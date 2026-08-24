package com.mapsyncer.paper;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.platform.MapSyncerPlatform;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper implementation of {@link MapSyncerPlatform}.
 *
 * <p>Speaks the same protocol as the Fabric server over Bukkit plugin messaging:
 * identical channel IDs and identical payload bytes, so the client mod cannot tell
 * which kind of server it is talking to.</p>
 *
 * <p>Two things genuinely differ from Fabric:</p>
 * <ul>
 *   <li><b>Payload size.</b> {@link Messenger#MAX_MESSAGE_SIZE} is 32766 bytes, far below
 *       the 1MB that vanilla {@code custom_payload} allows, so {@link #maxPayloadBytes()}
 *       reports a smaller limit and the sync logic splits regions into more parts.</li>
 *   <li><b>Channel availability.</b> A client announces the channels it listens on via
 *       {@code minecraft:register}; anything sent before that is dropped. The join
 *       handshake is therefore driven by {@code PlayerRegisterChannelEvent} — see
 *       {@link MapSyncerPlugin}.</li>
 * </ul>
 */
public final class PaperPlatform implements MapSyncerPlatform {

    /**
     * Byte limit for a single plugin message.
     *
     * <p>{@link Messenger#MAX_MESSAGE_SIZE} is 32766; this leaves headroom so that payload
     * header fields cannot push a message over the limit, which Bukkit answers with an
     * exception rather than a truncated send.</p>
     */
    private static final int MAX_PLUGIN_MESSAGE_BYTES = 30_000;

    /** Permission node for admin access, for permission plugins. Ops (level 4) also qualify. */
    static final String ADMIN_PERMISSION = "mapsyncer.admin";

    private final MapSyncerPlugin plugin;

    /** Server-to-client payload type to encoder. */
    private final Map<String, StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload>> clientboundCodecs =
            new ConcurrentHashMap<>();

    /**
     * Channels each player listens on.
     *
     * <p>Sync tasks send from worker threads, while
     * {@code CraftPlayer#getListeningPluginChannels()} reads a plain collection that is
     * only mutated on the main thread. This is a thread-safe mirror of it, maintained from
     * the channel register/unregister events in {@link MapSyncerPlugin}.</p>
     */
    private final Map<UUID, Set<String>> listeningChannels = new ConcurrentHashMap<>();

    /**
     * @param plugin the host plugin
     */
    PaperPlatform(MapSyncerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "Paper";
    }

    @Override
    public String version() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public Path configDir() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public MinecraftServer server() {
        return ((CraftServer) Bukkit.getServer()).getServer();
    }

    @Override
    public int maxPayloadBytes() {
        return MAX_PLUGIN_MESSAGE_BYTES;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Bukkit's {@code World#getWorldPath()} names the save directory of that world
     * directly — on 26.x that is {@code world/dimensions/<namespace>/<name>} — which beats
     * guessing a directory from the dimension ID and also covers worlds created by plugins
     * such as Multiverse. Returns {@code null} when there is no Bukkit world, leaving
     * {@code RegionScanner} to probe the layout itself.</p>
     */
    @Override
    public Path regionDir(ServerLevel level) {
        CraftWorld world = level.getWorld();
        if (world == null) {
            return null;
        }
        return world.getWorldPath().resolve("region");
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends CustomPacketPayload> void registerClientbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        String channel = type.id().toString();
        clientboundCodecs.put(channel,
                (StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload>) codec);
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
    }

    @Override
    public <T extends CustomPacketPayload> void registerServerbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            PayloadHandler<T> handler) {
        String channel = type.id().toString();
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, channel,
                (incomingChannel, bukkitPlayer, message) ->
                        receive(incomingChannel, bukkitPlayer, message, codec, handler));
    }

    /**
     * Decodes one payload from a client and hands it to its handler.
     *
     * <p>The bytes come from a client and are untrusted: a decode failure is logged and
     * the message dropped, never rethrown.</p>
     *
     * @param channel      the channel ID
     * @param bukkitPlayer the sender
     * @param message      the raw bytes
     * @param codec        the stream codec
     * @param handler      the handler for this payload type
     * @param <T>          the payload type
     */
    private <T extends CustomPacketPayload> void receive(
            String channel,
            Player bukkitPlayer,
            byte[] message,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            PayloadHandler<T> handler) {
        ServerPlayer serverPlayer = ((CraftPlayer) bukkitPlayer).getHandle();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(message), server().registryAccess());
        try {
            T payload = codec.decode(buf);
            handler.handle(payload, serverPlayer);
        } catch (Exception e) {
            MapSyncer.LOGGER.warn("Failed to decode {} from {}: {}",
                    channel, bukkitPlayer.getName(), e.toString());
        } finally {
            buf.release();
        }
    }

    @Override
    public void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player == null || player.connection == null) {
            return;
        }

        String channel = payload.type().id().toString();
        StreamCodec<RegistryFriendlyByteBuf, CustomPacketPayload> codec = clientboundCodecs.get(channel);
        if (codec == null) {
            MapSyncer.LOGGER.warn("No clientbound codec registered for {}", channel);
            return;
        }

        CraftPlayer bukkitPlayer = player.getBukkitEntity();
        if (!isListening(player.getUUID(), channel)) {
            return;
        }

        byte[] data;
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), server().registryAccess());
        try {
            codec.encode(buf, payload);
            data = new byte[buf.readableBytes()];
            buf.readBytes(data);
        } catch (Exception e) {
            MapSyncer.LOGGER.error("Failed to encode {} for {}", channel, bukkitPlayer.getName(), e);
            return;
        } finally {
            buf.release();
        }

        if (data.length > Messenger.MAX_MESSAGE_SIZE) {
            MapSyncer.LOGGER.error(
                    "Dropping {} for {}: {} bytes exceeds the plugin message limit of {} bytes. "
                            + "Lower maxSyncPacketSize in the MapSyncer config.",
                    channel, bukkitPlayer.getName(), data.length, Messenger.MAX_MESSAGE_SIZE);
            return;
        }

        bukkitPlayer.sendPluginMessage(plugin, channel, data);
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        if (player == null || player.connection == null) {
            return false;
        }
        return isListening(player.getUUID(), type.id().toString());
    }

    @Override
    public boolean isAdmin(ServerPlayer player) {
        return MapSyncerPlatform.super.isAdmin(player)
                || player.getBukkitEntity().hasPermission(ADMIN_PERMISSION);
    }

    @Override
    public boolean isAdmin(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return isAdmin(player);
        }
        return MapSyncerPlatform.super.isAdmin(source);
    }

    /**
     * @param playerId the player
     * @param channel  the channel ID
     * @return whether that player has registered the channel
     */
    private boolean isListening(UUID playerId, String channel) {
        Set<String> channels = listeningChannels.get(playerId);
        return channels != null && channels.contains(channel);
    }

    /**
     * Records that a player registered a channel.
     *
     * @param playerId the player
     * @param channel  the channel ID
     */
    void onChannelRegistered(UUID playerId, String channel) {
        listeningChannels.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    /**
     * Records that a player unregistered a channel.
     *
     * @param playerId the player
     * @param channel  the channel ID
     */
    void onChannelUnregistered(UUID playerId, String channel) {
        Set<String> channels = listeningChannels.get(playerId);
        if (channels != null) {
            channels.remove(channel);
        }
    }

    /**
     * Player disconnected: forget their channels.
     *
     * @param playerId the player
     */
    void onPlayerDisconnect(UUID playerId) {
        listeningChannels.remove(playerId);
    }
}
