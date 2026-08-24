package com.mapsyncer.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Remembers the modification time of each MCA file, so changed regions can be spotted.
 *
 * Drives incremental updates:
 * - timestamp changed: that region's map data needs regenerating
 * - timestamp unchanged: skip it
 *
 * Stored as a properties file, so it stays readable when debugging.
 */
public class McaTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaTimestampCache.class);
    private static final String CACHE_FILE_NAME = "mca_timestamps.cache";
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mc[ar]$");

    /** dimension -> region coordinate -> last modified time, in milliseconds. */
    private final Map<String, Map<String, Long>> timestampCache = new ConcurrentHashMap<>();

    /** Region count at which stale entries are pruned. */
    private static final int MAX_CACHE_REGIONS = 50000;

    /** Where the cache file lives. */
    private final Path cacheFilePath;

    /** The single instance. */
    private static volatile McaTimestampCache instance;

    /**
     * Returns the shared instance, creating it against {@code baseDir} on first call.
     *
     * @param baseDir directory holding the cache file
     * @return the shared instance
     */
    public static McaTimestampCache getInstance(Path baseDir) {
        if (instance == null) {
            synchronized (McaTimestampCache.class) {
                if (instance == null) {
                    instance = new McaTimestampCache(baseDir);
                }
            }
        }
        return instance;
    }

    /**
     * @param baseDir directory holding the cache file
     *
     * @param baseDir directory holding the cache file
     */
    private McaTimestampCache(Path baseDir) {
        this.cacheFilePath = baseDir.resolve(CACHE_FILE_NAME);
        loadCache();
    }

    /**
     * Loads the cache from its properties file.
     *
     * Line format: dimension/region_x_z = timestamp_seconds
     */
    private void loadCache() {
        if (!Files.exists(cacheFilePath)) {
            LOGGER.info("No existing timestamp cache found, will create new one");
            return;
        }

        try (InputStream is = Files.newInputStream(cacheFilePath)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                try {
                    // Stored in seconds; convert back to milliseconds.
                    long timestampSeconds = Long.parseLong(props.getProperty(key));
                    long timestampMillis = timestampSeconds * 1000;
                    // Key format: "dimension/region_x_z".
                    String[] parts = key.split("/");
                    if (parts.length == 2) {
                        String dimension = parts[0];
                        String regionKey = parts[1];
                        timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                                     .put(regionKey, timestampMillis);
                    }
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid timestamp for {}: {}", key, props.getProperty(key));
                }
            }

            int totalRegions = timestampCache.values().stream().mapToInt(Map::size).sum();
            LOGGER.info("Loaded timestamp cache: {} dimensions, {} regions",
                timestampCache.size(), totalRegions);
        } catch (IOException e) {
            LOGGER.warn("Failed to load timestamp cache, will rebuild: {}", e.getMessage());
            timestampCache.clear();
        }
    }

    /**
     * Writes the cache to its properties file.
     *
     * Written to a temporary file and moved into place, so a partial write is never visible.
     */
    public void saveCache() {
        try {
            Files.createDirectories(cacheFilePath.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, Map<String, Long>> dimEntry : timestampCache.entrySet()) {
                String dimension = dimEntry.getKey();
                for (Map.Entry<String, Long> regionEntry : dimEntry.getValue().entrySet()) {
                    // Line format: dimension/region_x_z = timestamp (seconds)
                    String key = dimension + "/" + regionEntry.getKey();
                    // Stored in seconds, which reads better in the file.
                    long timestampSeconds = regionEntry.getValue() / 1000;
                    props.setProperty(key, String.valueOf(timestampSeconds));
                }
            }

            // Write to a temporary file, then move it into place.
            Path tempFile = cacheFilePath.resolveSibling(CACHE_FILE_NAME + ".temp");
            try (OutputStream os = Files.newOutputStream(tempFile)) {
                props.store(os, "MCA file modification timestamps (seconds since epoch)\nFormat: dimension/region_x_z = timestamp");
            }
            Files.move(tempFile, cacheFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            int totalRegions = timestampCache.values().stream().mapToInt(Map::size).sum();
            LOGGER.info("Saved timestamp cache: {} dimensions, {} regions to {}",
                timestampCache.size(), totalRegions, cacheFilePath);
        } catch (IOException e) {
            LOGGER.error("Failed to save timestamp cache: {}", e.getMessage());
        }
    }

    /**
     * Last modified time of an MCA file.
     * @param mcaPath the MCA file
     * @return the time in milliseconds, or -1 if the file does not exist
     */
    public long getFileTimestamp(Path mcaPath) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(mcaPath, BasicFileAttributes.class);
            FileTime lastModified = attrs.lastModifiedTime();
            return lastModified.toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Whether a region needs regenerating.
     * @param dimension dimension name
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @param mcaPath the MCA file
     * @return {@code true} if it should be regenerated
     */
    public boolean needsRegeneration(String dimension, int regionX, int regionZ, Path mcaPath) {
        if (!Files.exists(mcaPath)) {
            return false;  // No file, nothing to generate.
        }

        String regionKey = regionX + "_" + regionZ;
        long currentTimestamp = getFileTimestamp(mcaPath);

        if (currentTimestamp < 0) {
            return true;  // Cannot read the time; regenerate to be safe.
        }

        Map<String, Long> dimCache = timestampCache.get(dimension);
        if (dimCache == null) {
            LOGGER.debug("No cached timestamp for dimension {}, will regenerate", dimension);
            return true;  // Nothing cached for this dimension yet.
        }

        Long cachedTimestamp = dimCache.get(regionKey);
        if (cachedTimestamp == null) {
            LOGGER.debug("No cached timestamp for region {} in {}, will generate", regionKey, dimension);
            return true;  // Nothing cached for this region yet.
        }

        // Compare in seconds, since that is the precision the cache stores.
        long currentSeconds = currentTimestamp / 1000;
        long cachedSeconds = cachedTimestamp / 1000;
        if (currentSeconds > cachedSeconds) {
            LOGGER.info("Region {} in {} has been updated (cached={}s, current={}s), will regenerate",
                regionKey, dimension, cachedSeconds, currentSeconds);
            return true;  // File changed.
        }

        return false;  // File unchanged.
    }

    /**
     * Records the current timestamp of a region's MCA file.
     * @param dimension dimension name
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @param mcaPath the MCA file
     */
    public void updateTimestamp(String dimension, int regionX, int regionZ, Path mcaPath) {
        long timestamp = getFileTimestamp(mcaPath);
        if (timestamp < 0) {
            LOGGER.warn("Could not get timestamp for {}", mcaPath);
            return;
        }

        String regionKey = regionX + "_" + regionZ;
        timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>())
                      .put(regionKey, timestamp);

        // Warn once the cache grows past its limit.
        int totalRegions = getTotalCachedRegions();
        if (totalRegions > MAX_CACHE_REGIONS) {
            LOGGER.warn("Timestamp cache size {} exceeds limit {}, consider calling trimStaleEntries()",
                totalRegions, MAX_CACHE_REGIONS);
        }

        LOGGER.debug("Updated timestamp cache for {} / {}: {}", dimension, regionKey, timestamp);
    }

    /**
     * @return how many regions are cached in total
     */
    private int getTotalCachedRegions() {
        return timestampCache.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * Removes entries whose MCA files no longer exist.
     *
     * Worth calling once the cache passes its size limit; needs the region directory so it
     * can check which files are still there.
     *
     * @param dimension dimension name
     * @param regionDir the dimension's region directory
     */
    public void trimStaleEntries(String dimension, Path regionDir) {
        Map<String, Long> dimCache = timestampCache.get(dimension);
        if (dimCache == null || dimCache.isEmpty()) return;

        int before = dimCache.size();
        java.util.List<String> toRemove = new java.util.ArrayList<>();

        for (String regionKey : dimCache.keySet()) {
            String[] parts = regionKey.split("_");
            if (parts.length == 2) {
                try {
                    int regionX = Integer.parseInt(parts[0]);
                    int regionZ = Integer.parseInt(parts[1]);
                    Path mcaPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
                    if (!Files.exists(mcaPath)) {
                        toRemove.add(regionKey);
                    }
                } catch (NumberFormatException ignored) {
                    // Malformed key; drop it too.
                    toRemove.add(regionKey);
                }
            }
        }

        for (String key : toRemove) {
            dimCache.remove(key);
        }

        if (!toRemove.isEmpty()) {
            LOGGER.info("Trimmed {} stale timestamp entries for dimension {} (before: {}, after: {})",
                toRemove.size(), dimension, before, dimCache.size());
        }
    }

    /**
     * Scans a region directory and returns everything that changed since the last scan.
     * @param dimension dimension name
     * @param regionDir the dimension's region directory
     * @return the regions that need regenerating
     */
    public java.util.List<RegionScanner.RegionCoords> scanAndUpdate(String dimension, Path regionDir) {
        java.util.List<RegionScanner.RegionCoords> needsRegeneration = new java.util.ArrayList<>();
        Map<String, Long> dimCache = timestampCache.computeIfAbsent(dimension, k -> new ConcurrentHashMap<>());

        if (!Files.exists(regionDir)) {
            LOGGER.warn("Region directory not found: {}", regionDir);
            return needsRegeneration;
        }

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path mcaFile : stream) {
                String fileName = mcaFile.getFileName().toString();
                java.util.regex.Matcher matcher = REGION_FILE_PATTERN.matcher(fileName);

                if (matcher.matches()) {
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    String regionKey = regionX + "_" + regionZ;

                    long currentTimestamp = getFileTimestamp(mcaFile);
                    Long cachedTimestamp = dimCache.get(regionKey);

                    // Compare in seconds, since that is the precision the cache stores.
                    long currentSeconds = currentTimestamp / 1000;
                    long cachedSeconds = cachedTimestamp != null ? cachedTimestamp / 1000 : 0;

                    if (cachedTimestamp == null || currentSeconds > cachedSeconds) {
                        needsRegeneration.add(new RegionScanner.RegionCoords(regionX, regionZ));
                        dimCache.put(regionKey, currentTimestamp);

                        if (cachedTimestamp != null) {
                            LOGGER.info("Detected update in {} / {}: cached={}s, current={}s",
                                dimension, regionKey, cachedSeconds, currentSeconds);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan region directory: {}", regionDir, e);
        }

        return needsRegeneration;
    }

    /**
     * Empties the cache.
     */
    public void clearCache() {
        timestampCache.clear();
        try {
            Files.deleteIfExists(cacheFilePath);
            LOGGER.info("Cleared timestamp cache");
        } catch (IOException e) {
            LOGGER.warn("Failed to delete cache file: {}", e.getMessage());
        }
    }

    /**
     * Cache statistics.
     *
     * @return a human-readable summary
     */
    public String getCacheStats() {
        int totalDimensions = timestampCache.size();
        int totalRegions = timestampCache.values().stream().mapToInt(Map::size).sum();
        return String.format("Timestamp cache: %d dimensions, %d regions cached", totalDimensions, totalRegions);
    }

    /**
     * Drops the instance.
     *
     * Called when the server stops, so a restart does not keep the old one alive.
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.timestampCache.clear();
            instance = null;
            LOGGER.info("McaTimestampCache instance reset");
        }
    }
}
