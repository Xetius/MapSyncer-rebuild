package com.mapsyncer.mca;

import com.mapsyncer.server.BlockPropertyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Converts a region file into Xaero's map format, without Minecraft.
 *
 * <p>Reads .mca files with this project's own parser and writes out what Xaero's World Map
 * expects.</p>
 *
 * <p>What it does:</p>
 * <ul>
 *   <li>reads and parses MCA region files</li>
 *   <li>handles block states, biomes and light</li>
 *   <li>scans in both surface and cave mode</li>
 *   <li>writes data in Xaero's format</li>
 * </ul>
 *
 * <p>Follows Xaero's WorldDataReader.</p>
 *
 * @see McaReader which reads the MCA file
 * @see ChunkDataParser which parses a chunk
 * @see ChunkSectionParser which parses a section
 * @see LightMode the lighting modes
 * @see DimensionTypeInfo the dimension properties
 */
public class RegionConverterStandalone {

    /**
     * Logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionConverterStandalone.class);

    /**
     * Block name used for blank pixels.
     *
     * <p>For pixels with no data, such as the void in the end.</p>
     */
    private static final String DEFAULT_BLOCK = "minecraft:air";

    /**
     * Biome name used for the void.
     *
     * <p>air plus the_void, which is what renders the void as deep purple.</p>
     */
    private static final String DEFAULT_BIOME = "minecraft:the_void";

    /**
     * Region size in blocks: 512x512.
     */
    public static final int REGION_SIZE_BLOCKS = 512;

    /**
     * Chunks per region: 32x32.
     */
    public static final int CHUNKS_PER_REGION = 32;

    /**
     * Blocks per tile chunk: 64x64.
     */
    public static final int BLOCKS_PER_TILE_CHUNK = 64;

    /**
     * Blocks per tile: 16x16, i.e. one Minecraft chunk.
     */
    public static final int BLOCKS_PER_TILE = 16;

    /**
     * Tiles per tile chunk: 4x4.
     */
    public static final int TILES_PER_TILE_CHUNK = 4;

    /**
     * Tile chunks per region: 8x8.
     */
    public static final int TILE_CHUNKS_PER_REGION = 8;

    /**
     * Xaero format major version.
     */
    public static final int MAJOR_VERSION = 6;

    /**
     * Xaero format minor version.
     */
    public static final int MINOR_VERSION = 8;

    /**
     * A converted region.
     *
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @param xaeroData the map data, in Xaero's format
     */
    public record ConvertedRegion(int regionX, int regionZ, byte[] xaeroData) {}

    /**
     * Cave mode parameters.
     *
     * <p>Control how deep a cave scan goes and how light is worked out.</p>
     *
     * @param caveStart the Y to start scanning down from
     * @param caveDepth how far below caveStart to scan
     */
    public record CaveModeParams(
        int caveStart,      // the Y to start scanning down from
        int caveDepth       // how far below caveStart to scan
    ) {
        /**
         * Cave mode off.
         *
         * <p>caveStart of {@code Integer.MAX_VALUE} means surface scanning.</p>
         */
        public static final CaveModeParams NONE = new CaveModeParams(Integer.MAX_VALUE, 0);

        /**
         * Default cave parameters.
         *
         * @param worldTopY the top of the world
         * @param defaultDepth the default depth, usually 63, as the nether uses
         * @return the parameters
         */
        public static CaveModeParams createDefault(int worldTopY, int defaultDepth) {
            return new CaveModeParams(worldTopY, defaultDepth);
        }
    }

