package com.mapsyncer.client;

import com.mapsyncer.util.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks a sync in progress and reports it to the player.
 * Covers how far along it is, how long it has taken, and whether it finished.
 *
 * <p>What it does:</p>
 * <ul>
 *   <li>tracks the start, the progress and the end of a sync</li>
 *   <li>reports the percentage as it goes</li>
 *   <li>times the sync</li>
 *   <li>notices when the server does not respond</li>
 * </ul>
 *
 * <p>What the player sees:</p>
 * <ul>
 *   <li>a progress line as the percentage moves</li>
 *   <li>the total time and region count when it finishes</li>
 * </ul>
 */
public class SyncProgressTracker {

    /** Whether a sync is being tracked. */
    private static volatile boolean tracking = false;

    /** Regions handled so far. */
    private static volatile int processed = 0;

    /** Regions in total. */
    private static volatile int total = 0;

    /** What the sync is doing right now. */
    private static volatile String status = "";

    /** When the sync started. */
    private static volatile long startTime = 0;

    /** The last percentage shown, so the same one is not repeated. */
    private static volatile int lastDisplayedPercent = -1;
    private static volatile long completedAt = 0;

    /** Whether the server has responded at all yet. */
    private static volatile boolean receivedFirstResponse = false;

    /** How long to wait for the server's first response: 5 seconds. */
    private static final long SERVER_RESPONSE_TIMEOUT_MS = 5000;

    /** The timeout checker; one shared executor rather than a new one per sync. */
    private static volatile ScheduledExecutorService timeoutChecker = null;

    /** The current timeout task, so it can be cancelled. */
    private static volatile java.util.concurrent.ScheduledFuture<?> timeoutFuture = null;

    /**
     * Starts tracking a sync.
     * Resets the counters, tells the player, and arms the timeout check.
     */
    public static void startTracking() {
        tracking = true;
        processed = 0;
        total = 0;
        status = Component.translatable("mapsyncer.sync.waiting").getString();
        startTime = System.currentTimeMillis();
        completedAt = 0;
        lastDisplayedPercent = -1;
        receivedFirstResponse = false;

        startTimeoutChecker();
    }

    /**
     * Arms the timeout check.
     * If the server has not responded within 5 seconds, says so — the message depends on
     * whether the server is known to have MapSyncer installed.
     */
    private static void startTimeoutChecker() {
        // Cancel any previous timeout task.
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
        }

        // The shared executor, created on first use.
        if (timeoutChecker == null || timeoutChecker.isShutdown()) {
            timeoutChecker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mapsyncer-sync-progress-timer");
                t.setDaemon(true);
                return t;
            });
        }

        timeoutFuture = timeoutChecker.schedule(() -> {
            if (tracking && !receivedFirstResponse) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    // Two different problems, two different messages.
                    if (MapPacketReceiver.isServerInstalled()) {
                        // Server has MapSyncer but has not replied: a network or server-side problem.
                        mc.player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.timeout"));
                    } else {
                        // Server does not have MapSyncer at all.
                        mc.player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.server_not_installed"));
                    }
                }
                cancelTracking();
            }
        }, SERVER_RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Records progress.
     * Called with each progress update the server sends.
     *
     * @param processed regions handled so far
     * @param total regions in total
     * @param status what the sync is doing right now
     */
    public static void update(int processed, int total, String status) {
        if (!receivedFirstResponse) {
            receivedFirstResponse = true;
            stopTimeoutChecker();
        }

        SyncProgressTracker.processed = processed;
        SyncProgressTracker.total = total;
        SyncProgressTracker.status = status;

        // Every update is shown.
        if (total > 0) {
            int percent = (processed * 100) / total;
            int interval = ClientConfig.VALUES.syncProgressChatIntervalPercent;
            if (interval > 0 && (lastDisplayedPercent < 0 || percent >= lastDisplayedPercent + interval || percent >= 100)) {
                lastDisplayedPercent = percent;
                displayProgress();
            }
        }
    }

    /**
     * Marks the sync finished.
     * Reports the region count and how long it took.
     */
    public static void complete() {
        completeWithCount(total);
    }

    /**
     * Marks the sync finished, with an explicit region count.
     * Used on the final response, so the count is what was actually received.
     *
     * @param count regions actually received
     */
    public static void completeWithCount(int count) {
        tracking = false;
        completedAt = System.currentTimeMillis();
        status = Component.translatable("mapsyncer.sync.completed", count, getElapsedSeconds()).getString();
        stopTimeoutChecker();

        long elapsed = getElapsedSeconds();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(ChatUtils.success("mapsyncer.sync.completed", count, elapsed));
        }
    }

    /**
     * Stops tracking.
     * Called when a sync is interrupted or cancelled.
     */
    public static void cancelTracking() {
        tracking = false;
        completedAt = 0;
        status = Component.translatable("mapsyncer.sync.cancelled").getString();
        stopTimeoutChecker();
    }

    /**
     * Disarms the timeout check.
     * Cancels the current task but keeps the executor for next time.
     */
    private static void stopTimeoutChecker() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    /**
     * Shuts the executor down, when the client is closing.
     * Releases the thread entirely.
     */
    public static void shutdown() {
        stopTimeoutChecker();
        if (timeoutChecker != null && !timeoutChecker.isShutdown()) {
            timeoutChecker.shutdown();
            timeoutChecker = null;
        }
    }

    /**
     * Shows the current progress.
     * Prints the percentage into the player's chat.
     */
    private static void displayProgress() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && tracking) {
            if (total > 0) {
                mc.player.sendSystemMessage(ChatUtils.message("mapsyncer.sync.progress", processed, total, lastDisplayedPercent));
            } else {
                mc.player.sendSystemMessage(ChatUtils.prefix().append(Component.literal(status)));
            }
        }
    }

    /**
     * Whether a sync is being tracked.
     *
     * @return {@code true} while one is in progress
     */
    public static boolean isTracking() {
        return tracking;
    }

    public static int getProcessed() {
        return processed;
    }

    public static int getTotal() {
        return total;
    }

    public static String getStatus() {
        return status;
    }

    public static int getPercent() {
        return total > 0 ? Math.min(100, (processed * 100) / total) : 0;
    }

    public static long getCompletedAt() {
        return completedAt;
    }

    /**
     * How long the sync has been running.
     * Measured from when tracking started.
     *
     * @return the elapsed time in seconds
     */
    public static long getElapsedSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}
