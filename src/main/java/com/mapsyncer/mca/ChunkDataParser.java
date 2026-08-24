package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a chunk's NBT.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>the heightmap, used by surface scans</li>
 *   <li>the chunk status, so only generated terrain is processed</li>
 *   <li>every section's blocks and biomes</li>
 *   <li>looking up and combining light values</li>
 * </ul>
 *
 * <p>Handles both 1.18+ chunks (flat root) and older ones (nested under "Level").</p>
 *
 * @see ChunkSectionParser which parses a single section
 * @see ChunkInfo a parsed chunk
 * @see LightStats light statistics
 */
public class ChunkDataParser {

    /**
     * Chunk statuses worth reading.
     *
     * <p>Follows Xaero's WorldDataReader, which accepts anything at FEATURES or later.</p>
     *
     * <p>Status order:</p>
     * <pre>empty -> structure_starts -> structure_references -> biomes -> noise -> surface -> features -> light -> spawn -> heightmaps -> full</pre>
     *
     * <p>Xaero, in WorldDataReader.java lines 333-340:</p>
     * <ul>
     *   <li>below BIOMES: return false, i.e. skip</li>
     *   <li>handleChunkBiomes() handles the biome data</li>
     *   <li>below FEATURES: return false, i.e. skip</li>
     * </ul>
     *
     * <p>So FEATURES and later are accepted, namely:</p>
     * <ul>features, light, spawn, heightmaps, full</ul>
     */
    private static final Set<String> ACCEPTABLE_STATUSES = Set.of(
        "minecraft:features",
        "minecraft:light",
        "minecraft:spawn",
        "minecraft:heightmaps",
        "minecraft:full",
        // The same names without a namespace.
        "features",
        "light",
        "spawn",
        "heightmaps",
        "full"
    );

    /**
     * Whether a chunk should be skipped because of its status.
     *
     * <p>Follows Xaero's WorldDataReader, which accepts anything at FEATURES or later.</p>
     *
     * @param status the chunk status
     * @return {@code true} to skip the chunk, {@code false} to read it
     */
    private static boolean shouldSkipChunk(String status) {
        if (status == null || status.isEmpty()) {
            return true;  // no status, so skip it
        }

        // Accept the status with or without a namespace.
        String normalizedStatus = status.contains(":") ? status : "minecraft:" + status;

        // FEATURES and later are worth reading.
        return !ACCEPTABLE_STATUSES.contains(normalizedStatus);
    }

    /**
     * A parsed chunk.
     *
     * <p>Everything read out of the chunk's NBT.</p>
     *
     * @param chunkX chunk X within the region, 0-31
     * @param chunkZ chunk Z within the region, 0-31
     * @param yPos the chunk's bottom section index, so yPos * 16 is chunkBottomY
     * @param chunkBottomY the chunk's bottom, as a world Y
     * @param status the chunk status, e.g. "minecraft:full"
     * @param heightmap 16x16 heightmap, in absolute world Y
     * @param sections every section, ordered from the top down
     */
    public record ChunkInfo(
        int chunkX,                 // chunk X within the region, 0-31
        int chunkZ,
        int yPos,                   // bottom section index, so yPos * 16 is chunkBottomY
        int chunkBottomY,           // the chunk's bottom, as a world Y
        String status,              // chunk status, e.g. "minecraft:full"
        int[][] heightmap,          // 16x16 heightmap, in absolute world Y
        List<ChunkSectionParser.SectionData> sections
    ) {}

