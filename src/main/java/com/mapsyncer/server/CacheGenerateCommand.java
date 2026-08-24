package com.mapsyncer.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.server.ConversionOrchestrator.DimensionCacheStats;
import com.mapsyncer.server.ConversionOrchestrator.SingleRegionResult;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.MapSyncerExecutors;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.resources.ResourceKey;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The {@code /mapsyncer} command: cache generation and status.
 *
 * Subcommands:
 * - /mapsyncer help - show help
 * - /mapsyncer generate - build the map cache for every dimension
 * - /mapsyncer generate <dimension> - build the cache for one dimension
 * - /mapsyncer generate <dimension> <x> <z> - build the cache for one region
 * - /mapsyncer generate <dimension> force - rebuild one dimension from scratch
 * - /mapsyncer status - show generation state and cache statistics
 * - /mapsyncer incremental off/tick/scheduled/status - configure incremental updates
 *
 * Admin only: vanilla permission level 4, or the mapsyncer.admin node on Paper.
 */
public class CacheGenerateCommand {

    /**
     * Registers the command.
     *
     * @param dispatcher the Brigadier dispatcher to register on
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mapsyncer")
                .requires(source -> Platform.get().isAdmin(source))
                .executes(CacheGenerateCommand::showHelp)
                .then(Commands.literal("help")
                        .executes(CacheGenerateCommand::showHelp))
                .then(Commands.literal("gui")
                        .executes(CacheGenerateCommand::openGui))
                .then(Commands.literal("generate")
                        .executes(CacheGenerateCommand::generateAll)
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(CacheGenerateCommand::generateDimension)
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(CacheGenerateCommand::generateSingleRegion)))
                                .then(Commands.literal("force")
                                        .executes(CacheGenerateCommand::generateDimensionForce))))
                .then(Commands.literal("status")
                        .executes(CacheGenerateCommand::showStatus))
                .then(Commands.literal("incremental")
                        .then(Commands.literal("run")
                                .executes(CacheGenerateCommand::runIncrementalNow))
                        .then(Commands.literal("off")
                                .executes(CacheGenerateCommand::setIncrementalOff))
                        .then(Commands.literal("tick")
                                .executes(CacheGenerateCommand::setIncrementalTick)
                                .then(Commands.argument("interval", IntegerArgumentType.integer(20, 72000))
                                        .executes(CacheGenerateCommand::setIncrementalTickInterval)))
                        .then(Commands.literal("scheduled")
                                .executes(CacheGenerateCommand::setIncrementalScheduled)
                                .then(Commands.argument("hour", IntegerArgumentType.integer(0, 23))
                                        .executes(CacheGenerateCommand::setScheduledTimeDefaultMinute)
                                        .then(Commands.argument("minute", IntegerArgumentType.integer(0, 59))
                                                .executes(CacheGenerateCommand::setScheduledTime))))));
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> ChatUtils.prefix().append(ChatUtils.header("mapsyncer.help.server.header")), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.gui"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_dim"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_region"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.generate_force"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.status"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_off"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_tick"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_scheduled"), false);
        ctx.getSource().sendSuccess(() -> ChatUtils.desc("mapsyncer.help.server.incremental_run"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int openGui(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!Platform.canSend(player, PacketHandler.OpenGuiPayload.TYPE)) {
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.gui_client_missing"));
            return 0;
        }
        Platform.send(player, new PacketHandler.OpenGuiPayload());
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Builds the map cache for every dimension.
     *
     * @param ctx command context
     * @return the command result
     */
    private static int generateAll(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_full"), false);

        MapSyncerExecutors.submitConversion(() -> {
            ConversionOrchestrator.generateAll(server);
            String dimList = String.join(", ", ConversionOrchestrator.getCompletedDimensions());
            server.execute(() -> ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.full_complete",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount(),
                            ConversionOrchestrator.getCompletedDimensions().size(),
                            dimList), false));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Builds the map cache for one dimension.
     *
     * @param ctx command context
     * @return the command result
     * @throws CommandSyntaxException if the dimension argument cannot be parsed
     */
    private static int generateDimension(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = ctx.getSource().getServer();
        String dimensionId = dimension.identifier().toString();
        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_dim", friendlyName), false);

        MapSyncerExecutors.submitConversion(() -> {
            ConversionOrchestrator.generateDimension(server, dimensionId);
            server.execute(() -> ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.dim_complete",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount(),
                            ConversionOrchestrator.getUpdatedCount()), false));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Rebuilds one dimension's map cache from scratch.
     *
     * Clears that dimension's cache directory, then regenerates every region.
     *
     * @param ctx command context
     * @return the command result
     * @throws CommandSyntaxException if the dimension argument cannot be parsed
     */
    private static int generateDimensionForce(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = ctx.getSource().getServer();
        String dimensionId = dimension.identifier().toString();
        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.generate.start_force", friendlyName), false);

        MapSyncerExecutors.submitConversion(() -> {
            ConversionOrchestrator.generateDimensionForce(server, dimensionId);
            server.execute(() -> ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.generate.force_complete",
                            ConversionOrchestrator.getProcessedCount(),
                            ConversionOrchestrator.getTotalCount(),
                            ConversionOrchestrator.getUpdatedCount()), false));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Builds the map cache for a single region.
     *
     * @param ctx command context
     * @return the command result
     * @throws CommandSyntaxException if an argument cannot be parsed
     */
    private static int generateSingleRegion(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        MinecraftServer server = ctx.getSource().getServer();

        if (ConversionOrchestrator.checkMcaFileExists(server, dimension, x, z) == null) {
            String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
            ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_not_found", x, z, friendlyName));
            return 0;
        }

        String friendlyName = DimensionPathMapping.getInstance().getFriendlyName(dimension);
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.command.generating_region", x, z, friendlyName), false);

        MapSyncerExecutors.submitConversion(() -> {
            SingleRegionResult result = ConversionOrchestrator.generateSingleRegion(server, dimension, x, z);
            server.execute(() -> {
                if (result == SingleRegionResult.SUCCESS) {
                    ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.region_converted"), false);
                } else if (result == SingleRegionResult.CONVERSION_FAILED) {
                    ctx.getSource().sendFailure(ChatUtils.error("mapsyncer.command.region_conversion_failed", x, z));
                }
            });
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Reports generation state, incremental update state and cache statistics.
     *
     * @param ctx command context
     * @return the command result
     */
    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        IncrementalUpdateHandler handler = IncrementalUpdateHandler.getInstance();
        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;

        // Build the combined status line.
        String genStatus;
        String incStatus;

        if (ConversionOrchestrator.isRunning()) {
            genStatus = String.format("Conversion running: %d/%d regions - %s",
                    ConversionOrchestrator.getProcessedCount(),
                    ConversionOrchestrator.getTotalCount(),
                    ConversionOrchestrator.getStatus());
        } else {
            genStatus = "No conversion running";
        }

        if (mode == UpdateMode.DISABLED || !handler.isRunning()) {
            incStatus = "Incremental updates off";
        } else if (mode == UpdateMode.TICK) {
            int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks;
            int remainingTicks = interval - handler.getTickCounter();
            int remainingSeconds = remainingTicks / 20;
            int minutes = remainingSeconds / 60;
            int seconds = remainingSeconds % 60;
            incStatus = String.format("Incremental updates in TICK mode, next in %dm %ds", minutes, seconds);
        } else if (mode == UpdateMode.SCHEDULED) {
            int hour = ModConfig.SERVER.scheduledUpdateHour;
            int minute = ModConfig.SERVER.scheduledUpdateMinute;
            incStatus = String.format("Incremental updates scheduled daily at %02d:%02d", hour, minute);
        } else {
            incStatus = "Incremental updates off";
        }

        // Both parts on one line.
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.combined", genStatus, incStatus), false);

        // Cache statistics: a total line, then one line per dimension.
        List<DimensionCacheStats> cacheStats = ConversionOrchestrator.getCacheStats();
        if (!cacheStats.isEmpty()) {
            int totalDims = cacheStats.size();
            int totalRegions = cacheStats.stream().mapToInt(DimensionCacheStats::regionCount).sum();
            long totalSize = cacheStats.stream().mapToLong(DimensionCacheStats::sizeBytes).sum();
            double totalSizeMB = totalSize / (1024.0 * 1024.0);

            // Total.
            ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.cache_total",
                    totalDims, totalRegions, totalSizeMB), false);

            // One line per dimension.
            for (DimensionCacheStats stat : cacheStats) {
                ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.status.cache_dim",
                        stat.dimension(), stat.regionCount(), stat.sizeMB()), false);
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int runIncrementalNow(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> ChatUtils.message("mapsyncer.command.incremental_run_start"), false);

        MapSyncerExecutors.submitConversion(() -> {
            ConversionOrchestrator.performIncrementalScan(server);
            server.execute(() -> ctx.getSource().sendSuccess(() ->
                    ChatUtils.success("mapsyncer.command.incremental_run_complete"), false));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Turns incremental updates off.
     *
     * @param ctx command context
     * @return the command result
     */
    private static int setIncrementalOff(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.DISABLED;
        saveConfig();
        IncrementalUpdateHandler.getInstance().stop();
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_disabled"), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Switches incremental updates to TICK mode.
     *
     * Uses the interval from the config.
     *
     * @param ctx command context
     * @return the command result
     */
    private static int setIncrementalTick(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.TICK;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks;
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_set", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Switches incremental updates to TICK mode with a given interval.
     *
     * @param ctx command context
     * @return the command result
     */
    private static int setIncrementalTickInterval(CommandContext<CommandSourceStack> ctx) {
        int interval = IntegerArgumentType.getInteger(ctx, "interval");
        ModConfig.SERVER.incrementalUpdateIntervalTicks = interval;
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.TICK;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_tick_interval", interval, interval / 20.0f), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Switches incremental updates to SCHEDULED mode.
     *
     * Uses the time from the config.
     *
     * @param ctx command context
     * @return the command result
     */
    private static int setIncrementalScheduled(CommandContext<CommandSourceStack> ctx) {
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.SCHEDULED;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int hour = ModConfig.SERVER.scheduledUpdateHour;
        int minute = ModConfig.SERVER.scheduledUpdateMinute;
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Switches to SCHEDULED mode at a given hour, using the configured minute.
     *
     * @param ctx command context
     * @return the command result
     */
    private static int setScheduledTimeDefaultMinute(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        ModConfig.SERVER.scheduledUpdateHour = hour;
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.SCHEDULED;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        int minute = ModConfig.SERVER.scheduledUpdateMinute;
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Switches to SCHEDULED mode at a given hour and minute.
     *
     * @param ctx command context
     * @return the command result
     */
    private static int setScheduledTime(CommandContext<CommandSourceStack> ctx) {
        int hour = IntegerArgumentType.getInteger(ctx, "hour");
        int minute = IntegerArgumentType.getInteger(ctx, "minute");
        ModConfig.SERVER.scheduledUpdateHour = hour;
        ModConfig.SERVER.scheduledUpdateMinute = minute;
        ModConfig.SERVER.incrementalUpdateMode = UpdateMode.SCHEDULED;
        saveConfig();
        IncrementalUpdateHandler.getInstance().start(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> ChatUtils.success("mapsyncer.command.incremental_scheduled_set", hour, minute), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Writes the config back to disk.
     */
    private static void saveConfig() {
        ModConfig.save();
    }
}
