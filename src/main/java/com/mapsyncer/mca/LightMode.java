package com.mapsyncer.mca;

/**
 * How lighting is worked out when rendering a region.
 *
 * <p>Two modes, mirroring how Xaero's World Map handles light:</p>
 *
 * <p>SURFACE:</p>
 * <ul>
 *   <li>uses block light only</li>
 *   <li>sky light is ignored entirely</li>
 *   <li>suits ordinary surface maps</li>
 *   <li>caves and basements come out dark</li>
 * </ul>
 *
 * <p>CAVE:</p>
 * <ul>
 *   <li>uses both block light and sky light</li>
 *   <li>takes whichever is brighter</li>
 *   <li>consults sky light when block light is below 15 and the dimension has a sky</li>
 *   <li>anything above the heightmap gets sky light 15, i.e. direct daylight</li>
 *   <li>which is what makes daylight visible through water from inside a cave</li>
 * </ul>
 *
 * @see DimensionTypeInfo for whether a dimension has sky light at all
 * @see ChunkSectionParser.LightData for where the light values come from
 */
public enum LightMode {

    /**
     * Surface mode: block light only.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>light level is the block light value</li>
     *   <li>sky light is ignored entirely</li>
     *   <li>light-emitting blocks are forced to 15</li>
     * </ul>
     *
     * <p>Used for ordinary surface maps.</p>
     */
    SURFACE,

    /**
     * Cave mode: the brighter of block light and sky light.
     *
     * <p>Rules, following Xaero's WorldDataReader:537-561:</p>
     * <ul>
     *   <li>starts at light 0, sky light 15 in dimensions that have a sky</li>
     *   <li>records sky light when block light is below 15 and sky light exists</li>
     *   <li>final value is max(block light, sky light)</li>
     *   <li>anything above the heightmap gets sky light 15</li>
     *   <li>uses sky light where there is no overlay and sky light is brighter</li>
     * </ul>
     *
     * <p>Used for cave maps and for seeing underwater.</p>
     */
    CAVE;

    /**
     * Works out the light level to render with.
     *
     * <p>Follows the lighting in Xaero's WorldDataReader.java:</p>
     * <ul>
     *   <li>line 186: worldHasSkylight = serverWorld.dimensionType().hasSkyLight()</li>
     *   <li>line 353: skyLightLevels[i] = worldHasSkylight ? 15 : 0</li>
     *   <li>lines 557-559: in cave mode, update sky light when dataLight < 15 and the world has sky light</li>
     * </ul>
     *
     * <p>The end is the odd one out:</p>
     * <ul>
     *   <li>worldHasSkylight is false, since the end has no sky light</li>
     *   <li>sky light starts at 0 rather than 15</li>
     *   <li>so sky light 15 is never used as a default there</li>
     * </ul>
     *
     * @param blockLight block light, 0-15
     * @param skyLight sky light, 0-15
     * @param hasSkyAccess whether this position is above the heightmap
     * @param hasOverlay whether something transparent (water, glass) covers it
     * @param isGlowing whether the block emits light
     * @param worldHasSkylight whether the dimension has sky light (false in the end)
     * @return the light level to render with, 0-15
     */
    public byte calculateEffectiveLight(byte blockLight, byte skyLight,
                                         boolean hasSkyAccess, boolean hasOverlay,
                                         boolean isGlowing, boolean worldHasSkylight) {
        // Light-emitting blocks are always fully lit.
        if (isGlowing) {
            return 15;
        }

        switch (this) {
            case SURFACE:
                // Surface mode: block light only.
                return blockLight;

            case CAVE:
                // Cave mode: the brighter of the two.
                if (blockLight >= 15) {
                    return blockLight;
                }

                // As Xaero does it: sky light 15 only where the dimension has a sky
                // and the position is exposed. The end has no sky light, so never here.
                byte effectiveSkyLight = (hasSkyAccess && worldHasSkylight) ? 15 : skyLight;

                // No overlay and sky light is brighter, so use sky light.
                if (!hasOverlay && effectiveSkyLight > blockLight) {
                    return effectiveSkyLight;
                }

                // Otherwise block light, which is the underwater case.
                return blockLight;

            default:
                return blockLight;
        }
    }

    /**
     * The sky light value to start from.
     *
     * <p>Depends on the mode and the dimension:</p>
     * <ul>
     *   <li>surface mode: 0, since sky light is unused</li>
     *   <li>cave mode: 15 where the dimension has sky light, otherwise 0</li>
     * </ul>
     *
     * @param worldHasSkylight whether the dimension has sky light
     * @return the starting sky light value, 0 or 15
     */
    public byte getDefaultSkyLight(boolean worldHasSkylight) {
        switch (this) {
            case SURFACE:
                return (byte) 0;  // surface mode ignores sky light
            case CAVE:
                return worldHasSkylight ? (byte) 15 : (byte) 0;
            default:
                return (byte) 0;
        }
    }

    /**
     * Whether this mode needs sky light data at all.
     *
     * <p>Only cave mode does.</p>
     *
     * @return {@code true} if sky light data is required
     */
    public boolean needsSkyLightData() {
        return this == CAVE;
    }
}