    /**
     * Converts one region file, in surface mode.
     *
     * <p>Uses the default surface-mode parameters.</p>
     *
     * @param mcaPath the .mca file
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @param minBuildHeight the lowest buildable Y, usually -64
     * @param worldTopY the highest buildable Y, usually 320
     * @return the converted region, or {@code null} if the file is missing or unreadable
     */
    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  int minBuildHeight, int worldTopY) {
        return convertRegion(mcaPath, regionX, regionZ, minBuildHeight, worldTopY,
                             LightMode.SURFACE, CaveModeParams.NONE, true);
    }

    /**
     * Converts one region file.
     *
     * <p>Compare Xaero's WorldDataReader.java line 186:</p>
     * <p>worldHasSkylight = serverWorld.dimensionType().hasSkyLight()</p>
     *
     * @param mcaPath the .mca file
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @param minBuildHeight the lowest buildable Y, usually -64
     * @param worldTopY the highest buildable Y, usually 320
     * @param lightMode SURFACE or CAVE
     * @param caveParams cave parameters, used only in cave mode
     * @param worldHasSkylight whether the dimension has sky light (false in the end)
     * @return the converted region, or {@code null} if conversion failed
     */
    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  int minBuildHeight, int worldTopY,
                                                  LightMode lightMode,
                                                  CaveModeParams caveParams,
                                                  boolean worldHasSkylight) {
        if (!Files.exists(mcaPath)) {
            return null;
        }

        try {
            MapRegionData regionData = readMcaFile(mcaPath, minBuildHeight, worldTopY, lightMode, caveParams, worldHasSkylight);
            if (regionData == null) return null;

            byte[] xaeroData = serializeToXaeroFormat(regionData, minBuildHeight);
            return new ConvertedRegion(regionX, regionZ, xaeroData);
        } catch (IOException e) {
            LOGGER.warn("Failed to convert region ({}, {}): {}", regionX, regionZ, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to convert region ({}, {}): {}", regionX, regionZ, e.getMessage());
            return null;
        } catch (OutOfMemoryError e) {
            LOGGER.error("Failed to convert region ({}, {}): Java heap space", regionX, regionZ);
            return null;
        }
    }

    /**
     * Converts one region file, taking the dimension properties as a bundle.
     *
     * <p>DimensionTypeInfo carries:</p>
     * <ul>
     *   <li>minY: the lowest buildable Y</li>
     *   <li>height: total height, so maxY is minY + height</li>
     *   <li>hasSkylight: whether it has sky light</li>
     *   <li>hasCeiling: whether it has a ceiling</li>
     * </ul>
     *
     * @param mcaPath the .mca file
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @param dimTypeInfo the dimension's properties
     * @param lightMode SURFACE or CAVE
     * @param caveParams cave parameters, used only in cave mode
     * @return the converted region
     */
    public static ConvertedRegion convertRegion(Path mcaPath, int regionX, int regionZ,
                                                  DimensionTypeInfo dimTypeInfo,
                                                  LightMode lightMode,
                                                  CaveModeParams caveParams) {
        return convertRegion(mcaPath, regionX, regionZ,
                             dimTypeInfo.minY(), dimTypeInfo.maxY(),
                             lightMode, caveParams, dimTypeInfo.hasSkylight());
    }

    /**
     * Reads a region file with the standalone parser, in surface mode.
     *
     * <p>Walks every chunk, reading blocks and light.</p>
     *
     * @param mcaPath the MCA file
     * @param minBuildHeight the lowest buildable Y
     * @param worldTopY the highest buildable Y
     * @return the region's data
     * @throws IOException if reading fails
     */
    static MapRegionData readMcaFile(Path mcaPath, int minBuildHeight, int worldTopY) throws IOException {
        return readMcaFile(mcaPath, minBuildHeight, worldTopY, LightMode.SURFACE, CaveModeParams.NONE, true);
    }

    /**
     * Reads a region file with the standalone parser.
     *
     * <p>Handles both surface and cave mode.</p>
     *
     * @param mcaPath the MCA file
     * @param minBuildHeight the lowest buildable Y
     * @param worldTopY the highest buildable Y
     * @param lightMode SURFACE or CAVE
     * @param caveParams cave parameters
     * @param worldHasSkylight whether the dimension has sky light
     * @return the region's data
     * @throws IOException if reading fails
     */
    static MapRegionData readMcaFile(Path mcaPath, int minBuildHeight, int worldTopY,
                                       LightMode lightMode, CaveModeParams caveParams,
                                       boolean worldHasSkylight) throws IOException {
        MapRegionData data = new MapRegionData(minBuildHeight, lightMode);

        try (McaReader reader = new McaReader(mcaPath.toString())) {
            int worldHeightRange = worldTopY - minBuildHeight;
            reader.forEachChunk(chunkData -> {
                try {
                    ChunkDataParser.ChunkInfo chunkInfo = ChunkDataParser.parseChunk(
                        chunkData.chunkX(), chunkData.chunkZ(), chunkData.nbt(),
                        minBuildHeight, worldHeightRange
                    );

                    if (chunkInfo != null) {
                        processChunk(data, chunkInfo, minBuildHeight, worldTopY, lightMode, caveParams, worldHasSkylight);
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("Skipping chunk ({}, {}) in {}: {}",
                            chunkData.chunkX(), chunkData.chunkZ(), mcaPath.getFileName(), e.getMessage());
                } catch (OutOfMemoryError e) {
                    LOGGER.error("Skipping chunk ({}, {}) in {}: Java heap space",
                            chunkData.chunkX(), chunkData.chunkZ(), mcaPath.getFileName());
                }
            });
        }

        return data;
    }

    /**
     * Processes one chunk, in surface mode.
     *
     * <p>Scans the chunk's blocks and picks out the surface.</p>
     *
     * @param data the region being built
     * @param chunk the chunk
     * @param minBuildHeight the lowest buildable Y
     * @param worldTopY the highest buildable Y
     */
    private static void processChunk(MapRegionData data, ChunkDataParser.ChunkInfo chunk,
                                       int minBuildHeight, int worldTopY) {
        processChunk(data, chunk, minBuildHeight, worldTopY, LightMode.SURFACE, CaveModeParams.NONE, true);
    }

    /**
     * Processes one chunk.
     *
     * <p>How lighting is worked out:</p>
     *
     * <p>SURFACE:</p>
     * <ul>
     *   <li>block light only</li>
     *   <li>sky light ignored entirely</li>
     *   <li>every pixel uses its block light</li>
     * </ul>
     *
     * <p>CAVE:</p>
     * <ul>
     *   <li>both block light and sky light</li>
     *   <li>open to the sky, i.e. above the heightmap: sky light 15, but only where worldHasSkylight</li>
     *   <li>the end has worldHasSkylight false, so sky light 15 is never used there</li>
     *   <li>underwater: block light</li>
     *   <li>elsewhere underground: the brighter of the two</li>
     * </ul>
     *
     * @param data the region being built
     * @param chunk the chunk
     * @param minBuildHeight the lowest buildable Y
     * @param worldTopY the highest buildable Y
     * @param lightMode SURFACE or CAVE
     * @param caveParams cave parameters
     * @param worldHasSkylight whether the dimension has sky light
     */
    private static void processChunk(MapRegionData data, ChunkDataParser.ChunkInfo chunk,
                                       int minBuildHeight, int worldTopY,
                                       LightMode lightMode, CaveModeParams caveParams,
                                       boolean worldHasSkylight) {
        int chunkX = chunk.chunkX();
        int chunkZ = chunk.chunkZ();
        if (chunkX < 0 || chunkX >= CHUNKS_PER_REGION || chunkZ < 0 || chunkZ >= CHUNKS_PER_REGION) {
            LOGGER.warn("Skipping chunk with out-of-region local coordinates ({}, {})", chunkX, chunkZ);
            return;
        }

        // Mark the chunk as present, which distinguishes ungenerated chunks from void pixels.
        data.chunkExists[chunkX][chunkZ] = true;

        // Cave parameters.
        int caveStart = caveParams.caveStart();
        int caveDepth = Math.max(0, caveParams.caveDepth());

        // Are we in cave mode?
        boolean isCaveMode = caveStart != Integer.MAX_VALUE;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int relX = chunkX * 16 + lx;
                int relZ = chunkZ * 16 + lz;

                // Bounds check.
                if (relX >= REGION_SIZE_BLOCKS || relZ >= REGION_SIZE_BLOCKS) {
                    continue;  // out of range
                }

                // Work out the range to scan.
                // Surface mode starts from the heightmap;
                // cave mode starts from caveStart and runs down caveDepth.
                int startY;
                int scanBottomY;
                int heightMapValue = chunk.heightmap()[lx][lz];
                int chunkBottomY = chunk.chunkBottomY();

                if (isCaveMode) {
                    // Cave mode: from caveStart down to caveStart - caveDepth.
                    startY = caveStart == Integer.MIN_VALUE ? worldTopY - 1 : clamp(caveStart, minBuildHeight, worldTopY - 1);
                    scanBottomY = Math.max(startY - caveDepth, minBuildHeight);
                } else {
                    // Surface mode: from the heightmap downwards.
                    startY = ChunkDataParser.getHeightmapStartY(chunk, lx, lz, worldTopY);
                    scanBottomY = minBuildHeight;
                }

                ChunkSectionParser.BlockState topState = null;
                int topY = -1;
                int highestBlockY = -1;
                String biomeName = null;
                List<OverlayData> overlayList = null;
                byte surfaceLight = 0;

                // Cave mode state, following Xaero's WorldDataReader.java:346-351 and 571-596.
                // underair: whether the scan has reached open air inside the cave yet.
                // Full-cave mode (caveStart == Integer.MIN_VALUE) starts true, since it scans
                // from the bottom; ordinary cave mode starts false and waits for air first.
                boolean underair = isCaveMode && caveStart == Integer.MIN_VALUE;

                // Scan from the highest section downwards.
                // Sections are ordered high to low, as in Xaero's WorldDataReader.
                int sectionIndex = 0;  // which section we are on
                for (ChunkSectionParser.SectionData section : chunk.sections()) {
                    if (section.blockPalette().isEmpty()) continue;

                    int sectionY = section.sectionY();
                    int sectionBaseY = sectionY * 16;
                    int sectionTopY = sectionBaseY + 15;
                    int sectionBottomY = sectionBaseY;

                    // Cave mode: skip sections above caveStart.
                    if (isCaveMode && sectionTopY > startY) continue;

                    // Skip sections below the bottom of the scan.
                    if (sectionBottomY < scanBottomY) continue;

                    if (sectionTopY < chunkBottomY) continue;

                    // Work out where in this section to start.
                    // Compare Xaero's WorldDataReader.java line 425:
                    // surface mode starts at heightMapValue + 3, or the section's top;
                    // cave mode starts at caveStart;
                    // and after the first section there is an extra +1 (i > 0 && ++startHeight).
                    int effectiveStartY = startY;
                    if (sectionIndex > 0) {
                        effectiveStartY = Math.min(startY + 1, worldTopY - 1);
                    }

                    // Surface mode: use the section top if the heightmap is below chunkBottomY.
                    if (!isCaveMode && heightMapValue < chunkBottomY) {
                        effectiveStartY = sectionTopY;
                    }

                    // Cave mode: never start above the section's top.
                    if (isCaveMode) {
                        effectiveStartY = Math.min(effectiveStartY, sectionTopY);
                    }

                    sectionIndex++;

                    // Single-block palette: still scan down to find the real height.
                    if (section.blockPalette().size() == 1 && section.blockData() == null) {
                        ChunkSectionParser.BlockState singleState = section.blockPalette().get(0);

                        // Cave mode: a section of pure air means we are inside the cave now.
                        if (singleState.isAir()) {
                            if (isCaveMode) {
                                underair = true;
                            }
                            continue;
                        }

                        // Cave mode: not inside the cave yet, so skip this section.
                        if (isCaveMode && !underair) {
                            continue;
                        }

                        // Scan down from the top of this section.
                        int scanStartY = Math.min(effectiveStartY - sectionBaseY, 15);
                        if (scanStartY < 0) scanStartY = 15;

                        // Cave mode: where this section's scan stops.
                        int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

                        for (int ly = scanStartY; ly >= localScanBottomY; ly--) {
                            int worldY = sectionBaseY + ly;

                            // Cave mode: stop below the bottom of the scan.
                            if (worldY < scanBottomY) break;

                            // Waterlogged blocks: the block is the surface, with water over it,
                            // so a water overlay goes on at the same level.
                            // Opacity uses water's lightBlock, as Xaero does.
                            if (BlockPropertyResolver.isWaterloggedSurface(singleState.name(), singleState.properties())) {
                                topState = singleState;
                                topY = worldY;
                                data.heightMap[relX][relZ] = topY;

                                // Water overlay at the same level, using water's lightBlock.
                                int opacity = BlockPropertyResolver.getLightBlock("minecraft:water");
                                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                                overlayList = addOverlay(overlayList, "minecraft:water", worldY, opacity, overlayLight);
                                if (highestBlockY < 0) highestBlockY = worldY;

                                surfaceLight = overlayLight;
                                biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz, true);
                                break;
                            }

                            boolean shouldOverlay = BlockPropertyResolver.shouldOverlay(singleState.name());

                            if (shouldOverlay) {
                                // Opacity is the block's lightBlock, as Xaero does.
                                int opacity = BlockPropertyResolver.getLightBlock(singleState.name());
                                byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                                overlayList = addOverlay(overlayList, singleState.name(), worldY, opacity, overlayLight);
                                if (highestBlockY < 0) highestBlockY = worldY;
                                continue;  // keep looking for the surface
                            }

                            // An opaque block is the surface.
                            topState = singleState;
                            topY = worldY;
                            if (highestBlockY < 0) highestBlockY = worldY;
                            data.heightMap[relX][relZ] = topY;

                            // Light, for the current mode.
                            surfaceLight = calculateSurfaceLight(section, lx, ly, lz, worldY,
                                heightMapValue, overlayList, lightMode, worldHasSkylight);

                            biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz, true);
                            break;
                        }

                        if (topState != null) break;  // surface found, so stop here
                        continue;  // otherwise try the next section
                    }

                    // Multi-block palette, so indices come from the packed array.
                    // Where in the section to start.
                    int localStartY = 15;
                    if (effectiveStartY >= sectionBaseY && effectiveStartY <= sectionTopY) {
                        localStartY = effectiveStartY - sectionBaseY;
                    }

                    // Cave mode: where this section's scan stops.
                    int localScanBottomY = Math.max(0, scanBottomY - sectionBaseY);

                    // Scan down from localStartY.
                    for (int ly = localStartY; ly >= localScanBottomY; ly--) {
                        int worldY = sectionBaseY + ly;

                        // Stop below the bottom of the scan.
                        if (worldY < scanBottomY) break;
                        if (worldY < chunkBottomY) break;

                        ChunkSectionParser.BlockState state = ChunkSectionParser.getBlockStateAt(section, lx, ly, lz);

                        // The heart of cave mode: air has to come first, as in Xaero's WorldDataReader.java:571-596.
                        if (state.isAir()) {
                            if (isCaveMode) {
                                underair = true;  // we are inside the cave now
                            }
                            continue;
                        }

                        // Cave mode: not inside the cave yet, so skip this block.
                        if (isCaveMode && !underair) {
                            continue;
                        }

                        // Step 1: waterlogged blocks are the surface, with water over them,
                        // so a water overlay goes on at the same level.
                        // Opacity uses water's lightBlock, as Xaero does.
                        if (BlockPropertyResolver.isWaterloggedSurface(state.name(), state.properties())) {
                            topState = state;
                            topY = worldY;
                            data.heightMap[relX][relZ] = topY;

                            // Water overlay at the same level, using water's lightBlock.
                            int opacity = BlockPropertyResolver.getLightBlock("minecraft:water");
                            byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                            overlayList = addOverlay(overlayList, "minecraft:water", worldY, opacity, overlayLight);
                            if (highestBlockY < 0) highestBlockY = worldY;

                            surfaceLight = overlayLight;
                            biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz);
                            break;
                        }

                        // Step 2: plain water becomes an overlay; keep looking for the surface.
                        // Opacity is the block's lightBlock, as Xaero does.
                        if (BlockPropertyResolver.isTranslucentFluid(state.name())) {
                            int opacity = BlockPropertyResolver.getLightBlock(state.name());
                            byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                            overlayList = addOverlay(overlayList, state.name(), worldY, opacity, overlayLight);
                            if (highestBlockY < 0) highestBlockY = worldY;
                            continue;  // keep looking for the surface
                        }

                        // Step 3: invisible blocks are skipped.
                        if (BlockPropertyResolver.isInvisible(state.name())) {
                            continue;
                        }

                        // Step 4: transparent blocks become overlays.
                        // Compare Xaero: overlayBuilder.build(state, state.getLightBlock(...), light, ...)
                        if (BlockPropertyResolver.isTransparent(state.name())) {
                            int opacity = BlockPropertyResolver.getLightBlock(state.name());
                            byte overlayLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
                            overlayList = addOverlay(overlayList, state.name(), worldY, opacity, overlayLight);
                            if (highestBlockY < 0) highestBlockY = worldY;
                            continue;
                        }

                        // Step 5: does it have a map colour?
                        if (!BlockPropertyResolver.hasVanillaColor(state.name())) {
                            continue;
                        }

                        // A visible solid block is the surface.
                        topState = state;
                        topY = worldY;
                        data.heightMap[relX][relZ] = topY;

                        // Light, for the current mode.
                        surfaceLight = calculateSurfaceLight(section, lx, ly, lz, worldY,
                            heightMapValue, overlayList, lightMode, worldHasSkylight);

                        biomeName = ChunkSectionParser.getBiomeAt(section, lx, ly, lz);
                        break;
                    }

                    if (topState != null) break;
                }

                // Record the pixel.
                if (topState != null || (overlayList != null && !overlayList.isEmpty())) {
                    data.hasData[relX][relZ] = true;
                    data.blockNames[relX][relZ] = topState != null ? topState.name() : "minecraft:air";
                    int topBlockYValue = (highestBlockY >= 0) ? highestBlockY : topY;
                    data.topBlockY[relX][relZ] = topBlockYValue;
                    // As Xaero does: a null biome name becomes THE_VOID, which renders deep purple.
                    data.biomeNames[relX][relZ] = biomeName != null ? biomeName : DEFAULT_BIOME;
                    data.lightMap[relX][relZ] = surfaceLight;
                    if (overlayList != null && !overlayList.isEmpty()) {
                        data.overlays.put(relX * REGION_SIZE_BLOCKS + relZ, overlayList);
                    }
                }
            }
        }
    }

    /**
     * Works out the light level for a surface pixel.
     *
     * <p>Compare Xaero's WorldDataReader.java lines 557-559:</p>
     * <ul>
     *   <li>sky light is only consulted in cave mode, below light 15, where the world has sky light</li>
     *   <li>the end has worldHasSkylight false, so sky light is never used there</li>
     * </ul>
     *
     * @param section the section
     * @param lx local X
     * @param ly local Y
     * @param lz local Z
     * @param worldY world Y
     * @param heightMapValue the heightmap value for this column
     * @param overlayList the overlays on this pixel
     * @param lightMode SURFACE or CAVE
     * @param worldHasSkylight whether the dimension has sky light
     * @return the light level, 0-15
     */
    private static byte calculateSurfaceLight(ChunkSectionParser.SectionData section,
                                                int lx, int ly, int lz, int worldY,
                                                int heightMapValue,
                                                List<OverlayData> overlayList,
                                                LightMode lightMode,
                                                boolean worldHasSkylight) {
        byte blockLight = ChunkSectionParser.getBlockLight(section, lx, ly, lz);
        byte skyLight = ChunkSectionParser.getSkyLight(section, lx, ly, lz);

        // Is there a fluid overlay, water or lava?
        boolean hasFluidOverlay = overlayList != null && overlayList.stream()
            .anyMatch(o -> BlockPropertyResolver.isWater(o.blockName));

        // Is the position open to the sky, i.e. above the heightmap?
        boolean hasSkyAccess = worldY >= heightMapValue;

        // Does the block emit light?
        boolean isGlowing = BlockPropertyResolver.isGlowing(
            ChunkSectionParser.getBlockStateAt(section, lx, ly, lz).name());

        return lightMode.calculateEffectiveLight(
            blockLight, skyLight, hasSkyAccess, hasFluidOverlay, isGlowing, worldHasSkylight);
    }

    /**
     * Serialises the region into Xaero's format.
     *
     * <p>Two cases worth keeping apart:</p>
     * <ul>
     *   <li>chunk present but the pixel is void: write AIR + void, which renders deep purple</li>
     *   <li>chunk absent, i.e. not generated: write an empty tile (tileMarker = -1), which the client skips</li>
     * </ul>
     *
     * <p>Coordinates:</p>
     * <ul>
     *   <li>one tile is one Minecraft chunk, both being 16x16</li>
     *   <li>chunkX = tileChunkO * 4 + tileI</li>
     *   <li>chunkZ = tileChunkP * 4 + tileJ</li>
     * </ul>
     *
     * @param data the region
     * @param minBuildHeight the lowest buildable Y
     * @return the region in Xaero's format
     * @throws IOException if writing fails
     */
    static byte[] serializeToXaeroFormat(MapRegionData data, int minBuildHeight) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            // Version header
            dos.writeByte(0xFF);
            dos.writeInt((MAJOR_VERSION << 16) | MINOR_VERSION);

            Map<String, Integer> blockPalette = new LinkedHashMap<>();
            Map<String, Integer> biomePalette = new LinkedHashMap<>();

            // 8x8 TileChunks
            for (int tileChunkO = 0; tileChunkO < TILE_CHUNKS_PER_REGION; tileChunkO++) {
                for (int tileChunkP = 0; tileChunkP < TILE_CHUNKS_PER_REGION; tileChunkP++) {
                    dos.writeByte((tileChunkO << 4) | tileChunkP);

                    // 4x4 Tiles
                    for (int tileI = 0; tileI < TILES_PER_TILE_CHUNK; tileI++) {
                        for (int tileJ = 0; tileJ < TILES_PER_TILE_CHUNK; tileJ++) {
                            // Which chunk this tile corresponds to.
                            // A tile is 16x16 blocks, i.e. exactly one Minecraft chunk.
                            int chunkX = tileChunkO * 4 + tileI;
                            int chunkZ = tileChunkP * 4 + tileJ;

                            // Where this chunk's pixels start.
                            int baseX = chunkX * 16;  // chunk's first X
                            int baseZ = chunkZ * 16;  // chunk's first Z

                            // Does the chunk exist?
                            if (!data.chunkExists[chunkX][chunkZ]) {
                                // Not generated: write the empty-tile marker.
                                // In Xaero's format that is tileMarker = -1.
                                dos.writeInt(-1);
                                continue;
                            }

                            // Present: write the tile's 16x16 pixels.
                            // The first pixel's params double as the tile marker, so they cannot be -1.
                            for (int bx = 0; bx < BLOCKS_PER_TILE; bx++) {
                                for (int bz = 0; bz < BLOCKS_PER_TILE; bz++) {
                                    int rx = baseX + bx;
                                    int rz = baseZ + bz;

                                    if (!data.hasData[rx][rz]) {
                                        // Chunk present but this pixel is void: AIR with a null biome.
                                        // Compare Xaero's prepareForWriting: state=AIR, biome=null, height=defaultHeight.
                                        String emptyBlockName = "minecraft:air";
                                        int emptyHeight = minBuildHeight;
                                        int emptyParams = 0;

                                        emptyParams |= 1;  // not grass
                                        emptyParams |= 0 << 8;  // light = 0
                                        emptyParams |= encodeHeightToParams(emptyHeight);

                                        if (!blockPalette.containsKey(emptyBlockName)) {
                                            emptyParams |= 0x200000;
                                        }

                                        dos.writeInt(emptyParams);

                                        if (!blockPalette.containsKey(emptyBlockName)) {
                                            writeBlockStateNbt(emptyBlockName, dos);
                                            blockPalette.put(emptyBlockName, blockPalette.size());
                                        } else {
                                            dos.writeInt(blockPalette.get(emptyBlockName));
                                        }

                                        continue;
                                    }

                                    // An ordinary pixel.
                                    String blockName = data.blockNames[rx][rz];
                                    if (blockName == null) blockName = DEFAULT_BLOCK;
                                    int height = data.heightMap[rx][rz];
                                    int topY = data.topBlockY[rx][rz];
                                    int topHeight = (topY >= 0) ? topY : height;
                                    String biomeName = data.biomeNames[rx][rz];
                                    if (biomeName == null) biomeName = DEFAULT_BIOME;
                                    int light = data.lightMap[rx][rz];
                                    List<OverlayData> overlays = data.overlays.get(rx * REGION_SIZE_BLOCKS + rz);
                                    boolean hasOverlays = overlays != null && !overlays.isEmpty();
                                    boolean isGrass = BlockPropertyResolver.isGrassBlock(blockName);
                                    boolean topHeightDifferent = (height != topHeight);

                                    // Build params
                                    int params = 0;
                                    if (!isGrass) params |= 1;
                                    if (hasOverlays) params |= 2;
                                    params |= light << 8;
                                    params |= encodeHeightToParams(height);
                                    if (biomeName != null) params |= 0x100000;
                                    if (topHeightDifferent) params |= 0x1000000;

                                    // Mark new palette entries
                                    if (!isGrass && !blockPalette.containsKey(blockName)) params |= 0x200000;
                                    if (biomeName != null && !biomePalette.containsKey(biomeName)) params |= 0x400000;

                                    dos.writeInt(params);

                                    // BlockState data
                                    if (!isGrass) {
                                        if (blockPalette.containsKey(blockName)) {
                                            dos.writeInt(blockPalette.get(blockName));
                                        } else {
                                            writeBlockStateNbt(blockName, dos);
                                            blockPalette.put(blockName, blockPalette.size());
                                        }
                                    }

                                    // TopHeight
                                    if (topHeightDifferent) {
                                        dos.writeByte(topHeight & 0xFF);
                                    }

                                    // Overlay data
                                    if (hasOverlays) {
                                        dos.writeByte(overlays.size());
                                        for (OverlayData overlay : overlays) {
                                            serializeOverlay(overlay, dos, blockPalette);
                                        }
                                    }

                                    // Biome data
                                    if (biomeName != null) {
                                        if (biomePalette.containsKey(biomeName)) {
                                            dos.writeInt(biomePalette.get(biomeName));
                                        } else {
                                            dos.writeUTF(biomeName);
                                            biomePalette.put(biomeName, biomePalette.size());
                                        }
                                    }
                                }
                            }

                            // Tile footer
                            dos.writeByte(1);
                            dos.writeInt(Integer.MAX_VALUE);
                            dos.writeByte(0);
                        }
                    }
                }
            }
        }
        return baos.toByteArray();
    }

    /**
     * Packs a height into the params field.
     *
     * @param height the height
     * @return the packed params
     */
    private static int encodeHeightToParams(int height) {
        return (height & 0xFF) << 12 | ((height >> 8) & 0xF) << 25;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Writes a block state's NBT to the stream.
     *
     * @param blockName the block name
     * @param dos the output stream
     * @throws IOException if writing fails
     */
    private static void writeBlockStateNbt(String blockName, DataOutputStream dos) throws IOException {
        ByteArrayOutputStream nbtBaos = new ByteArrayOutputStream();
        try (DataOutputStream nbtDos = new DataOutputStream(nbtBaos)) {
            nbtDos.writeByte(10);  // TAG_Compound
            nbtDos.writeShort(0);  // empty name
            nbtDos.writeByte(8);   // TAG_String
            nbtDos.writeUTF("Name");
            nbtDos.writeUTF(blockName);
            nbtDos.writeByte(0);   // TAG_End
        }
        dos.write(nbtBaos.toByteArray());
    }

    /**
     * Writes an overlay to the stream.
     *
     * @param overlay the overlay
     * @param dos the output stream
     * @param blockPalette the block palette
     * @throws IOException if writing fails
     */
    private static void serializeOverlay(OverlayData overlay, DataOutputStream dos,
                                          Map<String, Integer> blockPalette) throws IOException {
        boolean isWater = BlockPropertyResolver.isWater(overlay.blockName);
        int opacity = overlay.opacity;
        int light = overlay.light;

        int overlayParams = 0;
        if (!isWater) overlayParams |= 1;
        overlayParams |= light << 4;
        overlayParams |= opacity << 11;
        if (!isWater && !blockPalette.containsKey(overlay.blockName)) {
            overlayParams |= 0x400;
        }

        dos.writeInt(overlayParams);

        if (!isWater) {
            if (blockPalette.containsKey(overlay.blockName)) {
                dos.writeInt(blockPalette.get(overlay.blockName));
            } else {
                writeBlockStateNbt(overlay.blockName, dos);
                blockPalette.put(overlay.blockName, blockPalette.size());
            }
        }
    }

    // ========== Data structures ==========

    /**
     * Adds an overlay to a pixel's list, accumulating as Xaero does.
     *
     * Compare Xaero's OverlayBuilder.build():
     * - same block type: increaseOpacity(lightBlock)
     * - different block type: start a new overlay layer
     *
     * One deliberate difference: transparent blocks with lightBlock=0, such as seagrass and
     * kelp, get a minimum opacity of 1 so their colour still shows. Xaero's client does not
     * rely on opacity alone when it samples a texture, but data generated server-side needs
     * a non-zero opacity to render.
     *
     * @param overlayList the pixel's overlays
     * @param blockName the block name
     * @param y the Y coordinate
     * @param opacityToAdd the opacity to add, i.e. the block's lightBlock
     * @param light the light level
     */
    private static List<OverlayData> addOverlay(List<OverlayData> overlayList, String blockName, int y, int opacityToAdd, int light) {
        if (overlayList == null) {
            overlayList = new ArrayList<>(2);
        }
        // Never add more than 15 at once.
        if (opacityToAdd > 15) {
            opacityToAdd = 15;
        }

        // Transparent plants such as seagrass and kelp have lightBlock=0, which would leave
        // opacity at 0 and render nothing. They are TransparentBlocks with a real colour,
        // so give them a minimum opacity of 1.
        if (opacityToAdd == 0 && !BlockPropertyResolver.isWater(blockName)) {
            // Is this a water plant or another transparent plant?
            String blockId = blockName.toLowerCase();
            if (blockId.contains("seagrass") || blockId.contains("kelp") ||
                BlockPropertyResolver.isTransparent(blockName)) {
                opacityToAdd = 1;  // the minimum that still renders
            }
        }

        // Is the last overlay the same block type?
        OverlayData lastOverlay = overlayList.isEmpty() ? null : overlayList.get(overlayList.size() - 1);
        if (lastOverlay != null && lastOverlay.blockName.equals(blockName)) {
            // Same block: accumulate opacity, as Overlay.increaseOpacity does.
            lastOverlay.opacity = Math.min(15, lastOverlay.opacity + opacityToAdd);
        } else {
            // Different block: start a new layer.
            overlayList.add(new OverlayData(blockName, y, opacityToAdd, light));
        }
        return overlayList;
    }

    /**
     * One overlay layer.
     *
     * <p>A transparent block covering the pixel.</p>
     */
    static class OverlayData {
        /**
         * The block name.
         */
        final String blockName;

        /**
         * The Y coordinate.
         */
        final int y;

        /**
         * Opacity, which accumulates as layers are added.
         */
        int opacity;

        /**
         * The light level.
         */
        final int light;

        /**
         * Creates an overlay.
         *
         * @param blockName the block name
         * @param y the Y coordinate
         * @param opacity the opacity
         * @param light the light level
         */
        OverlayData(String blockName, int y, int opacity, int light) {
            this.blockName = blockName;
            this.y = y;
            this.opacity = opacity;
            this.light = light;
        }
    }

    /**
     * The region being built.
     *
     * <p>Everything read out of the region file, before serialisation.</p>
     */
    static class MapRegionData {
        /**
         * Block names, 512x512.
         */
        final String[][] blockNames;

        /**
         * Surface Y per pixel, 512x512.
         */
        final int[][] topBlockY;

        /**
         * Biome names, 512x512.
         */
        final String[][] biomeNames;

        /**
         * Heightmap, 512x512.
         */
        final int[][] heightMap;

        /**
         * Light levels, 512x512.
         */
        final byte[][] lightMap;

        /**
         * Whether each pixel has data, 512x512.
         */
        final boolean[][] hasData;

        /**
         * Whether each chunk exists, 32x32.
         */
        final boolean[][] chunkExists;

        /**
         * Overlays, stored sparsely.
         *
         * <p>key: pixelIndex = x * REGION_SIZE_BLOCKS + z</p>
         * <p>Value: that pixel's overlay layers.</p>
         *
         * <p>Most pixels have none, so a map rather than a 2D array saves roughly 5.5MB per region.</p>
         */
        final Map<Integer, List<OverlayData>> overlays;

        /**
         * The lowest buildable Y.
         */
        final int minBuildHeight;

        /**
         * The lighting mode, kept for debugging and statistics.
         */
        final LightMode lightMode;

        /**
         * Creates the region being built.
         *
         * @param minBuildHeight the lowest buildable Y
         * @param lightMode SURFACE or CAVE
         */
        MapRegionData(int minBuildHeight, LightMode lightMode) {
            this.minBuildHeight = minBuildHeight;
            this.lightMode = lightMode;
            blockNames = new String[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
            topBlockY = new int[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
            for (int x = 0; x < REGION_SIZE_BLOCKS; x++) {
                Arrays.fill(topBlockY[x], -1);
            }
            biomeNames = new String[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
            heightMap = new int[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
            for (int x = 0; x < REGION_SIZE_BLOCKS; x++) {
                Arrays.fill(heightMap[x], minBuildHeight);
            }
            lightMap = new byte[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
            hasData = new boolean[REGION_SIZE_BLOCKS][REGION_SIZE_BLOCKS];
            chunkExists = new boolean[CHUNKS_PER_REGION][CHUNKS_PER_REGION];  // 32x32 chunk presence
            overlays = new HashMap<>();  // sparse, so most pixels cost nothing
        }
    }
}
