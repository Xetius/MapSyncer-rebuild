package com.mapsyncer.client;

import com.mapsyncer.network.ClientMeta;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

/**
 * Works out what map data the client already has.
 * Hashes the client's region files and pairs each with a timestamp, so the server can
 * compare them against its own cache and send only what differs.
 *
 * <p>What it does:</p>
 * <ul>
 *   <li>walks the client's map directory, taking the CRC32 of every region file</li>
 *   <li>uses cached timestamps, so a rewritten file is not mistaken for changed data</li>
 *   <li>hashes in parallel on a shared ForkJoinPool, since there can be a lot of files</li>
 * </ul>
 *
 * <p>What the server does with it:</p>
 * <ul>
 *   <li>hashes match: skip, the client already has this exact data</li>
 *   <li>hashes differ and the client's timestamp is older: send it</li>
 * </ul>
 */
public class ClientHashManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientHashManager.class);

    /** Shared ForkJoinPool, capped at 2 threads so hashing does not stutter the game. */
    private static final ForkJoinPool SHARED_POOL = new ForkJoinPool(2);

    /**
     * One region's metadata: a timestamp in seconds plus the CRC32 hash.
     *
     * @param timestampSeconds the region file's timestamp, in seconds
     * @param hash the region file's CRC32, as eight hex characters
     */
    /**
     * Collects the timestamp and hash of every region the client has.
     * This is what the server compares against its own cache.
     *
     * <p>What the server does with it:</p>
     * <ul>
     *   <li>hashes match: skip, the client already has this exact data</li>
     *   <li>hashes differ and the client's timestamp is older: send it</li>
     * </ul>
     *
     * <p>Timestamps come from the last sync, out of sync_timestamps.cache, so that writing
     * a file does not make it look newer than it is.</p>
     *
     * <p>Hashing runs in parallel on 2 threads, so a large map does not stutter the game.</p>
     *
     * @param mapDir the directory to walk:
     *               - the mw$worldId directory when syncing one dimension
     *               - the Multiplayer_<server> directory when syncing all of them
     * @return relative path to its ClientMeta, i.e. timestamp and hash
     */
    public static Map<String, ClientMeta> computeMetaForSync(Path mapDir) {
        Map<String, ClientMeta> metaMap = new ConcurrentHashMap<>();

        if (mapDir == null || !Files.exists(mapDir)) {
            LOGGER.info("Map directory does not exist or is null, will request all regions from server");
            return metaMap;
        }

        // Determine the server directory (Multiplayer_<server>) for cache lookup
        Path serverDir = findServerDir(mapDir);
        if (serverDir == null) {
            LOGGER.warn("Could not find server directory from {}", mapDir);
            return metaMap;
        }

        // Load cached timestamps from previous sync
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        Map<String, ClientTimestampCache.CacheEntry> cachedTimestamps = tsCache.getAll();
        LOGGER.info("Loaded {} cached timestamps from previous sync", cachedTimestamps.size());

        // Collect all zip files from the specified directory (not entire server)
        java.util.List<Path> zipFiles;
        try (Stream<Path> walk = Files.walk(mapDir)) {
            zipFiles = walk.filter(p -> p.toString().endsWith(".zip"))
                    .filter(p -> isDefaultMapZip(mapDir, serverDir, p))
                    .toList();
        } catch (IOException e) {
            LOGGER.error("Failed to walk map directory", e);
            return metaMap;
        }

        LOGGER.info("Computing hashes for {} region files in {} (parallel=2)", zipFiles.size(), mapDir);

        // The shared 2-thread pool, so hashing neither stutters the game nor rebuilds a pool.
        try {
            SHARED_POOL.submit(() ->
                    zipFiles.parallelStream()
                            .forEach(zipPath -> {
                                try {
                                    // Extract region coordinates from filename
                                    String fileName = zipPath.getFileName().toString();
                                    if (!fileName.endsWith(".zip")) return;

                                    // Build relative path in server format (using serverDir as base)
                                    // This ensures path format matches server's GenerationCache
                                    String relativePath = buildRelativePath(zipPath, serverDir);

                                    // Compute CRC32 hash
                                    String hash = computeFileHash(zipPath);

                                    // Use cached timestamp if available (from previous sync)
                                    // This avoids issues where file modification time changes
                                    ClientTimestampCache.CacheEntry cached = cachedTimestamps.get(relativePath);
                                    long timestampSeconds;
                                    if (cached != null) {
                                        timestampSeconds = cached.timestampSeconds();
                                        LOGGER.debug("Region {}: using cached ts={}s, hash={}",
                                                relativePath, timestampSeconds, hash);
                                    } else {
                                        // No cached timestamp, use file modification time
                                        long timestampMillis = getFileModificationTime(zipPath);
                                        timestampSeconds = timestampMillis / 1000;
                                        LOGGER.debug("Region {}: using file ts={}s, hash={} (no cache)",
                                                relativePath, timestampSeconds, hash);
                                    }

                                    metaMap.put(relativePath, new ClientMeta(timestampSeconds, hash));

                                } catch (Exception e) {
                                    LOGGER.warn("Invalid region filename: {}", zipPath, e);
                                }
                            })
            ).get();  // Wait for completion
        } catch (Exception e) {
            LOGGER.error("Failed to compute hashes in parallel", e);
        }

        LOGGER.info("Found {} regions with metadata", metaMap.size());

        return metaMap;
    }

    /**
     * Finds the server directory (Multiplayer_<server>) from a path.
     * Works whether given the base directory or an mw$worldId directory.
     *
     * @param mapDir the directory to start from
     * @return the server directory, or {@code null} if there is none
     */
    private static Path findServerDir(Path mapDir) {
        Path current = mapDir;

        // Walk up the directory tree to find Multiplayer_<server>
        while (current != null) {
            String name = current.getFileName() != null ? current.getFileName().toString() : "";
            if (name.startsWith("Multiplayer_")) {
                return current;
            }
            current = current.getParent();
        }

        return null;
    }

    /**
     * A file's modification time, in milliseconds.
     *
     * @param path the file
     * @return the time in milliseconds, or 0 if it cannot be read
     */
    private static long getFileModificationTime(Path path) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileTime time = attrs.lastModifiedTime();
            return time.toMillis();
        } catch (IOException e) {
            LOGGER.error("Failed to get modification time for {}", path, e);
            return 0;
        }
    }

    /**
     * The CRC32 of a file's contents, via HashUtils.
     *
     * @param filePath the file
     * @return the CRC32, as eight hex characters
     */
    private static String computeFileHash(Path filePath) {
        return HashUtils.computeFileHash(filePath);
    }

    private static boolean isDefaultMapZip(Path mapDir, Path serverDir, Path zipPath) {
        String mapDirName = mapDir.getFileName() != null ? mapDir.getFileName().toString() : "";
        if (XaeroMapIntegrator.DEFAULT_MW_DIR_NAME.equals(mapDirName)) {
            return true;
        }

        String relative = serverDir.relativize(zipPath).toString().replace("\\", "/");
        String[] parts = relative.split("/");
        return parts.length >= 3 && XaeroMapIntegrator.DEFAULT_MW_DIR_NAME.equals(parts[1]);
    }

    /**
     * Builds the path in the form the server uses.
     * Converts Xaero's dimension name to Minecraft's and drops the mw$worldId level.
     *
     * <p>Handles the caves/<layer> layout:</p>
     * <ul>
     *   <li>surface: xaero_dim/regionX_regionZ</li>
     *   <li>caves: xaero_dim/caves/layer/regionX_regionZ</li>
     * </ul>
     *
     * <p>The dimension name has to be in Xaero's namespace$path form:</p>
     * <ul>
     *   <li>a name containing $ is already correct</li>
     *   <li>otherwise the correct form is looked up in the cache</li>
     *   <li>failing that, DimensionPathMapping converts it</li>
     * </ul>
     *
     * @param zipPath the zip file
     * @param serverDir the Multiplayer_<server> directory
     * @return the path in the server's form, without the .zip extension,
     *         matching GenerationCache: dim/regionX_regionZ or dim/caves/layer/regionX_regionZ
     */
    private static String buildRelativePath(Path zipPath, Path serverDir) {
        // Get relative path from server directory
        String relative = serverDir.relativize(zipPath).toString();
        relative = relative.replace("\\", "/");

        // Remove .zip extension
        if (relative.endsWith(".zip")) {
            relative = relative.substring(0, relative.length() - 4);
        }

        // Parse path components
        // Client paths look like:
        // surface: dimension/mw$worldId/regionX_regionZ (3 parts)
        // caves:   dimension/mw$worldId/caves/layer/regionX_regionZ (5 parts)
        String[] parts = relative.split("/");
        if (parts.length < 3) {
            LOGGER.warn("Unexpected path format: {}", relative);
            return relative;
        }

        String dirName = parts[0];  // the directory name, which may or may not be in Xaero's form
        String regionCoords = parts[parts.length - 1];  // Last part is regionX_regionZ

        // Is there a caves level?
        // Client cave paths are dimension/mw$worldId/caves/layer/regionX_regionZ,
        // so caves sits at parts[2], after mw$worldId at parts[1].
        int caveLayer = Integer.MAX_VALUE;
        boolean hasCaves = false;
        for (int i = 1; i < parts.length - 2; i++) {
            if (parts[i].equals("caves") && i + 1 < parts.length - 1) {
                hasCaves = true;
                try {
                    caveLayer = Integer.parseInt(parts[i + 1]);
                    LOGGER.debug("Found caves layer {} at index {} in path: {}", caveLayer, i, relative);
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid cave layer at index {} in path: {}", i + 1, relative);
                }
                break;
            }
        }

        if (hasCaves) {
            LOGGER.debug("Path has caves layer: {}", relative);
        }

        // The dimension name has to be in Xaero's form. The directory name may be:
        // 1. correct already: twilightforest$twilight_forest, containing $
        // 2. a vanilla dimension: null, DIM-1, DIM1
        // 3. wrong: twilight_forest, missing its namespace
        String xaeroDim = ensureCorrectXaeroFormat(dirName, serverDir);

        // Build path in server format (matches GenerationCache key format)
        String serverPath;
        if (caveLayer == Integer.MAX_VALUE) {
            // Surface: xaero_dim/regionX_regionZ
            serverPath = xaeroDim + "/" + regionCoords;
        } else {
            // Caves: xaero_dim/caves/layer/regionX_regionZ
            serverPath = xaeroDim + "/caves/" + caveLayer + "/" + regionCoords;
        }

        LOGGER.debug("buildRelativePath: {} -> {} (dirName={}, xaeroDim={})", relative, serverPath, dirName, xaeroDim);
        return serverPath;
    }

    /**
     * Puts a dimension name into Xaero's form.
     * The cases:
     * <ul>
     *   <li>vanilla dimensions (null, DIM-1, DIM1) are returned as they are</li>
     *   <li>a name containing $ is already correct</li>
     *   <li>anything else is looked up in the cache or the mapping table</li>
     * </ul>
     *
     * @param dirName the directory name, which may or may not be in Xaero's form
     * @param serverDir the server directory, for the cache lookup
     * @return the dimension name in Xaero's form
     */
    private static String ensureCorrectXaeroFormat(String dirName, Path serverDir) {
        // Vanilla dimensions as they are.
        if (dirName.equals("null") || dirName.equals("DIM-1") || dirName.equals("DIM1")) {
            return dirName;
        }

        // A name containing $ is already namespace$path.
        if (dirName.contains("$")) {
            return dirName;
        }

        // Legacy DIM{id} names are used as they are.
        if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {
            return dirName;
        }

        // Otherwise search the cache for the full form.
        // Cache keys look like xaeroDim/regionX_regionZ,
        // so look for a key whose dimension part matches dirName.
        ClientTimestampCache tsCache = ClientTimestampCache.getInstance(serverDir);
        for (String cacheKey : tsCache.getAll().keySet()) {
            int slashIndex = cacheKey.indexOf('/');
            if (slashIndex > 0) {
                String cachedDim = cacheKey.substring(0, slashIndex);
                // Does the cached xaeroDim match dirName?
                // The cache holds namespace$path; dirName may be just the path.
                if (cachedDim.contains("$")) {
                    String pathPart = cachedDim.substring(cachedDim.indexOf('$') + 1);
                    if (pathPart.equals(dirName)) {
                        LOGGER.info("Found correct xaeroDim from cache: {} -> {}", dirName, cachedDim);
                        return cachedDim;
                    }
                }
            }
        }

        // Failing that, try DimensionPathMapping.
        // Note that toXaeroDimension may not manage it without a namespace.
        String converted = DimensionPathMapping.getInstance().toXaeroDimension(dirName);
        if (!converted.equals(dirName)) {
            LOGGER.info("Converted xaeroDim via mapping: {} -> {}", dirName, converted);
            return converted;
        }

        // Nothing worked: return the name unchanged and log it. Syncing may misbehave.
        LOGGER.warn("Could not convert dirName '{}' to correct Xaero format, sync may fail", dirName);
        return dirName;
    }

    /**
     * Shuts down the shared ForkJoinPool.
     * Called when leaving a server or closing the client.
     */
    public static void shutdown() {
        if (!SHARED_POOL.isShutdown()) {
            SHARED_POOL.shutdown();
            LOGGER.debug("ClientHashManager shared ForkJoinPool shutdown");
        }
    }
}
