package com.mapsyncer.network;

import com.mapsyncer.MapSyncer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every packet MapSyncer sends between client and server.
 *
 * <p>Defines the channel IDs and the payload records, each with its own encoder and
 * decoder. The byte layouts here are the protocol: the Fabric server and the Paper
 * plugin both produce exactly these bytes.</p>
 *
 * <p>The core three:</p>
 * <ul>
 *   <li>{@link SyncRequestPayload} - client to server, the metadata of what it already has</li>
 *   <li>{@link SyncResponsePayload} - server to client, the map data it is missing</li>
 *   <li>{@link SyncProgressPayload} - server to client, how far along the sync is</li>
 * </ul>
 */
public class PacketHandler {

    private static final int MAX_CLIENT_META_ENTRIES = 200_000;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_DIMENSION_LENGTH = 256;
    private static final int MAX_HASH_LENGTH = 64;
    private static final int MAX_PART_DATA_LENGTH = 1_000_000;
    private static final int MAX_WAYPOINTS = 10_000;
    private static final int MAX_WAYPOINT_FIELD_LENGTH = 256;

    /** Channel: sync request. */
    public static final Identifier SYNC_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_request");
    /** Channel: sync response. */
    public static final Identifier SYNC_RESPONSE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_response");
    /** Channel: sync progress. */
    public static final Identifier SYNC_PROGRESS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_progress");
    public static final Identifier SYNC_REGION_PART_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_region_part");
    public static final Identifier SYNC_REGION_COMPLETE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "sync_region_complete");
    public static final Identifier RADIUS_SYNC_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "radius_sync_request");
    public static final Identifier PUBLIC_WAYPOINTS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "public_waypoints");
    public static final Identifier PUBLIC_WAYPOINTS_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "public_waypoints_request");
    public static final Identifier PUBLIC_WAYPOINT_ADD_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "public_waypoint_add");
    public static final Identifier PUBLIC_WAYPOINT_ADD_RESULT_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "public_waypoint_add_result");

    /** Channel: "this server has MapSyncer". */
    public static final Identifier SERVER_INSTALLED_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "server_installed");
    public static final Identifier ADMIN_STATUS_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "admin_status_request");
    public static final Identifier ADMIN_STATUS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "admin_status");
    public static final Identifier ADMIN_SETTINGS_UPDATE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "admin_settings_update");
    public static final Identifier OPEN_GUI_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "open_gui");
    public static final Identifier VOXY_CAPABILITY_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_capability_request");
    public static final Identifier VOXY_CAPABILITY_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_capability");
    public static final Identifier VOXY_SYNC_REQUEST_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_sync_request");
    public static final Identifier VOXY_SYNC_START_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_sync_start");
    public static final Identifier VOXY_REGION_PART_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_region_part");
    public static final Identifier VOXY_SYNC_PROGRESS_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_sync_progress");
    public static final Identifier VOXY_SYNC_COMPLETE_ID = Identifier.fromNamespaceAndPath(
            MapSyncer.MOD_ID, "voxy_sync_complete");

    /**
     * Sync request: the client's per-region metadata, a timestamp and a hash each.
     *
     * <p>Tells the server what map data the client already holds, so the server can work
     * out what is missing.</p>
     *
     * @param clientMeta region path to the client's timestamp and hash for it
     */
    public record SyncRequestPayload(Map<String, ClientMeta> clientMeta) implements CustomPacketPayload {
        /** The payload type. */
        public static final Type<SyncRequestPayload> TYPE = new Type<>(SYNC_REQUEST_ID);
        /** Stream codec used to read and write this payload. */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequestPayload> STREAM_CODEC = StreamCodec.of(
                SyncRequestPayload::encode, SyncRequestPayload::decode
        );

        /**
         * Writes a sync request to a network buffer.
         *
         * @param buf     the network buffer
         * @param payload the request to write
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncRequestPayload payload) {
            buf.writeInt(payload.clientMeta.size());
            for (var entry : payload.clientMeta.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
        }

        /**
         * Reads a sync request from a network buffer.
         *
         * @param buf the network buffer
         * @return the decoded request
         */
        public static SyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readInt();
            if (size < 0 || size > MAX_CLIENT_META_ENTRIES) {
                throw new IllegalArgumentException("Invalid sync metadata count: " + size);
            }
            Map<String, ClientMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String path = buf.readUtf(MAX_PATH_LENGTH);
                long timestampSeconds = buf.readLong();
                String hash = buf.readUtf(MAX_HASH_LENGTH);
                metaMap.put(path, new ClientMeta(timestampSeconds, hash));
            }
            return new SyncRequestPayload(metaMap);
        }

        /**
         * The payload type.
         *
         * @return the payload type
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RadiusSyncRequestPayload(
            Map<String, ClientMeta> clientMeta,
            String dimensionId,
            int radiusBlocks,
            int playerX,
            int playerY,
            int playerZ
    ) implements CustomPacketPayload {
        public static final Type<RadiusSyncRequestPayload> TYPE = new Type<>(RADIUS_SYNC_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, RadiusSyncRequestPayload> STREAM_CODEC = StreamCodec.of(
                RadiusSyncRequestPayload::encode, RadiusSyncRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, RadiusSyncRequestPayload payload) {
            buf.writeInt(payload.clientMeta.size());
            for (var entry : payload.clientMeta.entrySet()) {
                buf.writeUtf(entry.getKey());
                buf.writeLong(entry.getValue().timestampSeconds());
                buf.writeUtf(entry.getValue().hash());
            }
            buf.writeUtf(payload.dimensionId);
            buf.writeInt(payload.radiusBlocks);
            buf.writeInt(payload.playerX);
            buf.writeInt(payload.playerY);
            buf.writeInt(payload.playerZ);
        }

        public static RadiusSyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            int size = buf.readInt();
            if (size < 0 || size > MAX_CLIENT_META_ENTRIES) {
                throw new IllegalArgumentException("Invalid radius sync metadata count: " + size);
            }
            Map<String, ClientMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                String path = buf.readUtf(MAX_PATH_LENGTH);
                long timestampSeconds = buf.readLong();
                String hash = buf.readUtf(MAX_HASH_LENGTH);
                metaMap.put(path, new ClientMeta(timestampSeconds, hash));
            }
            return new RadiusSyncRequestPayload(
                    metaMap,
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Sync response: map data the client needs.
     *
     * <p>Carries the data for one or more regions and says whether this is the last
     * response of the sync.</p>
     *
     * @param chunks     the regions being sent
     * @param isComplete {@code true} when this is the last response
     * @param worldId    world ID, so the client knows which world this belongs to
     * @param status     "ok" = data follows, "uptodate" = nothing to send, "no_cache" = server has no cache, "dim_not_available" = no such dimension
     */
    public record SyncResponsePayload(List<ChunkMapData> chunks, boolean isComplete, int worldId, String status) implements CustomPacketPayload {
        /** The payload type. */
        public static final Type<SyncResponsePayload> TYPE = new Type<>(SYNC_RESPONSE_ID);
        /** Stream codec used to read and write this payload. */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncResponsePayload> STREAM_CODEC = StreamCodec.of(
                SyncResponsePayload::encode, SyncResponsePayload::decode
        );

        /**
         * Writes a sync response to a network buffer.
         *
         * @param buf     the network buffer
         * @param payload the response to write
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncResponsePayload payload) {
            buf.writeInt(payload.worldId);
            buf.writeInt(payload.chunks.size());
            for (ChunkMapData data : payload.chunks) {
                ChunkMapData.encode(buf, data);
            }
            buf.writeBoolean(payload.isComplete);
            buf.writeUtf(payload.status);
        }

        /**
         * Reads a sync response from a network buffer.
         *
         * @param buf the network buffer
         * @return the decoded response
         */
        public static SyncResponsePayload decode(RegistryFriendlyByteBuf buf) {
            int worldId = buf.readInt();
            int size = buf.readInt();
            List<ChunkMapData> chunks = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chunks.add(ChunkMapData.decode(buf));
            }
            boolean isComplete = buf.readBoolean();
            String status = buf.readUtf();
            return new SyncResponsePayload(chunks, isComplete, worldId, status);
        }

        /**
         * The payload type.
         *
         * @return the payload type
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Sync progress: how far the current sync has got.
     *
     * <p>Lets the client show a progress bar or status text.</p>
     *
     * @param processed regions handled so far
     * @param total     regions in total
     * @param status    a short description of the current step
     */
    public record SyncProgressPayload(int processed, int total, String status) implements CustomPacketPayload {
        /** The payload type. */
        public static final Type<SyncProgressPayload> TYPE = new Type<>(SYNC_PROGRESS_ID);
        /** Stream codec used to read and write this payload. */
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncProgressPayload> STREAM_CODEC = StreamCodec.of(
                SyncProgressPayload::encode, SyncProgressPayload::decode
        );

        /**
         * Writes a progress update to a network buffer.
         *
         * @param buf     the network buffer
         * @param payload the update to write
         */
        public static void encode(RegistryFriendlyByteBuf buf, SyncProgressPayload payload) {
            buf.writeInt(payload.processed);
            buf.writeInt(payload.total);
            buf.writeUtf(payload.status);
        }

        /**
         * Reads a progress update from a network buffer.
         *
         * @param buf the network buffer
         * @return the decoded update
         */
        public static SyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new SyncProgressPayload(buf.readInt(), buf.readInt(), buf.readUtf());
        }

        /**
         * The payload type.
         *
         * @return the payload type
         */
        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Sent to a client when it joins, to say this server has MapSyncer.
     *
     * <p>Lets the client know up front that syncing is available here.</p>
     *
     * @param version the server's mod or plugin version
     */
    public record SyncRegionPartPayload(
            String syncId,
            int worldId,
            String regionKey,
            String dimension,
            int regionX,
            int regionZ,
            int caveLayer,
            int partIndex,
            int totalParts,
            long byteOffset,
            long totalBytes,
            long timestampSeconds,
            String hash,
            byte[] data
    ) implements CustomPacketPayload {
        public static final Type<SyncRegionPartPayload> TYPE = new Type<>(SYNC_REGION_PART_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRegionPartPayload> STREAM_CODEC = StreamCodec.of(
                SyncRegionPartPayload::encode, SyncRegionPartPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, SyncRegionPartPayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeInt(payload.worldId);
            buf.writeUtf(payload.regionKey);
            buf.writeUtf(payload.dimension);
            buf.writeInt(payload.regionX);
            buf.writeInt(payload.regionZ);
            buf.writeInt(payload.caveLayer);
            buf.writeInt(payload.partIndex);
            buf.writeInt(payload.totalParts);
            buf.writeLong(payload.byteOffset);
            buf.writeLong(payload.totalBytes);
            buf.writeLong(payload.timestampSeconds);
            buf.writeUtf(payload.hash);
            buf.writeByteArray(payload.data);
        }

        public static SyncRegionPartPayload decode(RegistryFriendlyByteBuf buf) {
            return new SyncRegionPartPayload(
                    buf.readUtf(MAX_PATH_LENGTH),
                    buf.readInt(),
                    buf.readUtf(MAX_PATH_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readUtf(MAX_HASH_LENGTH),
                    buf.readByteArray(MAX_PART_DATA_LENGTH)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncRegionCompletePayload(
            String syncId,
            int worldId,
            String regionKey,
            String dimension,
            int regionX,
            int regionZ,
            int caveLayer,
            int totalParts,
            long totalBytes,
            long timestampSeconds,
            String hash
    ) implements CustomPacketPayload {
        public static final Type<SyncRegionCompletePayload> TYPE = new Type<>(SYNC_REGION_COMPLETE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRegionCompletePayload> STREAM_CODEC = StreamCodec.of(
                SyncRegionCompletePayload::encode, SyncRegionCompletePayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, SyncRegionCompletePayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeInt(payload.worldId);
            buf.writeUtf(payload.regionKey);
            buf.writeUtf(payload.dimension);
            buf.writeInt(payload.regionX);
            buf.writeInt(payload.regionZ);
            buf.writeInt(payload.caveLayer);
            buf.writeInt(payload.totalParts);
            buf.writeLong(payload.totalBytes);
            buf.writeLong(payload.timestampSeconds);
            buf.writeUtf(payload.hash);
        }

        public static SyncRegionCompletePayload decode(RegistryFriendlyByteBuf buf) {
            return new SyncRegionCompletePayload(
                    buf.readUtf(MAX_PATH_LENGTH),
                    buf.readInt(),
                    buf.readUtf(MAX_PATH_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readUtf(MAX_HASH_LENGTH)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ServerInstalledPayload(String version) implements CustomPacketPayload {
        /** The payload type. */
        public static final Type<ServerInstalledPayload> TYPE = new Type<>(SERVER_INSTALLED_ID);
        /** Stream codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, ServerInstalledPayload> STREAM_CODEC = StreamCodec.of(
                ServerInstalledPayload::encode, ServerInstalledPayload::decode
        );

        /**
         * Writes this payload to a network buffer.
         */
        public static void encode(RegistryFriendlyByteBuf buf, ServerInstalledPayload payload) {
            buf.writeUtf(payload.version);
        }

        /**
         * Reads this payload from a network buffer.
         */
        public static ServerInstalledPayload decode(RegistryFriendlyByteBuf buf) {
            return new ServerInstalledPayload(buf.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AdminStatusRequestPayload() implements CustomPacketPayload {
        public static final Type<AdminStatusRequestPayload> TYPE = new Type<>(ADMIN_STATUS_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminStatusRequestPayload> STREAM_CODEC = StreamCodec.of(
                AdminStatusRequestPayload::encode, AdminStatusRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, AdminStatusRequestPayload payload) {
        }

        public static AdminStatusRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new AdminStatusRequestPayload();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AdminStatusPayload(
            boolean allowed,
            boolean running,
            int processed,
            int total,
            int updated,
            int skipped,
            int dirtyCount,
            int cacheDimensionCount,
            int cacheRegionCount,
            long cacheSizeBytes,
            int syncSpeedLimitKBps,
            boolean radiusSyncEnabled,
            int maxRadiusSyncBlocks,
            String radiusSyncCenterMode,
            String radiusSyncFixedDimension,
            int radiusSyncFixedX,
            int radiusSyncFixedY,
            int radiusSyncFixedZ,
            boolean publicWaypointsEnabled,
            String publicWaypointsGroup,
            int publicWaypointsCount,
            String publicWaypointsHash,
            String status,
            String currentDimension,
            String incrementalStatus
    ) implements CustomPacketPayload {
        public static final Type<AdminStatusPayload> TYPE = new Type<>(ADMIN_STATUS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminStatusPayload> STREAM_CODEC = StreamCodec.of(
                AdminStatusPayload::encode, AdminStatusPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, AdminStatusPayload payload) {
            buf.writeBoolean(payload.allowed);
            buf.writeBoolean(payload.running);
            buf.writeInt(payload.processed);
            buf.writeInt(payload.total);
            buf.writeInt(payload.updated);
            buf.writeInt(payload.skipped);
            buf.writeInt(payload.dirtyCount);
            buf.writeInt(payload.cacheDimensionCount);
            buf.writeInt(payload.cacheRegionCount);
            buf.writeLong(payload.cacheSizeBytes);
            buf.writeInt(payload.syncSpeedLimitKBps);
            buf.writeBoolean(payload.radiusSyncEnabled);
            buf.writeInt(payload.maxRadiusSyncBlocks);
            buf.writeUtf(payload.radiusSyncCenterMode);
            buf.writeUtf(payload.radiusSyncFixedDimension);
            buf.writeInt(payload.radiusSyncFixedX);
            buf.writeInt(payload.radiusSyncFixedY);
            buf.writeInt(payload.radiusSyncFixedZ);
            buf.writeBoolean(payload.publicWaypointsEnabled);
            buf.writeUtf(payload.publicWaypointsGroup);
            buf.writeInt(payload.publicWaypointsCount);
            buf.writeUtf(payload.publicWaypointsHash);
            buf.writeUtf(payload.status);
            buf.writeUtf(payload.currentDimension);
            buf.writeUtf(payload.incrementalStatus);
        }

        public static AdminStatusPayload decode(RegistryFriendlyByteBuf buf) {
            return new AdminStatusPayload(
                    buf.readBoolean(),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readInt(),
                    buf.readUtf(MAX_HASH_LENGTH),
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readUtf()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record AdminSettingsUpdatePayload(
            boolean radiusSyncEnabled,
            int maxRadiusSyncBlocks,
            String radiusSyncCenterMode,
            String radiusSyncFixedDimension,
            int radiusSyncFixedX,
            int radiusSyncFixedY,
            int radiusSyncFixedZ
    ) implements CustomPacketPayload {
        public static final Type<AdminSettingsUpdatePayload> TYPE = new Type<>(ADMIN_SETTINGS_UPDATE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, AdminSettingsUpdatePayload> STREAM_CODEC = StreamCodec.of(
                AdminSettingsUpdatePayload::encode, AdminSettingsUpdatePayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, AdminSettingsUpdatePayload payload) {
            buf.writeBoolean(payload.radiusSyncEnabled);
            buf.writeInt(payload.maxRadiusSyncBlocks);
            buf.writeUtf(payload.radiusSyncCenterMode);
            buf.writeUtf(payload.radiusSyncFixedDimension);
            buf.writeInt(payload.radiusSyncFixedX);
            buf.writeInt(payload.radiusSyncFixedY);
            buf.writeInt(payload.radiusSyncFixedZ);
        }

        public static AdminSettingsUpdatePayload decode(RegistryFriendlyByteBuf buf) {
            return new AdminSettingsUpdatePayload(
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OpenGuiPayload() implements CustomPacketPayload {
        public static final Type<OpenGuiPayload> TYPE = new Type<>(OPEN_GUI_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenGuiPayload> STREAM_CODEC = StreamCodec.of(
                OpenGuiPayload::encode, OpenGuiPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, OpenGuiPayload payload) {
        }

        public static OpenGuiPayload decode(RegistryFriendlyByteBuf buf) {
            return new OpenGuiPayload();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PublicWaypoint(
            String name,
            String initial,
            int x,
            int y,
            int z,
            int color,
            boolean disabled,
            String type,
            String set,
            String dimension
    ) {
        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypoint waypoint) {
            buf.writeUtf(waypoint.name);
            buf.writeUtf(waypoint.initial);
            buf.writeInt(waypoint.x);
            buf.writeInt(waypoint.y);
            buf.writeInt(waypoint.z);
            buf.writeInt(waypoint.color);
            buf.writeBoolean(waypoint.disabled);
            buf.writeUtf(waypoint.type);
            buf.writeUtf(waypoint.set);
            buf.writeUtf(waypoint.dimension);
        }

        public static PublicWaypoint decode(RegistryFriendlyByteBuf buf) {
            return new PublicWaypoint(
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(16),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_DIMENSION_LENGTH)
            );
        }
    }

    public record PublicWaypointsPayload(
            String groupName,
            boolean replaceGroup,
            String hash,
            List<PublicWaypoint> waypoints
    ) implements CustomPacketPayload {
        public static final Type<PublicWaypointsPayload> TYPE = new Type<>(PUBLIC_WAYPOINTS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PublicWaypointsPayload> STREAM_CODEC = StreamCodec.of(
                PublicWaypointsPayload::encode, PublicWaypointsPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypointsPayload payload) {
            buf.writeUtf(payload.groupName);
            buf.writeBoolean(payload.replaceGroup);
            buf.writeUtf(payload.hash);
            buf.writeInt(payload.waypoints.size());
            for (PublicWaypoint waypoint : payload.waypoints) {
                PublicWaypoint.encode(buf, waypoint);
            }
        }

        public static PublicWaypointsPayload decode(RegistryFriendlyByteBuf buf) {
            String groupName = buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH);
            boolean replaceGroup = buf.readBoolean();
            String hash = buf.readUtf(MAX_HASH_LENGTH);
            int size = buf.readInt();
            if (size < 0 || size > MAX_WAYPOINTS) {
                throw new IllegalArgumentException("Invalid public waypoint count: " + size);
            }
            List<PublicWaypoint> waypoints = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                waypoints.add(PublicWaypoint.decode(buf));
            }
            return new PublicWaypointsPayload(groupName, replaceGroup, hash, waypoints);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PublicWaypointsRequestPayload() implements CustomPacketPayload {
        public static final Type<PublicWaypointsRequestPayload> TYPE = new Type<>(PUBLIC_WAYPOINTS_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PublicWaypointsRequestPayload> STREAM_CODEC = StreamCodec.of(
                PublicWaypointsRequestPayload::encode, PublicWaypointsRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypointsRequestPayload payload) {
        }

        public static PublicWaypointsRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new PublicWaypointsRequestPayload();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PublicWaypointAddPayload(PublicWaypoint waypoint) implements CustomPacketPayload {
        public static final Type<PublicWaypointAddPayload> TYPE = new Type<>(PUBLIC_WAYPOINT_ADD_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PublicWaypointAddPayload> STREAM_CODEC = StreamCodec.of(
                PublicWaypointAddPayload::encode, PublicWaypointAddPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypointAddPayload payload) {
            PublicWaypoint.encode(buf, payload.waypoint);
        }

        public static PublicWaypointAddPayload decode(RegistryFriendlyByteBuf buf) {
            return new PublicWaypointAddPayload(PublicWaypoint.decode(buf));
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PublicWaypointAddResultPayload(String status, String name) implements CustomPacketPayload {
        public static final Type<PublicWaypointAddResultPayload> TYPE = new Type<>(PUBLIC_WAYPOINT_ADD_RESULT_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, PublicWaypointAddResultPayload> STREAM_CODEC = StreamCodec.of(
                PublicWaypointAddResultPayload::encode, PublicWaypointAddResultPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, PublicWaypointAddResultPayload payload) {
            buf.writeUtf(payload.status);
            buf.writeUtf(payload.name);
        }

        public static PublicWaypointAddResultPayload decode(RegistryFriendlyByteBuf buf) {
            return new PublicWaypointAddResultPayload(
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH),
                    buf.readUtf(MAX_WAYPOINT_FIELD_LENGTH)
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxyCapabilityRequestPayload() implements CustomPacketPayload {
        public static final Type<VoxyCapabilityRequestPayload> TYPE = new Type<>(VOXY_CAPABILITY_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxyCapabilityRequestPayload> STREAM_CODEC = StreamCodec.of(
                VoxyCapabilityRequestPayload::encode, VoxyCapabilityRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxyCapabilityRequestPayload payload) {
        }

        public static VoxyCapabilityRequestPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxyCapabilityRequestPayload();
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxyCapabilityPayload(boolean enabled, String reason) implements CustomPacketPayload {
        public static final Type<VoxyCapabilityPayload> TYPE = new Type<>(VOXY_CAPABILITY_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxyCapabilityPayload> STREAM_CODEC = StreamCodec.of(
                VoxyCapabilityPayload::encode, VoxyCapabilityPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxyCapabilityPayload payload) {
            buf.writeBoolean(payload.enabled);
            buf.writeUtf(payload.reason);
        }

        public static VoxyCapabilityPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxyCapabilityPayload(buf.readBoolean(), buf.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxyRegionMeta(long timestampSeconds, long sizeBytes) {
        public static void encode(RegistryFriendlyByteBuf buf, VoxyRegionMeta meta) {
            buf.writeLong(meta.timestampSeconds);
            buf.writeLong(meta.sizeBytes);
        }

        public static VoxyRegionMeta decode(RegistryFriendlyByteBuf buf) {
            return new VoxyRegionMeta(buf.readLong(), buf.readLong());
        }
    }

    public record VoxySyncRequestPayload(String dimensionId, Map<String, VoxyRegionMeta> clientMeta) implements CustomPacketPayload {
        public static final Type<VoxySyncRequestPayload> TYPE = new Type<>(VOXY_SYNC_REQUEST_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxySyncRequestPayload> STREAM_CODEC = StreamCodec.of(
                VoxySyncRequestPayload::encode, VoxySyncRequestPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxySyncRequestPayload payload) {
            buf.writeUtf(payload.dimensionId);
            buf.writeInt(payload.clientMeta.size());
            for (var entry : payload.clientMeta.entrySet()) {
                buf.writeUtf(entry.getKey());
                VoxyRegionMeta.encode(buf, entry.getValue());
            }
        }

        public static VoxySyncRequestPayload decode(RegistryFriendlyByteBuf buf) {
            String dimensionId = buf.readUtf();
            int size = buf.readInt();
            Map<String, VoxyRegionMeta> metaMap = new HashMap<>();
            for (int i = 0; i < size; i++) {
                metaMap.put(buf.readUtf(), VoxyRegionMeta.decode(buf));
            }
            return new VoxySyncRequestPayload(dimensionId, metaMap);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxySyncStartPayload(String syncId, String dimensionId, int totalRegions, long totalBytes) implements CustomPacketPayload {
        public static final Type<VoxySyncStartPayload> TYPE = new Type<>(VOXY_SYNC_START_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxySyncStartPayload> STREAM_CODEC = StreamCodec.of(
                VoxySyncStartPayload::encode, VoxySyncStartPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxySyncStartPayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeUtf(payload.dimensionId);
            buf.writeInt(payload.totalRegions);
            buf.writeLong(payload.totalBytes);
        }

        public static VoxySyncStartPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxySyncStartPayload(buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readLong());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxyRegionPartPayload(String syncId, String dimensionId, int regionX, int regionZ,
                                        int partIndex, int totalParts, long byteOffset, long totalBytes,
                                        long timestampSeconds, byte[] data) implements CustomPacketPayload {
        public static final Type<VoxyRegionPartPayload> TYPE = new Type<>(VOXY_REGION_PART_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxyRegionPartPayload> STREAM_CODEC = StreamCodec.of(
                VoxyRegionPartPayload::encode, VoxyRegionPartPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxyRegionPartPayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeUtf(payload.dimensionId);
            buf.writeInt(payload.regionX);
            buf.writeInt(payload.regionZ);
            buf.writeInt(payload.partIndex);
            buf.writeInt(payload.totalParts);
            buf.writeLong(payload.byteOffset);
            buf.writeLong(payload.totalBytes);
            buf.writeLong(payload.timestampSeconds);
            buf.writeByteArray(payload.data);
        }

        public static VoxyRegionPartPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxyRegionPartPayload(
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readLong(),
                    buf.readByteArray()
            );
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxySyncProgressPayload(String syncId, int processedRegions, int totalRegions,
                                          long processedBytes, long totalBytes, String status) implements CustomPacketPayload {
        public static final Type<VoxySyncProgressPayload> TYPE = new Type<>(VOXY_SYNC_PROGRESS_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxySyncProgressPayload> STREAM_CODEC = StreamCodec.of(
                VoxySyncProgressPayload::encode, VoxySyncProgressPayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxySyncProgressPayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeInt(payload.processedRegions);
            buf.writeInt(payload.totalRegions);
            buf.writeLong(payload.processedBytes);
            buf.writeLong(payload.totalBytes);
            buf.writeUtf(payload.status);
        }

        public static VoxySyncProgressPayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxySyncProgressPayload(buf.readUtf(), buf.readInt(), buf.readInt(),
                    buf.readLong(), buf.readLong(), buf.readUtf());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record VoxySyncCompletePayload(String syncId, boolean success, String message,
                                          int transferredRegions, long transferredBytes) implements CustomPacketPayload {
        public static final Type<VoxySyncCompletePayload> TYPE = new Type<>(VOXY_SYNC_COMPLETE_ID);
        public static final StreamCodec<RegistryFriendlyByteBuf, VoxySyncCompletePayload> STREAM_CODEC = StreamCodec.of(
                VoxySyncCompletePayload::encode, VoxySyncCompletePayload::decode
        );

        public static void encode(RegistryFriendlyByteBuf buf, VoxySyncCompletePayload payload) {
            buf.writeUtf(payload.syncId);
            buf.writeBoolean(payload.success);
            buf.writeUtf(payload.message);
            buf.writeInt(payload.transferredRegions);
            buf.writeLong(payload.transferredBytes);
        }

        public static VoxySyncCompletePayload decode(RegistryFriendlyByteBuf buf) {
            return new VoxySyncCompletePayload(buf.readUtf(), buf.readBoolean(), buf.readUtf(),
                    buf.readInt(), buf.readLong());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
