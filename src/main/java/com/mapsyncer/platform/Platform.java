package com.mapsyncer.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Holds the {@link MapSyncerPlatform} implementation for the current server.
 *
 * <p>The loader entrypoint ({@code MapSyncerFabric} on Fabric, {@code MapSyncerPlugin}
 * on Paper) installs it with {@link #set(MapSyncerPlatform)} during startup; the shared
 * server code reaches the platform through this class.</p>
 */
public final class Platform {

    private static volatile MapSyncerPlatform current;

    private Platform() {
    }

    /**
     * Installs the platform implementation. Must run before any other MapSyncer code.
     *
     * @param platform the implementation for this loader
     */
    public static void set(MapSyncerPlatform platform) {
        current = platform;
    }

    /**
     * @return the current platform implementation
     * @throws IllegalStateException if no platform has been installed yet
     */
    public static MapSyncerPlatform get() {
        MapSyncerPlatform platform = current;
        if (platform == null) {
            throw new IllegalStateException("MapSyncer platform has not been initialised");
        }
        return platform;
    }

    /** @return whether a platform has been installed */
    public static boolean isInitialised() {
        return current != null;
    }

    /** @return the config directory */
    public static Path configDir() {
        return get().configDir();
    }

    /**
     * Sends a payload to a player.
     *
     * @param player  the recipient
     * @param payload the payload to send
     */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        get().send(player, payload);
    }

    /**
     * Whether the player's client has announced support for a payload type.
     *
     * @param player the player
     * @param type   the payload type
     * @return {@code true} if the client registered that channel
     */
    public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return get().canSend(player, type);
    }
}
