package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig.RadiusSyncCenterMode;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.ClientMeta;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.platform.MapSyncerPlatform;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.server.GenerationCache.RegionMeta;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.MapSyncerExecutors;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Serves map data to clients that ask for it.
 *
 * What it does:
 * - receives a sync request carrying the client's per-region timestamps and hashes
 * - compares that against the server cache to work out what the client is missing
 * - sends the missing regions in batches
 * - throttles the stream so it does not saturate the connection
 *
 * What gets sent (hash comparison, which also gives resume for free):
 * 1. hashes match: skip, the client already has this exact data
 * 2. hashes differ and the client's copy is older: send it
 * 3. hashes differ but the client's copy is newer: skip, the client is ahead
 * 4. the client has no metadata for the region: send it, it is new to them
 *
 * Resuming after a disconnect:
 * - falls out of the hash comparison; the client records what it received in
 *   sync_timestamps.cache and reports those hashes when it reconnects
 * - the server keeps no progress index of its own, which keeps this simple and leak-free
 */
public class ServerSyncHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandler.class);

    /** Hard ceiling on payload size (1MB), the vanilla custom payload limit. */
    private static final int MAX_PACKET_SIZE_LIMIT = 1_000_000;

    /**
     * Largest payload to send, which is what regions get split against.
     * The configured value, capped by both the vanilla limit and the platform's.
     *
     * @return the maximum payload size in bytes
     */
    private static int getMaxPacketSize() {
        int configValue = ModConfig.SERVER.maxSyncPacketSize;
        int platformLimit = Platform.get().maxPayloadBytes();
        return Math.min(configValue, Math.min(MAX_PACKET_SIZE_LIMIT, platformLimit));
    }

    /**
     * How many bytes to accumulate before sending, i.e. the target bytes per second.
     * Under a speed limit this rounds down to a whole number of full payloads, so each
     * second carries whole payloads. Unthrottled it is just the maximum payload size.
     *
     * @return the batch threshold in bytes
     */
    private static int getBatchThreshold(ServerPlayer player, UUID playerId) {
        int limitKBps = getEffectiveLimitKBps(player, playerId);
        if (limitKBps <= 0) {
            // No limit: one full payload.
            return getMaxPacketSize();
        }

        // Limited: round down to whole payloads.
        int maxPacketSize = getMaxPacketSize();
        int limitBytesPerSec = limitKBps * 1024;

        // Whole payloads per second, rounded down.
        int packetsPerSecond = limitBytesPerSec / maxPacketSize;

        // Always allow at least one, or nothing would ever be sent.
        if (packetsPerSecond < 1) {
            packetsPerSecond = 1;
        }

        // Effective rate: whole payloads times payload size.
        int actualThreshold = packetsPerSecond * maxPacketSize;

        LOGGER.debug("Speed limit adjusted: {} KB/s -> {} packets/s x {} KB = {} KB/s",
                limitKBps, packetsPerSecond, maxPacketSize / 1024, actualThreshold / 1024);

        return actualThreshold;
    }

    /**
     * Sends a batch, splitting it across payloads if it exceeds the size limit.
     *
     * @param batch the regions to send
     * @param batchBytes total size of the batch
     * @param serverPlayer the recipient
     * @param worldId the world ID
     * @param processed regions handled so far
     * @param total regions in total
     * @return how many payloads were sent
     */
    private static int sendBatchInChunks(List<ChunkMapData> batch, int batchBytes,
            ServerPlayer serverPlayer, int worldId, int processed, int total) {
        int maxPacketSize = getMaxPacketSize();

        if (batchBytes <= maxPacketSize) {
            // Fits in one payload.
            final List<ChunkMapData> batchToSend = new ArrayList<>(batch);
            serverPlayer.level().getServer().execute(() -> {
                Platform.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(batchToSend, false, worldId, "ok"));
                Platform.send(serverPlayer,
                        new PacketHandler.SyncProgressPayload(processed, total,
                                String.format("Sending regions %d/%d", processed, total)));
            });
            return 1;
        }

        // Otherwise split it up.
        List<ChunkMapData> currentChunk = new ArrayList<>();
        int currentSize = 0;
        int packetCount = 0;

        for (ChunkMapData chunk : batch) {
            // Adding this region would overflow the payload, so flush what we have.
            if (currentSize + chunk.data.length > maxPacketSize && !currentChunk.isEmpty()) {
                final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
                final int sentProgress = processed + packetCount;
                serverPlayer.level().getServer().execute(() -> {
                    Platform.send(serverPlayer,
                            new PacketHandler.SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                    Platform.send(serverPlayer,
                            new PacketHandler.SyncProgressPayload(sentProgress, total,
                                    String.format("Sending regions %d/%d", sentProgress, total)));
                });
                packetCount++;

                currentChunk.clear();
                currentSize = 0;
            }

            currentChunk.add(chunk);
            currentSize += chunk.data.length;
        }

        // Whatever is left over.
        if (!currentChunk.isEmpty()) {
            final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
            final int sentProgress = processed + packetCount;
            serverPlayer.level().getServer().execute(() -> {
                Platform.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                Platform.send(serverPlayer,
                        new PacketHandler.SyncProgressPayload(sentProgress, total,
                                String.format("Sending regions %d/%d", sentProgress, total)));
            });
            packetCount++;
        }

        return packetCount;
    }

    /**
     * Size of the data carried by one part.
     *
     * <p>Reserves 4KB for the part header: sync ID, path, dimension, coordinates and so on.
     * Where the platform's payload limit is generous (Fabric) parts are at least 64KB, as
     * before; where it is small (Paper's plugin messaging channel) the limit itself wins.</p>
     *
     * @return the per-part data size in bytes
     */
    private static int getPartDataSize() {
        int available = getMaxPacketSize() - 4096;
        if (available >= 64 * 1024) {
            return available;
        }
        return Math.max(4096, available);
    }

    private static boolean sendRegionParts(String syncId, RegionSyncInfo info, ChunkMapData chunk,
            ServerPlayer serverPlayer, int worldId, UUID playerId) {
        int partSize = getPartDataSize();
        int totalParts = Math.max(1, (chunk.data.length + partSize - 1) / partSize);
        long totalBytes = chunk.data.length;
        String hash = HashUtils.computeHash(chunk.data);

        for (int partIndex = 0; partIndex < totalParts; partIndex++) {
            if (Thread.currentThread().isInterrupted() || !isPlayerStillValid(serverPlayer)) {
                return false;
            }

            int offset = partIndex * partSize;
            int end = Math.min(offset + partSize, chunk.data.length);
            byte[] partData = Arrays.copyOfRange(chunk.data, offset, end);
            PacketHandler.SyncRegionPartPayload payload = new PacketHandler.SyncRegionPartPayload(
                    syncId, worldId, info.normalizedPath(), chunk.dimension, chunk.regionX, chunk.regionZ,
                    chunk.caveLayer, partIndex, totalParts, offset, totalBytes, chunk.timestampSeconds,
                    hash, partData);

            serverPlayer.level().getServer().execute(() ->
                    Platform.send(serverPlayer, payload));

            if (!applySpeedLimit(partData.length, serverPlayer, playerId)) {
                return false;
            }
        }

        PacketHandler.SyncRegionCompletePayload completePayload = new PacketHandler.SyncRegionCompletePayload(
                syncId, worldId, info.normalizedPath(), chunk.dimension, chunk.regionX, chunk.regionZ,
                chunk.caveLayer, totalParts, totalBytes, chunk.timestampSeconds, hash);
        serverPlayer.level().getServer().execute(() ->
                Platform.send(serverPlayer, completePayload));
        return true;
    }

    /** Players with a sync in flight, so it can be cut short on disconnect or dimension change. */
    private static final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();

    /** The dimension each player was in when their sync started. */
    private static final Map<UUID, ResourceKey<Level>> playerSyncDimensions = new ConcurrentHashMap<>();

    /** Each player's sync task, so it can be interrupted the moment they disconnect. */
    private static final Map<UUID, Future<?>> syncTasks = new ConcurrentHashMap<>();

    /** Throttling: bytes sent so far this cycle. */
    private static final Map<UUID, Long> speedLimitBytesSent = new ConcurrentHashMap<>();

    /** Throttling: when the current cycle started. */
    private static final Map<UUID, Long> speedLimitCycleStart = new ConcurrentHashMap<>();

    /** Per-player adaptive throttle state. */
    private static final Map<UUID, AdaptiveThrottleState> adaptiveThrottleStates = new ConcurrentHashMap<>();

    /**
     * Metadata chunks received from each player but not yet claimed by a request.
     *
     * <p>A client whose metadata does not fit one packet sends the surplus ahead of the
     * request itself; see {@code PacketHandler.SyncMetaChunkPayload}. Entries live here only
     * between the first chunk and the request that follows, and are dropped when the player
     * disconnects.</p>
     */
    private static final Map<UUID, Map<String, ClientMeta>> pendingClientMeta = new ConcurrentHashMap<>();

    /**
     * Largest number of buffered entries accepted from one player.
     *
     * <p>The chunks arrive before the request that consumes them, so without a ceiling a
     * client could stream them indefinitely and exhaust the server's heap. This is well
     * above any real map: 200k regions is roughly 25 million chunks.</p>
     */
    private static final int MAX_PENDING_CLIENT_META = 200_000;

    /** Longest a throttle cycle may run (1 second), so the running total stays small. */
    private static final long MAX_SPEED_LIMIT_CYCLE_MS = 1000;

    private static final class AdaptiveThrottleState {
        int currentLimitKBps;
        int lastPingMs;
        int stableRecoverSamples;
        long lastAdjustMillis;
        boolean congested;

        AdaptiveThrottleState(int initialLimitKBps) {
            this.currentLimitKBps = initialLimitKBps;
        }
    }

    /**
     * A region queued for syncing, without its data.
     * Holds only the path and metadata, which keeps memory flat while the whole list is
     * collected and sorted; the data itself is read one region at a time as it is sent.
     *
     * @param zipPath path of the cached zip
     * @param normalizedPath the region's path in the form the client uses
     * @param timestampSeconds when the server generated it, in seconds
     */
    private record RegionSyncInfo(Path zipPath, String normalizedPath, long timestampSeconds,
                                   int regionX, int regionZ, String dimension, int caveLayer) {
        /**
         * @return whether this is the surface layer
         */
        boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    private record SyncFilter(String xaeroDimension, int centerX, int centerY, int centerZ,
                              int radiusBlocks, String centerDescription, boolean clamped) {
        boolean includes(RegionSyncInfo info) {
            if (!xaeroDimension.equals(info.dimension())) {
                return false;
            }
            int minX = info.regionX() * 512;
            int maxX = minX + 511;
            int minZ = info.regionZ() * 512;
            int maxZ = minZ + 511;
            int nearestX = Math.max(minX, Math.min(centerX, maxX));
            int nearestZ = Math.max(minZ, Math.min(centerZ, maxZ));
            long dx = (long) nearestX - centerX;
            long dz = (long) nearestZ - centerZ;
            long radius = radiusBlocks;
            return dx * dx + dz * dz <= radius * radius;
        }
    }

    /**
     * Registers every payload type and the handlers for the ones clients send.
     *
     * <p>Which loader this ends up talking to is the platform's business; the payload
     * types and byte layouts are identical either way.</p>
     */
    public static void register() {
        MapSyncerPlatform platform = Platform.get();

        platform.registerClientbound(
                PacketHandler.SyncResponsePayload.TYPE,
                PacketHandler.SyncResponsePayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.SyncProgressPayload.TYPE,
                PacketHandler.SyncProgressPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.SyncRegionPartPayload.TYPE,
                PacketHandler.SyncRegionPartPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.SyncRegionCompletePayload.TYPE,
                PacketHandler.SyncRegionCompletePayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.ServerInstalledPayload.TYPE,
                PacketHandler.ServerInstalledPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.AdminStatusPayload.TYPE,
                PacketHandler.AdminStatusPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.OpenGuiPayload.TYPE,
                PacketHandler.OpenGuiPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.PublicWaypointsPayload.TYPE,
                PacketHandler.PublicWaypointsPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.PublicWaypointAddResultPayload.TYPE,
                PacketHandler.PublicWaypointAddResultPayload.STREAM_CODEC);

        platform.registerServerbound(
                PacketHandler.SyncMetaChunkPayload.TYPE,
                PacketHandler.SyncMetaChunkPayload.STREAM_CODEC,
                ServerSyncHandler::handleSyncMetaChunk);
        platform.registerServerbound(
                PacketHandler.SyncRequestPayload.TYPE,
                PacketHandler.SyncRequestPayload.STREAM_CODEC,
                ServerSyncHandler::handleSyncRequest);
        platform.registerServerbound(
                PacketHandler.RadiusSyncRequestPayload.TYPE,
                PacketHandler.RadiusSyncRequestPayload.STREAM_CODEC,
                ServerSyncHandler::handleRadiusSyncRequest);
        platform.registerServerbound(
                PacketHandler.AdminStatusRequestPayload.TYPE,
                PacketHandler.AdminStatusRequestPayload.STREAM_CODEC,
                (payload, player) -> handleAdminStatusRequest(player));
        platform.registerServerbound(
                PacketHandler.AdminSettingsUpdatePayload.TYPE,
                PacketHandler.AdminSettingsUpdatePayload.STREAM_CODEC,
                ServerSyncHandler::handleAdminSettingsUpdate);
        platform.registerServerbound(
                PacketHandler.PublicWaypointsRequestPayload.TYPE,
                PacketHandler.PublicWaypointsRequestPayload.STREAM_CODEC,
                (payload, player) -> sendPublicWaypoints(player));
        platform.registerServerbound(
                PacketHandler.PublicWaypointAddPayload.TYPE,
                PacketHandler.PublicWaypointAddPayload.STREAM_CODEC,
                ServerSyncHandler::handlePublicWaypointAdd);
    }

    private static void handleAdminStatusRequest(ServerPlayer player) {
        player.level().getServer().execute(() -> sendAdminStatus(player));
    }

    private static void handleAdminSettingsUpdate(PacketHandler.AdminSettingsUpdatePayload payload,
                                                  ServerPlayer player) {
        player.level().getServer().execute(() -> {
            if (!Platform.get().isAdmin(player)) {
                sendAdminStatus(player);
                return;
            }
            ModConfig.SERVER.enableRadiusSync = payload.radiusSyncEnabled();
            ModConfig.SERVER.maxRadiusSyncBlocks = Math.max(1, Math.min(100_000, payload.maxRadiusSyncBlocks()));
            try {
                ModConfig.SERVER.radiusSyncCenterMode = RadiusSyncCenterMode.valueOf(payload.radiusSyncCenterMode());
            } catch (IllegalArgumentException e) {
                ModConfig.SERVER.radiusSyncCenterMode = RadiusSyncCenterMode.PLAYER_POSITION;
            }
            ModConfig.SERVER.radiusSyncFixedDimension = payload.radiusSyncFixedDimension() == null
                    || payload.radiusSyncFixedDimension().isBlank()
                    ? "minecraft:overworld" : payload.radiusSyncFixedDimension();
            ModConfig.SERVER.radiusSyncFixedX = payload.radiusSyncFixedX();
            ModConfig.SERVER.radiusSyncFixedY = payload.radiusSyncFixedY();
            ModConfig.SERVER.radiusSyncFixedZ = payload.radiusSyncFixedZ();
            ModConfig.save();
            sendAdminStatus(player);
        });
    }

    private static void handlePublicWaypointAdd(PacketHandler.PublicWaypointAddPayload payload,
                                                ServerPlayer player) {
        player.level().getServer().execute(() -> {
            if (!Platform.get().isAdmin(player)) {
                Platform.send(player,
                        new PacketHandler.PublicWaypointAddResultPayload("permission_denied", ""));
                player.sendSystemMessage(ChatUtils.error("mapsyncer.waypoints.import.permission_denied"));
                sendAdminStatus(player);
                return;
            }

            try {
                PublicWaypointConfig.AddResult result = PublicWaypointConfig.addOrUpdateFromClient(payload.waypoint());
                String name = payload.waypoint() == null ? "" : payload.waypoint().name();
                if (result == PublicWaypointConfig.AddResult.FAILED) {
                    Platform.send(player,
                            new PacketHandler.PublicWaypointAddResultPayload("failed", name));
                    player.sendSystemMessage(ChatUtils.error("mapsyncer.waypoints.import.failed", name));
                    sendAdminStatus(player);
                    return;
                }

                String status = result == PublicWaypointConfig.AddResult.UPDATED ? "updated" : "added";
                Platform.send(player,
                        new PacketHandler.PublicWaypointAddResultPayload(status, name));
                player.sendSystemMessage(ChatUtils.success("mapsyncer.waypoints.import." + status, name));
                sendAdminStatus(player);
                sendPublicWaypoints(player);
            } catch (Exception e) {
                LOGGER.error("Failed to add public waypoint from {}", player.getUUID(), e);
                String name = payload.waypoint() == null ? "" : payload.waypoint().name();
                Platform.send(player,
                        new PacketHandler.PublicWaypointAddResultPayload("failed", name));
                player.sendSystemMessage(ChatUtils.error("mapsyncer.waypoints.import.failed", name));
                sendAdminStatus(player);
            }
        });
    }

    private static void sendAdminStatus(ServerPlayer player) {
        boolean allowed = Platform.get().isAdmin(player);
        if (!allowed) {
            Platform.send(player, new PacketHandler.AdminStatusPayload(
                    false, false, 0, 0, 0, 0, 0, 0, 0, 0L, 0,
                    false, 0, RadiusSyncCenterMode.PLAYER_POSITION.name(), "minecraft:overworld", 0, 64, 0,
                    false, "", 0, "",
                    "permission_denied", "", ""));
            return;
        }

        List<ConversionOrchestrator.DimensionCacheStats> cacheStats = ConversionOrchestrator.getCacheStats();
        int cacheDimensionCount = cacheStats.size();
        int cacheRegionCount = cacheStats.stream().mapToInt(ConversionOrchestrator.DimensionCacheStats::regionCount).sum();
        long cacheSizeBytes = cacheStats.stream().mapToLong(ConversionOrchestrator.DimensionCacheStats::sizeBytes).sum();
        ResourceKey<Level> currentDimension = ConversionOrchestrator.getCurrentDimension();
        String currentDimensionId = currentDimension == null ? "" : currentDimension.identifier().toString();
        PublicWaypointConfig.Summary waypointSummary = PublicWaypointConfig.summary();

        Platform.send(player, new PacketHandler.AdminStatusPayload(
                true,
                ConversionOrchestrator.isRunning(),
                ConversionOrchestrator.getProcessedCount(),
                ConversionOrchestrator.getTotalCount(),
                ConversionOrchestrator.getUpdatedCount(),
                ConversionOrchestrator.getSkippedCount(),
                DirtyRegionTracker.dirtyCount(),
                cacheDimensionCount,
                cacheRegionCount,
                cacheSizeBytes,
                ModConfig.SERVER.syncSpeedLimitKBps,
                ModConfig.SERVER.enableRadiusSync,
                ModConfig.SERVER.maxRadiusSyncBlocks,
                ModConfig.SERVER.radiusSyncCenterMode.name(),
                ModConfig.SERVER.radiusSyncFixedDimension,
                ModConfig.SERVER.radiusSyncFixedX,
                ModConfig.SERVER.radiusSyncFixedY,
                ModConfig.SERVER.radiusSyncFixedZ,
                waypointSummary.enabled(),
                waypointSummary.groupName(),
                waypointSummary.count(),
                waypointSummary.hash(),
                ConversionOrchestrator.getStatus(),
                currentDimensionId,
                IncrementalUpdateHandler.getInstance().getStatusInfo()
        ));
    }

    public static void sendPublicWaypoints(ServerPlayer player) {
        PacketHandler.PublicWaypointsPayload payload = PublicWaypointConfig.createPayload();
        if (payload != null) {
            Platform.send(player, payload);
        }
    }

    /**
     * A player disconnected.
     *
     * Resuming needs no bookkeeping here, because it falls out of the hash comparison:
     * - on reconnect the client reports the hashes it already has, from sync_timestamps.cache
     * - the server compares those and sends only what differs
     *
     * @param playerId the player
     */
    public static void onPlayerDisconnect(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        pendingClientMeta.remove(playerId);

        // Drop their throttle state.
        clearSpeedLimitState(playerId);

        // And interrupt the sync thread straight away.
        Future<?> syncTask = syncTasks.remove(playerId);
        if (syncTask != null && !syncTask.isDone()) {
            syncTask.cancel(true);
            LOGGER.info("Player {} disconnected, sync task cancelled", playerId);
        }
    }

    /**
     * Whether a player is still worth sending to: online, still syncing, still in the same dimension.
     *
     * @param player the player
     * @return {@code true} to keep going, {@code false} to abandon the sync
     */
    private static boolean isPlayerStillValid(ServerPlayer player) {
        UUID playerId = player.getUUID();

        // Check if player is still online and still in our sync set
        if (!syncingPlayers.contains(playerId) || player.connection == null) {
            return false;
        }

        // Check if player is still in the same dimension
        ResourceKey<Level> startDimension = playerSyncDimensions.get(playerId);
        if (startDimension != null && !player.level().dimension().equals(startDimension)) {
            LOGGER.info("Player {} changed dimension from {} to {}, aborting sync",
                    playerId, startDimension.identifier(), player.level().dimension().identifier());
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            return false;
        }

        return true;
    }

    /**
     * Reads the world ID out of xaeromap.txt.
     *
     * Location: &lt;world&gt;/xaeromap.txt
     * Format: id:&lt;number&gt;
     *
     * @param serverPlayer the player being synced
     * @return the world ID, or 0 if the file is missing
     */
    private static int readWorldIdFromXaeroMap(ServerPlayer serverPlayer) {
        try {
            Path xaeromapPath = serverPlayer.level().getServer()
                    .getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent()
                    .resolve("xaeromap.txt");

            if (!Files.exists(xaeromapPath)) {
                LOGGER.warn("xaeromap.txt not found at {}", xaeromapPath);
                return 0;
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(xaeromapPath.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2 && parts[0].equals("id")) {
                        int worldId = Integer.parseInt(parts[1]);
                        LOGGER.info("Read worldId {} from xaeromap.txt", worldId);
                        return worldId;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read xaeromap.txt", e);
        }
        return 0;
    }

    /**
     * Sleeps as needed to hold the send rate at the configured limit.
     *
     * How it works:
     * 1. track bytes sent and when the current cycle started
     * 2. after each send, work out the average rate over the cycle
     * 3. if that is above the limit, work out how long to wait
     * 4. if sending already took longer than the limit allows, do not wait at all
     *
     * The effect is that it adapts to the connection:
     * - on a fast link, waiting is what enforces the limit
     * - on a slow one, the link is already the limit and nothing extra is added
     *
     * @param bytesSent bytes sent by the call that just finished
     * @param player the player, so the wait can be cut short
     * @param playerId the player's UUID, so the wait can be cut short
     * @return {@code true} when the wait finished, {@code false} if the player dropped out
     */
    private static boolean applySpeedLimit(int bytesSent, ServerPlayer player, UUID playerId) {
        int limitKBps = getEffectiveLimitKBps(player, playerId);
        if (limitKBps <= 0) return true; // No limit

        // Current cycle, starting one if needed.
        Long cycleStart = speedLimitCycleStart.get(playerId);
        Long totalBytes = speedLimitBytesSent.get(playerId);

        if (cycleStart == null || totalBytes == null) {
            // New cycle.
            cycleStart = System.currentTimeMillis();
            totalBytes = 0L;
            speedLimitCycleStart.put(playerId, cycleStart);
            speedLimitBytesSent.put(playerId, totalBytes);
        }

        // Add this send to the running total.
        totalBytes += bytesSent;
        speedLimitBytesSent.put(playerId, totalBytes);

        // How long the cycle has actually taken.
        long actualTimeMs = System.currentTimeMillis() - cycleStart;

        // Cap the cycle length so the running total cannot grow without bound.
        if (actualTimeMs > MAX_SPEED_LIMIT_CYCLE_MS) {
            LOGGER.debug("Speed limit cycle too long ({} ms), resetting", actualTimeMs);
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            // Restart, counting this send as the beginning of the new cycle.
            totalBytes = (long) bytesSent;
            speedLimitBytesSent.put(playerId, totalBytes);
            cycleStart = System.currentTimeMillis();
            actualTimeMs = 0;
        }

        // How long these bytes should have taken at the configured rate.
        long expectedTimeMs = (totalBytes * 1000L) / (limitKBps * 1024L);

        // Already slower than that? The connection is the bottleneck; no need to wait.
        if (actualTimeMs >= expectedTimeMs) {
            LOGGER.debug("Bandwidth bottleneck detected: sent {} bytes in {} ms (expected {} ms at {} KBps), skipping wait",
                    totalBytes, actualTimeMs, expectedTimeMs, limitKBps);
            // Start a fresh cycle, since this one came in under the limit.
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            return true;
        }

        // Otherwise wait out the difference.
        long remainingTimeMs = expectedTimeMs - actualTimeMs;

        LOGGER.debug("Applying speed limit: sent {} bytes in {} ms, need to wait {} ms more (limit: {} KBps)",
                totalBytes, actualTimeMs, remainingTimeMs, limitKBps);

        // An interruptible wait, so a disconnect ends it promptly.
        long checkIntervalMs = 100; // Check every 100ms
        long waitStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - waitStartTime < remainingTimeMs) {
            // Check if player disconnected during speed limit wait
            if (!isPlayerStillValid(player)) {
                LOGGER.info("Player {} disconnected during speed limit wait, aborting sync", playerId);
                return false;
            }

            long waitRemainingMs = remainingTimeMs - (System.currentTimeMillis() - waitStartTime);
            long sleepMs = Math.min(checkIntervalMs, waitRemainingMs);

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        // Start a fresh cycle now the wait is over.
        speedLimitCycleStart.put(playerId, System.currentTimeMillis());
        speedLimitBytesSent.put(playerId, 0L);

        return true;
    }

    private static int getEffectiveLimitKBps(ServerPlayer player, UUID playerId) {
        if (!ModConfig.SERVER.enableAdaptiveSyncThrottle) {
            return ModConfig.SERVER.syncSpeedLimitKBps;
        }

        int ceiling = getAdaptiveCeilingKBps();
        if (ceiling <= 0) {
            return 0;
        }

        AdaptiveThrottleState state = adaptiveThrottleStates.computeIfAbsent(playerId,
                id -> new AdaptiveThrottleState(ceiling));
        if (state.currentLimitKBps <= 0) {
            state.currentLimitKBps = ceiling;
        } else if (state.currentLimitKBps > ceiling) {
            state.currentLimitKBps = ceiling;
        }

        int pingMs = player.connection == null ? 0 : player.connection.latency();
        state.lastPingMs = pingMs;

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(1L, ModConfig.SERVER.adaptiveThrottleAdjustCooldownMs);
        boolean cooldownReady = now - state.lastAdjustMillis >= cooldownMs;
        int oldLimit = state.currentLimitKBps;
        int minLimit = Math.min(ceiling, Math.max(1, ModConfig.SERVER.adaptiveMinSyncSpeedKBps));

        if (pingMs >= ModConfig.SERVER.adaptivePingThresholdMs) {
            state.stableRecoverSamples = 0;
            if (cooldownReady) {
                double factor = ModConfig.SERVER.adaptiveDecreaseFactor;
                int nextLimit = Math.max(minLimit, (int) Math.floor(state.currentLimitKBps * factor));
                state.currentLimitKBps = Math.min(ceiling, nextLimit);
                state.lastAdjustMillis = now;
                state.congested = true;
                logAdaptiveAdjustment(player, pingMs, oldLimit, state.currentLimitKBps, "congestion");
            } else {
                LOGGER.debug("Adaptive throttle cooldown: player={}, ping={}ms, limit={} KB/s",
                        player.getName().getString(), pingMs, state.currentLimitKBps);
            }
            return state.currentLimitKBps;
        }

        if (pingMs <= ModConfig.SERVER.adaptivePingRecoverMs) {
            state.stableRecoverSamples++;
            if (state.stableRecoverSamples >= ModConfig.SERVER.adaptiveStableRecoverSamples
                    && cooldownReady && state.currentLimitKBps < ceiling) {
                state.currentLimitKBps = Math.min(ceiling,
                        state.currentLimitKBps + Math.max(1, ModConfig.SERVER.adaptiveIncreaseStepKBps));
                state.lastAdjustMillis = now;
                state.stableRecoverSamples = 0;
                state.congested = state.currentLimitKBps < ceiling;
                logAdaptiveAdjustment(player, pingMs, oldLimit, state.currentLimitKBps, "recovery");
            }
            return state.currentLimitKBps;
        }

        state.stableRecoverSamples = 0;
        return state.currentLimitKBps;
    }

    private static int getAdaptiveCeilingKBps() {
        int fixedLimit = ModConfig.SERVER.syncSpeedLimitKBps;
        if (fixedLimit > 0) {
            return fixedLimit;
        }
        return Math.max(0, ModConfig.SERVER.adaptiveUnlimitedCeilingKBps);
    }

    private static void logAdaptiveAdjustment(ServerPlayer player, int pingMs, int oldLimit, int newLimit, String reason) {
        LOGGER.debug("Adaptive sync throttle {}: player={}, ping={}ms, {} -> {} KB/s",
                reason, player.getName().getString(), pingMs, oldLimit, newLimit);
    }

    /**
     * Clears a player's throttle state.
     *
     * @param playerId the player
     */
    private static void clearSpeedLimitState(UUID playerId) {
        speedLimitBytesSent.remove(playerId);
        speedLimitCycleStart.remove(playerId);
        adaptiveThrottleStates.remove(playerId);
    }

    /**
     * Clears everything tracked for a player, once their sync finishes or is abandoned.
     *
     * @param playerId the player
     */
    private static void cleanupSyncState(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        syncTasks.remove(playerId);
        clearSpeedLimitState(playerId);
    }

    private static SyncFilter createRadiusFilter(ServerPlayer player, PacketHandler.RadiusSyncRequestPayload payload) {
        if (!ModConfig.SERVER.enableRadiusSync) {
            return null;
        }

        int requestedRadius = Math.max(1, payload.radiusBlocks());
        int maxRadius = Math.max(1, ModConfig.SERVER.maxRadiusSyncBlocks);
        int radius = Math.min(requestedRadius, maxRadius);
        boolean clamped = radius != requestedRadius;
        RadiusSyncCenterMode mode = ModConfig.SERVER.radiusSyncCenterMode == null
                ? RadiusSyncCenterMode.PLAYER_POSITION : ModConfig.SERVER.radiusSyncCenterMode;

        String dimensionId = payload.dimensionId();
        int centerX = payload.playerX();
        int centerY = payload.playerY();
        int centerZ = payload.playerZ();
        String description;

        if (mode == RadiusSyncCenterMode.WORLD_SPAWN) {
            BlockPos spawn = player.level().getLevelData().getRespawnData().pos();
            centerX = spawn.getX();
            centerY = spawn.getY();
            centerZ = spawn.getZ();
            dimensionId = player.level().dimension().identifier().toString();
            description = String.format("server spawn [%s %d %d %d]", dimensionId, centerX, centerY, centerZ);
        } else if (mode == RadiusSyncCenterMode.FIXED) {
            dimensionId = ModConfig.SERVER.radiusSyncFixedDimension;
            centerX = ModConfig.SERVER.radiusSyncFixedX;
            centerY = ModConfig.SERVER.radiusSyncFixedY;
            centerZ = ModConfig.SERVER.radiusSyncFixedZ;
            description = String.format("fixed center [%s %d %d %d]", dimensionId, centerX, centerY, centerZ);
        } else {
            description = String.format("your position [%s %d %d %d]", dimensionId, centerX, centerY, centerZ);
        }

        String xaeroDimension = DimensionPathMapping.getInstance().toXaeroDimension(dimensionId);
        return new SyncFilter(xaeroDimension, centerX, centerY, centerZ, radius, description, clamped);
    }

    /**
     * Handles a client's sync request.
     *
     * Takes the client's metadata, compares it against the server cache and sends what is
     * missing. Resume falls out of the hash comparison, so there is no index to restore.
     *
     * <b>Note:</b> the work happens on a worker thread. Doing it on the server thread would
     * stall the server and trip the watchdog.
     *
     * @param payload the sync request
     * @param serverPlayer the player that sent it
     */
    /**
     * Buffers one instalment of a client's metadata until its request arrives.
     *
     * @param payload      the chunk
     * @param serverPlayer the player that sent it
     */
    private static void handleSyncMetaChunk(PacketHandler.SyncMetaChunkPayload payload,
                                            ServerPlayer serverPlayer) {
        UUID playerId = serverPlayer.getUUID();
        Map<String, ClientMeta> pending =
                pendingClientMeta.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());

        if (pending.size() + payload.clientMeta().size() > MAX_PENDING_CLIENT_META) {
            LOGGER.warn("Player {} sent more than {} buffered metadata entries, dropping them",
                    serverPlayer.getName().getString(), MAX_PENDING_CLIENT_META);
            pendingClientMeta.remove(playerId);
            return;
        }

        pending.putAll(payload.clientMeta());
    }

    /**
     * Combines a request's own metadata with whatever chunks preceded it.
     *
     * <p>Takes the buffer, so a request that never arrives cannot leave entries behind for
     * the next one to pick up. The request's own entries win on conflict, being the newest.</p>
     *
     * @param playerId    the player
     * @param requestMeta the metadata carried by the request itself
     * @return every entry the client reported
     */
    private static Map<String, ClientMeta> takeClientMeta(UUID playerId,
                                                          Map<String, ClientMeta> requestMeta) {
        Map<String, ClientMeta> pending = pendingClientMeta.remove(playerId);
        if (pending == null || pending.isEmpty()) {
            return requestMeta;
        }

        Map<String, ClientMeta> combined = new HashMap<>(pending);
        combined.putAll(requestMeta);
        LOGGER.info("Assembled {} metadata entries for {} ({} from chunks, {} from the request)",
                combined.size(), playerId, pending.size(), requestMeta.size());
        return combined;
    }

    private static void handleSyncRequest(PacketHandler.SyncRequestPayload payload, ServerPlayer serverPlayer) {
        sendPublicWaypoints(serverPlayer);

        UUID playerId = serverPlayer.getUUID();

        // If they were already syncing, interrupt the old run first.
        Future<?> oldTask = syncTasks.get(playerId);
        if (oldTask != null && !oldTask.isDone()) {
            LOGGER.info("Player {} requested new sync while syncing, interrupting old sync", playerId);
            oldTask.cancel(true);
            cleanupSyncState(playerId);
        }

        ResourceKey<Level> startDimension = serverPlayer.level().dimension();

        // Mark the player as syncing and note their dimension; both are quick.
        syncingPlayers.add(playerId);
        playerSyncDimensions.put(playerId, startDimension);

        // Client metadata (timestamp + hash) - contains already received regions for resume,
        // plus anything that arrived ahead of the request as its own chunks.
        Map<String, ClientMeta> clientMeta = takeClientMeta(playerId, payload.clientMeta());

        // The expensive part runs off the server thread.
        Future<?> syncTask = MapSyncerExecutors.submitSync(() ->
                processSyncAsync(serverPlayer, playerId, clientMeta, startDimension, null));
        syncTasks.put(playerId, syncTask);
        LOGGER.info("Started async sync task for player {}", serverPlayer.getName().getString());
    }

    private static void handleRadiusSyncRequest(PacketHandler.RadiusSyncRequestPayload payload,
                                                ServerPlayer serverPlayer) {
        sendPublicWaypoints(serverPlayer);
        UUID playerId = serverPlayer.getUUID();

        Future<?> oldTask = syncTasks.get(playerId);
        if (oldTask != null && !oldTask.isDone()) {
            LOGGER.info("Player {} requested radius sync while syncing, interrupting old sync", playerId);
            oldTask.cancel(true);
            cleanupSyncState(playerId);
        }

        ResourceKey<Level> startDimension = serverPlayer.level().dimension();
        syncingPlayers.add(playerId);
        playerSyncDimensions.put(playerId, startDimension);

        SyncFilter filter = createRadiusFilter(serverPlayer, payload);
        if (filter == null) {
            pendingClientMeta.remove(playerId);
            serverPlayer.level().getServer().execute(() -> {
                Platform.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, 0, "radius_disabled"));
                serverPlayer.sendSystemMessage(ChatUtils.error("mapsyncer.server.radius_disabled"));
            });
            cleanupSyncState(playerId);
            return;
        }

        serverPlayer.level().getServer().execute(() -> serverPlayer.sendSystemMessage(
                ChatUtils.message("mapsyncer.server.radius_start",
                        filter.radiusBlocks(), filter.centerDescription(), filter.clamped() ? " (clamped)" : "")));

        Map<String, ClientMeta> clientMeta = takeClientMeta(playerId, payload.clientMeta());
        Future<?> syncTask = MapSyncerExecutors.submitSync(() ->
                processSyncAsync(serverPlayer, playerId, clientMeta, startDimension, filter));
        syncTasks.put(playerId, syncTask);
        LOGGER.info("Started async radius sync task for player {}", serverPlayer.getName().getString());
    }

    /**
     * Runs the sync itself.
     * Walking the cache, comparing hashes and pushing data all happen here, on a worker
     * thread, so the server thread is never blocked.
     *
     * @param serverPlayer the player being synced
     * @param playerId the player's UUID
     * @param clientMeta what the client reported having
     * @param startDimension the dimension the player was in when the sync began
     */
    private static void processSyncAsync(ServerPlayer serverPlayer, UUID playerId,
            Map<String, ClientMeta> clientMeta, ResourceKey<Level> startDimension, SyncFilter filter) {

        // Read worldId from xaeromap.txt (Xaero's official method)
        int worldId = readWorldIdFromXaeroMap(serverPlayer);
        LOGGER.info("Server worldId from xaeromap.txt: {}", worldId);

        // Get server generation cache (timestamp + hash)
        GenerationCache genCache = GenerationCache.getInstance(ConversionOrchestrator.CACHE_DIR);
        Map<String, RegionMeta> serverCache = genCache.getAll();

        Path cacheDir = ConversionOrchestrator.CACHE_DIR;

        if (!Files.exists(cacheDir)) {
            // Chat and packets go out on the server thread.
            serverPlayer.level().getServer().execute(() -> {
                serverPlayer.sendSystemMessage(ChatUtils.message("mapsyncer.server.no_cache"));
                Platform.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "no_cache"));
            });
            cleanupSyncState(playerId);
            return;
        }

        // Sync logic:
        // 1. Hash match → skip (file content identical)
        // 2. Hash mismatch + client timestamp older → sync
        // 3. Hash mismatch + client timestamp newer → skip (client has newer data)
        // 4. Client has no metadata for this region → sync (new region)
        int hashMatchCount = 0;
        int timestampSkipCount = 0;

        // Determine which dimensions the client is requesting (based on their metadata keys)
        Set<String> requestedDimensions = new java.util.HashSet<>();
        if (filter != null) {
            requestedDimensions.add(filter.xaeroDimension());
        } else {
            for (String key : clientMeta.keySet()) {
                LOGGER.debug("Client meta key: {}", key);
                String[] parts = key.split("[/\\\\]");
                if (parts.length > 1) {
                    String dim = parts[0];
                    if (!key.contains("_placeholder_")) {
                        requestedDimensions.add(dim);
                    } else {
                        requestedDimensions.add(dim);
                        LOGGER.info("Found placeholder for dimension {}, will sync all regions", dim);
                    }
                }
            }
        }
        LOGGER.info("Client requesting dimensions (Xaero format): {}", requestedDimensions);

        // Check if requested dimensions have cache data
        Set<String> skippedDimensions = new HashSet<>();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        boolean hasValidDimension = false;

        for (String xaeroDim : requestedDimensions) {
            Path dimCacheDir = cacheDir.resolve(xaeroDim);
            if (Files.exists(dimCacheDir) && dimCacheDir.toFile().isDirectory()) {
                try (Stream<Path> stream = Files.walk(dimCacheDir)) {
                    boolean hasZipFiles = stream.anyMatch(p -> p.toString().endsWith(".zip"));
                    if (hasZipFiles) {
                        hasValidDimension = true;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to check dimension {} cache directory", xaeroDim, e);
                }
            } else {
                String friendlyDim = dimMapping.toServerDimension(xaeroDim);
                // Chat goes out on the server thread.
                serverPlayer.level().getServer().execute(() -> {
                    serverPlayer.sendSystemMessage(ChatUtils.error("mapsyncer.server.dim_not_available", friendlyDim, friendlyDim));
                });
                LOGGER.warn("Requested dimension {} (xaero: {}) has no cache data at {}", friendlyDim, xaeroDim, dimCacheDir);
            }
        }

        if (!hasValidDimension) {
            LOGGER.info("No valid dimension cache found for requested dimensions: {}", requestedDimensions);
            // Packets go out on the server thread.
            serverPlayer.level().getServer().execute(() -> {
                Platform.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "dim_not_available"));
            });
            cleanupSyncState(playerId);
            return;
        }

        // Compare server cache with client metadata to find differences
        // Collect paths only; the data is read later, one region at a time.
        List<RegionSyncInfo> regionsToSync = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(cacheDir)) {
            stream.filter(p -> p.toString().endsWith(".zip"))
                    .forEach(zipPath -> {
                        String relativePath = cacheDir.relativize(zipPath).toString();
                        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");

                        String[] parts = normalizedPath.split("[/\\\\]");
                        String xaeroDimName = parts.length > 1 ? parts[0] : "unknown";

                        String normalizedXaeroDim = dimMapping.toXaeroDimension(xaeroDimName);
                        if (!normalizedXaeroDim.equals(xaeroDimName)) {
                            normalizedPath = normalizedXaeroDim + normalizedPath.substring(xaeroDimName.length());
                        }

                        if (!requestedDimensions.contains(normalizedXaeroDim)) {
                            if (!skippedDimensions.contains(normalizedXaeroDim)) {
                                skippedDimensions.add(normalizedXaeroDim);
                                LOGGER.info("Skipping dimension {}: not requested", normalizedXaeroDim);
                            }
                            return;
                        }

                        RegionMeta serverMeta = serverCache.get(normalizedPath);
                        ClientMeta clientMetaEntry = clientMeta.get(normalizedPath);

                        // Does the client need this one?
                        boolean shouldSync = false;
                        long timestamp = 0;

                        // Server has no cache entry → compute hash from file
                        if (serverMeta == null) {
                            String serverHash = HashUtils.computeFileHash(zipPath);
                            timestamp = System.currentTimeMillis() / 1000;

                            if (clientMetaEntry == null) {
                                shouldSync = true;
                            } else if (!serverHash.equals(clientMetaEntry.hash())) {
                                shouldSync = true;
                            }
                        } else {
                            // Client has no metadata → sync (new region)
                            if (clientMetaEntry == null) {
                                shouldSync = true;
                                timestamp = serverMeta.timestampSeconds();
                            } else if (!serverMeta.hash().equals(clientMetaEntry.hash())) {
                                // Hash mismatch → check timestamps
                                if (clientMetaEntry.timestampSeconds() < serverMeta.timestampSeconds()) {
                                    shouldSync = true;
                                    timestamp = serverMeta.timestampSeconds();
                                }
                            }
                        }

                        if (shouldSync) {
                            // Parse the path, but leave the data on disk for now.
                            RegionSyncInfo info = parseRegionInfo(zipPath, normalizedPath, timestamp);
                            if (info != null) {
                                if (filter != null && !filter.includes(info)) {
                                    return;
                                }
                                regionsToSync.add(info);
                            }
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to walk cache directory", e);
        }

        // Count hash matches and timestamp skips
        for (Map.Entry<String, RegionMeta> entry : serverCache.entrySet()) {
            if (filter != null && !entry.getKey().startsWith(filter.xaeroDimension() + "/")) {
                continue;
            }
            ClientMeta cm = clientMeta.get(entry.getKey());
            if (cm != null && entry.getValue().hash().equals(cm.hash())) {
                hashMatchCount++;
            } else if (cm != null && cm.timestampSeconds() >= entry.getValue().timestampSeconds()) {
                timestampSkipCount++;
            }
        }

        int total = regionsToSync.size();
        // Final copies for the lambdas below.
        final int finalHashMatchCount = hashMatchCount;
        final int finalTimestampSkipCount = timestampSkipCount;

        LOGGER.info("Sync request from {}: {} regions to sync, {} hash match, {} timestamp skip",
                serverPlayer.getName().getString(), total, finalHashMatchCount, finalTimestampSkipCount);

        if (total == 0) {
            // Chat goes out on the server thread.
            serverPlayer.level().getServer().execute(() -> {
                serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.map_uptodate", finalHashMatchCount, finalTimestampSkipCount));
                Platform.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "uptodate"));
            });
            cleanupSyncState(playerId);
            return;
        }

        // Nearest first, so the map fills in around the player before anything else.
        sortByViewDistancePriority(regionsToSync, serverPlayer);

        // Send the lightweight "sync starting" notice immediately, so the client does not
        // sit waiting; it carries no data.
        final int initialTotal = total;
        serverPlayer.level().getServer().execute(() -> {
            Platform.send(serverPlayer,
                    new PacketHandler.SyncProgressPayload(0, initialTotal, "Sync started"));
        });

        // Read and send one region at a time, rather than loading everything into memory.
        String syncId = UUID.randomUUID().toString();
        List<ChunkMapData> batch = new ArrayList<>();
        int batchBytes = 0;
        int processed = 0;
        int batchThreshold = getBatchThreshold(serverPlayer, playerId); // target bytes per second

        for (RegionSyncInfo info : regionsToSync) {
            if (!isPlayerStillValid(serverPlayer)) {
                LOGGER.info("Player {} disconnected during sync", playerId);
                cleanupSyncState(playerId);
                return;
            }

            // Read this region's data now that it is its turn.
            ChunkMapData chunk = readRegionData(info);
            if (chunk == null) {
                LOGGER.warn("Failed to read region data: {}", info.normalizedPath());
                continue;
            }

            if (chunk.data.length > getMaxPacketSize()) {
                if (!batch.isEmpty()) {
                    if (!applySpeedLimit(batchBytes, serverPlayer, playerId)) {
                        LOGGER.info("Player {} disconnected during speed limit, aborting sync", playerId);
                        cleanupSyncState(playerId);
                        return;
                    }
                    sendBatchInChunks(batch, batchBytes, serverPlayer, worldId, processed, total);
                    processed += batch.size();
                    batch.clear();
                    batchBytes = 0;
                    batchThreshold = getBatchThreshold(serverPlayer, playerId);
                }

                if (!sendRegionParts(syncId, info, chunk, serverPlayer, worldId, playerId)) {
                    LOGGER.info("Player {} disconnected while sending region parts, aborting sync", playerId);
                    cleanupSyncState(playerId);
                    return;
                }
                processed++;
                final int partProgress = processed;
                serverPlayer.level().getServer().execute(() ->
                        Platform.send(serverPlayer,
                                new PacketHandler.SyncProgressPayload(partProgress, total,
                                        String.format("Sending regions %d/%d", partProgress, total))));
                continue;
            }

            // Once the batch reaches the threshold, flush it (split across payloads as needed).
            if (batchBytes + chunk.data.length > batchThreshold && !batch.isEmpty()) {
                if (!applySpeedLimit(batchBytes, serverPlayer, playerId)) {
                    LOGGER.info("Player {} disconnected during speed limit, aborting sync", playerId);
                    cleanupSyncState(playerId);
                    return;
                }

                sendBatchInChunks(batch, batchBytes, serverPlayer, worldId, processed, total);
                processed += batch.size();

                batch.clear();
                batchBytes = 0;
                batchThreshold = getBatchThreshold(serverPlayer, playerId);
            }

            batch.add(chunk);
            batchBytes += chunk.data.length;
        }

        if (!isPlayerStillValid(serverPlayer)) {
            LOGGER.info("Player {} disconnected before final batch", playerId);
            cleanupSyncState(playerId);
            return;
        }

        if (!batch.isEmpty()) {
            if (!applySpeedLimit(batchBytes, serverPlayer, playerId)) {
                LOGGER.info("Player {} disconnected during final speed limit, aborting sync", playerId);
                cleanupSyncState(playerId);
                return;
            }

            sendBatchInChunks(batch, batchBytes, serverPlayer, worldId, processed, total);
        }

        final int finalTotal = total;
            serverPlayer.level().getServer().execute(() -> {
                Platform.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "ok"));
                Platform.send(serverPlayer,
                        new PacketHandler.SyncProgressPayload(finalTotal, finalTotal, "completed"));
                serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", finalTotal));
            });

        LOGGER.info("Map sync complete for player {}: {} regions", serverPlayer.getName().getString(), total);

        cleanupSyncState(playerId);
    }

    /**
     * Builds the queue entry for a region, without reading its data.
     * Paths are collected and sorted first; the data comes later.
     *
     * @param zipPath path of the cached zip
     * @param normalizedPath the region's path in the form the client uses
     * @param timestampSeconds when the server generated it, in seconds
     * @return the queue entry, or {@code null} if it could not be read
     */
    private static RegionSyncInfo parseRegionInfo(Path zipPath, String normalizedPath, long timestampSeconds) {
        try {
            String[] parts = normalizedPath.split("[/\\\\]");

            String dimension;
            int caveLayer = Integer.MAX_VALUE;
            String fileName;

            if (parts.length >= 4 && parts[1].equals("caves")) {
                dimension = parts[0];
                caveLayer = Integer.parseInt(parts[2]);
                fileName = parts[3];
            } else {
                dimension = parts[0];
                fileName = parts[parts.length - 1];
            }

            String[] coords = fileName.split("_");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);

            return new RegionSyncInfo(zipPath, normalizedPath, timestampSeconds, regionX, regionZ, dimension, caveLayer);
        } catch (NumberFormatException e) {
            LOGGER.error("Failed to parse path: {}", normalizedPath, e);
            return null;
        }
    }

    /**
     * Reads one region's data.
     * Called when the region's turn comes, so only one region is in memory at a time.
     *
     * @param info the queued region
     * @return the region's data, or {@code null} if it could not be read
     */
    private static ChunkMapData readRegionData(RegionSyncInfo info) {
        try {
            byte[] data = Files.readAllBytes(info.zipPath());
            return new ChunkMapData(info.regionX(), info.regionZ(), info.dimension(),
                    data, info.timestampSeconds(), info.caveLayer());
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", info.zipPath(), e);
            return null;
        }
    }

    /**
     * Clears all tracking state.
     *
     * Called when the server stops, so nothing is left behind.
     */
    public static void cleanup() {
        syncingPlayers.clear();
        playerSyncDimensions.clear();
        syncTasks.clear();
        speedLimitBytesSent.clear();
        speedLimitCycleStart.clear();
        adaptiveThrottleStates.clear();
        LOGGER.info("ServerSyncHandler tracking data cleared");
    }

    /**
     * Clears state belonging to players who are no longer online.
     *
     * <p>A connection that dies outright may never reach the disconnect handler, leaving
     * entries behind. This sweeps anything not in the online list.</p>
     *
     * @param onlinePlayerIds UUIDs of everyone currently online
     */
    public static void cleanupOfflinePlayers(Set<UUID> onlinePlayerIds) {
        // Which tracked players are no longer connected?
        Set<UUID> toRemove = new HashSet<>();
        for (UUID playerId : syncingPlayers) {
            if (!onlinePlayerIds.contains(playerId)) {
                toRemove.add(playerId);
            }
        }

        // Drop their state.
        for (UUID playerId : toRemove) {
            LOGGER.info("Cleaning up stale state for offline player {}", playerId);
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);

        // And interrupt their sync thread if it is still running.
            Future<?> syncTask = syncTasks.remove(playerId);
            if (syncTask != null && !syncTask.isDone()) {
                syncTask.cancel(true);
            }

            clearSpeedLimitState(playerId);
        }

        if (!toRemove.isEmpty()) {
            LOGGER.debug("Cleaned up {} stale player states", toRemove.size());
        }
    }

    /**
     * Sorts the queue so the player's surroundings arrive first.
     * Regions within view distance go to the front, so the map fills in around the player.
     *
     * <p>The order:</p>
     * <ul>
     *   <li>work out which region the player is standing in</li>
     *   <li>regions within view distance of it come first</li>
     *   <li>everything else follows, nearest first</li>
     * </ul>
     *
     * @param regions the regions queued for syncing
     * @param player the player being synced
     */
    private static void sortByViewDistancePriority(List<RegionSyncInfo> regions, ServerPlayer player) {
        // Where the player is.
        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        int playerRegionX = playerChunkX >> 5;
        int playerRegionZ = playerChunkZ >> 5;

        // View distance, plus 2 chunks of slack for the player moving.
        int viewDistanceChunks = player.level().getServer().getPlayerList().getViewDistance() + 2;
        int viewDistanceRegions = (viewDistanceChunks >> 5) + 1;  // round up

        LOGGER.debug("Player region: ({}, {}), view distance: {} chunks = ~{} regions",
                playerRegionX, playerRegionZ, viewDistanceChunks, viewDistanceRegions);

        // Distance from the player to each region, then sort.
        regions.sort((a, b) -> {
            int distA = Math.max(Math.abs(a.regionX() - playerRegionX), Math.abs(a.regionZ() - playerRegionZ));
            int distB = Math.max(Math.abs(b.regionX() - playerRegionX), Math.abs(b.regionZ() - playerRegionZ));

        // In view first; beyond that, nearest first.
            boolean aInView = distA <= viewDistanceRegions;
            boolean bInView = distB <= viewDistanceRegions;

            if (aInView && !bInView) return -1;  // a is in view, so a first
            if (!aInView && bInView) return 1;   // b is in view, so b first
            return Integer.compare(distA, distB); // otherwise nearest first
        });

        // How many ended up in view, for the log.
        int viewRegionCount = 0;
        for (RegionSyncInfo info : regions) {
            int dist = Math.max(Math.abs(info.regionX() - playerRegionX), Math.abs(info.regionZ() - playerRegionZ));
            if (dist <= viewDistanceRegions) {
                viewRegionCount++;
            }
        }

        LOGGER.info("Sorted {} regions: {} in view distance ({} region radius), rest by distance",
                regions.size(), viewRegionCount, viewDistanceRegions);
    }
}
