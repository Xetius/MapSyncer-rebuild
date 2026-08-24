package com.mapsyncer.paper;

import com.mapsyncer.MapSyncer;
import com.mapsyncer.server.CacheGenerateCommand;
import com.mapsyncer.server.PlayerJoinHandler;
import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MapSyncer's Paper server entrypoint.
 *
 * <p>Runs the same server logic as the Fabric version — scanning, conversion, caching,
 * syncing, waypoints and Voxy. This class only wires Bukkit's lifecycle events to
 * {@link MapSyncer} and papers over the one place the two loaders genuinely differ:
 * when the client handshake can happen.</p>
 *
 * <p><b>Handshake timing.</b> A Fabric server already knows which channels a client
 * listens on by the time the player joins, but Bukkit's {@code PlayerJoinEvent} can
 * fire before the client's {@code minecraft:register} arrives. So the handshake is not
 * driven by the join event: it waits until the client registers the
 * {@code mapsyncer:server_installed} channel, which guarantees the "server has
 * MapSyncer" notice and the public waypoints actually reach the client.</p>
 */
public final class MapSyncerPlugin extends JavaPlugin implements Listener {

    /** A client registering this channel means its MapSyncer mod is ready. */
    private static final String HANDSHAKE_CHANNEL =
            com.mapsyncer.network.PacketHandler.SERVER_INSTALLED_ID.toString();

    private PaperPlatform platform;

    /** Players that have already handshaken, so it only happens once each. */
    private final Set<UUID> handshaken = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        platform = new PaperPlatform(this);
        MapSyncer.bootstrap(platform);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new DirtyRegionListener(), this);

        registerCommands();

        // The equivalent of Fabric's END_SERVER_TICK: drives incremental updates
        // and cleanup of state left behind by players who dropped out.
        getServer().getScheduler().runTaskTimer(this, () -> {
            MinecraftServer server = platform.server();
            if (server != null) {
                MapSyncer.onServerTick(server);
            }
        }, 1L, 1L);
    }

    @Override
    public void onDisable() {
        MinecraftServer server = platform == null ? null : platform.server();
        if (server != null) {
            MapSyncer.onServerStopping(server);
            MapSyncer.onServerStopped(server);
        }
        handshaken.clear();
    }

    /**
     * Registers the {@code /mapsyncer} command.
     *
     * <p>Paper's command registration ends up on the vanilla Brigadier dispatcher, so the
     * shared {@link CacheGenerateCommand} can be reused as-is. The lifecycle event fires
     * again every time the command tree is rebuilt (on a datapack reload, for instance),
     * so the command is never lost.</p>
     */
    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();
            @SuppressWarnings("unchecked")
            CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher =
                    (CommandDispatcher<net.minecraft.commands.CommandSourceStack>)
                            (CommandDispatcher<?>) registrar.getDispatcher();
            CacheGenerateCommand.register(dispatcher);
        });
    }

    /**
     * Server finished starting: register dimensions and start incremental updates if enabled.
     *
     * @param event the server load event
     */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        MinecraftServer server = platform.server();
        if (server != null) {
            MapSyncer.onServerStarted(server);
        }
    }

    /**
     * A client registered a channel; handshake once its MapSyncer mod is ready.
     *
     * @param event the channel registration event
     */
    @EventHandler
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        platform.onChannelRegistered(player.getUUID(), event.getChannel());

        if (!HANDSHAKE_CHANNEL.equals(event.getChannel())) {
            return;
        }
        if (handshaken.add(player.getUUID())) {
            PlayerJoinHandler.onPlayerJoin(player);
        }
    }

    /**
     * A client unregistered a channel.
     *
     * @param event the channel unregistration event
     */
    @EventHandler
    public void onUnregisterChannel(PlayerUnregisterChannelEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        platform.onChannelUnregistered(player.getUUID(), event.getChannel());
    }

    /**
     * Player left: interrupt their sync task and drop their state.
     *
     * @param event the quit event
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        handshaken.remove(player.getUUID());
        platform.onPlayerDisconnect(player.getUUID());
        PlayerJoinHandler.onPlayerLeave(player);
    }
}
