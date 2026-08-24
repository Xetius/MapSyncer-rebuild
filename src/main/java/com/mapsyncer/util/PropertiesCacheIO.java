package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

/**
 * Reads and writes cache files in {@code .properties} format.
 *
 * Shared by ClientTimestampCache and GenerationCache, which previously each had their own
 * copy. Values are of any type, converted through a parser and a formatter.
 */
public final class PropertiesCacheIO {

    private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesCacheIO.class);

    /**
     * Utility class; not instantiable.
     */
    private PropertiesCacheIO() {
        // Utility class; not instantiable.
    }

    /**
     * Loads a cache from a properties file.
     *
     * @param cacheFile the file to read
     * @param parser converts a stored string into a value
     * @return the loaded entries, empty if the file is missing or unreadable
     */
    public static <T> Map<String, T> load(Path cacheFile, Function<String, T> parser) {
        Map<String, T> cache = new HashMap<>();

        if (cacheFile == null || !Files.exists(cacheFile)) {
            LOGGER.info("Cache file not found: {}", cacheFile);
            return cache;
        }

        try (InputStream is = Files.newInputStream(cacheFile)) {
            Properties props = new Properties();
            props.load(is);

            for (String key : props.stringPropertyNames()) {
                T value = parser.apply(props.getProperty(key));
                if (value != null) {
                    cache.put(key, value);
                } else {
                    LOGGER.warn("Invalid cache entry for {}: {}", key, props.getProperty(key));
                }
            }

            LOGGER.info("Loaded {} entries from cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to load cache file: {}", cacheFile, e);
        }

        return cache;
    }

    /**
     * Writes a cache to a properties file.
     *
     * @param cacheFile the file to write
     * @param cache the entries to store
     * @param formatter converts a value into its stored string
     * @param header comment written at the top of the file
     */
    public static <T> void save(Path cacheFile, Map<String, T> cache, Function<T, String> formatter, String header) {
        if (cacheFile == null) {
            LOGGER.warn("Cache file path is null, skip saving");
            return;
        }

        try {
            Files.createDirectories(cacheFile.getParent());

            Properties props = new Properties();
            for (Map.Entry<String, T> entry : cache.entrySet()) {
                props.setProperty(entry.getKey(), formatter.apply(entry.getValue()));
            }

            try (OutputStream os = Files.newOutputStream(cacheFile)) {
                props.store(os, header != null ? header : "Cache file");
            }

            LOGGER.info("Saved {} entries to cache file: {}", cache.size(), cacheFile.getFileName());
        } catch (IOException e) {
            LOGGER.error("Failed to save cache file: {}", cacheFile, e);
        }
    }

    /**
     * Parses a {@code "timestamp_seconds:hash"} cache value.
     *
     * @param value the stored string, e.g. {@code "1234567890:abc12345"}
     * @return the parsed entry, or {@code null} if the string is malformed
     */
    public static TimestampHashEntry parseTimestampHash(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        String[] parts = value.split(":");
        if (parts.length == 2) {
            try {
                long ts = Long.parseLong(parts[0]);
                return new TimestampHashEntry(ts, parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * One cache entry: a timestamp plus the file's hash.
     */
    public record TimestampHashEntry(long timestampSeconds, String hash) {
        /**
         * @return this entry as its stored string form
         */
        public String format() {
            return timestampSeconds + ":" + hash;
        }
    }
}