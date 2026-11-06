# 定位条（Locator Bar）与摄像机播放期间的隐藏方案

本文针对 **Paper 26.1.2 build 74-stable（MC 26.1.2）** 确认，结论以本机 jar 的反编译实证为主：

- Paper API：`C:\Users\magma\.m2\repository\io\papermc\paper\paper-api\26.1.2.build.74-stable\paper-api-26.1.2.build.74-stable.jar`
- Paper 服务端（Mojang 映射名）：`server-test/versions/26.1.2/paper-26.1.2.jar`
- 原版客户端（Mojang 映射、Mojang 签名）：`C:\Users\magma\AppData\Roaming\.minecraft\versions\26.1.2\26.1.2.jar`
- 工具：`javap` 25.0.3、`findstr`（对解压后的 `.class` 做常量池字符串检索）

> **实现状态（2026-11-07）**：本插件已按 §5.1 的方案 A 接入，代码与生命周期见 §9；`config.yml` 开关为 `session.hide-locator-bar`。

> **关于网络来源的限制**：本次调研环境中 `web_search` / `web_fetch` 均返回 `HTTP 401`（搜索/抓取端点鉴权失败），无法引用 minecraft.wiki、PaperMC GitHub 的页面。因此下文把“本地 jar 实证”与“推断”严格分开；没有任何一条结论依赖博客或二手文章。
>
> 26.1.2 在本机是**真实版本**（Mojang 签名的客户端 jar、`server-test/version_history.json` 为 `{"currentVersion":"26.1.2-74-e4e17fc (MC: 26.1.2)"}`），并非虚构；但由于无法联网，**未能核对 26.1.2 与真实 1.21.6+ 的版本对应关系**，相关处已标注。

> **最短答案**：按玩家隐藏用 `Attribute.WAYPOINT_RECEIVE_RANGE` + transient `MULTIPLY_SCALAR_1(-1)` 修正符（§5.1，纯 Bukkit API）；整世界隐藏用 `World#setGameRule(GameRule.LOCATOR_BAR, false)`（§5.2）。两者都让客户端 waypoint 表变空，定位条不是变空条，而是整个不画。

---

## 1. 结论摘要（TL;DR）

1. **没有“隐藏定位条”的专用 Bukkit/Paper API**。`paper-api` 里没有 locator/waypoint 类或事件；与 waypoint 相关的公开 API 只有 `LivingEntity#getWaypointColor/setWaypointColor/getWaypointStyle/setWaypointStyle`（只改颜色/图标样式，不能隐藏），以及属性 `Attribute.WAYPOINT_RECEIVE_RANGE` / `WAYPOINT_TRANSMIT_RANGE`。
2. **但存在官方、受支持的“按玩家”开关**：`org.bukkit.attribute.Attribute.WAYPOINT_RECEIVE_RANGE`（原版属性 `minecraft:waypoint_receive_range`）。把它降到 `0`，服务端会走 `ServerPlayer#onAttributeUpdated → ServerWaypointManager#removePlayer`，向该玩家发 `ClientboundTrackedWaypointPacket.removeWaypoint`，客户端 waypoint 表清空，**定位条整个消失**（不是空条）。把它恢复，`addPlayer` 会重建连接并重发 `TRACK`。本插件已采用此路径（§9）。
3. **全局（整世界）开关是官方 gamerule**：`GameRule.LOCATOR_BAR`（`/gamerule locator_bar false`；26.1.2 的 gamerule id 是 snake_case，实测 `/gamerule locatorBar` 会报未知参数），API 为 `World#setGameRule(GameRule.LOCATOR_BAR, false)`。它作用于整个 `ServerLevel`、写入 `level.dat`、并影响该世界所有玩家，不适合“只隐藏被拍摄者”。
4. **NMS 直改 `ServerWaypointManager.locatorBarEnabled = false` 是全局的，而且单独改字段不会隐藏任何东西**——已有连接必须再调用 `breakAllConnections()` 才会发 `UNTRACK`。不建议。
5. **PacketEvents 清空可行但恢复危险**：只取消 `TRACK` 而不重发，后续 `UPDATE` 会让客户端在 `ClientWaypointManager#updateWaypoint` 里对 `null` 调 `update()` → NPE 断线。
6. **没有隐藏整个 HUD 的 API**（没有 `hideHud`，`ClientOption` 只有视距/皮肤/聊天等），F1 是纯客户端行为，服务端无法控制。
7. 播放期间玩家处于**旁观者**模式并不会自动隐藏定位条：客户端 `Gui` 先渲染 contextual bar，之后才判断 `SPECTATOR`；旁观者仍然收 waypoint。

**一句话**：用户说的“应该有 API”只对了一半——没有“隐藏定位条”的 API，但 `waypoint_receive_range` 属性就是原版留给“按玩家开关定位条”的钩子，且 Bukkit 已公开；Paper 另有 waypoint 颜色/样式 API，但不能用来隐藏。

---

## 2. 定位条是怎么工作的

### 2.1 服务端：`ServerWaypointManager`（每个 `ServerLevel` 一个）

`javap -p net.minecraft.server.waypoints.ServerWaypointManager`：

```text
public class net.minecraft.server.waypoints.ServerWaypointManager
        implements net.minecraft.world.waypoints.WaypointManager<WaypointTransmitter> {
  private final Set<WaypointTransmitter> waypoints;
  private final Set<ServerPlayer> players;
  private final Table<ServerPlayer, WaypointTransmitter, WaypointTransmitter.Connection> connections;
  public boolean locatorBarEnabled;
  public void trackWaypoint(WaypointTransmitter);
  public void updateWaypoint(WaypointTransmitter);
  public void untrackWaypoint(WaypointTransmitter);
  public void addPlayer(ServerPlayer);
  public void updatePlayer(ServerPlayer);
  public void removePlayer(ServerPlayer);
  public void breakAllConnections();
  public void remakeConnections(WaypointTransmitter);
  private boolean isLocatorBarEnabledFor(ServerPlayer);
  ...
}
```

