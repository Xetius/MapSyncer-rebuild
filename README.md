# MapSyncer Rebuild for Xaero's World Map

MapSyncer Rebuild sends map regions the server has already generated or explored to each player's Xaero's World Map, and adds public waypoints, radius syncing, incremental cache generation, Voxy syncing and a client GUI.

The client is a Fabric mod. The server comes in two forms — pick one:

- **Fabric mod**: the same jar as the client, dropped into a Fabric server's `mods/`.
- **Paper plugin**: a separate `-paper.jar`, dropped into a Paper server's `plugins/` (Purpur and other Paper forks included).

Both servers run the same sync logic and speak the same protocol, so the client cannot tell which one it is connected to.

> Based on [RuoChennn/MapSyncer-for-XaeroWorldmap](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap).
> This Rebuild is maintained by **ShanHe_YF**.

## Contents

- [About](#about)
- [Versions](#versions)
- [Features](#features)
- [Requirements](#requirements)
- [Getting started](#getting-started)
- [Paper servers](#paper-servers)
- [Client GUI](#client-gui)
- [Commands](#commands)
- [Configuration](#configuration)
- [Public waypoints](#public-waypoints)
- [The default Xaero map and migration](#the-default-xaero-map-and-migration)
- [Voxy syncing](#voxy-syncing)
- [Performance and sync strategy](#performance-and-sync-strategy)
- [Version numbering](#version-numbering)
- [Licence](#licence)
- [Building](#building)
- [FAQ](#faq)
- [Credits](#credits)

## About

Xaero's World Map normally keeps its map data on the client. A player joining a long-established server has to re-explore everything themselves before the map fills in. MapSyncer Rebuild has the server hand over the map cache it already has, so players get the server's view of the world straight away.

This Rebuild focuses on:

- Running on both Fabric servers and Paper servers from one codebase.
- Targeting the current Fabric release and Minecraft `26.2`.
- Writing ordinary map syncs into Xaero's `mw$default`, rather than creating a second `mw$map`.
- A client GUI that separates ordinary player features from admin ones.
- Public waypoints that do not trample players' private waypoints.
- Radius syncing, incremental scanning, dirty-region tracking and adaptive throttling.
- Optional Voxy syncing.

## Versions

| Item | Version |
| --- | --- |
| Version | `0.6.1` |
| Release jars | `mapsyncer-rebuild-26.2-0.6.1-client.jar`, `mapsyncer-rebuild-26.2-0.6.1-paper.jar` |
| Minecraft | `26.2` |
| Fabric Loader | `0.19.3` recommended, `>=0.19.0` at runtime |
| Fabric API | `0.158.0+26.2` recommended, `*` at runtime |
| Fabric Loom | `1.16.3` |
| Paper | `26.2` (built against `26.2.build.116-stable`) |
| paperweight-userdev | `2.0.0-beta.22` |
| Gradle wrapper | `9.4.1` |
| Java | `25` |
| Mod ID / plugin name | `mapsyncer` / `MapSyncer` |
| Rebuild by | `ShanHe_YF` |
| Original project | [RuoChennn/MapSyncer-for-XaeroWorldmap](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap) |

## Features

- **Fabric and Paper servers**: one set of sync logic, running either as a Fabric server mod or as a Paper plugin.
- **Server-side map cache syncing**: once the server has built its Xaero cache, clients can sync the current dimension, every dimension, or a named one.
- **Writes to `mw$default`**: ordinary syncing no longer creates a second `mw$map`, so players do not end up with duplicate maps.
- **Migration of old maps**: an existing `mw$map` or other historical `mw$*` directory has its missing `.zip` regions copied into `mw$default` in the background.
- **Radius syncing**: players can sync only the map within a given radius, which keeps the first sync manageable on large servers.
- **Public waypoints**: the server can push a shared waypoint group, merged into Xaero's `waypoints.txt`.
- **Admin GUI**: ops can see server status, trigger generation, adjust sync settings, and import public waypoints from their own Xaero waypoints.
- **Incremental generation**: rescans on a tick interval or once a day, and can be run on demand.
- **Dirty-region tracking**: block changes narrow down what a rescan has to look at.
- **Adaptive throttling**: sync speed backs off and recovers based on a player's ping.
- **Voxy syncing**: where both client and server support it, the current dimension's Voxy data can be synced.

## Requirements

### Server (Fabric)

- Fabric Loader
- Fabric API
- MapSyncer Rebuild (`mapsyncer-rebuild-26.2-0.6.1-client.jar`)

### Server (Paper)

- Paper `26.2` (or a fork such as Purpur)
- The MapSyncer Rebuild plugin (`mapsyncer-rebuild-26.2-0.6.1-paper.jar`)

The Paper build needs no Fabric Loader, no Fabric API, and no other plugins.

Neither server needs Xaero's World Map installed. Only Voxy syncing calls for anything else, and what that needs depends on your setup.

### Client

- Fabric Loader
- Fabric API
- MapSyncer Rebuild
- Xaero's World Map

Optional:

- Voxy: only for Voxy syncing.
- Xaero waypoint support: only for public waypoints, depending on the player's Xaero setup.

## Getting started

1. Install the server side, either way:
   - Fabric server: put `mapsyncer-rebuild-26.2-0.6.1-client.jar` in `mods/`.
   - Paper server: put `mapsyncer-rebuild-26.2-0.6.1-paper.jar` in `plugins/`.
2. Put `mapsyncer-rebuild-26.2-0.6.1-client.jar` in the client's `mods/` directory. The client is always the Fabric mod.
3. The client needs matching Fabric Loader and Fabric API versions, as does a Fabric server. A Paper server needs neither.
4. Start the server once, so MapSyncer writes its default config.
5. As an op, run this first — the console can run it too, without the leading `/`:

```text
/mapsyncer generate
```

6. Once the server has built its cache, players can run:

```text
/mapsyncer sync
```

Or open the GUI:

```text
/mapsyncer gui
```

## Paper servers

The Paper plugin runs the same scanning, conversion, caching, throttling and waypoint code as the Fabric server. Commands, config keys and the network protocol are identical, and the client needs no changes. Only the differences are listed here.

### Config directory

| Platform | Directory |
| --- | --- |
| Fabric | `config/` |
| Paper | `plugins/MapSyncer/` |

The file names are the same: `mapsyncer.json` and `mapsyncer-public-waypoints.json`.

### Permissions

The Fabric build uses vanilla permission levels, so admin commands need op level 4.

The Paper build also accepts the permission node `mapsyncer.admin` (default `op`), which lets permission plugins such as LuckPerms grant access without opping. Either one is enough.

### Payload size and splitting

Fabric uses vanilla `custom_payload`, which allows 1MB per payload. Paper goes through Bukkit plugin messaging, which caps a message at 32766 bytes, so the plugin clamps `maxSyncPacketSize` to under 30000 bytes and splits regions into more parts.

This changes nothing about the result and needs no configuration: a large `maxSyncPacketSize` is simply clamped, and throughput is still governed by `syncSpeedLimitKBps` and adaptive throttling.

### Dirty-region tracking

The Fabric build mixes into block updates and therefore sees every block change. Paper has no mixins, so the plugin listens to Bukkit events instead: placement, breaking, explosions, growth, pistons, buckets, newly generated chunks and so on.

Treat this as an accelerator rather than the source of truth. Changes it misses — another plugin writing blocks directly, for instance — are still picked up by the incremental scan through `.mca` file timestamps, just not until the next scan. Keeping `dirtyRegionFallbackFullScan = true` (the default) is therefore recommended on Paper.

### World directories

The Paper build asks Bukkit for each world's own directory rather than guessing one from the dimension ID, so worlds created by plugins such as Multiverse are located correctly.

### Handshake timing

Bukkit's player-join event can fire before the client announces which channels it listens on. The plugin therefore does not handshake on join: it waits until the client registers the `mapsyncer:server_installed` channel before sending the "server has MapSyncer" notice and the public waypoints, so neither is lost. Players see no difference.

### Console output

Command replies use translation keys, such as `mapsyncer.generate.full_complete`, which the client localises. A server console has no language file for them and prints the key itself. The Fabric server behaves the same way.

## Client GUI

Open it with:

```text
/mapsyncer gui
/mapsyncergui
```

What the GUI shows depends on who opens it:

| Role | Pages |
| --- | --- |
| Player | `Sync`, `Settings` |
| Op | `Sync`, `Admin`, `Settings` |

Typical use:

- Players sync the current dimension, every dimension, or a radius, and adjust auto-sync and HUD settings.
- Ops check server status, trigger generation, force-refresh a dimension, and run an incremental scan.
- Ops scan their local Xaero waypoints and import them as public waypoints.

## Commands

### Player commands

| Command | Effect |
| --- | --- |
| `/mapsyncer` | Show help |
| `/mapsyncer help` | Show help |
| `/mapsyncer gui` | Open the MapSyncer GUI |
| `/mapsyncergui` | Shorthand for opening the GUI |
| `/mapsyncer sync` | Sync the current dimension |
| `/mapsyncer sync radius <blocks>` | Sync the current dimension within a radius |
| `/mapsyncer sync all` | Sync every dimension that has a cache |
| `/mapsyncer sync <dimension>` | Sync one dimension |

Examples:

```text
/mapsyncer sync
/mapsyncer sync radius 1000
/mapsyncer sync all
/mapsyncer sync overworld
/mapsyncer sync minecraft:the_nether
```

`<dimension>` accepts both short names and full dimension IDs:

- `overworld`
- `the_nether`
- `the_end`
- `minecraft:overworld`
- `minecraft:the_nether`
- `minecraft:the_end`
- the full ID of any modded dimension

### Op and server commands

| Command | Effect |
| --- | --- |
| `/mapsyncer` / `/mapsyncer help` | Show the admin help |
| `/mapsyncer gui` | Open the admin GUI; the console cannot use it |
| `/mapsyncer generate` | Build the map cache for every dimension, in the background |
| `/mapsyncer generate <dimension>` | Build the cache for one dimension |
| `/mapsyncer generate <dimension> force` | Clear and rebuild one dimension's cache |
| `/mapsyncer generate <dimension> <x> <z>` | Build one MCA region |
| `/mapsyncer status` | Show generation progress, incremental mode and cache statistics |
| `/mapsyncer incremental run` | Run one incremental scan now |
| `/mapsyncer incremental off` | Turn incremental updates off |
| `/mapsyncer incremental tick` | Enable tick-interval updates at the configured interval |
| `/mapsyncer incremental tick <interval>` | Set the interval and enable tick-interval updates |
| `/mapsyncer incremental scheduled` | Enable daily updates at the configured time |
| `/mapsyncer incremental scheduled <hour>` | Run daily at that hour, on the hour |
| `/mapsyncer incremental scheduled <hour> <minute>` | Run daily at that time |

Examples:

```text
/mapsyncer generate
/mapsyncer generate minecraft:overworld
/mapsyncer generate minecraft:overworld force
/mapsyncer generate minecraft:overworld 0 0
/mapsyncer status
/mapsyncer incremental run
/mapsyncer incremental tick 200
/mapsyncer incremental scheduled 4 30
```

Admin commands need vanilla permission level 4 (`LEVEL_OWNERS`), or the `mapsyncer.admin` node on Paper. Run `/mapsyncer generate` once after first starting the server, then let players sync through the GUI or `/mapsyncer sync`.

## Configuration

MapSyncer Rebuild writes its config into the platform's config directory: `config/` on Fabric, `plugins/MapSyncer/` on Paper. After editing a file by hand, restart the server, or save the equivalent setting through the GUI or a command.

### Server config

Path:

```text
config/mapsyncer.json                (Fabric)
plugins/MapSyncer/mapsyncer.json     (Paper)
```

| Key | Meaning |
| --- | --- |
| `enableDebugLogging` | Enable debug logging |
| `maxConcurrentRegions` | How many regions the server converts at once |
| `maxSyncPacketSize` | Payload size used when splitting map data |
| `syncSpeedLimitKBps` | Fixed per-player speed limit |
| `enableAdaptiveSyncThrottle` | Adjust the speed limit from player ping |
| `adaptivePingThresholdMs` | Ping at which the speed backs off |
| `adaptivePingRecoverMs` | Ping at which the speed recovers |
| `adaptiveThrottleAdjustCooldownMs` | Cooldown between adjustments, default `2000ms` |
| `adaptiveMinSyncSpeedKBps` | Floor for the adaptive speed |
| `adaptiveIncreaseStepKBps` | How much the speed recovers per step on a stable connection |
| `adaptiveDecreaseFactor` | How sharply the speed drops on an unstable one |
| `adaptiveStableRecoverSamples` | Stable samples needed before the speed recovers |
| `adaptiveUnlimitedCeilingKBps` | Ceiling for the adaptive speed when no fixed limit is set |
| `enableVoxySync` | Allow Voxy syncing |
| `incrementalUpdateMode` | `DISABLED`, `TICK` or `SCHEDULED` |
| `incrementalUpdateIntervalTicks` | Interval for `TICK` mode |
| `scheduledUpdateHour` | Hour for `SCHEDULED` mode |
| `scheduledUpdateMinute` | Minute for `SCHEDULED` mode |
| `enableDirtyRegionTracking` | Narrow incremental scans to regions known to have changed |
| `dirtyRegionFallbackFullScan` | Fall back to a full scan when no regions are marked dirty |
| `maxDirtyRegionsPerIncrementalRun` | Dirty regions handled per incremental run |
| `incrementalForceSaveBeforeScan` | Force a chunk save before scanning |
| `enableRadiusSync` | Allow radius syncing |
| `maxRadiusSyncBlocks` | Largest radius a player may ask for |
| `radiusSyncCenterMode` | `PLAYER_POSITION`, `WORLD_SPAWN` or `FIXED` |
| `radiusSyncFixedDimension` | Dimension of the fixed centre |
| `radiusSyncFixedX` / `radiusSyncFixedY` / `radiusSyncFixedZ` | Coordinates of the fixed centre |
| `defaultScanMode` | Default scan mode |
| `defaultCaveStart` | Default cave start height |
| `dimensionConfigs` | Per-dimension scan settings |

The default dimension settings:

```text
minecraft:overworld|SURFACE|63|true|false|-64|384|384
minecraft:the_nether|CAVE|63|false|true|0|256|256
minecraft:the_end|SURFACE|63|false|false|0|256|256
```

### Client config

Path:

```text
config/mapsyncer-client.json
```

| Key | Meaning |
| --- | --- |
| `autoSyncOnJoin` | Sync automatically after joining |
| `showSyncHud` | Show the sync HUD |
| `syncProgressChatIntervalPercent` | Percentage step between chat progress messages; `0` disables them |
| `autoSyncDelaySeconds` | Delay before an automatic sync starts |

### Public waypoint config

Path:

```text
config/mapsyncer-public-waypoints.json                (Fabric)
plugins/MapSyncer/mapsyncer-public-waypoints.json     (Paper)
```

| Key | Meaning |
| --- | --- |
| `enabled` | Enable public waypoint syncing |
| `groupName` | The Xaero group public waypoints go into, default `ServerPublic` |
| `replaceGroup` | Replace an existing group of that name |
| `waypoints` | The public waypoints themselves |

## Public waypoints

Public waypoints are written in Xaero's `waypoints.txt` format:

```text
waypoint:name:initial:x:y:z:color:disabled:type:set:dimension
```

MapSyncer Rebuild only manages the public group, `ServerPublic` by default. It reads the existing `waypoints.txt`, removes the old lines of that group, and appends the current public waypoints.

It never touches:

- a player's private waypoints
- any other waypoint group
- anything else in the Xaero config

If the waypoint file is locked by Xaero, the client skips the write and says to sync again later.

An op can promote their own Xaero waypoints to public ones:

1. Create a private waypoint in Xaero as usual.
2. Open `/mapsyncer gui`.
3. Go to the `Admin` page.
4. Scan local waypoints.
5. Pick the ones to import.
6. Save; they are written to the server's `mapsyncer-public-waypoints.json`.

Importing does not modify the local private `waypoints.txt`. The server checks op permission and applies the rule "same dimension and same name, or same dimension and same coordinates, updates; otherwise appends".

## The default Xaero map and migration

Ordinary map syncing always writes into:

```text
mw$default
```

which is what stops players ending up with duplicate maps, such as the `mw$map` older versions produced.

Where a client already has an `mw$map` or another historical `mw$*` directory, MapSyncer Rebuild migrates it in the background:

- copies only the `.zip` regions that are missing
- keeps the `caves/<layer>` structure
- ignores `.part`, `.temp` and anything that is not a `.zip`
- never deletes the old directory
- never touches `waypoints.txt`

When the migration finishes it triggers an Xaero map reload, so players usually do not have to rejoin.

## Voxy syncing

Voxy syncing is optional. The GUI detects whether the client has Voxy installed and enabled, and the Voxy button only works when both sides support it.

Worth knowing:

- Voxy syncing only covers the current dimension.
- It uses the server's last saved data.
- The NBT is not cleaned up in any way.
- Blocks placed just now may not appear until the server next saves.
- With Voxy syncing off on the server, ordinary Xaero map syncing still works normally.

## Performance and sync strategy

### Ordinary map syncing

Ordinary syncing writes into `mw$default`, sending the server's generated cache in parts. `syncSpeedLimitKBps` caps the rate per player.

### Adaptive throttling

With `enableAdaptiveSyncThrottle` on, the server adjusts each player's sync speed from their ping: slower when the connection degrades, recovering gradually once it steadies.

### Incremental generation

| Mode | Meaning |
| --- | --- |
| `DISABLED` | No automatic updates |
| `TICK` | Run every N ticks |
| `SCHEDULED` | Run once a day at a set time |

### Dirty-region tracking

With `enableDirtyRegionTracking` on, the server notes which regions changed and handles those first on an incremental update, which keeps rescans cheap on large maps.

## Version numbering

The version and the file names are two different things, and only the version goes into
the jar's metadata.

`mod_version` in `gradle.properties` is a plain semantic version, currently `0.6.1`. It is
what lands in `fabric.mod.json`, in `plugin.yml` and in each jar's `Implementation-Version`
manifest attribute, so it is what Fabric Loader, Bukkit and launchers such as Prism display.
It must stay valid SemVer: a name like `mapsyncer-rebuild-26.2-0.1` is not one, and a
launcher shown that string has no version to display.

The release jars are named from that version plus the Minecraft version:

```text
mapsyncer-rebuild-<minecraft_version>-<mod_version>-client.jar
mapsyncer-rebuild-<minecraft_version>-<mod_version>-paper.jar
```

| Field | Meaning |
| --- | --- |
| `mapsyncer-rebuild` | The mod name |
| `<minecraft_version>` | The Minecraft version it targets, from `gradle.properties` |
| `<mod_version>` | The semantic version, from `gradle.properties` |
| `-client` / `-paper` | Which side the jar is for |

Bumping a release means editing `mod_version` alone; the file names, the metadata and the
release notes all follow from it.

## Licence

This Rebuild is released by **ShanHe_YF** under the **GNU General Public License v3.0 only**, SPDX identifier:

```text
GPL-3.0-only
```

The full text is in [LICENSE](LICENSE); attribution and the record of what was rewritten are in [NOTICE](NOTICE).

Distributing, modifying or redistributing this project means following GPLv3: keeping the copyright notices, keeping the licence text, and providing the corresponding source when you distribute a modified version.

## Building

Java 25 is required. On Windows, point at Java 25 explicitly:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --stacktrace
```

On Linux or macOS:

```bash
./gradlew clean build
```

That builds both sides. The artifacts are:

```text
build/libs/mapsyncer-rebuild-26.2-0.6.1-client.jar          # clients, and Fabric servers
build/libs/mapsyncer-rebuild-26.2-0.6.1-client-sources.jar  # sources
paper/build/libs/mapsyncer-rebuild-26.2-0.6.1-paper.jar     # Paper servers
```

To build only one side:

```bash
./gradlew :build         # the Fabric mod
./gradlew :paper:build   # the Paper plugin
```

The Paper build downloads a Paper development bundle the first time it runs, which takes a few minutes.

### Continuous integration and releases

Two GitHub Actions workflows live in `.github/workflows/`:

| Workflow | Runs on | What it does |
| --- | --- | --- |
| `build.yml` | Pushes to `main`, pull requests, manual runs | Builds both jars and attaches them to the run as artifacts, kept for 14 days |
| `release.yml` | Pushing a `v*` tag, or a manual run | Builds both jars and publishes a GitHub release with them |

To cut a release, tag a commit and push the tag:

```bash
git tag v0.6.1
git push origin v0.6.1
```

The release then carries three assets plus a `checksums.txt` of their SHA-256 sums:

```text
mapsyncer-rebuild-26.2-0.6.1-client.jar          # clients, and Fabric servers
mapsyncer-rebuild-26.2-0.6.1-paper.jar           # Paper servers
mapsyncer-rebuild-26.2-0.6.1-client-sources.jar  # sources
```

The release notes name the version from `gradle.properties` and say which file goes where, with GitHub's generated changelog appended. The build fails rather than publishing if either jar is missing.

Releases can also be started from the Actions tab: run **Release**, give it a tag name, and tick *draft* to review it before it goes public. A tag that does not exist yet is created at the commit the run started from.

Both workflows build on Java 25 (Temurin) and cache `~/.gradle`, which covers the Minecraft jars Loom downloads and the Paper development bundle. Expect the first run to be slow and later ones to be much quicker.

## FAQ

### Why do I see two maps in my client?

An older client may still have `mw$map`. MapSyncer Rebuild writes ordinary syncs into `mw$default` and merges the old data in the background. Once that finishes, the default map is the one to use.

### Will public waypoints overwrite my private ones?

No. MapSyncer Rebuild only replaces the public group; private waypoints and other groups are left alone.

### Can I sync maps without Xaero's waypoint support?

Yes. The public waypoint features are skipped or unavailable, but ordinary map syncing is unaffected.

### What happens if the server has Voxy syncing off?

The Voxy button stays disabled and ordinary Xaero map syncing carries on as normal.

### The map did not refresh right after syncing.

Usually the automatic reload catches up shortly. If nothing appears, reopen the Xaero map, or rejoin the server.

### What should I do first on a new server?

Have an op run:

```text
/mapsyncer generate
```

Once the base cache exists, players can sync through the GUI or `/mapsyncer sync`.

## Credits

- Original project: [RuoChennn/MapSyncer-for-XaeroWorldmap](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap)
- Rebuild by **ShanHe_YF**
- Thanks to Fabric, Paper, Xaero's World Map, Voxy and the surrounding community projects.
