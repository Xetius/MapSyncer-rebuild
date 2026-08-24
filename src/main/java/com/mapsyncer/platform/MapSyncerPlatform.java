package com.mapsyncer.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * The seam between MapSyncer's server logic and the loader it runs on.
 *
 * <p>The same server-side code runs on a Fabric server and on a Paper server.
 * Everything they disagree about — where config lives, where the version string
 * comes from, how custom payloads are sent and received, how big a payload may
 * be, and how admin permissions are checked — is collected here. Scanning,
 * conversion, caching, throttling and waypoints are shared verbatim.</p>
 *
 * <p>Networking:</p>
 * <ul>
 *   <li>Fabric uses {@code ServerPlayNetworking} and {@code PayloadTypeRegistry}.</li>
 *   <li>Paper uses Bukkit plugin messaging. Both are {@code minecraft:custom_payload}
 *       on the wire and use identical channel IDs and byte layouts, so one Fabric
 *       client mod talks to either server without knowing the difference.</li>
 * </ul>
 */
public interface MapSyncerPlatform {

    /** Platform name for log messages, e.g. {@code "Fabric"} or {@code "Paper"}. */
    String name();

    /** Version of the running mod or plugin. */
    String version();

    /** Config directory: {@code config/} on Fabric, {@code plugins/MapSyncer/} on Paper. */
    Path configDir();

    /** The running Minecraft server, or {@code null} before it has started. */
    MinecraftServer server();

    /**
     * The region directory for a dimension, when the platform can name it directly.
     *
     * <p>Returns {@code null} by default, which tells {@code RegionScanner} to probe
     * the known save layouts itself ({@code region/}, {@code DIM-1/region/},
     * {@code dimensions/<namespace>/<name>/region/}).</p>
     *
     * <p>Paper overrides this: Bukkit's {@code World#getWorldPath()} names the save
     * directory of that world directly, which is more reliable than guessing from the
     * dimension ID and also covers worlds created by plugins such as Multiverse.</p>
     *
     * @param level the dimension
     * @return the region directory, or {@code null} if the platform cannot determine it
     */
    default Path regionDir(ServerLevel level) {
        return null;
    }

    /**
     * Whether a player may use MapSyncer's admin features.
     *
     * <p>Defaults to the vanilla permission level ({@code LEVEL_OWNERS}, op level 4).
     * Paper also accepts the {@code mapsyncer.admin} permission node so that
     * permission plugins can grant access without opping.</p>
     *
     * @param player the player
     * @return {@code true} if the player has admin access
     */
    default boolean isAdmin(ServerPlayer player) {
        return Commands.LEVEL_OWNERS.check(player.permissions());
    }

    /**
     * Whether a command source may use MapSyncer's admin commands. The console always may.
     *
     * @param source the command source
     * @return {@code true} if the source has admin access
     */
    default boolean isAdmin(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_OWNERS).test(source);
    }

    /**
     * Maximum size of a single payload, in bytes.
     *
     * <p>Fabric gets the vanilla {@code custom_payload} limit of 1MB. Paper goes through
     * Bukkit plugin messaging, which caps messages at {@code Messenger.MAX_MESSAGE_SIZE}
     * (32766 bytes), so it returns a smaller value and the sync logic splits regions into
     * correspondingly more parts.</p>
     *
     * @return the per-payload byte limit
     */
    default int maxPayloadBytes() {
        return 1_000_000;
    }

    /**
     * Registers a server-to-client payload type.
     *
     * @param type  the payload type
     * @param codec its stream codec
     * @param <T>   the payload type
     */
    <T extends CustomPacketPayload> void registerClientbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec);

    /**
     * Registers a client-to-server payload type and the handler that receives it.
     *
     * <p>The handler may be called off the main thread; handlers that touch world state
     * are responsible for hopping onto the server thread themselves.</p>
     *
     * @param type    the payload type
     * @param codec   its stream codec
     * @param handler called with each decoded payload
     * @param <T>     the payload type
     */
    <T extends CustomPacketPayload> void registerServerbound(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            PayloadHandler<T> handler);

    /**
     * Sends a payload to a player. Silently does nothing if the player has disconnected
     * or their client does not listen on that channel.
     *
     * @param player  the recipient
     * @param payload the payload to send
     */
    void send(ServerPlayer player, CustomPacketPayload payload);

    /**
     * Whether the player's client has announced support for a payload type.
     *
     * @param player the player
     * @param type   the payload type
     * @return {@code true} if the client registered that channel
     */
    boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

    /** Receives payloads sent by a client. */
    @FunctionalInterface
    interface PayloadHandler<T extends CustomPacketPayload> {
        /**
         * @param payload the decoded payload
         * @param player  the player that sent it
         */
        void handle(T payload, ServerPlayer player);
    }
}
