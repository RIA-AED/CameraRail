<p align="center">
  <img src="docs/branding/hero.png" alt="CameraRail — 旁观者镜头轨道与过场动画（Paper / Folia）" width="100%">
</p>

# CameraRail

CameraRail 是一个用于 Paper / Folia 的 Minecraft 摄像机轨道插件。播放时，玩家会被切换为旁观者，服务端用一个隐藏的盔甲架作为相机锚点；安装 PacketEvents 后，客户端还会通过一个只存在于该客户端的假玩家实体渲染镜头，从而获得真正的 FOV 与更完整的药水效果。镜头按 `tracks.yml` 中定义的路径移动、停留或等待外部信号；播放结束后，玩家按配置的落点返回。

插件只负责镜头本身。登录验证、小游戏流程、正式传送和流程状态切换由其他插件负责，它们可以通过 `CameraService`、事件或 `/camerarail` 命令与镜头播放配合。

## 特性

- 7 种轨道步骤：`move`、`path`、`hold`、`wait`、`cut`、`effect`、`title`；`cut` 硬切后可选停留一段时间
- `path` 使用向心 Catmull-Rom 曲线，按弧长参数化，可连续穿过多个路径点
- 7 种缓动曲线，支持步骤级 `sway` 覆盖
- `wait` 支持外部信号、提前信号排队和超时
- 三种退出落点：镜头最后位置、进入前位置、自定义位置
- 基于 Paper Dialog 的图形编辑器与命令编辑器，修改即时保存，支持撤销
- 玩家退出或服务器崩溃后，下次登录恢复播放前的状态
- 播放期间隐藏被拍摄玩家的原版定位条（locator bar），正常/异常结束都恢复
- Paper / Folia 双端支持
- 可选 PacketEvents 假玩家镜头：FOV、失明雾/压天、夜视雾等按镜头实体读取的效果可用；不 shade PacketEvents，未安装时自动回退到盔甲架镜头

## 环境要求

| 项目 | 要求 |
| --- | --- |
| 服务端 | Paper 26.1.2（build 74 stable 测试通过）或 Folia 26.1.2 |
| Java | 25 |
| Minecraft | 26.1.2 |
| 客户端 | 原版 Java 版客户端，需正确处理 `Set Camera` 包 |
| PacketEvents | 可选，推荐 2.14.0+；用于假玩家镜头，不 shade |

插件已在 Paper 26.1.2-74 上完成基础镜头播放、信号推进和退出落点的实机测试。假玩家镜头依赖 PacketEvents 2.14.0，并已在 Paper 启动时确认注册成功；客户端画面（FOV、失明雾）的最终效果仍建议按 [docs/testing.md](docs/testing.md) 在目标客户端版本上验收。未安装 PacketEvents 或假玩家创建失败时，插件会记录日志并回退到盔甲架镜头，不会阻止播放。

## 安装

1. 将 PacketEvents 2.14.0+ 的插件 jar 放入服务端 `plugins/` 目录（可选，但 FOV/失明雾需要它）。
2. 构建或下载 `CameraRail-1.1.0.jar`，放入同一 `plugins/` 目录。
3. 启动服务器，插件会生成默认配置。
4. 编辑 `plugins/CameraRail/tracks.yml`，或进入游戏执行 `/camerarail edit`。
5. 执行 `/camerarail reload`，然后 `/camerarail play <轨道>` 预览。

首次启动会生成配置和轨道文件；`recovery/` 在第一次播放时创建。

没有安装 PacketEvents 时不会阻止插件运行，但会明确提示：

- 启动日志为 WARN：`PacketEvents not installed; fake-player camera disabled...`；
- 播放使用 `blindness`/`slowness`/`speed` 等镜头相关效果的轨道时，聊天栏提示当前是盔甲架回退模式，控制台只对每条轨道警告一次；
- 编辑器轨道概览显示“镜头模式: 盔甲架回退 · FOV/失明雾不可用”；
- `/camerarail info <轨道>` 显示“镜头模式: 假玩家 (PacketEvents)”或“镜头模式: 盔甲架回退”；
- 效果选择器摘要与效果表单显示回退说明；
- `/camerarail edit effect add ...` 追加镜头相关效果后，聊天栏追加同样的提示。

```text
plugins/CameraRail/
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
/camerarail advance login:ready <玩家>
```