    /**
     * Parses a chunk's NBT.
     *
     * <p>Handles both 1.18+ chunks (flat root) and older ones (nested under "Level").</p>
     *
     * <p>The steps:</p>
     * <ol>
     *   <li>check the status and skip chunks without generated terrain</li>
     *   <li>parse the heightmap</li>
     *   <li>parse every section and order them by Y</li>
     * </ol>
     *
     * @param localX chunk X within the region, 0-31
     * @param localZ chunk Z within the region, 0-31
     * @param chunkNbt the chunk's NBT
     * @param worldHeightRange the dimension's height range, worldTopY - minBuildHeight
     * @return the parsed chunk, or {@code null} if it is not worth reading
     */
    public static ChunkInfo parseChunk(int localX, int localZ, Tag.Compound chunkNbt,
                                       int minBuildHeight, int worldHeightRange) {
        // Only chunks with generated terrain are worth reading.
        // Generation order: empty, structure_starts, structure_references, biomes, noise, surface, ...
        // Terrain exists from surface onwards.
        String status = chunkNbt.getString("Status");

        // Skip the early statuses.
        if (shouldSkipChunk(status)) {
            return null;
        }

        // 1.18+ chunks keep sections at the root.
        Tag.Compound rootTag;
        if (chunkNbt.contains("sections", Tag.TAG_LIST)) {
            rootTag = chunkNbt;
        } else if (chunkNbt.contains("Level", Tag.TAG_COMPOUND)) {
            // Older chunks nest everything under Level.
            rootTag = chunkNbt.getCompound("Level");
        } else {
            return null;  // unrecognised format
        }

        // yPos, on 1.18+.
        int yPos = chunkNbt.getInt("yPos");
        int chunkBottomY = yPos * 16;

        // The heightmap, which needs the dimension's height range.
        int[][] heightmap = parseHeightmap(rootTag, minBuildHeight, worldHeightRange);

        // The sections.
        List<ChunkSectionParser.SectionData> sections = new ArrayList<>();
        if (rootTag.contains("sections", Tag.TAG_LIST)) {
            Tag.ListTag sectionsList = rootTag.getList("sections", Tag.TAG_COMPOUND);
            for (int i = 0; i < sectionsList.items().size(); i++) {
                Tag.Compound sectionTag = (Tag.Compound) sectionsList.items().get(i);
                ChunkSectionParser.SectionData section = ChunkSectionParser.parseSection(sectionTag);
                sections.add(section);
            }
        }

        // Ordered from the top down, which is how scanning walks them.
        sections.sort((a, b) -> Integer.compare(b.sectionY(), a.sectionY()));

        return new ChunkInfo(localX, localZ, yPos, chunkBottomY, status, heightmap, sections);
    }

    
    /**
     * Parses the heightmap.
     *
     * <p>Several formats:</p>
     * <ul>
     *   <li>1.18+ WORLD_SURFACE (LongArray)</li>
     *   <li>MOTION_BLOCKING_NO_LEAVES, which counts water</li>
     *   <li>the old HeightMap IntArray</li>
     * </ul>
     *
     * <p>MOTION_BLOCKING_NO_LEAVES is preferred, since it spots water above the terrain.</p>
     *
     * @param rootTag the chunk's root NBT
     * @param chunkBottomY the chunk's bottom, as a world Y
     * @param worldHeightRange the dimension's height range, used to derive bitsPerHeight
     * @return a 16x16 heightmap in absolute world Y
     */
    private static int[][] parseHeightmap(Tag.Compound rootTag, int minBuildHeight, int worldHeightRange) {
        int[][] heightmap = new int[16][16];
        for (int x = 0; x < 16; x++) {
            java.util.Arrays.fill(heightmap[x], minBuildHeight);
        }

        // The current Heightmaps compound.
        if (rootTag.contains("Heightmaps", Tag.TAG_COMPOUND)) {
            Tag.Compound heightmaps = rootTag.getCompound("Heightmaps");

        // MOTION_BLOCKING_NO_LEAVES first, since it counts water,
        // which is what spots water lying above the terrain.
            if (heightmaps.contains("MOTION_BLOCKING_NO_LEAVES", Tag.TAG_LONG_ARRAY)) {
                long[] data = heightmaps.getLongArray("MOTION_BLOCKING_NO_LEAVES");
                int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
                if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
                    decodeHeightmapLongArray(data, bitsPerHeight, minBuildHeight, heightmap);
                    return heightmap;
                }
            }

        // WORLD_SURFACE otherwise, which does not count water.
            if (heightmaps.contains("WORLD_SURFACE", Tag.TAG_LONG_ARRAY)) {
                long[] data = heightmaps.getLongArray("WORLD_SURFACE");
                int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
                if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
                    decodeHeightmapLongArray(data, bitsPerHeight, minBuildHeight, heightmap);
                    return heightmap;
                }
            }
        }

        // The old HeightMap IntArray.
        if (rootTag.contains("HeightMap", Tag.TAG_INT_ARRAY)) {
            int[] data = rootTag.getIntArray("HeightMap");
            if (data.length == 256) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        heightmap[x][z] = data[z * 16 + x];  // already an absolute world Y
                    }
                }
                return heightmap;
            }
        }

        // No heightmap at all: fall back to defaults.
        return heightmap;
    }

    /**
     * Works out the bits per entry of a heightmap.
     *
     * <p>Derived as the wiki describes:</p>
     * <ul>
     *   <li>h = highest height minus the lowest build height, i.e. the dimension's range</li>
     *   <li>b = ceil(log2(h))</li>
     *   <li>u = floor(64/b)</li>
     *   <li>l = ceil(256/u)</li>
     * </ul>
     *
     * @param longArrayLength length of the LongArray
     * @param worldHeightRange the dimension's height range
     * @return the bits per height value
     */
    private static int calculateBitsPerHeight(int longArrayLength, int worldHeightRange) {
        // Deriving it from the height range is the accurate way.
        if (worldHeightRange > 0) {
            // b = ceil(log2(h))
            return 32 - Integer.numberOfLeadingZeros(worldHeightRange - 1);
        }

        // Failing that, work backwards from the array length.
        // l = ceil(256/u) => u ≈ ceil(256/l)
        // b = floor(64/u)
        if (longArrayLength <= 0) return 0;
        int u = (256 + longArrayLength - 1) / longArrayLength; // ceil(256/l)
        return 64 / u;
    }

    /**
     * Decodes a LongArray heightmap, as the wiki specifies.
     *
     * <p>Values are stored as offsets from the dimension's lowest build height.</p>
     *
     * <p>The formula:</p>
     * <ul>
     *   <li>index i = x + 16*z</li>
     *   <li>value = (data[i/u] >> ((i%u)*b)) & ((1L<<b)-1L) + low</li>
     * </ul>
     *
     * @param data the long array
     * @param bitsPerHeight bits per height value
     * @param minBuildHeight the dimension's lowest build height, the baseline
     * @param heightmap the array to fill in
     */
    private static void decodeHeightmapLongArray(long[] data, int bitsPerHeight, int minBuildHeight, int[][] heightmap) {
        if (data == null || data.length == 0 || bitsPerHeight <= 0) {
            return;
        }

        // u = floor(64/b)
        int u = 64 / bitsPerHeight;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // Wiki: i = x + 16*z
                int i = x + 16 * z;

                // value = (data[i/u] >> ((i%u)*b)) & ((1L<<b)-1L)
                int longIndex = i / u;
                int bitOffset = (i % u) * bitsPerHeight;

                if (longIndex >= data.length) {
                    heightmap[x][z] = minBuildHeight;
                    continue;
                }

                long rawValue = (data[longIndex] >>> bitOffset) & ((1L << bitsPerHeight) - 1L);
                heightmap[x][z] = minBuildHeight + (int) rawValue;

            }
        }
    }

    /**
     * The full block state at a position.
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param worldY world Y
     * @param z local Z, 0-15
     * @return the block state
     */
    public static ChunkSectionParser.BlockState getBlockStateAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getBlockStateAt(section, x, localY, z);
            }
        }

        return new ChunkSectionParser.BlockState("minecraft:air", Map.of());
    }

    /**
     * The biome at a position, with boundary smoothing.
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param worldY world Y
     * @param z local Z, 0-15
     * @return the biome name
     */
    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z) {
        return getBiomeAt(chunk, x, worldY, z, true);
    }

    /**
     * The biome at a position, with boundary smoothing optional.
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param worldY world Y
     * @param z local Z, 0-15
     * @param smoothBoundary whether to smooth across voxel boundaries
     * @return the biome name
     */
    public static String getBiomeAt(ChunkInfo chunk, int x, int worldY, int z, boolean smoothBoundary) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getBiomeAt(section, x, localY, z, smoothBoundary);
            }
        }

        return null;
    }

    /**
     * The block light at a position.
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param worldY world Y
     * @param z local Z, 0-15
     * @return the block light, 0-15
     */
    public static byte getBlockLightAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getBlockLight(section, x, localY, z);
            }
        }

        return 0;
    }

    /**
     * The sky light at a position.
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param worldY world Y
     * @param z local Z, 0-15
     * @return the sky light, 0-15
     */
    public static byte getSkyLightAt(ChunkInfo chunk, int x, int worldY, int z) {
        int sectionY = worldY >> 4;
        int localY = worldY & 0xF;

        for (ChunkSectionParser.SectionData section : chunk.sections()) {
            if (section.sectionY() == sectionY) {
                return ChunkSectionParser.getSkyLight(section, x, localY, z);
            }
        }

        return 0;
    }

    /**
     * Whether a position is open to the sky, which lighting depends on.
     *
     * <p>True when worldY is at or above the heightmap value for that column.</p>
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param worldY world Y
     * @param z local Z, 0-15
     * @return {@code true} if the position is above the heightmap
     */
    public static boolean hasSkyAccess(ChunkInfo chunk, int x, int worldY, int z) {
        int surfaceY = chunk.heightmap()[x][z];
        return worldY >= surfaceY;
    }

    /**
     * The light level to render a position with.
     *
     * <p>Combines the light values according to the mode and the dimension.</p>
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param worldY world Y
     * @param z local Z, 0-15
     * @param lightMode SURFACE or CAVE
     * @param hasOverlay whether something transparent (water, glass) covers it
     * @param worldHasSkylight whether the dimension has sky light
     * @return the light level, 0-15
     */
    public static byte getEffectiveLight(ChunkInfo chunk, int x, int worldY, int z,
                                          LightMode lightMode, boolean hasOverlay,
                                          boolean worldHasSkylight) {
        byte blockLight = getBlockLightAt(chunk, x, worldY, z);
        byte skyLight = getSkyLightAt(chunk, x, worldY, z);
        boolean hasSkyAccess = hasSkyAccess(chunk, x, worldY, z);

        return lightMode.calculateEffectiveLight(blockLight, skyLight, hasSkyAccess, hasOverlay, false, worldHasSkylight);
    }

    /**
     * The light level to render with, in cave mode.
     *
     * <p>Follows the cave lighting in Xaero's WorldDataReader:</p>
     * <ul>
     *   <li>block light of 15 wins outright, since the block emits light</li>
     *   <li>open to the sky with no overlay: 15, i.e. direct daylight</li>
     *   <li>no overlay: the brighter of block light and sky light</li>
     *   <li>with an overlay: block light, which is the underwater case</li>
     * </ul>
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param worldY world Y
     * @param z local Z, 0-15
     * @param hasOverlay whether something transparent covers it
     * @return the light level, 0-15
     */
    public static byte getEffectiveLightCave(ChunkInfo chunk, int x, int worldY, int z,
                                              boolean hasOverlay) {
        byte blockLight = getBlockLightAt(chunk, x, worldY, z);

        // Block light of 15 wins outright.
        if (blockLight >= 15) {
            return blockLight;
        }

        boolean hasSkyAccess = hasSkyAccess(chunk, x, worldY, z);

        // Open to the sky with no overlay: full daylight.
        if (hasSkyAccess && !hasOverlay) {
            return 15;
        }

        // No overlay: the brighter of the two.
        if (!hasOverlay) {
            byte skyLight = getSkyLightAt(chunk, x, worldY, z);
            return (byte) Math.max(blockLight, skyLight);
        }

        // With an overlay: block light.
        return blockLight;
    }

    /**
     * Where to start scanning a column, from the heightmap plus 3.
     *
     * <p>The 3 covers decoration sitting on top: grass, flowers, snow layers.</p>
     *
     * @param chunk the chunk
     * @param x local X, 0-15
     * @param z local Z, 0-15
     * @param worldTopY the top of the world
     * @return the Y to start scanning from, never above the top of the world
     */
    public static int getHeightmapStartY(ChunkInfo chunk, int x, int z, int worldTopY) {
        int heightMapValue = chunk.heightmap()[x][z];
        // Plus 3, to cover grass, flowers and snow layers on top.
        int startY = heightMapValue + 3;
        // But never above the top of the world.
        return Math.min(startY, worldTopY - 1);
    }
}
