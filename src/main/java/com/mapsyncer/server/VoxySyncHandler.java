package com.mapsyncer.server;

import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.platform.MapSyncerPlatform;
import com.mapsyncer.platform.Platform;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VoxySyncHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxySyncHandler.class);
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("^r\\.(-?[0-9]+)\\.(-?[0-9]+)\\.mca$");
    private static final int MAX_PACKET_SIZE_LIMIT = 1_000_000;

    private static final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Thread> syncThreads = new ConcurrentHashMap<>();
    private static final Map<UUID, LevelKey> playerSyncDimensions = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> speedLimitBytesSent = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> speedLimitCycleStart = new ConcurrentHashMap<>();

    private VoxySyncHandler() {
    }

    public static void register() {
        MapSyncerPlatform platform = Platform.get();

        platform.registerClientbound(
                PacketHandler.VoxyCapabilityPayload.TYPE,
                PacketHandler.VoxyCapabilityPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.VoxySyncStartPayload.TYPE,
                PacketHandler.VoxySyncStartPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.VoxyRegionPartPayload.TYPE,
                PacketHandler.VoxyRegionPartPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.VoxySyncProgressPayload.TYPE,
                PacketHandler.VoxySyncProgressPayload.STREAM_CODEC);
        platform.registerClientbound(
                PacketHandler.VoxySyncCompletePayload.TYPE,
                PacketHandler.VoxySyncCompletePayload.STREAM_CODEC);

        platform.registerServerbound(
                PacketHandler.VoxyCapabilityRequestPayload.TYPE,
                PacketHandler.VoxyCapabilityRequestPayload.STREAM_CODEC,
                (payload, player) -> sendCapability(player));
        platform.registerServerbound(
                PacketHandler.VoxySyncRequestPayload.TYPE,
                PacketHandler.VoxySyncRequestPayload.STREAM_CODEC,
                VoxySyncHandler::handleSyncRequest);
    }

    public static void logSecurityWarningIfEnabled() {
        if (!ModConfig.SERVER.enableVoxySync) {
            return;
        }
        LOGGER.warn("============================================================");
        LOGGER.warn("[MapSyncer] Voxy sync is ENABLED. This sends full MCA region files to clients.");
        LOGGER.warn("[MapSyncer] MCA data can expose chest contents, block entities, entities, ores and hidden structures.");
        LOGGER.warn("[MapSyncer] Only enable this on trusted technical/build servers.");
        LOGGER.warn("============================================================");
    }

    private static void sendCapability(ServerPlayer player) {
        player.level().getServer().execute(() -> Platform.send(player,
                new PacketHandler.VoxyCapabilityPayload(
                        ModConfig.SERVER.enableVoxySync,
                        ModConfig.SERVER.enableVoxySync ? "enabled" : "server_disabled")));
    }

    private static void handleSyncRequest(PacketHandler.VoxySyncRequestPayload payload, ServerPlayer player) {
        player.level().getServer().execute(() -> {
            if (!ModConfig.SERVER.enableVoxySync) {
                sendComplete(player, "", false, "server_disabled", 0, 0);
                return;
            }

            UUID playerId = player.getUUID();
            if (syncingPlayers.contains(playerId)) {
                sendComplete(player, "", false, "busy", 0, 0);
                return;
            }

            Thread oldThread = syncThreads.remove(playerId);
            if (oldThread != null && oldThread.isAlive()) {
                oldThread.interrupt();
            }
            cleanupSyncState(playerId);

            if (!(player.level() instanceof ServerLevel level)) {
                sendComplete(player, "", false, "no_connection", 0, 0);
                return;
            }
            String currentDimension = level.dimension().identifier().toString();
            if (!currentDimension.equals(payload.dimensionId())) {
                sendComplete(player, "", false, "dimension_changed", 0, 0);
                return;
            }

            Path regionDir = RegionScanner.getRegionDir(level);
            if (regionDir == null || !Files.isDirectory(regionDir)) {
                sendComplete(player, "", false, "region_dir_missing", 0, 0);
                return;
            }

            String syncId = UUID.randomUUID().toString();
            syncingPlayers.add(playerId);
            playerSyncDimensions.put(playerId, new LevelKey(currentDimension));

            Thread thread = new Thread(() -> runSync(syncId, player, currentDimension, regionDir, payload.clientMeta()),
                    "mapsyncer-voxy-sync-" + playerId);
            thread.setDaemon(true);
            syncThreads.put(playerId, thread);
            thread.start();
        });
    }

    private static void runSync(String syncId, ServerPlayer player, String dimensionId, Path regionDir,
                                Map<String, PacketHandler.VoxyRegionMeta> clientMeta) {
        UUID playerId = player.getUUID();
        int transferredRegions = 0;
        long transferredBytes = 0;

        try {
            List<RegionFileInfo> regions = collectRegions(dimensionId, regionDir, clientMeta);
            long totalBytes = regions.stream().mapToLong(RegionFileInfo::sizeBytes).sum();
            sendStart(player, new PacketHandler.VoxySyncStartPayload(syncId, dimensionId, regions.size(), totalBytes));

            if (regions.isEmpty()) {
                sendComplete(player, syncId, true, "completed", 0, 0);
                return;
            }

            for (RegionFileInfo region : regions) {
                if (!isPlayerStillValid(player, dimensionId)) {
                    sendComplete(player, syncId, false, "interrupted", transferredRegions, transferredBytes);
                    return;
                }
                sendRegion(syncId, player, dimensionId, region);
                transferredRegions++;
                transferredBytes += region.sizeBytes();
                sendProgress(player, new PacketHandler.VoxySyncProgressPayload(syncId, transferredRegions, regions.size(),
                        transferredBytes, totalBytes, "sending"));
            }

            sendComplete(player, syncId, true, "completed", transferredRegions, transferredBytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendComplete(player, syncId, false, "interrupted", transferredRegions, transferredBytes);
        } catch (Exception e) {
            LOGGER.error("Voxy sync failed for {}", player.getName().getString(), e);
            sendComplete(player, syncId, false, "failed", transferredRegions, transferredBytes);
        } finally {
            Thread currentThread = syncThreads.get(playerId);
            if (currentThread == Thread.currentThread()) {
                syncThreads.remove(playerId);
            }
            cleanupSyncState(playerId);
        }
    }

    private static List<RegionFileInfo> collectRegions(String dimensionId, Path regionDir,
                                                       Map<String, PacketHandler.VoxyRegionMeta> clientMeta) throws IOException {
        List<RegionFileInfo> regions = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                Matcher matcher = REGION_FILE_PATTERN.matcher(fileName);
                if (!matcher.matches()) {
                    continue;
                }
                long sizeBytes = Files.size(path);
                if (sizeBytes <= 0) {
                    continue;
                }
                long timestampSeconds = Files.getLastModifiedTime(path).toMillis() / 1000;
                String key = dimensionId + "/" + fileName;
                PacketHandler.VoxyRegionMeta clientEntry = clientMeta.get(key);
                if (clientEntry != null
                        && clientEntry.timestampSeconds() == timestampSeconds
                        && clientEntry.sizeBytes() == sizeBytes) {
                    continue;
                }
                regions.add(new RegionFileInfo(path, Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)), timestampSeconds, sizeBytes));
            }
        }
        regions.sort((a, b) -> {
            int x = Integer.compare(a.regionX(), b.regionX());
            return x != 0 ? x : Integer.compare(a.regionZ(), b.regionZ());
        });
        return regions;
    }

    private static void sendRegion(String syncId, ServerPlayer player, String dimensionId, RegionFileInfo region)
            throws IOException, InterruptedException {
        int chunkSize = getPayloadDataSize();
        int totalParts = (int) Math.max(1, (region.sizeBytes() + chunkSize - 1) / chunkSize);
        ByteBuffer buffer = ByteBuffer.allocate(chunkSize);

        try (FileChannel channel = FileChannel.open(region.path(), StandardOpenOption.READ)) {
            for (int partIndex = 0; partIndex < totalParts; partIndex++) {
                long offset = (long) partIndex * chunkSize;
                buffer.clear();
                int read = channel.read(buffer, offset);
                if (read < 0) {
                    read = 0;
                }
                byte[] data = new byte[read];
                buffer.flip();
                buffer.get(data);
                if (data.length == 0 && region.sizeBytes() > 0) {
                    throw new IOException("Unexpected empty read for " + region.path());
                }

                if (!applySpeedLimit(data.length, player)) {
                    throw new InterruptedException("player invalid during speed limit");
                }

                PacketHandler.VoxyRegionPartPayload payload = new PacketHandler.VoxyRegionPartPayload(
                        syncId, dimensionId, region.regionX(), region.regionZ(), partIndex, totalParts,
                        offset, region.sizeBytes(), region.timestampSeconds(), data);
                sendPart(player, payload);
            }
        }
    }

    /**
     * Size of the data carried by one Voxy part.
     *
     * <p>Reserves 2KB for the part header. Where the platform's payload limit is small
     * (Paper's plugin messaging channel), falls back to that limit so a part never exceeds
     * what the channel accepts.</p>
     *
     * @return the per-part data size in bytes
     */
    private static int getPayloadDataSize() {
        int platformLimit = Platform.get().maxPayloadBytes();
        int maxPacketSize = Math.min(ModConfig.SERVER.maxSyncPacketSize,
                Math.min(MAX_PACKET_SIZE_LIMIT, platformLimit));
        int available = maxPacketSize - 2048;
        if (available >= 16 * 1024) {
            return available;
        }
        return Math.max(4096, available);
    }

    private static boolean applySpeedLimit(int bytesSent, ServerPlayer player) throws InterruptedException {
        int limitKBps = ModConfig.SERVER.syncSpeedLimitKBps;
        if (limitKBps <= 0 || bytesSent <= 0) {
            return isPlayerStillValid(player);
        }

        UUID playerId = player.getUUID();
        long cycleStart = speedLimitCycleStart.getOrDefault(playerId, System.currentTimeMillis());
        long totalBytes = speedLimitBytesSent.getOrDefault(playerId, 0L) + bytesSent;
        speedLimitCycleStart.put(playerId, cycleStart);
        speedLimitBytesSent.put(playerId, totalBytes);

        long actualTimeMs = System.currentTimeMillis() - cycleStart;
        long expectedTimeMs = (totalBytes * 1000L) / (limitKBps * 1024L);
        if (actualTimeMs >= expectedTimeMs || actualTimeMs > 1000) {
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            return isPlayerStillValid(player);
        }

        long waitMs = expectedTimeMs - actualTimeMs;
        long waitStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - waitStart < waitMs) {
            if (!isPlayerStillValid(player)) {
                return false;
            }
            Thread.sleep(Math.min(100, waitMs - (System.currentTimeMillis() - waitStart)));
        }
        return isPlayerStillValid(player);
    }

    private static boolean isPlayerStillValid(ServerPlayer player) {
        LevelKey key = playerSyncDimensions.get(player.getUUID());
        return key != null && isPlayerStillValid(player, key.dimensionId());
    }

    private static boolean isPlayerStillValid(ServerPlayer player, String dimensionId) {
        return syncingPlayers.contains(player.getUUID())
                && player.connection != null
                && player.level().dimension().identifier().toString().equals(dimensionId);
    }

    private static void sendStart(ServerPlayer player, PacketHandler.VoxySyncStartPayload payload) {
        player.level().getServer().execute(() -> sendIfConnected(player, payload));
    }

    private static void sendProgress(ServerPlayer player, PacketHandler.VoxySyncProgressPayload payload) {
        player.level().getServer().execute(() -> sendIfConnected(player, payload));
    }

    private static void sendComplete(ServerPlayer player, String syncId, boolean success, String message,
                                     int transferredRegions, long transferredBytes) {
        player.level().getServer().execute(() -> sendIfConnected(player,
                new PacketHandler.VoxySyncCompletePayload(syncId, success, message, transferredRegions, transferredBytes)));
    }

    private static void sendPart(ServerPlayer player, PacketHandler.VoxyRegionPartPayload payload) {
        player.level().getServer().execute(() -> sendIfConnected(player, payload));
    }

    private static void sendIfConnected(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection == null || !Platform.canSend(player, payload.type())) {
            return;
        }
        try {
            Platform.send(player, payload);
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOGGER.debug("Skipping Voxy packet for disconnected player {}", player.getUUID(), e);
        }
    }

    public static void onPlayerDisconnect(UUID playerId) {
        Thread thread = syncThreads.remove(playerId);
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        cleanupSyncState(playerId);
    }

    public static void cleanupOfflinePlayers(Set<UUID> onlinePlayerIds) {
        Set<UUID> toRemove = new HashSet<>();
        for (UUID playerId : syncingPlayers) {
            if (!onlinePlayerIds.contains(playerId)) {
                toRemove.add(playerId);
            }
        }
        for (UUID playerId : toRemove) {
            onPlayerDisconnect(playerId);
        }
    }

    public static void cleanup() {
        for (UUID playerId : new HashSet<>(syncingPlayers)) {
            onPlayerDisconnect(playerId);
        }
        syncingPlayers.clear();
        syncThreads.clear();
        playerSyncDimensions.clear();
        speedLimitBytesSent.clear();
        speedLimitCycleStart.clear();
    }

    private static void cleanupSyncState(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        speedLimitBytesSent.remove(playerId);
        speedLimitCycleStart.remove(playerId);
    }

    private record RegionFileInfo(Path path, int regionX, int regionZ, long timestampSeconds, long sizeBytes) {
    }

    private record LevelKey(String dimensionId) {
    }
}
