# 本地验证

## 单元测试

```text
mvn test
```

当前测试覆盖：

- 所有缓动曲线端点与单调性。
- yaw 最短角插值、pitch 限制、三维距离。
- Catmull-Rom 路径端点、连续性和 yaw 跨 ±180°。
- 摇摆零振幅、振幅边界、目标切换渐变。
- YAML 严格解析、`sway: off` 布尔兼容、重复 wait 信号、越界 pitch。
- `tracks.yml` 写入后重新解析的 round-trip。

## Paper 26.1.2 冒烟测试

构建后在本地 Paper 26.1.2（build 74 stable）加载：

```text
mvn package
java -jar paper-26.1.2-74.jar nogui
```

检查控制台：

```text
[ZthCameraRail] Loaded 1 camera track(s).
[ZthCameraRail] ZthCameraRail enabled: 1 track(s), Paper/Folia ready.
```

命令：

```text
/zth-camera list
/zth-camera reload
/zth-camera info login
```

## 实际镜头验证

测试环境使用离线模式、平地世界和本地 bot 客户端（mineflayer 连接后观察 `gameMode`、位置和视角变化）。以下行为已在 Paper 与 Folia 上实际跑通：

1. `play` 后玩家切到 `SPECTATOR`，隐藏盔甲架实体存在，玩家位置随镜头跨区块。
2. 镜头沿 `move` 和 `path` 移动；yaw/pitch 连续变化。
3. `wait` 进入等待，`advance` 后继续并完成。
4. 自然结束 `CameraTrackEndEvent` 的 `completed=true`，玩家回到 `exit` 并恢复原游戏模式。
5. 外部 `/tp` 结束会话，日志为 `reason=EXTERNAL_TELEPORT`，不再抢回位置。
6. 按 Shift 不会退出镜头（默认策略），玩家保持 `SPECTATOR`。
7. 在镜头中退出，恢复文件保留；重连后回到播放前的位置并收到恢复消息。
8. `/zth-camera reload` 能重新读取编辑后的 `tracks.yml`。
9. 游戏内编辑：创建、追加、修改目标/时长/摇摆、wait/effect/title、预览、自动保存、重载。
10. Folia 下使用实体调度器 + `teleportAsync`，镜头和区块跟随可用，无 Folia 线程异常。

上述真实客户端验证是在本地离线测试服完成的；测试脚本属于临时环境，不随插件发布。

## 如何判断客户端真的附身了

旁观相机是纯客户端状态，服务端读不回来，但可以观察玩家自身的移动包：客户端一旦锁上旁观相机，`LocalPlayer#sendPosition` 就不再发送任何位置包。

- 镜头移动时控制台**没有** `moved too quickly`，玩家位置跟着镜头走却没有发出移动包 → 附身成功。
- 控制台持续 `moved too quickly!`、玩家每隔一会儿被瞬移到盔甲架位置 → 客户端没有锁上相机。插件会在第 45 tick 打印 `Spectator camera did not lock for <玩家> (track=...)`。
- 排查顺序：客户端版本与协议（ViaVersion/ViaBackwards 之类是否在翻译）、是否有别的插件取消 `PlayerStartSpectatingEntityEvent`、镜头实体是否被别的插件 `hideEntity` 或删除。

只验证“服务端 `getSpectatorTarget()` 是盔甲架”是不够的：这个字段在客户端丢包时同样成立，正是 `setVisibleByDefault(true)` 那次回归没被发现的原因。

## 手工检查清单

- 站到安全位置，`/zc play test`；确认没有视角硬切（第一帧可能由进入前的 `blindness` 遮盖）。
- 用 `/zc advance <signal>` 推进 wait。
- 等待超时是否回到原位。
- 镜头中按 Shift / 尝试左键附身其他实体。
- 镜头中让其他插件或 `/tp` 传送自己。
- 镜头中退出并重连。
- 镜头中 `/stop`。
- 预览编辑器，结束后是否回到编辑前的位置。
- 编辑后 `/zc reload` 是否仍然有效。
- 配置一个无效轨道，确认其他轨道仍加载，日志指出具体 YAML 路径。
- 把 `exit` 配到安全地面；不要依赖插件替你防摔。

## Folia 26.1.2

```text
java -jar folia-26.1.2-8.jar nogui
```

重点观察：

- 启动日志中出现 `ZthCameraRail enabled`。
- 镜头跨 chunk/region 时没有 `Must use teleportAsync while in region threading` 异常；镜头实体在 Folia 下统一走 `teleportAsync`。
- 会话结束后玩家恢复为原游戏模式。
- 插件禁用/重载时没有跨 region 访问异常。

本地 Folia 使用 `teleportAsync` 队列更新镜头实体；如果服务端 region 负载很高，镜头更新频率可能略低于 20 tps，但客户端仍会在已有位置间插值。若服务器有非常严格的移动反作弊，建议对旁观者过场单独放行或降低跟踪强度。
