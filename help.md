# MapSyncer Rebuild Help

The starting point for MapSyncer Rebuild's project information, documentation and support.
For installing and using it, read [README.md](README.md) first; for the licence, see [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Project information

| Item | Value |
| --- | --- |
| Name | MapSyncer Rebuild for Xaero's World Map |
| Version | `0.7.0` |
| Mod ID / plugin name | `mapsyncer` / `MapSyncer` |
| Minecraft | `26.2` |
| Fabric Loader | `0.19.3` |
| Fabric API | `0.158.0+26.2` |
| Paper | `26.2` (built against `26.2.build.116-stable`) |
| Java | `25` |
| Rebuild by | `ShanHe_YF` |
| Original project | [RuoChennn/MapSyncer-for-XaeroWorldmap](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap) |
| Licence | GNU GPLv3, `GPL-3.0-only` |

MapSyncer Rebuild sends map regions the server has already generated or explored to each player's Xaero's World Map, and adds public waypoints, radius syncing, incremental cache generation, Voxy syncing and a client GUI. The client is a Fabric mod; the server runs either as a Fabric mod or as a Paper plugin.

## Documentation

| Document | Contents |
| --- | --- |
| [README.md](README.md) | The main page: installation, commands, configuration, building and FAQ |
| [LICENSE](LICENSE) | The full GNU GPLv3 text |
| [NOTICE](NOTICE) | Attribution, what was rewritten, and who maintains this Rebuild |
| `gradle.properties` | The Minecraft, Fabric and Paper versions this build targets |
| `src/main/resources/fabric.mod.json` | Fabric mod metadata |
| `paper/src/main/resources/plugin.yml` | Paper plugin metadata |

## Installing

### Server (Fabric)

Put these in the server's `mods/` directory:

- Fabric Loader
- Fabric API
- `mapsyncer-rebuild-26.2-0.7.0-client.jar`

### Server (Paper)

Put this in the server's `plugins/` directory:

- `mapsyncer-rebuild-26.2-0.7.0-paper.jar`

No Fabric Loader, no Fabric API, no other plugins.

Neither server needs Xaero's World Map installed.

### Client

Put these in the client's `mods/` directory:

- Fabric Loader
- Fabric API
- Xaero's World Map
- `mapsyncer-rebuild-26.2-0.7.0-client.jar`

For Voxy syncing, install and enable Voxy as well. Public waypoints need the client's Xaero waypoint support.

## Common commands

### Player commands

```text
/mapsyncer
/mapsyncer help
/mapsyncer gui
/mapsyncergui
/mapsyncer sync
/mapsyncer sync radius <blocks>
/mapsyncer sync all
/mapsyncer sync <dimension>
```

Examples:

```text
/mapsyncer sync
/mapsyncer sync radius 1000
/mapsyncer sync all
/mapsyncer sync minecraft:the_nether
```

### Op commands

```text
/mapsyncer generate
/mapsyncer generate <dimension>
/mapsyncer generate <dimension> force
/mapsyncer generate <dimension> <x> <z>
/mapsyncer status
/mapsyncer incremental run
/mapsyncer incremental off
/mapsyncer incremental tick <interval>
/mapsyncer incremental scheduled <hour> <minute>
```

On a new server, have an op run:

```text
/mapsyncer generate
```

Once the base cache exists, players can sync through the GUI or `/mapsyncer sync`.

## Configuration files

| File | Purpose |
| --- | --- |
| `mapsyncer.json` | Server config: generation, syncing, throttling, radius syncing, incremental scanning |
| `mapsyncer-client.json` | Client config: auto-sync, HUD, chat messages |
| `mapsyncer-public-waypoints.json` | Public waypoints |

They live in `config/` on Fabric and in `plugins/MapSyncer/` on Paper, and are written on first start. After editing one by hand, restart the server, or save the setting through the GUI or a command.

## FAQ

### Why do I see more than one map in my client?

An older client may still have `mw$map`. MapSyncer Rebuild writes ordinary syncs into `mw$default` and migrates the old data in the background, so no further duplicates are created.

### Will public waypoints overwrite private ones?

No. MapSyncer Rebuild only replaces the public group, `ServerPublic` by default. Private waypoints and other groups are left alone.

### Does it work without Voxy?

Yes. Voxy syncing is optional; without Voxy, ordinary Xaero map syncing works as normal.

### The map did not refresh right after syncing.

Usually Xaero's automatic reload catches up shortly. If nothing appears, reopen the Xaero map, or rejoin the server.

### What do I need to build it?

Java 25. On Windows:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --stacktrace
```

The artifacts are:

```text
build/libs/mapsyncer-rebuild-26.2-0.7.0-client.jar        # client, and Fabric servers
paper/build/libs/mapsyncer-rebuild-26.2-0.7.0-paper.jar  # Paper plugin
```

## Getting help

When reporting a problem, please include:

- the version, e.g. `0.7.0`, as shown in your launcher or by `/plugins`
- the Minecraft version
- whether the server is Fabric or Paper, and its version
- for Fabric: the Fabric Loader and Fabric API versions
- whether both client and server have MapSyncer Rebuild installed
- whether Xaero's World Map, Voxy or other map mods are installed
- the full error log or crash report
- how to reproduce it

On GitHub, please use Issues and attach the log and the steps. That makes it much easier to tell an installation problem from a configuration one, a map cache problem, or a compatibility issue.

## Licence

MapSyncer Rebuild is released by **ShanHe_YF** under the GNU GPLv3, SPDX identifier `GPL-3.0-only`.
The full text is in [LICENSE](LICENSE); attribution and the record of what was rewritten are in [NOTICE](NOTICE).
