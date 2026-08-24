package com.mapsyncer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * CRC32 hashing helpers.
 *
 * Shared by ClientHashManager and GenerationCache, which previously each had their own copy.
 */
public final class HashUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(HashUtils.class);

    /** Returned when a file is missing or cannot be read. */
    public static final String DEFAULT_HASH = "00000000";

    private HashUtils() {
        // Utility class; not instantiable.
    }

    /**
     * CRC32 of a file, read in chunks so large files do not spike memory.
     *
     * <p>Uses a fixed 8KB buffer rather than {@code Files.readAllBytes}.</p>
     *
     * @param filePath the file to hash
     * @return the CRC32 as eight hex characters, or {@code "00000000"} if the file is
     *         missing or unreadable
     */
    public static String computeFileHash(Path filePath) {
        if (filePath == null || !Files.exists(filePath)) {
            return DEFAULT_HASH;
        }

        CRC32 crc32 = new CRC32();
        byte[] buffer = new byte[8192];  // fixed 8KB buffer

        try (InputStream is = Files.newInputStream(filePath)) {
            int len;
            while ((len = is.read(buffer)) != -1) {
                crc32.update(buffer, 0, len);
            }
            return String.format("%08x", crc32.getValue());
        } catch (IOException e) {
            LOGGER.warn("Failed to compute hash for {}", filePath, e);
            return DEFAULT_HASH;
        }
    }

    /**
     * CRC32 of a byte array.
     *
     * @param data the bytes to hash
     * @return the CRC32 as eight hex characters
     */
    public static String computeHash(byte[] data) {
        if (data == null || data.length == 0) {
            return DEFAULT_HASH;
        }

        CRC32 crc32 = new CRC32();
        crc32.update(data);
        return String.format("%08x", crc32.getValue());
    }

    /**
     * Whether a hash is a real hash rather than the missing-file placeholder.
     *
     * @param hash the hash to check
     * @return {@code true} if the hash is usable
     */
    public static boolean isValidHash(String hash) {
        return hash != null && !hash.isEmpty() && !DEFAULT_HASH.equals(hash);
    }
}