`start` 是镜头眼睛的位置；`exit` 是玩家脚部的位置，仅在 `exit-mode: custom` 时使用。默认 `exit-mode` 为 `animation-end`，即停在镜头最后的位置；如果镜头在半空，玩家会按原版规则坠落，需要安全落点时请使用 `pre-entry` 或 `custom`。

信号也可以提前发送：如果信号到达时镜头还没走到匹配的 `wait`，插件会先记录，进入该步骤后再消费。每个 `wait` 只消费一次信号；步骤的 `timeout`（tick，`0` 表示不超时）或 `config.yml` 的 `session.wait-timeout` 控制超时。

## 命令

根命令为 `/camerarail`，别名 `/camera-rail`。

| 命令 | 说明 |
| --- | --- |
| `/camerarail help [命令]` | 查看帮助；带命令名时显示用法、参数和权限 |
| `/camerarail list` | 列出已加载的轨道 |
| `/camerarail info <id>` | 显示轨道详情 |
| `/camerarail play [id] [玩家]` | 播放轨道；只加载一条轨道时可省略 id |
| `/camerarail stop [玩家]` | 停止播放并恢复玩家 |
| `/camerarail advance <信号或*> [玩家]` | 推进等待步骤；`*` 表示当前等待步骤 |
| `/camerarail reload` | 重载配置、消息和轨道 |
| `/camerarail create <id> [名称]` | 以当前位置和视角创建轨道 |
| `/camerarail delete <id> confirm` | 删除轨道 |
| `/camerarail edit` | 打开图形编辑器 |
| `/camerarail edit help [命令]` | 查看编辑器命令 |

控制台执行 `play`、`stop`、`advance` 时必须提供玩家名。有人处于编辑器时，`/camerarail reload` 会被拒绝，避免覆盖未完成的草稿。

命令编辑器常用命令：

```text
/camerarail edit create login 登录过场
/camerarail edit add 100                # 追加移动步骤（当前视角）
/camerarail edit path                   # 追加路径步骤
/camerarail edit point                  # 追加路径点
/camerarail edit hold 40                # 追加停留
/camerarail edit cut 40                 # 追加快切并停留 40t
/camerarail edit wait login:ready       # 追加等待
/camerarail edit effect add darkness
/camerarail edit title 20 60 20 欢迎|正在同步
/camerarail edit select 3               # 选中步骤
/camerarail edit start                  # 起点设为当前视角
/camerarail edit exit                   # 自定义退出点（当前脚下位置）
/camerarail edit exit-mode pre-entry
/camerarail edit preview
/camerarail edit undo
/camerarail edit done
```

每次修改都会异步写入 `tracks.yml`，`/camerarail edit undo` 可撤销最近 30 次修改。完整命令列表和参数见 `/camerarail edit help`。

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `camerarail.command` | OP | 使用根命令和只读命令 |
| `camerarail.play` | OP | 播放轨道 |
| `camerarail.stop` | OP | 停止播放 |
| `camerarail.advance` | OP | 发送等待信号 |
| `camerarail.edit` | OP | 使用游戏内编辑器 |
| `camerarail.manage` | OP | 创建和删除轨道（同时需要 `edit`） |
| `camerarail.reload` | OP | 重载配置和轨道 |

`camerarail.command` 只允许执行根命令，不会自动包含其他子权限。只需要播放和查看的场景，可以给 `camerarail.command` 与 `camerarail.play`，不要授予 `edit`、`manage` 或 `reload`。

被播放的玩家不需要拥有权限；播放由有 `camerarail.play` 权限的命令发送者或插件发起。

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
| `session.hide-locator-bar` | `true` | 播放期间是否隐藏被拍摄玩家的原版定位条 |
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
| `cut` | 瞬切到目标姿态，然后可选停留一段时间 | `to`、`duration`、`sway` |
| `effect` | 添加或移除药水效果 | `effect`、`action`、`amplifier`、`duration` |
| `title` | 发送标题和副标题 | `title`、`subtitle`、`fade-in`、`stay`、`fade-out` |

