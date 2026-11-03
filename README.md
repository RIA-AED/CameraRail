# ZthCameraRail

ZthCameraRail 是一个用于 Paper / Folia 的 Minecraft 摄像机轨道插件。播放时，玩家会被切换为旁观者并附身到一个隐藏的盔甲架上，镜头按 `tracks.yml` 中定义的路径移动、停留或等待外部信号；播放结束后，玩家按配置的落点返回。

插件只负责镜头本身。登录验证、小游戏流程、正式传送和流程状态切换由其他插件负责，它们可以通过 `CameraService`、事件或 `/zc` 命令与镜头播放配合。

## 特性

- 7 种轨道步骤：`move`、`path`、`hold`、`wait`、`cut`、`effect`、`title`
- `path` 使用向心 Catmull-Rom 曲线，按弧长参数化，可连续穿过多个路径点
- 7 种缓动曲线，支持步骤级 `sway` 覆盖
- `wait` 支持外部信号、提前信号排队和超时
- 三种退出落点：镜头最后位置、进入前位置、自定义位置
- 基于 Paper Dialog 的图形编辑器与命令编辑器，修改即时保存，支持撤销
- 玩家退出或服务器崩溃后，下次登录恢复播放前的状态
- Paper / Folia 双端支持
- 无第三方依赖，不需要 ProtocolLib 等前置

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 26.1.2（build 74 stable 测试通过）或 Folia 26.1.2 |
| Java | 25 |
| Minecraft | 26.1.2 |
| 客户端 | 原版 Java 版客户端，需正确处理 `Set Camera` 包 |

插件已在 Paper 26.1.2-74 和 Folia 26.1.2 上完成镜头播放、信号推进和退出落点的实机测试。

## 安装

1. 构建或下载 `ZthCameraRail.jar`。
2. 将 jar 放入服务端 `plugins/` 目录。
3. 启动服务器，插件会生成默认配置。
4. 编辑 `plugins/ZthCameraRail/tracks.yml`，或进入游戏执行 `/zc edit`。
5. 执行 `/zc reload`，然后 `/zc play <轨道>` 预览。

首次启动会生成配置和轨道文件；`recovery/` 在第一次播放时创建。

```text
plugins/ZthCameraRail/
├── config.yml       # 全局配置
├── messages.yml     # 玩家消息（MiniMessage）
├── tracks.yml       # 轨道
└── recovery/        # 播放前的玩家状态，正常结束时删除对应文件
```

## 快速开始

`tracks.yml` 示例：

```yaml
schema-version: 1
tracks:
  login:
    display-name: "登录过场"
    world: world
    start: { x: 0.5, y: 100.0, z: 0.5, yaw: 0, pitch: 10 }
    exit-mode: pre-entry
    sway: natural
    entry-effects:
      - effect: darkness
    steps:
      - { type: hold, duration: 40, sway: off }
      - { type: effect, effect: darkness, action: remove }
      - type: move
        to: { x: 8.5, y: 98.0, z: -6.5, yaw: 28, pitch: 6 }
        duration: 100
        easing: ease-in-out
      - { type: wait, signal: "login:ready", timeout: 6000 }
      - type: path
        duration: 120
        easing: ease-in-out
        points:
          - { x: 2.5, y: 98.5, z: 4.5, yaw: 10, pitch: 3 }
          - { x: 0.5, y: 100.0, z: 0.5, yaw: 0, pitch: 0 }
```

登录流程确认玩家已登录后，由其他插件或命令发送信号：

```text
/zc advance login:ready <玩家>
```

`start` 是镜头眼睛的位置；`exit` 是玩家脚部的位置，仅在 `exit-mode: custom` 时使用。默认 `exit-mode` 为 `animation-end`，即停在镜头最后的位置；如果镜头在半空，玩家会按原版规则坠落，需要安全落点时请使用 `pre-entry` 或 `custom`。

信号也可以提前发送：如果信号到达时镜头还没走到匹配的 `wait`，插件会先记录，进入该步骤后再消费。每个 `wait` 只消费一次信号；步骤的 `timeout`（tick，`0` 表示不超时）或 `config.yml` 的 `session.wait-timeout` 控制超时。

## 命令

根命令为 `/zth-camera`，别名 `/zc`。

