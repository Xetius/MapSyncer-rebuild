package com.mapsyncer.mca;

import com.mapsyncer.nbt.NbtReader;
import com.mapsyncer.nbt.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads Minecraft region files (.mca). No dependencies.
 *
 *
 * <p>File layout:</p>
 * <ul>
 *   <li>0-4KB: location table, 32x32 chunk entries of 4 bytes each</li>
 *   <li>4-8KB: timestamp table, 32x32 chunk entries of 4 bytes each</li>
 *   <li>8KB onwards: chunk data, in 4KB sectors</li>
 * </ul>
 *
 * <p>Supported compression:</p>
 * <ul>
 *   <li>GZIP (type 1)</li>
 *   <li>ZLIB (type 2)</li>
 *   <li>uncompressed (type 3)</li>
 * </ul>
 *
 * <p>LZ4 (type 4) is not supported; it would need another dependency.</p>
 *
 * @see ChunkDataParser which parses a chunk's NBT
 * @see McaReader.ChunkLocation where a chunk lives in the file
 * @see McaReader.ChunkData one chunk's data
 */
public class McaReader implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(McaReader.class);
    /**
     * Sector size: 4KB.
     */
    private static final int SECTOR_SIZE = 4096;

    /**
     * Chunks per region: 32x32.
     */
    private static final int CHUNKS_PER_REGION = 32;

    private static final int MAX_CHUNK_DATA_LENGTH = 4 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_NBT_LENGTH = 16 * 1024 * 1024;

    // Compression types
    /**
     * GZIP compression.
     */
    private static final int COMPRESS_GZIP = 1;

    /**
     * ZLIB compression.
     */
    private static final int COMPRESS_ZLIB = 2;

    /**
     * No compression.
     */
    private static final int COMPRESS_NONE = 3;

    /**
     * LZ4 compression, which is not supported.
     */
    private static final int COMPRESS_LZ4 = 4;

    /**
     * Where a chunk lives in the file.
     *
     * <p>Its position plus metadata:</p>
     * <ul>
     *   <li>offsetSectors: sector the data starts at</li>
     *   <li>sectorCount: how many sectors it occupies</li>
     *   <li>timestamp: when the chunk was last modified</li>
     * </ul>
     */
    public record ChunkLocation(int offsetSectors, int sectorCount, int timestamp) {
        /**
         * Whether the chunk is present at all.
         *
         * @return {@code true} if both offsetSectors and sectorCount are above zero
         */
        public boolean exists() {
            return offsetSectors > 0 && sectorCount > 0;
        }

        /**
         * The chunk's byte offset in the file.
         *
         * @return offsetSectors * SECTOR_SIZE
         */
        public long dataOffset() {
            return (long) offsetSectors * SECTOR_SIZE;
        }
    }

    /**
     * One chunk's data.
     *
     * @param chunkX chunk X within the region, 0-31
     * @param chunkZ chunk Z within the region, 0-31
     * @param nbt the chunk's NBT
     */
    public record ChunkData(int chunkX, int chunkZ, Tag.Compound nbt) {}

    /**
     * The open region file.
     */
    private final RandomAccessFile raf;

    /**
     * Opens a region file.
     *
     * @param path the .mca file
     * @throws IOException if the file is missing, too small, or unreadable
     */
    public McaReader(String path) throws IOException {
        this.raf = new RandomAccessFile(path, "r");
        if (raf.length() < SECTOR_SIZE * 2) {
            throw new IOException("MCA file too small: " + raf.length() + " bytes");
        }
    }

    /**
     * Where a chunk lives in the file.
     *
     * <p>Read from the location and timestamp tables.</p>
     *
     * @param localX chunk X within the region, 0-31
     * @param localZ chunk Z within the region, 0-31
     * @return its offset, sector count and timestamp
     * @throws IOException if the file cannot be read
     */
    public ChunkLocation getChunkLocation(int localX, int localZ) throws IOException {
        int index = (localX + localZ * CHUNKS_PER_REGION) * 4;
        raf.seek(index);

        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        int offsetSectors = (b0 << 16) | (b1 << 8) | b2;
        int sectorCount = raf.readUnsignedByte();

        // The timestamp table.
        raf.seek(SECTOR_SIZE + index);
        int timestamp = raf.readInt();

        return new ChunkLocation(offsetSectors, sectorCount, timestamp);
    }

    /**
     * Reads one chunk's NBT.
     *
     * <p>The steps:</p>
     * <ol>
     *   <li>find where the chunk lives</li>
     *   <li>read its compressed length and compression type</li>
     *   <li>decompress it</li>
     *   <li>parse the NBT</li>
     * </ol>
     *
     * @param localX chunk X within the region, 0-31
     * @param localZ chunk Z within the region, 0-31
     * @return the chunk's NBT, or {@code null} if it is absent or unreadable
     * @throws IOException if reading or decompression fails
     */
    public Tag.Compound readChunkNbt(int localX, int localZ) throws IOException {
        ChunkLocation loc = getChunkLocation(localX, localZ);
        if (!loc.exists()) {
            return null;
        }

        long dataOffset = loc.dataOffset();
        if (dataOffset + 5 > raf.length()) {
            return null;
        }

        raf.seek(dataOffset);

        // Data length, which includes the compression type byte.
        int totalLength = raf.readInt();
        if (totalLength <= 1) {
            return null;
        }
        if (totalLength > MAX_CHUNK_DATA_LENGTH) {
            throw new IOException("Chunk data length too large: " + totalLength + " bytes");
        }

        // The compression type.
        int compressionType = raf.readUnsignedByte();

        // The compressed data.
        int dataLength = totalLength - 1;
        byte[] compressedData = new byte[dataLength];
        int read = 0;
        while (read < dataLength) {
            int r = raf.read(compressedData, read, dataLength - read);
            if (r == -1) break;
            read += r;
        }
        if (read != dataLength) {
            return null;
        }

        // Decompress.
        byte[] nbtData = decompress(compressedData, compressionType);
        if (nbtData == null) {
            return null;
        }

        // And parse.
        try (NbtReader reader = new NbtReader(new ByteArrayInputStream(nbtData))) {
            return reader.readDocument();
        }
    }

    /**
     * Reads every chunk present in the region.
     *
     * <p>Walks all 32x32 slots and reads whichever chunks are there.</p>
     * <p>A chunk that fails to read is logged and skipped rather than aborting the lot.</p>
     *
     * @return every chunk that could be read
     * @throws IOException if the file cannot be opened or read
     */
    public Iterable<ChunkData> readAllChunks() throws IOException {
        java.util.List<ChunkData> chunks = new java.util.ArrayList<>();

        for (int localX = 0; localX < CHUNKS_PER_REGION; localX++) {
            for (int localZ = 0; localZ < CHUNKS_PER_REGION; localZ++) {
                try {
                    Tag.Compound nbt = readChunkNbt(localX, localZ);
                    if (nbt != null) {
                        chunks.add(new ChunkData(localX, localZ, nbt));
                    }
                } catch (IOException e) {
                    // One bad chunk does not abort the rest.
                    LOGGER.warn("Failed to read chunk ({}, {}): {}", localX, localZ, e.getMessage());
                }
            }
        }

        return chunks;
    }

    public void forEachChunk(ChunkConsumer consumer) throws IOException {
        for (int localX = 0; localX < CHUNKS_PER_REGION; localX++) {
            for (int localZ = 0; localZ < CHUNKS_PER_REGION; localZ++) {
                try {
                    Tag.Compound nbt = readChunkNbt(localX, localZ);
                    if (nbt != null) {
                        consumer.accept(new ChunkData(localX, localZ, nbt));
                    }
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Failed to read chunk ({}, {}): {}", localX, localZ, e.getMessage());
                } catch (OutOfMemoryError e) {
                    LOGGER.error("Out of memory reading chunk ({}, {}); skipped it", localX, localZ);
                }
            }
        }
    }

    /**
     * Decompresses chunk data.
     *
     * <p>By compression type:</p>
     * <ul>
     *   <li>GZIP (1): GZIPInputStream</li>
     *   <li>ZLIB (2): InflaterInputStream</li>
     *   <li>uncompressed (3): returned as-is</li>
     *   <li>LZ4 (4): unsupported, throws</li>
     * </ul>
     *
     * @param data the compressed bytes
     * @param compressionType the compression type, 1-4
     * @return the decompressed NBT bytes
     * @throws IOException if decompression fails or the type is unsupported
     */
    private byte[] decompress(byte[] data, int compressionType) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        switch (compressionType) {
            case COMPRESS_GZIP:
                try (GZIPInputStream gis = new GZIPInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = gis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                        checkDecompressedSize(baos.size());
                    }
                }
                return baos.toByteArray();

            case COMPRESS_ZLIB:
                try (InflaterInputStream iis = new InflaterInputStream(bais)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = iis.read(buf)) > 0) {
                        baos.write(buf, 0, len);
                        checkDecompressedSize(baos.size());
                    }
                }
                return baos.toByteArray();

            case COMPRESS_NONE:
                checkDecompressedSize(data.length);
                return data;

            case COMPRESS_LZ4:
                // LZ4 would need another dependency.
                throw new IOException("LZ4 compression is not supported; use GZIP or ZLIB region files");

            default:
                throw new IOException("Unknown compression type: " + compressionType);
        }
    }

    private void checkDecompressedSize(int size) throws IOException {
        if (size > MAX_DECOMPRESSED_NBT_LENGTH) {
            throw new IOException("Decompressed chunk NBT too large: " + size + " bytes");
        }
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(ChunkData chunkData) throws IOException;
    }

    /**
     * Closes the region file.
     *
     * <p>Implements AutoCloseable, so this works with try-with-resources.</p>
     *
     * @throws IOException if closing fails
     */
    @Override
    public void close() throws IOException {
        raf.close();
    }
}
