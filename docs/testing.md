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
6. 真实 `play` 按 Shift 不会退出镜头（默认策略），玩家保持 `SPECTATOR`。
7. 任何预览（UI“预览此步”/“预览全部”和 `/zc preview`）按 Shift 会结束预览；UI 预览结束后回到原来的编辑器界面。
8. 在镜头中退出，恢复文件保留；重连后回到播放前的位置并收到恢复消息。
9. `/zth-camera reload` 能重新读取编辑后的 `tracks.yml`。
10. 游戏内编辑：创建、追加、修改目标/时长/摇摆、wait/effect/title、预览、自动保存、重载。
11. Folia 下使用实体调度器 + `teleportAsync`，镜头和区块跟随可用，无 Folia 线程异常。

上述真实客户端验证是在本地离线测试服完成的；测试脚本属于临时环境，不随插件发布。

## 如何判断客户端真的附身了

旁观相机是纯客户端状态，服务端读不回来，但可以观察玩家自身的移动包：客户端一旦锁上旁观相机，`LocalPlayer#sendPosition` 就不再发送任何位置包。

- 镜头移动时控制台**没有** `moved too quickly`，玩家位置跟着镜头走却没有发出移动包 → 附身成功。
- 控制台持续 `moved too quickly!`、玩家每隔一会儿被瞬移到盔甲架位置 → 客户端没有锁上相机。插件会在第 45 tick 打印 `Spectator camera did not lock for <玩家> (track=...)`。
- 排查顺序：客户端版本与协议（ViaVersion/ViaBackwards 之类是否在翻译）、是否有别的插件取消 `PlayerStartSpectatingEntityEvent`、镜头实体是否被别的插件 `hideEntity` 或删除。

只验证“服务端 `getSpectatorTarget()` 是盔甲架”是不够的：这个字段在客户端丢包时同样成立，正是 `setVisibleByDefault(true)` 那次回归没被发现的原因。

## 手工检查清单

- 站到安全位置，`/zc play test`；确认没有视角硬切（第一帧可能由进入前的 `blindness` 遮盖）。
- 用一条只有慢速 `move`/`path`（时长 120+ tick、`ease-in-out`）的轨道检查旋转：缓动两端不应出现“停一下、跳约 1.4°”的台阶；`debug: true` 时启动日志应包含 `precise-sync=true`。
- 用 `/zc advance <signal>` 推进 wait。
- 等待超时是否回到原位。
- 真实 `play` 中按 Shift 不会脱离（默认）；`/zc preview` 和编辑器预览中按 Shift 应结束预览，UI 预览回到原编辑器界面。
- 镜头中让其他插件或 `/tp` 传送自己。
- 镜头中退出并重连。
- 镜头中 `/stop`。
- 预览编辑器，结束后是否回到编辑前的位置。
- 编辑后 `/zc reload` 是否仍然有效。
- 配置一个无效轨道，确认其他轨道仍加载，日志指出具体 YAML 路径。
- 把 `exit` 配到安全地面；不要依赖插件替你防摔。
- `/zc help` 与 `/zc edit help`：分组、对齐、不换行；点击命令填入聊天栏；悬停显示用法；`/zc help play` 等详情页包含用法、参数、权限和相关命令。
- 用只有 `zth.camera.command`（或再给 `play`）的账号执行 `/zc help`，确认看不到无权限的子命令，补全同样只出现可用命令。

## Dialog 编辑器检查清单

- `/zc edit` 打开轨道列表；选择已有轨道、创建新轨道、分页与刷新正常。
- 关窗飞行后再 `/zc edit`，回到上次的轨道概览/步骤页/步骤表单和页码，而不是总回轨道概览。
- 轨道概览的步骤数、总时长、摇摆、跟随与实际 YAML 一致。
- 添加 7 种步骤；每种表单的默认值合理，保存后步骤列表和 `tracks.yml` 同步更新。
- 步骤表单标题带序号；保存后可在原表单继续改，“保存并返回”回步骤列表；“上一步/下一步”按已保存内容切换。
- 移动/硬切表单改坐标、时长、缓动、摇摆；点“取当前视角”后窗口关闭、倒计时、回填并重开。
- 缓动/摇摆选项显示中文名和行为说明；等待表单的“已有信号 >”列出所有轨道出现过的信号并可回填；效果表单的“常用效果 >”可回填常用 id，自定义 id 仍可用。
- 路径点列表：追加当前视角、逐点编辑、插入、删除、上移/下移；路径点数量上限生效。
- 轨道设置：显示名称、说明、摇摆预设/周期/种子、跟随；自定义摇摆振幅不会被意外覆盖（选择 `custom`）。
- 起点/退出点：起点取眼睛、退出点取脚下；清除退出点后结束回到进入前位置。
- 入场效果：添加、编辑、删除；只能添加，等级/时长生效。
- 步骤上移/下移/复制/删除、撤销；删除全部步骤后列表不崩。
- “预览此步”只播放选中的这一步，播到该步骤结束就停；之前的效果/标题不回放；预览结束回到原步骤表单，而不是步骤列表。
- 从步骤列表/轨道概览启动的预览，结束后分别回到步骤列表/轨道概览；停止预览后能回到编辑器。
- 两个管理员同时编辑同一轨道时，第二个被拒绝且提示清楚。
- Paper 控制台没有 `showDialog`/Dialog 序列化异常；Folia 下 UI 操作都在玩家调度器上执行。
- 保存失败（例如 `tracks.yml` 有无法解析的段落）时，编辑器显示保存失败，且不会覆盖手写段落。

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