`ServerLevel` 持有它：`private final ServerWaypointManager waypointManager;` + `public ServerWaypointManager getWaypointManager()`。

**`locatorBarEnabled` 的来源是 gamerule，不是配置也不是权限**（构造器字节码）：

```text
35: invokevirtual ServerLevel.getGameRules:()Lnet/minecraft/world/level/gamerules/GameRules;
38: getstatic     GameRules.LOCATOR_BAR:Lnet/minecraft/world/level/gamerules/GameRule;
41: invokevirtual GameRules.get:(...)Ljava/lang/Object;
47: invokevirtual Boolean.booleanValue:()Z
50: putfield      ServerWaypointManager.locatorBarEnabled:Z
```

`GameRules` 注册处：`ldc "locator_bar"`、`GameRuleCategory.PLAYER`、默认 `iconst_1`（true）→ 即 `/gamerule locator_bar false`（命令 id 用 snake_case；Bukkit 侧枚举名是 `GameRule.LOCATOR_BAR`）。

**`isLocatorBarEnabledFor(ServerPlayer)` 不是 per-player 逻辑**：

```text
private boolean isLocatorBarEnabledFor(net.minecraft.server.level.ServerPlayer);
  Code:
     0: aload_0
     1: getfield  #51   // Field locatorBarEnabled:Z
     4: ireturn
```

它只是字段的 getter。至少在该 build 里，这段代码没有任何按玩家分支；Paper API 也没有暴露相关方法（见 §3）。

**连接与收发**（`EntityAzimuthConnection`，玩家作为 waypoint 源时使用）：

- `connect()` → `ClientboundTrackedWaypointPacket.addWaypointAzimuth(source.getUUID(), icon, angle)`
- `disconnect()` → `ClientboundTrackedWaypointPacket.removeWaypoint(source.getUUID())`
- `update()` → 角度变化超过 `0.008726646 rad`（约 0.5°）时发 `updateWaypointAzimuth`
- `isBroken()` → `doesSourceIgnoreReceiver(...) || isChunkVisible(source.chunkPosition(), receiver) || !isReallyFar(...)`
  （即：只有“区块未发送且距离 > 332 格”的源才保留箭头；附近/可见的玩家不画箭头）

`WaypointTransmitter.doesSourceIgnoreReceiver(LivingEntity source, ServerPlayer receiver)` 的关键分支：

```text
16: receiver.isSpectator() == true  -> return false   // 旁观者照收
25: source.isSpectator()   == true  -> return true    // 旁观的源不发
40: range = min(source.WAYPOINT_TRANSMIT_RANGE, receiver.WAYPOINT_RECEIVE_RANGE)
58: return source.distanceTo(receiver) >= range
```

即：**旁观者接收方不会被跳过**，所以播放期间（旁观者）定位条仍然显示。另外 `createConnection` 里有 `if (source == receiver) return;`——**玩家自己的 waypoint 不会发给自己**，条上没有“自己”。

**关键钩子 `ServerPlayer#onAttributeUpdated`**：

```text
1:  attribute.is(Attributes.WAYPOINT_RECEIVE_RANGE)
9:  ifeq 46
12: ServerWaypointManager mgr = level().getWaypointManager();
20: if (getAttributes().getValue(attribute) > 0.0) mgr.addPlayer(this);
41: else                                          mgr.removePlayer(this);
```

**`removePlayer` 做三件事**（字节码）：

1. `connections.row(player).values().removeIf(conn -> { conn.disconnect(); return true; })` → 给该玩家逐个发 `UNTRACK`；
2. `untrackWaypoint((WaypointTransmitter) player)` → 把自己从 `waypoints` 集合移除、断开“自己作为源”的所有连接；
3. `players.remove(player)`。

**`addPlayer` 反向**：把自己加回 `players`，对当前所有 waypoint 调 `createConnection`（发 `TRACK`），若 `isTransmittingWaypoint()` 再 `trackWaypoint(this)` 把自己重新广播给别人。

另一处相关判断：`ServerChunkCache#move(ServerPlayer)` 只在 `player.isReceivingWaypoints()` 为真时才调用 `waypointManager.updatePlayer(player)`——同一个属性值（`> 0`）也决定“玩家移动时是否刷新 waypoint 连接”。

属性默认值（`Player#createAttributes` 字节码）：

```text
67: getstatic Attributes.WAYPOINT_TRANSMIT_RANGE
70: ldc2_w   6.0E7
73: AttributeSupplier$Builder.add(holder, 6.0E7)
76: getstatic Attributes.WAYPOINT_RECEIVE_RANGE
79: ldc2_w   6.0E7
82: AttributeSupplier$Builder.add(holder, 6.0E7)
```

`Attributes` 注册：两者都是 `RangedAttribute(name, 0.0, 0.0, 6.0E7)`，`MAX_RANGE = 60000000`。

### 2.2 客户端：只有“有没有 waypoint”，没有独立开关

`Gui#nextContextualInfoState()`（`net.minecraft.client.gui.Gui`）反编译：

```text
10: invokevirtual ClientPacketListener.getWaypointManager()
13: invokevirtual ClientWaypointManager.hasWaypoints:()Z      -> hasWaypoints
24: LocalPlayer.jumpableVehicle() != null                      -> jumpable
43: MultiPlayerGameMode.hasExperience()                        -> hasExperience

47: if (!hasWaypoints) goto 85
51:   if (jumpable && willPrioritizeJumpInfo())  return JUMPABLE_VEHICLE
66:   if (hasExperience && willPrioritizeExperienceInfo()) return EXPERIENCE
81:   return LOCATOR
85: if (jumpable) return JUMPABLE_VEHICLE
93: if (hasExperience) return EXPERIENCE
101: return EMPTY
```

