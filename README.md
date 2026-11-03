# ZthCameraRail

轻量的 Paper / Folia 摄像机轨道插件，面向登录过场、初次进入、小游戏转场和场景分镜。

它只负责“镜头”这一件事：把玩家安全地放进一条可配置的轨道，播放路径、等待外部信号、处理镜头微动和画面效果，然后把玩家交还给服务器。登录验证、小游戏流程、场景切换由你的胶水插件或脚本负责。

- 目标服务端：Minecraft 26.1.2（Paper 26.1.2 build 74 stable / Folia 26.1.2）
- Java：25
- 游戏内消息：中文，MiniMessage / RIA 风格
- 依赖：无，纯 Paper API

## 设计原则

- **让客户端补中间帧，而不是每 tick 硬 tp 玩家。** 玩家进入旁观模式并附身到一个隐藏的 `ArmorStand`，轨道每 tick 移动这个实体。客户端会对实体位置和视角做插值，因此镜头是连续的；玩家本体只负责区块加载和音效位置。
- **轨道是数据，不是代码。** 一条轨道是一个世界内的 `start` 加一组有序步骤。管理员可以用 YAML 写，也可以完全在游戏内编辑。
- **逐步增加复杂度。** 一开始只会 `move` 和 `hold` 也能用；需要时再加 `path`、`wait`、`effect`、`title`。
- **不和其他插件抢控制权。** 玩家被外部插件传送、切换游戏模式或切换世界时，镜头会体面地结束并交还状态，而不是和对方互相 teleport。
- **所有状态都可恢复。** 开始前保存游戏模式、位置、飞行状态、药水效果；正常结束、/stop、退出、崩溃恢复和服务器关闭都有对应处理。

## 工作原理

1. 在轨道起点生成一个默认对所有玩家隐藏的隐形标记盔甲架，然后只对当前玩家 `showEntity`。
2. 保存玩家原本的游戏模式、位置、飞行状态和药水效果。
3. 把玩家传送到镜头起点，切换为旁观者，再 `setSpectatorTarget` 到镜头实体。顺序不能反：客户端会丢弃找不到实体 id 的 `Set Camera` 包，而且不会重试。
4. 镜头的实体调度器每 tick 计算当前姿态（移动/路径/停留/摇摆），移动并旋转盔甲架。
5. 玩家实体在镜头跨区块时跟着移动，保证客户端持续收到镜头附近的区块；不会每 tick 传送玩家。
6. 遇到 `wait` 时保持镜头，等待 API 或命令发送信号。
7. 结束时把玩家传送到 `exit`；若没有配置 `exit`，则回到过场开始前的位置。随后恢复游戏模式、飞行状态和插件改动过的药水效果。

因为镜头运动发生在实体上，客户端会负责帧间插值；玩家自己的 `teleport` 只影响区块加载，不影响镜头画面。这也是直接 tp 玩家无法得到平滑镜头的原因。

### 为什么必须先 `showEntity` 再设旁观目标

旁观相机是纯客户端状态：`ClientPacketListener#handleSetCamera` 只在自己世界的实体表里按 id 查找，找不到就**静默丢弃**这个包，也不会再向服务端要一次。而 `ServerPlayer#setCamera` 只在目标**变化**时发包，所以服务端 `getSpectatorTarget()` 完全无法区分“客户端已附身”和“客户端把这个包丢了”。

一旦丢包，服务端仍然每 tick 执行 `ServerPlayer#tick` 里的 `absSnapTo(camera)`，把玩家吸附到盔甲架上；客户端却还在自由旁观，于是玩家看到的是一卡一卡的瞬移，控制台刷 `moved too quickly`。因此镜头实体保持默认隐藏、由插件对单个玩家 `showEntity`——这个调用会**同步**把生成包推给客户端，保证它排在 `Set Camera` 之前；实体若保持 `visibleByDefault(true)`，`showEntity` 会直接短路返回，生成包改由实体跟踪器自己发，顺序就失去保证。

