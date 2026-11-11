# 本地验证

## 单元测试

```text
mvn test
```

当前测试覆盖：

- 所有缓动曲线端点与单调性。
- yaw 最短角插值、pitch 限制、三维距离。
- Catmull-Rom 路径端点、连续性、yaw 跨 ±180°，以及路径首点与起点重合 / 原地转身 / 1 mm 间距时 yaw、pitch 不越过关键帧范围；报告轨迹按 60 tick、`ease-in-out` 播放时每 tick |Δyaw| < 3° 且全程最多一次方向反转；位置全部重合的纯旋转路径不出现 NaN 或越界（回归：path 起点视角抽动）。
- 摇摆零振幅、振幅边界、目标切换渐变、同种子可复现，以及跨 `period` 边界不跳变（单 tick 位移不超过解析上界）。
- YAML 严格解析、`sway: off` 布尔兼容、重复 wait 信号、越界 pitch。
- `cut` 停留时长：YAML 解析、省略时默认 0、越界校验、按 0 计入时间轴、写入 round-trip。
- `tracks.yml` 写入后重新解析的 round-trip。
- 假玩家镜头的发包计划：head yaw 的 1/256 圈量化（含负角度截断边界）、首次必发与量化值去重、位置/身体包保持 3 tick 节奏。
- 假玩家镜头硬切重建后的同步节奏：`CameraEntitySync.reset()` 后下一个 tick 重新发送位置与 head yaw，随后恢复 3 tick 位置节奏（`CameraEntitySyncTest.resetRestartsPositionAndHeadSync`）。
- 入场效果的生效时机：只有从轨道起点开始（真实播放或从起点预览）才施加；从中间步骤开始的预览不施加。

## Paper 26.1.2 冒烟测试

构建后在本地 Paper 26.1.2（build 74 stable）加载：

```text
mvn package
java -jar paper-26.1.2-74.jar nogui
```

检查控制台：

```text
[CameraRail] Loaded 1 camera track(s).
[CameraRail] PacketEvents fake-player camera enabled.
[CameraRail] CameraRail enabled: 1 track(s), Paper/Folia ready.
```

未安装 PacketEvents 时第二行应为 WARN：

```text
[CameraRail] PacketEvents not installed; fake-player camera disabled. FOV and blindness/slowness/speed camera effects are unavailable; using the armor-stand camera. Install PacketEvents 2.14.0+ to enable them.
```

命令：

```text
/camerarail list
/camerarail reload
/camerarail info login
```

## 实际镜头验证

测试环境使用离线模式、平地世界和本地 bot 客户端（mineflayer 连接后观察 `gameMode`、位置和视角变化）。以下行为已在 Paper 与 Folia 上实际跑通；其中第 1–11 项是基础盔甲架镜头，假玩家镜头需要额外按下一节验证：

1. `play` 后玩家切到 `SPECTATOR`，隐藏盔甲架实体存在，玩家位置随镜头跨区块。
2. 镜头沿 `move` 和 `path` 移动；yaw/pitch 连续变化。
3. `wait` 进入等待，`advance` 后继续并完成。
4. 自然结束 `CameraTrackEndEvent` 的 `completed=true`，玩家按 `exit-mode` 落点（默认停在镜头最后的位置）并恢复原游戏模式。
5. 外部 `/tp` 结束会话，日志为 `reason=EXTERNAL_TELEPORT`，不再抢回位置。
6. 真实 `play` 按 Shift 不会退出镜头（默认策略），玩家保持 `SPECTATOR`。
7. 任何预览（UI“预览此步”/“预览全部”和 `/camerarail preview`）按 Shift 会结束预览；UI 预览结束后回到原来的编辑器界面。
8. 在镜头中退出，恢复文件保留；重连后回到播放前的位置并收到恢复消息。
9. `/camerarail reload` 能重新读取编辑后的 `tracks.yml`。
10. 游戏内编辑：创建、追加、修改目标/时长/摇摆、wait/effect/title、预览、自动保存、重载。
11. Folia 下使用实体调度器 + `teleportAsync`，镜头和区块跟随可用，无 Folia 线程异常。
12. 播放开始、相机锁定后 ≤1 tick，被拍摄玩家客户端的定位条消失；`/camerarail stop`、自然结束、退出、外部传送等异常结束后 ≤1 tick 恢复。修改 `session.hide-locator-bar` 后 `/camerarail reload`，播放中的隐藏状态在 20 tick 内跟随配置（背景与命令见 [locator-bar.md](locator-bar.md) 第 9 节）。
13. Folia 下远距离 `cut`（跨 region）：真实 `play` 不应出现“过场动画中无法离开镜头”动作栏提示，编辑器预览不应提前结束，且会话继续正常播放和退出。假玩家镜头下，服务端日志应出现一条 `Server released the spectator camera ... without a Shift input` INFO（该事件由服务端在镜头实体跨 region 时替换实体、清空旧旁观目标产生，插件不再把它当作 Shift 操作）；盔甲架回退模式的 `cut` 会主动解除并重新锁定相机，因此不产生这条日志，但同样应完整播放。Paper 上同一轨道通常不应出现该日志（镜头实体不会被替换；其他插件或传送路径主动释放相机时仍可能出现，且会走同一条静默重锁分支）。

