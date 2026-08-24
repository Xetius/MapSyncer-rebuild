package com.mapsyncer.mca;

/**
 * The properties of a dimension type that matter here.
 *
 * <p>Drives lighting and the world height range when parsing region files offline.</p>
 *
 * <p>See the <a href="https://minecraft.wiki/w/Dimension_type">dimension type</a> page.</p>
 *
 * <p>The fields:</p>
 * <ul>
 *   <li>hasSkylight: whether the dimension has sky light, which lighting depends on</li>
 *   <li>hasCeiling: whether it has a ceiling, as the nether does</li>
 *   <li>minY: the lowest buildable Y</li>
 *   <li>height: total height, so minY + height is the highest buildable Y</li>
 *   <li>logicalHeight: the usable height, which can be less than height</li>
 * </ul>
 *
 * <p>The vanilla dimensions:</p>
 * <table border="1">
 *   <tr><th>dimension</th><th>hasSkylight</th><th>hasCeiling</th><th>minY</th><th>height</th></tr>
 *   <tr><td>Overworld</td><td>true</td><td>false</td><td>-64</td><td>384</td></tr>
 *   <tr><td>Nether</td><td>false</td><td>true</td><td>0</td><td>256</td></tr>
 *   <tr><td>End</td><td>false</td><td>false</td><td>0</td><td>256</td></tr>
 * </table>
 *
 * @param hasSkylight whether the dimension has sky light
 * @param hasCeiling whether it has a ceiling, as the nether does; affects cave scanning
 * @param minY the lowest buildable Y
 * @param height the dimension's total height
 * @param logicalHeight the usable height
 */
public record DimensionTypeInfo(
    boolean hasSkylight,      // has sky light
    boolean hasCeiling,       // has a ceiling
    int minY,                 // lowest buildable Y
    int height,               // total height
    int logicalHeight         // usable height
) {

    /**
     * The highest buildable Y, i.e. minY + height.
     *
     * @return the top of the world
     */
    public int maxY() {
        return minY + height;
    }

    /**
     * The overworld's dimension type.
     *
     * <p>Sky light, no ceiling, Y from -64 to 320.</p>
     *
     * @return the overworld's dimension type info
     */
    public static DimensionTypeInfo overworld() {
        return new DimensionTypeInfo(true, false, -64, 384, 384);
    }

    /**
     * The nether's dimension type.
     *
     * <p>No sky light, has a ceiling, Y from 0 to 256.</p>
     *
     * @return the nether's dimension type info
     */
    public static DimensionTypeInfo nether() {
        return new DimensionTypeInfo(false, true, 0, 256, 256);
    }

    /**
     * The end's dimension type.
     *
     * <p>No sky light, no ceiling, Y from 0 to 256.</p>
     *
     * @return the end's dimension type info
     */
    public static DimensionTypeInfo theEnd() {
        return new DimensionTypeInfo(false, false, 0, 256, 256);
    }

    /**
     * The dimension type for a known dimension ID.
     *
     * @param dimensionId the dimension ID, e.g. "minecraft:overworld", "minecraft:the_nether", "the_end"
     * @return its dimension type info, defaulting to the overworld's for anything unknown
     */
    public static DimensionTypeInfo fromDimensionId(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            return overworld();
        }

        String normalized = dimensionId
            .replace("minecraft:", "")
            .toLowerCase();

        switch (normalized) {
            case "overworld":
                return overworld();
            case "the_nether":
                return nether();
            case "the_end":
                return theEnd();
            default:
                // Unknown dimension: assume overworld-like.
                return overworld();
        }
    }

    /**
     * Built from Minecraft's own DimensionType.
     * (Needs a running server, so this is the server-side path.)
     *
     * @param dimensionType the Minecraft dimension type
     * @return the equivalent dimension type info
     */
    public static DimensionTypeInfo fromDimensionType(net.minecraft.world.level.dimension.DimensionType dimensionType) {
        return new DimensionTypeInfo(
            dimensionType.hasSkyLight(),
            dimensionType.hasCeiling(),
            dimensionType.minY(),
            dimensionType.height(),
            dimensionType.logicalHeight()
        );
    }

    /**
     * The sky light value to start from.
     *
     * <p>As in Xaero's WorldDataReader line 353:</p>
     * <ul>
     *   <li>dimensions with sky light: 15</li>
     *   <li>dimensions without: 0</li>
     * </ul>
     *
     * @return 15 where the dimension has sky light, otherwise 0
     */
    public byte getDefaultSkyLight() {
        return hasSkylight ? (byte) 15 : (byte) 0;
    }

    /**
     * Whether this is a cave-like dimension, i.e. one with a ceiling.
     *
     * <p>Those usually want CAVE scan mode.</p>
     * <p>The nether is the obvious example.</p>
     *
     * @return {@code true} if the dimension has a ceiling
     */
    public boolean isCaveDimension() {
        return hasCeiling;
    }

    /**
     * A sensible height to start a cave scan from.
     *
     * <p>Dimensions with a ceiling (the nether) start below it.</p>
     * <p>Everything else starts at sea level, 63.</p>
     *
     * @return the recommended cave scan start height, as a world Y
     */
    public int getRecommendedCaveStart() {
        if (hasCeiling) {
            // Nether: the ceiling sits around Y=128, so scan downwards from 63.
            return Math.max(minY + 32, (minY + height) / 2 - 32);
        }
        // Everything else: sea level.
        return Math.max(minY, 63);
    }

    /**
     * Formats this for the config file.
     *
     * <p>Format: "hasSkylight|hasCeiling|minY|height|logicalHeight"</p>
     * <p>Used both in the config and on the wire.</p>
     *
     * @return the config string
     */
    public String toConfigString() {
        return hasSkylight + "|" + hasCeiling + "|" + minY + "|" + height + "|" + logicalHeight;
    }

    /**
     * Parses a dimension type from its config string.
     *
     * <p>Format: "hasSkylight|hasCeiling|minY|height|logicalHeight"</p>
     * <p>Anything malformed falls back to the overworld's values.</p>
     *
     * @param configStr the config string
     * @return the parsed dimension type info
     */
    public static DimensionTypeInfo fromConfigString(String configStr) {
        if (configStr == null || configStr.isEmpty()) {
            return overworld();
        }

        String[] parts = configStr.split("\\|");
        if (parts.length < 4) {
            return overworld();
        }

        try {
            boolean hasSkylight = Boolean.parseBoolean(parts[0]);
            boolean hasCeiling = Boolean.parseBoolean(parts[1]);
            int minY = Integer.parseInt(parts[2]);
            int height = Integer.parseInt(parts[3]);
            int logicalHeight = parts.length > 4 ? Integer.parseInt(parts[4]) : height;

            return new DimensionTypeInfo(hasSkylight, hasCeiling, minY, height, logicalHeight);
        } catch (NumberFormatException e) {
            return overworld();
        }
    }

    /**
     * A readable form of this dimension type.
     *
     * @return every field, formatted
     */
    @Override
    public String toString() {
        return String.format("DimensionTypeInfo[hasSkylight=%s, hasCeiling=%s, minY=%d, height=%d, maxY=%d]",
            hasSkylight, hasCeiling, minY, height, maxY());
    }
}