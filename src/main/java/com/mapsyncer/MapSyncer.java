package com.mapsyncer;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.platform.MapSyncerPlatform;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.server.DimensionRegistry;
import com.mapsyncer.server.DirtyRegionTracker;
import com.mapsyncer.server.IncrementalUpdateHandler;
import com.mapsyncer.server.PlayerJoinHandler;
import com.mapsyncer.server.PublicWaypointConfig;
import com.mapsyncer.server.ServerSyncHandler;
import com.mapsyncer.server.VoxySyncHandler;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MapSyncer's server-side core, independent of the loader it runs on.
 *
 * <p>Called by whichever entrypoint is present:</p>
 * <ul>
 *   <li>Fabric: {@code com.mapsyncer.fabric.MapSyncerFabric}</li>
 *   <li>Paper: {@code com.mapsyncer.paper.MapSyncerPlugin}</li>
 * </ul>
 *
 * <p>The entrypoint installs a {@link MapSyncerPlatform} and forwards its loader's
 * lifecycle events to the {@code onServerXxx} methods below. Everything the server
 * actually does is shared between the two.</p>
 */
public final class MapSyncer {

    /** Mod / plugin ID, and the namespace of every network channel. */
    public static final String MOD_ID = "mapsyncer";

    /** Shared logger. */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Current version, read from the platform by {@link #bootstrap(MapSyncerPlatform)}. */
    public static String VERSION = "unknown";

    private MapSyncer() {
    }

    /**
     * Starts the server core: installs the platform, loads config, registers packets.
     *
     * @param platform the implementation for this loader
     */
    public static void bootstrap(MapSyncerPlatform platform) {
        Platform.set(platform);
        VERSION = platform.version();

        ModConfig.load();
        PublicWaypointConfig.load();
        ServerSyncHandler.register();
        VoxySyncHandler.register();
        VoxySyncHandler.logSecurityWarningIfEnabled();

        LOGGER.info("MapSyncer {} initialized for {}", VERSION, platform.name());
    }

    /**
     * Server finished starting: register dimensions and start incremental updates if enabled.
     *
     * @param server the server
     */
    public static void onServerStarted(MinecraftServer server) {
        DimensionRegistry.registerAllDimensions(server);

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;
        if (mode != UpdateMode.DISABLED) {
            IncrementalUpdateHandler.getInstance().start(server);
        }
    }

    /**
     * Server is shutting down: stop incremental updates and Voxy syncing.
     *
     * @param server the server
     */
    public static void onServerStopping(MinecraftServer server) {
        IncrementalUpdateHandler.getInstance().stop();
        VoxySyncHandler.cleanup();
        DirtyRegionTracker.clear();
    }

    /**
     * Server has stopped: release every cached singleton.
     *
     * @param server the server
     */
    public static void onServerStopped(MinecraftServer server) {
        PlayerJoinHandler.onServerStopped(server);
    }

    /**
     * Server tick: drives incremental updates and cleanup of stale per-player state.
     *
     * @param server the server
     */
    public static void onServerTick(MinecraftServer server) {
        IncrementalUpdateHandler.onServerTick(server);
        PlayerJoinHandler.onServerTick(server);
    }
}
