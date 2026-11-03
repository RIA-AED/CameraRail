# ZthCameraRail

轻量的 Paper / Folia 摄像机轨道插件，面向登录过场、初次进入、小游戏转场和场景分镜。

它只负责“镜头”这一件事：把玩家安全地放进一条可配置的轨道，播放路径、等待外部信号、处理镜头微动和画面效果，然后把玩家交还给服务器。登录验证、小游戏流程、场景切换由你的胶水插件或脚本负责。

- 目标服务端：Minecraft 26.1.2（Paper 26.1.2 build 74 stable / Folia 26.1.2）
- Java：25
- 游戏内消息：中文，MiniMessage / RIA 风格
- 依赖：无；纯 Paper API，镜头精确同步仅在可用时通过服务端映射字段反射启用（不可用时自动退回默认跟踪）

## 设计原则

- **让客户端补中间帧，而不是每 tick 硬 tp 玩家。** 玩家进入旁观模式并附身到一个隐藏的 `ArmorStand`，轨道每 tick 移动这个实体。客户端会对实体位置和视角做插值，因此镜头是连续的；玩家本体只负责区块加载和音效位置。
- **轨道是数据，不是代码。** 一条轨道是一个世界内的 `start` 加一组有序步骤。管理员可以用 YAML 写，也可以完全在游戏内编辑。
- **逐步增加复杂度。** 一开始只会 `move` 和 `hold` 也能用；需要时再加 `path`、`wait`、`effect`、`title`。
- **不和其他插件抢控制权。** 玩家被外部插件传送、切换游戏模式或切换世界时，镜头会体面地结束并交还状态，而不是和对方互相 teleport。
- **所有状态都可恢复。** 开始前保存游戏模式、位置、飞行状态、药水效果；正常结束、/stop、退出、崩溃恢复和服务器关闭都有对应处理。

## 工作原理

1. 在轨道起点生成一个默认对所有玩家隐藏的隐形盔甲架（非 marker，客户端按眼高取景），然后只对当前玩家 `showEntity`。
2. 保存玩家原本的游戏模式、位置、飞行状态和药水效果。
3. 把玩家传送到镜头起点，切换为旁观者，再 `setSpectatorTarget` 到镜头实体。顺序不能反：客户端会丢弃找不到实体 id 的 `Set Camera` 包，而且不会重试。
4. 镜头的实体调度器每 tick 计算当前姿态（移动/路径/停留/摇摆），移动并旋转盔甲架。
5. 玩家实体在镜头跨区块时跟着移动，保证客户端持续收到镜头附近的区块；不会每 tick 传送玩家。
6. 遇到 `wait` 时保持镜头，等待 API 或命令发送信号。
7. 结束时按 `exit-mode` 放置玩家：默认 `animation-end` 停在镜头最后的位置（使用未叠加 sway 的基准姿态，最终坐标与编辑值一致）；`pre-entry` 回到过场开始前的位置；`custom` 使用 `exit` 指定的脚部位置。先把玩家身体传送到目标位置，等 1 tick 再解除旁观，避免客户端先回到旧身体位置造成接缝。随后恢复游戏模式、飞行状态和插件改动过的药水效果。

因为镜头运动发生在实体上，客户端会负责帧间插值；玩家自己的 `teleport` 只影响区块加载，不影响镜头画面。这也是直接 tp 玩家无法得到平滑镜头的原因。

### 镜头旋转的刷新率

镜头每 tick 都在服务端算出一个新姿态，但普通实体跟踪器只按 `EntityType#updateInterval` 发包（盔甲架是 3 tick），并且把 yaw/pitch 压成 1/256 圈（约 1.4°）的字节。走动中的生物看不出来；镜头就是画面本身，缓动两端角速度很小时，量化后的目标好几 tick 才变一次，客户端只能一小步一小步地追。