`path` 的 `points` 是当前姿态之后的路径点，至少一个。`easing` 可选 `linear`、`ease-in`、`ease-out`、`ease-in-out`、`smoothstep`、`smootherstep`、`sine`。`wait` 的 `timeout` 省略时使用 `session.wait-timeout`。`title` 的文本使用 MiniMessage。`effect` 的 `action` 默认 `add`，`duration` 省略或为 `0` 时持续到显式移除或轨道结束。同一轨道内的 `wait` 信号名不能重复。`cut` 的 `duration` 省略或为 `0` 时是纯瞬时硬切，不占用时间轴；正整数 N 表示硬切后在新姿态停留 N tick，计入轨道总时长，整个过程没有过渡帧。

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

`/camerarail edit`（或 `/camerarail edit gui`）打开 Paper 的 Dialog 全屏编辑器，不需要额外依赖或客户端 mod。

- 轨道列表：选择已有轨道，或以当前视角新建轨道。
- 轨道概览：查看步骤、总时长和摇摆；编辑轨道设置、起点、退出点、入场效果；预览整条轨道。
- 步骤表单：按类型编辑全部字段，支持保存并返回、上一步/下一步、上移/下移、复制、删除和单步预览。
- 坐标表单提供“取当前视角/取当前脚下位置”和“传送到此”；起点和步骤按眼睛坐标记录，退出点按脚部坐标记录。
- 硬切步骤表单在目标姿态之外提供“硬切后停留”时长（tick，`0` 表示纯瞬时）；硬切后的画面在两种镜头模式下都是单帧切换、没有过渡帧。
- 路径步骤有独立的路径点列表，可追加当前视角、插入、删除和上下移动；路径点表单也可以用“上一步/下一步”在相邻路径点之间切换，切换会丢弃未保存的输入。
- 等待步骤可以复用已有信号；效果步骤提供常用效果列表，也允许输入任意注册 id。
- 所有修改立即自动保存，可撤销最近 30 次；按 Esc 关闭后再执行 `/camerarail edit` 会回到上次的界面和页码。

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

