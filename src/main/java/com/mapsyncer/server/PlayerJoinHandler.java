package com.mapsyncer.server;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.util.BlockColorMapper;
import com.mapsyncer.util.MapSyncerExecutors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

/**
 * Handles players joining and leaving, plus cleanup at server shutdown.
 *
 * - starts the incremental update handler on join, if enabled and not already running
 * - interrupts a player's sync task when they leave
 * - periodically clears state left behind by players who dropped out uncleanly
 * - releases every cached singleton when the server stops
 */
public class PlayerJoinHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerJoinHandler.class);

    /** How often to run the stale-state sweep: 1200 ticks, i.e. once a minute. */
    private static final int CLEANUP_CHECK_INTERVAL_TICKS = 1200;

    /** Ticks since the last sweep. */
    private static int cleanupTickCounter = 0;

    /**
     * A player joined.
     *
     * Tells the client the server has MapSyncer, sends the public waypoints, and starts the
     * incremental update handler if it is enabled and not already running.
     *
     * @param player the player that joined
     */
    public static void onPlayerJoin(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        // Tell the client this server has MapSyncer installed.
        Platform.send(player, new PacketHandler.ServerInstalledPayload(MapSyncer.VERSION));
        ServerSyncHandler.sendPublicWaypoints(player);

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;
        if (!ConversionOrchestrator.isRunning() && mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(server);
        }
    }

    /**
     * A player left: interrupt whatever sync they had running.
     *
     * @param player the player that left
     */
    public static void onPlayerLeave(ServerPlayer player) {
        ServerSyncHandler.onPlayerDisconnect(player.getUUID());
        VoxySyncHandler.onPlayerDisconnect(player.getUUID());
    }

    /**
     * The server stopped: drop every cached singleton so a restart does not leak the old ones.
     *
     * @param server the server that stopped
     */
    public static void onServerStopped(MinecraftServer server) {
        LOGGER.info("Server stopped, cleaning up singleton cache instances");

        // Reset singleton instances to release memory
        GenerationCache.resetInstance();
        McaTimestampCache.resetInstance();
        IncrementalUpdateHandler.resetInstance();

        // Clear shared/server-side static caches.
        BlockColorMapper.clearCache();
        BlockPropertyResolver.clearCache();

        // Clear sync tracking data
        ServerSyncHandler.cleanup();
        VoxySyncHandler.cleanup();
        MapSyncerExecutors.shutdown();

        LOGGER.info("Singleton cache cleanup completed");
    }

    /**
     * Server tick: periodically clears state left by players who dropped out uncleanly.
     *
     * <p>When a connection dies outright — network drop, client crash — the leave handler may
     * never run, leaving entries behind in {@code syncingPlayers} and friends. This sweep
     * compares the tracked players against the online player list and clears anyone who is
     * no longer connected.</p>
     *
     * @param server the server
     */
    public static void onServerTick(MinecraftServer server) {
        cleanupTickCounter++;

        // Once a minute.
        if (cleanupTickCounter < CLEANUP_CHECK_INTERVAL_TICKS) {
            return;
        }
        cleanupTickCounter = 0;

        if (server == null) return;

        // UUIDs of everyone currently online.
        Set<UUID> onlinePlayerIds = new java.util.HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            onlinePlayerIds.add(player.getUUID());
        }

        // Drop state belonging to anyone who is not.
        ServerSyncHandler.cleanupOfflinePlayers(onlinePlayerIds);
        VoxySyncHandler.cleanupOfflinePlayers(onlinePlayerIds);
    }
}