| 命令 | 说明 |
| --- | --- |
| `/zc help [命令]` | 查看帮助；带命令名时显示用法、参数和权限 |
| `/zc list` | 列出已加载的轨道 |
| `/zc info <id>` | 显示轨道详情 |
| `/zc play [id] [玩家]` | 播放轨道；只加载一条轨道时可省略 id |
| `/zc stop [玩家]` | 停止播放并恢复玩家 |
| `/zc advance <信号或*> [玩家]` | 推进等待步骤；`*` 表示当前等待步骤 |
| `/zc reload` | 重载配置、消息和轨道 |
| `/zc create <id> [名称]` | 以当前位置和视角创建轨道 |
| `/zc delete <id> confirm` | 删除轨道 |
| `/zc edit` | 打开图形编辑器 |
| `/zc edit help [命令]` | 查看编辑器命令 |

控制台执行 `play`、`stop`、`advance` 时必须提供玩家名。有人处于编辑器时，`/zc reload` 会被拒绝，避免覆盖未完成的草稿。

命令编辑器常用命令：

```text
/zc edit create login 登录过场
/zc edit add 100                # 追加移动步骤（当前视角）
/zc edit path                   # 追加路径步骤
/zc edit point                  # 追加路径点
/zc edit hold 40                # 追加停留
/zc edit wait login:ready       # 追加等待
/zc edit effect add darkness
/zc edit title 20 60 20 欢迎|正在同步
/zc edit select 3               # 选中步骤
/zc edit start                  # 起点设为当前视角
/zc edit exit                   # 自定义退出点（当前脚下位置）
/zc edit exit-mode pre-entry
/zc edit preview
/zc edit undo
/zc edit done
```

每次修改都会异步写入 `tracks.yml`，`/zc edit undo` 可撤销最近 30 次修改。完整命令列表和参数见 `/zc edit help`。

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `zth.camera.command` | OP | 使用根命令和只读命令 |
| `zth.camera.play` | OP | 播放轨道 |
| `zth.camera.stop` | OP | 停止播放 |
| `zth.camera.advance` | OP | 发送等待信号 |
| `zth.camera.edit` | OP | 使用游戏内编辑器 |
| `zth.camera.manage` | OP | 创建和删除轨道（同时需要 `edit`） |
| `zth.camera.reload` | OP | 重载配置和轨道 |

`zth.camera.command` 只允许执行根命令，不会自动包含其他子权限。只需要播放和查看的场景，可以给 `zth.camera.command` 与 `zth.camera.play`，不要授予 `edit`、`manage` 或 `reload`。

被播放的玩家不需要拥有权限；播放由有 `zth.camera.play` 权限的命令发送者或插件发起。

## 配置

`config.yml` 中的时间单位均为 tick，20 tick = 1 秒。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `defaults.move-duration` | `100` | `move`、`path`、`hold` 未写时长时的默认值 |
| `defaults.easing` | `ease-in-out` | 默认缓动曲线 |
| `defaults.sway` | `natural` | 默认摇摆预设 |
| `defaults.sway-period` | `160` | 摇摆周期 |
| `session.follow-camera` | `true` | 是否让玩家实体跟随镜头跨区块 |
| `session.wait-timeout` | `6000` | `wait` 步骤的默认超时；`0` 表示永不超时 |
| `session.allow-sneak-exit` | `false` | 真实播放中是否允许按 Shift 退出 |
| `session.recovery` | `true` | 是否启用崩溃恢复 |
| `session.recovery-max-age-hours` | `168` | 恢复文件的最大有效期 |
| `limits.max-steps` | `256` | 单条轨道最大步骤数 |
| `limits.max-path-points` | `128` | 路径步骤最大路径点数 |
| `limits.max-track-ticks` | `72000` | 单条轨道定时步骤的总时长上限 |
| `debug` | `false` | 输出镜头同步和姿态调试日志 |

玩家消息和帮助文本使用 MiniMessage，可在 `messages.yml` 中按服务器风格修改。

## 轨道格式

### 轨道字段

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `display-name` | string | 否 | 显示名称；默认使用轨道 id |
| `description` | string | 否 | 轨道说明 |
| `world` | string | 是 | 轨道所在世界，必须与服务器加载的世界名一致 |
| `start` | pose | 是 | 起始镜头姿态（眼睛位置） |
| `exit-mode` | string | 否 | `animation-end`（默认）、`pre-entry` 或 `custom` |
| `exit` | pose | 否 | 玩家脚部落点，仅 `exit-mode: custom` 时使用 |
| `sway` | preset / map | 否 | 轨道默认摇摆；默认取 `config.yml` |
| `follow` | boolean | 否 | 玩家实体是否跟随镜头跨区块；默认取配置 |
| `entry-effects` | list | 否 | 进入镜头前施加的效果，只能添加 |
| `steps` | list | 否 | 有序步骤；可以为空 |

轨道 id 只能包含字母、数字、`-` 和 `_`，最长 64 字符；id 不区分大小写，加载和保存时统一使用小写。所有坐标都是 `world` 中的绝对坐标。

