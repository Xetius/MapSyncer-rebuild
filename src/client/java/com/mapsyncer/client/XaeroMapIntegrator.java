package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.util.HashUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The bridge to Xaero's World Map.
 * Writes synced map data into Xaero's directories and works out where those are.
 *
 * <p>What it does:</p>
 * <ul>
 *   <li>finds the current server's map directory</li>
 *   <li>writes the data the server sends into it</li>
 *   <li>tracks which regions a sync touched</li>
 *   <li>resets Xaero's region state so the new data is picked up</li>
 * </ul>
 *
 * <p>Directory layout:</p>
 * <ul>
 *   <li>multiplayer: xaero/world-map/Multiplayer_&lt;serverIP&gt;/&lt;dimension&gt;/mw$&lt;worldId&gt;/</li>
 *   <li>singleplayer: xaero/world-map/Multiplayer_Singleplayer/&lt;dimension&gt;/mw$&lt;worldId&gt;/</li>
 *   <li>LAN: xaero/world-map/Multiplayer_LAN/&lt;dimension&gt;/mw$&lt;worldId&gt;/</li>
 * </ul>
 */
public class XaeroMapIntegrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(XaeroMapIntegrator.class);
    public static final String DEFAULT_WORLD_ID = "default";
    public static final String DEFAULT_MW_DIR_NAME = "mw$" + DEFAULT_WORLD_ID;

    /** Regions this sync updated, so only those need resetting. */
    private static volatile Set<RegionCoord> updatedRegions = new HashSet<>();

    /** Regions unloaded before the sync that had been loaded, so they can be marked loadState=4 after. */
    private static volatile Set<RegionCoord> preUnloadedRegions = new HashSet<>();

    /**
     * The regions this sync updated.
     *
     * @return a copy of the set
     */
    public static Set<RegionCoord> getUpdatedRegions() {
        return new HashSet<>(updatedRegions);
    }

    /**
     * The regions unloaded before the sync that had been loaded.
     * Those want loadState=4 (reload me) afterwards rather than loadState=0 (never loaded).
     *
     * @return a copy of the set
     */
    public static Set<RegionCoord> getPreUnloadedRegions() {
        return new HashSet<>(preUnloadedRegions);
    }

    /**
     * Clears the pre-unloaded region set.
     */
    public static void clearPreUnloadedRegions() {
        preUnloadedRegions.clear();
    }

    /**
     * Clears both region sets.
     * Called when a sync finishes and when leaving a server.
     */
    public static void clearRegionTracking() {
        updatedRegions.clear();
        preUnloadedRegions.clear();
        LOGGER.debug("Cleared region tracking sets");
    }

    /**
     * A region's coordinates, as tracked during a sync.
     * Includes the cave layer, so surface and cave layers stay distinct.
     *
     * @param x region X coordinate
     * @param z region Z coordinate
     * @param caveLayer the cave layer, or {@code Integer.MAX_VALUE} for the surface
     */
    public record RegionCoord(int x, int z, int caveLayer) {
        /**
         * Convenience constructor for the surface layer.
         *
         * @param x region X coordinate
         * @param z region Z coordinate
         */
        public RegionCoord(int x, int z) {
            this(x, z, Integer.MAX_VALUE);
        }

        /**
         * Whether this is the surface layer.
         *
         * @return {@code true} for the surface layer
         */
        public boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    /**
     * Records the regions a sync updated.
     * Those are the ones reset when the map reloads.
     * The cave layer is included, so surface and cave layers stay distinct.
     *
     * @param chunks the regions received during the sync
     */
    public static void recordUpdatedRegions(List<ChunkMapData> chunks) {
        // Clear existing set first to prevent memory leak
        // (previous pattern "updatedRegions = regions" created new Set but old Set remained in memory)
        updatedRegions.clear();

        for (ChunkMapData chunk : chunks) {
            updatedRegions.add(new RegionCoord(chunk.regionX, chunk.regionZ, chunk.caveLayer));
        }
        LOGGER.debug("Recorded {} updated regions for selective reset", updatedRegions.size());
    }

    /**
     * Records the regions a sync updated, from a set of coordinates.
     * Cheaper than passing the full data, since only the coordinates are needed.
     *
     * @param coords the region coordinates
     */
    public static void recordUpdatedRegionCoords(Set<RegionCoord> coords) {
        // Clear existing set first to prevent memory leak
        updatedRegions.clear();
        updatedRegions.addAll(coords);
        LOGGER.debug("Recorded {} updated region coords for selective reset", updatedRegions.size());
    }

    /**
     * The regions within view distance of the player.
     * Worked out from the player's position and render distance.
     * Assumes the surface layer.
     *
     * <p>How it is worked out:</p>
     * <ul>
     *   <li>view distance is the render distance, as a radius in chunks</li>
     *   <li>one region is 32 chunks</li>
     *   <li>so the player's position gives the regions that radius can touch</li>
     * </ul>
     *
     * @return the regions within view distance, on the surface layer
     */
    public static Set<RegionCoord> getViewDistanceRegions() {
        return getViewDistanceRegions(Integer.MAX_VALUE);
    }

    /**
     * The regions within view distance of the player.
     * Worked out from the player's position and render distance.
     *
     * <p>How it is worked out:</p>
     * <ul>
     *   <li>view distance is the render distance, as a radius in chunks</li>
     *   <li>one region is 32 chunks</li>
     *   <li>so the player's position gives the regions that radius can touch</li>
     * </ul>
     *
     * @param caveLayer the cave layer, or {@code Integer.MAX_VALUE} for the surface
     * @return the regions within view distance
     */
    public static Set<RegionCoord> getViewDistanceRegions(int caveLayer) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) {
            return new HashSet<>();
        }

        // Get player position in chunks
        int playerChunkX = player.getBlockX() >> 4;  // 16 blocks per chunk
        int playerChunkZ = player.getBlockZ() >> 4;

        // Get view distance (render distance) in chunks (radius)
        int viewDistance = mc.options.renderDistance().get();

        // The view distance in chunks:
        // from playerChunkX - viewDistance to playerChunkX + viewDistance.
        int minChunkX = playerChunkX - viewDistance;
        int maxChunkX = playerChunkX + viewDistance;
        int minChunkZ = playerChunkZ - viewDistance;
        int maxChunkZ = playerChunkZ + viewDistance;

        // Converted to region coordinates.
        // A region spans regionX * 32 to (regionX + 1) * 32 - 1.
        int minRegionX = minChunkX >> 5;  // floor division for negative numbers
        int maxRegionX = maxChunkX >> 5;
        int minRegionZ = minChunkZ >> 5;
        int maxRegionZ = maxChunkZ >> 5;

        // Floor division, including for negatives:
        // Java's >> 5 floors either way, which is what is wanted here.

        Set<RegionCoord> viewRegions = new HashSet<>();

        // Every region in range, on the given cave layer.
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                viewRegions.add(new RegionCoord(rx, rz, caveLayer));
            }
        }

        LOGGER.debug("View distance regions: viewDistance={}, chunks ({},{}) to ({},{}), regions ({},{}) to ({},{}), total {} (layer={})",
                viewDistance, minChunkX, minChunkZ, maxChunkX, maxChunkZ,
                minRegionX, minRegionZ, maxRegionX, maxRegionZ, viewRegions.size(), caveLayer);

        return viewRegions;
    }

    /**
     * Unloads every region within the player's view.
     * Called when syncing the current dimension, so those regions reload the server's data.
     *
     * @return how many regions were unloaded
     */
    public static int unloadViewDistanceRegions() {
        Set<RegionCoord> viewRegions = getViewDistanceRegions();
        if (viewRegions.isEmpty()) {
            LOGGER.info("No view distance regions to unload");
            return 0;
        }

        LOGGER.info("Unloading {} view distance regions before sync", viewRegions.size());
        return resetSpecificRegionLoadStates(viewRegions);
    }

    /**
     * Resets the load state of specific regions.
     * Public, for callers outside this class.
     *
     * @param regionsToReset the regions to reset
     * @return how many were reset
     */
    public static int resetSpecificRegionLoadStates(Set<RegionCoord> regionsToReset) {
        int resetCount = 0;

        try {
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Method getCurrentSession = worldMapSessionClass.getMethod("getCurrentSession");
            Object session = getCurrentSession.invoke(null);

            if (session == null) {
                LOGGER.warn("Could not get WorldMapSession for selective reset");
                return 0;
            }

            Method getMapProcessor = worldMapSessionClass.getMethod("getMapProcessor");
            Object mapProcessor = getMapProcessor.invoke(session);

            if (mapProcessor == null) {
                LOGGER.warn("Could not get MapProcessor for selective reset");
                return 0;
            }

            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Method getMapWorld = mapProcessorClass.getMethod("getMapWorld");
            Object mapWorld = getMapWorld.invoke(mapProcessor);

            if (mapWorld == null) {
                LOGGER.warn("Could not get MapWorld for selective reset");
                return 0;
            }

            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            Method getCurrentDimension = mapWorldClass.getMethod("getCurrentDimension");
            Object mapDimension = getCurrentDimension.invoke(mapWorld);

            if (mapDimension == null) {
                LOGGER.warn("Could not get current dimension for selective reset");
                return 0;
            }

            // Get the LayeredRegionManager
            Class<?> mapDimensionClass = Class.forName("xaero.map.world.MapDimension");
            Method getLayeredMapRegions = mapDimensionClass.getMethod("getLayeredMapRegions");
            Object layeredRegionManager = getLayeredMapRegions.invoke(mapDimension);

            if (layeredRegionManager == null) {
                LOGGER.warn("Could not get LayeredRegionManager");
                return 0;
            }

            // Get the surface layer
            Class<?> layeredRegionManagerClass = Class.forName("xaero.map.region.LayeredRegionManager");
            Method getLayer = layeredRegionManagerClass.getMethod("getLayer", int.class);
            Object mapLayer = getLayer.invoke(layeredRegionManager, Integer.MAX_VALUE);

            if (mapLayer == null) {
                LOGGER.warn("Could not get surface MapLayer");
                return 0;
            }

            // Get LeveledRegionManager
            Class<?> mapLayerClass = Class.forName("xaero.map.region.MapLayer");
            Method getMapRegions = mapLayerClass.getMethod("getMapRegions");
            Object leveledRegionManager = getMapRegions.invoke(mapLayer);

            if (leveledRegionManager == null) {
                LOGGER.warn("Could not get LeveledRegionManager");
                return 0;
            }

            // Access regionTextureMap
            Class<?> leveledRegionManagerClass = Class.forName("xaero.map.region.LeveledRegionManager");
            Field regionTextureMapField = leveledRegionManagerClass.getDeclaredField("regionTextureMap");
            regionTextureMapField.setAccessible(true);
            Object regionTextureMap = regionTextureMapField.get(leveledRegionManager);

            if (regionTextureMap != null && regionTextureMap instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) regionTextureMap;

                for (Object columnEntry : map.values()) {
                    if (columnEntry instanceof java.util.Map) {
                        java.util.Map<?, ?> column = (java.util.Map<?, ?>) columnEntry;
                        for (Object regionEntry : column.values()) {
                            // Traverse and selectively reset
                            resetCount += selectiveResetLeafRegions(regionEntry, regionsToReset);
                        }
                    }
                }
            }

            LOGGER.info("Selective reset completed: {} regions reset", resetCount);

        } catch (Exception e) {
            LOGGER.warn("Failed to selective reset regions: {}", e.getMessage());
        }

        return resetCount;
    }

    /**
     * Walks the regions and resets the ones in the target set.
     * Regions that were loaded (loadState==2) are noted in preUnloadedRegions, so that after
     * the sync they can be set to loadState=4 (reload me) rather than loadState=0 (never loaded).
     *
     * @param region the region
     * @param regionsToReset the regions to reset
     * @return how many were reset
     */
    private static int selectiveResetLeafRegions(Object region, Set<RegionCoord> regionsToReset) {
        int count = 0;
        try {
            Class<?> regionClass = region.getClass();

            // Check if this is a MapRegion (leaf)
            if (regionClass.getName().equals("xaero.map.region.MapRegion")) {
                // Get region coordinates from the MapRegion object
                Field regionXField = regionClass.getDeclaredField("regionX");
                Field regionZField = regionClass.getDeclaredField("regionZ");
                regionXField.setAccessible(true);
                regionZField.setAccessible(true);
                int rx = regionXField.getInt(region);
                int rz = regionZField.getInt(region);

                RegionCoord coord = new RegionCoord(rx, rz);

                // Only reset if this region is in our target set
                if (regionsToReset.contains(coord)) {
                    Field loadStateField = regionClass.getDeclaredField("loadState");
                    loadStateField.setAccessible(true);
                    byte currentLoadState = loadStateField.getByte(region);

                    if (currentLoadState == 2) {  // Only reset loaded regions
                        // Was loaded, so mark it for loadState=4 after the sync.
                        preUnloadedRegions.add(coord);

                        loadStateField.setByte(region, (byte) 0);
                        count++;

                        LOGGER.debug("Pre-unloaded region ({}, {}) was loaded, recorded for loadState=4", rx, rz);
                    } else if (currentLoadState == 4) {
                        // Awaiting reload counts as loaded too.
                        preUnloadedRegions.add(coord);
                        loadStateField.setByte(region, (byte) 0);
                        count++;
                    }
                }
            } else if (regionClass.getName().equals("xaero.map.region.BranchLeveledRegion")) {
                // Traverse children
                Field childrenField = regionClass.getDeclaredField("children");
                childrenField.setAccessible(true);
                Object childrenArray = childrenField.get(region);

                if (childrenArray != null && childrenArray.getClass().isArray()) {
                    int outerLength = java.lang.reflect.Array.getLength(childrenArray);
                    for (int i = 0; i < outerLength; i++) {
                        Object innerArray = java.lang.reflect.Array.get(childrenArray, i);
                        if (innerArray != null && innerArray.getClass().isArray()) {
                            int innerLength = java.lang.reflect.Array.getLength(innerArray);
                            for (int j = 0; j < innerLength; j++) {
                                Object child = java.lang.reflect.Array.get(innerArray, j);
                                if (child != null) {
                                    count += selectiveResetLeafRegions(child, regionsToReset);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Error in selective reset: {}", e.getMessage());
        }
        return count;
    }

    /**
     * The current server's base directory, i.e. its null directory.
     * That is xaero/world-map/Multiplayer_&lt;server&gt;/null/
     *
     * <p>Covers every kind of game:</p>
     * <ul>
     *   <li>multiplayer: Multiplayer_&lt;serverIP&gt;/</li>
     *   <li>singleplayer uses a "Singleplayer" directory; LAN games are handled separately</li>
     * </ul>
     *
     * @return the base directory, or {@code null} if not connected to anything
     */
    public static Path getCurrentServerBaseDirectory() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        LOGGER.debug("getCurrentServerBaseDirectory: connection={}", connection);
        if (connection == null) {
            LOGGER.warn("getCurrentServerBaseDirectory: connection is null");
            return null;
        }

        // ServerData, which exists in multiplayer.
        ServerData serverData = connection.getServerData();
        LOGGER.debug("getCurrentServerBaseDirectory: serverData={}, serverData.ip={}",
                serverData, serverData != null ? serverData.ip : "N/A");

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = gameDir.resolve("xaero").resolve("world-map");

        String serverIP;

        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            // Multiplayer.
            serverIP = serverData.ip;

            // Clean up server IP
            int portDivider = serverIP.lastIndexOf(":");
            if (portDivider > 0 && serverIP.indexOf(":") != serverIP.lastIndexOf(":")) {
                portDivider = serverIP.lastIndexOf("]:") + 1;
            }
            if (portDivider > 0) {
                serverIP = serverIP.substring(0, portDivider);
            }
            serverIP = serverIP.replace("[", "").replace("]", "");
            serverIP = serverIP.replaceAll(":", ".");
            while (serverIP.endsWith(".")) {
                serverIP = serverIP.substring(0, serverIP.length() - 1);
            }
            if (serverIP.isEmpty()) {
                serverIP = "Empty Address";
            }
        } else {
            // Singleplayer or LAN.
            // Is this singleplayer?
            if (mc.hasSingleplayerServer()) {
                serverIP = "Singleplayer";
                LOGGER.debug("Singleplayer mode detected");
            } else {
                // LAN: work it out from the connection.
                // LAN servers usually appear as localhost.
                serverIP = "LAN";
                LOGGER.debug("LAN mode detected");
            }
        }

        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        Path dimDir = serverDir.resolve("null");

        // If that directory does not exist, look for an existing Xaero one.
        if (!dimDir.toFile().exists()) {
            // Scan world-map for a directory matching this server.
            try {
                if (worldMapDir.toFile().exists() && worldMapDir.toFile().isDirectory()) {
                    Files.list(worldMapDir)
                        .filter(p -> p.getFileName().toString().startsWith("Multiplayer_"))
                        .filter(p -> Files.isDirectory(p))
                        .forEach(p -> {
                            Path candidateDim = p.resolve("null");
                            if (candidateDim.toFile().exists()) {
                                LOGGER.debug("Found existing Xaero directory: {}", candidateDim);
                            }
                        });
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to scan world-map directory: {}", e.getMessage());
            }

            // In singleplayer, create it: this is the first sync.
            if (serverIP.equals("Singleplayer") || serverIP.equals("LAN")) {
                LOGGER.info("Creating Xaero directory for {} mode: {}", serverIP, dimDir);
                try {
                    Files.createDirectories(dimDir);
                } catch (IOException e) {
                    LOGGER.warn("Failed to create Xaero directory: {}", e.getMessage());
                }
            }
        }

        LOGGER.debug("Server base directory: {}", dimDir);
        return dimDir;
    }

    /**
     * The current server's directory, i.e. Multiplayer_&lt;serverIP&gt;.
     * The parent of every dimension folder.
     * That is xaero/world-map/Multiplayer_&lt;server&gt;/
     *
     * @return the server directory, or {@code null} if not connected to anything
     */
    public static Path getCurrentServerDirectory() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            LOGGER.warn("getCurrentServerDirectory: connection is null");
            return null;
        }

        ServerData serverData = connection.getServerData();
        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = gameDir.resolve("xaero").resolve("world-map");

        String serverIP;

        if (serverData != null && serverData.ip != null && !serverData.ip.isEmpty()) {
            serverIP = serverData.ip;

            // Clean up server IP
            int portDivider = serverIP.lastIndexOf(":");
            if (portDivider > 0 && serverIP.indexOf(":") != serverIP.lastIndexOf(":")) {
                portDivider = serverIP.lastIndexOf("]:") + 1;
            }
            if (portDivider > 0) {
                serverIP = serverIP.substring(0, portDivider);
            }
            serverIP = serverIP.replace("[", "").replace("]", "");
            serverIP = serverIP.replaceAll(":", ".");
            while (serverIP.endsWith(".")) {
                serverIP = serverIP.substring(0, serverIP.length() - 1);
            }
            if (serverIP.isEmpty()) {
                serverIP = "Empty Address";
            }
        } else {
            if (mc.hasSingleplayerServer()) {
                serverIP = "Singleplayer";
            } else {
                serverIP = "LAN";
            }
        }

        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);
        LOGGER.debug("Server directory: {}", serverDir);
        return serverDir;
    }

    /**
     * Writes the map data the server sent into the right place.
     * Uses the worldId the server supplied, so the path is right.
     * Returns the mw directory for the caller to work with.
     * Also records the server's timestamps locally, for the next sync to compare against.
     *
     * @param chunks the regions received
     * @param serverWorldId the server's worldId
     * @return the last mw directory written to, or {@code null} if writing failed
     */
    public static Path writeMapDataAndReturnDir(List<ChunkMapData> chunks, int serverWorldId) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            LOGGER.error("Not connected to server");
            return null;
        }

        ServerData serverData = connection.getServerData();
        if (serverData == null) {
            LOGGER.error("No server data available");
            return null;
        }

        // Get server address
        String serverIP = serverData.ip;
        if (serverIP == null || serverIP.isEmpty()) {
            serverIP = "Unknown";
        }

        // Clean up server IP
        int portDivider = serverIP.lastIndexOf(":");
        if (portDivider > 0 && serverIP.indexOf(":") != serverIP.lastIndexOf(":")) {
            portDivider = serverIP.lastIndexOf("]:") + 1;
        }
        if (portDivider > 0) {
            serverIP = serverIP.substring(0, portDivider);
        }
        serverIP = serverIP.replace("[", "").replace("]", "");
        serverIP = serverIP.replaceAll(":", ".");
        while (serverIP.endsWith(".")) {
            serverIP = serverIP.substring(0, serverIP.length() - 1);
        }
        if (serverIP.isEmpty()) {
            serverIP = "Empty Address";
        }

        LOGGER.info("Using server worldId: {}", serverWorldId);

        Path gameDir = mc.gameDirectory.toPath();
        Path worldMapDir = gameDir.resolve("xaero").resolve("world-map");
        Path serverDir = worldMapDir.resolve("Multiplayer_" + serverIP);

        // Get timestamp cache for this server
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);

        Path lastMwDir = null;
        for (ChunkMapData chunk : chunks) {
            lastMwDir = writeChunkDataAndGetDir(chunk, serverDir, serverWorldId);

            // Update timestamp cache with server's timestamp and computed hash
            String relativePath = buildRelativePathForCache(chunk);
            String hash = HashUtils.computeHash(chunk.data);
            tsCache.update(relativePath, chunk.timestampSeconds, hash);
            LOGGER.debug("Updated timestamp cache for {}: ts={}s, hash={}",
                    relativePath, chunk.timestampSeconds, hash);
        }

        // Save timestamp cache after all chunks written
        tsCache.save();
        LOGGER.info("Saved timestamp cache for {} regions", chunks.size());

        return lastMwDir;
    }

    /**
     * Builds the timestamp cache path in the form the server uses.
     *
     * <p>Matching GenerationCache:</p>
     * <ul>
     *   <li>surface: xaeroDim/regionX_regionZ, e.g. twilightforest$twilight_forest/0_0</li>
     *   <li>caves: xaeroDim/caves/layer/regionX_regionZ</li>
     * </ul>
     *
     * <p>chunk.dimension is already in Xaero's form, so it is used as it is.</p>
     *
     * @param chunk the region's data
     * @return the relative path
     */
    private static String buildRelativePathForCache(ChunkMapData chunk) {
        // chunk.dimension is already in Xaero's form, e.g. twilightforest$twilight_forest,
        // which is the same key format the server's GenerationCache uses.
        String xaeroDim = chunk.dimension;

        if (chunk.caveLayer == Integer.MAX_VALUE) {
            // Surface layer.
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            // Cave layer.
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }

    /**
     * Writes a region and returns the mw directory it went into.
     * Handles the caves/<layer> layout:
     * <ul>
     *   <li>surface: Multiplayer_&lt;server&gt;/&lt;xaero_dimension&gt;/mw$&lt;worldId&gt;/&lt;regionX_regionZ&gt;.zip</li>
     *   <li>caves: Multiplayer_&lt;server&gt;/&lt;xaero_dimension&gt;/mw$&lt;worldId&gt;/caves/&lt;layer&gt;/&lt;regionX_regionZ&gt;.zip</li>
     * </ul>
     *
     * @param chunk the region's data
     * @param worldId worldId
     * @return the mw directory
     */
    public static Path writeChunkDataAndGetMwDir(ChunkMapData chunk, int worldId) {
        Path serverDir = getCurrentServerDirectory();
        if (serverDir == null) {
            LOGGER.warn("Cannot determine the server directory");
            return null;
        }
        return writeChunkDataAndGetDir(chunk, serverDir, worldId);
    }

    /**
     * Writes a region and returns the mw directory it went into.
     * For callers that already resolved the server directory on the client thread, so a
     * background thread never touches Minecraft's connection objects.
     *
     * @param chunk the region's data
     * @param serverDir the server directory
     * @param worldId worldId
     * @return the mw directory
     */
    public static Path writeChunkDataAndGetMwDir(ChunkMapData chunk, Path serverDir, int worldId) {
        if (serverDir == null) {
            LOGGER.warn("Cannot determine the server directory");
            return null;
        }
        return writeChunkDataAndGetDir(chunk, serverDir, worldId);
    }

    /**
     * Writes a region and returns the mw directory it went into.
     * Handles the caves/<layer> layout:
     * <ul>
     *   <li>surface: Multiplayer_&lt;server&gt;/&lt;xaero_dimension&gt;/mw$&lt;worldId&gt;/&lt;regionX_regionZ&gt;.zip</li>
     *   <li>caves: Multiplayer_&lt;server&gt;/&lt;xaero_dimension&gt;/mw$&lt;worldId&gt;/caves/&lt;layer&gt;/&lt;regionX_regionZ&gt;.zip</li>
     * </ul>
     *
     * @param chunk the region's data
     * @param serverDir the server directory
     * @param worldId worldId
     * @return the mw directory
     */
    public record RegionFileTarget(Path mwDir, Path targetDir, Path outputFile) {
        public Path partFile() {
            return outputFile.resolveSibling(outputFile.getFileName().toString() + ".part");
        }
    }

    public static RegionFileTarget resolveRegionFileTarget(ChunkMapData chunk, Path serverDir, int worldId) {
        if (serverDir == null) {
            return null;
        }

        Path mwDir = getDefaultMwDir(serverDir, chunk.dimension);
        if (mwDir == null) {
            return null;
        }
        Path targetDir = chunk.caveLayer == Integer.MAX_VALUE
                ? mwDir
                : mwDir.resolve("caves").resolve(String.valueOf(chunk.caveLayer));
        Path outputFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip");
        return new RegionFileTarget(mwDir, targetDir, outputFile);
    }

    public static Path getDefaultMwDir(Path serverDir, String xaeroDim) {
        if (serverDir == null || xaeroDim == null || xaeroDim.isBlank()) {
            return null;
        }
        return serverDir.resolve(xaeroDim).resolve(DEFAULT_MW_DIR_NAME);
    }

    private static Path writeChunkDataAndGetDir(ChunkMapData chunk, Path serverDir, int worldId) {
        RegionFileTarget resolvedTarget = resolveRegionFileTarget(chunk, serverDir, worldId);
        if (resolvedTarget == null) {
            return null;
        }
        Path mwDir = resolvedTarget.mwDir();
        Path targetDir = resolvedTarget.targetDir();
        Path outputFile = resolvedTarget.outputFile();
        Path tempFile = targetDir.resolve(chunk.regionX + "_" + chunk.regionZ + ".zip.temp");

        try {
            Files.createDirectories(targetDir);

            // Direct write: replace existing file with server data (no incremental merge)
            Files.write(tempFile, chunk.data);
            Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Wrote map file: {} (layer={}, {} bytes)", outputFile,
                chunk.isSurfaceLayer() ? "surface" : chunk.caveLayer, chunk.data.length);
        } catch (IOException e) {
            LOGGER.error("Failed to write map file: {}", outputFile, e);
        }

        return mwDir;
    }
}
