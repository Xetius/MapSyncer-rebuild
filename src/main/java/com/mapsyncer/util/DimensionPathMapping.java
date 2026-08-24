package com.mapsyncer.util;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Maps a dimension between the three names it goes by.
 *
 * Namely:
 * - Identifier path (the_nether, the_end, overworld)
 * - its save directory on disk
 * - its Xaero directory name (DIM-1, DIM1, null)
 *
 * Save layouts:
 * <pre>
 * current (26.x): world/dimensions/&lt;namespace&gt;/&lt;dimension&gt;/region/
 *   overworld: world/dimensions/minecraft/overworld/region/
 *   nether:    world/dimensions/minecraft/the_nether/region/
 *   modded:    world/dimensions/twilightforest/twilight_forest/region/
 *
 * legacy:         world/region/, world/DIM-1/region/, world/DIM1/region/
 * </pre>
 *
 * Detection tries the current layout first and falls back to the legacy one, so both
 * old and new saves work.
 *
 * Examples:
 * | dimension ID | save directory | Xaero directory |
 * |------------------|-------------|------------|
 * | minecraft:overworld | . (the save root) | null |
 * | minecraft:the_nether | DIM-1 | DIM-1 |
 * | minecraft:the_end | DIM1 | DIM1 |
 * | twilightforest:twilight_forest | dimensions/twilightforest/twilight_forest | twilightforest$twilight_forest |
 */
public class DimensionPathMapping {

    private static final Logger LOGGER = LoggerFactory.getLogger(DimensionPathMapping.class);

    // The single instance.
    private static volatile DimensionPathMapping instance;

    // Dimension path -> save directory, as actually detected at runtime.
    private final Map<String, String> pathToFolder = new ConcurrentHashMap<>();

    // Dimension path -> Xaero directory name.
    private final Map<String, String> pathToXaero = new ConcurrentHashMap<>();

    // ========== Built-in mappings ==========

    // Vanilla dimensions, legacy layout.
    private static final Map<String, String> VANILLA_FORMAT = new LinkedHashMap<>();

    // Vanilla dimensions, Xaero directory names (these never change).
    private static final Map<String, String> VANILLA_XAERO_MAPPINGS = new LinkedHashMap<>();

    static {
        // Vanilla dimensions in the legacy layout.
        // Stored without the minecraft: prefix; lookups go through normalizeDimPath.
        VANILLA_FORMAT.put("overworld", ".");
        VANILLA_FORMAT.put("the_nether", "DIM-1");
        VANILLA_FORMAT.put("the_end", "DIM1");

        // Xaero directory names for the vanilla dimensions.
        VANILLA_XAERO_MAPPINGS.put("overworld", "null");
        VANILLA_XAERO_MAPPINGS.put("the_nether", "DIM-1");
        VANILLA_XAERO_MAPPINGS.put("the_end", "DIM1");
    }

    /**
     * Sets up the built-in mappings.
     */
    private DimensionPathMapping() {
        // Xaero names, vanilla dimensions only.
        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);

