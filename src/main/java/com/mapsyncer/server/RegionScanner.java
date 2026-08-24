package com.mapsyncer.server;

import com.mapsyncer.platform.Platform;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the region files of a world.
 *
 * Handles both save layouts:
 * - current (26.1+): dimensions/&lt;namespace&gt;/&lt;dimension&gt;/region/
 * - legacy: region/, DIM-1/region/, DIM1/region/, DIM{id}/region/
 *
 * The layout is detected at runtime, current format first. Empty (0-byte) MCA files are
 * skipped, since they hold no chunks.
 */
public class RegionScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionScanner.class);

    /** Matches MCA/MCR region file names. */
    private static final Pattern REGION_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mc[ar]$");

    /**
     * A region's coordinates.
     *
     * @param x region X coordinate
     * @param z region Z coordinate
     */
    public record RegionCoords(int x, int z) {
    }

    /**
     * The result of scanning one directory.
     *
     * @param regions regions that were found
     * @param skippedEmptyCount how many empty files were skipped
     */
    public record RegionScanResult(List<RegionCoords> regions, int skippedEmptyCount) {
    }

    /**
     * The regions of one dimension.
     *
     * @param dimension the dimension key
     * @param regions regions that were found
     * @param skippedEmptyCount how many empty files were skipped
     */
    public record DimensionRegions(net.minecraft.resources.ResourceKey<Level> dimension, List<RegionCoords> regions, int skippedEmptyCount) {
    }

    /**
     * Scans the region files of every loaded dimension.
     *
     * @param server the running server
     * @return one entry per dimension
     */
    public static List<DimensionRegions> scanAllDimensions(MinecraftServer server) {
        List<ServerLevel> levels = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimId = level.dimension().identifier().getPath();
            if (levels.stream().noneMatch(l -> l.dimension().identifier().getPath().equals(dimId))) {
                levels.add(level);
            }
        }

        List<DimensionRegions> result = new ArrayList<>();
        for (ServerLevel level : levels) {
            Path regionDir = getRegionDir(level);
            RegionScanResult scanResult = regionDir == null
                    ? new RegionScanResult(List.of(), 0)
                    : scanRegionDirectory(regionDir);
            result.add(new DimensionRegions(level.dimension(), scanResult.regions(), scanResult.skippedEmptyCount()));
        }
        return result;
    }

    /**
     * Scans the region files of one dimension.
     *
     * @param level the dimension
     * @return what was found
     */
    public static RegionScanResult scanDimension(ServerLevel level) {
        Path regionDir = getRegionDir(level);
        if (regionDir == null) {
            return new RegionScanResult(List.of(), 0);
        }
        return scanRegionDirectory(regionDir);
    }

    /**
     * Locates the region directory of a dimension.
     *
     * Tries, in order:
     * 1. whatever the platform reports directly (Paper asks Bukkit for the world's path)
     * 2. current format (26.1+): dimensions/&lt;namespace&gt;/&lt;dimension&gt;/region/
     * 3. legacy format: region/ (overworld), DIM-1/region/ (nether), DIM1/region/ (end)
     * 4. modded legacy: DIM{id}/region/
     *
     * What it finds is cached in DimensionPathMapping.
     *
     * @param level the dimension
     * @return the region directory, or {@code null} if none was found
     */
    public static Path getRegionDir(ServerLevel level) {
        try {
            // Prefer the directory the platform names outright (Paper asks Bukkit).
            Path platformRegionDir = Platform.get().regionDir(level);
            if (platformRegionDir != null && Files.isDirectory(platformRegionDir)) {
                return platformRegionDir.toRealPath();
            }

            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
            if (!Files.exists(worldRoot)) return null;
            worldRoot = worldRoot.toRealPath();

            DimensionPathMapping mapping = DimensionPathMapping.getInstance();
            String dimId = level.dimension().identifier().toString();

            // Otherwise probe the layouts, current format first.
            Path regionDir = mapping.detectRegionDir(worldRoot, dimId);

            if (regionDir != null && Files.exists(regionDir)) {
                return regionDir.toRealPath();
            }

            LOGGER.warn("Region directory not found for dimension {} after detection", dimId);
            return null;
        } catch (IOException e) {
            LOGGER.error("Failed to get region directory", e);
            return null;
        }
    }

    /**
     * Lists the MCA files in a directory.
     *
     * Region coordinates come from the file names; empty (0-byte) files are skipped.
     *
     * @param regionDir the directory to scan
     * @return the regions found and the number of empty files skipped
     */
    public static RegionScanResult scanRegionDirectory(Path regionDir) {
        List<RegionCoords> regions = new ArrayList<>();
        if (!Files.exists(regionDir)) {
            return new RegionScanResult(regions, 0);
        }

        int skippedEmpty = 0;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                Matcher matcher = REGION_PATTERN.matcher(fileName);
                if (matcher.matches()) {
                    // Skip empty (0KB) MCA files - they contain no chunk data
                    try {
                        long fileSize = Files.size(file);
                        if (fileSize == 0) {
                            skippedEmpty++;
                            LOGGER.debug("Skipping empty MCA file: {} (0 bytes)", fileName);
                            continue;
                        }
                    } catch (IOException e) {
                        LOGGER.warn("Failed to check file size for {}", fileName, e);
                        continue;
                    }

                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    regions.add(new RegionCoords(regionX, regionZ));
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to scan region directory: {}", regionDir, e);
        }

        if (skippedEmpty > 0) {
            LOGGER.info("Skipped {} empty (0KB) MCA files in {}", skippedEmpty, regionDir);
        }

        return new RegionScanResult(regions, skippedEmpty);
    }
}