### 步骤类型

| 类型 | 说明 | 关键字段 |
| --- | --- | --- |
| `move` | 从当前姿态平滑移动到目标姿态 | `to`、`duration`、`easing`、`sway` |
| `path` | 连续穿过多个路径点 | `points`、`duration`、`easing`、`sway` |
| `hold` | 保持当前姿态 | `duration`、`sway` |
| `wait` | 保持当前姿态直到收到信号或超时 | `signal`、`timeout`、`sway` |
| `cut` | 瞬切到目标姿态 | `to`、`sway` |
| `effect` | 添加或移除药水效果 | `effect`、`action`、`amplifier`、`duration` |
| `title` | 发送标题和副标题 | `title`、`subtitle`、`fade-in`、`stay`、`fade-out` |

`path` 的 `points` 是当前姿态之后的路径点，至少一个。`easing` 可选 `linear`、`ease-in`、`ease-out`、`ease-in-out`、`smoothstep`、`smootherstep`、`sine`。`wait` 的 `timeout` 省略时使用 `session.wait-timeout`。`title` 的文本使用 MiniMessage。`effect` 的 `action` 默认 `add`，`duration` 省略或为 `0` 时持续到显式移除或轨道结束。同一轨道内的 `wait` 信号名不能重复。

### 退出落点

| `exit-mode` | 行为 |
| --- | --- |
| `animation-end` | 默认。结束时停在镜头最后的位置，按未叠加摇摆的基准姿态换算到玩家脚部 |
| `pre-entry` | 回到进入镜头前的位置 |
| `custom` | 使用 `exit` 指定的玩家脚部位置和朝向 |

只写了 `exit` 而没有写 `exit-mode` 时按 `custom` 处理。退出点是玩家脚部坐标，不会额外提供飞行或无敌；请确保落点安全。

### 摇摆

`sway` 可使用预设 `off`、`gentle`、`natural`、`strong`，也可以写 YAML 映射，以 `preset` 为基础并覆盖 `x`、`y`、`z`、`yaw`、`pitch`、`period`、`seed`。步骤可以覆盖为 `inherit` 或某个预设；覆盖只改变振幅，周期和种子仍来自轨道。

预设档位的 `yaw` 为 `0`，只做位置漂移和轻微俯仰；需要左右转头时，在自定义映射中显式设置 `yaw`。YAML 会把未加引号的 `off` 解析为布尔值，插件按 `off` 处理。

完整的字段、校验规则和更多示例见 [docs/yaml-reference.md](docs/yaml-reference.md)。

## 游戏内编辑器

`/zc edit`（或 `/zc edit gui`）打开 Paper 的 Dialog 全屏编辑器，不需要额外依赖或客户端 mod。

- 轨道列表：选择已有轨道，或以当前视角新建轨道。
- 轨道概览：查看步骤、总时长和摇摆；编辑轨道设置、起点、退出点、入场效果；预览整条轨道。
- 步骤表单：按类型编辑全部字段，支持保存并返回、上一步/下一步、上移/下移、复制、删除和单步预览。
- 坐标表单提供“取当前视角/取当前脚下位置”和“传送到此”；起点和步骤按眼睛坐标记录，退出点按脚部坐标记录。
- 路径步骤有独立的路径点列表，可追加当前视角、插入、删除和上下移动。
- 等待步骤可以复用已有信号；效果步骤提供常用效果列表，也允许输入任意注册 id。
- 所有修改立即自动保存，可撤销最近 30 次；按 Esc 关闭后再执行 `/zc edit` 会回到上次的界面和页码。

对话框依赖 Paper 26.1.2 的 Dialog API。命令编辑器不依赖 Dialog，始终可用，适合快速追加步骤或微调参数。

## 开发者 API

通过 Bukkit 的 `ServicesManager` 获取 `CameraService`：

```java
CameraService cameras = Bukkit.getServicesManager().load(CameraService.class);
if (cameras != null) {
    cameras.play(player, "login");
    cameras.advance(player, "login:ready");
}
```

常用方法：

```java
Set<String> trackIds();
boolean isPlaying(Player player);
Optional<PlaybackInfo> playback(Player player);
Optional<String> waitingSignal(Player player);

boolean play(Player player, String trackId);
boolean stop(Player player);
boolean advance(Player player, String signal);
boolean advance(Player player);

int reload();
```

`play` 返回 `true` 只表示请求已受理，不保证第一帧已经渲染；实际结束以 `CameraTrackEndEvent` 为准。播放相关方法可以从任意线程调用，`reload()` 请在服务端线程执行。

事件：