- `ClientWaypointManager.hasWaypoints()` = `!waypoints.isEmpty()`；
- `waypoints` 是 `ConcurrentHashMap<Either<UUID,String>, TrackedWaypoint>`；
- `trackWaypoint` = `map.put(id, wp)`；`untrackWaypoint` = `map.remove(id)`；`updateWaypoint` = `map.get(id).update(wp)`（**对不存在的 id 会 NPE**）；
- `Gui` 用 `contextualInfoBarRenderers` 把 `LOCATOR` 映射到 `LocatorBarRenderer`，`EXPERIENCE` 映射到 `ExperienceBarRenderer`，`EMPTY` 映射到 `ContextualBarRenderer.EMPTY`。

因此：**客户端没有“定位条开关”，只要 waypoint 表为空，定位条就不画**（不是画一条空条）。

`LocatorBarRenderer#extractRenderState` 用的参考实体是 **camera entity**：

```text
16: invokevirtual Minecraft.getCameraEntity:()Lnet/minecraft/world/entity/Entity;
55: Minecraft.player.connection.getWaypointManager()
75: ClientWaypointManager.forEachWaypoint(cameraEntity, consumer)
```

本插件的假玩家镜头只影响“以谁为中心排序/计算角度”，不影响“是否显示”——显示与否只看 `minecraft.player` 连接上的 waypoint 表。

### 2.3 旁观者 / 经验条的显示矩阵

`MultiPlayerGameMode#hasExperience()` = `localPlayerMode.isSurvival()`，所以旁观者/创造模式为 `false`。
`Gui#extractHotbarAndDecorations` 先渲染 contextual bar（`extractBackground` + `extractRenderState`），**之后**才判断 `getPlayerMode() == SPECTATOR` 去处理 actionbar / 旁观者菜单。

| 玩家状态 | waypoint 表非空 | waypoint 表为空 |
| --- | --- | --- |
| 生存（有经验等级） | 定位条 | 经验条 |
| 创造 | 定位条 | `EMPTY`（什么都不画） |
| 旁观（本插件播放时） | **定位条**（用户的现状） | `EMPTY`（什么都不画） |
| 骑乘可跳跃载具 | 视 `willPrioritizeJumpInfo()`，否则定位条/经验条 | 跳跃条 |

结论：**播放时把该玩家的 waypoint 表清空，定位条就消失，且旁观者不会退化成经验条**。

---

## 3. 官方 API 盘点（逐项核实）

| 途径 | 是否存在 | 粒度 | 证据 |
| --- | --- | --- | --- |
| Bukkit/Paper 的 locator/waypoint 类或事件 | **无** | — | 对 API jar 做 `jar tf` 并检索 locator/waypoint，无类输出；方法级只有下面三行（外观 API / 属性 / gamerule），`Player` 没有隐藏相关方法 |
| `Player#hideHud` / 任何 HUD API | **无** | — | API jar 中没有 HUD 类；`ClientOption` 只有 `SKIN_PARTS`、`CHAT_VISIBILITY`、`LOCALE`、`MAIN_HAND`、`VIEW_DISTANCE`、`TEXT_FILTERING_ENABLED`、`ALLOW_SERVER_LISTINGS`、`PARTICLE_VISIBILITY`、`CHAT_COLORS_ENABLED`，无 HUD/定位条项 |
| `LivingEntity` 的 waypoint 外观 API | **有** | 单实体 | `getWaypointColor/setWaypointColor`、`getWaypointStyle/setWaypointStyle`；`CraftLivingEntity#updateWaypoint` 做 untrack+track；只改图标颜色/样式，**不能隐藏** |
| `GameRule.LOCATOR_BAR` | **有** | 整世界 | `org.bukkit.GameRule.LOCATOR_BAR`（`javap` 可见）；对应 `GameRules.LOCATOR_BAR`，默认 true |
| `World#setGameRule(GameRule<T>, T)` | **有** | 整世界 | `org.bukkit.World` 方法签名；`CraftWorld#setGameRule` → `CraftEventFactory.handleGameRuleSet` → `GameRules.set(…, ServerLevel)` → `MinecraftServer#onGameRuleChanged`（已实证） |
| `WorldGameRuleChangeEvent` | **有**（可取消） | 整世界 | `io.papermc.paper.event.world.WorldGameRuleChangeEvent`（可 `getValue/setValue/cancel`）；`setGameRule` 被取消时返回 `false`，设置失败 |
| `Attribute.WAYPOINT_RECEIVE_RANGE` | **有** | **单玩家** | API 常量存在，映射到 `waypoint_receive_range`（`Attribute` 静态初始化里 `ldc "waypoint_receive_range"`） |
| `/waypoint` 命令 | 有，但**不能隐藏** | — | `WaypointCommand` 只有 `setWaypointStyle`、`setWaypointColor`、`resetWaypointColor`、`listWaypoints`（`javap -p`） |
| `/attribute <player> minecraft:waypoint_receive_range base set 0` | **有**（原版命令） | 单玩家 | `AttributeCommand` 有 `base set`；但 base 值写玩家存档，必须手动还原 |
| `server.properties` 配置项 | **无** | — | 26.1.2 服务端 `server.properties` 无 locator/waypoint 键；`DedicatedServerProperties` 无相关字段 |
| Paper 配置（`paper-global.yml` / `paper-world-defaults.yml`） | **无** | — | 本机 `server-test/config/*.yml` 检索 locator/waypoint 无命中 |
| Paper 事件 | **无** | — | API jar 无 waypoint 相关事件类 |
| 内置数据包 / 特性开关 `minecraft:locator_bar` | 存在痕迹，**不是运行时开关** | 世界创建期 | 客户端语言文件 `dataPack.locator_bar.name/description`；datafixer `V4424` 用 `ConverterRemoveFeatureFlag` 移除旧世界的 `minecraft:locator_bar`（26.1 起转正）。未验证能否/如何用它关掉定位条 |
| 客户端选项 | **无** | — | `net.minecraft.client.Options` 无 locator/waypoint 字段；`options.txt` 无此项 |