开始后的第 2、10 tick 还会各重发一次 `Set Camera`（通过先清空再设置旁观目标实现，因为 Bukkit API 没有直接发包的入口），覆盖客户端尚未就绪的情况。

## 安装

1. 构建或下载 `ZthCameraRail.jar`。
2. 放进 `plugins/`，启动一次 Paper/Folia。
3. 编辑 `plugins/ZthCameraRail/tracks.yml`，或进入游戏使用 `/zth-camera edit` 创建。
4. 执行 `/zth-camera reload` 或重开服务器。
5. 用 `/zth-camera play <轨道>` 预览。

第一次启动会生成：

```text
plugins/ZthCameraRail/
├── config.yml       # 全局默认值与安全策略
├── messages.yml     # 中文 MiniMessage 消息
├── tracks.yml       # 轨道数据
└── recovery/        # 玩家状态恢复文件（正常结束时清空）
```

## 三分钟示例

```yaml
schema-version: 1
tracks:
  login:
    display-name: "登录 · 晨雾"
    world: world
    start: { x: 0.5, y: 100.0, z: 0.5, yaw: 0, pitch: 10 }
    # exit 可省略；省略时回到开始过场前的位置
    # exit: { x: 0.5, y: 65.0, z: 0.5, yaw: 0, pitch: 0 }
    sway: natural
    entry-effects:
      - effect: blindness
    steps:
      - { type: hold, duration: 40, sway: off }
      - { type: effect, effect: blindness, action: remove }
      - { type: effect, effect: slowness, amplifier: 2 }
      - type: move
        to: { x: 8.5, y: 98.0, z: -6.5, yaw: 28, pitch: 6 }
        duration: 100
        easing: ease-in-out
      - { type: wait, signal: authme:authenticated, timeout: 6000 }
      - { type: effect, effect: slowness, action: remove }
      - type: path
        duration: 120
        easing: ease-in-out
        points:
          - { x: 2.5, y: 98.5, z: 4.5, yaw: 10, pitch: 3 }
          - { x: 0.5, y: 100.0, z: 0.5, yaw: 0, pitch: 0 }
```

外部插件或命令在登录成功时发送：

```text
/zth-camera advance authme:authenticated AuthMePlayer
```

镜头会继续走完 `path`。示例没有配置 `exit`，所以结束后会回到玩家开始过场前的位置。

> `start` 是**镜头眼睛**的位置，通常要高于地面；`exit` 是**玩家脚部**的位置。若配置 `exit`，请把它放在安全落点，否则玩家退出镜头后仍会按原版规则坠落/受伤。

## 概念

### 轨道（track）

一个具名、不可变的播放序列。一条轨道只能在一个世界内运行。所有坐标都是绝对坐标。

### 步骤（step）

按顺序执行：

| 类型 | 作用 | 关键字段 |
| --- | --- | --- |
| `move` | 从当前姿态平滑移动到目标姿态 | `to`, `duration`, `easing`, `sway` |
| `path` | 平滑穿过多个路径点 | `points`, `duration`, `easing`, `sway` |
| `hold` | 保持当前姿态 | `duration`, `sway` |
| `wait` | 保持当前姿态直到外部信号 | `signal`, `timeout`, `sway` |
| `cut` | 硬切到目标姿态 | `to`, `sway` |
| `effect` | 添加/移除药水效果 | `effect`, `action`, `amplifier`, `duration` |
| `title` | 发送标题/副标题 | `title`, `subtitle`, `fade-in`, `stay`, `fade-out` |

`move` 每段都有独立的缓动，适合“镜头从 A 推到 B”。`path` 内部使用向心 Catmull-Rom 曲线并按弧长参数化，适合“连续穿过多个路径点”，不会在每个路径点前刹停。

### 摇摆（sway）