上述真实客户端验证是在本地离线测试服完成的；测试脚本属于临时环境，不随插件发布。假玩家镜头是 2026-11 新增的实现，服务端启动和 PacketEvents 注册已冒烟通过，但客户端画面仍需在目标客户端版本上按下一节验收。

## 假玩家镜头验证

先安装 PacketEvents 2.14.0+，启动日志出现 `PacketEvents fake-player camera enabled`。然后：

1. 播放任意轨道；服务端 `getSpectatorTarget()` 应仍是盔甲架，客户端第三人称/旁观相机应正常移动，没有自由视角或镜头卡死；含 yaw/pitch 变化的 `move`/`path` 应能看到视角旋转，**包括 `wait` 信号推进之后的步骤**（回归点：修复前假玩家镜头只发 `EntityPositionSync`，画面 yaw 会冻结在 spawn 时的角度）。
2. 进入镜头后按 F3 查看实体数量：客户端会多一个 `CamRail...` 玩家实体，但 tab 列表不显示它，画面里也不应看到它的模型（第一人称相机实体本身不渲染）。
3. 假玩家模式下客户端**不应存在**盔甲架实体；镜头位置不应出现半透明盔甲架。临时移除 PacketEvents 回退后，盔甲架才应通过 `showEntity` 出现并成为相机。
4. 用 `effect: blindness` 检查失明雾和天空压暗；用 `effect: darkness` 检查亮度/雾/天空，确认 `/data get entity @s active_effects` 中只有 `darkness`，没有额外 `blindness`。darkness 的淡入约 22 tick，短于此时长不要判定为“没生效”。
5. 用 `effect: slowness`（等级 0/1/2）检查 FOV 逐级收窄，用 `effect: speed` 检查展宽。效果移除或轨道结束后 FOV 应恢复。
6. 在 `wait` 中按 Shift、被 `/tp`、切世界、退出重连，确认假玩家被清理，客户端没有残留的“卡在假玩家镜头”状态。
7. 临时移走 PacketEvents jar，重启后应只打印回退日志，所有轨道仍能播放；此时 `blindness`/`slowness` 应回到无画面状态，盔甲架应重新通过 `showEntity` 出现在客户端。
8. Folia 下重复第 1、5 项，重点看玩家与盔甲架跨 region 时是否出现线程异常或假玩家位置滞后。
9. 跨版本链路（客户端 26.2/26.3 经 ViaVersion 连接 26.1.2）单独测一遍 FOV：假玩家是客户端侧实体，链路可能不转发其属性包；目标环境应使用 26.1.2 原生客户端验收。
10. 移除 PacketEvents 后重启，确认启动 WARN、GUI 回退状态、`/camerarail edit effect add` 的聊天栏提示，以及 `/camerarail play` 时只对相关轨道提示；纯 `nausea` 轨道不应触发这些提示。
11. Folia 远距离 `cut`（跨 region）后：播放中按 Shift 仍应被锁定（出现一次锁定提示）；预览中按 Shift 仍应结束预览。无 Shift 的服务端释放分支不会被误判为 Shift——假玩家镜头下日志只出现 `Server released the spectator camera ... without a Shift input`，不会出现锁定提示，也不会结束预览；盔甲架回退模式不产生该事件，行为同上。

## 如何判断客户端真的附身了

旁观相机是纯客户端状态，服务端读不回来，但可以观察玩家自身的移动包：客户端一旦锁上旁观相机，`LocalPlayer#sendPosition` 就不再发送任何位置包。

- 镜头移动时控制台**没有** `moved too quickly`，玩家位置跟着镜头走却没有发出移动包 → 附身成功。
- 控制台持续 `moved too quickly!`、玩家每隔一会儿被瞬移到盔甲架位置 → 客户端没有锁上相机。插件会在第 45 tick 打印 `Spectator camera did not lock for <玩家> (track=...)`。
- 排查顺序：客户端版本与协议（ViaVersion/ViaBackwards 之类是否在翻译）、是否有别的插件取消 `PlayerStartSpectatingEntityEvent`、镜头实体是否被别的插件 `hideEntity` 或删除。

