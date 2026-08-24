package com.mapsyncer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mapsyncer.MapSyncer;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.mca.DimensionTypeInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side configuration.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>general settings (debug logging, concurrency limits and so on)</li>
 *   <li>incremental updates (mode and interval)</li>
 *   <li>per-dimension scanning (scan mode, cave start height and so on)</li>
 * </ul>
 */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /**
     * Path to the config file.
     *
     * <p>The directory comes from the platform: {@code config/} on Fabric,
     * {@code plugins/MapSyncer/} on Paper.</p>
     *
     * @return the config file path
     */
    private static Path configPath() {
        return Platform.configDir().resolve("mapsyncer.json");
    }

    public static final ServerConfig SERVER = new ServerConfig();

    /**
     * Loads the config file, writing defaults if it does not exist yet.
     */
    public static void load() {
        if (Files.exists(configPath())) {
            try {
                String json = Files.readString(configPath());
                JsonObject root = GSON.fromJson(json, JsonObject.class);
                ServerConfig loaded = GSON.fromJson(root, ServerConfig.class);
                if (loaded != null) {
                    SERVER.copyFrom(loaded);
                }
                boolean needsSave = false;
                if (root != null) {
                    if (!root.has("enableVoxySync")) {
                        SERVER.enableVoxySync = false;
                        needsSave = true;
                    }
                    if (!root.has("enableDirtyRegionTracking")) {
                        SERVER.enableDirtyRegionTracking = true;
                        needsSave = true;
                    }
                    if (!root.has("dirtyRegionFallbackFullScan")) {
                        SERVER.dirtyRegionFallbackFullScan = true;
                        needsSave = true;
                    }
                    if (!root.has("maxDirtyRegionsPerIncrementalRun")) {
                        SERVER.maxDirtyRegionsPerIncrementalRun = 512;
                        needsSave = true;
                    }
                    if (!root.has("incrementalForceSaveBeforeScan")) {
                        SERVER.incrementalForceSaveBeforeScan = true;
                        needsSave = true;
                    }
                    if (!root.has("enableRadiusSync")) {
                        SERVER.enableRadiusSync = true;
                        needsSave = true;
                    }
                    if (!root.has("maxRadiusSyncBlocks")) {
                        SERVER.maxRadiusSyncBlocks = 3000;
                        needsSave = true;
                    }
                    if (!root.has("radiusSyncCenterMode")) {
                        SERVER.radiusSyncCenterMode = RadiusSyncCenterMode.PLAYER_POSITION;
                        needsSave = true;
                    }
                    if (!root.has("enableAdaptiveSyncThrottle")) {
                        SERVER.enableAdaptiveSyncThrottle = true;
                        needsSave = true;
                    }
                    if (!root.has("adaptivePingThresholdMs")) {
                        SERVER.adaptivePingThresholdMs = 200;
                        needsSave = true;
                    }
                    if (!root.has("adaptivePingRecoverMs")) {
                        SERVER.adaptivePingRecoverMs = 150;
                        needsSave = true;
                    }
                    if (!root.has("adaptiveThrottleAdjustCooldownMs")) {
                        SERVER.adaptiveThrottleAdjustCooldownMs = 2000;
                        needsSave = true;
                    }
                }
                if (needsSave) {
                    save();
                }
                MapSyncer.LOGGER.info("Loaded config from {}", configPath());
            } catch (Exception e) {
                MapSyncer.LOGGER.error("Failed to load config, using defaults", e);
            }
        } else {
            save();
        }
    }

    /**
     * Writes the current config back to disk.
     */
    public static void save() {
        try {
            Files.createDirectories(configPath().getParent());
            String json = GSON.toJson(SERVER);
            Files.writeString(configPath(), json);
            MapSyncer.LOGGER.info("Saved config to {}", configPath());
        } catch (IOException e) {
            MapSyncer.LOGGER.error("Failed to save config", e);
        }
    }

    /**
     * How incremental cache updates are triggered.
     */
    public enum UpdateMode {
        DISABLED,
        TICK,
        SCHEDULED
    }

    /**
     * How a dimension is sampled when building the map.
     */
    public enum ScanMode {
        SURFACE,
        CAVE
    }

    public enum RadiusSyncCenterMode {
        PLAYER_POSITION,
        WORLD_SPAWN,
        FIXED
    }

    /**
     * Scan settings for a single dimension.
     */
    public record DimensionScanConfig(
        String dimension,
        ScanMode scanMode,
        int caveStart,
        DimensionTypeInfo dimTypeInfo
    ) {
        public DimensionScanConfig(String dimension, ScanMode scanMode, int caveStart) {
            this(dimension, scanMode, caveStart, null);
        }

        public int getCaveLayer() {
            if (scanMode == ScanMode.SURFACE) {
                return Integer.MAX_VALUE;
            }
            if (caveStart == Integer.MAX_VALUE || caveStart == Integer.MIN_VALUE) {
                return caveStart;
            }
            return caveStart >> 4;
        }

        public int getCaveDepth(int minBuildHeight) {
            if (scanMode == ScanMode.SURFACE) {
                return 0;
            }
            return Math.max(30, caveStart - minBuildHeight);
        }

        public DimensionTypeInfo getDimensionTypeInfo() {
            if (dimTypeInfo != null) {
                return dimTypeInfo;
            }
            return DimensionTypeInfo.fromDimensionId(dimension);
        }
    }

    /**
     * The server config as stored in JSON.
     */
    public static class ServerConfig {
        // General settings
        public boolean enableDebugLogging = false;
        public int maxConcurrentRegions = 4;
        public int maxSyncPacketSize = 262144; // 256KB
        public int syncSpeedLimitKBps = 1024; // 1MB/s
        public boolean enableAdaptiveSyncThrottle = true;
        public int adaptivePingThresholdMs = 200;
        public int adaptivePingRecoverMs = 150;
        public int adaptiveThrottleAdjustCooldownMs = 2000;
        public int adaptiveMinSyncSpeedKBps = 128;
        public int adaptiveIncreaseStepKBps = 64;
        public double adaptiveDecreaseFactor = 0.5;
        public int adaptiveStableRecoverSamples = 3;
        public int adaptiveUnlimitedCeilingKBps = 4096;
        // Sends raw MCA files to clients when enabled. Keep disabled unless players are trusted.
        public boolean enableVoxySync = false;

        // Incremental update settings
        public UpdateMode incrementalUpdateMode = UpdateMode.DISABLED;
        public int incrementalUpdateIntervalTicks = 200; // 10 seconds
        public int scheduledUpdateHour = 4;
        public int scheduledUpdateMinute = 0;
        public boolean enableDirtyRegionTracking = true;
        public boolean dirtyRegionFallbackFullScan = true;
        public int maxDirtyRegionsPerIncrementalRun = 512;
        public boolean incrementalForceSaveBeforeScan = true;

        // Radius sync settings
        public boolean enableRadiusSync = true;
        public int maxRadiusSyncBlocks = 3000;
        public RadiusSyncCenterMode radiusSyncCenterMode = RadiusSyncCenterMode.PLAYER_POSITION;
        public String radiusSyncFixedDimension = "minecraft:overworld";
        public int radiusSyncFixedX = 0;
        public int radiusSyncFixedY = 64;
        public int radiusSyncFixedZ = 0;

        // Dimension scan settings
        public ScanMode defaultScanMode = ScanMode.SURFACE;
        public int defaultCaveStart = 63;
        public List<String> dimensionConfigs = getDefaultDimensionConfigStrings();

        private static List<String> getDefaultDimensionConfigStrings() {
            List<String> defaults = new ArrayList<>();
            defaults.add("minecraft:overworld|SURFACE|63|true|false|-64|384|384");
            defaults.add("minecraft:the_nether|CAVE|63|false|true|0|256|256");
            defaults.add("minecraft:the_end|SURFACE|63|false|false|0|256|256");
            return defaults;
        }

        public void copyFrom(ServerConfig other) {
            this.enableDebugLogging = other.enableDebugLogging;
            this.maxConcurrentRegions = other.maxConcurrentRegions;
            this.maxSyncPacketSize = other.maxSyncPacketSize;
            this.syncSpeedLimitKBps = other.syncSpeedLimitKBps;
            this.enableAdaptiveSyncThrottle = other.enableAdaptiveSyncThrottle;
            this.adaptivePingThresholdMs = other.adaptivePingThresholdMs > 0 ? other.adaptivePingThresholdMs : 200;
            this.adaptivePingRecoverMs = other.adaptivePingRecoverMs > 0 ? other.adaptivePingRecoverMs : 150;
            this.adaptiveThrottleAdjustCooldownMs = other.adaptiveThrottleAdjustCooldownMs > 0
                    ? other.adaptiveThrottleAdjustCooldownMs : 2000;
            this.adaptiveMinSyncSpeedKBps = other.adaptiveMinSyncSpeedKBps > 0 ? other.adaptiveMinSyncSpeedKBps : 128;
            this.adaptiveIncreaseStepKBps = other.adaptiveIncreaseStepKBps > 0 ? other.adaptiveIncreaseStepKBps : 64;
            this.adaptiveDecreaseFactor = other.adaptiveDecreaseFactor > 0 && other.adaptiveDecreaseFactor < 1
                    ? other.adaptiveDecreaseFactor : 0.5;
            this.adaptiveStableRecoverSamples = other.adaptiveStableRecoverSamples > 0
                    ? other.adaptiveStableRecoverSamples : 3;
            this.adaptiveUnlimitedCeilingKBps = other.adaptiveUnlimitedCeilingKBps > 0
                    ? other.adaptiveUnlimitedCeilingKBps : 4096;
            this.enableVoxySync = other.enableVoxySync;
            this.incrementalUpdateMode = other.incrementalUpdateMode != null
                    ? other.incrementalUpdateMode : UpdateMode.DISABLED;
            this.incrementalUpdateIntervalTicks = other.incrementalUpdateIntervalTicks;
            this.scheduledUpdateHour = other.scheduledUpdateHour;
            this.scheduledUpdateMinute = other.scheduledUpdateMinute;
            this.enableDirtyRegionTracking = other.enableDirtyRegionTracking;
            this.dirtyRegionFallbackFullScan = other.dirtyRegionFallbackFullScan;
            this.maxDirtyRegionsPerIncrementalRun = other.maxDirtyRegionsPerIncrementalRun > 0
                    ? other.maxDirtyRegionsPerIncrementalRun : 512;
            this.incrementalForceSaveBeforeScan = other.incrementalForceSaveBeforeScan;
            this.enableRadiusSync = other.enableRadiusSync;
            this.maxRadiusSyncBlocks = other.maxRadiusSyncBlocks > 0 ? other.maxRadiusSyncBlocks : 3000;
            this.radiusSyncCenterMode = other.radiusSyncCenterMode != null
                    ? other.radiusSyncCenterMode : RadiusSyncCenterMode.PLAYER_POSITION;
            this.radiusSyncFixedDimension = other.radiusSyncFixedDimension != null && !other.radiusSyncFixedDimension.isBlank()
                    ? other.radiusSyncFixedDimension : "minecraft:overworld";
            this.radiusSyncFixedX = other.radiusSyncFixedX;
            this.radiusSyncFixedY = other.radiusSyncFixedY;
            this.radiusSyncFixedZ = other.radiusSyncFixedZ;
            this.defaultScanMode = other.defaultScanMode != null ? other.defaultScanMode : ScanMode.SURFACE;
            this.defaultCaveStart = other.defaultCaveStart;
            this.dimensionConfigs = other.dimensionConfigs != null && !other.dimensionConfigs.isEmpty()
                    ? other.dimensionConfigs : getDefaultDimensionConfigStrings();
        }

        public List<DimensionScanConfig> parseDimensionConfigs() {
            List<DimensionScanConfig> result = new ArrayList<>();
            for (String configStr : dimensionConfigs) {
                DimensionScanConfig config = parseConfigString(configStr);
                if (config != null) {
                    result.add(config);
                }
            }
            return result;
        }

        private DimensionScanConfig parseConfigString(String configStr) {
            if (configStr == null || configStr.isEmpty()) {
                return null;
            }

            String[] parts = configStr.split("\\|");
            if (parts.length < 1) {
                return null;
            }

            String dimension = parts[0];
            int caveStart = 63;
            DimensionTypeInfo dimTypeInfo = DimensionTypeInfo.fromDimensionId(dimension);

            try {
                boolean isNewFormat = parts.length > 1 &&
                    (parts[1].equalsIgnoreCase("SURFACE") || parts[1].equalsIgnoreCase("CAVE"));

                int scanModeIndex = isNewFormat ? 1 : 2;
                int caveStartIndex = isNewFormat ? 2 : 3;
                int dimTypeStartIndex = isNewFormat ? 3 : 4;

                String modeStr = parts.length > scanModeIndex ? parts[scanModeIndex] : "SURFACE";

                if (parts.length > caveStartIndex) {
                    caveStart = Integer.parseInt(parts[caveStartIndex]);
                }

                if (parts.length >= dimTypeStartIndex + 5) {
                    boolean hasSkylight = Boolean.parseBoolean(parts[dimTypeStartIndex]);
                    boolean hasCeiling = Boolean.parseBoolean(parts[dimTypeStartIndex + 1]);
                    int minY = Integer.parseInt(parts[dimTypeStartIndex + 2]);
                    int height = Integer.parseInt(parts[dimTypeStartIndex + 3]);
                    int logicalHeight = Integer.parseInt(parts[dimTypeStartIndex + 4]);
                    dimTypeInfo = new DimensionTypeInfo(hasSkylight, hasCeiling, minY, height, logicalHeight);
                }

                ScanMode mode = ScanMode.valueOf(modeStr.toUpperCase());
                return new DimensionScanConfig(dimension, mode, caveStart, dimTypeInfo);
            } catch (NumberFormatException e) {
                return new DimensionScanConfig(dimension, ScanMode.SURFACE, 63, dimTypeInfo);
            } catch (IllegalArgumentException e) {
                return new DimensionScanConfig(dimension, ScanMode.SURFACE, caveStart, dimTypeInfo);
            }
        }

        public DimensionScanConfig getConfigForDimension(String dimensionPath) {
            String normalizedPath = dimensionPath.replace("minecraft:", "").toLowerCase();

            if (normalizedPath.equals("the_nether")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("the_nether")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:the_nether", ScanMode.CAVE, 63,
                    DimensionTypeInfo.nether());
            }

            if (normalizedPath.equals("overworld")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("overworld")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:overworld", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.overworld());
            }

            if (normalizedPath.equals("the_end")) {
                for (DimensionScanConfig config : parseDimensionConfigs()) {
                    String configDim = config.dimension().replace("minecraft:", "").toLowerCase();
                    if (configDim.equals("the_end")) {
                        return config;
                    }
                }
                return new DimensionScanConfig("minecraft:the_end", ScanMode.SURFACE, 63,
                    DimensionTypeInfo.theEnd());
            }

            for (DimensionScanConfig config : parseDimensionConfigs()) {
                String configDim = config.dimension();
                if (configDim.equalsIgnoreCase(dimensionPath) ||
                    configDim.equalsIgnoreCase("minecraft:" + dimensionPath) ||
                    configDim.replace("minecraft:", "").equalsIgnoreCase(dimensionPath)) {
                    return config;
                }
            }

            DimensionTypeInfo inferredDimType = DimensionTypeInfo.fromDimensionId(dimensionPath);
            return new DimensionScanConfig(dimensionPath, defaultScanMode, defaultCaveStart, inferredDimType);
        }
    }
}
