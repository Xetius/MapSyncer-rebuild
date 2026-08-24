package com.mapsyncer.client;

import com.mapsyncer.util.PropertiesCacheIO;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Remembers the region timestamps the server sent, and how far the last sync got.
 * Compared on the next sync, so a region is not re-fetched just because the local file's
 * modification time changed.
 *
 * <p>The file, which holds both state and timestamps:</p>
 * <pre>
 * # Sync timestamps cache
 * _state = in_progress
 * _dimensions = null, DIM-1
 * _command = /mapsyncer sync all
 * null/0_0 = 1234567890:abc12345
 * null/1_0 = 1234567891:def45678
 * </pre>
 *
 * <p>Sync state, kept deliberately simple:</p>
 * <ul>
 *   <li>two states only: in_progress and completed</li>
 *   <li>set to in_progress when a sync starts</li>
 *   <li>set to completed when it finishes</li>
 *   <li>disconnecting changes nothing, so it stays in_progress</li>
 *   <li>on joining, an in_progress state prompts the player to resume</li>
 *   <li>no cache file at all means this world has never been synced, so nothing is checked</li>
 * </ul>
 */
public class ClientTimestampCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientTimestampCache.class);

    /** The cache file, which holds both the state and the timestamps. */
    private static final String CACHE_FILE_NAME = "sync_timestamps.cache";

    /** Prefix marking the state keys apart from the region entries. */
    private static final String KEY_STATE = "_state";
    private static final String KEY_DIMENSIONS = "_dimensions";
    private static final String KEY_COMMAND = "_command";
    private static final int SAVE_EVERY_UPDATES = 50;
    private static final long SAVE_EVERY_MS = 10_000L;

    /** Sync state: in progress, so it can be resumed. */
    public static final String SYNC_STATE_IN_PROGRESS = "in_progress";

    /** Sync state: finished. */
    public static final String SYNC_STATE_COMPLETED = "completed";

    /** The single instance. */
    private static volatile ClientTimestampCache instance;

    /** The server directory last used. */
    private static volatile Path lastBaseDir = null;

    /**
     * The server directory last used.
     *
     * @return that directory, or {@code null} if there is none
     */
    public static Path getLastBaseDir() {
        return lastBaseDir;
    }

    /** Path to the cache file. */
    private final Path cacheFile;

    /** The cache: relative path, e.g. "null/0_0", to its entry. */
    private final Map<String, CacheEntry> cache = new HashMap<>();

    /** The current sync state; {@code null} means this world has never been synced. */
    private volatile String syncState = null;

    /** Dimensions covered by the sync. */
    private volatile Set<String> syncDimensions = new HashSet<>();

    /** The command that started the sync, quoted back when offering to resume. */
    private volatile String syncCommand = "";
    private int unsavedUpdates = 0;
    private long lastSaveMillis = 0L;

    /**
     * One cache entry: a timestamp in seconds plus the CRC32 hash.
     *
     * @param timestampSeconds the timestamp, in seconds
     * @param hash the CRC32, as eight hex characters
     */
    public record CacheEntry(long timestampSeconds, String hash) {
        /**
         * Parses a stored entry.
         */
        public static CacheEntry parse(String value) {
            TimestampHashEntry entry = PropertiesCacheIO.parseTimestampHash(value);
            return entry != null ? new CacheEntry(entry.timestampSeconds(), entry.hash()) : null;
        }

        /**
         * Formats this entry for storage.
         */
        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }

    /**
     * Creates the cache for one server directory.
     */
    private ClientTimestampCache(Path baseDir) {
        this.cacheFile = baseDir.resolve(CACHE_FILE_NAME);
        load();
        this.lastSaveMillis = System.currentTimeMillis();
    }

    /**
     * The shared instance.
     */
    public static ClientTimestampCache getInstance(Path baseDir) {
        if (baseDir == null) {
            return instance;
        }

        if (instance == null || lastBaseDir == null || !lastBaseDir.equals(baseDir)) {
            synchronized (ClientTimestampCache.class) {
                if (instance == null || lastBaseDir == null || !lastBaseDir.equals(baseDir)) {
                    instance = new ClientTimestampCache(baseDir);
                    lastBaseDir = baseDir;
                    LOGGER.info("ClientTimestampCache initialized for baseDir: {}", baseDir);
                }
            }
        }
        return instance;
    }

    /**
     * Drops the instance.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.clearInMemory();
            instance = null;
            lastBaseDir = null;
            LOGGER.info("ClientTimestampCache instance reset");
        }
    }

    public static synchronized void saveCurrent() {
        if (instance != null) {
            instance.save();
        }
    }

    /**
     * Loads the cache file: state and region timestamps.
     */
    private void load() {
        if (!Files.exists(cacheFile)) {
            syncState = null;
            LOGGER.info("Cache file not found, never synced before");
            return;
        }

        try {
            Properties props = new Properties();
            try (var in = Files.newInputStream(cacheFile)) {
                props.load(in);
            }

            // The state keys.
            syncState = props.getProperty(KEY_STATE, null);
            String dimsStr = props.getProperty(KEY_DIMENSIONS, "");
            syncDimensions = new HashSet<>();
            if (!dimsStr.isEmpty()) {
                for (String dim : dimsStr.split(",")) {
                    syncDimensions.add(dim.trim());
                }
            }
            syncCommand = props.getProperty(KEY_COMMAND, "");

            // And the region entries.
            for (String key : props.stringPropertyNames()) {
                if (!key.startsWith("_")) {
                    CacheEntry entry = CacheEntry.parse(props.getProperty(key));
                    if (entry != null) {
                        cache.put(key, entry);
                    }
                }
            }

            LOGGER.info("Loaded cache: state={}, regions={}, file={}", syncState, cache.size(), cacheFile.getFileName());
        } catch (Exception e) {
            LOGGER.warn("Failed to load cache file: {}", e.getMessage());
            syncState = null;
        }
    }

    /**
     * Writes the cache file: state and region timestamps.
     */
    public synchronized void save() {
        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();

            // The state.
            if (syncState != null) {
                props.setProperty(KEY_STATE, syncState);
            }
            props.setProperty(KEY_DIMENSIONS, String.join(",", syncDimensions));
            props.setProperty(KEY_COMMAND, syncCommand);

            // The region entries.
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), entry.getValue().format());
            }

            try (var out = Files.newOutputStream(cacheFile)) {
                // State first.
                StringBuilder content = new StringBuilder();
                content.append("# Sync timestamps cache\n");
                content.append("# ==================== STATE ====================\n");
                if (syncState != null) {
                    content.append(KEY_STATE).append("=").append(syncState).append("\n");
                }
                content.append(KEY_DIMENSIONS).append("=").append(String.join(",", syncDimensions)).append("\n");
                content.append(KEY_COMMAND).append("=").append(syncCommand).append("\n");
                content.append("\n");
                content.append("# ==================== TIMESTAMP CACHE ====================\n");
                content.append("# Format: dimension/region_x_z = timestamp_seconds:hash\n");

                // Then the regions.
                for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                    content.append(entry.getKey()).append("=").append(entry.getValue().format()).append("\n");
                }

                out.write(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            LOGGER.debug("Saved cache: state={}, regions={}", syncState, cache.size());
            unsavedUpdates = 0;
            lastSaveMillis = System.currentTimeMillis();
        } catch (Exception e) {
            LOGGER.warn("Failed to save cache file: {}", e.getMessage());
        }
    }

    public synchronized void saveDeferred() {
        unsavedUpdates++;
        long now = System.currentTimeMillis();
        if (unsavedUpdates >= SAVE_EVERY_UPDATES || now - lastSaveMillis >= SAVE_EVERY_MS) {
            save();
        }
    }

    /**
     * Marks a sync as started.
     */
    public synchronized void markSyncStart(Set<String> dimensions, String command) {
        syncState = SYNC_STATE_IN_PROGRESS;
        syncDimensions = new HashSet<>(dimensions);
        syncCommand = command;
        save();
        LOGGER.info("Marked sync start: dimensions={}, command={}", dimensions, command);
    }

    /**
     * Marks a sync as finished.
     */
    public synchronized void markSyncComplete() {
        syncState = SYNC_STATE_COMPLETED;
        save();
        LOGGER.info("Marked sync complete");
    }

    /**
     * Clears the sync state, when the player declines to resume.
     */
    public synchronized void clearSyncState() {
        syncState = SYNC_STATE_COMPLETED;
        syncDimensions.clear();
        syncCommand = "";
        save();
        LOGGER.info("Cleared sync state (marked as completed)");
    }

    /**
     * The current sync state.
     */
    public synchronized String getSyncState() {
        return syncState;
    }

    /**
     * The command that started the sync.
     */
    public synchronized String getSyncCommand() {
        return syncCommand;
    }

    /**
     * Whether there is a sync worth resuming.
     */
    public synchronized boolean needsResume() {
        return SYNC_STATE_IN_PROGRESS.equals(syncState);
    }

    /**
     * The dimensions covered by the sync.
     */
    public synchronized Set<String> getSyncDimensions() {
        return new HashSet<>(syncDimensions);
    }

    /**
     * Records a region's timestamp and hash.
     */
    public synchronized void update(String relativePath, long timestampSeconds, String hash) {
        cache.put(relativePath, new CacheEntry(timestampSeconds, hash));
    }

    /**
     * Looks up a region's entry.
     */
    public synchronized CacheEntry get(String relativePath) {
        return cache.get(relativePath);
    }

    /**
     * Every cached entry.
     *
     * <p>An unmodifiable view rather than a copy, so reading it is cheap.</p>
     * <p>Use {@link #update} to change anything.</p>
     */
    public synchronized Map<String, CacheEntry> getAll() {
        return new HashMap<>(cache);
    }

    /**
     * Empties the cache.
     */
    public synchronized void clear() {
        clearInMemory();
        try {
            Files.deleteIfExists(cacheFile);
            LOGGER.info("Cleared cache");
        } catch (Exception e) {
            LOGGER.warn("Failed to delete cache file: {}", e.getMessage());
        }
    }

    private void clearInMemory() {
        cache.clear();
        syncState = null;
        syncDimensions.clear();
        syncCommand = "";
    }

    /**
     * Whether a dimension has been synced before.
     */
    public synchronized boolean hasDimensionSynced(String xaeroDim) {
        String prefix = xaeroDim + "/";
        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the cache file exists.
     */
    public boolean cacheFileExists() {
        return Files.exists(cacheFile);
    }

    /**
     * Path to the cache file.
     */
    public Path getCacheFile() {
        return cacheFile;
    }
}
