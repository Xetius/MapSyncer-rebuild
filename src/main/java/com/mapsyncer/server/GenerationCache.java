package com.mapsyncer.server;

import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers when each region was generated and what its contents hash to.
 *
 * Used to decide what a client is missing:
 * - hashes match: skip it, the client already has this exact data
 * - hashes differ: compare timestamps, and send it if the client's copy is older
 *
 * Storage:
 * - in memory: relativePath -> RegionMeta
 * - on disk: generation_cache.properties
 * - line format: dimension/region_x_z = timestamp_seconds:hash
 */
public class GenerationCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(GenerationCache.class);

    /** The single instance. */
    private static volatile GenerationCache instance;

    /** Where the cache file lives. */
    private final Path cacheFile;

    /** The cache itself: relativePath -> RegionMeta. */
    private final Map<String, RegionMeta> cache = new ConcurrentHashMap<>();

    /**
     * One region's metadata: generation time in seconds plus its CRC32 hash.
     *
     * Equivalent to TimestampHashEntry; kept as its own type for compatibility.
     */
    public record RegionMeta(long timestampSeconds, String hash) {
        /**
         * Parses a stored cache value.
         *
         * @param value the stored string, in {@code timestamp:hash} form
         * @return the parsed metadata, or {@code null} if the string is malformed
         */
        public static RegionMeta parse(String value) {
            TimestampHashEntry entry = PropertiesCacheIO.parseTimestampHash(value);
            return entry != null ? new RegionMeta(entry.timestampSeconds(), entry.hash()) : null;
        }

        /**
         * Formats this for storage.
         *
         * @return the {@code timestamp:hash} string
         */
        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }

    /**
     * @param cacheDir directory holding the cache file
     *
     * @param cacheDir directory holding the cache file
     */
    private GenerationCache(Path cacheDir) {
        this.cacheFile = cacheDir.resolve("generation_cache.properties");
        load();
    }

    /**
     * Returns the shared instance, creating it against {@code cacheDir} on first call.
     *
     * @param cacheDir directory holding the cache file
     * @return the shared instance
     */
    public static GenerationCache getInstance(Path cacheDir) {
        if (instance == null) {
            synchronized (GenerationCache.class) {
                if (instance == null) {
                    instance = new GenerationCache(cacheDir);
                }
            }
        }
        return instance;
    }

    /**
     * Loads the cache from disk.
     *
     * Reads through PropertiesCacheIO.
     */
    private void load() {
        Map<String, TimestampHashEntry> loaded = PropertiesCacheIO.load(cacheFile, PropertiesCacheIO::parseTimestampHash);
        for (Map.Entry<String, TimestampHashEntry> entry : loaded.entrySet()) {
            cache.put(entry.getKey(), new RegionMeta(entry.getValue().timestampSeconds(), entry.getValue().hash()));
        }
    }

    /**
     * Writes the cache to disk.
     *
     * Writes through PropertiesCacheIO.
     */
    public void save() {
        Map<String, TimestampHashEntry> toSave = new HashMap<>();
        for (Map.Entry<String, RegionMeta> entry : getAll().entrySet()) {
            toSave.put(entry.getKey(), new TimestampHashEntry(entry.getValue().timestampSeconds(), entry.getValue().hash()));
        }
        PropertiesCacheIO.save(cacheFile, toSave, TimestampHashEntry::format,
            "Generation cache for map regions\nFormat: dimension/region_x_z = timestamp_seconds:hash\nHash is CRC32 of file content");
    }

    /**
     * Records the metadata of one region.
     *
     * @param relativePath path within the cache, e.g. {@code dimension/regionX_regionZ}
     * @param timestampSeconds generation time, in seconds
     * @param hash the region's CRC32 hash
     */
    public void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
    }

    /**
     * Records the metadata of one region, hashing the file itself.
     *
     * The CRC32 comes from HashUtils.
     *
     * @param relativePath path within the cache
     * @param filePath the generated file to hash
     * @param timestampSeconds generation time, in seconds
     */
    public void updateWithHash(String relativePath, Path filePath, long timestampSeconds) {
        String hash = HashUtils.computeFileHash(filePath);
        cache.put(relativePath, new RegionMeta(timestampSeconds, hash));
        LOGGER.debug("Updated cache for {}: ts={}, hash={}", relativePath, timestampSeconds, hash);
    }

    /**
     * Looks up one region's metadata.
     *
     * @param relativePath path within the cache
     * @return the metadata, or {@code null} if this region is not cached
     */
    public RegionMeta getMeta(String relativePath) {
        return cache.get(relativePath);
    }

    /**
     * All cached metadata.
     *
     * <p>An unmodifiable view rather than a copy, so reading it is cheap.</p>
     * <p>Use {@link #update} to change anything.</p>
     *
     * @return an unmodifiable view of the cache
     */
    public Map<String, RegionMeta> getAll() {
        return Collections.unmodifiableMap(new HashMap<>(cache));
    }

    /**
     * Whether a region needs sending to a client.
     *
     * The rules:
     * - not in the server cache: no, the server has nothing to send
     * - client has no metadata for it: yes, it is new to them
     * - hashes match: no, they already have this exact data
     * - client's timestamp is older than the server's: yes
     * - client's timestamp is newer than the server's: no
     *
     * @param relativePath path within the cache
     * @param clientMeta what the client reported for this region
     * @return {@code true} if the region should be sent
     */
    public boolean needsSync(String relativePath, RegionMeta clientMeta) {
        RegionMeta serverMeta = cache.get(relativePath);

        if (serverMeta == null) {
            return false;
        }

        if (clientMeta == null) {
            return true;
        }

        if (serverMeta.hash().equals(clientMeta.hash())) {
            LOGGER.debug("Skip sync {}: hash match", relativePath);
            return false;
        }

        if (clientMeta.timestampSeconds() < serverMeta.timestampSeconds()) {
            LOGGER.debug("Need sync {}: client ts={} < server ts={}",
                relativePath, clientMeta.timestampSeconds(), serverMeta.timestampSeconds());
            return true;
        }

        LOGGER.debug("Skip sync {}: client has newer data", relativePath);
        return false;
    }

    /**
     * Empties the cache.
     */
    public void clear() {
        cache.clear();
        save();
    }

    /**
     * Drops the instance.
     *
     * Clears the cache and releases the singleton; called when the server stops.
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.cache.clear();
            instance = null;
            LOGGER.info("GenerationCache instance reset");
        }
    }
}