| 事件 | 触发时机 | 说明 |
| --- | --- | --- |
| `CameraTrackStartEvent` | 玩家被切换为旁观者之前 | 可取消；取消后玩家状态不变，也不会触发结束事件 |
| `CameraWaitEvent` | 进入 `wait` 步骤时 | 提供 `signal()`、`timeoutTicks()` 和 `preview()` |
| `CameraTrackEndEvent` | 玩家被释放、状态恢复之后 | 提供 `reason()`、`completed()` 和 `preview()` |

`completed()` 仅在轨道自然走完并成功把玩家放到退出位置时为 `true`。`EndReason` 包括 `FINISHED`、`STOPPED`、`QUIT`、`KICKED`、`TIMEOUT`、`LIMIT`、`CAMERA_LOST`、`EXTERNAL_TELEPORT`、`GAME_MODE_CHANGE`、`WORLD_CHANGE`、`DEATH`、`ERROR` 和 `RECOVERED`。

更完整的集成顺序、AuthMe 示例和事件用法见 [docs/integration.md](docs/integration.md)。

## 实现说明

- 镜头实体是一个隐藏的普通（非 marker）盔甲架，玩家以旁观者身份附身到它；服务端每 tick 更新实体姿态，客户端负责帧间插值，不会每 tick 传送玩家本体。
- 附身前先对玩家调用 `showEntity`，再设置旁观目标；第 2、10 tick 会重发一次 `Set Camera`，第 45 tick 检查客户端是否仍在发送自身移动包，未锁定时在控制台输出警告。
- 镜头跨区块时，玩家实体跟随移动以保证区块加载；可以用轨道字段 `follow: false` 或 `session.follow-camera: false` 关闭。
- 镜头实体使用服务端的精确位置同步模式（通过反射设置 `requiresPrecisePosition`），避免慢速旋转被量化成约 1.4° 的台阶；字段不可用时自动退回默认的实体跟踪。
- 退出时先把玩家身体传送到落点，等待 1 tick 再解除旁观并恢复游戏模式、飞行状态和药水效果，避免客户端先回到旧身体位置造成接缝。

### Folia

插件在 `plugin.yml` 中声明 `folia-supported: true`。镜头实体使用 `EntityScheduler`，玩家操作在玩家自己的 `EntityScheduler` 上执行；镜头和玩家传送均使用 `teleportAsync`，镜头所在区块通过插件区块票据保持加载，会话结束后释放。没有全局 tick 任务，每个会话只有一个镜头实体 tick 任务。

### 崩溃恢复

开启 `session.recovery` 时，播放前会在 `recovery/` 下写入玩家状态。正常结束会删除；玩家退出或服务器崩溃时保留，下次登录恢复位置、游戏模式、飞行状态和药水效果。超过 `session.recovery-max-age-hours` 的文件会在下次登录读取时删除。不要手动删除正在使用的恢复文件。

## 已知限制

- 附身盔甲架时，只有少数药水效果有画面：`nausea` 完整生效，`darkness` 部分生效，`night_vision` 大部分生效；`blindness`、`slowness` 等没有画面效果。详见 [docs/spectator-effects.md](docs/spectator-effects.md)。
- 自定义 `sway` 的 `yaw` 在部分客户端可能出现短促的水平回抽；预设档位 `yaw` 为 `0`，默认不会触发。
- 一条轨道只能属于一个世界。
- 编辑器保存会重写整个 `tracks.yml`，只保留文件头注释。文件中存在无法解析的轨道时，编辑器会拒绝保存，需要先修复并执行 `/zc reload`。
- 从选中步骤开始预览时，不会回放之前的 `effect` 和 `title` 步骤。
- 不支持循环轨道、多轨道叠加或任意命令步骤；流程编排交给其他插件。
- 镜头锁定依赖客户端正确处理 `Set Camera` 包，少数第三方客户端或协议转换链路可能无法锁定旁观相机。
- 精确同步依赖服务端映射字段；服务端改名时会自动退回默认的量化跟踪，播放仍可继续。

## 构建

需要 Java 25 和 Maven：

```text
mvn test
mvn package
```

产物为 `target/ZthCameraRail.jar`。`mvn test` 覆盖缓动、路径曲线、摇摆、YAML 解析与写入、帮助页排版等单元测试。

## 文档

- [tracks.yml 字段参考](docs/yaml-reference.md)
- [插件集成与事件](docs/integration.md)
- [旁观者附身下的药水效果](docs/spectator-effects.md)
- [本地验证与测试清单](docs/testing.md)

## 许可

本项目由 RIA 维护。仓库目前未附带开源许可证文件，对外分发前请先联系维护者。