**结论**：官方与定位条相关的 API 只有三类，且都不是“隐藏定位条”的专用开关：

- 单玩家且能隐藏：`Attribute.WAYPOINT_RECEIVE_RANGE`（属性值 ≤ 0）；
- 整世界且能隐藏：`GameRule.LOCATOR_BAR`；
- 单实体但**不能隐藏**：`LivingEntity` 的 waypoint 颜色/样式 API。

---

## 4. 可行方案对比

| 方案 | 粒度 | 官方 API | 隐藏是否立即 | 恢复方式 | Folia | 主要副作用 / 风险 |
| --- | --- | --- | --- | --- | --- | --- |
| **A. `WAYPOINT_RECEIVE_RANGE` 归零（transient modifier）** | 单玩家 | ✅ Bukkit 属性 | 下一 tick | 移除 modifier → `addPlayer` 自动重发 `TRACK` | ✅ 玩家 region 线程安全 | 别人的条上也暂时看不到你（`removePlayer` 语义）；约 1 tick 延迟 |
| B. `World#setGameRule(LOCATOR_BAR,false)` | 整世界 | ✅ Bukkit GameRule | 立即（`breakAllConnections`） | 设回 true，服务端对所有玩家 `addPlayer` | ⚠️ 需在正确线程；写 `level.dat` | 影响同世界所有玩家；持久化；可能覆盖管理员设置；恢复时要处理并发修改 |
| C. NMS `manager.locatorBarEnabled = false` | 整世界 | ❌ 反射 | **不会**（必须再 `breakAllConnections()`） | 置 true + 对所有玩家 `addPlayer` | ⚠️ region 线程 | 同上，且更容易写错（只改字段等于没隐藏） |
| D. NMS `manager.removePlayer(serverPlayer)` / `addPlayer` | 单玩家 | ❌ 反射（但走原版逻辑） | 立即（同 tick） | `addPlayer` | ⚠️ region 线程 | 与 A 相同的“自己从别人条上消失”副作用；版本升级易碎 |
| E. PacketEvents 取消 `TRACK`/`UPDATE` + 主动发 `UNTRACK` | 单玩家 | ❌ 协议层 | 立即 | **必须重放 `TRACK`**，否则 `UPDATE` → 客户端 NPE 断线 | ⚠️ 事件线程 + 发包线程 | 复杂、脆弱；缓存/重放状态要自己维护 |
| F. 客户端 mod / F1 | 单客户端 | ❌ | 立即 | — | — | 服务端不可控，不能作为插件功能 |

---

## 5. 推荐方案与代码

### 5.1 推荐：`WAYPOINT_RECEIVE_RANGE` + 瞬时（transient）修正符

为什么用 **transient modifier** 而不是 `setBaseValue(0)`：

- `AttributeInstance#pack()`（玩家存档序列化）只保存 `baseValue` 与 `permanentModifiers`（`javap -c AttributeInstance` 可见 `getfield permanentModifiers`），**transient modifier 不落盘**。服务器崩溃时不会把玩家永久留在“无定位条”状态。
- `AttributeModifier.Operation.MULTIPLY_SCALAR_1`（Bukkit 侧）按 `ordinal()` 映射到原版 `ADD_MULTIPLIED_TOTAL`（`CraftAttributeInstance.convert` 用 `Operation.values()[bukkit.ordinal()]`），`amount = -1.0` 时结果恒为 0，**不受其他插件加算 modifier 影响**，比 `ADD_NUMBER(-6e7)` 稳。
- **原版自己就用同一套技巧**：玩家潜行时 `ServerPlayer` 给 `WAYPOINT_TRANSMIT_RANGE` 加 transient `ADD_MULTIPLIED_TOTAL(-1)` 修饰符（`WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER`），起身时 `removeModifier`（`javap -c ServerPlayer` 实证）。所以 transient 修正符不是插件 hack，而是原版实现“临时隐藏/收窄 waypoint”的标准方式；这里只是换到 `WAYPOINT_RECEIVE_RANGE` 上。
- 值被 `RangedAttribute` 夹到 `[0, 6e7]`，0 即“不接收”。
- 原版没有把 `WAYPOINT_RECEIVE_RANGE` 降到 0 的内容（`Player` 只给它 `6.0E7` 默认值；潜行修正作用于 `WAYPOINT_TRANSMIT_RANGE`），所以“receive 归零 = 关定位条”是原版逻辑自然产生的钩子；Mojang 未承诺这一语义长期稳定，升级时要回归验证。

