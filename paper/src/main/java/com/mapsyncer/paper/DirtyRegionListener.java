package com.mapsyncer.paper;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.server.DirtyRegionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Dirty-region tracking for Paper.
 *
 * <p>The Fabric build mixes into {@code LevelChunk#setBlockState} and therefore sees every
 * block change. Paper has no mixins, so this listens to Bukkit events instead, covering the
 * common sources: player edits, explosions, growth, pistons and newly generated chunks.</p>
 *
 * <p>Treat this as an accelerator rather than the source of truth. Changes it misses — another
 * plugin writing blocks directly, or parts of world generation that fire no event — are still
 * picked up by the incremental scan through {@code .mca} file timestamps, just not until the
 * next scan. Keeping {@code dirtyRegionFallbackFullScan = true} (the default) is therefore
 * recommended on Paper.</p>
 */
public final class DirtyRegionListener implements Listener {

    /**
     * Marks the region containing a block as dirty.
     *
     * @param block the block that changed
     */
    private static void mark(Block block) {
        if (!ModConfig.SERVER.enableDirtyRegionTracking) {
            return;
        }
        ServerLevel level = ((CraftWorld) block.getWorld()).getHandle();
        DirtyRegionTracker.markDirty(level, new BlockPos(block.getX(), block.getY(), block.getZ()));
    }

    /**
     * Marks the region containing a chunk as dirty.
     *
     * @param chunk the chunk that changed
     */
    private static void mark(Chunk chunk) {
        if (!ModConfig.SERVER.enableDirtyRegionTracking) {
            return;
        }
        ServerLevel level = ((CraftWorld) chunk.getWorld()).getHandle();
        DirtyRegionTracker.markDirty(level,
                new BlockPos(chunk.getX() << 4, level.getMinY(), chunk.getZ() << 4));
    }

    /** @param event a block was placed */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        mark(event.getBlock());
    }

    /** @param event a block was broken */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        mark(event.getBlock());
    }

    /** @param event a block burned away */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        mark(event.getBlock());
    }

    /** @param event a block faded, such as ice or snow melting */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        mark(event.getBlock());
    }

    /** @param event a block formed, such as ice or snow */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        mark(event.getBlock());
    }

    /** @param event a crop grew */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent event) {
        mark(event.getBlock());
    }

    /** @param event a block spread, such as grass or mycelium */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        mark(event.getBlock());
    }

    /** @param event leaves decayed */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        mark(event.getBlock());
    }

    /** @param event a block exploded */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        mark(event.getBlock());
        event.blockList().forEach(DirtyRegionListener::mark);
    }

    /** @param event an entity exploded */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(DirtyRegionListener::mark);
    }

    /** @param event an entity changed a block, such as an enderman or wither */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        mark(event.getBlock());
    }

    /** @param event a piston extended */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        mark(event.getBlock());
    }

    /** @param event a piston retracted */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        mark(event.getBlock());
    }

    /** @param event a bucket was emptied */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        mark(event.getBlock());
    }

    /** @param event a bucket was filled */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        mark(event.getBlock());
    }

    /** @param event a tree or fungus grew */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        mark(event.getLocation().getBlock());
    }

    /** @param event a new chunk was generated */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkPopulate(ChunkPopulateEvent event) {
        mark(event.getChunk());
    }
}