- 服务端锚点是一个隐藏的普通（非 marker）盔甲架，玩家以旁观者身份附身到它；服务端每 tick 更新实体姿态，客户端负责帧间插值，不会每 tick 传送玩家本体。
- 安装 PacketEvents 时，插件还会生成一个只存在于该客户端的 PLAYER 实体，并把发往盔甲架的 `Set Camera` 包改写成假玩家 id。服务端 `getSpectatorTarget()` 仍是盔甲架，区块票据、每 tick 吸附和跨区块跟随都不变；假玩家负责客户端的 Camera、FOV 和按镜头实体读取的效果。客户端旁观相机的视角取的是实体的 head yaw，而位置包只同步身体朝向，所以插件还要在量化后的 head yaw 变化时逐 tick 下发 `EntityHeadLook`（假玩家没有服务端实体，原版实体跟踪不会替它发这个包）；否则画面 yaw 会停在进入镜头时的角度。
- 假玩家的位置、pitch 和身体朝向每 3 tick 从同一份镜头姿态同步一次（与精确实体跟踪的节奏一致）；视角 yaw 的 head 同步则是每个镜头 tick 检查、量化值变化才发包。插件直接计算并下发假玩家的 `MOVEMENT_SPEED` 属性：客户端不会给客户端侧实体应用药水效果的属性修正，所以 `slowness`/`speed` 的 FOV 必须走属性包。PacketEvents 不可用或假玩家生成失败时，自动保持原盔甲架镜头。
- `cut` 硬切不走上面的客户端帧间插值，在两种镜头模式下都是单帧切换：假玩家镜头下，插件在硬切时销毁旧的客户端相机实体，按目标姿态重建假玩家实体，并在同一 tick 把 `Set Camera` 指向新实体（切换相机目标本身是瞬时的，新实体没有位置/头部插值状态），同时把 `MOVEMENT_SPEED` 属性（`slowness`/`speed` 的 FOV）和 `blindness`/`darkness`/`night_vision` 等镜头可见效果重新下发到新实体；盔甲架回退模式下，客户端会插值镜头盔甲架的位置和朝向（约 3 tick），直接传送会滑过去，所以插件先解除相机、把玩家本体瞬移到硬切姿态（解除期间客户端相机就是玩家本体，画面已经立即切到目标镜头），再移动盔甲架，约 4 tick 后等客户端追上再重新锁定相机。两种模式都没有补间；`cut` 的 `duration` 只控制跳转后的停留。
- 假玩家镜头激活时**不会**向该客户端发送盔甲架实体：盔甲架只作为服务端 spectator target 存在。只有假玩家不可用、回退到盔甲架镜头时才对玩家调用 `showEntity`。旁观者客户端能看见 invisible 实体，若把盔甲架发给客户端，会在镜头位置出现半透明模型。
- 播放期间通过给玩家加一个 transient 的 `waypoint_receive_range` 乘算修正符（`MULTIPLY_SCALAR_1 = -1`）隐藏原版定位条（locator bar）：相机锁定后隐藏，移除修正符即恢复（最多 1 tick 生效）。只增删本插件命名空间下的修正符，不读取/写回属性基础值，不碰其他插件的 modifier，因此第三方对该属性的修改不会被覆盖；修正符不落盘，崩溃/重登不会残留。镜头 tick 每 20 tick 在玩家调度器上复查一次（第三方清 modifiers、`/camerarail reload` 切换配置都会被纠正），所有结束/退出/恢复路径都会移除。实现见 `LocatorBarHider`，背景见 [docs/locator-bar.md](docs/locator-bar.md)。
- 第 2、10 tick 会重发一次 `Set Camera`，第 45 tick 检查客户端是否仍在发送自身移动包，未锁定时在控制台输出警告。
- 镜头锁定时，旁观者按数字键 1-9 或使用旁观菜单传送到其他玩家会被服务端处理为 `TeleportCause.SPECTATE` 的本体传送；插件会取消该传送并重新锁定镜头，动作栏提示镜头仍在锁定中，不再把它当作外部传送而提前结束播放。其他原因的外部传送仍按原策略结束会话。
- 镜头跨区块时，玩家实体跟随移动以保证区块加载；可以用轨道字段 `follow: false` 或 `session.follow-camera: false` 关闭。
- 服务端镜头实体（含回退模式的盔甲架）使用精确位置同步模式（通过反射设置 `requiresPrecisePosition`），让位置、pitch 和身体朝向走 float 的 `EntityPositionSync`；但客户端旁观相机的视角 yaw 来自 head yaw，协议只提供 1 字节（1/256 圈 = 1.40625°）的 `RotateHead` 包并由客户端做 3 tick 插值，所以慢速旋转仍可能出现亚度级台阶与约 2–3 tick 的跟随滞后——这与旁观原版玩家一致，不是本插件能消除的量化。字段不可用时自动退回默认的实体跟踪。
- 退出时先解除旁观/相机并恢复游戏模式、飞行状态和药水效果，清空残余速度后再把玩家传送到落点，并在 1 tick 后对同一落点再重同步一次传送：这样既避免客户端在相机锁定期间收到传送造成的位置确认相位错乱，也保证落点脚底位于方块表面之上（`ExitPlacement` 会抬升 0.1 mm 并在脚底嵌入方块碰撞体时抬到碰撞体顶，因为 `pose.y - eyeHeight` 的 float 误差会让脚底低约 4.8e-9，GUI 取景的 6 位小数取整、潜行/游泳取景与站立恢复的眼高差也可能让脚底嵌进方块）。编辑器的“传送到此”使用同一套落点校正与重同步。
- 退出与编辑器“传送到此”都会对同一落点发送两次传送（间隔约 1 tick）来重置客户端的传送确认状态；监听 `PlayerTeleportEvent` 的其他插件会看到两次 `PLUGIN` 传送。

### Folia

插件在 `plugin.yml` 中声明 `folia-supported: true`。镜头实体使用 `EntityScheduler`，玩家操作在玩家自己的 `EntityScheduler` 上执行；镜头和玩家传送均使用 `teleportAsync`，镜头所在区块通过插件区块票据保持加载，会话结束后释放。没有全局 tick 任务，每个会话只有一个镜头实体 tick 任务。

### 崩溃恢复

开启 `session.recovery` 时，播放前会在 `recovery/` 下写入玩家状态。正常结束会删除；玩家退出或服务器崩溃时保留，下次登录恢复位置、游戏模式、飞行状态和药水效果。超过 `session.recovery-max-age-hours` 的文件会在下次登录读取时删除。不要手动删除正在使用的恢复文件。

## 已知限制