只验证“服务端 `getSpectatorTarget()` 是盔甲架”是不够的：这个字段在客户端丢包时同样成立。假玩家模式还要确认客户端**没有**收到盔甲架实体；只有回退模式才应通过 `showEntity` 把盔甲架发给客户端。旁观者能看到 invisible 实体，因此不能靠 `setInvisible(true)` 或 `setVisible(false)` 来隐藏镜头位置的盔甲架。

## 手工检查清单

- 站到安全位置，`/camerarail play test`；确认没有视角硬切。安装 PacketEvents 时可以用 `blindness` 压暗/遮切；未安装时不要依赖它（见 [spectator-effects.md](spectator-effects.md)）。
- 假玩家镜头下：`nausea` 有视角摇晃；`darkness` 变暗并压天空/起雾；`blindness` 起雾并压天；`slowness`/`speed` 改变 FOV；`night_vision` 提亮并改变雾色。未安装 PacketEvents 时退回旧行为：只有 `nausea` 完整、`darkness` 部分、`night_vision` 大部分，`blindness`/`slowness` 没有画面。
- 用一条只有慢速 `move`/`path`（时长 120+ tick、`ease-in-out`）的轨道检查旋转：视角应始终跟随、方向不应回抽；客户端视角 yaw 由 head 包按 1/256 圈量化，极慢的移动允许 1.40625° 的台阶感和约 2–3 tick 的滞后（旁观原版玩家同样如此）。`debug: true` 时启动日志应包含 `precise-sync=true`。
- 回归（path 起点抽动）：写一条 `path`，第一个路径点与 `track.start` 位置完全相同、只有 yaw 差零点几度。播放时起点应平滑转向第二个点，**不应左右快速抽动两三下**；把该首点删掉再播，画面应基本一致。`debug: true` 时 `camera trace` 的 `max/tick yaw` 应一直很小。原地转身（相邻路径点位置相同、yaw 差 90°）同样不应甩视角，但该点不占用时间轴——需要平滑原地转身时用 `move`。
- 假玩家镜头回归点：`wait` 信号后的 `move` 视角必须旋转。修复前只同步了身体朝向，客户端旁观相机读不到 head yaw，画面会一直看向进入镜头时的方向；修复后每个镜头 tick 检查量化 head yaw 并在变化时发 `EntityHeadLook`。快速转身（如 180°/20 tick）约在 150 ms 内到位。
- 用 `sway: strong`、`period: 160` 的 `hold` 停留 30 秒以上：镜头应一直平滑漂移+点头，地平线只上下轻微呼吸、**不应左右转**（预设档位 `yaw` 恒为 0），每 8 秒的周期边界也不应有顿挫。
- （已知，见 README“已知限制”）自定义 `yaw` 时，部分客户端会出现 0.2–1°、1 秒内回抽的左右抖动；预设档位没有 `yaw`，默认不会触发。服务端姿态流已实测连续（`debug: true` 时 `camera trace` 的 `max/tick yaw`、`entity yaw step`、`stale` 都正常），换 Marker 实体也一样；测试时不要把它当成配置或相位问题。
- `cut` 停留时长：写 `duration: 40` 的硬切应在步骤的第一个 tick 直接跳到 `to`（没有过渡帧），在新姿态停留 40 tick（`sway` 继续生效）后继续下一步；省略 `duration` 或写 `0` 时仍是纯瞬时硬切，下一步在同一 tick 立刻开始。硬切在两种镜头模式下都是客户端单帧切换：假玩家镜头下旧的 `CamRail...` 实体消失、换成新实体 id，盔甲架回退模式下画面也立即显示目标镜头、相机在约 4 tick 后重新锁定。把带 `duration` 的 `cut` 放在最后一步时，停留结束后应正常按 `exit-mode` 退出。
- 硬切补间检查：准备两个画面明显不同的镜头，中间放一个 `cut`。假玩家镜头下按 F3 观察客户端实体列表：硬切瞬间旧 `CamRail...` 实体消失并出现新实体 id，画面在一帧内切到目标镜头，没有滑动过渡；`blindness` 雾等效果应跨硬切保持。盔甲架回退模式下画面同样立即切到目标镜头，约 4 tick 后相机重新锁定；之后按住 W 走 2 秒，玩家本体不应移动（镜头仍锁定）。`debug: true` 时控制台会记录 `hard cut: ...` 行（假玩家实体重建、盔甲架解除/重新锁定）。
- 硬切后的继续：`cut` 后面的 `hold`/`move` 必须从 `to` 的目标姿态继续，不能从硬切前的姿态插值；随后正常退出并按 `exit-mode` 恢复。
- 旁观者传送拦截：至少两名玩家在线时，镜头锁定后按数字键 1-9 或使用旁观菜单传送到另一名玩家：会话不应结束（不出现 `EXTERNAL_TELEPORT`），动作栏出现锁定提示（默认 `camera.locked`：“过场动画中无法离开镜头。”，可在 `messages.yml` 修改），镜头被重新锁定；`/tp` 等其他原因的外部传送仍会结束会话。
- 用 `/camerarail advance <signal>` 推进 wait。
- 等待超时是否按 `exit-mode` 落点。
- 真实 `play` 中按 Shift 不会脱离（默认）；`/camerarail preview` 和编辑器预览中按 Shift 应结束预览，UI 预览回到原编辑器界面。
- 镜头中让其他插件或 `/tp` 传送自己。
- 镜头中退出并重连。
- 镜头中 `/stop`；确认客户端镜头在 1 tick 内回到玩家自身，假玩家实体和 tab 条目消失。
- 切换 `slowness` 等级，确认 FOV 平滑变化且不受 `sway` 影响。
- 预览编辑器，结束后是否回到编辑前的位置；从中间步骤预览时不应触发入场效果（失明/黑暗等）。
- 编辑后 `/camerarail reload` 是否仍然有效。
- 配置一个无效轨道，确认其他轨道仍加载，日志指出具体 YAML 路径。
- 播放一条 `exit-mode: animation-end` 的轨道，结束后**立刻按住 W 走 2 秒**：不应出现“前进一下就被瞬间拉回”的循环（旧版本在部分原版客户端上会出现，只有跳跃能解除）；退出点脚底应位于方块表面之上约 0.0001 格。`debug: true` 时控制台会多一行 `Camera exit: track=... destination=... body-before=... body-after=... teleported=... resynced=...`，可核对两次传送与落点。
- 在编辑器里对某个 `move` 步骤点“传送到此”：玩家应到达目标点且能立刻正常行走；该路径同样会做脚底校正，并在 1 tick 后重同步一次传送。
- 默认 `animation-end` 可能停在半空；需要安全落点时选择 `pre-entry` 或配置自定义 `exit`，不要依赖插件替你防摔。
- `/camerarail help` 与 `/camerarail edit help`：分组、对齐、不换行；点击命令填入聊天栏；悬停显示用法；`/camerarail help play` 等详情页包含用法、参数、权限和相关命令。
- 用只有 `camerarail.command`（或再给 `play`）的账号执行 `/camerarail help`，确认看不到无权限的子命令，补全同样只出现可用命令。