        LOGGER.info("DimensionPathMapping initialized with {} Xaero mappings", pathToXaero.size());
    }

    /**
     * @return the shared instance
     *
     * @return the shared instance
     */
    public static DimensionPathMapping getInstance() {
        if (instance == null) {
            synchronized (DimensionPathMapping.class) {
                if (instance == null) {
                    instance = new DimensionPathMapping();
                }
            }
        }
        return instance;
    }

    /**
     * Drops the instance, for tests or a config reload.
     *
     * @return void
     */
    public static void resetInstance() {
        synchronized (DimensionPathMapping.class) {
            instance = null;
        }
        LOGGER.info("DimensionPathMapping instance reset");
    }

    // ========== Save directory detection ==========

    /**
     * Finds the region directory of a dimension.
     *
     * Tries, in order: the current dimensions/ layout, anything already cached, the legacy
     * layout for vanilla dimensions, and finally dimensions/&lt;namespace&gt;/&lt;path&gt; for modded
     * ones. Whatever it finds is cached.
     *
     * @param worldRoot the save root
     * @param dimPath the dimension, e.g. "overworld", "the_nether", "twilightforest:twilight_forest"
     * @return the region directory, or {@code null} if none was found
     */
    public Path detectRegionDir(Path worldRoot, String dimPath) {
        if (worldRoot == null || !Files.exists(worldRoot)) {
            return null;
        }

        String normalized = normalizeDimPath(dimPath);
        String modernFolder = toModernDimensionFolder(toNamespacedDimensionId(dimPath, normalized));

        if (modernFolder != null) {
            Path regionDir = resolveRegionDir(worldRoot, modernFolder);
            if (Files.exists(regionDir)) {
                LOGGER.info("Detected dimension {} (26.1.2 format): {}", normalized, modernFolder);
                pathToFolder.put(normalized, modernFolder);
                return regionDir;
            }
        }

        // 1. Anything cached from an earlier lookup.
        String cachedFolder = pathToFolder.get(normalized);
        if (cachedFolder != null) {
            Path regionDir = resolveRegionDir(worldRoot, cachedFolder);
            if (Files.exists(regionDir)) {
                return regionDir;
            }
        }

        // 2. Vanilla dimensions in the legacy layout.
        if (isVanillaDimension(normalized)) {
            String vanillaFolder = VANILLA_FORMAT.get(normalized);
            if (vanillaFolder != null) {
                Path regionDir = resolveRegionDir(worldRoot, vanillaFolder);
                if (Files.exists(regionDir)) {
                    LOGGER.info("Detected vanilla dimension {}: {}", normalized, vanillaFolder);
                    pathToFolder.put(normalized, vanillaFolder);
                    return regionDir;
                }
            }
        }

        // 3. Modded dimensions under dimensions/<namespace>/<path>.
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            if (parts.length == 2) {
                String newFormatFolder = "dimensions/" + parts[0] + "/" + parts[1];
                Path regionDir = resolveRegionDir(worldRoot, newFormatFolder);
                if (Files.exists(regionDir)) {
                    LOGGER.info("Detected Mod dimension {} (new format): {}", normalized, newFormatFolder);
                    pathToFolder.put(normalized, newFormatFolder);
                    return regionDir;
                }
            }
        }

        // 4. Legacy DIM{id} directories, as some older mods use.
        //    Detected on the fly; nothing is preset for these.

        LOGGER.warn("Could not detect region directory for dimension: {}", normalized);
        return null;
    }

    /**
     * Whether this is one of the three vanilla dimensions.
     *
     * @param dimPath the dimension path, already normalised
     * @return {@code true} for overworld, nether or end
     */
    private boolean isVanillaDimension(String dimPath) {
        return "overworld".equals(dimPath) || "the_nether".equals(dimPath) || "the_end".equals(dimPath);
    }

    private String toNamespacedDimensionId(String originalDimPath, String normalizedDimPath) {
        if (originalDimPath != null && originalDimPath.contains(":")) {
            return originalDimPath;
        }
        if (isVanillaDimension(normalizedDimPath)) {
            return "minecraft:" + normalizedDimPath;
        }
        return normalizedDimPath;
    }

    private String toModernDimensionFolder(String namespacedDimId) {
        if (namespacedDimId == null || !namespacedDimId.contains(":")) {
            return null;
        }

        String[] parts = namespacedDimId.split(":", 2);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return null;
        }

        return "dimensions/" + parts[0] + "/" + parts[1];
    }

    /**
     * Resolves a save directory name to its region directory.
     *
     * @param worldRoot the save root
     * @param folder the directory name, e.g. ".", "DIM-1", "dimensions/mod/dim"
     * @return the full path to that dimension's region directory
     */
    private Path resolveRegionDir(Path worldRoot, String folder) {
        if (folder == null || folder.isEmpty() || ".".equals(folder)) {
            return worldRoot.resolve("region");
        }
        return worldRoot.resolve(folder).resolve("region");
    }

    // ========== Save directory names ==========

    /**
     * The save directory name for a dimension.
     *
     * Vanilla dimensions get the legacy name; modded ones get the dimensions/ form.
     *
     * @param dimPath the dimension, e.g. "the_nether", "my_mod:custom_dim"
     * @return the save directory name
     */
    public String getFolderName(String dimPath) {
        String normalized = normalizeDimPath(dimPath);

        // Anything already cached, keyed by the normalised name.
        String cached = pathToFolder.get(normalized);
        if (cached != null) {
            return cached;
        }

        // Vanilla: the legacy name.
        if (isVanillaDimension(normalized)) {
            String vanillaFolder = VANILLA_FORMAT.get(normalized);
            return vanillaFolder != null ? vanillaFolder : ".";
        }

        // Modded: dimensions/<namespace>/<path>.
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            if (parts.length == 2) {
                return "dimensions/" + parts[0] + "/" + parts[1];
            }
        }

        // Default to the current layout.
        return "dimensions/minecraft/" + normalized;
    }

    /**
     * The save directory name for a dimension key.
     *
     * @param dimensionKey the dimension key
     * @return the save directory name
     */
    public String getFolderName(ResourceKey<Level> dimensionKey) {
        return getFolderName(dimensionKey.identifier().toString());
    }

    /**
     * The dimension ID that owns a save directory.
     *
     * @param folderName the save directory name
     * @return the dimension ID
     */
    public String getPathFromFolder(String folderName) {
        // Look through the forward mapping.
        for (Map.Entry<String, String> entry : pathToFolder.entrySet()) {
            if (entry.getValue().equals(folderName)) {
                return entry.getKey();
            }
        }

        // Current layout: dimensions/<namespace>/<path> -> namespace:path
        if (folderName.startsWith("dimensions/")) {
            String remaining = folderName.substring(11);
            String[] parts = remaining.split("/");
            if (parts.length == 2) {
                return parts[0] + ":" + parts[1];
            }
            return remaining;
        }

        // Legacy names for the vanilla dimensions.
        if (".".equals(folderName) || "region".equals(folderName)) return "overworld";
        if ("DIM-1".equals(folderName)) return "the_nether";
        if ("DIM1".equals(folderName)) return "the_end";

        // Older MapSyncer versions wrote namespace$path; accept that too.
        if (folderName.contains("$")) {
            return folderName.replace('$', ':');
        }

        return folderName;
    }

    // ========== Xaero directory names ==========

    /**
     * The Xaero directory name for a dimension.
     *
     * Vanilla dimensions use Xaero's fixed names (null, DIM-1, DIM1); modded ones use
     * namespace$path.
     *
     * @param dimPath the dimension ID
     * @return the Xaero directory name
     */
    public String getXaeroFolder(String dimPath) {
        String normalized = normalizeDimPath(dimPath);

        // Vanilla: the fixed names.
        String vanillaXaero = VANILLA_XAERO_MAPPINGS.get(normalized);
        if (vanillaXaero != null) {
            return vanillaXaero;
        }

        // Anything registered for a modded dimension.
        String registered = pathToXaero.get(normalized);
        if (registered != null) {
            return registered;
        }

        // Modded: namespace$path.
        if (normalized.contains(":")) {
            String[] parts = normalized.split(":");
            if (parts.length == 2) {
                return parts[0] + "$" + parts[1];
            }
        }

        return normalized;
    }

    /**
     * The dimension ID behind an Xaero directory name.
     *
     * @param xaeroFolder the Xaero directory name
     * @return the dimension ID
     */
    public String getPathFromXaero(String xaeroFolder) {
        // Look through the forward mapping.
        for (Map.Entry<String, String> entry : pathToXaero.entrySet()) {
            if (entry.getValue().equals(xaeroFolder)) {
                return entry.getKey();
            }
        }

        // Xaero's fixed names for the vanilla dimensions.
        if ("null".equals(xaeroFolder)) return "overworld";
        if ("DIM-1".equals(xaeroFolder)) return "the_nether";
        if ("DIM1".equals(xaeroFolder)) return "the_end";

        // Modded: namespace$path -> namespace:path
        if (xaeroFolder.contains("$")) {
            return xaeroFolder.replace('$', ':');
        }

        return xaeroFolder;
    }

    // ========== Converting between client and server names ==========

    /**
     * Converts a client dimension name to the server's form.
     *
     * @param clientDim the client name, possibly in Xaero's form
     * @return the server's dimension name
     */
    public String toServerDimension(String clientDim) {
        if (clientDim == null || clientDim.isEmpty()) {
            return "overworld";
        }

        String normalized = normalizeDimPath(clientDim);

        // Xaero's vanilla names.
        if ("null".equals(normalized)) return "overworld";
        if ("DIM-1".equals(normalized)) return "the_nether";
        if ("DIM1".equals(normalized)) return "the_end";

        // Otherwise search the forward mapping by Xaero name.
        for (Map.Entry<String, String> entry : pathToXaero.entrySet()) {
            if (entry.getValue().equals(normalized)) {
                return entry.getKey();
            }
        }

        return normalized;
    }

    /**
     * Converts a server dimension name to Xaero's form.
     *
     * @param serverDim the server's dimension name
     * @return the Xaero name
     */
    public String toXaeroDimension(String serverDim) {
        if (serverDim == null || serverDim.isEmpty()) {
            return "null";
        }

        // Already an Xaero name?
        if (serverDim.equals("null") || serverDim.equals("DIM-1") || serverDim.equals("DIM1")) {
            return serverDim;
        }
        if (serverDim.contains("$")) {
            return serverDim;
        }
        if (serverDim.startsWith("DIM")) {
            return serverDim;
        }

        // Convert it.
        return getXaeroFolder(normalizeDimPath(serverDim));
    }

    /**
     * A dimension name fit to show a player.
     *
     * @param dimPath the dimension path
     * @return the normalised name
     */
    public String getFriendlyName(String dimPath) {
        return normalizeDimPath(dimPath);
    }

    /**
     * A dimension name fit to show a player.
     *
     * @param dimensionKey the dimension key
     * @return the normalised name
     */
    public String getFriendlyName(ResourceKey<Level> dimensionKey) {
        return getFriendlyName(dimensionKey.identifier().getPath());
    }

    // ========== Helpers ==========

    /**
     * Normalises a dimension path by dropping the {@code minecraft:} prefix.
     *
     * @param dimPath the raw dimension path
     * @return the path without the {@code minecraft:} prefix
     */
    private String normalizeDimPath(String dimPath) {
        if (dimPath == null || dimPath.isEmpty()) {
            return "overworld";
        }

        // Drop the minecraft: prefix.
        if (dimPath.startsWith("minecraft:")) {
            dimPath = dimPath.substring(10);
        }

        // Xaero writes "null" for the overworld.
        if ("null".equals(dimPath)) {
            return "overworld";
        }

        return dimPath;
    }

    /**
     * Whether this is the overworld.
     *
     * @param dimPath the dimension path
     * @return {@code true} for the overworld
     */
    public boolean isOverworld(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "overworld".equals(normalized) || ".".equals(normalized);
    }

    /**
     * Whether this is the nether.
     *
     * @param dimPath the dimension path
     * @return {@code true} for the nether
     */
    public boolean isNether(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "the_nether".equals(normalized) || "DIM-1".equals(normalized);
    }

    /**
     * Whether this is the end.
     *
     * @param dimPath the dimension path
     * @return {@code true} for the end
     */
    public boolean isEnd(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        return "the_end".equals(normalized) || "DIM1".equals(normalized);
    }

    /**
     * The region directory relative to the save root.
     *
     * @param dimPath the dimension path
     * @return a relative path such as "region" or "DIM-1/region"
     */
    public String getRegionRelativePath(String dimPath) {
        String folder = getFolderName(dimPath);
        if (".".equals(folder)) {
            return "region";
        }
        return folder + "/region";
    }

    // ========== Registration ==========

    /**
     * Registers a dimension's directory names.
     *
     * @param dimPath the dimension ID
     * @param folderName the save directory name
     * @param xaeroFolder the Xaero directory name
     */
    public void registerMapping(String dimPath, String folderName, String xaeroFolder) {
        String normalized = normalizeDimPath(dimPath);
        pathToFolder.put(normalized, folderName);
        pathToXaero.put(normalized, xaeroFolder);
        LOGGER.info("Registered dimension mapping: {} -> folder={}, xaero={}", normalized, folderName, xaeroFolder);
    }

    /**
     * Registers a dimension, deriving the Xaero directory name from the save directory.
     *
     * @param dimPath the dimension ID
     * @param folderName the save directory name
     */
    public void registerMapping(String dimPath, String folderName) {
        String xaeroFolder = computeXaeroFolderFromFolderName(dimPath, folderName);
        registerMapping(dimPath, folderName, xaeroFolder);
    }

    /**
     * Derives the Xaero directory name from a save directory name.
     *
     * @param dimPath the dimension ID
     * @param folderName the save directory name
     * @return the Xaero directory name
     */
    private String computeXaeroFolderFromFolderName(String dimPath, String folderName) {
        String vanillaXaero = VANILLA_XAERO_MAPPINGS.get(normalizeDimPath(dimPath));
        if (vanillaXaero != null) {
            return vanillaXaero;
        }

        // Current layout, dimensions/<namespace>/<path>,
        // becomes namespace$path.
        if (folderName.startsWith("dimensions/")) {
            String remaining = folderName.substring(11);
            String[] parts = remaining.split("/");
            if (parts.length == 2) {
                return parts[0] + "$" + parts[1];
            }
        }

        // Legacy DIM{id} names are used as they are.
        if (folderName.startsWith("DIM") || ".".equals(folderName)) {
            return folderName;
        }

        return getXaeroFolder(dimPath);
    }

    /**
     * Forgets a dimension's mapping.
     *
     * @param dimPath the dimension ID to forget
     */
    public void removeMapping(String dimPath) {
        String normalized = normalizeDimPath(dimPath);
        pathToFolder.remove(normalized);
        pathToXaero.remove(normalized);
        LOGGER.info("Removed dimension mapping for: {}", normalized);
    }

    /**
     * Clears every detected mapping, back to the built-in state.
     *
     * @return void
     */
    public void clearDetectedMappings() {
        pathToFolder.clear();
        // Keep the Xaero names for the vanilla dimensions.
        pathToXaero.clear();
        pathToXaero.putAll(VANILLA_XAERO_MAPPINGS);
        LOGGER.info("Cleared all detected dimension mappings");
    }

    /**
     * Every registered save directory mapping.
     *
     * @return a copy of the mapping
     */
    public Map<String, String> getAllFolderMappings() {
        return new HashMap<>(pathToFolder);
    }

    /**
     * Every registered Xaero directory mapping.
     *
     * @return a copy of the mapping
     */
    public Map<String, String> getAllXaeroMappings() {
        return new HashMap<>(pathToXaero);
    }

    // ========== Scanning ==========

    /**
     * Searches for a dimension's region directory.
     *
     * @param worldRoot the save root
     * @param dimId the dimension ID
     * @return the region directory, or {@code null} if none was found
     */
    public Path autoSearchRegionDir(Path worldRoot, String dimId) {
        return detectRegionDir(worldRoot, dimId);
    }

    /**
     * Scans a save directory and registers every dimension it finds.
     *
     * <p>Uses try-with-resources so the streams from {@code Files.list} are closed.</p>
     *
     * @param worldRoot the save root
     * @return how many mappings were registered
     */
    public int scanAndRegisterDimensions(Path worldRoot) {
        if (worldRoot == null || !Files.exists(worldRoot)) {
            return 0;
        }

        try {
            // 1. The dimensions/ directory, where modded dimensions live.
            Path dimensionsDir = worldRoot.resolve("dimensions");
            if (Files.exists(dimensionsDir)) {
                try (Stream<Path> namespaceStream = Files.list(dimensionsDir)) {
                    namespaceStream.filter(Files::isDirectory)
                        .forEach(namespaceDir -> {
                            String namespace = namespaceDir.getFileName().toString();
                            // Namespaces are scanned as they come, minecraft included.
                            try (Stream<Path> dimStream = Files.list(namespaceDir)) {
                                dimStream.filter(Files::isDirectory)
                                    .forEach(dimDir -> {
                                        String dimName = dimDir.getFileName().toString();
                                        Path regionDir = dimDir.resolve("region");
                                        if (Files.exists(regionDir)) {
                                            String dimPath = namespace + ":" + dimName;
                                            if (!pathToFolder.containsKey(normalizeDimPath(dimPath))) {
                                                registerMapping(dimPath, "dimensions/" + namespace + "/" + dimName);
                                                LOGGER.info("Auto-registered Mod dimension: {} -> dimensions/{}/{}", dimPath, namespace, dimName);
                                            }
                                        }
                                    });
                            } catch (Exception e) {
                                LOGGER.warn("Error scanning namespace directory: {}", namespace, e);
                            }
                        });
                }
            }

            // 2. Legacy DIM{id} directories, as some older mods use.
            try (Stream<Path> rootStream = Files.list(worldRoot)) {
                rootStream.filter(Files::isDirectory)
                    .forEach(dir -> {
                        String dirName = dir.getFileName().toString();
                        if (dirName.startsWith("DIM") || dirName.startsWith("DIM-")) {
                            // Skip the vanilla ones; those are already known.
                            if ("DIM-1".equals(dirName) || "DIM1".equals(dirName)) {
                                return;
                            }
                            Path regionDir = dir.resolve("region");
                            if (Files.exists(regionDir)) {
                                // An unknown DIM{id}: log it, but there is no way to
                                // work out which dimension ID it belongs to.
                                LOGGER.info("Found unknown DIM directory: {} (cannot determine dimension ID)", dirName);
                            }
                        }
                    });
            }

            // 3. The overworld's own region/ directory.
            Path overworldRegion = worldRoot.resolve("region");
            if (Files.exists(overworldRegion) && !pathToFolder.containsKey("overworld")) {
                pathToFolder.put("overworld", ".");
                LOGGER.info("Confirmed overworld using traditional format: region/");
            }

        } catch (Exception e) {
            LOGGER.warn("Error scanning world directory: {}", e.getMessage());
        }

        return pathToFolder.size();
    }

    /**
     * Every detected mapping, for writing into the config.
     *
     * @return a copy of the mapping
     */
    public Map<String, String> getDetectedMappingsForConfig() {
        return new LinkedHashMap<>(pathToFolder);
    }
}
