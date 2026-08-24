package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mapsyncer.network.ClientMeta;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * The client-side {@code /mapsyncer} command.
 * Registers the command players use to pull map data from the server.
 *
 * <p>Subcommands:</p>
 * <ul>
 *   <li>/mapsyncer - show help</li>
 *   <li>/mapsyncer help - show help</li>
 *   <li>/mapsyncer sync - sync the current dimension</li>
 *   <li>/mapsyncer sync all - sync every dimension</li>
 *   <li>/mapsyncer sync &lt;dimension&gt; - sync one dimension</li>
 * </ul>
 *
 * <p>The dimension argument accepts:</p>
 * <ul>
 *   <li>vanilla dimensions: overworld, the_nether, the_end</li>
 *   <li>modded dimensions: the full ID, e.g. twilightforest:twilight_forest</li>
 * </ul>
 */
public class MapSyncerCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapSyncerCommand.class);

    /**
     * Registers the client command.
     * Builds the /mapsyncer command tree with Brigadier.
     *
     * @param dispatcher the command dispatcher
     */
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                literal("mapsyncer")
                        .executes(MapSyncerCommand::showHelp)
                        .then(literal("help")
                                .executes(MapSyncerCommand::showHelp))
                        .then(literal("sync")
                                .executes(MapSyncerCommand::executeSyncCurrentDim)
                                .then(literal("radius")
                                        .then(argument("blocks", IntegerArgumentType.integer(1))
                                                .executes(MapSyncerCommand::executeSyncRadius)))
                                .then(literal("all")
                                        .executes(MapSyncerCommand::executeSyncAll))
                                .then(argument("dimension", StringArgumentType.greedyString())
                                        .suggests(MapSyncerCommand::suggestDimensions)
                                        .executes(MapSyncerCommand::executeSyncDimension)))
                        .then(literal("gui")
                                .executes(MapSyncerCommand::openGui))
                        .then(literal("clearstate")
                                .requires(source -> false)
                                .executes(MapSyncerCommand::clearSyncState))
                        // Anything not handled above belongs to the server: generate,
                        // status, incremental. Brigadier matches literals before
                        // arguments, so this only catches what is left over.
                        .then(argument("serverCommand", StringArgumentType.greedyString())
                                .executes(MapSyncerCommand::forwardToServer))
        );
        dispatcher.register(
                literal("mapsyncergui")
                        .executes(MapSyncerCommand::openGui)
        );
    }

    private static int openGui(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = context.getSource().getClient();
        mc.execute(() -> mc.setScreenAndShow(new MapSyncerScreen()));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Sends a subcommand this client does not handle on to the server.
     *
     * <p>The admin subcommands — {@code generate}, {@code status}, {@code incremental} —
     * live on the server, but this mod registers {@code /mapsyncer} on the client too.
     * Fabric only passes a command to the server when its own dispatcher does not know the
     * command name at all; a name it knows with an argument it does not produces
     * "Incorrect argument for command" locally, and the server never sees it. Forwarding
     * explicitly is what the admin GUI already does for its own buttons.</p>
     *
     * @param context command context
     * @return the command result
     */
    private static int forwardToServer(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = context.getSource().getClient();
        if (mc.player == null || mc.player.connection == null) {
            return 0;
        }
        // getInput() is the command as typed, without the leading slash, which is the
        // form sendCommand expects.
        mc.player.connection.sendCommand(context.getInput());
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Prints the command help.
     *
     * @param context command context
     * @return the command result
     */
    private static int showHelp(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        // The client-side sync commands.
        mc.player.sendSystemMessage(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.command.help_header")));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_sync"));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_sync_radius"));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_sync_dim"));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_sync_all"));
        mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.command.help_gui"));
        mc.player.sendSystemMessage(ChatUtils.header("mapsyncer.command.help_dimension_note"));

        // Ops also get the server-side commands.
        if (net.minecraft.commands.Commands.LEVEL_OWNERS.check(context.getSource().getPlayer().permissions())) {
            mc.player.sendSystemMessage(ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.generate"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.generate_dim"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.generate_region"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.generate_force"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.status"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.incremental_off"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.incremental_tick"));
            mc.player.sendSystemMessage(ChatUtils.desc("mapsyncer.help.server.incremental_scheduled"));
        }

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Suggests dimension names.
     * Covers vanilla dimensions, modded ones, and any Xaero directory already present.
     *
     * @param context command context
     * @param builder the suggestion builder
     * @return the suggestions
     */
    private static CompletableFuture<Suggestions> suggestDimensions(CommandContext<FabricClientCommandSource> context, SuggestionsBuilder builder) {
        builder.suggest("overworld");
        builder.suggest("the_nether");
        builder.suggest("the_end");
        builder.suggest("all");

        Set<String> added = new HashSet<>();

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null) {
            ResourceKey<Level> currentDim = level.dimension();
            Identifier currentLoc = currentDim.identifier();
            if (!"minecraft".equals(currentLoc.getNamespace())) {
                String suggestion = currentLoc.toString();
                builder.suggest(suggestion);
                added.add(suggestion);
            }

            level.registryAccess().lookup(Registries.DIMENSION_TYPE).ifPresent(registry -> {
                for (var key : registry.registryKeySet()) {
                    Identifier loc = key.identifier();
                    String namespace = loc.getNamespace();
                    if ("minecraft".equals(namespace)) continue;

                    String path = loc.getPath();
                    String dimPath = path.endsWith("_type") ? path.substring(0, path.length() - 5) : path;
                    String suggestion = namespace + ":" + dimPath;
                    if (!added.contains(suggestion)) {
                        builder.suggest(suggestion);
                        added.add(suggestion);
                    }
                }
            });

            level.registryAccess().lookup(Registries.LEVEL_STEM).ifPresent(registry -> {
                for (var key : registry.registryKeySet()) {
                    Identifier loc = key.identifier();
                    String namespace = loc.getNamespace();
                    if ("minecraft".equals(namespace)) continue;
                    String suggestion = loc.toString();
                    if (!added.contains(suggestion)) {
                        builder.suggest(suggestion);
                        added.add(suggestion);
                    }
                }
            });
        }

        Path baseDir = XaeroMapIntegrator.getCurrentServerBaseDirectory();
        if (baseDir != null) {
            try (Stream<Path> dirs = Files.list(baseDir)) {
                dirs.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("mw$"))
                    .forEach(p -> {
                        String dirName = p.getFileName().toString();
                        String suggestion = xaeroDirToDimensionId(dirName);
                        if (suggestion != null && !suggestion.isEmpty() && !added.contains(suggestion)) {
                            builder.suggest(suggestion);
                            added.add(suggestion);
                        }
                    });
            } catch (IOException e) {
                LOGGER.debug("Failed to scan Xaero directory", e);
            }
        }

        return builder.buildFuture();
    }

    /**
     * Converts an Xaero directory name back to a dimension ID.
     * Handles both vanilla and modded dimensions.
     *
     * @param dirName the Xaero directory name
     * @return the dimension ID, or an empty string if it cannot be converted
     */
    private static String xaeroDirToDimensionId(String dirName) {
        if ("null".equals(dirName)) return "overworld";
        if ("DIM-1".equals(dirName)) return "the_nether";
        if ("DIM1".equals(dirName)) return "the_end";
        if (dirName.contains("$")) return dirName.replace('$', ':');
        if (dirName.startsWith("DIM")) return "";
        return dirName;
    }

    /**
     * Syncs one dimension, named as an argument.
     * Accepts both short names and full IDs.
     *
     * @param context command context
     * @return the command result
     */
    private static int executeSyncDimension(CommandContext<FabricClientCommandSource> context) {
        String dimInput = StringArgumentType.getString(context, "dimension");

        if ("all".equalsIgnoreCase(dimInput)) {
            return executeSyncAll(context);
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        String dimensionId = resolveDimensionId(dimInput, mc.level);

        sendSyncRequest(mc, dimensionId, false);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Syncs every dimension.
     * Asks the server for the map data of all of them.
     *
     * @param context command context
     * @return the command result
     */
    private static int executeSyncAll(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;

        sendSyncRequest(mc, "all", true);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Syncs the current dimension.
     * Works out which dimension the player is in and asks for that one.
     *
     * @param context command context
     * @return the command result
     */
    private static int executeSyncCurrentDim(CommandContext<FabricClientCommandSource> context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return 0;

        String dimensionId = currentDimensionId(mc);

        sendSyncRequest(mc, dimensionId, false);

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Clears the stored sync state.
     * Used to dismiss the prompt about resuming an interrupted sync.
     *
     * @param context command context
     * @return the command result
     */
    private static int clearSyncState(CommandContext<FabricClientCommandSource> context) {
        ClientJoinHandler.clearSyncState();
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Resolves a dimension name the player typed into a full dimension ID.
     * Accepts short names such as overworld and full IDs such as minecraft:overworld.
     *
     * @param input what the player typed
     * @param level the client's world
     * @return the full dimension ID
     */
    public static String currentDimensionId(Minecraft mc) {
        if (mc.level == null) {
            return "minecraft:overworld";
        }
        ResourceKey<Level> currentDim = mc.level.dimension();
        return currentDim.identifier().toString();
    }

    private static int executeSyncRadius(CommandContext<FabricClientCommandSource> context) {
        int radiusBlocks = IntegerArgumentType.getInteger(context, "blocks");
        sendRadiusSyncRequest(Minecraft.getInstance(), radiusBlocks);
        return Command.SINGLE_SUCCESS;
    }

    static String resolveDimensionId(String input, ClientLevel level) {
        switch (input.toLowerCase()) {
            case "overworld": return "minecraft:overworld";
            case "nether": case "the_nether": return "minecraft:the_nether";
            case "end": case "the_end": return "minecraft:the_end";
        }

        if (input.contains(":")) return input;

        var optRegistry = level.registryAccess().lookup(Registries.DIMENSION_TYPE);
        if (optRegistry.isPresent()) {
            var registry = optRegistry.get();
            for (var key : registry.registryKeySet()) {
                Identifier loc = key.identifier();
                if ("minecraft".equals(loc.getNamespace())) continue;
                String path = loc.getPath();
                String dimPath = path.endsWith("_type") ? path.substring(0, path.length() - 5) : path;
                if (dimPath.equals(input) || path.equals(input)) {
                    return loc.getNamespace() + ":" + dimPath;
                }
            }
        }

        return "minecraft:" + input;
    }


    /**
     * Sends a sync request to the server.
     * Hashes the client's regions, builds the request and sends it.
     *
     * @param mc the Minecraft client
     * @param dimensionId the dimension ID, or "all" when syncing everything
     * @param syncAll whether every dimension is being synced
     */
    static void sendSyncRequest(Minecraft mc, String dimensionId, boolean syncAll) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        String xaeroDim = syncAll ? null : dimMapping.toXaeroDimension(dimensionId);

        CompletableFuture.supplyAsync(() -> prepareSyncRequest(serverDir, dimensionId, xaeroDim, syncAll))
                .thenAccept(prepared -> mc.execute(() -> {
                    if (mc.player == null) {
                        return;
                    }

                    LOGGER.info("Sending sync request with {} entries (serverDir={})",
                            prepared.metaMap().size(), serverDir);
                    ClientPlayNetworking.send(new PacketHandler.SyncRequestPayload(prepared.metaMap()));
                    SyncProgressTracker.startTracking();
                }))
                .exceptionally(error -> {
                    LOGGER.error("Failed to prepare sync request", error);
                    return null;
                });
    }

    static void sendRadiusSyncRequest(Minecraft mc, int radiusBlocks) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!ClientPlayNetworking.canSend(PacketHandler.RadiusSyncRequestPayload.TYPE)) {
            mc.player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.radius_unsupported"));
            return;
        }

        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        String dimensionId = currentDimensionId(mc);
        String xaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(dimensionId);
        int playerX = mc.player.getBlockX();
        int playerY = mc.player.getBlockY();
        int playerZ = mc.player.getBlockZ();

        CompletableFuture.supplyAsync(() -> prepareSyncRequest(serverDir, dimensionId, xaeroDim, false))
                .thenAccept(prepared -> mc.execute(() -> {
                    if (mc.player == null) {
                        return;
                    }
                    ClientPlayNetworking.send(new PacketHandler.RadiusSyncRequestPayload(
                            prepared.metaMap(), dimensionId, radiusBlocks, playerX, playerY, playerZ));
                    SyncProgressTracker.startTracking();
                }))
                .exceptionally(error -> {
                    LOGGER.error("Failed to prepare radius sync request", error);
                    return null;
                });
    }

    private static PreparedSyncRequest prepareSyncRequest(Path serverDir, String dimensionId,
                                                          String xaeroDim, boolean syncAll) {
        Map<String, ClientMeta> metaMap;

        // Send the request first and wait for the server to confirm it has data;
        // chunk updates are only paused once status="ok" comes back.

        ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir) : null;

        if (syncAll) {
            if (serverDir != null && tsCache != null && tsCache.cacheFileExists()) {
                metaMap = ClientHashManager.computeMetaForSync(serverDir);
                LOGGER.info("Sync all: {} cached entries", metaMap.size());
            } else {
                metaMap = new java.util.HashMap<>();
                LOGGER.info("First sync all, sending empty request");
            }
        } else {
            if (tsCache != null && tsCache.cacheFileExists() && tsCache.hasDimensionSynced(xaeroDim)) {
                Path dimDir = serverDir.resolve(xaeroDim);
                Path mwDir = findMwDir(dimDir);
                if (mwDir != null) {
                    metaMap = ClientHashManager.computeMetaForSync(mwDir);
                    LOGGER.info("Dimension {} previously synced, {} entries", dimensionId, metaMap.size());
                } else {
                    metaMap = new java.util.HashMap<>();
                    metaMap.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                    LOGGER.warn("Dimension {} has cache but no mw$ dir", dimensionId);
                }
            } else {
                metaMap = new java.util.HashMap<>();
                metaMap.put(xaeroDim + "/_placeholder_", new ClientMeta(0, "00000000"));
                LOGGER.info("First sync for {}", dimensionId);
            }
        }

        // Mark the sync as started, so an interrupted one can be resumed.
        if (tsCache != null) {
            Set<String> dimensions = new HashSet<>();
            if (syncAll) {
                dimensions.add("all");
            } else {
                dimensions.add(xaeroDim);
            }
            String command = syncAll ? "/mapsyncer sync all" : "/mapsyncer sync " + dimensionId;
            tsCache.markSyncStart(dimensions, command);
        }

        return new PreparedSyncRequest(metaMap);
    }

    private record PreparedSyncRequest(Map<String, ClientMeta> metaMap) {
    }

    /**
     * Finds the mw$worldId directory inside a dimension directory.
     * That is where Xaero keeps its map data.
     *
     * @param dimDir the dimension directory
     * @return the mw$ directory, or {@code null} if there is none
     */
    private static Path findMwDir(Path dimDir) {
        if (dimDir == null || !dimDir.toFile().exists()) return null;
        Path defaultMwDir = dimDir.resolve(XaeroMapIntegrator.DEFAULT_MW_DIR_NAME);
        if (Files.isDirectory(defaultMwDir)) {
            return defaultMwDir;
        }
        try {
            return Files.list(dimDir)
                    .filter(p -> XaeroMapIntegrator.DEFAULT_MW_DIR_NAME.equals(p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
