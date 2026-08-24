package com.mapsyncer.fabric;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.platform.MapSyncerPlatform;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Fabric implementation of {@link MapSyncerPlatform}.
 *
 * <p>Networking goes straight through the Fabric API's {@code PayloadTypeRegistry}
 * and {@code ServerPlayNetworking}, exactly as it did before the platform split.</p>
 */
public final class FabricPlatform implements MapSyncerPlatform {

    private volatile MinecraftServer server;

    @Override
    public String name() {
        return "Fabric";
    }

    @Override
    public String version() {
        return FabricLoader.getInstance()
                .getModContainer(MapSyncer.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public MinecraftServer server() {
        return server;
    }

    /**
     * Records the running server.
     *
     * @param server the server, or {@code null} once it has stopped
     */
    void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public <T extends CustomPacketPayload> void registerClientbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.clientboundPlay().register(type, codec);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            PayloadHandler<T> handler) {
        PayloadTypeRegistry.serverboundPlay().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type,
                (payload, context) -> handler.handle(payload, context.player()));
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || player.connection == null) {
            return;
        }
        if (!ServerPlayNetworking.canSend(player, payload.type())) {
            return;
        }
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player != null && player.connection != null && ServerPlayNetworking.canSend(player, type);
    }
}
