package com.mapsyncer.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mapsyncer.MapSyncer;
import com.mapsyncer.platform.Platform;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.HashUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PublicWaypointConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /**
     * Path to the public waypoint config file.
     *
     * <p>The directory comes from the platform: {@code config/} on Fabric,
     * {@code plugins/MapSyncer/} on Paper.</p>
     *
     * @return the config file path
     */
    private static Path configPath() {
        return Platform.configDir().resolve("mapsyncer-public-waypoints.json");
    }

    private static volatile Config config = defaultConfig();
    private static volatile PacketHandler.PublicWaypointsPayload cachedPayload;
    private static volatile Summary cachedSummary = new Summary(false, "ServerPublic", 0, "");

    private PublicWaypointConfig() {
    }

    public static void load() {
        if (!Files.exists(configPath())) {
            config = defaultConfig();
            rebuildCache();
            save();
            return;
        }

        try {
            Config loaded = GSON.fromJson(Files.readString(configPath()), Config.class);
            config = sanitize(loaded);
            rebuildCache();
            MapSyncer.LOGGER.info("Loaded public waypoints from {}", configPath());
        } catch (Exception e) {
            config = defaultConfig();
            rebuildCache();
            MapSyncer.LOGGER.error("Failed to load public waypoints, using defaults", e);
        }
    }

    private static boolean save() {
        return save(config);
    }

    private static boolean save(Config configToSave) {
        try {
            Files.createDirectories(configPath().getParent());
            Files.writeString(configPath(), GSON.toJson(configToSave));
            return true;
        } catch (IOException e) {
            MapSyncer.LOGGER.error("Failed to save public waypoint config", e);
            return false;
        }
    }

    public static PacketHandler.PublicWaypointsPayload createPayload() {
        return cachedPayload;
    }

    public static Summary summary() {
        return cachedSummary;
    }

    public static synchronized AddResult addOrUpdateFromClient(PacketHandler.PublicWaypoint waypoint) {
        if (waypoint == null) {
            return AddResult.FAILED;
        }

        Config current = sanitize(copyConfig(config));
        String name = cleanField(waypoint.name(), "Waypoint");
        String initial = cleanField(waypoint.initial(), name.substring(0, Math.min(1, name.length())));
        String dimension = cleanField(waypoint.dimension(), "minecraft:overworld");
        String type = cleanField(waypoint.type(), "0");

        Waypoint incoming = new Waypoint();
        incoming.enabled = true;
        incoming.name = name;
        incoming.initial = initial;
        incoming.dimension = dimension;
        incoming.x = waypoint.x();
        incoming.y = waypoint.y();
        incoming.z = waypoint.z();
        incoming.color = waypoint.color();
        incoming.disabled = waypoint.disabled();
        incoming.type = type;

        boolean updated = false;
        for (int i = 0; i < current.waypoints.size(); i++) {
            Waypoint existing = current.waypoints.get(i);
            if (existing == null) {
                continue;
            }
            String existingDimension = cleanField(existing.dimension, "minecraft:overworld");
            boolean sameDimension = existingDimension.equals(dimension);
            boolean sameName = cleanField(existing.name, "Waypoint").equalsIgnoreCase(name);
            boolean samePosition = existing.x == incoming.x && existing.y == incoming.y && existing.z == incoming.z;
            if (sameDimension && (sameName || samePosition)) {
                current.waypoints.set(i, incoming);
                updated = true;
                break;
            }
        }

        if (!updated) {
            current.waypoints.add(incoming);
        }

        current.enabled = true;
        if (!save(current)) {
            return AddResult.FAILED;
        }
        config = current;
        rebuildCache();
        return updated ? AddResult.UPDATED : AddResult.ADDED;
    }

    private static void rebuildCache() {
        Config current = config;
        if (current == null || !current.enabled || current.waypoints == null || current.waypoints.isEmpty()) {
            String groupName = current == null ? "ServerPublic" : cleanField(current.groupName, "ServerPublic");
            cachedPayload = null;
            cachedSummary = new Summary(current != null && current.enabled, groupName, 0, "");
            return;
        }

        List<PacketHandler.PublicWaypoint> waypoints = new ArrayList<>();
        String groupName = cleanField(current.groupName, "ServerPublic");
        for (Waypoint waypoint : current.waypoints) {
            if (waypoint == null || !waypoint.enabled) {
                continue;
            }
            String name = cleanField(waypoint.name, "Waypoint");
            String initial = cleanField(waypoint.initial, name.substring(0, Math.min(1, name.length())));
            String type = cleanField(waypoint.type, "0");
            String dimension = cleanField(waypoint.dimension, "minecraft:overworld");
            waypoints.add(new PacketHandler.PublicWaypoint(
                    name,
                    initial,
                    waypoint.x,
                    waypoint.y,
                    waypoint.z,
                    waypoint.color,
                    waypoint.disabled,
                    type,
                    groupName,
                    dimension
            ));
        }

        if (waypoints.isEmpty()) {
            cachedPayload = null;
            cachedSummary = new Summary(current.enabled, groupName, 0, "");
            return;
        }
        String hash = HashUtils.computeHash(GSON.toJson(waypoints).getBytes(StandardCharsets.UTF_8));
        cachedPayload = new PacketHandler.PublicWaypointsPayload(groupName, current.replaceGroup, hash, waypoints);
        cachedSummary = new Summary(current.enabled, groupName, waypoints.size(), hash);
    }

    private static Config sanitize(Config loaded) {
        Config result = loaded == null ? defaultConfig() : loaded;
        result.groupName = cleanField(result.groupName, "ServerPublic");
        if (result.waypoints == null) {
            result.waypoints = new ArrayList<>();
        }
        return result;
    }

    private static String cleanField(String value, String fallback) {
        String cleaned = value == null || value.isBlank() ? fallback : value.trim();
        return cleaned.replace(':', '_').replace('\n', ' ').replace('\r', ' ');
    }

    private static Config defaultConfig() {
        Config cfg = new Config();
        cfg.enabled = false;
        cfg.groupName = "ServerPublic";
        cfg.replaceGroup = true;
        cfg.waypoints = new ArrayList<>();
        Waypoint spawn = new Waypoint();
        spawn.enabled = true;
        spawn.name = "Spawn";
        spawn.initial = "S";
        spawn.dimension = "minecraft:overworld";
        spawn.x = 0;
        spawn.y = 64;
        spawn.z = 0;
        spawn.color = 2;
        spawn.disabled = false;
        spawn.type = "0";
        cfg.waypoints.add(spawn);
        return cfg;
    }

    private static Config copyConfig(Config source) {
        Config copy = new Config();
        Config safeSource = source == null ? defaultConfig() : source;
        copy.enabled = safeSource.enabled;
        copy.groupName = safeSource.groupName;
        copy.replaceGroup = safeSource.replaceGroup;
        copy.waypoints = new ArrayList<>();
        if (safeSource.waypoints != null) {
            for (Waypoint waypoint : safeSource.waypoints) {
                copy.waypoints.add(copyWaypoint(waypoint));
            }
        }
        return copy;
    }

    private static Waypoint copyWaypoint(Waypoint source) {
        if (source == null) {
            return null;
        }
        Waypoint copy = new Waypoint();
        copy.enabled = source.enabled;
        copy.name = source.name;
        copy.initial = source.initial;
        copy.dimension = source.dimension;
        copy.x = source.x;
        copy.y = source.y;
        copy.z = source.z;
        copy.color = source.color;
        copy.disabled = source.disabled;
        copy.type = source.type;
        return copy;
    }

    public static final class Config {
        public boolean enabled;
        public String groupName;
        public boolean replaceGroup;
        public List<Waypoint> waypoints;
    }

    public static final class Waypoint {
        public boolean enabled = true;
        public String name;
        public String initial;
        public String dimension;
        public int x;
        public int y;
        public int z;
        public int color = 0;
        public boolean disabled;
        public String type = "0";
    }

    public record Summary(boolean enabled, String groupName, int count, String hash) {
    }

    public enum AddResult {
        ADDED,
        UPDATED,
        FAILED
    }
}
