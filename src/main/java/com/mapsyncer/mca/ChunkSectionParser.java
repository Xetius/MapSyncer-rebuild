package com.mapsyncer.mca;

import com.mapsyncer.nbt.Tag;
import com.mapsyncer.util.BoundedStringPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses one chunk section.
 *
 * <p>Reads a section's blocks and biomes:</p>
 * <ul>
 *   <li>the block state palette and its packed indices</li>
 *   <li>the biome palette and its packed indices</li>
 *   <li>block light and sky light</li>
 * </ul>
 *
 * <p>A section is a 16x16x16 box of blocks; a chunk is a stack of them.</p>
 *
 * @see ChunkDataParser which parses a whole chunk
 * @see SectionData a parsed section
 * @see BlockState a parsed block state
 */
public class ChunkSectionParser {
    private static final Map<String, String> EMPTY_PROPERTIES = Map.of();
    private static final BlockState AIR_STATE = new BlockState("minecraft:air", EMPTY_PROPERTIES);
    private static final String DEFAULT_BIOME = "minecraft:the_void";

    /**
     * A block state: its name and its properties.
     *
     * @param name the block name, e.g. {@code "minecraft:stone"}
     * @param properties the block's properties, e.g. {@code {snowy: "false", facing: "north"}}
     */
    public record BlockState(
        String name,                        // block name, e.g. "minecraft:stone"
        Map<String, String> properties      // properties, e.g. {snowy: "false", facing: "north"}
    ) {
        /**
         * The full block ID, properties included.
         *
         * <p>Formatted as {@code "minecraft:grass_block[snowy=false]"}.</p>
         *
         * @return the block ID with its properties
         */
        public String getFullName() {
            if (properties.isEmpty()) {
                return name;
            }
            StringBuilder sb = new StringBuilder(name);
            sb.append("[");
            boolean first = true;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (!first) sb.append(",");
                sb.append(e.getKey()).append("=").append(e.getValue());
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }

        /**
         * Whether this is air.
         *
         * <p>Covers air, cave air and void air.</p>
         *
         * @return {@code true} if the block name contains "air"
         */
        public boolean isAir() {
            return name.equals("minecraft:air") ||
                   name.equals("minecraft:cave_air") ||
                   name.equals("minecraft:void_air");
        }

        /**
         * Whether this is water.
         *
         * <p>Both still and flowing.</p>
         *
         * @return {@code true} for minecraft:water or minecraft:flowing_water
         */
        public boolean isWater() {
            return name.equals("minecraft:water") || name.equals("minecraft:flowing_water");
        }

        /**
         * Whether this is lava.
         *
         * <p>Both still and flowing.</p>
         *
         * @return {@code true} for minecraft:lava or minecraft:flowing_lava
         */
        public boolean isLava() {
            return name.equals("minecraft:lava") || name.equals("minecraft:flowing_lava");
        }

        /**
         * Whether this is a fluid, i.e. water or lava.
         *
         * @return {@code true} for water or lava
         */
        public boolean isFluid() {
            return isWater() || isLava();
        }

        /**
         * Whether this is a grass block.
         *
         * @return {@code true} for minecraft:grass_block
         */
        public boolean isGrassBlock() {
            return name.equals("minecraft:grass_block");
        }

        /**
         * Whether this is transparent: water, lava, glass and so on.
         *
         * <p>Decides whether it is drawn as an overlay.</p>
         *
         * @return {@code true} if it belongs on top of what is beneath it
         */
        public boolean isTransparentOverlay() {
            return isWater() || name.equals("minecraft:glass") ||
                   name.endsWith("_stained_glass") || name.equals("minecraft:glass_pane") ||
                   name.endsWith("_stained_glass_pane") || name.equals("minecraft:ice") ||
                   name.endsWith("_ice") || name.equals("minecraft:tinted_glass");
        }

        /**
         * Whether this is invisible, and so skipped when scanning.
         *
         * <p>Torches, short grass, flowers, glass and the like.</p>
         *
         * @return {@code true} if the block should be skipped
         */
        public boolean isInvisible() {
            // Torch
            if (name.equals("minecraft:torch") || name.endsWith("_torch")) return true;
            // Short grass
            if (name.equals("minecraft:short_grass") || name.equals("minecraft:grass")) return true;
            // Glass (handled as transparent overlay, not invisible in scan)
            // Flowers (default flowers config off)
            if (isFlower()) return true;
            // Double plant non-flowers (tall_grass, large_fern)
            if (name.equals("minecraft:tall_grass") || name.equals("minecraft:large_fern")) return true;
            return false;
        }

        /**
         * Whether this is a flower.
         *
         * <p>Both small and tall flowers.</p>
         *
         * @return {@code true} for flowers
         */
        public boolean isFlower() {
            return name.equals("minecraft:dandelion") || name.equals("minecraft:poppy") ||
                   name.equals("minecraft:blue_orchid") || name.equals("minecraft:allium") ||
                   name.equals("minecraft:red_tulip") || name.equals("minecraft:orange_tulip") ||
                   name.equals("minecraft:white_tulip") || name.equals("minecraft:pink_tulip") ||
                   name.equals("minecraft:oxeye_daisy") || name.equals("minecraft:cornflower") ||
                   name.equals("minecraft:lily_of_the_valley") || name.equals("minecraft:wither_rose") ||
                   name.equals("minecraft:sunflower") || name.equals("minecraft:rose_bush") ||
                   name.equals("minecraft:peony") || name.equals("minecraft:azure_bluet") ||
                   name.endsWith("_tulip") || name.contains("orchid") ||
                   name.equals("minecraft:pitcher_plant") || name.endsWith("_pitcher_crop");
        }

        /**
         * Whether this block is waterlogged.
         *
         * <p>A waterlogged block holds both the block and water.</p>
         *
         * @return {@code true} if it has waterlogged=true
         */
        public boolean isWaterlogged() {
            return properties.containsKey("waterlogged") &&
                   "true".equals(properties.get("waterlogged"));
        }

        /**
         * Whether this is a solid block with water above it.
         *
         * <p>A waterlogged block has its own colour but wants a water overlay drawn on top.</p>
         *
         * @return {@code true} if it is waterlogged and not water itself
         */
        public boolean isWaterloggedSurface() {
            return isWaterlogged() && !isWater() && !isAir();
        }
    }

