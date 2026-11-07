# 旁观者镜头下的药水效果与假玩家摄像机

本文针对 **Minecraft 26.1.2 原版客户端**（Paper 26.1.2 服务端）确认。客户端行为来自对 26.1.2 客户端 jar 的反编译；服务端行为来自 Paper 26.1.2 与 PacketEvents 2.14.0 的实际启动验证。

## 当前实现：盔甲架锚点 + 客户端假玩家

从 2026-11 起，插件采用两层结构：

```text
服务端：隐藏盔甲架（真正的 spectator target、区块票据、每 tick 吸附、跨区块跟随）
   │
   │  ClientboundSetCameraPacket(armorStand)
   ▼
PacketEvents：把包里的 camera id 改写成假玩家 id
   │
   ▼
客户端：只存在于该客户端的 PLAYER 实体（假玩家）
   = client Camera entity / AbstractClientPlayer
```

- **服务端 `getSpectatorTarget()` 仍然是盔甲架**。`ServerPlayer#tick` 继续把玩家身体吸附到盔甲架，`follow-camera` 继续用玩家实体加载区块。
- 假玩家镜头激活时，盔甲架**不会**发给该客户端，只作为服务端 spectator target 存在。旁观者客户端能看见 invisible 实体，若把盔甲架也发过去，镜头位置会出现半透明模型；只有回退到盔甲架镜头时才 `showEntity`。
- **客户端 Camera 是假玩家**。假玩家由 PacketEvents 的 `PlayerInfoUpdate`、`SpawnEntity(PLAYER)`、`EntityMetadata`、`UpdateAttributes`、`EntityPositionSync`、`EntityHeadLook` 生成；没有服务端实体、没有连接、没有区块票据。
- 假玩家的位置、pitch 和身体朝向每 3 tick 从同一份镜头姿态同步一次（`EntityPositionSync`），节奏与精确实体跟踪一致；客户端负责插值。
- **视角 yaw 走 head yaw**。客户端旁观相机读取 `LivingEntity#getViewYRot`（即 `yHeadRot`），而 `EntityPositionSync` 只写身体朝向；假玩家没有服务端实体，原版 `ServerEntity` 不会替它生成 `ClientboundRotateHeadPacket`。因此插件在量化后的 head yaw 变化时逐镜头 tick 下发 `EntityHeadLook`；`spawn` 包里的 head yaw 只保证初始视角正确，之后的旋转完全依赖这个包。
- head yaw 在协议中只有 1 字节（1/256 圈 = 1.40625°），客户端还会对它做 3 tick 插值；慢速缓动因此可能有亚度级台阶和约 2–3 tick 的跟随滞后。这与盔甲架回退模式（`ArmorStand` 同为 `LivingEntity`）以及旁观原版玩家时一致，不是假玩家路径特有的退化。
- 假玩家的 FOV 由插件计算 `MOVEMENT_SPEED` 并下发属性包；`slowness`/`speed` 不再作为效果包发给假玩家，避免效果与属性双重修正。
- 会话结束时先停止改写 `Set Camera`，让服务端把客户端镜头切回玩家自身，再销毁假玩家并移除其 PlayerInfo。

这解决的不是“让盔甲架拥有玩家属性”，而是**换一个客户端 Camera 实体**：服务端锚点保持原样，客户端看到一个真正的 `AbstractClientPlayer`。

## 客户端到底读谁

| 渲染点 | 读取对象 | 假玩家镜头下 |
| --- | --- | --- |
| 视角摇晃 `GameRenderer#tick` / `#renderLevel` | `minecraft.player` | `nausea` 由真实玩家提供，完整生效 |
| 亮度、黑暗脉动 `LightmapRenderStateExtractor#extract` | `minecraft.player` | `darkness` / `night_vision` 由真实玩家提供 |
| FOV `Camera#tickFov` | `getCameraEntity() instanceof AbstractClientPlayer` | 假玩家是 `AbstractClientPlayer` → FOV 生效 |
| 失明/黑暗天空 `Camera#extractRenderState` | `camera.entity()` | 假玩家收到效果 → 生效 |
| 失明/黑暗/夜视雾 `FogRenderer` | `camera.entity()` | 假玩家收到效果 → 生效 |
| 水下、着火、方块遮脸 `ScreenEffectRenderer` | `minecraft.player`，并检查 `isSpectator()` | 旁观者被明确跳过，仍然没有 |
| HUD 效果图标 `Gui#extractEffects` | `minecraft.player`，看 `showIcon` | 插件设置 `showIcon=false`；客户端/整合包仍可能显示自己的效果提示，但它只是 darkness，不是 blindness |
| 全屏渐变/黑场 | 没有原版协议包 | 仍然不能做 |

因此，同一个药水效果现在可以同时发给两个实体：

- **真实玩家**：负责 `nausea`、亮度图、HUD 状态；
- **假玩家**：负责 FOV、失明/黑暗天空、雾。

`slowness` / `speed` 没有独立的客户端渲染代码，它们通过 `MOVEMENT_SPEED` 属性改变 FOV；假玩家是玩家实体，所以这条路径终于能生效。

