package com.mapsyncer.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseCoralPlantBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CaveVinesBlock;
import net.minecraft.world.level.block.CaveVinesPlantBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.SmallDripleafBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.TorchflowerCropBlock;
import net.minecraft.world.level.block.TwistingVinesBlock;
import net.minecraft.world.level.block.TwistingVinesPlantBlock;
import net.minecraft.world.level.block.WeepingVinesBlock;
import net.minecraft.world.level.block.WeepingVinesPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers questions about a block by asking the Minecraft registry.
 *
 * Follows Xaero's World Map. Works out, for a block:
 * - whether it is air, a fluid, or transparent
 * - whether it is a flower or a plant
 * - how much light it blocks and how much it emits
 * - whether it can be waterlogged
 * - whether it has a usable map colour
 *
 * Vanilla and modded blocks alike; results are cached.
 */
public class BlockPropertyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockPropertyResolver.class);

    /** Stand-in BlockGetter and BlockPos, for APIs that insist on them. */
    private static final BlockGetter PLACEHOLDER_BLOCK_GETTER = new PlaceholderBlockGetter();
    private static final BlockPos PLACEHOLDER_BLOCKPOS = BlockPos.ZERO;

    /** Cached properties per block, pruned when it gets too large. */
    private static final ConcurrentHashMap<String, BlockProperties> propertiesCache = new ConcurrentHashMap<>();

    /** Cache ceiling; older entries are dropped past this. */
    private static final int MAX_CACHE_SIZE = 10000;

    /** Blocks whose MapColor lookup threw, so it is not retried. */
    private static final ConcurrentHashMap<String, Boolean> buggedBlocks = new ConcurrentHashMap<>();

    /**
     * Everything worked out about one block.
     *
     * These are the properties map rendering decides from.
     */
    public record BlockProperties(
        boolean isAir,
        boolean isWater,
        boolean isLava,
        boolean isFluid,
        boolean isTransparent,      // transparent: glass, ice and so on
        boolean isInvisible,        // skipped entirely when scanning
        boolean isFlower,
        boolean isPlant,            // flowers, grass, crops, mushrooms and so on
        boolean isGrassBlock,
        boolean isGlowing,          // emits light
        int lightBlock,             // how much light it blocks
        int lightEmission,          // how much light it emits
        boolean canBeWaterlogged,   // accepts the waterlogged property
        boolean hasVanillaColor,    // has a map colour at all
        boolean hasMapColor         // has a usable MapColor
    ) {
        /**
         * Whether this is the surface of a waterlogged block.
         *
         * @param properties the block's property values
         * @return {@code true} if it is waterlogged
         */
        public boolean isWaterloggedSurface(Map<String, String> properties) {
            if (properties == null) return false;
            return canBeWaterlogged &&
                   "true".equals(properties.get("waterlogged")) &&
                   !isWater && !isAir;
        }

        /**
         * Whether this is a see-through fluid, i.e. water.
         *
         * @return {@code true} for water
         */
        public boolean isTranslucentFluid() {
            return isWater;
        }

        /**
         * Whether this should be drawn as an overlay.
         *
         * Overlay blocks — water, transparent blocks — render on top of whatever is beneath.
         *
         * @return {@code true} if it should be an overlay
         */
        public boolean shouldOverlay() {
            return isWater || isTransparent;
        }
    }

    /**
     * The properties of a block, by name.
     *
     * Cached, so each block name is resolved once.
     * Once the cache passes its ceiling, some older entries are dropped.
     *
     * @param blockName the block name, e.g. "minecraft:stone" or "modid:custom_block"
     * @return the block's properties
     */
    public static BlockProperties getProperties(String blockName) {
        // Prune the cache if it has grown past its ceiling.
        if (propertiesCache.size() > MAX_CACHE_SIZE) {
            trimCache();
        }
        return propertiesCache.computeIfAbsent(blockName, BlockPropertyResolver::resolveProperties);
    }

    /**
     * Prunes the cache.
     *
     * Drops half the entries once it passes the ceiling.
     * Deliberately crude: entries go by iteration order rather than true LRU, which avoids
     * sorting the whole map.
     */
    private static void trimCache() {
        int currentSize = propertiesCache.size();
        int toRemove = currentSize / 2;

        LOGGER.debug("Trimming properties cache: size={}, removing {} entries", currentSize, toRemove);

        // Drop half the entries; approximate rather than strict LRU.
        int removed = 0;
        for (String key : propertiesCache.keySet()) {
            if (removed >= toRemove) break;
            propertiesCache.remove(key);
            removed++;
        }

        LOGGER.debug("Cache trimmed: removed {} entries, new size={}", removed, propertiesCache.size());
    }

    /**
     * The properties of a block, from its state.
     *
     * @param state the block state
     * @return the block's properties
     */
    public static BlockProperties getProperties(BlockState state) {
        String blockName = getKey(state);
        return getProperties(blockName);
    }

    /**
     * Works out a block's properties from the registry.
     *
     * Looks the block up and reads what it needs off the default block state.
     *
     * @param blockName the block's registry name
     * @return the block's properties
     */
    private static BlockProperties resolveProperties(String blockName) {
        try {
            Identifier location = Identifier.parse(blockName);
            Optional<Block> blockOpt = BuiltInRegistries.BLOCK.getOptional(location);

            if (blockOpt.isEmpty()) {
                LOGGER.debug("Block not found in registry: {}, using fallback", blockName);
                return getFallbackProperties(blockName);
            }

            Block block = blockOpt.get();

            // The default state, which the generic properties come from.
            BlockState defaultState = block.defaultBlockState();

            // Read the properties.
            boolean isAir = defaultState.isAir() || block instanceof AirBlock;

            // Fluid: is it a LiquidBlock?
            boolean isFluid = block instanceof LiquidBlock;
            FluidState fluidState = defaultState.getFluidState();
            Fluid fluid = fluidState.getType();
            boolean isWater = fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
            boolean isLava = fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;

            // Transparency, the Xaero way: AirBlock, TransparentBlock, translucent rendering.
            boolean isTransparent = checkTransparency(block, defaultState);

            // Invisibility: RenderShape.INVISIBLE, plus tags and classes.
            boolean isInvisible = checkInvisibility(block, defaultState, true);

            // Flower: BlockTags.FLOWERS, plus the flower classes.
            boolean isFlower = checkIsFlower(block, defaultState);

            // Plant: base classes and tags, which casts a wider net.
            boolean isPlant = checkIsPlant(block, defaultState, isFlower);

            // Grass block?
            boolean isGrassBlock = block == Blocks.GRASS_BLOCK;

            // Glowing: from getLightEmission.
            int lightEmission = defaultState.getLightEmission();
            boolean isGlowing = lightEmission >= 15;

            // How much light it blocks.
            int lightBlock = getLightBlock(defaultState);

            // Can it be waterlogged?
            boolean canBeWaterlogged = checkCanBeWaterlogged(block, defaultState);

            // Does it have a usable MapColor?
            boolean hasMapColor = checkHasMapColor(defaultState, blockName);

            // And a map colour at all: not air, not invisible, not a known-bad block.
            boolean hasVanillaColor = !isAir && !isInvisible && !buggedBlocks.containsKey(blockName);

            return new BlockProperties(
                isAir, isWater, isLava, isFluid,
                isTransparent, isInvisible, isFlower, isPlant, isGrassBlock,
                isGlowing, lightBlock, lightEmission, canBeWaterlogged,
                hasVanillaColor, hasMapColor
            );

        } catch (Exception e) {
            LOGGER.warn("Failed to resolve block properties for {}: {}", blockName, e.getMessage());
            return getFallbackProperties(blockName);
        }
    }

    /**
     * Whether a block has a usable MapColor.
     *
     * Follows Xaero's hasVanillaColor.
     *
     * @param state the block state
     * @param blockName the block's registry name
     * @return {@code true} if MapColor gives something usable
     */
    private static boolean checkHasMapColor(BlockState state, String blockName) {
        try {
            MapColor mapColor = state.getMapColor(PLACEHOLDER_BLOCK_GETTER, PLACEHOLDER_BLOCKPOS);
            if (mapColor != null && mapColor.col != 0) {
                return true;
            }
        } catch (Throwable t) {
            // Remember the block threw, so it is not retried.
            buggedBlocks.put(blockName, true);
            LOGGER.debug("Broken vanilla map color definition found: {}", blockName);
        }
        return false;
    }

    /**
     * How much light a block blocks, across API versions.
     *
     * @param state the block state
     * @return the light blocking value, 0-15
     */
    private static int getLightBlock(BlockState state) {
        try {
            // getLightBlock wants a BlockGetter and a BlockPos.
            return state.getLightDampening();
        } catch (Exception e) {
            // Failing that, estimate from the block type.
            FluidState fluidState = state.getFluidState();
            if (!fluidState.isEmpty()) {
                // Water blocks 2, lava blocks 15.
                if (fluidState.getType() == Fluids.WATER || fluidState.getType() == Fluids.FLOWING_WATER) {
                    return 2;
                }
                if (fluidState.getType() == Fluids.LAVA || fluidState.getType() == Fluids.FLOWING_LAVA) {
                    return 15;
                }
            }
            // Air blocks nothing.
            if (state.isAir()) {
                return 0;
            }
            // Leaves block 1.
            if (state.is(BlockTags.LEAVES)) {
                return 1;
            }
            // Anything else solid blocks all of it.
            return 15;
        }
    }

    /**
     * Whether a block is transparent, and so drawn as an overlay.
     *
     * Follows Xaero's shouldOverlay:
     * 1. AirBlock or TransparentBlock: overlay
     * 2. anything rendered translucent: overlay
     *
     * Note that leaves render cutout rather than translucent, so leaves are not overlays.
     *
     * @param block the block
     * @param state the block state
     * @return {@code true} if it is transparent
     */
    private static boolean checkTransparency(Block block, BlockState state) {
        // 1. AirBlock or TransparentBlock, as Xaero does it.
        //    TransparentBlock covers glass, ice, tinted glass and so on.
        if (block instanceof AirBlock || block instanceof TransparentBlock) {
            return true;
        }

        // 2. Fluids (water, lava), which render translucent.
        FluidState fluidState = state.getFluidState();
        if (!fluidState.isEmpty()) {
            return true;  // fluids render translucent
        }

        // 3. Water plants (kelp, seagrass) subclass TransparentBlock and are caught above,
        //    but a mod may not subclass it, so check for those by name.
        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (blockId.contains("kelp") || blockId.contains("seagrass")) {
            return true;
        }

        // 4. Blocks known to render translucent.
        //    Taken from Minecraft's render type definitions.
        //    Leaves render cutout rather than translucent, so they are deliberately absent.

        // 5. Otherwise infer it.
        //    A server cannot call the client's render API, so this goes on block properties:
        //    translucent blocks usually block little light, though leaves do too.
        //    Leaves have lightBlock = 1 but render cutout_mipped, not translucent.

        // Exclude leaves explicitly, despite their lightBlock of 1.
        if (state.is(BlockTags.LEAVES)) {
            return false;  // leaves are solid, not an overlay
        }

        // Anything else blocking less than full light may be translucent.
        int lightBlock = getLightBlock(state);
        if (lightBlock > 0 && lightBlock < 15) {
            return true;
        }

        return false;
    }

    /**
     * Whether a block is invisible, and so skipped when scanning.
     *
     * Follows Xaero's MapWriter.isInvisible().
     *
     * @param block the block
     * @param state the block state
     * @param flowers whether flower rendering is enabled in the config
     * @return {@code true} if the block should be skipped
     */
    private static boolean checkInvisibility(Block block, BlockState state, boolean flowers) {
        // 1. RenderShape.INVISIBLE, which modded blocks get for free.
        if (!(block instanceof LiquidBlock) &&
            state.getRenderShape() == RenderShape.INVISIBLE) {
            return true;
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();

        // 2. Torches, which Xaero hardcodes.
        if (block == Blocks.TORCH || blockId.contains("torch") || blockId.endsWith("_torch")) {
            return true;
        }

        // 3. Short grass, which Xaero skips by default.
        if (block == Blocks.SHORT_GRASS) {
            return true;
        }

        // 4. Glass, which Xaero treats as invisible.
        if (block == Blocks.GLASS || block == Blocks.GLASS_PANE ||
            blockId.contains("stained_glass") || blockId.contains("stained_glass_pane")) {
            return true;
        }

        // 5. Flowers.
        boolean isFlower = checkIsFlower(block, state);

        // 6. DoublePlantBlocks that are not flowers (tall grass, large fern).
        if (block instanceof DoublePlantBlock && !isFlower) {
            return true;
        }

        // 7. Skip flowers when flower rendering is off.
        if (isFlower && !flowers) {
            return true;
        }

        // 8. Blocks whose MapColor lookup threw.
        String blockName = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (buggedBlocks.containsKey(blockName)) {
            return true;
        }

        return false;
    }

    /**
     * Whether a block is a flower.
     *
     * Follows Xaero: BlockTags.FLOWERS plus FlowerBlock and TallFlowerBlock.
     *
     * @param block the block
     * @param state the block state
     * @return {@code true} if it is a flower
     */
    private static boolean checkIsFlower(Block block, BlockState state) {
        // 1. The BlockTags.FLOWERS tag, which covers modded flowers.
        if (state.is(BlockTags.FLOWERS)) {
            return true;
        }

        // 2. FlowerBlock: vanilla small flowers.
        if (block instanceof FlowerBlock) {
            return true;
        }

        // 3. TallFlowerBlock: vanilla tall flowers.
        if (block instanceof TallFlowerBlock) {
            return true;
        }

        // 4. MushroomBlock: mushrooms.
        if (block instanceof MushroomBlock) {
            return true;
        }

        // 5. Lily pads.
        if (block == Blocks.LILY_PAD) {
            return true;
        }

        // 6. Specific vanilla flowers (mushrooms are not tagged as flowers but count here).
        if (block == Blocks.BROWN_MUSHROOM || block == Blocks.RED_MUSHROOM) {
            return true;
        }

        // 7. PitcherCropBlock.
        if (block instanceof PitcherCropBlock) {
            return true;
        }

        // 8. TorchflowerCropBlock.
        if (block instanceof TorchflowerCropBlock) {
            return true;
        }

        return false;
    }

    /**
     * Whether a block is a plant: flowers, grass, crops, mushrooms, vines and so on.
     *
     * Checked through base classes and BlockTags.
     *
     * @param block the block
     * @param state the block state
     * @param isFlower whether it has already been identified as a flower
     * @return {@code true} if it is a plant
     */
    private static boolean checkIsPlant(Block block, BlockState state, boolean isFlower) {
        // Flowers are plants.
        if (isFlower) {
            return true;
        }

        // 1. BushBlock: the base class most plants extend.
        if (block instanceof BushBlock) {
            return true;
        }

        // 2. CropBlock: wheat, carrots, potatoes, beetroot and so on.
        if (block instanceof CropBlock) {
            return true;
        }

        // 3. StemBlock: pumpkin and melon stems.
        if (block instanceof StemBlock) {
            return true;
        }

        // 4. AttachedStemBlock: stems that have fruited.
        if (block instanceof AttachedStemBlock) {
            return true;
        }

        // 5. SaplingBlock: saplings.
        if (block instanceof SaplingBlock) {
            return true;
        }

        // 6. TallGrassBlock: tall grass.
        if (block instanceof TallGrassBlock) {
            return true;
        }

        // 7. Dead bushes.
        if (block == Blocks.DEAD_BUSH) {
            return true;
        }

        // 8. CactusBlock: cactus.
        if (block instanceof CactusBlock) {
            return true;
        }

        // 9. SugarCaneBlock: sugar cane.
        if (block instanceof SugarCaneBlock) {
            return true;
        }

        // 10. BambooStalkBlock / BambooSaplingBlock: bamboo.
        if (block instanceof BambooStalkBlock || block instanceof BambooSaplingBlock) {
            return true;
        }

        // 11. NetherWartBlock: nether wart.
        if (block instanceof NetherWartBlock) {
            return true;
        }

        // 12. SeagrassBlock / TallSeagrassBlock: seagrass.
        if (block instanceof SeagrassBlock || block instanceof TallSeagrassBlock) {
            return true;
        }

        // 13. KelpBlock / KelpPlantBlock: kelp.
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock) {
            return true;
        }

        // 14. GrowingPlantBlock and friends: vine-like growing plants.
        if (block instanceof GrowingPlantBlock || block instanceof GrowingPlantBodyBlock || block instanceof GrowingPlantHeadBlock) {
            return true;
        }

        // 15. CaveVinesBlock / CaveVinesPlantBlock: glow berries.
        if (block instanceof CaveVinesBlock || block instanceof CaveVinesPlantBlock) {
            return true;
        }

        // 16. TwistingVinesBlock / TwistingVinesPlantBlock: twisting vines.
        if (block instanceof TwistingVinesBlock || block instanceof TwistingVinesPlantBlock) {
            return true;
        }

        // 17. WeepingVinesBlock / WeepingVinesPlantBlock: weeping vines.
        if (block instanceof WeepingVinesBlock || block instanceof WeepingVinesPlantBlock) {
            return true;
        }

        // 18. ChorusPlantBlock / ChorusFlowerBlock: chorus plants.
        if (block instanceof ChorusPlantBlock || block instanceof ChorusFlowerBlock) {
            return true;
        }

        // 19. BaseCoralPlantBlock: coral plants.
        if (block instanceof BaseCoralPlantBlock) {
            return true;
        }

        // 20. BigDripleafBlock / SmallDripleafBlock: dripleaf.
        if (block instanceof BigDripleafBlock || block instanceof SmallDripleafBlock) {
            return true;
        }

        // 21. BlockTags, which covers modded plants.
        //     The CROPS tag.
        if (state.is(BlockTags.CROPS)) {
            return true;
        }

        // 22. Name matching, as a fallback for mods that use no standard base class.
        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (blockId.contains("plant") || blockId.contains("crop") ||
            blockId.contains("sapling") || blockId.contains("seed") ||
            blockId.contains("vine") || blockId.contains("fern") ||
            blockId.contains("bush") || blockId.contains("grass") ||
            blockId.contains("kelp") || blockId.contains("seagrass") ||
            blockId.contains("cactus") || blockId.contains("reed") ||
            blockId.contains("stem") || blockId.contains("leaf") ||
            blockId.contains("mushroom") || blockId.contains("fungus")) {
            return true;
        }

        return false;
    }

    /**
     * Whether a block can be waterlogged.
     *
     * Decided by whether its state definition has the waterlogged property.
     *
     * @param block the block
     * @param state the block state
     * @return {@code true} if it can be waterlogged
     */
    private static boolean checkCanBeWaterlogged(Block block, BlockState state) {
        // The state definition is the accurate answer.
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equals("waterlogged")) {
                return true;
            }
        }

        // Failing that, match the usual waterloggable blocks by name.
        String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
        if (blockId.contains("fence_gate") || blockId.contains("stairs") ||
            blockId.contains("slab") || blockId.contains("wall") ||
            blockId.contains("door") || blockId.contains("trapdoor") ||
            blockId.contains("lantern") || blockId.contains("chain") ||
            blockId.contains("coral") || blockId.contains("grate") ||
            blockId.contains("sign") || blockId.contains("banner") ||
            blockId.contains("bed") || blockId.contains("scaffolding") ||
            blockId.contains("conduit") || blockId.contains("light") ||
            blockId.contains("sea_pickle") || blockId.contains("kelp")) {
            return true;
        }

        return false;
    }

    /**
     * Fallback properties for a block that is not in the registry.
     *
     * Inferred by matching the name.
     *
     * @param blockName the block name
     * @return the inferred properties
     */
    private static BlockProperties getFallbackProperties(String blockName) {
        String name = blockName.toLowerCase();

        boolean isAir = name.contains("air") || name.contains("void");
        boolean isWater = name.contains("water") && !name.contains("waterlogged");
        boolean isLava = name.contains("lava");
        boolean isFluid = isWater || isLava;

        boolean isTransparent = name.contains("glass") || name.contains("ice") ||
                               name.contains("kelp") || name.contains("seagrass");

        boolean isInvisible = name.contains("torch") ||
                             (name.contains("grass") && !name.contains("grass_block") && !name.contains("tall"));

        boolean isFlower = name.contains("flower") || name.contains("rose") ||
                          name.contains("tulip") || name.contains("lily");

        // Plants, by name.
        boolean isPlant = isFlower || name.contains("plant") || name.contains("crop") ||
                         name.contains("sapling") || name.contains("seed") ||
                         name.contains("vine") || name.contains("fern") ||
                         name.contains("bush") || name.contains("grass") ||
                         name.contains("kelp") || name.contains("seagrass") ||
                         name.contains("cactus") || name.contains("reed") ||
                         name.contains("stem") || name.contains("leaf") ||
                         name.contains("mushroom") || name.contains("fungus") ||
                         name.contains("wheat") || name.contains("carrot") ||
                         name.contains("potato") || name.contains("beetroot");

        boolean isGrassBlock = name.contains("grass_block");

        boolean isGlowing = name.contains("glow") || name.contains("lantern") ||
                           name.contains("lamp") || name.contains("torch") ||
                           name.contains("lava") || name.contains("fire");

        int lightBlock = isAir ? 0 : (isFluid || isTransparent ? 2 : 15);
        int lightEmission = isGlowing ? 15 : 0;

        boolean canBeWaterlogged = name.contains("fence") || name.contains("stairs") ||
                                  name.contains("slab") || name.contains("door") ||
                                  name.contains("trapdoor") || name.contains("wall") ||
                                  name.contains("lantern") || name.contains("coral");

        boolean hasVanillaColor = !isAir && !isInvisible;
        boolean hasMapColor = hasVanillaColor;

        return new BlockProperties(
            isAir, isWater, isLava, isFluid,
            isTransparent, isInvisible, isFlower, isPlant, isGrassBlock,
            isGlowing, lightBlock, lightEmission, canBeWaterlogged,
            hasVanillaColor, hasMapColor
        );
    }

    /**
     * The registry name of a block state.
     *
     * @param state the block state
     * @return the registry name, e.g. "minecraft:stone"
     */
    public static String getKey(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * The registry name of a block.
     *
     * @param block the block
     * @return the registry name, e.g. "minecraft:stone"
     */
    public static String getKey(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    /**
     * Empties the cache.
     */
    public static void clearCache() {
        propertiesCache.clear();
        buggedBlocks.clear();
    }

    /**
     * How many blocks are cached.
     *
     * @return the cache size
     */
    public static int getCacheSize() {
        return propertiesCache.size();
    }

    /**
     * How many blocks are known to be broken.
     *
     * @return how many blocks threw from MapColor
     */
    public static int getBuggedBlocksCount() {
        return buggedBlocks.size();
    }

    // ========== Convenience wrappers ==========

    /**
     * Whether a block is air.
     *
     * @param blockName the block name
     * @return {@code true} for air
     */
    public static boolean isAir(String blockName) {
        return getProperties(blockName).isAir();
    }

    /**
     * Whether a block is water.
     *
     * @param blockName the block name
     * @return {@code true} for water
     */
    public static boolean isWater(String blockName) {
        return getProperties(blockName).isWater();
    }

    /**
     * Whether a block is lava.
     *
     * @param blockName the block name
     * @return {@code true} for lava
     */
    public static boolean isLava(String blockName) {
        return getProperties(blockName).isLava();
    }

    /**
     * Whether a block is a fluid.
     *
     * @param blockName the block name
     * @return {@code true} for fluids
     */
    public static boolean isFluid(String blockName) {
        return getProperties(blockName).isFluid();
    }

    /**
     * Whether a block is transparent.
     *
     * @param blockName the block name
     * @return {@code true} if transparent
     */
    public static boolean isTransparent(String blockName) {
        return getProperties(blockName).isTransparent();
    }

    /**
     * Whether a block is invisible.
     *
     * @param blockName the block name
     * @return {@code true} if invisible
     */
    public static boolean isInvisible(String blockName) {
        return getProperties(blockName).isInvisible();
    }

    /**
     * Whether a block is a flower.
     *
     * @param blockName the block name
     * @return {@code true} for flowers
     */
    public static boolean isFlower(String blockName) {
        return getProperties(blockName).isFlower();
    }

    /**
     * Whether a block is a plant.
     *
     * @param blockName the block name
     * @return {@code true} for plants
     */
    public static boolean isPlant(String blockName) {
        return getProperties(blockName).isPlant();
    }

    /**
     * Whether a block is a grass block.
     *
     * @param blockName the block name
     * @return {@code true} for grass blocks
     */
    public static boolean isGrassBlock(String blockName) {
        return getProperties(blockName).isGrassBlock();
    }

    /**
     * Whether a block glows.
     *
     * @param blockName the block name
     * @return {@code true} if it emits light
     */
    public static boolean isGlowing(String blockName) {
        return getProperties(blockName).isGlowing();
    }

    /**
     * How much light a block blocks.
     *
     * @param blockName the block name
     * @return the light blocking value, 0-15
     */
    public static int getLightBlock(String blockName) {
        return getProperties(blockName).lightBlock();
    }

    /**
     * How much light a block emits.
     *
     * @param blockName the block name
     * @return the light emission value, 0-15
     */
    public static int getLightEmission(String blockName) {
        return getProperties(blockName).lightEmission();
    }

    /**
     * Whether a block can be waterlogged.
     *
     * @param blockName the block name
     * @return {@code true} if it can be waterlogged
     */
    public static boolean canBeWaterlogged(String blockName) {
        return getProperties(blockName).canBeWaterlogged();
    }

    /**
     * Whether a block has a vanilla map colour.
     *
     * @param blockName the block name
     * @return {@code true} if it has one
     */
    public static boolean hasVanillaColor(String blockName) {
        return getProperties(blockName).hasVanillaColor();
    }

    /**
     * Whether a block has a usable map colour.
     *
     * @param blockName the block name
     * @return {@code true} if it has one
     */
    public static boolean hasMapColor(String blockName) {
        return getProperties(blockName).hasMapColor();
    }

    /**
     * Whether a block should be drawn as an overlay.
     *
     * @param blockName the block name
     * @return {@code true} if it should be an overlay
     */
    public static boolean shouldOverlay(String blockName) {
        return getProperties(blockName).shouldOverlay();
    }

    /**
     * Whether a block is a see-through fluid.
     *
     * @param blockName the block name
     * @return {@code true} for water
     */
    public static boolean isTranslucentFluid(String blockName) {
        return getProperties(blockName).isTranslucentFluid();
    }

    /**
     * Whether this is the surface of a waterlogged block.
     *
     * @param blockName the block name
     * @param properties the block's property values
     * @return {@code true} if it is waterlogged
     */
    public static boolean isWaterloggedSurface(String blockName, Map<String, String> properties) {
        return getProperties(blockName).isWaterloggedSurface(properties);
    }

    /**
     * A stand-in BlockGetter, for APIs that insist on one.
     *
     * Returns air and empty fluid everywhere; nothing reads real world data through it.
     */
    private static class PlaceholderBlockGetter implements BlockGetter {
        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }

        /**
         * Paper declares {@code getBlockStateIfLoaded} on {@code BlockGetter}; Fabric and
         * vanilla do not. There is deliberately no {@code @Override}: on Paper this
         * implements the interface method, on Fabric it is simply a method nothing calls,
         * and one source tree serves both.
         *
         * @param pos the position
         * @return air; this is a stand-in
         */
        public BlockState getBlockStateIfLoaded(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        /**
         * As {@link #getBlockStateIfLoaded(BlockPos)}: a Paper-only interface method.
         *
         * @param pos the position
         * @return empty; this is a stand-in
         */
        public net.minecraft.world.level.material.FluidState getFluidIfLoaded(BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 256;
        }

        @Override
        public int getMinY() {
            return -64;
        }
    }
}