```java
package net.ria.zth.camerarail.runtime;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 播放期间隐藏单个玩家的原版定位条（locator bar）。
 *
 * <p>原理：把 minecraft:waypoint_receive_range 压到 0。服务端在玩家下一次
 * tick 的 refreshDirtyAttributes 里看到值 <= 0，会调用
 * ServerWaypointManager#removePlayer：向该玩家发送所有 waypoint 的 UNTRACK，
 * 客户端 ClientWaypointManager 变空，Gui#nextContextualInfoState 不再返回
 * LOCATOR，定位条消失。恢复时移除 modifier，addPlayer 会重发 TRACK。</p>
 *
 * <p>所有方法都必须在玩家所属 region 线程调用（Folia：player.getScheduler()）。</p>
 */
final class LocatorBarHider {
    private final Plugin plugin;
    private final NamespacedKey key;

    LocatorBarHider(Plugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "locator_bar_hidden");
    }

    /** 只应在玩家 region 线程调用。 */
    void hide(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (instance == null || instance.getModifier(key) != null) {
            return;
        }
        instance.addTransientModifier(new AttributeModifier(
                key, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    /** 只应在玩家 region 线程调用。幂等。 */
    void show(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE);
        if (instance == null) {
            return;
        }
        if (instance.getModifier(key) != null) {
            instance.removeModifier(key);
        }
    }

    /** 从任意线程安全地安排到玩家 region 线程。 */
    void hideLater(Player player) {
        EntityScheduler scheduler = player.getScheduler();
        scheduler.execute(plugin, () -> hide(player), () -> { }, 0L);
    }

    void showLater(Player player) {
        EntityScheduler scheduler = player.getScheduler();
        scheduler.execute(plugin, () -> show(player), () -> { }, 0L);
    }
}
```

接入点（对应现有 `CameraEngine`）：

| 位置 | 调用 | 说明 |
| --- | --- | --- |
| `CameraEngine.attach(Session)`：`player.setGameMode(GameMode.SPECTATOR)` 之后 | `locatorBarHider.hide(player)` | 已经在玩家 scheduler 里执行 |
| `CameraEngine.restorePlayer(Session, boolean)` 的最外层（`original == null` 早退之前也要走到），或会话 end 的统一清理处 | `locatorBarHider.show(player)` | 和 `restoreEffects` 一样，**必须无条件执行**，避免异常路径残留 |
| 会话结束但玩家中途退出 | 不需要处理 | transient modifier 随实体消失，不落盘 |
| `applyRecovery(player, data)`：恢复 gamemode 后 | `locatorBarHider.show(player)` | 插件重载（同一进程）时玩家实体仍带 modifier；重启则本来就没有 |
| 玩家在会话中重生 / 换维度 | 重新 `hide(player)` | 重生会新建 `ServerPlayer`，属性修正符不一定被复制；建议在 `PlayerRespawnEvent`/会话 tick 里断言一次 |

延迟说明：属性修改后要等到 `LivingEntity.tick()` 末尾的 `refreshDirtyAttributes()` 才触发 `onAttributeUpdated`，所以**最多晚 1 tick**（50 ms）生效。若必须同 tick 生效，用 5.3 的 NMS 方案 D。

### 5.2 全局兜底：`GameRule.LOCATOR_BAR`（整世界）

适合“整个拍摄世界都不需要定位条”的服务器（例如专门的片场世界）：

```java
// 保存旧值，结束时还原；注意该调用影响该世界所有玩家。
Boolean previous = world.getGameRuleValue(GameRule.LOCATOR_BAR);
world.setGameRule(GameRule.LOCATOR_BAR, false);
// ... 播放 ...
if (previous != null) {
    world.setGameRule(GameRule.LOCATOR_BAR, previous);
}
```

要点：

- 写入 `level.dat`，重启后仍然生效；
- `World#setGameRule` 不是静默的：它会触发可取消的 `WorldGameRuleChangeEvent`（`CraftEventFactory.handleGameRuleSet` 实证），被别的插件取消时返回 `false`、设置不生效——要用返回值判断；
- `MinecraftServer#onGameRuleChanged(ServerLevel, GameRule, T)` 对 `LOCATOR_BAR` 的处理是：写 `manager.locatorBarEnabled`；为 `true` 时对该世界所有玩家 `addPlayer`，为 `false` 时 `breakAllConnections()`（因此会立即给所有人发 `UNTRACK`）；
- 与其它插件/管理员并发修改同一个 gamerule 时会互相覆盖，建议只在“专用片场世界”使用。

### 5.3 备选：NMS 直接调 `removePlayer`（需要同 tick 或不想碰属性时）

方法名在该 jar 中是 Mojang 映射（`paper-26.1.2.jar` 内就是 `net/minecraft/...`），反射名可用，但版本升级易碎：

```java
static Object handle(Player player) throws ReflectiveOperationException {
    return player.getClass().getMethod("getHandle").invoke(player);
}

static void removeFromWaypoints(Player player) throws ReflectiveOperationException {
    Object handle = handle(player);
    Object level = handle.getClass().getMethod("level").invoke(handle);          // ServerLevel
    Object manager = level.getClass().getMethod("getWaypointManager").invoke(level);
    Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
    manager.getClass().getMethod("removePlayer", serverPlayer).invoke(manager, handle);
}

static void addBackToWaypoints(Player player) throws ReflectiveOperationException {
    Object handle = handle(player);
    Object level = handle.getClass().getMethod("level").invoke(handle);
    Object manager = level.getClass().getMethod("getWaypointManager").invoke(level);
    Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
    manager.getClass().getMethod("addPlayer", serverPlayer).invoke(manager, handle);
}
```

调用必须在玩家 region 线程（`ServerWaypointManager` 内部是 `HashSet` + Guava `Table`，**没有任何同步**；Folia 下跨线程写属于数据竞争）。效果与方案 A 完全一致（同样会让别人看不到你），只是**没有 1 tick 延迟**。

### 5.4 不推荐：PacketEvents 清空 + 取消

`server-test/plugins/packetevents-2.14.0.jar` 里有：

```text
com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWaypoint
WrapperPlayServerWaypoint$Operation: TRACK / UNTRACK / UPDATE
com.github.retrooper.packetevents.protocol.world.waypoint.{TrackedWaypoint,WaypointIcon,...}
```