`sway` 是持续的微小扰动，不是随机抖动。它由两个正弦频率叠加，按轨道级 `period` 与 `seed` 生成，因此同一条轨道的预览是可复现的。

预设：`off`、`gentle`、`natural`、`strong`。默认由 `config.yml` 的 `defaults.sway` 决定，步骤可以用 `sway` 覆盖，`inherit` 表示使用轨道默认值。步骤切换时振幅会平滑过渡，不会突然开始/停止。

### 画面效果（effect）

支持任意已注册药水效果，常见缩写：

- `blindness`（失明）：用于进入镜头前遮住硬切。
- `darkness`（黑暗）：更明显的暗角/雾效。
- `slowness`（缓慢）：会收窄客户端 FOV，可作为“长焦”的近似。等级越高越明显。
- `night_vision`、`nausea`、`speed`、`invisibility` 等。

效果默认隐藏粒子和 HUD 图标，不污染界面。`duration` 省略或为 `0` 表示持续到步骤显式移除/轨道结束；正整数表示指定 tick。插件退出时会恢复它改动过的原效果，并把原本剩余的时间扣掉已经过去的时长。

> 原版 Minecraft 没有“全屏亮度渐变”或“精确 FOV 曲线”的服务端协议。真正的黑场渐变需要资源包/光影/客户端 mod；本插件用 `blindness`/`darkness` 做近似。`slowness` 的 FOV 变化也是原版行为，不保证在所有客户端设置下一致。

### 信号（signal）

`wait` 步骤会停住镜头，并进入名为 `signal` 的等待。外部通过 API 或命令发送同名信号继续：

```java
cameras.advance(player, "authme:authenticated");
```

信号可以提前发送：如果信号到达时镜头还没走到 `wait`，会按顺序排队，等进入匹配的 `wait` 后消费。每个轨道建议使用唯一的信号名。等待默认有超时（`config.yml` 的 `session.wait-timeout`），超时后会结束镜头并恢复玩家状态。

## 游戏内编辑

命令驱动，无 GUI 依赖，所有修改即时保存：

```text
/zth-camera edit create login 登录 · 晨雾   # 以当前视角创建并进入编辑
/zth-camera edit add 100                    # 从当前位置追加一个移动步骤
/zth-camera edit here                       # 把选中步骤的目标改成当前视角
/zth-camera edit path                       # 创建路径步骤
/zth-camera edit point                      # 给选中路径追加当前视角
/zth-camera edit hold 40                    # 追加停留
/zth-camera edit wait authme:authenticated  # 追加等待信号
/zth-camera edit effect add blindness       # 追加失明效果
/zth-camera edit title 20 60 20 欢迎|正在同步 # 标题/副标题
/zth-camera edit select 3
/zth-camera edit duration 80
/zth-camera edit easing ease-in-out
/zth-camera edit sway natural|off|inherit
/zth-camera edit preview                    # 进入预览，结束时回到编辑位置
/zth-camera edit undo
/zth-camera edit done
```

编辑器中每次修改都会通过单线程异步写入 `tracks.yml`；`/zth-camera edit undo` 可撤销最近 30 次修改。编辑保存会重写整个 `tracks.yml`，手写注释只保留文件头，因此大量手写注释建议放在独立文档中。若 `tracks.yml` 中存在无法解析的轨道，插件会拒绝保存，避免把手写但暂时写错的段落覆盖掉；先修好文件并 `/zth-camera reload`，再继续编辑。

完整命令和参数见 `/zth-camera help`、`/zth-camera edit help`。

## 命令与权限

根命令：`/zth-camera`，别名 `/zc`。

