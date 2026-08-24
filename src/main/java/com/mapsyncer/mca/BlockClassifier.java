package com.mapsyncer.mca;

import java.util.*;

/**
 * Classifies blocks by matching their names, rather than calling into Minecraft.
 *
 * @deprecated Currently unused, kept as a fallback.
 *             The project runs inside a server, so BlockPropertyResolver is preferred:
 *             it reads properties from the Minecraft API and so handles modded blocks.
 *             This class is retained for cases that may come up later:
 *             1. offline or pre-generation use, with no Minecraft runtime
 *             2. as a fallback when BlockPropertyResolver is unavailable
 *             3. quick checks where modded blocks do not matter
 *
 * @see com.mapsyncer.server.BlockPropertyResolver the runtime resolver, which is preferred
 */
@Deprecated(since = "2026-05-21", forRemoval = false)
public class BlockClassifier {

    /**
     * Air blocks.
     *
     * <p>Every kind of air.</p>
     */
    private static final Set<String> AIR_BLOCKS = Set.of(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air"
    );

    /**
     * Water blocks.
     *
     * <p>Both still and flowing.</p>
     */
    private static final Set<String> WATER_BLOCKS = Set.of(
        "minecraft:water", "minecraft:flowing_water"
    );

    /**
     * Lava blocks.
     *
     * <p>Both still and flowing.</p>
     */
    private static final Set<String> LAVA_BLOCKS = Set.of(
        "minecraft:lava", "minecraft:flowing_lava"
    );

    /**
     * Transparent blocks, drawn as overlays.
     *
     * <p>Follows Xaero's MapWriter.blockStateHasTranslucentRenderType.</p>
     * <p>These render translucent and so belong on top of whatever is beneath them.</p>
     */
    private static final Set<String> TRANSPARENT_BLOCKS = Set.of(
        "minecraft:glass", "minecraft:glass_pane",
        "minecraft:white_stained_glass", "minecraft:orange_stained_glass",
        "minecraft:magenta_stained_glass", "minecraft:light_blue_stained_glass",
        "minecraft:yellow_stained_glass", "minecraft:lime_stained_glass",
        "minecraft:pink_stained_glass", "minecraft:gray_stained_glass",
        "minecraft:light_gray_stained_glass", "minecraft:cyan_stained_glass",
        "minecraft:purple_stained_glass", "minecraft:blue_stained_glass",
        "minecraft:brown_stained_glass", "minecraft:green_stained_glass",
        "minecraft:red_stained_glass", "minecraft:black_stained_glass",
        "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
        "minecraft:tinted_glass",
        // Water plants, all translucent, so all overlays.
        "minecraft:kelp", "minecraft:kelp_plant",
        "minecraft:seagrass", "minecraft:tall_seagrass"
    );

    /**
     * Invisible blocks, always skipped when scanning.
     *
     * <p>Follows Xaero's MapWriter.isInvisible():</p>
     * <ul>
     *   <li>torches and short grass: always skipped</li>
     *   <li>glass and glass panes: always skipped</li>
     *   <li>DoublePlantBlocks that are not flowers (tall grass, large fern): always skipped</li>
     * </ul>
     */
    private static final Set<String> INVISIBLE_BLOCKS = Set.of(
        "minecraft:torch", "minecraft:wall_torch", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
        "minecraft:soul_torch", "minecraft:soul_wall_torch",
        "minecraft:short_grass", "minecraft:grass", // 1.20+ uses short_grass
        "minecraft:tall_grass", "minecraft:large_fern", // DoublePlantBlocks that are not flowers
        "minecraft:glass", "minecraft:glass_pane"
    );

    /**
     * Flowers.
     *
     * <p>Follows Xaero: BlockTags.FLOWERS plus FlowerBlock and TallFlowerBlock.</p>
     * <p>Xaero defaults FLOWERS to true, so flowers do show on the surface.</p>
     */
    private static final Set<String> FLOWER_BLOCKS = Set.of(
        // Small flowers (FlowerBlock)
        "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
        "minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip",
        "minecraft:orange_tulip", "minecraft:white_tulip", "minecraft:pink_tulip",
        "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
        "minecraft:wither_rose", "minecraft:brown_mushroom", "minecraft:red_mushroom",
        // Tall flowers (TallFlowerBlock)
        "minecraft:sunflower", "minecraft:rose_bush", "minecraft:peony", "minecraft:pitcher_plant"
    );

    /**
     * Blocks with no map colour.
     *
     * <p>Most blocks have one; these are the exceptions.</p>
     */
    private static final Set<String> NO_COLOR_BLOCKS = Set.of(
        "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
        "minecraft:structure_void", "minecraft:barrier"
    );

    /**
     * Light-emitting blocks, those at light level 15.
     *
     * <p>These are forced to full brightness.</p>
     */
    private static final Set<String> GLOWING_BLOCKS = Set.of(
        "minecraft:glowstone", "minecraft:lava", "minecraft:flowing_lava",
        "minecraft:torch", "minecraft:wall_torch", "minecraft:redstone_torch", "minecraft:redstone_wall_torch",
        "minecraft:soul_torch", "minecraft:soul_wall_torch",
        "minecraft:sea_lantern", "minecraft:sea_pickle",
        "minecraft:shroomlight", "minecraft:end_rod",
        "minecraft:beacon", "minecraft:conduit",
        "minecraft:jack_o_lantern", "minecraft:magma_block",
        "minecraft:lantern", "minecraft:soul_lantern",
        "minecraft:campfire", "minecraft:soul_campfire",
        "minecraft:light", "minecraft:crying_obsidian",
        "minecraft:respawn_anchor", "minecraft:glow_lichen",
        "minecraft:calcite", "minecraft:small_amethyst_bud",
        "minecraft:medium_amethyst_bud", "minecraft:large_amethyst_bud",
        "minecraft:amethyst_cluster", "minecraft:budding_amethyst"
    );