    /**
     * A parsed section.
     *
     * <p>Everything read out of one 16x16x16 section.</p>
     *
     * @param sectionY the section index, so sectionY * 16 is its bottom world Y
     * @param blockPalette the block states, properties included
     * @param blockNames just the block names, for quick lookups
     * @param blockData packed block palette indices
     * @param blockBitsPerEntry bits per block index
     * @param biomePalette the biome names, e.g. ["minecraft:plains", ...]
     * @param biomeData packed biome palette indices
     * @param biomeBitsPerEntry bits per biome index
     * @param blockLight block light, 2048 bytes
     * @param skyLight sky light, 2048 bytes
     */
    public record SectionData(
        int sectionY,                       // section index; sectionY * 16 is its bottom world Y
        List<BlockState> blockPalette,      // block states, properties included
        List<String> blockNames,            // just the names, for quick lookups
        long[] blockData,                   // packed block palette indices
        int blockBitsPerEntry,              // bits per block index
        List<String> biomePalette,          // biome names, e.g. ["minecraft:plains", ...]
        long[] biomeData,                   // packed biome palette indices
        int biomeBitsPerEntry,              // bits per biome index
        byte[] blockLight,                  // block light, 2048 bytes
        byte[] skyLight                     // sky light, 2048 bytes
    ) {
        // The record gives us blockNames() for free.
    }

