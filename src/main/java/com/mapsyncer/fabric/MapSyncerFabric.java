package com.mapsyncer.fabric;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.PlayerJoinHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Fabric server entrypoint.
 *
 * <p>Does nothing but wire Fabric's lifecycle events to the shared {@link MapSyncer}
 * core; the sync logic itself is the same code the Paper plugin runs.</p>
 */
public class MapSyncerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        FabricPlatform platform = new FabricPlatform();
        MapSyncer.bootstrap(platform);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CacheGenerateCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            platform.setServer(server);
            MapSyncer.onServerStarted(server);
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(MapSyncer::onServerStopping);

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            MapSyncer.onServerStopped(server);
            platform.setServer(null);
        });

        ServerTickEvents.END_SERVER_TICK.register(MapSyncer::onServerTick);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PlayerJoinHandler.onPlayerJoin(handler.player));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PlayerJoinHandler.onPlayerLeave(handler.player));
    }
}
