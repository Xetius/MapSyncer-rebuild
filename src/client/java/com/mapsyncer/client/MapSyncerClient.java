package com.mapsyncer.client;

import com.mapsyncer.client.voxy.VoxySyncClient;
import com.mapsyncer.network.PacketHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapSyncerClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerClient.class);

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        SyncHudOverlay.register();
        MapSyncerKeybinds.register();
        ClientTickEvents.END_CLIENT_TICK.register(MapPacketReceiver::onClientTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                MapSyncerCommand.register(dispatcher));

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.SyncResponsePayload.TYPE,
                (payload, context) -> MapPacketReceiver.handleSyncResponse(payload, context));

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.SyncProgressPayload.TYPE,
                (payload, context) -> MapPacketReceiver.handleSyncProgress(payload, context));

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.SyncRegionPartPayload.TYPE,
                (payload, context) -> MapPacketReceiver.handleSyncRegionPart(payload, context));

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.SyncRegionCompletePayload.TYPE,
                (payload, context) -> MapPacketReceiver.handleSyncRegionComplete(payload, context));

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.ServerInstalledPayload.TYPE,
                (payload, context) -> {
                    MapPacketReceiver.handleServerInstalled(payload, context);
                    context.client().execute(VoxySyncClient::requestCapability);
                });

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.AdminStatusPayload.TYPE,
                (payload, context) -> AdminStatusClientState.handle(payload, context));

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.OpenGuiPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        context.client().setScreenAndShow(new MapSyncerScreen())));

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.PublicWaypointsPayload.TYPE,
                PublicWaypointReceiver::handle);

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.PublicWaypointAddResultPayload.TYPE,
                (payload, context) -> PublicWaypointImportClientState.handleAddResult(payload));

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.VoxyCapabilityPayload.TYPE,
                VoxySyncClient::handleCapability);

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.VoxySyncStartPayload.TYPE,
                VoxySyncClient::handleStart);

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.VoxyRegionPartPayload.TYPE,
                VoxySyncClient::handlePart);

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.VoxySyncProgressPayload.TYPE,
                VoxySyncClient::handleProgress);

        ClientPlayNetworking.registerGlobalReceiver(PacketHandler.VoxySyncCompletePayload.TYPE,
                VoxySyncClient::handleComplete);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientJoinHandler.onClientJoin(client);
            client.execute(VoxySyncClient::requestCapability);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AdminStatusClientState.reset();
            PublicWaypointClientState.reset();
            PublicWaypointImportClientState.reset();
            VoxySyncClient.reset();
            ClientJoinHandler.onClientDisconnect(client);
        });

        LOGGER.info("MapSyncer client initialized for Fabric");
    }
}
