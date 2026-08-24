package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.DimensionScanConfig;
import com.mapsyncer.config.ModConfig.ScanMode;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.mca.LightMode;
import com.mapsyncer.mca.RegionConverterStandalone;
import com.mapsyncer.mca.RegionConverterStandalone.CaveModeParams;
import com.mapsyncer.mca.RegionConverterStandalone.ConvertedRegion;
import com.mapsyncer.server.RegionScanner.DimensionRegions;
import com.mapsyncer.server.RegionScanner.RegionCoords;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Drives the conversion pipeline: scan, convert, write.
 *
 * Three ways in:
 * - everything: every region of every dimension
 * - one dimension: every region of that dimension
 * - one region: a single region of one dimension
 *
 * Timestamp caching decides what actually needs converting, so unchanged files are not
 * processed twice. Incremental runs only touch MCA files whose timestamps moved.
 */
public class ConversionOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversionOrchestrator.class);

    /** Whether a conversion is in progress. */
    private static volatile boolean isRunning = false;
    private static final AtomicBoolean RUN_LOCK = new AtomicBoolean(false);

    /** Regions handled so far. */
    private static volatile int processedCount = 0;

    /** Regions skipped because their timestamp had not moved. */
    private static volatile int skippedCount = 0;

    /** Regions in total. */
    private static volatile int totalCount = 0;

    /** What the conversion is doing right now. */
    private static volatile String currentStatus = "idle";

    /** The dimension being converted. */
    private static volatile ResourceKey<Level> currentDimension = null;

    /** Dimensions finished so far, for the "generation complete" message. */
    private static volatile List<String> completedDimensions = new ArrayList<>();

    /** Where the generated cache is written. */
    public static final Path CACHE_DIR = Path.of("server_map_cache");

    /** The MCA timestamp cache. */
    private static McaTimestampCache timestampCache;

    /**
     * Outcome of converting a single region.
     */
    public enum SingleRegionResult {
        /** Converted. */
        SUCCESS,
        /** No such region. */
        REGION_NOT_FOUND,
        /** Conversion failed. */
        CONVERSION_FAILED,
        /** Another conversion is already running. */
        ALREADY_RUNNING
    }

    /**
     * Empties a dimension's cache directory.
     *
     * @param dimCacheDir the dimension's cache directory
     */
    private static void clearDimensionCache(Path dimCacheDir) {
        if (!Files.exists(dimCacheDir)) {
            LOGGER.info("No existing cache to clear for dimension: {}", dimCacheDir);
            return;
        }

        try {
            // Delete the contents recursively.
            Files.walk(dimCacheDir)
                    .sorted((a, b) -> -a.compareTo(b)) // files before their directories
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                            LOGGER.debug("Deleted: {}", path);
                        } catch (IOException e) {
                            LOGGER.warn("Failed to delete: {}", path);
                        }
                    });
            LOGGER.info("Cleared cache directory: {}", dimCacheDir);
        } catch (IOException e) {
            LOGGER.error("Failed to clear dimension cache: {}", dimCacheDir, e);
        }
    }

    /**
     * The timestamp cache, creating it on first use.
     *
     * @return the MCA timestamp cache
     */
    private static McaTimestampCache getTimestampCache() {
        if (timestampCache == null) {
            timestampCache = McaTimestampCache.getInstance(CACHE_DIR);
        }
        return timestampCache;
    }

    private static boolean tryStartRun() {
        if (!RUN_LOCK.compareAndSet(false, true)) {
            LOGGER.warn("Conversion already in progress");
            return false;
        }
        isRunning = true;
        return true;
    }

    private static void releaseRun() {
        isRunning = false;
        RUN_LOCK.set(false);
    }

    /**
     * Converts every region of every dimension.
     *
     * @param server the running server
     */
    public static void generateAll(MinecraftServer server) {
        if (!tryStartRun()) {
            return;
        }
        processedCount = 0;
        skippedCount = 0;
        completedDimensions = new ArrayList<>();  // start a fresh list

        // Step 1: Force save all chunks to disk before reading .mca files
        if (!saveAllChunks(server)) {
            LOGGER.error("Failed to save all chunks, aborting map generation");
            releaseRun();
            return;
        }

        List<DimensionRegions> allRegions = RegionScanner.scanAllDimensions(server);
        totalCount = allRegions.stream().mapToInt(d -> d.regions().size()).sum();
        int totalSkippedEmpty = allRegions.stream().mapToInt(DimensionRegions::skippedEmptyCount).sum();
        if (totalCount == 0) {
            LOGGER.info("No regions found to convert");
            releaseRun();
            return;
        }
        LOGGER.info("Starting conversion of {} regions across {} dimensions", totalCount, allRegions.size());
        try {
            for (DimensionRegions dimRegions : allRegions) {
                convertDimension(server, dimRegions, false);
            }
        } finally {
            releaseRun();
            currentStatus = "completed";
            LOGGER.info("Conversion completed: {}/{} regions, {} skipped (empty MCA)", processedCount, totalCount, totalSkippedEmpty);
        }
    }

    /**
     * Converts every region of one dimension.
     *
     * Uses the timestamp cache, so unchanged regions are skipped.
     *
     * @param server the running server
     * @param dimensionId the dimension, e.g. "minecraft:overworld"
     */
    public static void generateDimension(MinecraftServer server, String dimensionId) {
        if (!tryStartRun()) {
            return;
        }
        processedCount = 0;
        skippedCount = 0;
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); releaseRun(); return; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); releaseRun(); return; }

        // Force save all chunks before reading .mca files
        if (!saveAllChunks(server)) {
            LOGGER.error("Failed to save all chunks, aborting map generation");
            releaseRun();
            return;
        }

        RegionScanner.RegionScanResult scanResult = RegionScanner.scanDimension(level);
        List<RegionCoords> regions = scanResult.regions();
        totalCount = regions.size();
        currentDimension = dimKey;
        try {
            convertDimension(server, new DimensionRegions(dimKey, regions, scanResult.skippedEmptyCount()), false);
        } finally {
            releaseRun();
            currentStatus = "completed";
        }
    }

    /**
     * Rebuilds one dimension from scratch.
     *
     * Clears the dimension's cache directory and regenerates every region, ignoring the
     * timestamp cache.
     *
     * @param server the running server
     * @param dimensionId the dimension, e.g. "minecraft:overworld"
     */
    public static void generateDimensionForce(MinecraftServer server, String dimensionId) {
        if (!tryStartRun()) {
            return;
        }
        processedCount = 0;
        skippedCount = 0;
        ResourceKey<Level> dimKey = parseDimensionId(dimensionId, server);
        if (dimKey == null) { LOGGER.error("Unknown dimension: {}", dimensionId); releaseRun(); return; }
        ServerLevel level = server.getLevel(dimKey);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimensionId); releaseRun(); return; }

        // Clear this dimension's cache before regenerating it.
        String fullDimId = dimKey.identifier().toString(); // full ID, namespace included
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);
        Path dimCacheDir = CACHE_DIR.resolve(xaeroDimName);
        clearDimensionCache(dimCacheDir);

        // Force save all chunks before reading .mca files
        if (!saveAllChunks(server)) {
            LOGGER.error("Failed to save all chunks, aborting map generation");
            releaseRun();
            return;
        }

        RegionScanner.RegionScanResult scanResult = RegionScanner.scanDimension(level);
        List<RegionCoords> regions = scanResult.regions();
        totalCount = regions.size();
        currentDimension = dimKey;
        try {
            convertDimension(server, new DimensionRegions(dimKey, regions, scanResult.skippedEmptyCount()), true);
        } finally {
            releaseRun();
            currentStatus = "completed";
        }
    }

    /**
     * Whether one region's MCA file exists.
     *
     * @param server the running server
     * @param dimension the dimension key
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @return the MCA file path, or {@code null} if there is none
     */
    public static Path checkMcaFileExists(MinecraftServer server, ResourceKey<Level> dimension, int regionX, int regionZ) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return null;

        Path regionDir = RegionScanner.getRegionDir(level);

        if (regionDir == null) return null;

        Path mcaPath = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        return Files.exists(mcaPath) ? mcaPath : null;
    }

    /**
     * Converts a single region.
     *
     * @param server the running server
     * @param dimension the dimension key
     * @param regionX region X coordinate
     * @param regionZ region Z coordinate
     * @return what happened
     */
    public static SingleRegionResult generateSingleRegion(MinecraftServer server, ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (RUN_LOCK.get()) {
            LOGGER.warn("Conversion already in progress");
            return SingleRegionResult.ALREADY_RUNNING;
        }

        // Check the MCA file exists before doing anything else.
        Path mcaPath = checkMcaFileExists(server, dimension, regionX, regionZ);
        if (mcaPath == null) {
            LOGGER.warn("MCA file not found for region ({}, {}) in dimension {}", regionX, regionZ, dimension.identifier().getPath());
            return SingleRegionResult.REGION_NOT_FOUND;
        }

        if (!tryStartRun()) {
            return SingleRegionResult.ALREADY_RUNNING;
        }
        totalCount = 1;
        processedCount = 0;
        currentDimension = dimension;
        ServerLevel level = server.getLevel(dimension);
        if (level == null) { LOGGER.error("Level not loaded for dimension: {}", dimension); releaseRun(); return SingleRegionResult.CONVERSION_FAILED; }

        // Force save all chunks before reading .mca files
        if (!saveAllChunks(server)) {
            LOGGER.error("Failed to save all chunks, aborting map generation");
            releaseRun();
            return SingleRegionResult.CONVERSION_FAILED;
        }

        // Cache key is the full dimension ID, so current-layout paths map correctly.
        String fullDimId = dimension.identifier().toString();
        String dimPath = dimension.identifier().getPath(); // used for config lookups

        // Scan settings for this dimension.
        DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);
        ScanMode scanMode = scanConfig.scanMode();
        int caveLayer = scanConfig.getCaveLayer();

        // Xaero's directory name, from the full dimension ID.
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

        // Where the MCA files live (detected at runtime on 1.21+).
        Path regionDir = RegionScanner.getRegionDir(level);

        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", dimension);
            releaseRun();
            return SingleRegionResult.CONVERSION_FAILED;
        }

        // Output directory, including the caves/<layer> subdirectory when relevant.
        Path baseOutputDir = CACHE_DIR.resolve(xaeroDimName);
        Path outputDir;
        if (caveLayer == Integer.MAX_VALUE) {
            outputDir = baseOutputDir;
        } else {
            outputDir = baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
        }

        // Take the real dimension type from the running server.
        DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionType(level.dimensionType());
        LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
            dimPath, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
            dimTypeInfo.minY(), dimTypeInfo.height());

        // Lighting mode and cave parameters, from the config.
        LightMode lightMode;
        CaveModeParams caveParams;
        if (scanMode == ScanMode.CAVE) {
            lightMode = LightMode.CAVE;
            int caveDepth = scanConfig.getCaveDepth(dimTypeInfo.minY());
            caveParams = new CaveModeParams(scanConfig.caveStart(), caveDepth);
            LOGGER.info("Single region generation: using CAVE mode with caveStart={}, caveLayer={}",
                scanConfig.caveStart(), caveLayer);
        } else {
            lightMode = LightMode.SURFACE;
            caveParams = CaveModeParams.NONE;
            LOGGER.info("Single region generation: using SURFACE mode");
        }

        SingleRegionResult result = SingleRegionResult.SUCCESS;
        try {
            Files.createDirectories(outputDir);
            ConvertedRegion converted = RegionConverterStandalone.convertRegion(
                mcaPath, regionX, regionZ, dimTypeInfo, lightMode, caveParams);
            if (converted != null) {
                XaeroWriter.writeRegionFile(outputDir, converted);
                processedCount = 1;
                LOGGER.info("Converted single region: ({}, {})", regionX, regionZ);
            } else {
                LOGGER.warn("Could not convert region ({}, {}): conversion failed", regionX, regionZ);
                result = SingleRegionResult.CONVERSION_FAILED;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write region file", e);
            result = SingleRegionResult.CONVERSION_FAILED;
        }
        finally {
            releaseRun();
            currentStatus = "completed";
        }
        return result;
    }

    /**
     * Converts every region of one dimension.
     *
     * With {@code force} it regenerates everything; otherwise the timestamp cache decides
     * what has changed.
     *
     * @param server the running server
     * @param dimRegions the dimension and its regions
     * @param force whether to regenerate regardless of timestamps
     */
    private static void convertDimension(MinecraftServer server, DimensionRegions dimRegions, boolean force) {
        ServerLevel level = server.getLevel(dimRegions.dimension());
        if (level == null) { LOGGER.error("Level not loaded"); return; }

        currentDimension = dimRegions.dimension();
        // Full dimension ID, namespace included, e.g. "twilightforest:twilight_forest".
        // Used for the Xaero directory mapping so current-layout paths become namespace$path.
        String fullDimId = dimRegions.dimension().identifier().toString();
        // Just the path part, e.g. "twilight_forest".
        // Used for config lookups, since config entries may omit the namespace.
        String dimPath = dimRegions.dimension().identifier().getPath();

        // Scan settings for this dimension, looked up by path.
        DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);
        ScanMode scanMode = scanConfig.scanMode();
        int caveLayer = scanConfig.getCaveLayer();

        // Xaero's directory name, from the full dimension ID.
        String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

        // Where the MCA files live (detected at runtime on 1.21+).
        Path regionDir = RegionScanner.getRegionDir(level);

        // Output directory, including the caves/<layer> subdirectory when relevant.
        Path baseOutputDir = CACHE_DIR.resolve(xaeroDimName);
        Path outputDir;
        if (caveLayer == Integer.MAX_VALUE) {
            // Surface mode: straight into the dimension directory.
            outputDir = baseOutputDir;
        } else {
            // Cave mode: into caves/<layer>.
            outputDir = baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
        }

        try { Files.createDirectories(outputDir); } catch (IOException e) {
            LOGGER.error("Failed to create output directory: {}", outputDir, e);
            return;
        }

        // The region directory has to exist.
        if (regionDir == null) {
            LOGGER.error("Region directory not found for dimension: {}", xaeroDimName);
            return;
        }

        // Take the real dimension type from the running server.
        DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionType(level.dimensionType());
        LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
            dimPath, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
            dimTypeInfo.minY(), dimTypeInfo.height());

        // Lighting mode and cave parameters, from the config.
        LightMode lightMode;
        CaveModeParams caveParams;
        if (scanMode == ScanMode.CAVE) {
            lightMode = LightMode.CAVE;
            int caveDepth = scanConfig.getCaveDepth(dimTypeInfo.minY());
            caveParams = new CaveModeParams(scanConfig.caveStart(), caveDepth);
            LOGGER.info("Dimension {}: using CAVE mode with caveStart={}, caveLayer={}, caveDepth={}",
                xaeroDimName, scanConfig.caveStart(), caveLayer, caveDepth);
        } else {
            lightMode = LightMode.SURFACE;
            caveParams = CaveModeParams.NONE;
            LOGGER.info("Dimension {}: using SURFACE mode", xaeroDimName);
        }

        // Ask the timestamp cache what changed.
        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(CACHE_DIR);
        List<RegionCoords> needsUpdate = force ? dimRegions.regions() : mcaCache.scanAndUpdate(dimPath, regionDir);

        List<RegionCoords> regions = dimRegions.regions();
        Set<RegionCoords> regionSet = new HashSet<>(regions);
        LOGGER.info("Dimension {}: {} total regions, {} need update (force={})", dimPath, regions.size(), needsUpdate.size(), force);

        List<RegionCoords> failedRegions = new ArrayList<>();
        skippedCount = 0;  // reset the skip counter
        long generationTimeSeconds = System.currentTimeMillis() / 1000;  // Unified generation timestamp (seconds)

        // Convert with the standalone MCA parser: faster, and it never loads chunks.
        for (RegionCoords coords : needsUpdate) {
            // Only regions that are actually present.
            if (!regionSet.contains(coords)) {
                continue;
            }

            currentStatus = "Converting region (" + coords.x() + ", " + coords.z() + ")";
            Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");

            // Read the MCA file directly, with the dimension type info.
            ConvertedRegion converted = RegionConverterStandalone.convertRegion(
                mcaPath, coords.x(), coords.z(), dimTypeInfo, lightMode, caveParams);

            if (converted != null) {
                try {
                    Path outputFile = XaeroWriter.writeRegionFile(outputDir, converted);
                    mcaCache.updateTimestamp(dimPath, coords.x(), coords.z(), mcaPath);
                    // Update generation cache with timestamp and hash
                    // relativePath is xaeroDim/regionX_regionZ, or
                    // xaeroDim/caves/layer/regionX_regionZ for cave layers.
                    String relativePath;
                    if (caveLayer == Integer.MAX_VALUE) {
                        relativePath = xaeroDimName + "/" + coords.x() + "_" + coords.z();
                    } else {
                        relativePath = xaeroDimName + "/caves/" + caveLayer + "/" + coords.x() + "_" + coords.z();
                    }
                    genCache.updateWithHash(relativePath, outputFile, generationTimeSeconds);
                } catch (IOException e) {
                    LOGGER.error("Failed to write region file", e);
                    failedRegions.add(coords);
                    continue;
                }
                processedCount++;
                LOGGER.info("Converted region ({}, {}): {}/{}", coords.x(), coords.z(), processedCount, needsUpdate.size());
            } else {
                failedRegions.add(coords);
            }
        }

        // Outside force mode, also pick up regions that were never generated at all.
        if (!force) {
            for (RegionCoords coords : regions) {
                if (needsUpdate.contains(coords)) continue;  // already done above

                // Is there already an output file?
                if (XaeroWriter.regionFileExists(outputDir, coords.x(), coords.z())) {
                    // Present and up to date, so skip it.
                    processedCount++;
                    skippedCount++;
                    LOGGER.debug("Skipped region ({}, {}): unchanged (timestamp match)", coords.x(), coords.z());
                    continue;
                }

                // New region; generate it.
                currentStatus = "Generating new region (" + coords.x() + ", " + coords.z() + ")";
                Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");

                // Convert, with the dimension type info.
                ConvertedRegion converted = RegionConverterStandalone.convertRegion(
                    mcaPath, coords.x(), coords.z(), dimTypeInfo, lightMode, caveParams);

                if (converted != null) {
                    try {
                        Path outputFile = XaeroWriter.writeRegionFile(outputDir, converted);
                        mcaCache.updateTimestamp(dimPath, coords.x(), coords.z(), mcaPath);
                        // Update generation cache with timestamp and hash
                        // relativePath is xaeroDim/regionX_regionZ, or
                        // xaeroDim/caves/layer/regionX_regionZ for cave layers.
                        String relativePath;
                        if (caveLayer == Integer.MAX_VALUE) {
                            relativePath = xaeroDimName + "/" + coords.x() + "_" + coords.z();
                        } else {
                            relativePath = xaeroDimName + "/caves/" + caveLayer + "/" + coords.x() + "_" + coords.z();
                        }
                        genCache.updateWithHash(relativePath, outputFile, generationTimeSeconds);
                    } catch (IOException e) {
                        LOGGER.error("Failed to write region file", e);
                        failedRegions.add(coords);
                        continue;
                    }
                    processedCount++;
                    LOGGER.info("Generated new region ({}, {}): {}/{}", coords.x(), coords.z(), processedCount, totalCount);
                } else {
                    failedRegions.add(coords);
                }
            }
        }

        // Retry whatever failed.
        if (!failedRegions.isEmpty()) {
            LOGGER.warn("Failed to convert {} regions", failedRegions.size());
            for (RegionCoords coords : failedRegions) {
                LOGGER.warn("Failed region: ({}, {})", coords.x(), coords.z());
            }
        }

        // Summarise.
        LOGGER.info("Dimension {} completed: {} total, {} converted, {} skipped (unchanged), {} skipped (empty MCA), {} failed",
            dimPath, regions.size(), processedCount - skippedCount, skippedCount, dimRegions.skippedEmptyCount(), failedRegions.size());

        // Record the friendly name, for the "generation complete" message.
        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimRegions.dimension());
        completedDimensions.add(friendlyName);

        // Persist the timestamp cache.
        mcaCache.saveCache();
        genCache.save();
    }

    /**
     * Flushes every loaded chunk of every dimension to disk.
     *
     * The MCA files have to be current before they are read. Must run on the server thread:
     * C2ME does not allow this concurrently.
     *
     * @param server the running server
     * @return {@code true} if the save succeeded
     */
    private static boolean saveAllChunks(MinecraftServer server) {
        try {
            LOGGER.info("Flushing all chunks to disk...");
            currentStatus = "Saving all chunks to disk...";

            // Must execute on server thread to avoid C2ME ConcurrentModificationException
            // C2ME prevents async calls to saveEverything()
            final boolean[] success = new boolean[1];
            final Throwable[] error = new Throwable[1];

            server.execute(() -> {
                try {
                    server.saveEverything(false, true, true);
                    success[0] = true;
                } catch (Throwable t) {
                    error[0] = t;
                }
            });

            // Wait for save to complete (with timeout)
            long startTime = System.currentTimeMillis();
            long timeoutMs = 60000; // 60 seconds timeout
            while (!success[0] && error[0] == null) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    LOGGER.error("Timeout waiting for chunk save to complete");
                    return false;
                }
                Thread.sleep(100);
            }

            if (error[0] != null) {
                LOGGER.error("Error during chunk flush", error[0]);
                return false;
            }

            LOGGER.info("All chunks flushed to disk successfully");
            return true;
        } catch (InterruptedException e) {
            LOGGER.error("Interrupted while waiting for chunk save", e);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            LOGGER.error("Error during chunk flush", e);
            return false;
        }
    }

    /**
     * Resolves a dimension ID to its key.
     *
     * Accepts:
     * - short names: overworld, the_nether, the_end
     * - full IDs: minecraft:overworld, minecraft:the_nether
     * - modded IDs: twilightforest:twilight_forest
     *
     * @param id the dimension ID
     * @param server the running server
     * @return the dimension key, or {@code null} if the ID does not resolve
     */
    public static ResourceKey<Level> parseDimensionId(String id, MinecraftServer server) {
        String normalized = id.toLowerCase();

        // Canonical names for the vanilla dimensions; input may use any of the forms above.
        switch (normalized) {
            case "overworld", "minecraft:overworld":
                return Level.OVERWORLD;
            case "the_nether", "minecraft:the_nether":
                return Level.NETHER;
            case "the_end", "minecraft:the_end":
                return Level.END;
        }

        // Otherwise parse it as an identifier and look it up.
        try {
            Identifier location = Identifier.parse(id);
            // Search the loaded dimensions for a match.
            for (ServerLevel level : server.getAllLevels()) {
                Identifier dimLocation = level.dimension().identifier();
                if (dimLocation.equals(location) ||
                    dimLocation.getPath().equals(id) ||
                    dimLocation.toString().equals(id)) {
                    return level.dimension();
                }
            }
            LOGGER.warn("Dimension not found: {}", id);
        } catch (Exception e) {
            LOGGER.error("Invalid dimension id format: {}", id, e);
        }

        return null;
    }

    /**
     * Runs a scheduled incremental scan across every dimension.
     *
     * Called periodically by IncrementalUpdateHandler on the server thread. Only regions
     * whose timestamps moved are regenerated.
     *
     * @param server the running server
     */
    public static void performIncrementalScan(MinecraftServer server) {
        if (!tryStartRun()) {
            LOGGER.debug("Conversion already in progress, skipping incremental scan");
            return;
        }

        try {
        List<DirtyRegionTracker.DirtyRegion> dirtySnapshot = ModConfig.SERVER.enableDirtyRegionTracking
                ? DirtyRegionTracker.takeSnapshot(ModConfig.SERVER.maxDirtyRegionsPerIncrementalRun)
                : List.of();
        boolean useDirtySnapshot = !dirtySnapshot.isEmpty();
        if (!useDirtySnapshot && !ModConfig.SERVER.dirtyRegionFallbackFullScan) {
            LOGGER.debug("No dirty regions queued and fallback full scan is disabled");
            releaseRun();
            return;
        }

        boolean forceSaveBeforeScan = ModConfig.SERVER.incrementalForceSaveBeforeScan;
        if (forceSaveBeforeScan && !saveAllChunks(server)) {
            LOGGER.error("Failed to save chunks for incremental scan");
            releaseRun();
            return;
        }

        List<DimensionRegions> allRegions = useDirtySnapshot
                ? buildDirtyDimensionRegions(dirtySnapshot)
                : RegionScanner.scanAllDimensions(server);
        McaTimestampCache mcaCache = getTimestampCache();
        GenerationCache genCache = GenerationCache.getInstance(CACHE_DIR);
        int totalUpdated = 0;
        long generationTimeSeconds = System.currentTimeMillis() / 1000;
        if (useDirtySnapshot) {
            LOGGER.info("Incremental update using {} dirty regions ({} still queued)",
                    dirtySnapshot.size(), DirtyRegionTracker.dirtyCount());
        }

        for (DimensionRegions dimRegions : allRegions) {
            ServerLevel level = server.getLevel(dimRegions.dimension());
            if (level == null) continue;

            // Full dimension ID, namespace included, for the Xaero directory mapping.
            String fullDimId = dimRegions.dimension().identifier().toString();
            String dimPath = dimRegions.dimension().identifier().getPath(); // used for config lookups

            // Scan settings for this dimension.
            DimensionScanConfig scanConfig = ModConfig.SERVER.getConfigForDimension(dimPath);
            ScanMode scanMode = scanConfig.scanMode();
            int caveLayer = scanConfig.getCaveLayer();

            // Xaero's directory name, from the full dimension ID.
            String xaeroDimName = DimensionPathMapping.getInstance().toXaeroDimension(fullDimId);

            // Where the MCA files live (detected at runtime on 1.21+).
            Path regionDir = RegionScanner.getRegionDir(level);
            if (regionDir == null) continue;

            // Output directory, including the caves/<layer> subdirectory when relevant.
            Path baseOutputDir = CACHE_DIR.resolve(xaeroDimName);
            Path outputDir;
            if (caveLayer == Integer.MAX_VALUE) {
                outputDir = baseOutputDir;
            } else {
                outputDir = baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));
            }

            // Take the real dimension type from the running server.
            DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionType(level.dimensionType());

            // Lighting mode and cave parameters.
            LightMode lightMode;
            CaveModeParams caveParams;
            if (scanMode == ScanMode.CAVE) {
                lightMode = LightMode.CAVE;
                int caveDepth = scanConfig.getCaveDepth(dimTypeInfo.minY());
                caveParams = new CaveModeParams(scanConfig.caveStart(), caveDepth);
            } else {
                lightMode = LightMode.SURFACE;
                caveParams = CaveModeParams.NONE;
            }

            Set<DirtyRegionTracker.DirtyRegion> dirtyForDimension = new LinkedHashSet<>();
            java.util.List<RegionCoords> needsUpdate = new ArrayList<>();
            if (useDirtySnapshot) {
                Set<RegionCoords> uniqueCoords = new LinkedHashSet<>();
                for (DirtyRegionTracker.DirtyRegion dirty : dirtySnapshot) {
                    if (dirty.matches(dimRegions.dimension())) {
                        dirtyForDimension.add(dirty);
                        uniqueCoords.add(dirty.toRegionCoords());
                    }
                }
                needsUpdate.addAll(uniqueCoords);
            } else if (ModConfig.SERVER.dirtyRegionFallbackFullScan) {
                needsUpdate = mcaCache.scanAndUpdate(dimPath, regionDir);
            }

            if (needsUpdate.isEmpty()) {
                LOGGER.debug("No updates needed for dimension {}", dimPath);
                continue;
            }

            LOGGER.info("Dimension {}: {} regions need incremental update (mode={}, hasSkylight={})",
                dimPath, needsUpdate.size(), scanMode, dimTypeInfo.hasSkylight());

            try {
                Files.createDirectories(outputDir);
            } catch (IOException e) {
                LOGGER.error("Failed to create output directory: {}", outputDir, e);
                continue;
            }

            for (RegionCoords coords : needsUpdate) {
                Path mcaPath = regionDir.resolve("r." + coords.x() + "." + coords.z() + ".mca");
                if (!Files.exists(mcaPath)) {
                    if (forceSaveBeforeScan) {
                        markDirtyProcessed(dirtyForDimension, coords);
                    }
                    continue;
                }

                DirtyRegionTracker.DirtyRegion dirtyRegion = findDirtyRegion(dirtyForDimension, coords);
                if (!forceSaveBeforeScan && dirtyRegion != null && !isDirtyRegionReady(mcaPath, dirtyRegion)) {
                    continue;
                }

                ConvertedRegion converted = RegionConverterStandalone.convertRegion(
                    mcaPath, coords.x(), coords.z(), dimTypeInfo, lightMode, caveParams);

                if (converted != null) {
                    try {
                        Path outputFile = XaeroWriter.writeRegionFile(outputDir, converted);
                        mcaCache.updateTimestamp(dimPath, coords.x(), coords.z(), mcaPath);

                        // Update GenerationCache with correct relativePath format
                        String relativePath;
                        if (caveLayer == Integer.MAX_VALUE) {
                            relativePath = xaeroDimName + "/" + coords.x() + "_" + coords.z();
                        } else {
                            relativePath = xaeroDimName + "/caves/" + caveLayer + "/" + coords.x() + "_" + coords.z();
                        }
                        genCache.updateWithHash(relativePath, outputFile, generationTimeSeconds);

                        totalUpdated++;
                        markDirtyProcessed(dirtyForDimension, coords);
                        LOGGER.debug("Incrementally updated region ({}, {}) in {} (layer={})", coords.x(), coords.z(), dimPath, caveLayer == Integer.MAX_VALUE ? "surface" : caveLayer);
                    } catch (IOException e) {
                        LOGGER.error("Failed to write region file during incremental update", e);
                    }
                }
            }
        }

        if (totalUpdated > 0) {
            LOGGER.info("Incremental scan completed: {} regions updated", totalUpdated);
            mcaCache.saveCache();
            genCache.save();
        }
        currentStatus = "completed";
        releaseRun();
        } catch (Exception e) {
            LOGGER.error("Unexpected error during incremental scan", e);
        } finally {
            if (RUN_LOCK.get()) {
                releaseRun();
            }
        }
    }

    private static List<DimensionRegions> buildDirtyDimensionRegions(List<DirtyRegionTracker.DirtyRegion> dirtySnapshot) {
        Map<ResourceKey<Level>, LinkedHashSet<RegionCoords>> byDimension = new HashMap<>();
        for (DirtyRegionTracker.DirtyRegion dirty : dirtySnapshot) {
            byDimension.computeIfAbsent(dirty.dimension(), ignored -> new LinkedHashSet<>())
                    .add(dirty.toRegionCoords());
        }

        List<DimensionRegions> result = new ArrayList<>(byDimension.size());
        for (Map.Entry<ResourceKey<Level>, LinkedHashSet<RegionCoords>> entry : byDimension.entrySet()) {
            result.add(new DimensionRegions(entry.getKey(), new ArrayList<>(entry.getValue()), 0));
        }
        return result;
    }

    private static DirtyRegionTracker.DirtyRegion findDirtyRegion(
            Set<DirtyRegionTracker.DirtyRegion> dirtyRegions, RegionCoords coords) {
        if (dirtyRegions == null || dirtyRegions.isEmpty()) {
            return null;
        }
        for (DirtyRegionTracker.DirtyRegion dirty : dirtyRegions) {
            if (dirty.regionX() == coords.x() && dirty.regionZ() == coords.z()) {
                return dirty;
            }
        }
        return null;
    }

    private static boolean isDirtyRegionReady(Path mcaPath, DirtyRegionTracker.DirtyRegion dirtyRegion) {
        try {
            long mcaModifiedMillis = Files.getLastModifiedTime(mcaPath).toMillis();
            if (mcaModifiedMillis < dirtyRegion.latestDirtyAtMillis()) {
                LOGGER.debug("Deferring dirty region ({}, {}) in {}: MCA mtime {} < dirty time {}",
                        dirtyRegion.regionX(), dirtyRegion.regionZ(), dirtyRegion.dimension().identifier(),
                        mcaModifiedMillis, dirtyRegion.latestDirtyAtMillis());
                return false;
            }
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not read MCA timestamp for {}, keeping dirty flag", mcaPath);
            return false;
        }
    }

    private static void markDirtyProcessed(Set<DirtyRegionTracker.DirtyRegion> dirtyRegions, RegionCoords coords) {
        if (dirtyRegions == null || dirtyRegions.isEmpty()) {
            return;
        }

        for (DirtyRegionTracker.DirtyRegion dirty : dirtyRegions) {
            if (dirty.regionX() == coords.x() && dirty.regionZ() == coords.z()) {
                DirtyRegionTracker.markProcessed(dirty);
            }
        }
    }

    /**
     * Whether a conversion is running.
     *
     * @return {@code true} while one is in progress
     */
    public static boolean isRunning() { return isRunning; }

    /**
     * Regions handled so far.
     *
     * @return the processed count
     */
    public static int getProcessedCount() { return processedCount; }

    /**
     * Regions in total.
     *
     * @return the total count
     */
    public static int getTotalCount() { return totalCount; }

    /**
     * Regions actually regenerated this run, excluding skips.
     *
     * @return the updated count
     */
    public static int getUpdatedCount() { return processedCount - skippedCount; }

    /**
     * Regions skipped because their timestamp had not moved.
     *
     * @return the skipped count
     */
    public static int getSkippedCount() { return skippedCount; }

    /**
     * What the conversion is doing right now.
     *
     * @return the status text
     */
    public static String getStatus() { return currentStatus; }

    /**
     * The dimension being converted.
     *
     * @return the dimension key, or {@code null} when idle
     */
    public static ResourceKey<Level> getCurrentDimension() { return currentDimension; }

    /**
     * Dimensions finished this run.
     *
     * @return their friendly names
     */
    public static List<String> getCompletedDimensions() { return completedDimensions; }

    /**
     * Cache statistics for one dimension.
     *
     * @param dimension the dimension's friendly name
     * @param regionCount how many regions are cached
     * @param sizeBytes how much disk they take, in bytes
     */
    public record DimensionCacheStats(String dimension, int regionCount, long sizeBytes) {
        /**
         * @return the cache size in MB
         *
         * @return the cache size in MB
         */
        public double sizeMB() {
            return sizeBytes / (1024.0 * 1024.0);
        }
    }

    /**
     * Cache statistics per dimension.
     *
     * Walks the cache directory counting regions and adding up file sizes.
     *
     * @return one entry per dimension that has cached regions
     */
    public static List<DimensionCacheStats> getCacheStats() {
        List<DimensionCacheStats> stats = new ArrayList<>();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();

        if (!Files.exists(CACHE_DIR)) {
            return stats;
        }

        try (DirectoryStream<Path> dimDirs = Files.newDirectoryStream(CACHE_DIR)) {
            for (Path dimDir : dimDirs) {
                if (!dimDir.toFile().isDirectory()) continue;

                String dimName = dimDir.getFileName().toString();
                String friendlyName = dimMapping.getFriendlyName(dimName);

                int regionCount = 0;
                long totalSize = 0;

                // Every zip under this dimension, cave layers included.
                // A stream can only be consumed once, so collect first, then count and measure.
                try (Stream<Path> files = Files.walk(dimDir)) {
                    List<Path> zipFiles = files
                            .filter(p -> p.toString().endsWith(".zip"))
                            .toList();

                    regionCount = zipFiles.size();
                    totalSize = zipFiles.stream()
                            .mapToLong(p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .sum();
                }

                if (regionCount > 0) {
                    stats.add(new DimensionCacheStats(friendlyName, regionCount, totalSize));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to get cache stats", e);
        }

        return stats;
    }
}