因此插件在生成镜头后会把该实体切到服务端的“精确位置同步”模式：跟踪器仍按原来的 3 tick 周期发包，但包里带的是完整位置和 float yaw/pitch，客户端在这 3 tick 内插值。问题不是包太少，而是包里的角度被量化了；换成精确角度后 3 tick 的节奏就足够连续，也不会像每 tick 强制发包那样给镜头增加约 2 tick 的平滑延迟。这只是一次内部字段切换，不装 ProtocolLib、不发自定义 payload；如果以后服务端改掉了这个映射名称，桥接会自动失效并退回原版量化跟踪，镜头仍然可用。

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
    # 结束位置：默认停在镜头最后的位置；也可写 exit-mode: pre-entry 回到进入前的位置
    exit-mode: animation-end
    # 自定义退出点：exit-mode: custom + exit（玩家脚部位置）
    # exit: { x: 0.5, y: 65.0, z: 0.5, yaw: 0, pitch: 0 }
    sway: natural
    # 附身镜头下只有 darkness/nausea/night_vision 等少数效果有画面；
    # blindness/slowness 不会生效，见 docs/spectator-effects.md。
    entry-effects:
      - effect: darkness
    steps:
      - { type: hold, duration: 40, sway: off }
      - { type: effect, effect: darkness, action: remove }
      - type: move
        to: { x: 8.5, y: 98.0, z: -6.5, yaw: 28, pitch: 6 }
        duration: 100
        easing: ease-in-out
      - { type: wait, signal: authme:authenticated, timeout: 6000 }
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

镜头会继续走完 `path`。示例没有配置自定义退出点，所以结束后会停在镜头最后的位置。

> `start` 是**镜头眼睛**的位置，通常要高于地面；`exit` 是**玩家脚部**的位置，只在 `exit-mode: custom` 时使用。默认 `animation-end` 会把玩家放在镜头最终位置（按眼高换算到脚部）；`pre-entry` 回到进入镜头前的位置。若使用 `custom`，请把 `exit` 放在安全落点，否则玩家退出镜头后仍会按原版规则坠落/受伤。

### 为什么相机实体不是 marker

marker 盔甲架在客户端会用实体原点作为旁观相机位置，而服务端 `ArmorStand#getEyeHeight()` 仍可能返回非零眼高；这会让整个镜头比保存的视角低一个眼高，退出时看起来像从脚部升到头部。普通隐形盔甲架在客户端和服务端都按同一个眼高取景，因此相机实体不使用 marker 标记。

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

`sway` 是持续的微小扰动，不是随机抖动。它由两个正弦频率叠加，按轨道级 `period` 与 `seed` 生成，因此同一条轨道的预览是可复现的。两个频率都按绝对 tick 连续求值，不会在 `period` 边界重新起算，所以摇摆本身不会跳变；`period` 只决定摆动快慢。

预设：`off`、`gentle`、`natural`、`strong`。默认由 `config.yml` 的 `defaults.sway` 决定，步骤可以用 `sway` 覆盖，`inherit` 表示使用轨道默认值。步骤切换时振幅会平滑过渡，不会突然开始/停止。预设档位**不使用左右转头**（`yaw` 恒为 0），只做位置漂移加轻微俯仰，这样视角不会左右抽动；需要左右转头时，在 YAML 里写自定义 `yaw`。

### 画面效果（effect）

附身镜头下，药水效果只有少数会真正改变画面。`effect` 步骤施加给玩家；不要施加给盔甲架（客户端收不到它的效果包）。

- `nausea`：完整生效，视角摇晃。
- `darkness`：部分生效，世界变暗/脉动；天空和雾不变。
- `night_vision`：大部分生效，亮度提升；雾色不变。
- `blindness`、`slowness`/`speed` 等：在当前“盔甲架 + 旁观者附身”模式下没有画面效果。FOV 与失明雾读的是镜头实体；盔甲架不是玩家，且它的效果不会同步给客户端。

