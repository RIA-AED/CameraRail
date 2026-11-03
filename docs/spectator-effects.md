# 旁观者附身镜头下的药水效果

本文针对 **Minecraft 26.1.2 原版客户端**（Paper 26.1.2 服务端）确认。结论来自对客户端 jar 的反编译，而不是只看服务端 `getPotionEffects()` 是否为空。

## 结论

1. `effect` 步骤施加给 **玩家**（`CameraEngine#applyEffectNow` 里的 `session.player`），不是盔甲架。目标对象本身是对的，不要改成 `session.camera`。
2. 但“施加给玩家”不等于“画面上一定看得到”。附身到盔甲架后，客户端把效果分成两类读取者：
   - `minecraft.player`（看画面的旁观者）；
   - `camera entity`（当前是盔甲架）。
3. 盔甲架身上的药水效果**不会**同步给旁观者，所以把效果改施加到盔甲架也不会生效。
4. 在“盔甲架 + 旁观者附身”模式下，真正有画面的只剩：
   - `nausea`：完整；
   - `darkness`：部分（世界亮度/脉动，缺雾和天空压暗）；
   - `night_vision`：大部分（亮度提升，缺雾色提亮）。

   `blindness`、`slowness`、`speed` 等没有画面效果。

## 客户端到底读谁

| 渲染点 | 读取对象 | 附身盔甲架时 |
| --- | --- | --- |
| 视角摇晃 `GameRenderer#tick` / `#renderLevel` | `minecraft.player` | `nausea` 生效 |
| 亮度、黑暗脉动 `LightmapRenderStateExtractor#extract` | `minecraft.player` | `darkness`、`night_vision` 生效 |
| FOV `Camera#tickFov` | `getCameraEntity() instanceof AbstractClientPlayer` | 盔甲架不是玩家 → 倍率恒为 1.0 |
| 失明/黑暗天空 `Camera#extractRenderState`（`doesMobEffectBlockSky`） | `camera.entity()` | 盔甲架没有效果 → 不压暗 |
| 失明/黑暗/夜视雾 `FogRenderer` + `BlindnessFogEnvironment` / `DarknessFogEnvironment` | `camera.entity()` | 盔甲架没有效果 → 无雾 |
| 水下、着火、方块遮脸 `ScreenEffectRenderer` | `minecraft.player`，并检查 `isSpectator()` / `noPhysics` | 旁观者被明确跳过 |
| HUD 效果图标 `Gui#extractEffects` | `minecraft.player`，看 `showIcon` | 插件设为 `false`，本来就不显示 |

`blindness` 在客户端渲染路径里只被 `Camera`（天空压暗）和 `BlindnessFogEnvironment`（雾）读取，二者都读 `camera.entity()`；`Player#isBlind` 只影响疾跑判定，不产生画面。`slowness` 没有独立的客户端引用，它通过 `MOVEMENT_SPEED` 属性影响 FOV，而 FOV 只对 `AbstractClientPlayer` 镜头实体生效。因此这两个效果在附身盔甲架时是“零像素”的。

`darkness` 和 `night_vision` 则是“一半一半”：亮度图读旁观者自己，所以能变暗/提亮；雾（以及 `darkness` 的天空压暗）读镜头实体，所以不会跟着变。

## 为什么施加给盔甲架也没用

- `LivingEntity#onEffectAdded` / `#onEffectUpdated` / `#onEffectsRemoved` 只把 `ClientboundUpdateMobEffectPacket` 发给**乘客**（`sendEffectToPassengers`）；`ServerEntity#sendPairingData` 不包含效果实例。
- `setSpectatorTarget(armorStand)` 只是客户端相机目标，不是骑乘关系，旁观者不在盔甲架的乘客列表里。
- 因此旁观者客户端的盔甲架 `activeEffects` 是空的；即使服务端给盔甲架 `addPotionEffect`，`Camera` / `FogRenderer` 也读不到。