- 假玩家镜头下，`blindness` 的雾/压天、`darkness`/`night_vision` 的雾也会生效，`slowness`/`speed` 会改变 FOV；`nausea`、亮度图等仍按真实玩家读取。未安装 PacketEvents 或假玩家创建失败时，效果退回旧行为（仅 `nausea` 完整、`darkness` 部分、`night_vision` 大部分）。详见 [docs/spectator-effects.md](docs/spectator-effects.md)。
- 假玩家镜头不是完整客户端渲染：全屏渐变/黑场、水下/着火/方块贴脸等 `ScreenEffectRenderer` 叠加层仍不可用，FOV 也只能通过药水等级（`slowness`/`speed`）表达。
- 假玩家是客户端侧实体，跨版本链路（ViaVersion/ViaBackwards）是否完整转发它的 PlayerInfo、属性和效果包取决于链路；建议在目标 26.1.2 原生客户端上验收 FOV 和画面效果。若链路不转发属性包，FOV 会保持 1.0，但镜头与其余效果不受影响。
- 假玩家镜头的视角 yaw 通过原版 head-yaw 包同步：协议精度为 1/256 圈（约 1.40625°），客户端还会做 3 tick 插值。因此极慢的缓动平移可能有亚度级台阶或约 2–3 tick 的跟随滞后，快速转身约 150 ms 后到位；位置与 pitch 不受影响。这与盔甲架回退模式、旁观原版玩家时一致。
- `cut` 本身在两种镜头模式下都是单帧切换、没有补间，不受上面的位置/head 插值影响；但盔甲架回退模式为了等客户端把镜头盔甲架插值到新位置，会额外用约 4 tick 完成解除、移动和重新锁定相机（这期间画面已经是目标镜头，步骤处理暂停），因此回退模式下每次硬切会比轨道时间轴多约 4 tick。
- 镜头锁定期间，原版旁观者的数字键 1-9 和旁观菜单“传送到玩家”快捷方式会被忽略（服务端取消 `SPECTATE` 传送并重新锁定镜头，动作栏提示锁定），不会再因此提前结束播放；其他原因的外部传送仍会结束会话。
- Folia 下镜头实体跨 region（例如远距离 `cut`）时，服务端会把旧镜头实体替换为新实体，并先清空一次旁观目标。事件层表现为一次 `PlayerStopSpectatingEntityEvent`；插件通过服务端 sneak 标记判断它并非玩家按 Shift，取消该事件并静默重新锁定镜头，因此不会结束预览、不会提示锁定、也不会触发 `allow-sneak-exit`。首次发生时会记录一条 `Server released the spectator camera ... without a Shift input` INFO 日志用于确认；Paper 上通常不会出现该分支（镜头实体不会被替换，除非其他插件或传送路径主动释放了相机）。
- 自定义 `sway` 的 `yaw` 在部分客户端可能出现短促的水平回抽；预设档位 `yaw` 为 `0`，默认不会触发。
- 一条轨道只能属于一个世界。
- 编辑器保存会重写整个 `tracks.yml`，只保留文件头注释。文件中存在无法解析的轨道时，编辑器会拒绝保存，需要先修复并执行 `/camerarail reload`。
- 从选中步骤开始预览时，不会回放之前的 `effect` 和 `title` 步骤，也不会施加入场效果；从轨道起点开始的预览与真实播放一致，会施加入场效果。
- 不支持循环轨道、多轨道叠加或任意命令步骤；流程编排交给其他插件。
- 镜头锁定依赖客户端正确处理 `Set Camera` 包，少数第三方客户端或协议转换链路可能无法锁定旁观相机。
- 精确同步依赖服务端映射字段；服务端改名时会自动退回默认的量化跟踪，播放仍可继续。
- 定位条隐藏依赖 26.1.2 的 `waypoint_receive_range` 属性；服务端不提供该属性时只记录一次 WARN 并继续播放（定位条保持可见），不会中断镜头。

## 构建

需要 Java 25 和 Maven：

```text
mvn test
mvn package
```

产物为 `target/CameraRail-1.1.0.jar`。`mvn test` 覆盖缓动、路径曲线、摇摆、YAML 解析与写入、帮助页排版等单元测试。

## 文档

- [tracks.yml 字段参考](docs/yaml-reference.md)
- [插件集成与事件](docs/integration.md)
- [旁观者镜头下的药水效果与假玩家摄像机](docs/spectator-effects.md)
- [定位条（locator bar）与播放期间隐藏](docs/locator-bar.md)
- [本地验证与测试清单](docs/testing.md)

## 许可

本项目由 RIA 维护。仓库目前未附带开源许可证文件，对外分发前请先联系维护者。