    /**
     * Parses a section from its NBT.
     *
     * <p>Reads the blocks, biomes and light.</p>
     *
     * @param sectionTag the section's NBT
     * @return the parsed section
     */
    public static SectionData parseSection(Tag.Compound sectionTag) {
        int sectionY = sectionTag.getByte("Y");

        // block_states
        List<BlockState> blockPalette = new ArrayList<>();
        List<String> blockNames = new ArrayList<>();
        long[] blockData = null;
        int blockBitsPerEntry = 0;

        if (sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
            Tag.Compound blockStates = sectionTag.getCompound("block_states");

            // The palette, properties included.
            if (blockStates.contains("palette", Tag.TAG_LIST)) {
                Tag.ListTag paletteList = blockStates.getList("palette", Tag.TAG_COMPOUND);
                for (int i = 0; i < paletteList.items().size(); i++) {
                    Tag.Compound stateTag = (Tag.Compound) paletteList.items().get(i);
                    BlockState blockState = parseBlockState(stateTag);
                    blockPalette.add(blockState);
                    blockNames.add(blockState.name());
                }
            }

            // The packed data.
            if (blockStates.contains("data", Tag.TAG_LONG_ARRAY)) {
                blockData = blockStates.getLongArray("data");
            }

            // And the bits per entry.
            blockBitsPerEntry = calculateBitsPerEntry(blockPalette.size(), blockData);
        }

        // biomes
        List<String> biomePalette = new ArrayList<>();
        long[] biomeData = null;
        int biomeBitsPerEntry = 0;

        if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
            Tag.Compound biomes = sectionTag.getCompound("biomes");

            // The palette, whose elements are plain strings.
            if (biomes.contains("palette", Tag.TAG_LIST)) {
                Tag.ListTag paletteList = biomes.getList("palette", Tag.TAG_STRING);
                for (int i = 0; i < paletteList.items().size(); i++) {
                    Tag.StringTag biomeTag = (Tag.StringTag) paletteList.items().get(i);
                    biomePalette.add(BoundedStringPool.canonicalize(biomeTag.value()));
                }
            }

            // The packed data.
            if (biomes.contains("data", Tag.TAG_LONG_ARRAY)) {
                biomeData = biomes.getLongArray("data");
            }

            // Bits per entry: biomes use 64 voxels (4x4x4), not 4096.
            biomeBitsPerEntry = calculateBiomeBitsPerEntry(biomePalette.size(), biomeData);
        }

        // Light.
        byte[] blockLight = sectionTag.getByteArray("BlockLight");
        byte[] skyLight = sectionTag.getByteArray("SkyLight");

