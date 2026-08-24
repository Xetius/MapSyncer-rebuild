package com.mapsyncer.network;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * One region's map data, as sent from server to client.
 *
 * <p>Carries the converted map data for a single region plus the metadata the client
 * needs to file it: surface layer and cave layers are both sent this way.</p>
 *
 * <p>About {@code caveLayer}:</p>
 * <ul>
 *   <li>{@code Integer.MAX_VALUE}: the surface layer (the default)</li>
 *   <li>anything else: a cave layer, stored under {@code caves/<caveLayer>/...}</li>
 *   <li>the layer number is {@code caveStart >> 4}, i.e. divided by 16</li>
 * </ul>
 *
 * @see PacketHandler.SyncResponsePayload
 */
public class ChunkMapData {

    /** Region X coordinate, in regions. */
    public final int regionX;
    /** Region Z coordinate, in regions. */
    public final int regionZ;
    /** Dimension ID, e.g. {@code "minecraft:overworld"}. */
    public final String dimension;
    /** The map data: the compressed contents of the region file. */
    public final byte[] data;
    /** When the server generated this, in seconds, so the client can spot stale data. */
    public final long timestampSeconds;
    /** Cave layer number; {@code Integer.MAX_VALUE} means the surface layer. */
    public final int caveLayer;

    /**
     * Convenience constructor: surface layer, timestamp 0.
     *
     * @param regionX   region X coordinate
     * @param regionZ   region Z coordinate
     * @param dimension dimension ID
     * @param data      the map data
     */
    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data) {
        this(regionX, regionZ, dimension, data, 0, Integer.MAX_VALUE);
    }

    /**
     * Convenience constructor: surface layer.
     *
     * @param regionX           region X coordinate
     * @param regionZ           region Z coordinate
     * @param dimension         dimension ID
     * @param data              the map data
     * @param timestampSeconds  when the server generated it, in seconds
     */
    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data, long timestampSeconds) {
        this(regionX, regionZ, dimension, data, timestampSeconds, Integer.MAX_VALUE);
    }

    /**
     * Full constructor.
     *
     * @param regionX           region X coordinate
     * @param regionZ           region Z coordinate
     * @param dimension         dimension ID
     * @param data              the map data
     * @param timestampSeconds  when the server generated it, in seconds
     * @param caveLayer         cave layer number; {@code Integer.MAX_VALUE} for the surface
     */
    public ChunkMapData(int regionX, int regionZ, String dimension, byte[] data,
                         long timestampSeconds, int caveLayer) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.dimension = dimension;
        this.data = data;
        this.timestampSeconds = timestampSeconds;
        this.caveLayer = caveLayer;
    }

    /**
     * Whether this is the surface layer.
     *
     * @return {@code true} if {@code caveLayer} is {@code Integer.MAX_VALUE}
     */
    public boolean isSurfaceLayer() {
        return caveLayer == Integer.MAX_VALUE;
    }

    /**
     * Writes this to a network buffer.
     *
     * <p>A flag byte keeps the format backwards compatible:</p>
     * <ul>
     *   <li>the base fields first (regionX, regionZ, dimension, data, timestampSeconds)</li>
     *   <li>then a flag saying whether a cave layer follows</li>
     *   <li>the cave layer number only when this is not the surface layer</li>
     * </ul>
     *
     * @param buf  the network buffer
     * @param data the map data to write
     */
    public static void encode(RegistryFriendlyByteBuf buf, ChunkMapData data) {
        buf.writeInt(data.regionX);
        buf.writeInt(data.regionZ);
        buf.writeUtf(data.dimension);
        buf.writeByteArray(data.data);
        buf.writeLong(data.timestampSeconds);

        // Flag byte, for backwards compatibility.
        boolean hasCaveLayer = data.caveLayer != Integer.MAX_VALUE;
        buf.writeBoolean(hasCaveLayer);
        if (hasCaveLayer) {
            buf.writeInt(data.caveLayer);
        }
    }

    /**
     * Reads one of these from a network buffer.
     *
     * <p>Backwards compatible:</p>
     * <ul>
     *   <li>reads the flag to see whether a cave layer follows</li>
     *   <li>with no flag, or a false one, assumes the surface layer</li>
     * </ul>
     *
     * @param buf the network buffer
     * @return the decoded map data
     */
    public static ChunkMapData decode(RegistryFriendlyByteBuf buf) {
        int regionX = buf.readInt();
        int regionZ = buf.readInt();
        String dimension = buf.readUtf();
        byte[] data = buf.readByteArray();
        long timestampSeconds = buf.readLong();

        // Cave layer, if this sender wrote one.
        int caveLayer = Integer.MAX_VALUE;
        if (buf.isReadable()) {
            boolean hasCaveLayer = buf.readBoolean();
            if (hasCaveLayer) {
                caveLayer = buf.readInt();
            }
        }

        return new ChunkMapData(regionX, regionZ, dimension, data, timestampSeconds, caveLayer);
    }
}