## Dialog 编辑器检查清单

- `/camerarail edit` 打开轨道列表；选择已有轨道、创建新轨道、分页与刷新正常。
- 关窗飞行后再 `/camerarail edit`，回到上次的轨道概览/步骤页/步骤表单和页码，而不是总回轨道概览。
- 轨道概览的步骤数、总时长、摇摆、跟随与实际 YAML 一致。
- 添加 7 种步骤；每种表单的默认值合理，保存后步骤列表和 `tracks.yml` 同步更新。
- 步骤表单标题带序号；保存后可在原表单继续改，“保存并返回”回步骤列表。
- “保存”只提交编辑框里的数值；预览、上一步/下一步、上移/下移、复制会先保存编辑框里的输入再执行：上一步/下一步成功后在新表单顶部提示“已保存刚才的修改.”，上移/下移/复制显示各自的“已移动步骤./已复制步骤.”。无效输入会留在原表单并提示错误，此时 Esc/“保存并关闭”也关不掉，需要先修正或点“取消”。窗口底部的“保存并关闭”按钮和 Esc 也会先保存再关闭（有效输入已写入，重新打开表单能看到；新建步骤/入场效果的“关闭”仍是取消，不会创建）；只有“取消”和“删除”会明确丢弃未保存的输入。列表/选择器点“返回”只导航，不丢当前输入；信号/效果选择器的底栏返回与 Esc 会带着已选值回到原表单。
- 坐标表单点“传送到此”后窗口关闭、人物到保存位置、朝向正确；“传送到此”会先保存编辑框里的坐标；起点/步骤/路径点按眼睛换算脚部，退出点按脚部直接传送；`/camerarail edit` 回到原表单。
- 移动/硬切表单改坐标、时长、缓动、摇摆；硬切表单另有“硬切后停留”时长（tick，`0` = 瞬时）且没有缓动；保存后步骤列表的类型名仍是 `硬切 (cut)`，停留非 0 时显示如 `硬切 40t · 0.0s`、悬停提示包含 `停留: 40t`；点“取当前视角”立即捕获点击时的视角、马上保存为这一步的目标并重开表单（表单同步显示新坐标和“已保存步骤.”），没有倒计时。播放时硬切在两种镜头模式下都是客户端单帧切换；停留为 `0` 时下一步同一 tick 立刻开始，停留非 0 时在新姿态保持对应 tick 数后继续。
- 缓动/摇摆选项显示中文名和行为说明；等待表单的“已有信号 >”列出所有轨道出现过的信号并可回填；效果表单的“常用效果 >”可回填常用 id，自定义 id 仍可用。
- 路径点列表：追加当前视角、逐点编辑、插入、删除、上移/下移；路径点表单的 `<- 上一步`/`下一步 ->` 可在相邻路径点间切换并会先保存编辑框里的坐标（不改变路径点顺序）；表单里的“取当前视角”立即把点击时的视角写进该路径点并提示“已取当前视角并保存.”，“插入当前视角”会先保存当前点再插入；路径点数量上限生效。
- 重复点防御：创建 `path` 步骤后**原地**“追加当前视角”（与步骤起点位置差 < 0.01 格，对话框切换期间视角被轻微晃动也算）会被判为与上一个点同位置而拒绝，不会新增重合点；先移动/转动到别的位置再追加，会用真实路径点替换创建时的占位点（点数仍为 1）。与相邻路径点位置相同的插入同样被拒绝；`limits.max-path-points: 1` 时替换占位点仍应成功，只有真正增加点数才会报上限。`/camerarail edit here`（命令路径，对 `path` 是追加点）同样不应写出与起点重合的点。
- 轨道设置：显示名称、说明、摇摆预设/周期/种子、跟随；自定义摇摆振幅不会被意外覆盖（选择 `custom`）。
- 用一条最后一点用“取当前视角”保存的轨道播放；第三人称确认 gm3 相机在盔甲架眼高而不是底部，结束后玩家视角不应从脚部升到头部。
- 退出点：默认“动画结束位置”，也可选“进入前的位置”或“自定义位置”；只有自定义才填脚部坐标。默认模式最终 x/z/yaw/pitch 应与保存点一致（不使用含 sway 的最后一帧），结束瞬间玩家身体应停在镜头最后位置，不应先回到旧身体位置再传送。
- YAML：不写 `exit-mode` 且不写 `exit` 时按 `animation-end`；旧文件写了 `exit` 但没写模式时仍按 `custom`。
- 入场效果：添加、编辑、删除；只能添加，等级/时长生效。真实播放和从轨道起点开始的预览会施加；从中间步骤开始的预览（含“预览此步”）不会施加。
- 步骤上移/下移/复制/删除、撤销；删除全部步骤后列表不崩。
- “预览此步”只播放选中的这一步，播到该步骤结束就停；之前的效果/标题不回放，从第 2 步起也不施加入场效果；预览结束回到原步骤表单，而不是步骤列表。
- 从步骤列表/轨道概览启动的预览，结束后分别回到步骤列表/轨道概览；停止预览后能回到编辑器。
- 两个管理员同时编辑同一轨道时，第二个被拒绝且提示清楚。
- Paper 控制台没有 `showDialog`/Dialog 序列化异常；Folia 下 UI 操作都在玩家调度器上执行。
- 保存失败（例如 `tracks.yml` 有无法解析的段落）时，编辑器显示保存失败，且不会覆盖手写段落。

## Folia 26.1.2

```text
java -jar folia-26.1.2-8.jar nogui
```

重点观察：

- 启动日志中出现 `CameraRail enabled`。
- 镜头跨 chunk/region 时没有 `Must use teleportAsync while in region threading` 异常；镜头实体在 Folia 下统一走 `teleportAsync`。
- 会话结束后玩家恢复为原游戏模式。
- 插件禁用/重载时没有跨 region 访问异常。

本地 Folia 使用 `teleportAsync` 队列更新镜头实体；如果服务端 region 负载很高，镜头更新频率可能略低于 20 tps，但客户端仍会在已有位置间插值。若服务器有非常严格的移动反作弊，建议对旁观者过场单独放行或降低跟踪强度。
