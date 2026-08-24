package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.DimensionScanConfig;
import com.mapsyncer.config.ModConfig.ScanMode;
import com.mapsyncer.mca.DimensionTypeInfo;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Detects the save layout of each dimension on first run and writes it into the config.
 *
 * What it does:
 * 1. scans every loaded dimension the first time a map generation runs
 * 2. works out which layout that dimension uses (current dimensions/ or legacy DIM)
 * 3. adds a recommended scan config for any dimension not configured yet
 *
 * Layouts on Minecraft 1.21+:
 * - current: dimensions/minecraft/overworld/region, dimensions/minecraft/the_nether/region
 * - legacy: region/, DIM-1/region/, DIM1/region/, DIM{id}/region/
 */
public class DimensionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionRegistry.class);

    /** Whether the one-time registration has already run. */
    private static volatile boolean hasRegistered = false;

    /**
     * Built-in recommended settings for dimensions we know about.
     *
     * Vanilla dimensions get specific settings; modded ones fall back to a preset or to
     * surface mode. The dimension type info drives lighting and height range when parsing
     * region files offline.
     */
    private static final Map<String, DimensionScanConfig> PRESET_CONFIGS = new LinkedHashMap<>();

    static {
        // Vanilla dimensions; paths are detected at runtime on 1.21+.
        // Overworld: surface mode, has skylight, minY=-64, height=384
        PRESET_CONFIGS.put("minecraft:overworld",
                new DimensionScanConfig("minecraft:overworld", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.overworld()));

        // Nether: cave mode, no skylight, has a ceiling, minY=0, height=256
        PRESET_CONFIGS.put("minecraft:the_nether",
                new DimensionScanConfig("minecraft:the_nether", ScanMode.CAVE, 63,
                    DimensionTypeInfo.nether()));

        // End: surface mode, no skylight, no ceiling, minY=0, height=256
        PRESET_CONFIGS.put("minecraft:the_end",
                new DimensionScanConfig("minecraft:the_end", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.theEnd()));

        // Presets for modded dimensions.
        // Twilight Forest: forest terrain, so surface mode like the overworld.
        PRESET_CONFIGS.put("twilightforest:twilight_forest",
                new DimensionScanConfig("twilightforest:twilight_forest", ScanMode.SURFACE, 63,
                    new DimensionTypeInfo(true, false, 0, 256, 256)));

        // Aether: a sky dimension, surface mode.
        PRESET_CONFIGS.put("aether:the_aether",
                new DimensionScanConfig("aether:the_aether", ScanMode.SURFACE, 63,
                    new DimensionTypeInfo(true, false, 0, 256, 256)));

        // Betweenlands: an underground swamp, cave mode suits it.
        PRESET_CONFIGS.put("thebetweenlands:betweenlands",
                new DimensionScanConfig("thebetweenlands:betweenlands", ScanMode.CAVE, 32,
                    new DimensionTypeInfo(false, true, 0, 256, 256)));

        // Erebus: insect caves, cave mode.
        PRESET_CONFIGS.put("erebus:erebus",
                new DimensionScanConfig("erebus:erebus", ScanMode.CAVE, 32,
                    new DimensionTypeInfo(false, true, 0, 256, 256)));
    }

    /**
     * Registers every dimension in the config, once, on the first map conversion.
     *
     * Detects each dimension's actual layout and writes it out. Later calls do nothing.
     *
     * @param server the running server
     */
    public static void registerAllDimensions(MinecraftServer server) {
        // Only ever run once.
        if (hasRegistered) {
            LOGGER.debug("Dimensions already registered, skipping");
            return;
        }

        LOGGER.info("Starting dimension registration on first map generation...");

        // The world's save root.
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);

        // Let DimensionPathMapping find and record every dimension path.
        DimensionPathMapping mapping = DimensionPathMapping.getInstance();
        mapping.scanAndRegisterDimensions(worldRoot);

        // What is configured right now.
        List<String> currentConfigs = ModConfig.SERVER.dimensionConfigs;

        // Parsed, so dimensions can be matched by ID.
        Set<String> configuredDimensions = new HashSet<>();
        for (DimensionScanConfig config : ModConfig.SERVER.parseDimensionConfigs()) {
            configuredDimensions.add(normalizeDimensionId(config.dimension()));
        }

        LOGGER.info("Currently configured dimensions: {}", configuredDimensions);

        // Every dimension the server has loaded.
        Set<String> newDimensions = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> dimKey = level.dimension();
            String dimId = dimKey.identifier().toString();

            String normalizedId = normalizeDimensionId(dimId);

            if (!configuredDimensions.contains(normalizedId)) {
                // Not configured yet, so it needs adding.
                newDimensions.add(dimId);
                LOGGER.info("Found unconfigured dimension: {} (normalized: {})", dimId, normalizedId);
            }
        }

        if (newDimensions.isEmpty()) {
            LOGGER.info("All dimensions already configured, no updates needed");
            hasRegistered = true;
            return;
        }

        // Existing config plus whatever is new.
        List<String> updatedConfigs = new ArrayList<>(currentConfigs);

        // Add the dimensions that were found (paths auto-detected on 1.21+).
        for (String dimId : newDimensions) {
        // Recommended settings, e.g. the scan mode.
            DimensionScanConfig preset = getRecommendedConfig(dimId);

        // Take the real dimension type from the running ServerLevel.
            ServerLevel level = getLevelForDimension(server, dimId);
            DimensionTypeInfo dimTypeInfo;
            if (level != null) {
                dimTypeInfo = DimensionTypeInfo.fromDimensionType(level.dimensionType());
                LOGGER.info("Dimension {}: hasSkylight={}, hasCeiling={}, minY={}, height={}",
                    dimId, dimTypeInfo.hasSkylight(), dimTypeInfo.hasCeiling(),
                    dimTypeInfo.minY(), dimTypeInfo.height());
            } else {
                // No level available; fall back to the preset or an inferred value.
                dimTypeInfo = preset.dimTypeInfo() != null ? preset.dimTypeInfo() : DimensionTypeInfo.fromDimensionId(dimId);
            }

        // Combine the recommendation with the dimension type info.
            DimensionScanConfig finalConfig = new DimensionScanConfig(
                    dimId,
                    preset.scanMode(),
                    preset.caveStart(),
                    dimTypeInfo
            );

            String configStr = configToString(finalConfig);
            updatedConfigs.add(configStr);
            LOGGER.info("Added dimension config: {} (scan_mode={}, hasSkylight={})",
                    dimId, finalConfig.scanMode(), dimTypeInfo.hasSkylight());
        }

        // Update the config value.
        ModConfig.SERVER.dimensionConfigs = updatedConfigs;

        // And write it out.
        ModConfig.save();

        hasRegistered = true;
        LOGGER.info("Dimension registration completed: {} new dimensions added, total {} dimensions configured",
                newDimensions.size(), updatedConfigs.size());
    }

    /**
     * Clears the registration flag, for tests or a forced rescan.
     */
    public static void resetRegistration() {
        hasRegistered = false;
        DimensionPathMapping.resetInstance();
        LOGGER.info("Dimension registration state reset");
    }

    /**
     * Normalises a dimension ID: drops the {@code minecraft:} prefix and lowercases it.
     *
     * @param dimId the dimension ID
     * @return the normalised ID
     */
    private static String normalizeDimensionId(String dimId) {
        return dimId.replace("minecraft:", "").toLowerCase();
    }

    /**
     * Finds the ServerLevel for a dimension ID.
     *
     * @param server the running server
     * @param dimId the dimension ID, e.g. {@code "minecraft:overworld"}
     * @return the level, or {@code null} if it is not loaded
     */
    private static ServerLevel getLevelForDimension(MinecraftServer server, String dimId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimId)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Recommended settings for a dimension, such as its scan mode.
     *
     * @param dimId the dimension ID
     * @return the recommended scan config
     */
    private static DimensionScanConfig getRecommendedConfig(String dimId) {
        // Use a preset if we have one (scan mode plus dimension type info).
        for (Map.Entry<String, DimensionScanConfig> entry : PRESET_CONFIGS.entrySet()) {
            if (normalizeDimensionId(entry.getKey()).equals(normalizeDimensionId(dimId))) {
                return entry.getValue();
            }
        }

        // Otherwise: surface mode, with the dimension type inferred.
        return new DimensionScanConfig(dimId, ScanMode.SURFACE, 63,
            DimensionTypeInfo.fromDimensionId(dimId));
    }

    /**
     * Formats a DimensionScanConfig as the string stored in the config file.
     *
     * Format: dimension|scan_mode|cave_start|dim_type_info
     * where dim_type_info is: hasSkylight|hasCeiling|minY|height|logicalHeight
     *
     * @param config the scan config
     * @return the config string
     */
    private static String configToString(DimensionScanConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append(config.dimension());
        sb.append("|").append(config.scanMode().name());
        sb.append("|").append(config.caveStart());

        // Append the dimension type info.
        if (config.dimTypeInfo() != null) {
            sb.append("|").append(config.dimTypeInfo().toConfigString());
        }

        return sb.toString();
    }

    /**
     * A dimension ID in its friendly form.
     *
     * @param dimId the dimension ID
     * @return the name without the {@code minecraft:} prefix
     */
    private static String toFriendlyName(String dimId) {
        String normalized = normalizeDimensionId(dimId);
        // Just the standard name, minus the minecraft: prefix.
        return normalized;
    }

    /**
     * Whether dimensions have been registered.
     *
     * @return {@code true} once registration has run
     */
    public static boolean isRegistered() {
        return hasRegistered;
    }
}