但这里有一个容易踩的坑：**客户端不会为实体应用药水自带的属性修正**。`LivingEntity#onEffectAdded` 在 `level.isClientSide()` 时直接返回，属性修正原本由服务端计算并通过 `ClientboundUpdateAttributesPacket` 下发。假玩家没有服务端实体，因此插件必须自己根据 `slowness`/`speed` 等级计算移动速度，并用 `WrapperPlayServerUpdateAttributes` 下发；只发 `EntityEffect` 效果包不会改变 FOV。

## FOV 的实际行为

`AbstractClientPlayer#getFieldOfViewModifier` 的核心是：

```text
modifier = (MOVEMENT_SPEED / walkingSpeed + 1) / 2
```

玩家默认 `walkingSpeed = 0.1`、`MOVEMENT_SPEED = 0.1`，所以默认倍率是 1.0。`slowness` 每级给 `MOVEMENT_SPEED` 一个 `-15%` 的乘算修正，`speed` 每级 `+20%`：

| 效果 | 等级（amplifier） | 近似 FOV 倍率 |
| --- | --- | --- |
| `slowness` | 0 / 1 / 2 / 3 / 4 | 0.925 / 0.85 / 0.775 / 0.70 / 0.625 |
| `speed` | 0 / 1 / 2 / 3 / 4 | 1.10 / 1.20 / 1.30 / 1.40 / 1.50（上限） |

最终视野还会乘以客户端“视野”设置，并受 `Camera#tickFov` 的 `0.1..1.5` 限制和 0.5 平滑影响。客户端“效果视角”选项（`fovEffectScale`）为 0 时，FOV 变化会被插值回 1.0，这是原版行为。

插件目前**没有独立的 `fov` 步骤字段**；FOV 通过现有的 `effect` 步骤使用 `slowness` / `speed` 表达。这样效果时长、等级和移除 action 都能复用现有轨道格式。

## 效果能力表

| 效果 | 假玩家镜头 | 说明 |
| --- | --- | --- |
| `nausea` | ✅ 完整 | 视角摇晃读真实玩家 |
| `blindness` | ✅ 雾 + 天空压暗 | 假玩家收到效果 |
| `darkness` | ✅ 亮度 + 雾 + 天空 | 真实玩家提供亮度，假玩家提供雾/天空 |
| `night_vision` | ✅ 亮度 + 雾色 | 同上 |
| `slowness` | ✅ FOV 收窄（原生 26.1.2） | 假玩家 `MOVEMENT_SPEED` 属性包 |
| `speed` | ✅ FOV 展宽（原生 26.1.2） | 假玩家 `MOVEMENT_SPEED` 属性包 |
| `regeneration` / `resistance` / `absorption` 等 | 无画面 | 原本就没有屏幕渲染，只影响状态 |
| 水下/着火/方块贴脸 | ❌ | `ScreenEffectRenderer` 对旁观者直接跳过 |
| 全屏渐变/黑场 | ❌ | 原版没有对应协议包 |

`blindness` 是“雾 + 天空压暗”，不是全屏纯黑渐变；`darkness` 也不是无缝黑场。需要真正的淡入淡出时，仍然只能用标题/资源包/客户端 mod 或后期。

## darkness 不是 blindness

`darkness` 和 `blindness` 在客户端都会压暗天空、加近雾，所以看起来很像；但代码里没有把 darkness 映射成 blindness：

- `TrackParser` / `EditorManager` 把 `darkness` 解析为 `minecraft:darkness`；
- `PacketCameraView#cameraPotion` 把 `darkness` 映射为 `PotionTypes.DARKNESS`；
- `EffectSpec` 只把 `spec.type()` 加到真实玩家，不存在隐式添加 `blindness` 的路径。

验证方法：播放只含 darkness 的轨道后执行 `/data get entity @s active_effects`，结果应只有 `minecraft:darkness`；如果同时出现 `blindness`，它来自另一条显式 `blindness` 步骤、之前的残留效果或外部 `/effect`，不是 darkness 生成的。

如果 HUD 同时出现 **`失明 ∞`** 和 **`黑暗`**，先检查：

- 编辑器 → 轨道概览 → **入场效果**；
- 或 `/camerarail info <轨道>` 的 **入场效果** 行；
- 或 `tracks.yml` 的 `entry-effects`。

`entry-effects` 里如果有一条 `blindness` 且没有写 `duration`，它会按“持续到轨道结束”处理；只移除 darkness 不会移除这条 blindness。需要给它设置有限 `duration`，或另加一个 `action: remove` 的 blindness 步骤。已经存在的无限 blindness 可以用 `/effect clear @s` 清掉一次。

两个容易误判为“没生效/没失效”的点：

- **淡入/淡出约 22 tick（约 1.1 秒）**。`darkness` 的 `MobEffect` 设置了 blend duration；短于这个时长的效果只会部分变暗，移除后也会继续淡出一段时间。
- **duration 0 表示持续到被移除或轨道结束**。如果只写 `effect: darkness` 而不写 `duration`，它不会自动在几秒后消失；需要另加一个 `action: remove` 步骤，或设置有限 `duration`。