## 为什么“有时看起来又是对的”

- **自由旁观**（`/gamemode spectator` 后不 `spectate` 任何实体）：`camera.entity()` 回落到玩家自己，所以 `blindness`、`slowness` 等会正常渲染；只有水下/着火/方块遮脸被旁观者逻辑跳过。
- **附身到盔甲架**：`camera.entity()` 变成盔甲架，上述依赖镜头实体的效果立刻失效。
- 附身到另一个**玩家**时，FOV 读的是那个玩家的移动速度属性，而不是旁观者自己的效果；失明雾仍依赖该玩家的效果实例，而效果包不会发给旁观者，所以也不完整。

这解释了“旁观者附身状态下药水效果渲染很奇怪”：不是随机，而是不同渲染点读不同实体。

## 这是不是 Minecraft 的 bug？

从代码看，它更像**三个独立设计在旁观者相机上撞出的边界情况**，而不是一个单点 bug：

1. 屏幕类效果（`nausea`、HUD、`darkness`/`night_vision` 的亮度）按本地玩家读取。
2. 环境/相机类效果（FOV、`blindness`/`darkness` 的雾和天空）按 `camera.entity()` 读取；这是为了让第一人称、第三人称和骑乘共用同一套 `Camera` 代码。
3. 非玩家实体的效果包只发给乘客，而不是所有观察者。

正常游戏里 `camera.entity()` 就是玩家自己（或玩家正在骑的载具，而效果仍按玩家读），1/2/3 不会冲突。旁观者把 `camera.entity()` 换成另一个实体后，三者就对不上了。

所以：从玩家视角可以把它当 bug 反馈；从实现看，它属于 spectator 相机没有定义清楚“效果归属”的未处理组合。部分限制是刻意的（`ScreenEffectRenderer` 明确跳过旁观者的水下/着火，HUD 也按旁观者隐藏），但“同一个效果一半读玩家、一半读镜头实体”这一点确实不一致。它不造成崩溃或服务端状态错误，也没有证据表明是 26.1.2 新引入的回归。

## 骑乘能解决什么

先澄清一个关键点：**Camera 由 `ClientboundSetCameraPacket`（Bukkit 的 `setSpectatorTarget`）决定，不由载具决定。** 骑上实体不会自动把 Camera 变成玩家。

| 做法 | Camera 实体 | 效果 | 镜头旋转 |
| --- | --- | --- | --- |
| 保持现状：spectate 盔甲架 | 盔甲架 | 只有玩家类效果 | 服务端锁定，平滑 |
| 骑别的实体，但保留 spectator target | 盔甲架 | 和现状一样 | 服务端锁定 |
| 骑别的实体，并清掉 spectator target | **玩家** | 玩家类效果全部可用（`blindness`/`slowness`/`nausea`/`darkness`/`night_vision`） | **客户端控制，无法像盔甲架那样锁定** |
| 骑**盔甲架本身**，保留 spectator target | 盔甲架 | 盔甲架的效果包会发给乘客 → `blindness`/`darkness`/`night_vision` 的雾/天空可用；FOV 仍不行 | 服务端锁定 |

补充：

- 普通旁观者右键**不能**上坐骑：`Player#interactOn` 对 spectator 直接返回，`ArmorStand#interact` 也只返回成功而不调用 `startRiding`。
- 服务端可以强制：原版 `/ride <player> mount <entity>` 用的是 `startRiding(entity, force=true)`，会绕过 `canRide`/`canAddPassenger`；插件也可以走 NMS 的 `startRiding(..., true)`。Bukkit `Entity#addPassenger` 是否带 force 取决于服务端实现，需要实测；失败时用 NMS。
- **骑盔甲架 + 保留 spectator target** 是一个有意思的纯服务端折中：玩家成为盔甲架的乘客后，`LivingEntity#sendEffectToPassengers` 会把盔甲架的效果包发给玩家。这样 `blindness` 的雾/天空、`darkness`/`night_vision` 的雾都能工作，而镜头仍由盔甲架控制、保持平滑。缺点是它属于利用载具同步的 hack：要处理潜行/下马、镜头实体删除、Folia 跨 region、反作弊和效果包时序（效果要在上马**之后**施加，否则要等 600 tick 的刷新）。
- **Camera = 玩家**时虽然效果全了，但视角旋转由客户端鼠标控制，服务端只能每 tick 改 rotation，无法像当前盔甲架那样锁住镜头；所以只适合 `hold`/固定机位，或者你愿意接受玩家自由看。