    /**
     * Whether a block is air.
     *
     * @param blockName the block name
     * @return {@code true} for air
     */
    public static boolean isAir(String blockName) {
        return AIR_BLOCKS.contains(blockName);
    }

    /**
     * Whether a block is water.
     *
     * @param blockName the block name
     * @return {@code true} for water
     */
    public static boolean isWater(String blockName) {
        return WATER_BLOCKS.contains(blockName);
    }

    /**
     * Whether a block is lava.
     *
     * @param blockName the block name
     * @return {@code true} for lava
     */
    public static boolean isLava(String blockName) {
        return LAVA_BLOCKS.contains(blockName);
    }

    /**
     * Whether a block is a fluid, i.e. water or lava.
     *
     * @param blockName the block name
     * @return {@code true} for fluids
     */
    public static boolean isFluid(String blockName) {
        return isWater(blockName) || isLava(blockName);
    }

    /**
     * Whether a block is a see-through fluid, i.e. water.
     *
     * <p>Lava is opaque and forms a surface; water is transparent and forms an overlay.</p>
     *
     * @param blockName the block name
     * @return {@code true} for water
     */
    public static boolean isTranslucentFluid(String blockName) {
        return isWater(blockName);
    }

    /**
     * Whether a block is transparent, and so drawn as an overlay.
     *
     * @param blockName the block name
     * @return {@code true} if transparent
     */
    public static boolean isTransparent(String blockName) {
        return TRANSPARENT_BLOCKS.contains(blockName) || isWater(blockName);
    }

    /**
     * Whether a block is invisible, and so skipped when scanning.
     *
     * @param blockName the block name
     * @return {@code true} if invisible
     */
    public static boolean isInvisible(String blockName) {
        return INVISIBLE_BLOCKS.contains(blockName);
    }

    /**
     * Whether a block is a flower.
     *
     * <p>Follows Xaero: BlockTags.FLOWERS plus FlowerBlock and TallFlowerBlock.</p>
     *
     * @param blockName the block name
     * @return {@code true} for flowers
     */
    public static boolean isFlower(String blockName) {
        return FLOWER_BLOCKS.contains(blockName);
    }

    /**
     * Whether a block has a map colour, i.e. is a visible solid block.
     *
     * @param blockName the block name
     * @return {@code true} if it has a map colour
     */
    public static boolean hasVanillaColor(String blockName) {
        return !NO_COLOR_BLOCKS.contains(blockName) && !isAir(blockName);
    }

    /**
     * Whether a block is a grass block.
     *
     * @param blockName the block name
     * @return {@code true} for grass blocks
     */
    public static boolean isGrassBlock(String blockName) {
        return blockName.equals("minecraft:grass_block");
    }

    /**
     * Whether a block emits light, and so renders at full brightness.
     *
     * <p>Follows Xaero's MapWriter.isGlowing().</p>
     *
     * @param blockName the block name
     * @return {@code true} if it emits light
     */
    public static boolean isGlowing(String blockName) {
        return GLOWING_BLOCKS.contains(blockName);
    }

    /**
     * Whether a block should be drawn as an overlay.
     *
     * @param blockName the block name
     * @return {@code true} if it should be an overlay
     */
    public static boolean shouldOverlay(String blockName) {
        return isTranslucentFluid(blockName) || isTransparent(blockName);
    }

    /**
     * How much light a block blocks.
     *
     * <p>Follows Minecraft's Block.getLightBlock() and Xaero's overlay handling.</p>
     * <p>Values from the <a href="https://minecraft.wiki/w/Opacity">wiki</a>.</p>
     *
     * @param blockName the block name
     * @return the light blocking value, 0-15
     */
    public static int getLightBlock(String blockName) {
        // Water blocks 2, as of Minecraft 1.13.
        if (isWater(blockName)) return 2;
        // Lava emits light and blocks all of it.
        if (isLava(blockName)) return 15;
        // Ice blocks 2.
        if (blockName.equals("minecraft:ice") ||
            blockName.equals("minecraft:packed_ice") ||
            blockName.equals("minecraft:blue_ice") ||
            blockName.equals("minecraft:frosted_ice")) return 2;
        // Leaves block 1.
        if (blockName.contains("leaves") ||
            blockName.endsWith("_leaves")) return 1;
        // Glass blocks nothing.
        if (blockName.equals("minecraft:glass") ||
            blockName.equals("minecraft:glass_pane") ||
            blockName.contains("stained_glass") ||
            blockName.contains("tinted_glass")) return 0;
        // Water plants sit in water, so they use water's value.
        if (blockName.equals("minecraft:kelp") ||
            blockName.equals("minecraft:kelp_plant") ||
            blockName.equals("minecraft:seagrass") ||
            blockName.equals("minecraft:tall_seagrass")) return 2;
        // Air.
        if (isAir(blockName)) return 0;
        // A waterlogged block blocks whatever the block itself blocks,
        // which for most of them (fence gates, stairs and so on) is nothing.
        // Default: solid blocks block all of it.
        return 15;
    }
}