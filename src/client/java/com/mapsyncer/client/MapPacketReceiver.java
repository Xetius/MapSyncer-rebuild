package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Receives map data from the server and writes it into Xaero's map directory.
 *
 * <p>What it does:</p>
 * <ul>
 *   <li>handles the sync request, response and progress packets</li>
 *   <li>writes each region as it arrives and loads it straight away</li>
 *   <li>resets Xaero's region state so the new data is picked up</li>
 *   <li>spots timed-out and stale syncs, so nothing is left accumulating</li>
 *   <li>when syncing the current dimension, unloads nearby regions first so the server's data replaces them</li>
 * </ul>
 */
public class MapPacketReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketReceiver.class);

    /** Whether a sync is running, which chunk-update pausing keys off. */
    private static volatile boolean syncInProgress = false;

    /**
     * Whether a sync is running.
     *
     * @return {@code true} while one is in progress
     */
    public static boolean isSyncInProgress() {
        return syncInProgress;
    }

    /** Whether this server has MapSyncer, learned when joining. */
    private static volatile boolean serverInstalled = false;

    /** The server's version. */
    private static volatile String serverVersion = "";

    /** The last mw directory written to, for cache clearing. */
    private static volatile Path lastMwDir = null;

    /** When the sync started, used to spot stale syncs. */
    private static volatile long syncStartTime = 0;

    /** How long before a sync counts as stale: 10 minutes. */
    private static final long STALE_SYNC_TIMEOUT_MS = 10 * 60 * 1000;

    /** Writes to disk off the main thread, so packet handling never blocks the client. */
    private static final ExecutorService SYNC_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mapsyncer-client-sync-worker");
        thread.setDaemon(true);
        return thread;
    });

    /** Xaero regions refreshed per tick, so reflection-driven refreshes do not drop frames. */
    private static final int REGION_RELOAD_BATCH_SIZE = 10;
    private static final long REGION_RELOAD_BATCH_DELAY_MS = 2_000;

    /** Regions waiting to be refreshed on the client thread. */
    private static final ConcurrentLinkedQueue<PendingRegionLoad> pendingRegionLoads = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingRegionLoad> externalRegionLoads = new ConcurrentLinkedQueue<>();

    /** Discards work left over from a disconnect, a cancellation, or a newer sync. */
    private static final AtomicLong syncGeneration = new AtomicLong();
    private static final Map<String, IncomingRegionParts> incomingRegionParts = new ConcurrentHashMap<>();
    private static final Map<Path, List<Path>> cacheDirectoriesByMwDir = new ConcurrentHashMap<>();

    private static volatile boolean completionPending = false;
    private static volatile int completionRegionCount = 0;
    private static volatile long lastRegionReloadFlushMillis = 0;

    /** Regions updated during this sync; coordinates only, to keep memory flat. */
    private static final Set<XaeroMapIntegrator.RegionCoord> updatedRegionCoords = ConcurrentHashMap.newKeySet();

    /** Regions already loaded, so none is loaded twice. */
    private static final Set<XaeroMapIntegrator.RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();

    /** Cached reflection handles, so the lookups happen once. */
    private static volatile Object cachedMapProcessor = null;
    private static volatile Object cachedMapSaveLoad = null;
    private static volatile Method cachedGetLeafMapRegion = null;
    private static volatile Method cachedRequestLoad = null;
    private static volatile Field cachedLoadStateField = null;
    private static volatile Field cachedShouldCacheField = null;
    private static volatile Method cachedSetHasHadTerrain = null;
    private static volatile Method cachedCancelRefresh = null;

    /** Whether the reflection cache has been initialised. */
    private static volatile boolean reflectionInitialized = false;

    /**
     * Whether the current sync has been running too long.
     * A stale sync usually means the connection dropped, so its data should be discarded.
     *
     * @return {@code true} if the sync is stale
     */
    public static boolean isSyncStale() {
        if (!syncInProgress || syncStartTime == 0) {
            return false;
        }
        return System.currentTimeMillis() - syncStartTime > STALE_SYNC_TIMEOUT_MS;
    }

    /**
     * Discards everything accumulated by the current sync.
     * Called when a sync is interrupted or goes stale.
     */
    public static void clearSyncData() {
        syncGeneration.incrementAndGet();
        syncInProgress = false;
        lastMwDir = null;
        syncStartTime = 0;
        completionPending = false;
        completionRegionCount = 0;
        pendingRegionLoads.clear();
        externalRegionLoads.clear();
        loadedRegions.clear();
        lastRegionReloadFlushMillis = 0;
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        cleanupPartFiles();
        cleanupStalePartFiles(serverDir);
        cacheDirectoriesByMwDir.clear();
        clearReceivedChunks();
        LOGGER.info("Cleared sync data to prevent memory leak");
    }

    /**
     * Clears the set of updated region coordinates.
     * Called when a sync finishes or is interrupted, and when the client stops.
     */
    public static void clearReceivedChunks() {
        updatedRegionCoords.clear();
    }

    /**
     * Whether this server has MapSyncer.
     *
     * @return {@code true} if it does
     */
    public static boolean isServerInstalled() {
        return serverInstalled;
    }

    public static String getServerVersion() {
        return serverVersion;
    }

    /**
     * Forgets the server's install state, on leaving the server.
     */
    public static void resetServerStatus() {
        serverInstalled = false;
        serverVersion = "";
        AutoSyncManager.reset();
        clearSyncData();
        clearReflectionCache();
    }

    public static void handleServerInstalled(PacketHandler.ServerInstalledPayload payload,
            ClientPlayNetworking.Context context) {
        serverInstalled = true;
        serverVersion = payload.version();
        LOGGER.info("Server has MapSyncer installed, version: {}", serverVersion);
        AutoSyncManager.onServerInstalled();
    }

    /**
     * Handles a sync response from the server.
     * Each region is loaded as soon as it is written, rather than waiting for the whole sync.
     *
     * <p>By status:</p>
     * <ul>
     *   <li>"ok": data follows, so stream it in</li>
     *   <li>"uptodate": nothing to do</li>
     *   <li>"no_cache": the server has no cache, so nothing to do</li>
     *   <li>"dim_not_available": no such dimension, so nothing to do</li>
     * </ul>
     *
     * @param payload the sync response
     * @param context the packet context
     */
    public static void handleSyncResponse(PacketHandler.SyncResponsePayload payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> {
            String status = payload.status();
            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();

            LOGGER.debug("Received sync response: status={}, chunks={}, isComplete={}", status, chunks.size(), payload.isComplete());

            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
            DefaultMapMigration.schedule(serverDir);

            // What to do depends on the status.
            if ("no_cache".equals(status) || "dim_not_available".equals(status)) {
                LOGGER.info("Server returned error status: {}, no sync needed", status);
                SyncProgressTracker.cancelTracking();
                clearSyncData();
                clearReflectionCache();
                clearSyncStateOnWorker(serverDir);
                return;
            }

            if ("uptodate".equals(status)) {
                LOGGER.info("Map is up-to-date, no sync needed");
                SyncProgressTracker.completeWithCount(0);
                clearSyncData();
                clearReflectionCache();
                markSyncCompleteOnWorker(serverDir);
                return;
            }

            // status == "ok": there is data to take.
            if (isSyncStale()) {
                SyncProgressTracker.cancelTracking();
                clearSyncData();
                clearReflectionCache();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.timeout"));
                }
                return;
            }

            // Set up the reflection cache on the first packet of data.
            if (!syncInProgress) {
                syncInProgress = true;
                syncStartTime = System.currentTimeMillis();
                long generation = syncGeneration.incrementAndGet();
                updatedRegionCoords.clear();
                loadedRegions.clear();
                pendingRegionLoads.clear();
                cleanupPartFiles();
                cleanupStalePartFiles(serverDir);
                cacheDirectoriesByMwDir.clear();
                completionPending = false;
                completionRegionCount = 0;
                lastRegionReloadFlushMillis = System.currentTimeMillis();
                LOGGER.info("Starting sync (background streaming mode, generation={})", generation);
                initializeReflectionCache();
            }

            // The current view distance, to tell near regions from far ones.
            // Note this has to use the chunk's caveLayer, not the surface layer.
            Minecraft mc = Minecraft.getInstance();
            boolean isCaveDimension = mc.level != null && mc.level.dimension() == Level.NETHER;
            String currentXaeroDim = mc.level != null
                    ? DimensionPathMapping.getInstance().toXaeroDimension(mc.level.dimension().identifier().toString())
                    : null;
            Map<Integer, Set<XaeroMapIntegrator.RegionCoord>> viewRegionsByLayer = new HashMap<>();
            for (ChunkMapData chunk : chunks) {
                boolean shouldProcess = isCaveDimension
                    ? (chunk.caveLayer != Integer.MAX_VALUE)
                    : (chunk.caveLayer == Integer.MAX_VALUE);
                if (shouldProcess && currentXaeroDim != null && currentXaeroDim.equals(chunk.dimension)) {
                    viewRegionsByLayer.computeIfAbsent(chunk.caveLayer, XaeroMapIntegrator::getViewDistanceRegions);
                }
            }

            long generation = syncGeneration.get();
            List<ChunkMapData> chunkSnapshot = List.copyOf(chunks);
            SYNC_WORKER.execute(() -> processSyncResponseOnWorker(
                    context.client(),
                    chunkSnapshot,
                    payload.isComplete(),
                    serverWorldId,
                    serverDir,
                    isCaveDimension,
                    currentXaeroDim,
                    viewRegionsByLayer,
                    generation));
        });
    }

    /**
     * Handles a progress update from the server.
     * Passes it to the progress tracker.
     *
     * @param payload the progress update
     * @param context the packet context
     */
    public static void handleSyncProgress(PacketHandler.SyncProgressPayload payload, ClientPlayNetworking.Context context) {
        context.client().execute(() ->
                SyncProgressTracker.update(payload.processed(), payload.total(), payload.status()));
    }

    public static void handleSyncRegionPart(PacketHandler.SyncRegionPartPayload payload,
            ClientPlayNetworking.Context context) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        DefaultMapMigration.schedule(serverDir);
        boolean isCaveDimension = context.client().level != null && context.client().level.dimension() == Level.NETHER;
        String currentXaeroDim = context.client().level != null
                ? DimensionPathMapping.getInstance().toXaeroDimension(context.client().level.dimension().identifier().toString())
                : null;

        if (!syncInProgress) {
            syncInProgress = true;
            syncStartTime = System.currentTimeMillis();
            syncGeneration.incrementAndGet();
            lastRegionReloadFlushMillis = System.currentTimeMillis();
            initializeReflectionCache();
        }

        long generation = syncGeneration.get();
        SYNC_WORKER.execute(() -> processRegionPartOnWorker(
                payload, serverDir, isCaveDimension, currentXaeroDim, generation));
    }

    public static void handleSyncRegionComplete(PacketHandler.SyncRegionCompletePayload payload,
            ClientPlayNetworking.Context context) {
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        boolean isCaveDimension = context.client().level != null && context.client().level.dimension() == Level.NETHER;
        String currentXaeroDim = context.client().level != null
                ? DimensionPathMapping.getInstance().toXaeroDimension(context.client().level.dimension().identifier().toString())
                : null;
        long generation = syncGeneration.get();
        SYNC_WORKER.execute(() -> processRegionCompleteOnWorker(
                context.client(), payload, serverDir, isCaveDimension, currentXaeroDim, generation));
    }

    /**
     * Resumes chunk updates once the sync is done.
     * The old global pause is no longer used.
     */
    private static void resumeChunkUpdates() {
        syncInProgress = false;
        ClientTimestampCache.saveCurrent();
        LOGGER.info("Sync complete");
    }

    private static void processSyncResponseOnWorker(Minecraft client,
            List<ChunkMapData> chunks,
            boolean isComplete,
            int serverWorldId,
            Path serverDir,
            boolean isCaveDimension,
            String currentXaeroDim,
            Map<Integer, Set<XaeroMapIntegrator.RegionCoord>> viewRegionsByLayer,
            long generation) {
        if (generation != syncGeneration.get()) {
            return;
        }

        try {
            ClientTimestampCache tsCache = getTimestampCacheOnWorker(serverDir);

            for (ChunkMapData chunk : chunks) {
                if (generation != syncGeneration.get()) {
                    return;
                }

                XaeroMapIntegrator.RegionCoord coord = new XaeroMapIntegrator.RegionCoord(
                        chunk.regionX, chunk.regionZ, chunk.caveLayer);
                updatedRegionCoords.add(coord);

                Path mwDir = null;
                if (serverDir != null) {
                    mwDir = XaeroMapIntegrator.writeChunkDataAndGetMwDir(chunk, serverDir, serverWorldId);
                    if (mwDir != null) {
                        lastMwDir = mwDir;
                        cacheDirectoriesByMwDir.remove(mwDir);
                    }
                } else {
                    LOGGER.warn("Skipping map write because server directory is unavailable");
                }

                if (tsCache != null) {
                    String relativePath = buildRelativePathForCache(chunk);
                    String hash = HashUtils.computeHash(chunk.data);
                    tsCache.update(relativePath, chunk.timestampSeconds, hash);
                    tsCache.saveDeferred();
                }

                boolean shouldProcess = isCaveDimension
                    ? (chunk.caveLayer != Integer.MAX_VALUE)
                    : (chunk.caveLayer == Integer.MAX_VALUE);
                if (mwDir != null) {
                    clearSingleRegionCache(coord, mwDir);
                }
                if (shouldProcess && mwDir != null
                        && currentXaeroDim != null && currentXaeroDim.equals(chunk.dimension)) {
                    Set<XaeroMapIntegrator.RegionCoord> viewRegionsForLayer = viewRegionsByLayer.get(chunk.caveLayer);
                    boolean inViewDistance = viewRegionsForLayer != null && viewRegionsForLayer.contains(coord);
                    if (inViewDistance) {
                        pendingRegionLoads.offer(new PendingRegionLoad(coord, chunk.caveLayer, true, generation));
                        LOGGER.debug("Queued region ({}, {}) layer={} for throttled Xaero reload",
                                coord.x(), coord.z(), chunk.caveLayer);
                    } else {
                        LOGGER.debug("Region ({}, {}) layer={} written; Xaero reload deferred until needed",
                                coord.x(), coord.z(), chunk.caveLayer);
                    }
                }
            }

            if (isComplete) {
                int totalReceived = updatedRegionCoords.size();
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
                cleanupPartFiles();
                cleanupStalePartFiles(serverDir);

                client.execute(() -> {
                    if (generation != syncGeneration.get()) {
                        return;
                    }
                    completionRegionCount = totalReceived;
                    completionPending = true;
                    tryCompleteSyncOnClient(generation);
                });
            }
        } catch (Exception e) {
            LOGGER.error("Failed to process sync response on background worker", e);
            client.execute(() -> {
                if (generation != syncGeneration.get()) {
                    return;
                }
                SyncProgressTracker.cancelTracking();
                clearSyncData();
                clearReflectionCache();
            });
        }
    }

    private static ClientTimestampCache getTimestampCacheOnWorker(Path serverDir) {
        return serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir)
                : null;
    }

    private static void processRegionPartOnWorker(PacketHandler.SyncRegionPartPayload payload,
            Path serverDir, boolean isCaveDimension, String currentXaeroDim, long generation) {
        if (generation != syncGeneration.get() || serverDir == null) {
            return;
        }

        ChunkMapData chunk = new ChunkMapData(payload.regionX(), payload.regionZ(), payload.dimension(),
                new byte[0], payload.timestampSeconds(), payload.caveLayer());
        XaeroMapIntegrator.RegionFileTarget target =
                XaeroMapIntegrator.resolveRegionFileTarget(chunk, serverDir, payload.worldId());
        if (target == null) {
            return;
        }
        if (payload.totalParts() <= 0 || payload.totalBytes() < 0
                || payload.byteOffset() < 0 || payload.byteOffset() + payload.data().length > payload.totalBytes()) {
            return;
        }

        try {
            Files.createDirectories(target.targetDir());
            String key = payload.syncId() + "|" + payload.regionKey();
            IncomingRegionParts parts = incomingRegionParts.computeIfAbsent(key, ignored ->
                    new IncomingRegionParts(target.partFile(), target.outputFile(), payload.totalParts(),
                            payload.totalBytes(), payload.timestampSeconds(), payload.hash(), chunk));
            if (!parts.matches(payload, target)) {
                cleanupIncomingPart(key, parts);
                return;
            }
            if (payload.partIndex() < 0 || payload.partIndex() >= parts.totalParts) {
                cleanupIncomingPart(key, parts);
                return;
            }
            if (parts.received.get(payload.partIndex())) {
                return;
            }
            if (parts.receivedBytes + payload.data().length > parts.totalBytes) {
                cleanupIncomingPart(key, parts);
                return;
            }

            try (SeekableByteChannel channel = Files.newByteChannel(parts.partFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.position(payload.byteOffset());
                channel.write(ByteBuffer.wrap(payload.data()));
            }
            parts.received.set(payload.partIndex());
            parts.receivedBytes += payload.data().length;
        } catch (Exception e) {
            LOGGER.error("Failed to write region part {}", payload.regionKey(), e);
        }
    }

    private static void processRegionCompleteOnWorker(Minecraft client,
            PacketHandler.SyncRegionCompletePayload payload, Path serverDir,
            boolean isCaveDimension, String currentXaeroDim, long generation) {
        if (generation != syncGeneration.get() || serverDir == null) {
            return;
        }

        String key = payload.syncId() + "|" + payload.regionKey();
        IncomingRegionParts parts = incomingRegionParts.remove(key);
        if (parts == null) {
            LOGGER.warn("Missing part state for completed region {}", payload.regionKey());
            return;
        }

        try {
            if (parts.received.cardinality() != payload.totalParts()
                    || Files.size(parts.partFile) != payload.totalBytes()) {
                cleanupIncomingPart(key, parts);
                LOGGER.warn("Incomplete region parts for {}", payload.regionKey());
                return;
            }

            String hash = HashUtils.computeFileHash(parts.partFile);
            if (!hash.equals(payload.hash())) {
                cleanupIncomingPart(key, parts);
                LOGGER.warn("Hash mismatch for region {}: {} != {}", payload.regionKey(), hash, payload.hash());
                return;
            }

            Files.createDirectories(parts.outputFile.getParent());
            Path mwDir = parts.chunk.caveLayer == Integer.MAX_VALUE
                    ? parts.outputFile.getParent()
                    : parts.outputFile.getParent().getParent().getParent();
            try {
                Files.move(parts.partFile, parts.outputFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(parts.partFile, parts.outputFile, StandardCopyOption.REPLACE_EXISTING);
            }

            lastMwDir = mwDir;
            cacheDirectoriesByMwDir.remove(mwDir);

            ClientTimestampCache tsCache = getTimestampCacheOnWorker(serverDir);
            if (tsCache != null) {
                tsCache.update(payload.regionKey(), payload.timestampSeconds(), payload.hash());
                tsCache.saveDeferred();
            }

            XaeroMapIntegrator.RegionCoord coord = new XaeroMapIntegrator.RegionCoord(
                    payload.regionX(), payload.regionZ(), payload.caveLayer());
            updatedRegionCoords.add(coord);
            clearSingleRegionCache(coord, lastMwDir);
            boolean shouldProcess = isCaveDimension
                    ? (payload.caveLayer() != Integer.MAX_VALUE)
                    : (payload.caveLayer() == Integer.MAX_VALUE);
            if (shouldProcess && currentXaeroDim != null && currentXaeroDim.equals(payload.dimension())) {
                Set<XaeroMapIntegrator.RegionCoord> viewRegions =
                        XaeroMapIntegrator.getViewDistanceRegions(payload.caveLayer());
                if (viewRegions.contains(coord)) {
                    pendingRegionLoads.offer(new PendingRegionLoad(coord, payload.caveLayer(), true, generation));
                }
            }
        } catch (Exception e) {
            cleanupIncomingPart(key, parts);
            LOGGER.error("Failed to complete region parts {}", payload.regionKey(), e);
        }
    }

    private static void cleanupIncomingPart(String key, IncomingRegionParts parts) {
        incomingRegionParts.remove(key);
        try {
            Files.deleteIfExists(parts.partFile);
        } catch (Exception e) {
            LOGGER.debug("Failed to delete part file {}", parts.partFile, e);
        }
    }

    private static void cleanupPartFiles() {
        for (Map.Entry<String, IncomingRegionParts> entry : incomingRegionParts.entrySet()) {
            cleanupIncomingPart(entry.getKey(), entry.getValue());
        }
        incomingRegionParts.clear();
    }

    private static void cleanupStalePartFiles(Path serverDir) {
        if (serverDir == null || !Files.exists(serverDir)) {
            return;
        }

        try (var stream = Files.walk(serverDir)) {
            stream.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().endsWith(".zip.part"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            LOGGER.debug("Failed to delete stale part file {}", path, e);
                        }
                    });
        } catch (Exception e) {
            LOGGER.debug("Failed to scan stale part files under {}", serverDir, e);
        }
    }

    private static void markSyncCompleteOnWorker(Path serverDir) {
        SYNC_WORKER.execute(() -> {
            ClientTimestampCache tsCache = getTimestampCacheOnWorker(serverDir);
            if (tsCache != null) {
                tsCache.markSyncComplete();
            }
        });
    }

    private static void clearSyncStateOnWorker(Path serverDir) {
        SYNC_WORKER.execute(() -> {
            ClientTimestampCache tsCache = getTimestampCacheOnWorker(serverDir);
            if (tsCache != null) {
                tsCache.clearSyncState();
            }
        });
    }

    public static void queueExternalRegionReloads(Set<XaeroMapIntegrator.RegionCoord> regions) {
        if (regions == null || regions.isEmpty()) {
            return;
        }
        long generation = syncGeneration.get();
        for (XaeroMapIntegrator.RegionCoord coord : regions) {
            if (coord != null) {
                externalRegionLoads.offer(new PendingRegionLoad(coord, coord.caveLayer(), true, generation));
            }
        }
        lastRegionReloadFlushMillis = 0;
    }

    private static void flushExternalRegionLoads() {
        long generation = syncGeneration.get();
        if (!externalRegionLoads.isEmpty() && !reflectionInitialized) {
            initializeReflectionCache();
        }
        if (externalRegionLoads.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (externalRegionLoads.size() < REGION_RELOAD_BATCH_SIZE
                && now - lastRegionReloadFlushMillis < REGION_RELOAD_BATCH_DELAY_MS) {
            return;
        }
        int processed = 0;
        while (processed < REGION_RELOAD_BATCH_SIZE) {
            PendingRegionLoad pending = externalRegionLoads.poll();
            if (pending == null) {
                break;
            }
            if (pending.generation() != generation) {
                continue;
            }
            triggerSingleRegionLoad(pending.coord(), pending.caveLayer(), pending.inViewDistance());
            processed++;
        }
        if (processed > 0) {
            lastRegionReloadFlushMillis = System.currentTimeMillis();
        }
    }

    public static void onClientTick(Minecraft client) {
        long generation = syncGeneration.get();
        if (pendingRegionLoads.isEmpty()) {
            flushExternalRegionLoads();
            tryCompleteSyncOnClient(generation);
            return;
        }

        long now = System.currentTimeMillis();
        boolean shouldFlush = completionPending
                || pendingRegionLoads.size() >= REGION_RELOAD_BATCH_SIZE
                || now - lastRegionReloadFlushMillis >= REGION_RELOAD_BATCH_DELAY_MS;
        if (!shouldFlush) {
            tryCompleteSyncOnClient(generation);
            return;
        }

        int processed = 0;
        while (processed < REGION_RELOAD_BATCH_SIZE) {
            PendingRegionLoad pending = pendingRegionLoads.poll();
            if (pending == null) {
                break;
            }
            if (pending.generation() != generation) {
                continue;
            }

            triggerSingleRegionLoad(pending.coord(), pending.caveLayer(), pending.inViewDistance());
            processed++;
        }
        if (processed > 0) {
            lastRegionReloadFlushMillis = now;
        }

        flushExternalRegionLoads();
        tryCompleteSyncOnClient(generation);
    }

    private static void tryCompleteSyncOnClient(long generation) {
        if (!completionPending || generation != syncGeneration.get() || !pendingRegionLoads.isEmpty()) {
            return;
        }

        int totalReceived = completionRegionCount;
        LOGGER.info("Sync complete: {} regions processed", totalReceived);

        if (!updatedRegionCoords.isEmpty()) {
            XaeroMapIntegrator.recordUpdatedRegionCoords(new HashSet<>(updatedRegionCoords));
        } else {
            LOGGER.info("Sync complete with no data received");
        }

        SyncProgressTracker.completeWithCount(totalReceived);
        resumeChunkUpdates();
        clearSyncState();
        clearReflectionCache();
    }

    /**
     * Clears the sync state, leaving the reflection cache alone.
     */
    private static void clearSyncState() {
        updatedRegionCoords.clear();
        loadedRegions.clear();
        pendingRegionLoads.clear();
        ClientTimestampCache.saveCurrent();
        cleanupPartFiles();
        cleanupStalePartFiles(XaeroMapIntegrator.getCurrentServerDirectory());
        cacheDirectoriesByMwDir.clear();
        completionPending = false;
        completionRegionCount = 0;
        lastMwDir = null;
        syncStartTime = 0;
    }

    /**
     * Clears the reflection cache.
     */
    private static void clearReflectionCache() {
        reflectionInitialized = false;
        cachedMapProcessor = null;
        cachedMapSaveLoad = null;
        cachedGetLeafMapRegion = null;
        cachedRequestLoad = null;
        cachedLoadStateField = null;
        cachedShouldCacheField = null;
        cachedSetHasHadTerrain = null;
        cachedCancelRefresh = null;
    }

    // ========== Loading regions as they arrive ==========

    /**
     * Sets up the reflection cache, once.
     * Called on the first packet of sync data.
     */
    private static void initializeReflectionCache() {
        if (reflectionInitialized) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // WorldMapSession
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Object session = worldMapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                LOGGER.warn("Cannot initialise the reflection cache: WorldMapSession is null");
                return;
            }

            // MapProcessor
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            cachedMapProcessor = worldMapSessionClass.getMethod("getMapProcessor").invoke(session);
            if (cachedMapProcessor == null) {
                LOGGER.warn("Cannot initialise the reflection cache: MapProcessor is null");
                return;
            }

            // MapSaveLoad
            Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            cachedMapSaveLoad = mapProcessorClass.getMethod("getMapSaveLoad").invoke(cachedMapProcessor);
            if (cachedMapSaveLoad == null) {
                LOGGER.warn("Cannot initialise the reflection cache: MapSaveLoad is null");
                return;
            }

            // Cache the methods and fields used repeatedly.
            cachedGetLeafMapRegion = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            cachedRequestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class, boolean.class);

            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            cachedLoadStateField = mapRegionClass.getDeclaredField("loadState");
            cachedLoadStateField.setAccessible(true);
            cachedCancelRefresh = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);
            cachedSetHasHadTerrain = mapRegionClass.getMethod("setHasHadTerrain");

            Class<?> leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion");
            cachedShouldCacheField = leveledRegionClass.getDeclaredField("shouldCache");
            cachedShouldCacheField.setAccessible(true);

            reflectionInitialized = true;

            // regionDetectionComplete has to be true, or getLeafMapRegion returns null.
            Method setRegionDetectionComplete = mapSaveLoadClass.getMethod("setRegionDetectionComplete", boolean.class);
            setRegionDetectionComplete.invoke(cachedMapSaveLoad, true);

            LOGGER.info("Reflection cache initialised, regionDetectionComplete=true");

        } catch (Exception e) {
            LOGGER.error("Failed to initialise the reflection cache", e);
        }
    }

    /**
     * Loads one region immediately.
     * Uses the cached reflection handles and sets shouldCache=true so a cache is written.
     *
     * Regions in view go through requestLoad(prioritize=true), which puts them at the front;
     * regions out of view are appended to the toLoad queue directly, bypassing loadingFiles.
     *
     * @param coord the region
     * @param caveLayer the cave layer, or {@code Integer.MAX_VALUE} for the surface
     * @param inViewDistance whether the region is within view distance
     */
    private static void triggerSingleRegionLoad(XaeroMapIntegrator.RegionCoord coord, int caveLayer, boolean inViewDistance) {
        if (!reflectionInitialized || cachedMapProcessor == null) {
            LOGGER.warn("Reflection cache not initialised; cannot load region ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
            return;
        }

        // Do not load the same region twice.
        if (loadedRegions.contains(coord)) {
            LOGGER.debug("Region ({}, {}) layer={} already loaded, skipping", coord.x(), coord.z(), caveLayer);
            return;
        }

        try {
            // Get or create the MapRegion, with the right caveLayer.
            Object mapRegion = cachedGetLeafMapRegion.invoke(cachedMapProcessor,
                caveLayer, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("Cannot create MapRegion ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            // Clear the refresh flag.
            cachedCancelRefresh.invoke(mapRegion, cachedMapProcessor);

            // shouldCache=true, so the load counts as complete.
            cachedShouldCacheField.setBoolean(mapRegion, true);

            // hasHadTerrain has to be true as well, or loadCacheTextures returns just the
            // metadata and skips the actual data.
            cachedSetHasHadTerrain.invoke(mapRegion);

            cachedLoadStateField.setByte(mapRegion, (byte) 4);
            cachedRequestLoad.invoke(cachedMapSaveLoad, mapRegion,
                    inViewDistance ? "sync view" : "sync deferred", inViewDistance);
            LOGGER.debug("Queued Xaero reload for region ({}, {}) layer={} inView={}",
                    coord.x(), coord.z(), caveLayer, inViewDistance);

            loadedRegions.add(coord);

        } catch (Exception e) {
            LOGGER.warn("Failed to load region ({}, {}) layer={}: {}", coord.x(), coord.z(), caveLayer, e.getMessage());
        }
    }

    /**
     * Deletes one region's cache file.
     * Called before loading a region, so the newest data is what gets read.
     *
     * @param coord the region
     */
    private static void clearSingleRegionCache(XaeroMapIntegrator.RegionCoord coord) {
        clearSingleRegionCache(coord, lastMwDir);
    }

    private static void clearSingleRegionCache(XaeroMapIntegrator.RegionCoord coord, Path mwDir) {
        if (mwDir == null) return;

        String cacheFileName = coord.x() + "_" + coord.z() + ".xwmc";
        List<Path> cacheDirs = cacheDirectoriesByMwDir.computeIfAbsent(mwDir, MapPacketReceiver::findCacheDirectories);

        for (Path cacheDir : cacheDirs) {
            Path cacheFile = cacheDir.resolve(cacheFileName);
            if (cacheFile.toFile().exists()) {
                try {
                    java.nio.file.Files.deleteIfExists(cacheFile);
                    LOGGER.debug("Cleared cache: {}", cacheFile);
                } catch (Exception e) {
                    LOGGER.warn("Failed to clear cache: {}", cacheFile);
                }
                return;
            }
        }
    }

    private record PendingRegionLoad(XaeroMapIntegrator.RegionCoord coord,
                                     int caveLayer,
                                     boolean inViewDistance,
                                     long generation) {
    }

    private static class IncomingRegionParts {
        final Path partFile;
        final Path outputFile;
        final int totalParts;
        final long totalBytes;
        final long timestampSeconds;
        final String hash;
        final ChunkMapData chunk;
        final BitSet received;
        long receivedBytes;

        IncomingRegionParts(Path partFile, Path outputFile, int totalParts, long totalBytes,
                long timestampSeconds, String hash, ChunkMapData chunk) {
            this.partFile = partFile;
            this.outputFile = outputFile;
            this.totalParts = totalParts;
            this.totalBytes = totalBytes;
            this.timestampSeconds = timestampSeconds;
            this.hash = hash;
            this.chunk = chunk;
            this.received = new BitSet(totalParts);
        }

        boolean matches(PacketHandler.SyncRegionPartPayload payload, XaeroMapIntegrator.RegionFileTarget target) {
            return totalParts == payload.totalParts()
                    && totalBytes == payload.totalBytes()
                    && timestampSeconds == payload.timestampSeconds()
                    && hash.equals(payload.hash())
                    && outputFile.equals(target.outputFile());
        }
    }

    /**
     * Builds the timestamp cache path in the form the server uses.
     *
     * @param chunk the region's data
     * @return the relative path
     */
    private static String buildRelativePathForCache(ChunkMapData chunk) {
        String xaeroDim = chunk.dimension;
        if (chunk.caveLayer == Integer.MAX_VALUE) {
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }

    /**
     * Finds every cache directory under an mw directory.
     * They are named cache, cache_1, cache_&lt;version&gt;.
     *
     * @param mwDir the mw directory
     * @return the cache directories
     */
    private static java.util.List<Path> findCacheDirectories(Path mwDir) {
        java.util.List<Path> cacheDirs = new java.util.ArrayList<>();

        try {
            // Standard cache directories
            Path cache = mwDir.resolve("cache");
            Path cache1 = mwDir.resolve("cache_1");

            if (cache.toFile().exists() && cache.toFile().isDirectory()) {
                cacheDirs.add(cache);
            }
            if (cache1.toFile().exists() && cache1.toFile().isDirectory()) {
                cacheDirs.add(cache1);
            }

            // Also check for versioned cache directories (cache_<number>)
            try (java.nio.file.DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(mwDir, "cache_*")) {
                for (Path dir : stream) {
                    if (dir.toFile().isDirectory() && !cacheDirs.contains(dir)) {
                        cacheDirs.add(dir);
                    }
                }
            }

            LOGGER.debug("Found {} cache directories", cacheDirs.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to find cache directories: {}", e.getMessage());
        }

        return cacheDirs;
    }

    /**
     * Unloads nearby regions so the server's data replaces what is on screen.
     * Called when syncing the dimension the player is currently in.
     *
     * @param targetDimension the dimension being synced, in Xaero's form
     */
    public static void prepareSyncForDimension(String targetDimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // Only when syncing the dimension the player is in.
        String currentXaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(
                mc.level.dimension().identifier().toString());

        if (targetDimension.equals(currentXaeroDim)) {
        // Unload the regions within view.
            LOGGER.info("Syncing current dimension {}, unloading view distance regions", targetDimension);
            int unloaded = XaeroMapIntegrator.unloadViewDistanceRegions();
            if (unloaded > 0 && mc.player != null) {
                mc.player.sendSystemMessage(
                        ChatUtils.desc("mapsyncer.sync.unloading_regions", unloaded));
            }
        }
    }
}