## 对镜头设计的直接影响

在当前的盔甲架 + 旁观者模式下：

- `blindness` 不能用来遮硬切，也不会在入场时压黑画面；
- `slowness` / `speed` 不能做 FOV 变焦；
- `darkness` 只能做“世界变暗/脉动”，天空和雾不会变；
- `night_vision` 能提亮画面，但雾色不变；
- 水下、着火、方块贴脸等屏幕叠加层对旁观者不可用；南瓜头/细雪在 `Gui` 里另有路径，但不是药水效果；
- 受击/死亡倾斜、走路摇晃、暗角等读镜头实体，盔甲架没有，天然不会出现（对电影镜头反而是好事）。

## 可选方案

1. **保留附身，接受限制**：只用 `nausea` / `darkness` / `night_vision`；冲击/手持感用 `sway` 或路径直接做，不依赖药水；黑场和变焦交给客户端 mod/资源包或后期。
2. **效果镜头改用自由旁观**：不设 spectator target，镜头实体就是玩家；`blindness`/`slowness` 可用，但玩家位置只能靠服务端传送，失去实体插值的平滑度，适合静止/`hold` 或本来就被黑场遮住的切换。
3. **NMS 注入盔甲架效果包**：把 `ClientboundUpdateMobEffectPacket` 直接发给旁观者，让客户端认为盔甲架有 `blindness`/`darkness`/`night_vision`。这样雾和天空能工作，但 FOV 仍然不行（盔甲架不是 `AbstractClientPlayer`），且依赖 26.1.2 映射，版本升级会坏。目前未实现。
4. **骑盔甲架，让效果包发给乘客**：不依赖 NMS，保留服务端锁定的镜头；能让 `blindness`/`darkness`/`night_vision` 的雾/天空工作，但 FOV 仍然不行，且要处理上马/下马和 Folia/反作弊的边界。目前未实现。
5. **真正的 FOV / 全屏渐变**：需要镜头实体是玩家（真实玩家/NPC）或客户端 mod/光影；纯服务端协议没有 FOV 包，也没有全屏黑场包。

## 游戏内验证

自由旁观：

```text
/gamemode spectator @s
/effect give @s minecraft:blindness 30 0 true
```

附身盔甲架：

```text
/summon armor_stand ~ ~ ~ {Marker:1b,Invisible:1b,NoGravity:1b}
/spectate @e[type=armor_stand,limit=1,sort=nearest] @s
/effect give @s minecraft:blindness 30 0 true
```

预期：自由旁观时画面起雾/压暗；附身后同一效果不再改变画面。把 `blindness` 换成 `nausea` 应仍能看到视角摇晃，换成 `slowness` 应只在自由旁观时收窄 FOV。

骑盔甲架、让效果包发给乘客：

```text
/summon armor_stand ~ ~ ~ {Marker:1b,Invisible:1b,NoGravity:1b,Tags:["cam"]}
/ride <player> mount @e[type=armor_stand,tag=cam,limit=1]
/spectate @e[type=armor_stand,tag=cam,limit=1] <player>
/effect give @e[type=armor_stand,tag=cam,limit=1] minecraft:blindness 30 0 true
```

预期：镜头仍然是盔甲架视角，但画面会出现失明雾/压暗；`slowness` 仍不会改变 FOV。注意效果要在上马之后施加，否则乘客可能收不到首次效果包。