        return new SectionData(
            sectionY, blockPalette, blockNames, blockData, blockBitsPerEntry,
            biomePalette, biomeData, biomeBitsPerEntry,
            blockLight.length == 2048 ? blockLight : null,
            skyLight.length == 2048 ? skyLight : null
        );
    }

    /**
     * Parses one block state's NBT.
     *
     * <p>Shaped as {@code {Name: "minecraft:grass_block", Properties: {snowy: "false"}}}.</p>
     *
     * @param stateTag the block state's NBT
     * @return the parsed block state
     */
    private static BlockState parseBlockState(Tag.Compound stateTag) {
        String name = BoundedStringPool.canonicalize(stateTag.getString("Name"));
        Map<String, String> properties = EMPTY_PROPERTIES;

        if (stateTag.contains("Properties", Tag.TAG_COMPOUND)) {
            Tag.Compound propsTag = stateTag.getCompound("Properties");
            properties = new LinkedHashMap<>();
            for (Map.Entry<String, Tag> entry : propsTag.children().entrySet()) {
                Tag propTag = entry.getValue();
                if (propTag instanceof Tag.StringTag str) {
                    properties.put(BoundedStringPool.canonicalize(entry.getKey()),
                            BoundedStringPool.canonicalize(str.value()));
                }
            }
            if (properties.isEmpty()) {
                properties = EMPTY_PROPERTIES;
            }
        }

        return new BlockState(name, properties);
    }

    /**
     * Works out the bits per entry of a block palette.
     *
     * <p>As the wiki specifies:</p>
     * <ul>
     *   <li>palette of 1 or fewer: 0, since a single-block section needs no data array</li>
     *   <li>palette of 16 or fewer: 4</li>
     *   <li>larger: ceil(log2(paletteSize))</li>
     * </ul>
     *
     * @param paletteSize the palette size
     * @param data the packed data
     * @return the bits per entry
     */
    private static int calculateBitsPerEntry(int paletteSize, long[] data) {
        if (paletteSize <= 1) {
            return 0;  // single-block section, so no data array
        }

        // As the wiki says: b = 4 up to 16 entries, otherwise ceil(log2(c)).
        if (paletteSize <= 16) {
            return 4;
        }
        // ceil(log2(c)) = 32 - numberOfLeadingZeros(c - 1)
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    /**
     * Works out the bits per entry of a biome palette.
     *
     * <p>As the wiki specifies: ceil(log2(paletteSize)).</p>
     *
     * @param paletteSize the palette size
     * @param data the packed data
     * @return the bits per entry
     */
    private static int calculateBiomeBitsPerEntry(int paletteSize, long[] data) {
        if (paletteSize <= 1) {
            return 0;  // single-biome section, so no data array
        }

        // As the wiki says: b = ceil(log2(c)).
        // ceil(log2(c)) = 32 - numberOfLeadingZeros(c - 1)
        return 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    /**
     * The full block state at a position in a section.
     *
     * @param section the section
     * @param x local X, 0-15
     * @param y local Y, 0-15
     * @param z local Z, 0-15
     * @return the block state, or air if the position is out of range
     */
    public static BlockState getBlockStateAt(SectionData section, int x, int y, int z) {
        if (section.blockPalette.isEmpty()) {
            return AIR_STATE;
        }

        // Single-block palette.
        if (section.blockPalette.size() == 1) {
            return section.blockPalette.get(0);
        }

        // No data.
        if (section.blockData == null || section.blockBitsPerEntry == 0) {
            return AIR_STATE;
        }

        // Index, in YZX order.
        int blockIndex = (y << 8) | (z << 4) | x;

        // Read the palette index out of the packed data.
        int paletteIndex = readBitsFromArray(section.blockData, blockIndex, section.blockBitsPerEntry);

        if (paletteIndex < 0 || paletteIndex >= section.blockPalette.size()) {
            return AIR_STATE;
        }

        return section.blockPalette.get(paletteIndex);
    }

    /**
     * The block name at a position in a section.
     *
     * @param section the section
     * @param x local X, 0-15
     * @param y local Y, 0-15
     * @param z local Z, 0-15
     * @return the block name
     */
    public static String getBlockAt(SectionData section, int x, int y, int z) {
        return getBlockStateAt(section, x, y, z).name();
    }

    /**
     * The biome at a position in a section, without boundary smoothing.
     *
     * @param section the section
     * @param x local X, 0-15
     * @param y local Y, 0-15
     * @param z local Z, 0-15
     * @return the biome name
     */
    public static String getBiomeAt(SectionData section, int x, int y, int z) {
        return getBiomeAt(section, x, y, z, false);
    }

    /**
     * The biome at a position in a section, with optional boundary smoothing.
     *
     * Biomes are stored per 4x4x4 voxel, so one voxel covers 4x4 pixels of the map.
     * To get something like Xaero's biome blending, pixels near a voxel edge are biased
     * towards the neighbouring voxel, so Xaero's cross-shaped sampling picks up more than
     * one biome.
     *
     * Voxel coordinates, in voxels:
     * - voxel (0,0) covers pixels (0-3, 0-3)
     * - voxel (1,0) covers pixels (4-7, 0-3)
     * - voxel (0,1) covers pixels (0-3, 4-7)
     *
     * The smoothing:
     * - each pixel nominally belongs to the voxel it sits in
     * - but where it sits inside that voxel decides which voxel is actually sampled
     * - relX < 2 and relZ < 2 (top left): this voxel, or the one up and left
     * - relX >= 2 and relZ < 2 (top right): biased right
     * - relX < 2 and relZ >= 2 (bottom left): biased down
     * - relX >= 2 and relZ >= 2 (bottom right): biased down and right
     *
     * Compare Xaero's BiomeColorCalculator, which averages a cross of five samples.
     *
     * @param section the section
     * @param x local X, 0-15
     * @param y local Y, 0-15
     * @param z local Z, 0-15
     * @param smoothBoundary whether to smooth across voxel boundaries
     */
    public static String getBiomeAt(SectionData section, int x, int y, int z, boolean smoothBoundary) {
        // As Xaero's WorldDataReader does: default to THE_VOID.
        // An empty biome palette means the void, which renders deep purple.
        if (section.biomePalette.isEmpty()) {
            return DEFAULT_BIOME;
        }

        // Single-biome palette.
        if (section.biomePalette.size() == 1) {
            return section.biomePalette.get(0);
        }

        // No data: the void again.
        if (section.biomeData == null || section.biomeBitsPerEntry == 0) {
            return DEFAULT_BIOME;
        }

        // The plain index: voxelIndex = (y/4)*16 + (z/4)*4 + (x/4)
        int voxelY = y >> 2;
        int voxelZ = z >> 2;
        int voxelX = x >> 2;

        // Smoothing: nudge by where the pixel sits inside its voxel.
        if (smoothBoundary) {
            // Position within the voxel, 0-3.
            int relX = x & 3;
            int relZ = z & 3;

            // The point is to let Xaero's cross-shaped sampling see more than one biome.
            // It samples (x-1,z), (x,z-1), (x,z), (x,z+1), (x+1,z),
            // so those samples need to land in different voxels.

            // Treat the pixel's position as an offset from the voxel's top-left corner:
            // offsets 0 and 1 stay in this voxel, offsets 2 and 3 lean into the
            // neighbouring one, because they sit near the right or bottom edge.
            //

            // relX >= 2 means the pixel is near the right edge, so use the voxel to the right.
            // relZ >= 2 means it is near the bottom edge, so use the voxel below.

            if (relX >= 2 && voxelX < 3) {
                voxelX++;
            }
            if (relZ >= 2 && voxelZ < 3) {
                voxelZ++;
            }
        }

        int voxelIndex = (voxelY << 4) | (voxelZ << 2) | voxelX;

        int paletteIndex = readBitsFromArray(section.biomeData, voxelIndex, section.biomeBitsPerEntry);

        if (paletteIndex < 0 || paletteIndex >= section.biomePalette.size()) {
            return null;
        }

        return section.biomePalette.get(paletteIndex);
    }

    /**
     * Reads one packed value out of a long array, as the wiki specifies.
     *
     * <p>The formula:</p>
     * <ul>
     *   <li>u = floor(64/b), the number of entries per long</li>
     *   <li>getPalette(i) = (data[i/u] >>> ((i%u)*b)) & ((1L<<b)-1)</li>
     * </ul>
     *
     * <p>Entries never straddle two longs.</p>
     *
     * @param data the long array
     * @param index the entry index: YZX-encoded for blocks, the voxel index for biomes
     * @param bitsPerEntry bits per entry
     * @return the palette index
     */
    public static int readBitsFromArray(long[] data, int index, int bitsPerEntry) {
        if (data == null || data.length == 0 || bitsPerEntry <= 0) {
            return 0;
        }

        // u = floor(64/b), the number of entries per long.
        int u = 64 / bitsPerEntry;

        // The formula: data[i/u] >>> ((i%u)*b) & ((1L<<b)-1)
        int longIndex = index / u;
        int posInLong = index % u;
        int bitOffset = posInLong * bitsPerEntry;

        if (longIndex >= data.length) {
            return 0;
        }

        // Unsigned shift, then mask off b bits.
        return (int) ((data[longIndex] >>> bitOffset) & ((1L << bitsPerEntry) - 1L));
    }

    /**
     * The block light at a position, from the nibble array.
     *
     * @param section the section
     * @param x local X, 0-15
     * @param y local Y, 0-15
     * @param z local Z, 0-15
     * @return the block light, 0-15
     */
    public static byte getBlockLight(SectionData section, int x, int y, int z) {
        return getLightValue(section.blockLight(), x, y, z);
    }

    /**
     * The sky light at a position, from the nibble array.
     *
     * @param section the section
     * @param x local X, 0-15
     * @param y local Y, 0-15
     * @param z local Z, 0-15
     * @return the sky light, 0-15
     */
    public static byte getSkyLight(SectionData section, int x, int y, int z) {
        return getLightValue(section.skyLight(), x, y, z);
    }

    /**
     * Reads a light value out of a nibble array, as the wiki specifies.
     *
     * <p>Two 4-bit values per byte, so 2048 bytes hold 4096 values.</p>
     * <p>Stored in YZX order.</p>
     *
     * <p>The formula: getLight(x, y, z) = (data[yzx >> 1] >> (4 * (yzx & 1))) & 0xF</p>
     * <p>where yzx = (y << 8) | (z << 4) | x</p>
     *
     * @param lightArray the nibble array: 2048 bytes holding 4096 values
     * @param x local X, 0-15
     * @param y local Y, 0-15
     * @param z local Z, 0-15
     * @return the light value, 0-15
     */
    public static byte getLightValue(byte[] lightArray, int x, int y, int z) {
        if (lightArray == null || lightArray.length != 2048) {
            return 0;
        }

        // The YZX index.
        int yzx = (y << 8) | (z << 4) | x;

        // The formula: (data[yzx >> 1] >> (4 * (yzx & 1))) & 0xF
        // yzx >> 1 picks the byte, since each byte holds two values;
        // yzx & 1 says which half (0 = low nibble, 1 = high nibble);
        // 4 * (yzx & 1) is therefore the bit offset, 0 or 4.
        return (byte) ((lightArray[yzx >> 1] >> (4 * (yzx & 1))) & 0xF);
    }

    /**
     * Expands a section's light data.
     *
     * <p>Turns the nibble arrays into full 4096-byte arrays.</p>
     *
     * @param section the section
     * @return the expanded light data, indexed as (y<<8)|(z<<4)|x
     */
    public static LightData parseLightData(SectionData section) {
        byte[] blockLight = new byte[4096];
        byte[] skyLight = new byte[4096];

        if (section.blockLight() != null && section.blockLight().length == 2048) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        blockLight[idx] = getLightValue(section.blockLight(), x, y, z);
                    }
                }
            }
        }

        if (section.skyLight() != null && section.skyLight().length == 2048) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        skyLight[idx] = getLightValue(section.skyLight(), x, y, z);
                    }
                }
            }
        }

        return new LightData(section.sectionY(), blockLight, skyLight);
    }

    /**
     * A section's expanded light data.
     *
     * <p>4096 bytes per array, one value per block.</p>
     *
     * @param sectionY the section index
     * @param blockLight block light, 4096 bytes of 0-15
     * @param skyLight sky light, 4096 bytes of 0-15
     */
    public record LightData(
        int sectionY,           // the section index
        byte[] blockLight,      // block light, 4096 bytes of 0-15
        byte[] skyLight         // sky light, 4096 bytes of 0-15
    ) {
        /**
         * Whether there is any light data at all.
         *
         * @return {@code true} if either array is present
         */
        public boolean hasLightData() {
            return blockLight != null || skyLight != null;
        }

        /**
         * The block light at a position.
         *
         * @param x local X, 0-15
         * @param localY local Y, 0-15, from the bottom of the section
         * @param z local Z, 0-15
         * @return the block light, 0-15
         */
        public byte getBlockLightAt(int x, int localY, int z) {
            if (blockLight == null) return 0;
            int idx = (localY << 8) | (z << 4) | x;
            return idx < blockLight.length ? blockLight[idx] : 0;
        }

        /**
         * The sky light at a position.
         *
         * @param x local X, 0-15
         * @param localY local Y, 0-15, from the bottom of the section
         * @param z local Z, 0-15
         * @return the sky light, 0-15
         */
        public byte getSkyLightAt(int x, int localY, int z) {
            if (skyLight == null) return 0;
            int idx = (localY << 8) | (z << 4) | x;
            return idx < skyLight.length ? skyLight[idx] : 0;
        }

        /**
         * The light level to render with, in surface mode.
         *
         * <p>Block light only; sky light is ignored.</p>
         *
         * @param x local X, 0-15
         * @param localY local Y, 0-15
         * @param z local Z, 0-15
         * @return the block light
         */
        public byte getEffectiveLightSurface(int x, int localY, int z) {
            return getBlockLightAt(x, localY, z);
        }

        /**
         * The light level to render with, in cave mode.
         *
         * <p>Xaero uses sky light 15 in daylight but block light underwater.</p>
         * <p>Follows Xaero's WorldDataReader:537-561.</p>
         *
         * @param x local X, 0-15
         * @param localY local Y, 0-15
         * @param z local Z, 0-15
         * @param hasSkyAccess whether the position is above the heightmap
         * @param hasOverlay whether something transparent (water, glass) covers it
         * @return the light level, 0-15
         */
        public byte getEffectiveLightCave(int x, int localY, int z,
                                          boolean hasSkyAccess, boolean hasOverlay) {
            byte blockLight = getBlockLightAt(x, localY, z);

            // Block light of 15 wins outright: the block emits light.
            if (blockLight >= 15) {
                return blockLight;
            }

            // Open to the sky: full daylight.
            if (hasSkyAccess && !hasOverlay) {
                return 15;
            }

            // No overlay: the brighter of the two.
            if (!hasOverlay) {
                byte skyLight = getSkyLightAt(x, localY, z);
                return (byte) Math.max(blockLight, skyLight);
            }

            // With an overlay, underwater for instance: block light.
            return blockLight;
        }

        /**
         * The light level to render with, for either mode.
         *
         * <p>Combines the light values according to the mode and the dimension.</p>
         *
         * @param x local X, 0-15
         * @param localY local Y, 0-15
         * @param z local Z, 0-15
         * @param lightMode SURFACE or CAVE
         * @param hasSkyAccess whether the position is above the heightmap
         * @param hasOverlay whether something transparent covers it
         * @param worldHasSkylight whether the dimension has sky light
         * @return the light level, 0-15
         */
        public byte getEffectiveLight(int x, int localY, int z,
                                       LightMode lightMode,
                                       boolean hasSkyAccess, boolean hasOverlay,
                                       boolean worldHasSkylight) {
            return lightMode.calculateEffectiveLight(
                getBlockLightAt(x, localY, z),
                getSkyLightAt(x, localY, z),
                hasSkyAccess, hasOverlay, false, worldHasSkylight
            );
        }
    }
}