```text
/zc list
/zc info <track>
/zc play <track> [player]
/zc stop [player]
/zc advance <signal|*> [player]
/zc reload
/zc create <id> [显示名称]
/zc delete <id> confirm
/zc edit ...
```

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `zth.camera.command` | OP | 根命令 |
| `zth.camera.play` | OP | 播放 |
| `zth.camera.stop` | OP | 停止 |
| `zth.camera.advance` | OP | 发送等待信号 |
| `zth.camera.edit` | OP | 游戏内编辑 |
| `zth.camera.manage` | OP | 创建/删除 |
| `zth.camera.reload` | OP | 重载配置 |

`zth.camera.command` 只负责允许执行根命令，不再自动继承其他子权限。给一个“只能看登录镜头”的组时，请授予 `zth.camera.command` + `zth.camera.play`（需要停止/推进再加 `stop`/`advance`），不要给 `edit`、`manage` 或 `reload`。

玩家不会因为“被播放”而需要权限；播放由受信任的插件/命令发起即可。

## 集成 API

通过 Bukkit ServicesManager 获取：

```java
CameraService cameras = Bukkit.getServicesManager().load(CameraService.class);
if (cameras != null) {
    cameras.play(player, "login");
    cameras.advance(player, "authme:authenticated");
}
```

常用方法：

```java
boolean play(Player player, String trackId); // 已受理，不保证已经渲染第一帧
boolean advance(Player player, String signal);
boolean advance(Player player);              // 推进当前 wait
boolean stop(Player player);                 // 中止并恢复原状态
Optional<PlaybackInfo> playback(Player player);
Optional<String> waitingSignal(Player player);
Set<String> trackIds();
int reload();                                // 返回加载数量，-1 表示失败
```

事件：

- `CameraTrackStartEvent`（可取消）：在真正接管玩家前触发，其他插件可以 veto。
- `CameraWaitEvent`：镜头进入等待步骤时触发，适合通知登录/准备界面。
- `CameraTrackEndEvent`：结束后触发，包含 `EndReason`、`completed`、`preview`。

示例：

```java
@EventHandler
public void onAuthSuccess(AuthMeAuthenticatedEvent event) {
    CameraService cameras = Bukkit.getServicesManager().load(CameraService.class);
    if (cameras != null) {
        cameras.advance(event.getPlayer(), "authme:authenticated");
    }
}
```

更完整的集成说明、AuthMe 顺序和小游戏示例见 [docs/integration.md](docs/integration.md)。

## 边界行为

| 情况 | 行为 |
| --- | --- |
| 玩家按 Shift | 默认取消并提示；`session.allow-sneak-exit: true` 可允许退出 |
| 玩家尝试附身别的实体 | 取消并重新锁定镜头 |
| 其他插件传送玩家 | 结束镜头，不再抢回位置；恢复游戏模式和效果 |
| 其他插件改游戏模式 | 结束镜头，不覆盖对方设置的新模式 |
| 玩家切换世界 | 结束镜头并恢复状态 |
| 玩家退出 | 恢复游戏模式/效果；保留恢复文件，下次登录传回原位置 |
| 服务器崩溃/重启 | 恢复文件在下次登录时恢复位置、模式、飞行和效果 |
| 等待信号超时 | 结束镜头，恢复原位置 |
| 镜头实体丢失 | 结束镜头，恢复原位置 |
| 预览 | 正常结束或超时都回到进入预览前的位置 |
| 多世界轨道 | 不支持；一条轨道只能属于一个世界 |
| 跨区块飞行 | 玩家实体随镜头跨区块移动，保证区块加载；可由轨道 `follow: false` 关闭 |

退出点不在安全位置时，玩家仍会按原版规则坠落/受伤；插件不会额外给飞行或无敌。请给 `exit` 配置一个安全落点。

## Folia

插件在 `plugin.yml` 中声明 `folia-supported: true`，并只使用 Paper 的区域/实体调度器：