另外，效果生命周期做了两层保护：附身时重放 entry-effects 会跳过已经过期的项；每 tick 还会检查真实玩家是否仍有该效果，若玩家被 `/effect clear`、死亡或效果被外部移除，会立刻移除假玩家上的对应效果，避免雾残留。

## 为什么只给盔甲架发效果没用

- `LivingEntity#onEffectAdded` / `#onEffectUpdated` / `#onEffectsRemoved` 只把 `ClientboundUpdateMobEffectPacket` 发给**乘客**（`sendEffectToPassengers`）；`ServerEntity#sendPairingData` 不包含效果实例。
- `setSpectatorTarget(armorStand)` 只是客户端相机目标，不是骑乘关系，旁观者不在盔甲架的乘客列表里。
- 因此即使服务端给盔甲架 `addPotionEffect`，旁观者客户端的盔甲架 `activeEffects` 仍是空的。
- 假玩家方案绕开了这个问题：效果包直接发给客户端假玩家的 entity id，客户端把效果加到 `RemotePlayer` 上。

## 没有 PacketEvents 时的回退

PacketEvents 是软依赖。未安装、API 未初始化或假玩家生成失败时：

- 服务端盔甲架镜头继续工作；
- 客户端 Camera 是盔甲架，FOV 恒为 1.0；
- `nausea` 完整，`darkness` 部分（亮度/脉动，缺雾/天空），`night_vision` 大部分（亮度，缺雾色）；
- `blindness`、`slowness`、`speed` 没有画面效果。

回退不会阻止轨道播放；控制台会说明当前使用的是盔甲架镜头。具体提示：

- 启动：WARN `PacketEvents not installed; fake-player camera disabled...`；
- 播放含镜头相关效果的轨道：聊天栏提示一次，控制台按轨道 id 只 WARN 一次；
- `/camerarail info <轨道>`：显示“镜头模式: 假玩家 (PacketEvents)”或“镜头模式: 盔甲架回退”；
- 编辑器轨道概览、效果选择器和效果表单：显示“盔甲架回退模式”及受影响的 FOV/失明雾；
- `/camerarail edit effect add ...`：追加成功后，聊天栏追加同样的提示。

假玩家镜头创建失败（PacketEvents 存在但发包失败）时，控制台保留异常堆栈，玩家聊天栏收到 `camera.fake-camera-failed` 并回退到盔甲架。

## 这是不是 Minecraft 的 bug？

从代码看，它更像**三个独立设计在旁观者相机上撞出的边界情况**：

1. 屏幕类效果（`nausea`、HUD、`darkness`/`night_vision` 的亮度）按本地玩家读取；
2. 环境/相机类效果（FOV、雾、天空）按 `camera.entity()` 读取；
3. 非玩家实体的效果包只发给乘客，而不是所有观察者。

正常游戏里 `camera.entity()` 就是玩家自己（或玩家正在骑的载具，效果仍按玩家读），三者不会冲突。旁观者把 `camera.entity()` 换成另一个实体后，三者就对不上了。部分限制是刻意的（`ScreenEffectRenderer` 明确跳过旁观者的水下/着火），但“同一个效果一半读玩家、一半读镜头实体”确实不一致。它不造成崩溃或服务端状态错误。

## 游戏内验证

先确认启动日志出现：

```text
[CameraRail] PacketEvents fake-player camera enabled.
```

用编辑器给一条轨道加效果步骤：

```text
/camerarail edit effect add blindness
/camerarail edit effect add slowness 1
/camerarail edit effect add darkness
/camerarail edit effect add night_vision
/camerarail edit effect add nausea
```

播放后预期：

- `blindness`：雾变近、天空压暗；
- `slowness 1`：FOV 平滑收窄到约 0.85；
- `darkness`：画面变暗、天空/雾变暗；
- `night_vision`：亮度提升、雾色变亮；
- `nausea`：镜头摇晃；
- 移除效果或结束轨道后，FOV/雾/天空恢复。

服务端仍可用 `/camerarail info` 观察轨道状态；不要在假玩家镜头下用 `/effect give` 给盔甲架来验证，效果包不会发给旁观者。

## 历史方案对比

在假玩家方案之前，曾评估过：

- **给盔甲架注入 NMS 效果包**：能让雾/天空工作，但 FOV 仍不行，且依赖 26.1.2 映射；
- **让旁观者骑盔甲架**：可以利用乘客效果同步，但 FOV 仍不行，且要处理上马/下马、Folia 和反作弊；
- **真正的 NPC/真实玩家相机**：FOV 可以，但需要服务端假连接或 Citizens 一类方案，成本高。

现在的假玩家方案是**客户端实体 + 服务端盔甲架锚点**的折中：不需要服务端 NPC，不需要改 `ServerPlayer#tick`，同时拿到了 FOV 和相机实体效果。