思路：对目标玩家取消 `TRACK`/`UPDATE`，并按已知 UUID 主动发 `removeWaypoint(uuid)`。**恢复时必须先重放缓存下来的 `TRACK`**，因为客户端：

```text
ClientWaypointManager.updateWaypoint(TrackedWaypoint wp):
  this.waypoints.get(wp.id()).update(wp)   // get 为 null -> NPE
```

服务端的 connection 仍然存活，会继续按角度/位置发 `UPDATE`；如果只恢复“放行”而不补 `TRACK`，客户端会在包处理线程抛 NPE 并断线。这比方案 A/D 复杂得多，收益只有“避免自己从别人条上消失”，不建议作为主路径。

### 5.5 手动验证（管理员 / 调试）

不写代码也能先确认“这条链路真的有效”：

```text
# 隐藏：下一 tick 生效
/attribute <player> minecraft:waypoint_receive_range base set 0
# 恢复：Player 的默认 base 是 60000000
/attribute <player> minecraft:waypoint_receive_range base set 60000000
```

注意：

- `base set` 会写进玩家 `playerdata`（**已实测**：设成 12345 后断线重连仍为 12345.0，见 §5.6），测试后必须还原，否则该玩家会一直看不到定位条；
- `/attribute ... modifier add <id> -1 add_multiplied_total` 也能隐藏，但 `AttributeCommand` 用的是 `addPermanentModifier`（`javap -c` 实证），同样落盘，必须 `modifier remove <id>`；
- 验证时机：玩家在**旁观者**模式下、附近有另一名玩家（有 waypoint 源）时观察屏幕底部的条是否消失。

**建议的验收清单**：

1. 播放开始后 ≤1 tick，被拍摄客户端的定位条消失（不是空条）；
2. 播放结束后 ≤1 tick 恢复，且经验条/定位条回到原状态；
3. 其他玩家的条上，被拍摄者的点也消失（预期副作用，确认服主可接受）；
4. 强制杀进程重启后，玩家登录不再处于“无定位条”状态（transient modifier 不落盘）；
5. Folia 下无跨线程异常/`AsyncCatcher` 报错。

### 5.6 本机实测记录（2026-11-07）

在 `server-test`（Paper 26.1.2 build 74-stable，world 平地）用 OP 玩家执行原版命令实测：

| 命令 | 结果 | 说明 |
| --- | --- | --- |
| `/gamerule locator_bar` | `locator_bar 目前为：true` | gamerule 存在且默认开；`/gamerule locatorBar` 报未知参数，**id 是 snake_case** |
| `/attribute A_Magma_Block minecraft:waypoint_receive_range base set 12345` → 断线重连 → `... get` | `12345.0` | **base 值会写进 playerdata**，重登/崩溃后仍保留 → 不能用 `setBaseValue(0)` 做“临时隐藏” |
| `/attribute A_Magma_Block minecraft:waypoint_receive_range modifier add zth:hide -1 add_multiplied_total` → `... get` | `0.0` | `MULTIPLY_SCALAR_1(-1)` 的结果确实是 0；26.1.2 命令的 operation 名是 `add_multiplied_total`（不是枚举名 `multiply_scalar_1`） |
| `/attribute ... modifier remove zth:hide` + `base set 60000000` → `... get` | `60000000.0` | 可完整还原 |

补充说明：

- 本机 Horizon 客户端（nightly-2026-11-03，Rust 实现）在这个测试服上**没有渲染出定位条**（单人时没有条；给猪设置 `waypoint_transmit_range=6e7` 后被 `/waypoint list` 记为 waypoint 也没画出来），所以无法用 Horizon 做肉眼验收；本节的客户端结论全部来自原版 26.1.2 `client.jar` 的字节码。最终视觉验收仍需原版客户端 + 第二名玩家（按上面的验收清单）。
- `/attribute ... modifier add` 走的是 `addPermanentModifier`（`AttributeCommand` 字节码），所以这条命令加出来的 modifier 也会落盘；只有插件的 `AttributeInstance#addTransientModifier` 不落盘。

---

## 6. 针对本插件播放流程的落地建议

1. **默认走方案 A**：`attach()` 切旁观者后 `hide`，`restorePlayer()` 的 `finally` 里 `show`，`applyRecovery` 后再 `show` 一次（幂等）。
2. **线程**：所有调用都通过 `player.getScheduler()`（现有代码已经这样调度玩家操作），不要从相机盔甲的 scheduler 里直接改玩家属性。
3. **旁观者模式**：切旁观者本身不会隐藏定位条（`doesSourceIgnoreReceiver` 对旁观者接收方返回 `false`），必须显式隐藏；反过来，隐藏后旁观者也不会出现经验条（`hasExperience()` 为 `false` → `EMPTY`），画面干净。
4. **假玩家镜头（PacketEvents）**：不影响定位条的显示判断（判断只看 `minecraft.player.connection.getWaypointManager()`）；只影响 `LocatorBarRenderer` 计算角度/距离时使用的 camera entity。隐藏方案对两种镜头模式通用。
5. **崩溃恢复**：transient modifier 不落盘，所以崩溃/重启后不会残留“永久无定位条”；但若插件在同一进程内被重载，实体上的 modifier 还在，恢复流程必须 `show`。
6. **配置**：建议加一个 `hide-locator-bar: true/false`（默认 true），并允许“仅在旁观者镜头期间隐藏”，方便服主在纯生存服里关掉此行为。
7. **`PlayerStateSnapshot` 不需要扩展**：用 transient modifier 不改变 base value，无需快照/还原；只有改用 `setBaseValue(0)` 时才必须在快照里记录原值（并且要接受它会被写进玩家存档；base 值持久化已实测，见 §5.6）。