真正的黑场渐变/精确 FOV 需要客户端 mod、资源包或换相机方案；详见 [docs/spectator-effects.md](docs/spectator-effects.md)。

效果默认隐藏粒子和 HUD 图标，不污染界面。`duration` 省略或为 `0` 表示持续到步骤显式移除/轨道结束；正整数表示指定 tick。插件退出时会恢复它改动过的原效果，并把原本剩余的时间扣掉已经过去的时长。

### 信号（signal）

`wait` 步骤会停住镜头，并进入名为 `signal` 的等待。外部通过 API 或命令发送同名信号继续：

```java
cameras.advance(player, "authme:authenticated");
```

信号可以提前发送：如果信号到达时镜头还没走到 `wait`，会按顺序排队，等进入匹配的 `wait` 后消费。每个轨道建议使用唯一的信号名。等待默认有超时（`config.yml` 的 `session.wait-timeout`），超时后会结束镜头并恢复玩家状态。

## 图形编辑器（Dialog）

`/zth-camera edit`（或 `/zth-camera edit gui`）会打开 Paper 26.1.2 自带的全屏 Dialog 编辑器，不需要额外依赖或客户端 mod。

```text
/zc edit
  → 轨道列表：选择轨道 / 以当前视角新建
  → 轨道概览：步骤、轨道设置、起点、退出点、入场效果、预览、删除
  → 步骤列表：点击步骤编辑；添加步骤；撤销；预览全部
  → 步骤表单：按类型编辑全部字段；保存并返回；上一步/下一步；上移/下移/复制/删除；只预览此步
```

- 支持全部 7 种步骤：`move`、`path`、`hold`、`wait`、`cut`、`effect`、`title`。
- 坐标、时长、缓动、摇摆、信号、效果、标题都能在表单里直接改；坐标不再只能靠 `here` 抓取。
- 点“取当前视角”/“取当前脚下位置”会立即捕获点击时的位置，再重开表单；没有等待倒计时，避免等待期间人物或视角漂移。
- “退出点 >”可以选择：动画结束位置（默认，停在镜头最后的位置）、进入前的位置、自定义位置；只有自定义位置才需要填脚部坐标。
- 结束时先把玩家身体传送到最终位置，等 1 tick 再解除旁观，避免客户端先回到旧身体位置造成接缝。
- 按 Esc 关闭窗口后可以正常移动/飞行；再次 `/zc edit` 会回到上次的界面、页码和选中步骤，而不是总回轨道概览。
- 坐标相关的表单（移动/硬切目标、路径点、起点、退出点）都有“传送到此”：关闭窗口并传送过去，再用 `/zc edit` 回到原表单。起点/步骤/路径点按眼睛坐标换算脚部位置，退出点按保存的脚部位置直接传送。
- 步骤表单标题带序号（如“步骤 3 · 移动”），并提供“保存并返回”“上一步/下一步”，连续改多步时不用反复回列表。
- 有输入的表单用“取消”表示不保存并返回；列表和选择器的“返回”只是导航，不会丢输入。
- 路径步骤有独立的路径点列表：逐个编辑、追加当前视角、插入、删除、上移/下移。
- 轨道设置可以改显示名称、说明、默认摇摆（预设/周期/种子）和是否跟随区块；入场效果可以添加、编辑、删除。
- “预览此步”只播放选中的这一步，播完自动停；结束回到原来的步骤表单，方便“改参数 → 预览这一段 → 继续改”。这一步之前的 `effect`/`title` 不会回放；遇到 `wait` 时用 `/zc advance <信号>` 推进。
- 等待步骤的“已有信号 >”会列出所有轨道 `wait` 步骤里出现过的信号，也可以直接手输新信号；效果表单有“常用效果 >”列表，同时保留自定义效果 id 输入。
- 所有修改立即自动保存，可撤销最近 30 次；`tracks.yml` 的写入、注释丢失和解析失败保护与命令编辑器相同。
- 需要 Paper/Folia 26.1.2 的 Dialog API；长文本和批量编辑仍然用 YAML/命令更顺手。
- 信号没有单独的声明文件：它就是 `wait` 步骤里的字符串，列表从已有步骤推导；效果 id 来自服务端药水效果注册表，常用列表只是快捷入口。