- 镜头实体使用 `EntityScheduler`，跟随实体跨 region 迁移。
- 玩家操作全部在玩家自己的 `EntityScheduler` 上执行。
- 镜头实体在所有平台都通过 `teleportAsync` 移动；Paper 上同样能加载起点/路径经过的未加载区块，避免同步传送被静默取消。
- 镜头所在区块使用插件区块票据保持加载，结束后释放；玩家实体仍按区块跟随。
- 文件写入在专用 I/O 线程，不在服务端线程阻塞。
- 没有全局 tick 任务；每个会话只有一个镜头实体 tick 任务。
- 命令可以从任意线程执行，内部会切换到正确的调度器。

如果同时安装其他强控制玩家位置的插件，建议把 `session.follow-camera` 设为 `false`，并确保镜头路径足够短，或由对方插件在镜头结束后再接管位置。

## 构建与测试

需要 Java 25 和 Maven：

```text
mvn test
mvn package
```

产物：`target/ZthCameraRail.jar`。

本地 Paper/Folia 26.1.2 验证步骤见 [docs/testing.md](docs/testing.md)。

## 实机验证记录

- Paper 26.1.2-74 + **官方 Minecraft 26.1.2 客户端**：观察者镜头成功附身，按住 W 不会移动镜头，高空镜头视角正常；默认 `/zc play`、远距离起点、标题/失明/缓慢效果、等待信号、结束回原位置、外部传送、死亡、退出重连恢复均通过。截图见 [docs/vanilla-camera-test.png](docs/vanilla-camera-test.png)。
- Folia 26.1.2-8 + ViaVersion/ViaBackwards：同一套 `login` 轨道播放、等待推进、结束回原位置通过，无 region 线程异常。
- 单元测试：`mvn test`，19 个测试全部通过。

- 2026-11-03：此前把镜头实体改成 `setVisibleByDefault(true)`，`showEntity` 于是变成空操作，生成包改由实体跟踪器按自己的节奏发出，`Set Camera` 抢先到达；原版 26.1.2 客户端因此无法附身，控制台持续刷 `moved too quickly`。现已恢复“实体默认隐藏、对玩家 `showEntity`、再设旁观目标”，并加入重发与未附身告警。

### 客户端兼容性（重要）

Horizon nightly 2026-11-01（26.3 协议）链路**不会锁定旁观者相机**。在同一个测试服上，不经过本插件、直接执行原版 `/spectate`（对盔甲架和僵尸都试过），客户端仍然可以自由移动；服务端 `getSpectatorTarget()` 和实体可见性都正常。这是该客户端链路没有实现 `Set Camera` 的表现，不是插件能绕过的协议问题。请使用官方 Java 版客户端验证镜头附身；如果它后续正确实现 `Set Camera` 包，这个镜头方案会自动生效。

插件现在会在第 45 tick 检查一次：如果这之后仍然收到玩家自身的移动包，说明客户端没有锁上相机，控制台会打印 `Spectator camera did not lock ...`。

## 性能

- 每个正在播放的玩家只有一个每 tick 的实体调度任务。
- 路径曲线在步骤开始时一次采样，之后每次查询是二分查找 + 线性插值。
- 摇摆是常数时间三角函数，不分配大对象。
- 区块跟随只在镜头跨区块时触发玩家传送，不是每 tick。
- 轨道文件在编辑时异步顺序写入；恢复文件只在会话开始/结束时写删。
- 不使用 ProtocolLib，不发送自定义 payload。

## 已知限制

- 原版没有真正的全屏渐变、精确 FOV 曲线或景深；效果只能近似。
- 镜头实体是盔甲架，依赖客户端正常处理旁观者目标；极少数客户端 mod/资源包可能改变表现。
- 一条轨道不能跨世界。
- 编辑器会重写 `tracks.yml`，不保留手写注释；若文件里有解析失败的轨道，插件会拒绝保存，直到修好并重载。
- 没有循环轨道/多轨道混合；需要循环等待时，用 `wait` 或外部信号控制。
- 没有任意命令步骤（安全考虑）；流程编排交给胶水插件。

## 许可

内部自用插件，按 RIA 服务器需要维护。