---

## 7. 风险与未验证项

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 端到端视觉效果（定位条真的消失、恢复后回来） | **未实测** | 字节码链路证据完整；但本机 Horizon 客户端（Rust）没渲染出定位条（见 §5.6），无法肉眼验收，仍需原版客户端 + 第二名玩家 |
| `setBaseValue(0)` 的持久化 | **已实测** | 设 12345 后断线重连仍为 12345.0（§5.6）；所以 base value 只能做“持久修改”，临时隐藏必须用 transient modifier |
| 1 tick 延迟的观感 | 推断 | `LivingEntity#tick → refreshDirtyAttributes → onAttributeUpdated`，最多 50 ms |
| Folia 线程安全 | 推断（高置信） | `ServerWaypointManager` 用非并发 `HashSet`/`Table`；本插件所有玩家操作已在 `player.getScheduler()`，保持即可 |
| `World#setGameRule` 是否触发 `onGameRuleChanged` | **已实证** | `CraftWorld#setGameRule → CraftEventFactory.handleGameRuleSet → GameRules.set(…, ServerLevel) → MinecraftServer#onGameRuleChanged` |
| gamerule 在 Folia 的线程要求 | 未验证 | 涉及 `level.dat`，建议只在主线程/全局区域调度器上改 |
| 与其它插件同时改该属性/gamerule | 未验证 | `MULTIPLY_SCALAR_1 = -1` 对加算 modifier 免疫，但对方若也写 base value 仍可能冲突 |
| `WAYPOINT_RECEIVE_RANGE` 的语义稳定性 | 中风险 | 原版对 `WAYPOINT_TRANSMIT_RANGE` 已用同款 transient 技巧（潜行），但 `RECEIVE_RANGE` 归零不是原版内容；升级大版本需回归 |
| 26.1.2 与真实 1.21.6+ 的行为对应 | **未验证** | 无法联网；本机 jar 是唯一依据 |
| 内置数据包/特性开关 `minecraft:locator_bar` 的实际作用 | 未验证 | 只有语言键与 datafixer 证据；运行时是否能关闭未确认 |
| 重生/换维度后 modifier 是否保留 | 未验证 | 建议在恢复流程里幂等 `hide` |

---

## 8. 来源清单

### 8.1 本地实证（命令可复现）

```powershell
$api = 'C:\Users\magma\.m2\repository\io\papermc\paper\paper-api\26.1.2.build.74-stable\paper-api-26.1.2.build.74-stable.jar'
jar tf $api | Select-String -Pattern 'locator|waypoint'          # 无输出
javap -p -classpath $api org.bukkit.entity.Player                # 无 waypoint/locator/HUD 方法
javap -p -classpath $api org.bukkit.GameRule                     # LOCATOR_BAR 存在
javap -p -classpath $api org.bukkit.attribute.Attribute          # WAYPOINT_RECEIVE_RANGE / WAYPOINT_TRANSMIT_RANGE
javap -p -classpath $api com.destroystokyo.paper.ClientOption    # 无 HUD 选项

$srv = 'server-test\versions\26.1.2\paper-26.1.2.jar'
javap -p     -classpath $srv net.minecraft.server.waypoints.ServerWaypointManager
javap -p -c  -classpath $srv net.minecraft.server.waypoints.ServerWaypointManager   # 构造器读 LOCATOR_BAR；
                                                                                   # addPlayer/removePlayer/createConnection
                                                                                   # isLocatorBarEnabledFor 直接返回字段
javap -p -c  -classpath $srv net.minecraft.server.level.ServerPlayer                # onAttributeUpdated / isReceivingWaypoints / 潜行时的 WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER
javap -p -c  -classpath $srv net.minecraft.world.waypoints.WaypointTransmitter      # doesSourceIgnoreReceiver / isReallyFar
javap -p -c  -classpath $srv 'net.minecraft.world.waypoints.WaypointTransmitter$EntityAzimuthConnection'  # connect/disconnect/update
javap -p -c  -classpath $srv net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket
javap -p -c  -classpath $srv net.minecraft.server.commands.WaypointCommand           # 只有 color/style/list
javap -p -c  -classpath $srv net.minecraft.world.level.gamerules.GameRules           # locator_bar 默认 true
javap -p -c  -classpath $srv net.minecraft.server.MinecraftServer                    # onGameRuleChanged 处理 LOCATOR_BAR
javap -p -c  -classpath $srv net.minecraft.world.entity.ai.attributes.AttributeInstance  # pack() 只存 baseValue+permanentModifiers
javap -p -c  -classpath $srv org.bukkit.craftbukkit.attribute.CraftAttributeInstance # Operation 按 ordinal 映射
javap -p -c  -classpath $srv net.minecraft.world.entity.player.Player                # createAttributes: 6.0E7 默认范围

$cl = 'C:\Users\magma\AppData\Roaming\.minecraft\versions\26.1.2\26.1.2.jar'
javap -p -c  -classpath $cl net.minecraft.client.gui.Gui                             # nextContextualInfoState / 渲染顺序
javap -p -c  -classpath $cl net.minecraft.client.waypoints.ClientWaypointManager      # hasWaypoints / track/untrack/update
javap -p -c  -classpath $cl net.minecraft.client.gui.contextualbar.LocatorBarRenderer # 使用 getCameraEntity()
javap -p -c  -classpath $cl net.minecraft.client.multiplayer.MultiPlayerGameMode      # hasExperience = isSurvival
```

运行时实测（2026-11-07，Paper 26.1.2-74 本机测试服，OP 玩家，见 §5.6）：