### 命令编辑器（仍可用）

适合脚本、远程控制或不想开窗口时使用：

```text
/zth-camera edit create login 登录 · 晨雾   # 以当前视角创建并进入编辑
/zth-camera edit add 100                    # 从当前位置追加一个移动步骤
/zth-camera edit here                       # 把选中步骤的目标改成当前视角
/zth-camera edit path                       # 创建路径步骤
/zth-camera edit point                      # 给选中路径追加当前视角
/zth-camera edit hold 40                    # 追加停留
/zth-camera edit wait authme:authenticated  # 追加等待信号
/zth-camera edit effect add darkness       # 追加黑暗效果（附身镜头下部分生效）
/zth-camera edit title 20 60 20 欢迎|正在同步 # 标题/副标题
/zth-camera edit select 3
/zth-camera edit exit-mode animation-end     # 默认：结束在镜头最后的位置
/zth-camera edit exit-mode pre-entry         # 结束后回到进入镜头前的位置
/zth-camera edit exit                        # 自定义退出点：当前脚下位置
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
/zc help [命令]      # 帮助；带命令名时显示用法、参数、权限与相关命令
/zc list
/zc info <track>
/zc play <track> [player]
/zc stop [player]
/zc advance <signal|*> [player]
/zc reload
/zc create <id> [显示名称]
/zc delete <id> confirm
/zc edit ...
/zc edit help [命令]
```

`/zc help` 按权限过滤，只列出当前玩家能用的子命令；每条命令都可以点击填入聊天栏，悬停显示完整用法，`/zc help <命令>` 给出用法、参数、权限节点和相关命令。帮助按字体的像素宽度排版（不按字符数），因此中英文混排也能对齐，且不会在命令中间换行。`/zc edit help` 用同样的排版覆盖全部编辑命令。

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
| 玩家按 Shift | 预览（UI 预览和 `/zc preview`）总是结束预览并回到编辑器；真实 `play` 默认锁定并提示，`session.allow-sneak-exit: true` 可允许真实播放退出 |
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

退出点不在安全位置时，玩家仍会按原版规则坠落/受伤；插件不会额外给飞行或无敌。默认的动画结束位置可能停在半空，需要安全落点时请选择“进入前的位置”或自定义 `exit`。

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

- Paper 26.1.2-74 + **官方 Minecraft 26.1.2 客户端**：观察者镜头成功附身，按住 W 不会移动镜头，高空镜头视角正常；默认 `/zc play`、远距离起点、标题、药水效果步骤、等待信号、结束落点、外部传送、死亡、退出重连恢复均通过。药水效果只验证了服务端状态；附身镜头下 `blindness`/`slowness` 没有画面效果，见 [docs/spectator-effects.md](docs/spectator-effects.md)。截图见 [docs/vanilla-camera-test.png](docs/vanilla-camera-test.png)。
- Folia 26.1.2-8 + ViaVersion/ViaBackwards：同一套 `login` 轨道播放、等待推进、结束落点通过，无 region 线程异常。
- 单元测试：`mvn test`，47 个测试全部通过。
- 帮助页面：全部帮助行（两种命令名、两个作用域、全部详情页）在单元测试中按字体像素宽度断言不超过聊天宽度，并校验权限过滤、点击填入与消息键完整性。
- 图形编辑器（Dialog）已通过编译与单元测试，尚未在 Paper 26.1.2 官方客户端上逐项点击验证；检查清单见 [docs/testing.md](docs/testing.md)。

