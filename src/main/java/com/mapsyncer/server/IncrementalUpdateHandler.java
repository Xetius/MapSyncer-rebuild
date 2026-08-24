package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.config.ModConfig.UpdateMode;
import com.mapsyncer.util.MapSyncerExecutors;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rescans and regenerates map regions that have changed since the last pass.
 *
 * Two modes:
 * - TICK: run a scan every N ticks
 * - SCHEDULED: run a scan once a day at a set time
 *
 * Which regions need regenerating is decided from {@code .mca} file timestamps, so only
 * changed regions are converted.
 */
public class IncrementalUpdateHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(IncrementalUpdateHandler.class);

    /** The single instance. */
    private static volatile IncrementalUpdateHandler instance;

    /** The running server. */
    private volatile MinecraftServer server;

    /** Whether scans are currently scheduled. */
    private volatile boolean running = false;

    /** Tick counter used by TICK mode. */
    private final AtomicInteger tickCounter = new AtomicInteger(0);

    /** When the last scheduled run happened, so a day cannot fire twice. */
    private volatile LocalDateTime lastScheduledUpdate = null;

    /**
     * @return the shared instance
     */
    public static IncrementalUpdateHandler getInstance() {
        if (instance == null) {
            synchronized (IncrementalUpdateHandler.class) {
                if (instance == null) {
                    instance = new IncrementalUpdateHandler();
                }
            }
        }
        return instance;
    }

    /**
     * Starts scheduling incremental scans.
     *
     * @param server the running server
     */
    public void start(MinecraftServer server) {
        if (running) {
            LOGGER.warn("Incremental update handler already running");
            return;
        }
        this.server = server;
        this.running = true;
        this.tickCounter.set(0);
        this.lastScheduledUpdate = null;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;
        if (mode == UpdateMode.TICK) {
            LOGGER.info("Incremental update handler started (TICK mode, interval: {} ticks = {} seconds)",
                ModConfig.SERVER.incrementalUpdateIntervalTicks,
                ModConfig.SERVER.incrementalUpdateIntervalTicks / 20);
        } else if (mode == UpdateMode.SCHEDULED) {
            LOGGER.info("Incremental update handler started (SCHEDULED mode, daily at {}:{})",
                ModConfig.SERVER.scheduledUpdateHour,
                ModConfig.SERVER.scheduledUpdateMinute);
        }
    }

    /**
     * Stops scheduling incremental scans.
     */
    public void stop() {
        running = false;
        server = null;
        tickCounter.set(0);
        lastScheduledUpdate = null;
        LOGGER.info("Incremental update handler stopped");
    }

    /**
     * @return whether scans are currently scheduled
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * @return the current tick counter
     */
    public int getTickCounter() {
        return tickCounter.get();
    }

    /**
     * Server tick: checks whether the configured mode is due for a scan.
     *
     * @param server the server
     */
    public static void onServerTick(MinecraftServer server) {
        IncrementalUpdateHandler handler = getInstance();
        if (!handler.running || handler.server == null) return;

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;
        if (mode == UpdateMode.DISABLED) return;

        switch (mode) {
            case TICK:
                handler.checkTickMode();
                break;
            case SCHEDULED:
                handler.checkScheduledMode();
                break;
            case DISABLED:
                // Do nothing
                break;
        }
    }

    /**
     * Whether TICK mode is due for a scan.
     */
    private void checkTickMode() {
        int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks;
        int currentTick = tickCounter.incrementAndGet();

        if (currentTick >= interval) {
            tickCounter.set(0);
            performScheduledUpdate("TICK mode interval");
        }
    }

    /**
     * Whether SCHEDULED mode is due for a scan.
     *
     * Fires inside a one-minute window around the configured time, at most once a day.
     */
    private void checkScheduledMode() {
        LocalDateTime now = LocalDateTime.now();
        int targetHour = ModConfig.SERVER.scheduledUpdateHour;
        int targetMinute = ModConfig.SERVER.scheduledUpdateMinute;
        LocalTime targetTime = LocalTime.of(targetHour, targetMinute);
        LocalTime currentTime = now.toLocalTime();

        // Check if we've reached the scheduled time (within 1 minute window)
        // and haven't already updated today
        if (currentTime.isAfter(targetTime) && currentTime.isBefore(targetTime.plusMinutes(1))) {
            if (lastScheduledUpdate == null || !lastScheduledUpdate.toLocalDate().equals(now.toLocalDate())) {
                lastScheduledUpdate = now;
                performScheduledUpdate("SCHEDULED mode daily update at " + targetHour + ":" + targetMinute);
            }
        }
    }

    /**
     * Runs one incremental scan.
     *
     * @param reason what triggered it, for the log
     */
    private void performScheduledUpdate(String reason) {
        LOGGER.info("Queueing incremental update: {}", reason);

        MapSyncerExecutors.submitConversion(() -> {
            try {
                ConversionOrchestrator.performIncrementalScan(server);
            } catch (Exception e) {
                LOGGER.error("Error during scheduled incremental update", e);
            }

            server.execute(() -> {
                if (server.getPlayerList().getPlayerCount() == 0) {
                    LOGGER.info("No players online after incremental update, stopping handler to save resources");
                    stop();
                }
            });
        });
    }

    /**
     * Current state and when the next scan is expected, as shown by {@code /mapsyncer status}.
     *
     * @return a human-readable status line
     */
    public String getStatusInfo() {
        if (!running) {
            return "Stopped";
        }

        UpdateMode mode = ModConfig.SERVER.incrementalUpdateMode;
        switch (mode) {
            case DISABLED:
                return "Running but disabled";
            case TICK:
                int interval = ModConfig.SERVER.incrementalUpdateIntervalTicks;
                int remaining = interval - tickCounter.get();
                return String.format("TICK mode: next update in %d ticks (%.1f seconds)",
                    remaining, remaining / 20.0f);
            case SCHEDULED:
                int targetHour = ModConfig.SERVER.scheduledUpdateHour;
                int targetMinute = ModConfig.SERVER.scheduledUpdateMinute;
                LocalDateTime now = LocalDateTime.now();
                LocalTime targetTime = LocalTime.of(targetHour, targetMinute);
                LocalDateTime nextUpdate = now.toLocalDate().atTime(targetTime);
                if (now.toLocalTime().isAfter(targetTime)) {
                    nextUpdate = nextUpdate.plusDays(1);
                }
                long secondsUntil = java.time.Duration.between(now, nextUpdate).getSeconds();
                return String.format("SCHEDULED mode: next update at %02d:%02d (in %dh %dm)",
                    targetHour, targetMinute, secondsUntil / 3600, (secondsUntil % 3600) / 60);
            default:
                return "Unknown mode";
        }
    }

    /**
     * Drops the instance so a server restart does not keep the old one alive.
     */
    public static void resetInstance() {
        if (instance != null) {
            instance.stop();
            instance = null;
            LOGGER.info("IncrementalUpdateHandler instance reset");
        }
    }
}