```text
/gamerule locator_bar                                                    # -> true（id 为 snake_case）
/attribute <player> minecraft:waypoint_receive_range base set 12345      # 断线重连后仍为 12345.0（落盘）
/attribute <player> minecraft:waypoint_receive_range modifier add zth:hide -1 add_multiplied_total
/attribute <player> minecraft:waypoint_receive_range get                 # -> 0.0
```

### 8.2 关键类 / 方法索引

| 结论 | 类 | 方法 / 字段 |
| --- | --- | --- |
| gamerule 是开关 | `net.minecraft.world.level.gamerules.GameRules` / `GameRule` | `LOCATOR_BAR`（默认 true） |
| gamerule 变更的处理 | `net.minecraft.server.MinecraftServer` | `onGameRuleChanged(ServerLevel, GameRule, T)` |
| 每级管理器 | `net.minecraft.server.waypoints.ServerWaypointManager` | `locatorBarEnabled`、`addPlayer`、`removePlayer`、`breakAllConnections` |
| per-player 钩子 | `net.minecraft.server.level.ServerPlayer` | `onAttributeUpdated(Holder)`、`isReceivingWaypoints()` |
| 原版 transient 技巧 | `net.minecraft.server.level.ServerPlayer` | `WAYPOINT_TRANSMIT_RANGE_CROUCH_MODIFIER`：`ADD_MULTIPLIED_TOTAL(-1)` + `addOrUpdateTransientModifier` |
| 连接与包 | `net.minecraft.world.waypoints.WaypointTransmitter$Entity{Azimuth,Block,Chunk}Connection` | `connect` / `disconnect` / `update` / `isBroken` |
| 忽略规则 | `net.minecraft.world.waypoints.WaypointTransmitter` | `doesSourceIgnoreReceiver`、`isReallyFar`、`isChunkVisible` |
| 协议 | `net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket` | `removeWaypoint(UUID)` 等静态工厂 |
| 客户端显示判断 | `net.minecraft.client.gui.Gui` | `nextContextualInfoState()`、`extractHotbarAndDecorations` |
| 客户端 waypoint 表 | `net.minecraft.client.waypoints.ClientWaypointManager` | `hasWaypoints()`、`updateWaypoint`（缺失 id 会 NPE） |
| 客户端渲染 | `net.minecraft.client.gui.contextualbar.LocatorBarRenderer` | `extractRenderState`（以 `getCameraEntity()` 为参考） |

### 8.3 文档 / 版本

- `server-test/version_history.json`：`{"currentVersion":"26.1.2-74-e4e17fc (MC: 26.1.2)"}`
- `server-test/config/paper-global.yml`、`paper-world-defaults.yml`：无 locator/waypoint 配置
- `server-test/plugins/packetevents-2.14.0.jar`：`WrapperPlayServerWaypoint`（TRACK/UNTRACK/UPDATE）
- 外部链接：因本环境 `web_search`/`web_fetch` 返回 401，**未能引用** minecraft.wiki / PaperMC GitHub；上文所有结论均来自上述本地 jar。

---

## 9. 本插件的实现（已落地，2026-11-07）

本插件按 §5.1 的方案 A 实现，代码在 `net.ria.zth.camerarail.runtime.LocatorBarHider`，由 `CameraEngine` 管理整个生命周期：

| 时机 | 行为 |
| --- | --- |
| `CameraEngine.attach()`：旁观相机真正锁定玩家后 | 调用 `syncLocatorBar`，按配置加 transient `MULTIPLY_SCALAR_1(-1)` 修正符（`session.hide-locator-bar: true` 时） |
| 镜头 tick 每 20 tick | 在玩家调度器上复查：修正符被第三方清掉时补回；`/zc reload` 把配置改成 false 时移除 |
| `restorePlayer()` 的 `finally` | 无条件移除本插件的修正符（含 `original == null` 早退与异常路径） |
| `handleQuit()` / `recoverOnJoin()` / `applyRecovery()` | 兜底移除；重登后 `applyRecovery` 也会清一次（正常崩溃/重登时 transient 修正符已随实体消失） |
| `shutdown()`（插件禁用/关服） | Paper 主线程直接移除；Folia 在玩家调度器上尝试移除（Folia 不支持 `/reload`，正常关服时实体即将消失） |

状态管理边界：

- **只增删本插件命名空间下的修正符**（`<plugin>:locator_bar_hidden`）；不读取、不写回 base value，不碰其他插件的 modifier。第三方对 `waypoint_receive_range` 的 base/修正符修改在播放结束后原样生效；第三方若自己把值设为 0，播放结束后玩家仍保持他们设定的隐藏状态（我们不“帮”它恢复成可见）。
- 修正符用 `MULTIPLY_SCALAR_1(-1)`，最终值恒为 0：播放期间其他插件加算/加乘 base 的修正符无法让定位条回来；除非删除本插件的修正符，最多 20 tick 后会被补回。
- transient 修正符不落盘：崩溃、杀进程、断线重连都不会残留；服务端不提供该属性时只记录一次 WARN，镜头继续播放。
- **只影响被拍摄玩家**：不修改 gamerule、不操作其他玩家的属性。隐藏期间该玩家也不再作为其他玩家的 waypoint 源；但播放时该玩家是旁观者，原版 `doesSourceIgnoreReceiver` 本来就不会把旁观者显示给其他玩家，所以实际无额外差异。
- 与假玩家镜头兼容：隐藏判断只看 `minecraft.player` 连接上的 waypoint 表，与相机实体是盔甲架还是假玩家无关。
- 配置：`session.hide-locator-bar`（默认 `true`），`/zc reload` 后最多 20 tick 生效；只影响“隐藏”行为，不影响已加载的轨道。

验收：见 [testing.md](testing.md) 第 12 项；手动命令见本文 §5.5 与 §5.6。