- 2026-11-03：此前把镜头实体改成 `setVisibleByDefault(true)`，`showEntity` 于是变成空操作，生成包改由实体跟踪器按自己的节奏发出，`Set Camera` 抢先到达；原版 26.1.2 客户端因此无法附身，控制台持续刷 `moved too quickly`。现已恢复“实体默认隐藏、对玩家 `showEntity`、再设旁观目标”，并加入重发与未附身告警。

### 客户端兼容性（重要）

Horizon nightly 2026-11-01（26.3 协议）链路**不会锁定旁观者相机**。在同一个测试服上，不经过本插件、直接执行原版 `/spectate`（对盔甲架和僵尸都试过），客户端仍然可以自由移动；服务端 `getSpectatorTarget()` 和实体可见性都正常。这是该客户端链路没有实现 `Set Camera` 的表现，不是插件能绕过的协议问题。请使用官方 Java 版客户端验证镜头附身；如果它后续正确实现 `Set Camera` 包，这个镜头方案会自动生效。

插件现在会在第 45 tick 检查一次：如果这之后仍然收到玩家自身的移动包，说明客户端没有锁上相机，控制台会打印 `Spectator camera did not lock ...`。

## 性能

- 每个正在播放的玩家只有一个每 tick 的实体调度任务。
- 精确同步的镜头仍按实体跟踪周期给跟踪它的一个玩家发一个精确位置/朝向包，不额外生成实体或每 tick 任务。
- 路径曲线在步骤开始时一次采样，之后每次查询是二分查找 + 线性插值。
- 摇摆是常数时间三角函数，不分配大对象。
- 区块跟随只在镜头跨区块时触发玩家传送，不是每 tick。
- 轨道文件在编辑时异步顺序写入；恢复文件只在会话开始/结束时写删。
- 不使用 ProtocolLib，不发送自定义 payload。

## 已知限制

- 原版没有真正的全屏渐变、精确 FOV 曲线或景深；而且在“盔甲架 + 旁观者附身”下，`blindness`/`slowness` 等依赖镜头实体的效果没有画面，只有 `nausea`、部分 `darkness` 和大部分 `night_vision` 可用（见 [docs/spectator-effects.md](docs/spectator-effects.md)）。
- 镜头实体是盔甲架，依赖客户端正常处理旁观者目标；极少数客户端 mod/资源包可能改变表现。
- `sway` 的**左右旋转**（`yaw`）在部分客户端上会偶发 0.2–1° 的短促回抽；预设档位现已把 `yaw` 固定为 0（只做位置漂移与轻微俯仰）来规避，自定义 `yaw` 时仍可能出现。服务端这一侧已逐项实测排除——摇摆公式（单测钉死每 tick 位移上界）、角度量化（`precise-sync` 回读为 `on`）、传送与区块（静止 hold 无跨区块事件，`stale=0/20`）、姿态流本身（strong 档 `max/tick yaw` 0.074°，盔甲架每 tick 都被更新）；换成非生物实体（Marker）同样出现，所以也不是生物相机的身体/头部朝向路径。剩余原因在客户端对旁观实体相机的插值/渲染，服务端无法控制。
- 镜头的精确同步依赖 Paper 26.1.2 的映射字段；未来服务端改名时桥接会自动退回默认的量化跟踪（慢速旋转可能重新出现 1.4° 台阶），不会影响播放。
- 一条轨道不能跨世界。
- 编辑器会重写 `tracks.yml`，不保留手写注释；若文件里有解析失败的轨道，插件会拒绝保存，直到修好并重载。
- 图形编辑器依赖 Paper 26.1.2 的 Dialog API；命令编辑器始终可用。
- 从选中步骤预览不会回放之前的 `effect`/`title` 步骤；它们不是镜头状态，预览只从该步骤的镜头姿态开始。
- 没有循环轨道/多轨道混合；需要循环等待时，用 `wait` 或外部信号控制。
- 没有任意命令步骤（安全考虑）；流程编排交给胶水插件。

## 许可

内部自用插件，按 RIA 服务器需要维护。
