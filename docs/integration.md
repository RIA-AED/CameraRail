# 与其他插件的集成

ZthCameraRail 不依赖 AuthMe、小游戏插件或脚本引擎。假玩家镜头需要可选的 PacketEvents，但未安装时只会回退到盔甲架镜头，不影响下面的 API、事件或命令。它只暴露三样东西：

1. Bukkit `ServicesManager` 里的 `CameraService`。
2. 三个事件。
3. 命令：`/zth-camera play`、`/zth-camera advance`、`/zth-camera stop`。

## 获取服务

```java
import net.ria.zth.camerarail.api.CameraService;
import org.bukkit.Bukkit;

CameraService cameras = Bukkit.getServicesManager().load(CameraService.class);
if (cameras == null) {
    getLogger().warning("ZthCameraRail is not installed");
    return;
}
```

`play` 返回 `true` 只表示请求已受理，真正的接管是异步的（要等玩家调度器/区域调度器）。如果需要确认第一帧已经准备好，监听 `CameraWaitEvent`，或使用 `CameraTrackStartEvent` 后延迟一 tick。

## 登录 / AuthMe 场景

推荐顺序：

1. 玩家进入服务器，AuthMe 完成自己的 limbo/位置处理。
2. 你的胶水插件确定“现在可以让镜头接管了”。
3. `cameras.play(player, "login")`。
4. 镜头进入 `wait authme:authenticated`，`CameraWaitEvent` 可以用来更新登录 HUD。
5. AuthMe 登录成功回调里：
   ```java
   cameras.advance(player, "authme:authenticated");
   ```
6. `CameraTrackEndEvent` 触发后，再执行 AuthMe 的“正式进入游戏”传送/状态切换，避免外部传送打断镜头。

```java
@EventHandler
public void onAuthmeLogin(SomeAuthMeLoginEvent event) {
    CameraService cameras = Bukkit.getServicesManager().load(CameraService.class);
    if (cameras != null) {
        cameras.advance(event.getPlayer(), "authme:authenticated");
    }
}
```

> AuthMe 的类名/事件名会随版本变化，上面的 `SomeAuthMeLoginEvent` 只是占位。请以你实际安装的 AuthMe 版本 API 为准，或让脚本引擎执行命令 `/zc advance authme:authenticated <player>`。

### 外部传送会把镜头交还给对方

如果 AuthMe 在镜头播放期间把玩家传送到别的位置，`PlayerTeleportEvent` 会被视为外部传送，镜头立即结束（不抢回位置）。这是刻意设计，避免两个插件互相 teleport。请：

- 在 AuthMe 的位置稳定后再 `play`，或
- 把 AuthMe 的传送推迟到 `CameraTrackEndEvent` 之后。

如果必须让 AuthMe 在镜头期间传送玩家本体，可以关闭轨道的 `follow`，但外部传送仍会结束会话；这不是支持的并行控制方式。

## 小游戏 / 场景转场

```java
public void startRound(Player player, GameArena arena) {
    CameraService cameras = Bukkit.getServicesManager().load(CameraService.class);
    if (cameras == null) {
        player.teleport(arena.spawn());
        return;
    }
    cameras.play(player, "arena_intro");
}

@EventHandler
public void onCountdownFinished(CountdownFinishedEvent event) {
    CameraService cameras = Bukkit.getServicesManager().load(CameraService.class);
    if (cameras != null) {
        cameras.advance(event.getPlayer(), "arena:ready");
    }
}
```

一个典型轨道：

```yaml
arena_intro:
  start: { ... }       # 从场景外推入
  steps:
    - { type: move, to: { ... }, duration: 80 }
    - { type: wait, signal: "arena:ready", timeout: 1200 }
    - { type: path, duration: 100, points: [...] }
    - { type: hold, duration: 20 }
```

小游戏插件负责实际传送、清空背包、倒计时；ZthCameraRail 只负责镜头。

## 提前信号

`advance` 是队列式的。信号早于 `wait` 到达时会先记录，进入匹配的 wait 后消费一次。因此即使 AuthMe 回调和轨道启动存在竞态，也不容易丢信号。

```java
cameras.play(player, "login");
// 可能在同一 tick 或更早发生
cameras.advance(player, "authme:authenticated");
```

每个 `wait` 对应一次 `advance` 调用；同一 track 内重复信号名会被 YAML 校验拒绝。

## 事件

### CameraTrackStartEvent

在玩家被切到旁观者之前触发，可取消：

```java
@EventHandler
public void onCameraStart(CameraTrackStartEvent event) {
    if (event.preview()) {
        return;
    }
    if (!canUseCinematic(event.getPlayer())) {
        event.setCancelled(true);
    }
}
```

取消后玩家完全不会被改动，也不会收到结束事件。

### CameraWaitEvent

```java
@EventHandler
public void onCameraWait(CameraWaitEvent event) {
    authHud.show(event.getPlayer(), event.signal());
}
```

触发在玩家调度器上，适合安全访问玩家。

### CameraTrackEndEvent

```java
@EventHandler
public void onCameraEnd(CameraTrackEndEvent event) {
    if (event.reason() == EndReason.FINISHED && event.completed()) {
        joinGame(event.getPlayer());
    }
}
```

`completed` 只在自然走完并成功把玩家放到 `exit-mode` 指定的位置时为 `true`。中止、超时、外部传送、退出等均为 `false`。`preview` 为编辑器预览。

`EndReason`：`FINISHED`、`STOPPED`、`QUIT`、`KICKED`、`TIMEOUT`、`LIMIT`、`CAMERA_LOST`、`EXTERNAL_TELEPORT`、`GAME_MODE_CHANGE`、`WORLD_CHANGE`、`DEATH`、`ERROR`、`RECOVERED`。

## 命令桥

如果胶水层是脚本/控制台命令：

```text
/zth-camera play login <player>
/zth-camera advance authme:authenticated <player>
/zth-camera stop <player>
```

控制台执行 `play` 时必须提供玩家名。`advance` 也支持 `*` 表示推进当前 wait。

## 线程与 Folia

`CameraService` 的所有方法都是线程安全的，可以从任意线程调用。内部会调度到玩家/实体区域。事件在玩家所在的 region 线程触发，监听器里不要在服务端线程之外做阻塞 I/O。

不要自己每 tick 传送被镜头接管的玩家；那会和 `follow-camera` 的区块跟随竞争。需要移动玩家时，先 `stop(player)`，等 `CameraTrackEndEvent` 后再操作。

## 恢复语义

开始播放前，插件会写一个恢复文件。正常结束会删除；玩家退出时保留，并在下次登录时把玩家传回播放前的位置、恢复游戏模式/飞行/药水效果。服务器崩溃/关闭也走同一机制。

因此：

- 不要清理 `plugins/ZthCameraRail/recovery/` 下的文件，除非你明确要放弃恢复。
- 如果玩家在镜头期间退出后又立即重连，会看到一条灰色的“已恢复”消息，并被传送回原位置。
- 如果原世界没有加载，恢复会跳过传送并提示，但仍尽量恢复游戏模式。

## 关闭 / 重载

- `/zth-camera reload` 在有人处于编辑器时会拒绝，避免覆盖正在编辑的草稿。
- 重载不会打断已经开始的播放；已有会话使用开始时的不可变轨道快照。
- 插件禁用时会尽量恢复在线玩家的游戏模式和效果，并保留恢复文件供下次登录修复。
