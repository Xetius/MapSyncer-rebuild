# MapSyncer Rebuild for Xaero World Map

MapSyncer Rebuild 是一个 Fabric 服务端/客户端双端 mod，用于把服务端已经探索或已经生成的地图区域同步到客户端的 Xaero World Map，并提供公共路标、半径同步、增量缓存生成、Voxy 同步和客户端 GUI。

> 本项目基于 [RuoChennn/MapSyncer-for-XaeroWorldmap](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap) 改写。  
> 当前 Rebuild 版本由 **ShanHe_YF** 制作、整理和维护。

## 目录

- [项目说明](#项目说明)
- [当前版本](#当前版本)
- [主要特性](#主要特性)
- [安装要求](#安装要求)
- [快速开始](#快速开始)
- [客户端 GUI](#客户端-gui)
- [命令说明](#命令说明)
- [配置文件](#配置文件)
- [公共路标同步](#公共路标同步)
- [Xaero 默认地图与迁移](#xaero-默认地图与迁移)
- [Voxy 同步](#voxy-同步)
- [性能与同步策略](#性能与同步策略)
- [版本命名规则](#版本命名规则)
- [许可证](#许可证)
- [构建项目](#构建项目)
- [常见问题](#常见问题)
- [致谢](#致谢)

## 项目说明

原版 Xaero World Map 的地图数据主要存放在客户端本地。如果玩家首次进入一个已经探索过很久的服务器，客户端通常需要自己重新探索地图，才能看到完整地形。MapSyncer Rebuild 的目标是让服务端把已经生成好的地图缓存下发给客户端，让玩家更快拿到服务器端已有的世界地图数据。

这个 Rebuild 版本重点整理了以下方向：

- 适配当前 Fabric 模板和 Minecraft `26.1.2`。
- 普通地图同步固定写入 Xaero 的 `mw$default`，避免重复创建第二个 `mw$map`。
- 增加/整理客户端 GUI，普通玩家和 OP 管理功能分区显示。
- 支持公共路标同步，并尽量避免覆盖玩家私人路标。
- 支持半径同步、增量扫描、脏 region 追踪和自适应同步限速。
- 提供 Voxy 同步入口，用于可选的 Voxy 地图数据同步。

## 当前版本

| 项目 | 版本 |
| --- | --- |
| Mod 版本 | `mapsyncer-rebuild-26.1.2-0.3` |
| Minecraft / Fabric | `26.1.2` |
| Fabric Loader | 推荐 `0.19.3`，运行时 `>=0.19.0` |
| Fabric API | 推荐 `0.151.0+26.1.2`，运行时 `*` |
| Fabric Loom | `1.16.3` |
| Gradle Wrapper | `9.4.1` |
| Java | `25` |
| Mod ID | `mapsyncer` |
| 当前 Rebuild 制作 | `ShanHe_YF` |
| 原始项目 | [RuoChennn/MapSyncer-for-XaeroWorldmap](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap) |

## 主要特性

- **服务端地图缓存同步**：服务端生成 Xaero 地图缓存后，客户端可按当前维度、全部维度或指定维度同步。
- **固定写入 `mw$default`**：普通地图同步不再创建第二个 `mw$map`，减少玩家客户端里出现多个地图目录的情况。
- **旧地图迁移**：如果客户端存在旧的 `mw$map` 或历史 `mw$*` 目录，会在后台把缺失的 `.zip` 区域迁移到 `mw$default`。
- **半径同步**：玩家可以只同步自己周围指定半径内的地图，降低大地图服务器的首次同步压力。
- **公共路标同步**：服务端可以下发公共路标，客户端按 Xaero `waypoints.txt` 格式合并写入。
- **OP 管理 GUI**：OP 可以在 GUI 中查看状态、触发生成、管理同步参数，并从本地 Xaero 路标导入公共路标。
- **增量缓存生成**：支持按 tick 间隔或每日定时执行增量扫描，也支持手动执行一次增量扫描。
- **脏 region 追踪**：通过区块变更追踪减少无意义的全量扫描。
- **自适应限速**：可根据玩家 Ping 自动降低或恢复同步速度。
- **Voxy 同步支持**：在客户端和服务端都满足条件时，可同步当前维度的 Voxy 数据。

## 安装要求

### 服务端

服务端需要安装：

- Fabric Loader
- Fabric API
- MapSyncer Rebuild

服务端不需要安装 Xaero's World Map。只有使用 Voxy 同步时，才需要按服务器实际需求准备 Voxy 相关环境。

### 客户端

客户端需要安装：

- Fabric Loader
- Fabric API
- MapSyncer Rebuild
- Xaero's World Map

可选：

- Voxy：仅在使用 Voxy 同步时需要。
- Xaero 路标相关组件：仅在使用公共路标功能时需要，具体以玩家当前 Xaero 安装环境为准。

## 快速开始

1. 将 `mapsyncer-rebuild-26.1.2-0.3.jar` 放入服务端 `mods/` 目录。
2. 将同一个 jar 放入客户端 `mods/` 目录。
3. 确保服务端和客户端都安装了匹配版本的 Fabric Loader 与 Fabric API。
4. 启动服务端，让 MapSyncer 自动生成默认配置文件。
5. OP 首次进服后建议执行：

```text
/mapsyncer generate
```

6. 等服务端生成基础缓存后，玩家可执行：

```text
/mapsyncer sync
```

也可以打开 GUI：

```text
/mapsyncer gui
```

## 客户端 GUI

客户端 GUI 可以通过以下命令打开：

```text
/mapsyncer gui
/mapsyncergui
```

GUI 会根据玩家身份显示不同页面：

| 身份 | 页面 |
| --- | --- |
| 普通玩家 | `Sync`、`Settings` |
| OP | `Sync`、`Admin`、`Settings` |

常见用途：

- 普通玩家可以同步当前维度、全部维度、指定半径地图，并调整客户端自动同步与 HUD 设置。
- OP 可以查看服务端同步状态，触发缓存生成、强制刷新指定维度、执行增量扫描。
- OP 可以扫描本地 Xaero 路标，并导入为服务端公共路标。

## 命令说明

### 玩家命令

| 命令 | 作用 |
| --- | --- |
| `/mapsyncer` | 显示帮助信息 |
| `/mapsyncer help` | 显示帮助信息 |
| `/mapsyncer gui` | 打开 MapSyncer GUI |
| `/mapsyncergui` | 打开 MapSyncer GUI 的快捷命令 |
| `/mapsyncer sync` | 同步当前维度 |
| `/mapsyncer sync radius <blocks>` | 同步当前维度指定半径内的地图 |
| `/mapsyncer sync all` | 同步所有已生成缓存的维度 |
| `/mapsyncer sync <dimension>` | 同步指定维度 |

示例：

```text
/mapsyncer sync
/mapsyncer sync radius 1000
/mapsyncer sync all
/mapsyncer sync overworld
/mapsyncer sync minecraft:the_nether
```

`<dimension>` 支持短名称和完整维度 ID：

- `overworld`
- `the_nether`
- `the_end`
- `minecraft:overworld`
- `minecraft:the_nether`
- `minecraft:the_end`
- 其他模组注册的完整维度 ID

### OP / 服务端管理命令

| 命令 | 作用 |
| --- | --- |
| `/mapsyncer` / `/mapsyncer help` | 显示 OP 管理命令帮助 |
| `/mapsyncer gui` | 让当前 OP 玩家打开管理 GUI，控制台不能使用 GUI |
| `/mapsyncer generate` | 后台生成所有维度的 Xaero 地图缓存 |
| `/mapsyncer generate <dimension>` | 后台生成指定维度缓存 |
| `/mapsyncer generate <dimension> force` | 清理并强制重新生成指定维度缓存 |
| `/mapsyncer generate <dimension> <x> <z>` | 只生成指定 MCA region |
| `/mapsyncer status` | 查看生成进度、增量模式和缓存统计 |
| `/mapsyncer incremental run` | 立即执行一次增量扫描 |
| `/mapsyncer incremental off` | 关闭自动增量更新 |
| `/mapsyncer incremental tick` | 按当前配置的 tick 间隔启用增量更新 |
| `/mapsyncer incremental tick <interval>` | 设置并启用按 tick 间隔执行的增量更新 |
| `/mapsyncer incremental scheduled` | 使用当前配置时间启用每日定时增量更新 |
| `/mapsyncer incremental scheduled <hour>` | 设置每日指定小时执行，分钟默认为 `0` |
| `/mapsyncer incremental scheduled <hour> <minute>` | 设置每日指定时间执行 |

示例：

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

OP 管理命令需要 `LEVEL_OWNERS` 权限。建议首次开服后先执行 `/mapsyncer generate` 生成基础缓存，再让玩家通过 GUI 或 `/mapsyncer sync` 同步。

## 配置文件

MapSyncer Rebuild 会在 `config/` 目录生成配置文件。修改配置后，建议重启服务端或使用对应 GUI/命令保存配置。

### 服务端配置

路径：

```text
config/mapsyncer.json
```

| 配置项 | 说明 |
| --- | --- |
| `enableDebugLogging` | 是否启用调试日志 |
| `maxConcurrentRegions` | 服务端并发处理 region 的数量 |
| `maxSyncPacketSize` | 地图同步分包大小 |
| `syncSpeedLimitKBps` | 每个玩家的固定最高同步速度 |
| `enableAdaptiveSyncThrottle` | 是否启用基于玩家 Ping 的自适应限速 |
| `adaptivePingThresholdMs` | 触发限速的 Ping 阈值 |
| `adaptivePingRecoverMs` | 恢复速度的 Ping 阈值 |
| `adaptiveThrottleAdjustCooldownMs` | 自适应调速冷却时间，默认 `2000ms` |
| `adaptiveMinSyncSpeedKBps` | 自适应限速的最低速度 |
| `adaptiveIncreaseStepKBps` | 网络稳定时每次恢复速度的步进 |
| `adaptiveDecreaseFactor` | 网络不稳定时速度下降比例 |
| `adaptiveStableRecoverSamples` | 恢复速度前需要的稳定样本数量 |
| `adaptiveUnlimitedCeilingKBps` | 不限速时的自适应速度上限 |
| `enableVoxySync` | 是否允许 Voxy 同步 |
| `incrementalUpdateMode` | 增量更新模式，可为 `DISABLED`、`TICK`、`SCHEDULED` |
| `incrementalUpdateIntervalTicks` | tick 模式的执行间隔 |
| `scheduledUpdateHour` | 定时模式的执行小时 |
| `scheduledUpdateMinute` | 定时模式的执行分钟 |
| `enableDirtyRegionTracking` | 是否启用脏 region 精确增量 |
| `dirtyRegionFallbackFullScan` | 脏 region 为空时是否回退到全量扫描 |
| `maxDirtyRegionsPerIncrementalRun` | 单次增量运行最多处理的脏 region 数量 |
| `incrementalForceSaveBeforeScan` | 增量扫描前是否强制保存区块 |
| `enableRadiusSync` | 是否允许半径同步 |
| `maxRadiusSyncBlocks` | 玩家可请求的最大同步半径 |
| `radiusSyncCenterMode` | 半径同步中心模式，可为 `PLAYER_POSITION`、`WORLD_SPAWN`、`FIXED` |
| `radiusSyncFixedDimension` | 固定中心点所在维度 |
| `radiusSyncFixedX` / `radiusSyncFixedY` / `radiusSyncFixedZ` | 固定中心点坐标 |
| `defaultScanMode` | 默认扫描模式 |
| `defaultCaveStart` | 默认洞穴起始高度 |
| `dimensionConfigs` | 维度扫描配置列表 |

默认维度配置示例：

```text
minecraft:overworld|SURFACE|63|true|false|-64|384|384
minecraft:the_nether|CAVE|63|false|true|0|256|256
minecraft:the_end|SURFACE|63|false|false|0|256|256
```

### 客户端配置

路径：

```text
config/mapsyncer-client.json
```

| 配置项 | 说明 |
| --- | --- |
| `autoSyncOnJoin` | 进服后是否自动同步 |
| `showSyncHud` | 是否显示同步 HUD |
| `syncProgressChatIntervalPercent` | 聊天栏进度提示间隔百分比，`0` 表示不按百分比提示 |
| `autoSyncDelaySeconds` | 自动同步延迟秒数 |

### 公共路标配置

路径：

```text
config/mapsyncer-public-waypoints.json
```

主要字段：

| 配置项 | 说明 |
| --- | --- |
| `enabled` | 是否启用公共路标同步 |
| `groupName` | 写入 Xaero 的公共路标组名，默认 `ServerPublic` |
| `replaceGroup` | 是否替换同名公共组 |
| `waypoints` | 公共路标列表 |

## 公共路标同步

公共路标会按 Xaero 的 `waypoints.txt` 文本格式写入：

```text
waypoint:name:initial:x:y:z:color:disabled:type:set:dimension
```

MapSyncer Rebuild 只管理公共组，默认组名为 `ServerPublic`。写入时会读取现有 `waypoints.txt`，删除公共组旧行，再追加新的公共路标。

不会被覆盖的内容：

- 玩家私人路标。
- 其他路标组。
- 与公共组无关的 Xaero 配置。

如果路标文件被 Xaero 占用，客户端会放弃本次写入并提示稍后手动同步。

OP 可以从自己的 Xaero 本地路标导入公共路标：

1. 在 Xaero 中正常创建一个私人路标。
2. 打开 `/mapsyncer gui`。
3. 进入 `Admin` 页面。
4. 扫描本地路标。
5. 在列表中选择要导入的路标。
6. 保存后，该路标会写入服务端 `config/mapsyncer-public-waypoints.json`。

导入不会修改本地私人 `waypoints.txt`。服务端会校验 OP 权限，并按“同维度同名或同维度同坐标则更新，否则追加”的规则保存。

## Xaero 默认地图与迁移

MapSyncer Rebuild 的普通地图同步固定写入：

```text
mw$default
```

这样可以避免客户端出现多个重复地图，例如旧版常见的 `mw$map`。

如果客户端已经存在旧的 `mw$map` 或其他历史 `mw$*` 目录，MapSyncer Rebuild 会在后台迁移：

- 只复制缺失的 `.zip` 地图区域。
- 保留 `caves/<layer>` 结构。
- 忽略 `.part`、`.temp` 和非 `.zip` 文件。
- 不删除旧目录。
- 不修改 `waypoints.txt`。

迁移完成后会自动触发 Xaero 地图批量重载，玩家通常不需要重新进入游戏。

## Voxy 同步

Voxy 同步是可选功能。GUI 会自动检测客户端是否安装并启用了 Voxy。只有客户端和服务端都支持时，Voxy 同步按钮才可用。

注意事项：

- Voxy 同步只处理当前维度。
- 使用服务端最近一次落盘的存档数据。
- 不会清理 NBT。
- 刚放置的方块可能要等服务端保存后才会出现在 Voxy 中。
- 如果服务端未启用 Voxy 同步，普通 Xaero 地图同步仍然可以正常使用。

## 性能与同步策略

### 普通地图同步

普通地图同步会写入 `mw$default`，并根据服务端生成的缓存向客户端分包传输。服务端可通过 `syncSpeedLimitKBps` 限制单个玩家的同步速度。

### 自适应限速

启用 `enableAdaptiveSyncThrottle` 后，服务端会根据玩家 Ping 调整同步速度。网络变差时降低速度，网络恢复稳定后逐步提高速度。

### 增量生成

增量生成支持三种模式：

| 模式 | 说明 |
| --- | --- |
| `DISABLED` | 不自动执行增量更新 |
| `TICK` | 按 tick 间隔执行 |
| `SCHEDULED` | 每天在指定时间执行 |

### 脏 region 追踪

启用 `enableDirtyRegionTracking` 后，服务端会记录发生变化的 region，并在增量更新时优先处理这些区域，减少大地图服务器的扫描压力。

## 版本命名规则

从 `mapsyncer-rebuild-26.1.2-0.1` 开始，MapSyncer Rebuild 的 mod 版本和发布文件名统一使用以下格式：

```text
mapsyncer-rebuild-<游戏版本号>-<发布序号>
```

字段说明：

| 字段 | 含义 |
| --- | --- |
| `mapsyncer-rebuild` | mod 名称 |
| `<游戏版本号>` | 当前适配的 Minecraft/Fabric 游戏版本 |
| `<发布序号>` | 当前游戏版本下的发布序号，从 `0.1` 开始 |

当前版本：

```text
mapsyncer-rebuild-26.1.2-0.3
```

后续命名示例：

| 场景 | 示例 |
| --- | --- |
| 同一游戏版本的第二版 | `mapsyncer-rebuild-26.1.2-0.2` |
| 同一游戏版本的第三版 | `mapsyncer-rebuild-26.1.2-0.3` |
| 升级到新游戏版本后的第一版 | `mapsyncer-rebuild-<新游戏版本号>-0.1` |

以后发布版本请按这个规则命名。

## 许可证

本项目当前 Rebuild 版本由 **ShanHe_YF** 以 **GNU General Public License v3.0 only** 发布，SPDX 标识为：

```text
GPL-3.0-only
```

完整协议正文见仓库根目录的 [LICENSE](LICENSE)。项目归属和改写说明见 [NOTICE](NOTICE)。

这意味着你在分发、修改或再发布本项目时，需要遵守 GPLv3 的条款，包括保留版权声明、保留许可证文本，并在分发修改版本时按 GPLv3 要求提供对应源代码。

## 构建项目

本项目需要 Java 25。Windows 下可以显式指定 Java 25 后构建：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build --stacktrace
```

构建产物位于：

```text
build/libs/
```

当前规则下的主要产物：

```text
build/libs/mapsyncer-rebuild-26.1.2-0.3.jar
build/libs/mapsyncer-rebuild-26.1.2-0.3-sources.jar
```

## 常见问题

### 为什么客户端里会看到两个地图？

旧客户端可能保留了 `mw$map`。MapSyncer Rebuild 会把普通同步固定写入 `mw$default`，并在后台把旧数据合并进去。迁移完成后，玩家主要使用默认地图即可。

### 公共路标会覆盖我的私人路标吗？

不会。MapSyncer Rebuild 只替换公共组，私人路标和其他组不会被修改。

### 没装 Xaero 路标组件还能同步地图吗？

可以。公共路标相关功能会跳过或不可用，但普通地图同步不受影响。

### 服务端没启用 Voxy 会怎样？

Voxy 同步按钮会保持不可用，普通 Xaero 地图同步仍然正常。

### 同步刚结束后地图没有立刻刷新怎么办？

通常等待自动批量重载即可。如果仍然没有显示，可以重新打开 Xaero 地图，或者重新进入服务器。

### 首次开服应该先做什么？

建议 OP 先执行：

```text
/mapsyncer generate
```

生成基础缓存后，再让玩家使用 GUI 或 `/mapsyncer sync` 同步地图。

## 致谢

- 原始项目：[RuoChennn/MapSyncer-for-XaeroWorldmap](https://github.com/RuoChennn/MapSyncer-for-XaeroWorldmap)
- Rebuild 版本制作与整理：**ShanHe_YF**
- 感谢 Fabric、Xaero World Map、Voxy 及相关社区项目。
