# 实现说明

本文面向维护者与排障，记录镜头同步、假玩家镜头和协议层面的细节。普通使用不需要阅读；
功能、配置和用户可见的限制见 [README](../README.md)。

## 镜头锚点

服务端锚点是一个隐藏的普通（非 marker）盔甲架，玩家以旁观者身份附身到它；服务端每 tick
更新实体姿态，客户端负责帧间插值，不会每 tick 传送玩家本体。

镜头跨区块时，玩家实体跟随移动以保证区块加载；可以用轨道字段 `follow: false` 或
`session.follow-camera: false` 关闭。

镜头锁定期间，旁观者按数字键 1-9 或使用旁观菜单传送到其他玩家会被服务端处理为
`TeleportCause.SPECTATE` 的本体传送；插件会取消该传送并重新锁定镜头，动作栏提示镜头仍在
锁定中，不再把它当作外部传送而提前结束播放。其他原因的外部传送仍按原策略结束会话。

## 假玩家镜头（PacketEvents）

安装 PacketEvents 时，插件还会生成一个只存在于该客户端的 PLAYER 实体，并把发往盔甲架的
`Set Camera` 包改写成假玩家 id。服务端 `getSpectatorTarget()` 仍是盔甲架，区块票据、每 tick
吸附和跨区块跟随都不变；假玩家负责客户端的 Camera、FOV 和按镜头实体读取的效果。客户端
旁观相机的视角取的是实体的 head yaw，而位置包只同步身体朝向，所以插件还要在量化后的
head yaw 变化时逐 tick 下发 `EntityHeadLook`（假玩家没有服务端实体，原版实体跟踪不会替它发
这个包）；否则画面 yaw 会停在进入镜头时的角度。

假玩家的位置、pitch 和身体朝向每 3 tick 从同一份镜头姿态同步一次（与精确实体跟踪的节奏
一致）；视角 yaw 的 head 同步则是每个镜头 tick 检查、量化值变化才发包。插件直接计算并下发
假玩家的 `MOVEMENT_SPEED` 属性：客户端不会给客户端侧实体应用药水效果的属性修正，所以
`slowness`/`speed` 的 FOV 必须走属性包。PacketEvents 不可用或假玩家生成失败时，自动保持原
盔甲架镜头。

假玩家镜头激活时**不会**向该客户端发送盔甲架实体：盔甲架只作为服务端 spectator target
存在。只有假玩家不可用、回退到盔甲架镜头时才对玩家调用 `showEntity`。旁观者客户端能看见
invisible 实体，若把盔甲架发给客户端，会在镜头位置出现半透明模型。

第 2、10 tick 会重发一次 `Set Camera`，第 45 tick 检查客户端是否仍在发送自身移动包，未锁定
时在控制台输出警告。

## 硬切（cut）

`cut` 硬切不走上面的客户端帧间插值，在两种镜头模式下都是单帧切换：

- 假玩家镜头下，插件在硬切时销毁旧的客户端相机实体，按目标姿态重建假玩家实体，并在同一
  tick 把 `Set Camera` 指向新实体（切换相机目标本身是瞬时的，新实体没有位置/头部插值状态），
  同时把 `MOVEMENT_SPEED` 属性（`slowness`/`speed` 的 FOV）和 `blindness`/`darkness`/
  `night_vision` 等镜头可见效果重新下发到新实体。
- 盔甲架回退模式下，客户端会插值镜头盔甲架的位置和朝向（约 3 tick），直接传送会滑过去，
  所以插件先解除相机、把玩家本体瞬移到硬切姿态（解除期间客户端相机就是玩家本体，画面已经
  立即切到目标镜头），再移动盔甲架，约 4 tick 后等客户端追上再重新锁定相机。

两种模式都没有补间；`cut` 的 `duration` 只控制跳转后的停留。回退模式下每次硬切会比轨道
时间轴多约 4 tick。

## 路径插值

`path` 的采样是“位置 + 旋转”两条独立的向心 Catmull-Rom 曲线，共用同一份关键帧（步骤开始姿态 +
`points`）：

- 位置按三维距离做 centripetal 参数化，再按采样折线的累计弧长重新参数化，因此 `duration` 分配到的是
  行进距离，整体速度均匀。
- yaw/pitch 按**角度距离**（`hypot(最短 yaw 差, pitch 差)`）做 centripetal 参数化。早先版本让旋转和位置
  共用位置节点间距：当相邻关键帧位置重合或极近（镜头原地转身，或轨道的 `start` 又被写成第一个路径点），
  位置节点间距会塌到 `EPSILON = 1e-4`，而 yaw 仍可能变化几十度，旋转样条从那一段外推，几个 tick 内甩出
  数百度的过冲——屏幕上就是 path 起点“左右快速抽动两三下”。改用角距离后旋转节点间距只取决于视角变化，
  不再受位置重合影响；`PathCurveTest` 用报告中的真实轨迹（含 60 tick 逐 tick 播放）、原地 90° 转身、
  1 mm 间距和纯旋转路径几个用例锁定该回归。
- 因为进度按弧长分配，**与上一点位置重合的路径点不占用时间轴**：它只会让视线在该点“瞬间”转到目标角度。
  需要原地转身请用 `move`（`to` 写同一位置、不同 yaw）或 `cut`，不要靠在 `path` 里放重复点。
- 编辑器在追加/插入路径点时还会拦截这类退化点：捕获姿态与相邻路径点（或新建步骤的占位点）位置差
  < 0.01 格就替换占位点或拒绝写入。判断只看位置而不看视角：同一位置的第二个点不产生移动，原地转身应
  用 `move`，这样也能挡住“对话框切换期间视角被晃了一下”写出的重复点（实现见 `PathPoints`）。无状态
  编辑命令与编辑工具走同一套 `PathPoints` 校验，不会绕过重复点与上限检查。
- 替换占位点不算新增点数：`limits.max-path-points` 配置为 1 时，创建路径后的第一次追加仍会用真实路径点替换占位点（点数保持 1）；只有真正增加点数才会被上限拒绝。
- 例外：整条路径的所有关键帧位置都相同时，总弧长为 0，`at()` 会退化为按采样均匀分配，视角是平滑转过去的；上面“重合点不占用时间轴、瞬间转向”的描述只适用于有位移的路径。

## Dialog 编辑器的保存语义

Dialog 里的按钮都是一次性的 `customClick` 回调，点完窗口就换掉，所以草稿不能留在表单里：

- 每个表单只有一个写入口（`PendingSave`）。显式“保存”= 走这个写入口再执行该表单的收尾：步骤/路径点
  表单留在原表单，轨道设置回概览，入场效果回列表，起点/退出点回上一屏；“保存并返回”额外回步骤列表。
- 其他会离开或替换表单的按钮（预览、上一步/下一步、上移/下移、复制、传送到此、插入当前视角）先读取
  当前输入并保存，再执行动作。输入不合法时留在原表单显示错误、动作不执行——这样不会出现“我改了但
  没存上”。因此无效输入时 Esc/“保存并关闭”也会被挡下，需要先修正或点“取消”。
- “取当前视角/取当前脚下位置”捕获点击时的姿态后立即写入；捕获失败（预览中、不在轨道世界等）会带着
  表单里已输入的值重新打开原表单并提示原因，而不是跳到轨道概览。
- 没改动过的表单（输入框文本与打开时完全一致且没有暂存值）直接跳过写盘，避免每次翻页都产生无意义的
  撤销记录和 `tracks.yml` 重写；新建步骤总是会写入。退出点还没有自定义姿态时，表单里显示的是“当前
  脚部位置”预览：显式保存会把它写成自定义退出点，但直接关闭不会。
- 窗口底部的“保存并关闭”和 Esc 走 Dialog 的 `exit_action`：客户端提交退出动作时会带上当前输入值，
  关闭前先保存；响应里缺字段时按表单打开时的值补齐，完全没有字段值（例如客户端没提交响应）时直接
  关闭，不会卡住玩家。新建表单（添加步骤、添加入场效果、新建轨道）例外：关闭仍是取消，不会因为按
  Esc 就创建一个新对象。
- 选择器（已有信号、常用效果）只暂存选择；它们的底栏返回与 Esc 也回到原表单并带上暂存值。
- 只有“取消”和“删除”会丢掉未保存的输入。
- 带 `afterAction(CLOSE)` 的 Dialog 会在 click 回调返回后才关闭；如果回调在同一 tick 打开下一屏，新
  Dialog 会被随后的关闭动作吞掉（表现为点分页/进入后直接回到游戏）。因此列表分页、返回、表单导航等
  跨屏按钮统一延迟 2 tick 再 `showDialog`。另外多按钮网格单列超过约 8 行时会与底栏重叠，列表类界面
  （轨道、步骤、路径点、入场效果、工具选择器、信号/效果选择器）使用两列布局。

实现：`PendingSave` + `saveAction`（动作前保存）/`saveExplicitly`（显式保存）两种包装；各表单的
`applyXxx` 负责校验/写入并返回 `SaveOutcome`（`SAVED`/`UNCHANGED`/`FAILED`），`FAILED` 表示表单
已带着错误重新打开、调用方不得继续导航。

## 无状态编辑命令与编辑工具

### 目标层级：与 Dialog 一一对应

命令编辑不再依赖编辑会话里隐藏的“选中步骤”：旧流程先 `edit select <索引>` 再执行 `edit here`/`edit duration`，
命令的结果取决于此前的命令历史，选错步骤、在错误的轨道上执行或另一名玩家同时编辑都会把修改写到意外位置。
现在每条命令都自带目标 `EditTarget`，写作 `<轨道>[:<步骤>[:<路径点>]]`；命令层是 1-based，`EditTarget` 内部
0-based（`NO_STEP`/`NO_POINT` 为 `-1`）。UI 入口仍是根命令 `/camerarail editor [<轨道>|close]`：不带参数
打开轨道列表，带轨道 id 直接打开该轨道，`close` 关闭编辑会话并释放轨道。三层目标的动作集合刻意复刻
Dialog 的页面结构：

- **轨道层**（Dialog 的轨道概览）：`display`/`description`/`start`/`exit`/`exit-mode`/`sway`/`follow`
  对应轨道设置、起点与退出点；`entry` 对应入场效果列表；`list`/`add <类型>` 对应步骤列表与“添加步骤”；
  `preview` 是开关（预览中再次执行即停止），`stop` 在轨道/步骤/路径点三层都能停止预览，`undo`/`save`
  对应概览和步骤列表上的撤销与保存。
- **步骤层**（Dialog 的步骤表单）：动作按 `Step` 类型分派，只有该类型表单里存在的字段才会被接受。例如
  `title` 步骤只有 `title`/`subtitle`/`fade`，`wait` 步骤只有 `signal`/`timeout`/`sway`；`duration` 属于
  move/path/hold/wait/cut，`easing` 只有 move/path，`sway` 属于五种带摇摆的步骤，写错类型时提示该步骤
  实际可用的动作。
  `here`/`pos` 是 move/cut 的“取当前视角/显式坐标”，对 path 步骤会被拒绝并列出该步骤实际可用的动作
  （`duration`/`easing`/`sway`/`point` 等）。
- **路径点层**（Dialog 的路径点表单）：`here`/`pos`/`remove`/`up`/`down`/`insert`/`stop`，与路径点表单的
  “取当前视角/显式坐标/删除/上移/下移/插入当前视角”对应；`stop` 用于结束当前预览。
- **共有结构动作**：`remove`/`duplicate`/`up`/`down`/`preview`/`stop` 对所有步骤可用，对应步骤表单里的
  同名按钮；`preview` 同样是开关，`stop` 也接受路径点目标用于结束预览。

`EditTarget.parse` 是严格的：先把轨道 id 转成小写，再按 `[a-z0-9][a-z0-9_-]{0,63}` 校验（大写输入会被
接受并规范化），索引必须是正整数；解析失败返回 `null`，命令层显示用法而不是猜一个目标。轨道 id 统一
小写，与 `TrackRepository` 的大小写不敏感查找一致。
`edit` 后面第一个参数固定是目标参数，因此轨道 id 与根命令 `help`/`tools` 同名时也不需要转义；UI 入口在
根命令（`/camerarail editor [<轨道>|close]`、`/camerarail tools`、`/camerarail help edit`），同一个位置不会
既当子命令又当参数。`editor close` 是关闭会话的保留写法，id 为 `close` 的轨道请从编辑器的轨道列表打开。

- `add <类型>` 与 Dialog 的“添加步骤”一致，追加到轨道末尾并使用 Dialog 的默认值（之后再用步骤层字段改）；
  也接受便捷写法：`add move|path|hold [时长]`、`add cut [停留]`、`add wait [信号] [超时]`（省略信号时
  自动生成唯一名 `wait`/`wait2`…）、`add effect [add|remove] [效果] [等级] [时长]`（省略动作视为 `add`，
  可直接 `add effect blindness`）、`add title [标题[|副标题]]` 或
  `add title <淡入> <停留> <淡出> <标题>[|<副标题>]`。需要调整顺序时用步骤层 `up`/`down`，不提供任意位置
  插入。
- 轨道级 `list` 列出步骤，每行携带该步骤目标的点击事件，点击即填入 `<轨道>:<步骤>`，“先看再改”因此不需要
  额外的选中状态；路径步骤的 `point list` 同理。
- 编辑命令的参数解析集中在 `EditArguments`（纯函数），消息与提示集中在 `EditFeedback`，权限检查在
  `CameraCommand`，目标定位与动作实现由 `EditCommands` 路由到各层处理器；步骤数、总时长、`wait` 信号
  唯一、路径点上限、MiniMessage 严格解析、轨道 id 与世界不可变等结构性校验集中在纯 Java 的 `TrackRules`
  （id 规则来自 `TrackId`，strict MiniMessage 来自 `MiniMessages`），命令侧与 Dialog 侧共用同一套规则，
  只在错误文案包装上各自处理：命令层越界时用 `messages.yml` 文案，`TrackRules`/`EditService` 返回内部错误
  文本由调用方展示。

### 命令层模块

`edit` 命令族按职责拆成一组只做命令层工作的类，彼此通过小的接口衔接，行为与拆分前一致：

- `CameraCommand`：根命令入口。`onCommand` 只做根分发（`help`/`list`/`info`/`play`/`stop`/`advance`/
  `reload`/`create`/`delete`/`editor`/`tools`/`edit`）；`edit()` 负责权限、玩家检查与目标解析，然后构造
  这次调用共享的 `EditFeedback` 与 `EditContext`；`onTabComplete` 直接委托 `EditCompletions.complete`。
- `EditCommands`：编辑命令的路由器。按目标形状把动作分给对应处理器（`Kind.TRACK`/`STEP`/`POINT`），
  唯一特例是轨道层的 `add <类型>`，由 `AddStepCommands` 处理（`Kind.ADD`）；处理器之间不互相调用。
- `TrackEditCommands` / `StepEditCommands` / `PointEditCommands`：分别是轨道层、步骤层、路径点层的动作实现
  （轨道设置与 `entry`、步骤字段与结构动作、路径点操作）。它们只通过 `EditContext` 取依赖，写入统一交给
  `EditService`。
- `AddStepCommands`：轨道层 `add` 的七种步骤类型与便捷写法；参数解释（时长边界、可省略的 effect 动作、
  `title` 两种形式、`wait` 唯一信号名）在纯 Java 的 `AddStepArguments` 里并可单测；除 `wait` 省略信号时
  自动生成唯一名外，其余默认值与 Dialog 的“添加步骤”一致。
- `EditEffects`：把效果参数解析成 `EffectSpec`（注册表解析 + ADD 的等级/时长 clamp）。它依赖 Bukkit 注册表，
  因此不做纯函数单测；未知效果的提示仍由 `EditFeedback.invalidEffect` 发出。
- `EditFeedback`：`edit` 命令族与 `/camerarail info` 状态视图的命令侧输出——状态与列表渲染、可点击动作行
  （`actionLines`/`sendActions`）、摘要与类型名，以及 `applied`/`invalidValue`/`invalidEffect`/`requirePose`/
  `requireInt` 这些“校验并提示”的入口。它由 `edit()` 每次调用构造，保证点击填入使用玩家输入的 alias。
- `EditCompletions`：全部 Tab 补全，唯一入口是 `complete(sender, args, alias)`；内部按根命令、`edit` 目标与
  `help edit` 主题分开处理。
- `EditArguments`：纯参数解析（整数/浮点/布尔/坐标/序号/动作 token），不接触 sender 或消息。
- `AddStepArguments`：`add <类型>` 尾部的纯参数解释（`duration`/`effectChoice`/`title`/`nextWaitSignal`），
  非法 token 交给调用方用 `EditFeedback.invalidValue` 报给玩家。
- `EditActions`：动作词汇——`STRUCTURE_ACTIONS`、`TRACK_ACTIONS`、`POINT_ACTIONS`、各步骤类型的字段动作与
  类型名，供错误提示与 Tab 补全共用（帮助的条目表在 `CommandHelp`，不依赖它）。
- `StepFields`：步骤字段的不可变 copy-with 操作（`withDuration`/`withEasing`/`withSway`）与步内查询
  （`targetPose`/`pathStep`/`pathCount`/`swayName`/`easingName`）。
- `EditPreview`：`preview` 开关与 `stop` 的统一入口；轨道/步骤处理器用它做 toggle，路径点处理器用它 stop。
- `EditContext`：一次编辑调用共享的最小依赖（`service`/`repository`/`config`/`messages`/`feedback`/`label`）；
  不把无关依赖塞进来，处理器需要其它东西时用参数或本地计算。

调用链固定为 `onCommand → edit() → EditCommands.handle → 各层处理器`（轨道层 `add` 直接进
`AddStepCommands`），补全走 `onTabComplete → EditCompletions.complete`。这次拆分只移动代码、收紧了依赖边界，
不改变命令语法、文案、点击填入或补全结果；回归由 `CommandHelpTest`、`ActionHintTest`、`EditFeedbackTest`、
`EditCompletionsTest`、`EditArgumentsTest`、`AddStepArgumentsTest`、`EditActionsTest`、`StepFieldsTest`、
`EditCommandsTest` 覆盖。

### 提示、补全与帮助主题

- **可点击动作**：`EditFeedback.actionLines` 把动作行拆成 head、click-to-fill 动作片段和 tail；
  `EditFeedback.sendActions` 负责加前缀并按聊天栏宽度换行（预留前缀与 4 px）。每个片段的点击命令是
  `/label edit <目标> <动作> `，tail 固定为「详情见 `/label help edit <动作>`」（`editor.actions-hint-tail`）。
  使用方：步骤/路径点状态（`statusStep`/`statusPoint`，head `editor.actions-hint`）、写错动作（`wrongAction`，
  head `editor.wrong-action-head`）、`edit <轨道>` 状态的两行（`statusTrack`，head `editor.track-hint-head`
  「步骤与预览」与 `editor.track-settings-head`「轨道设置」，动作集合分别是 list/add/preview/stop/undo/save
  与 display/description/start/exit/exit-mode/sway/follow/entry）、`list` 的头部（`listSteps`，add/preview）。
  编辑视图不输出 `/play` 链接：`statusTrack` 走 `sendTrackFields`，只有 `/camerarail info` 的 `infoTrack`
  才带 `track.info-preview`。
- **成功提示带目标**：命令层给消息传 `track` 或 `target` 占位符——轨道级 display/description/exit-mode/
  sway（含周期）/follow 等回显轨道 id，步骤级 duration/easing/step-sway/signal/timeout/title/subtitle/
  fade/effect 等回显 `<轨道:步骤>`。
- **补全规则**：全部补全在 `EditCompletions`，入口是 `complete(sender, args, alias)`。`editCompletion` 在
  第一个参数精确命中轨道 id 时同时给出 `id:`（可继续补步骤号、路径点号）；路径点目标只有在路径步骤存在
  且点号有效时才补全动作，否则返回空；`advanceSuggestions` 总是包含 `*`；`editorArguments` 给轨道 id 加上
  `close`；`help edit` 补全 `CommandHelp.names(Scope.EDITOR)`。
- **帮助主题**：`EDITOR_TOPICS`（duration/easing/signal/timeout/subtitle/fade）只出现在 `help edit <字段>`
  的详情页与补全里，不占 overview 行；`Entry.usage` 让 move/path/hold/wait/cut 这些“类型摘要行”的详情用法
  渲染成 `<here|pos|…>` 而不是一条伪命令；`description [说明]`、`title`/`subtitle [文本]` 的方括号表示可省略
  （省略即清除），`here`/`pos` 归入 `Group.STEP_OR_POINT`（帮助里显示「步骤/路径点」）。
- 以上 head/tail/usage 文案都在 `messages.yml`；本次清理删除了 41 个不再被代码引用的旧键，命令层引用的键
  在 YAML 中全部存在。

### 命令编辑与 Dialog 编辑器解耦

`EditService`（命令与物品工具）和 `EditorManager`（Dialog 会话）是两套独立的编辑入口，只通过
`EditorLocks` 共享“哪条轨道正被 Dialog 会话占用”这一件事：

- **Dialog 编辑器**只维护会话草稿、会话撤销历史（`EditorSession`）和 UI 状态。打开轨道时用
  `EditorLocks.tryLock(trackId, playerId)`（`ConcurrentHashMap.putIfAbsent`，同一条轨道只会有一个会话
  拿到锁），拿到锁之后才在 `TrackRepository.compute` 里读草稿；关闭会话/退出/删除时 unlock。每次改动仍
  立即写回 `TrackRepository`，但撤销栈是会话自己的。
- **命令与物品工具**不读写会话草稿：所有修改都走 `TrackRepository.compute(id, changer)`，读当前值、改动、
  校验、写回在仓库的单把写锁内完成，因此来自不同 Folia 区域线程的编辑会依次应用，不会互相覆盖。
  撤销记录是 `UndoHistory`（每玩家 30 条，条目保存 before+after 快照；`CameraTrack` 不可变，无需拷贝）。
- **冲突处理**：命令侧对 Dialog 占用的检查放在 `compute` 的写锁内，和它保护的写入是同一个原子步骤；
  Dialog 先 `tryLock` 再读草稿，所以命令写入要么发生在草稿读取之前（草稿看得到），要么被锁检查拒绝。
  轨道被 Dialog 会话占用就拒绝（包括自己的会话，提示先关闭编辑器会话，可用 `/camerarail editor close`
  释放），避免命令悄悄覆盖会话草稿、反过来 Dialog 的保存也不会覆盖命令的写入。
- **仓库快照**：`TrackRepository` 只有一把写锁（`synchronized (writeLock)` 覆盖 compute/delete 与 reload
  的整表替换），轨道表用不可变 `Map` 快照经 volatile 字段发布，所以 `all()`/`get()` 在锁外读取，读者看到
  的一定是某个完整版本。
- **reload 守卫**：`CameraRailPlugin.reloadEverything()` 自己检查 `editors.hasEditors()`，有 Dialog 会话时
  记一条 warning 并返回 `-1`（不替换轨道表、不清撤销历史）；`/camerarail reload` 与 `CameraService.reload()`
  走同一入口，因此都安全。`tracks.yml` 存在个别解析错误时也返回 `-1`，但其余轨道仍会重载、失败轨道保留
  上一版本，撤销历史随这次非致命重载一起清空。
- 结构校验只有一处实现：`TrackRules.validate(base, updated, cfg)` 覆盖步骤数、总时长、`wait` 信号唯一、
  路径点上限、title/subtitle 的 strict MiniMessage，以及普通编辑必须保持 id 与世界不变（改 id 走独立的
  `TrackRepository.rename` 事务，见「重命名」一节）；命令侧与 Dialog 侧都调用它，id 正则与 strict
  MiniMessage 也分别只在 `TrackId` 与 `MiniMessages` 里定义一次。
- **取景与坐标**：`captureStart`/`captureExit`/`capturePose` 取玩家当前视角或脚下位置，要求玩家与轨道在
  同一世界（预览中也会被拒绝）；`setStartPose`、`setExitPose`、`setPose`（move/cut 目标或路径点）、
  `setPathPointPose` 写入显式坐标，不要求同世界；`setPose` 对 path 步骤报错，路径点走 `setPathPointPose`。
- **入场效果**：`addEntryEffect`/`setEntryEffect`/`removeEntryEffect` 只改 `entryEffects` 列表，序号由命令层
  转成 0-based；效果 action 固定为 ADD，与 Dialog 的入场效果表单一致。
- **预览**：命令/工具直接调用 `CameraEngine.playPreview(player, trackId, start)`（预览到轨道末尾），不触碰
  Dialog 会话的 `previewing` 标记；`edit <轨道> preview` 是开关，预览中再次执行（或在任意层级用 `stop`）
  即停止，预览始终允许 Shift 退出。Dialog 预览仍走 `EditorManager.startPreview`，由 `CameraTrackEndEvent`
  重开界面。两边共用引擎的“预览结束回原位置”和 Shift 退出。

### 命令撤销栈

- `EditService` 按玩家维护撤销历史 `UndoHistory`（`Deque<Entry>`，每条记录轨道 id 和修改前/后的
  `CameraTrack` 快照），整个栈上限 30 条；`CameraTrack` 不可变，快照不需要深拷贝。
- 每次成功的编辑（新建轨道不算撤销）都在 `TrackRepository.compute` 内压入 before+after；`/camerarail edit <轨道> undo` 在同一个
  compute 周期里把记录的 after 与仓库当前值比较：相等才恢复 before 并移除该条，否则说明轨道被其他玩家、
  Dialog 会话、reload 或手改的 `tracks.yml` 修改过，撤销被拒绝并提示“轨道在撤销后被修改过，无法撤销.”，
  不会覆盖别人的改动。没有记录时提示“没有可以撤销的修改”。
- 删除轨道时仓库的删除钩子（在写锁外）让每条历史丢弃该轨道的条目：删除后立刻 undo 会因为轨道已不存在而
  提示“找不到轨道 <id>.”；之后若用同名 id 新建轨道，旧的撤销记录也不会套到新轨道上（undo 提示“没有
  可以撤销的修改.”）。被拒绝的过期条目仍留在栈里，后续再撤销时重新比对。
- reload 只要 `tracks.reload()` 不是致命失败（文件本身可读），撤销历史就会在仓库写锁内清空——包括“个别轨道解析失败、其余仍重载”的情况；文件完全无法读取（fatal）或被 Dialog 守卫拒绝时历史保留。
  撤销历史只存在内存里，玩家退出（`PlayerQuitEvent`）时清空，不跨重启持久化。
- Dialog 会话内的撤销是另一条独立的历史（`EditorSession`）。

### 重命名

轨道 id 不只是名字：它是 `tracks.yml` 的键、命令目标、`CameraService` 的参数、编辑锁的键，也是默认摇摆
种子的来源。因此重命名是一条“换身份”的事务，而不是普通字段编辑；两条入口最终都调用
`TrackRepository.rename(oldId, newId, precondition)`：

- 仓储在写锁内依次检查源轨道、目标 id 是否可用，再执行可选的 `precondition`（用于拒绝正被 Dialog 会话
  占用的轨道），然后一次性删除旧键、写入新键。监听器在锁外收到 `(oldId, newId)`。
- `EditService.rename` 是命令入口（动作名 `id`，与 `display` 区分）：要求 `camerarail.manage`，二次确认；
  锁检查与播放/预览检查通过后，仓储 precondition 再兜住命令检查与写入之间的并发窗口。旧 id 的
  `UndoHistory` 条目由仓储监听器清掉，避免之后 undo 复活旧 id。
- `EditorManager.rename` 是 Dialog 入口：轨道设置表单里「轨道 id」和「显示名称」并排编辑，保存时先执行
  重命名（会话先 `tryLock(newId)`，改键后换成新轨道草稿、释放旧锁保留新锁、清空会话撤销栈），再把其余
  字段写回新 id 的草稿。显示名称字段没被改动时，默认显示名跟随新 id；改动过则按输入值写入。Esc /
  「保存并关闭」走同一条保存路径。
- `CameraTrack.withId` 只换身份：显示名称原本等于旧 id 时跟随新 id，自定义显示名称和 `sway`（含 seed）
  保持不变。`TrackRepository.onTrackRenamed` 在插件启动时同时接到 `EditService`（清撤销栈）和
  `EditorTools`（在玩家调度器上把工具选择改到新 id）。
- 正在播放/预览的轨道拒绝重命名：`Session` 快照、事件和恢复文件都记录着开始时的 id，改名后无法一致。
  旧 id 的聊天点击链接与外部调用者不会自动更新，因此成功提示会明确说明。

### 编辑工具

`EditorTools` 提供两件物品工具，箱子 GUI 由 `ToolSelectorUI` 绘制；入口是 `/camerarail tools` 和 Dialog
编辑器轨道列表/概览里的“编辑工具”按钮，二者都要求 `camerarail.edit` 权限。

- **物品标记**：工具是带 `PersistentDataContainer` 标记键的普通木棒/末影之眼（键在本插件命名空间下），按标记
  区分类型，不依赖 display name 或 lore，玩家改名或复制同类物品不会被误认。
- **选择状态**：每名玩家的当前目标（取景工具：轨道 + 起点/退出点/路径步骤；预览工具：轨道 + 起始步骤）只
  保存在内存 map 里，不写回物品；重新登录后需要重新选择目标，工具物品本身留在背包。
- **ActionBar 循环**：取得工具后为玩家保留一个 10 tick 的 `EntityScheduler` 循环，手持工具时刷新
  ActionBar 显示当前目标与下一步提示（背包/箱子暂存时循环不取消，重新持有后立即点亮）；退出后取消。
  写入成功、重复路径点、超出 `limits.max-path-points`、不在轨道世界、预览中等结果都走 ActionBar，
  不刷聊天栏。
- **操作**：取景工具右键调用 `EditService.captureStart`/`captureExit`/`appendPathPoint`；预览工具右键调用
  `EditService.preview`，与命令编辑共用同一套校验、保存与撤销路径。预览开始后玩家被切换为旁观者，
  客户端不会再发送物品交互包，因此“预览中右键停止”不可达；退出预览用 Shift，或执行
  `/camerarail edit <轨道> preview`（开关，再执行一次即停止）与任意层级 `stop`（预览会话恒允许 Shift 退出），
  ActionBar 也这样提示。`stopPreview` 保留给命令与事件真正到达时使用。
- **交互去重**：右键经 `PlayerInteractEvent` 处理并做去重，同一次点击产生的重复事件只处理一次；Shift+右键
  优先打开目标选择器，避免一次点击既写点又切换预览。
- **Folia**：工具选择状态与预览都是玩家本地状态，工具操作统一在 `player.getScheduler()` 上执行，不跨区域
  直接操作实体。

## 定位条隐藏

播放期间通过给玩家加一个 transient 的 `waypoint_receive_range` 乘算修正符
（`MULTIPLY_SCALAR_1 = -1`）隐藏原版定位条（locator bar）：相机锁定后隐藏，移除修正符即恢复
（最多 1 tick 生效）。只增删本插件命名空间下的修正符，不读取/写回属性基础值，不碰其他插件的
modifier，因此第三方对该属性的修改不会被覆盖；修正符不落盘，崩溃/重登不会残留。镜头 tick
每 20 tick 在玩家调度器上复查一次（第三方清 modifiers、`/camerarail reload` 切换配置都会被
纠正），所有结束/退出/恢复路径都会移除。实现见 `LocatorBarHider`，背景见
[locator-bar.md](locator-bar.md)。

## 精确同步与量化

服务端镜头实体（含回退模式的盔甲架）使用精确位置同步模式（通过反射设置
`requiresPrecisePosition`），让位置、pitch 和身体朝向走 float 的 `EntityPositionSync`；但客户端
旁观相机的视角 yaw 来自 head yaw，协议只提供 1 字节（1/256 圈 = 1.40625°）的 `RotateHead`
包并由客户端做 3 tick 插值，所以慢速旋转仍可能出现亚度级台阶与约 2–3 tick 的跟随滞后——
这与旁观原版玩家一致，不是本插件能消除的量化。字段不可用时自动退回默认的实体跟踪。

## 退出与传送重同步

退出时先解除旁观/相机并恢复游戏模式、飞行状态和药水效果，清空残余速度后再把玩家传送到
落点，并在 1 tick 后对同一落点再重同步一次传送：这样既避免客户端在相机锁定期间收到传送
造成的位置确认相位错乱，也保证落点脚底位于方块表面之上（`ExitPlacement` 会抬升 0.1 mm 并在
脚底嵌入方块碰撞体时抬到碰撞体顶，因为 `pose.y - eyeHeight` 的 float 误差会让脚底低约
4.8e-9，GUI 取景的 6 位小数取整、潜行/游泳取景与站立恢复的眼高差也可能让脚底嵌进方块）。
编辑器的“传送到此”使用同一套落点校正与重同步。

退出与编辑器“传送到此”都会对同一落点发送两次传送（间隔约 1 tick）来重置客户端的传送确认
状态；监听 `PlayerTeleportEvent` 的其他插件会看到两次 `PLUGIN` 传送。

## Folia

插件在 `plugin.yml` 中声明 `folia-supported: true`。镜头实体使用 `EntityScheduler`，玩家操作在
玩家自己的 `EntityScheduler` 上执行；镜头和玩家传送均使用 `teleportAsync`，镜头所在区块通过
插件区块票据保持加载，会话结束后释放。没有全局 tick 任务，每个会话只有一个镜头实体 tick
任务。

Folia 下镜头实体跨 region（例如远距离 `cut`）时，服务端会把旧镜头实体替换为新实体，并先清空
一次旁观目标。事件层表现为一次 `PlayerStopSpectatingEntityEvent`；插件通过服务端 sneak 标记
判断它并非玩家按 Shift，取消该事件并静默重新锁定镜头，因此不会结束预览、不会提示锁定、
也不会触发 `allow-sneak-exit`。首次发生时会记录一条
`Server released the spectator camera ... without a Shift input` INFO 日志用于确认；Paper 上通常
不会出现该分支（镜头实体不会被替换，除非其他插件或传送路径主动释放了相机）。

## 未安装 PacketEvents 时的提示

没有安装 PacketEvents 时不会阻止插件运行，但会明确提示：

- 启动日志为 WARN：`PacketEvents not installed; fake-player camera disabled...`；
- 播放使用 `blindness`/`slowness`/`speed` 等镜头相关效果的轨道时，聊天栏提示当前是盔甲架
  回退模式，控制台只对每条轨道警告一次；
- 编辑器轨道概览显示“镜头模式: 盔甲架回退 · FOV/失明雾不可用”；
- `/camerarail info <id>` 显示“镜头模式: 假玩家 (PacketEvents)”或“镜头模式: 盔甲架回退”；
- 效果选择器摘要与效果表单显示回退说明；
- `/camerarail edit <轨道> add effect ...` 追加镜头相关效果后，聊天栏追加同样的提示。

## 协议与兼容性限制

- 假玩家镜头下，`blindness` 的雾/压天、`darkness`/`night_vision` 的雾也会生效，`slowness`/
  `speed` 会改变 FOV；`nausea`、亮度图等仍按真实玩家读取。未安装 PacketEvents 或假玩家创建
  失败时，效果退回旧行为（仅 `nausea` 完整、`darkness` 部分、`night_vision` 大部分）。详见
  [spectator-effects.md](spectator-effects.md)。
- 假玩家镜头不是完整客户端渲染：全屏渐变/黑场、水下/着火/方块贴脸等 `ScreenEffectRenderer`
  叠加层仍不可用，FOV 也只能通过药水等级（`slowness`/`speed`）表达。
- 假玩家是客户端侧实体，跨版本链路（ViaVersion/ViaBackwards）是否完整转发它的 PlayerInfo、
  属性和效果包取决于链路；建议在目标 26.1.2 原生客户端上验收 FOV 和画面效果。若链路不转发
  属性包，FOV 会保持 1.0，但镜头与其余效果不受影响。
- 假玩家镜头的视角 yaw 通过原版 head-yaw 包同步：协议精度为 1/256 圈（约 1.40625°），客户端
  还会做 3 tick 插值。因此极慢的缓动平移可能有亚度级台阶或约 2–3 tick 的跟随滞后，快速转身
  约 150 ms 后到位；位置与 pitch 不受影响。这与盔甲架回退模式、旁观原版玩家时一致。
- 镜头锁定依赖客户端正确处理 `Set Camera` 包，少数第三方客户端或协议转换链路可能无法锁定
  旁观相机。
- 精确同步依赖服务端映射字段；服务端改名时会自动退回默认的量化跟踪，播放仍可继续。
- 定位条隐藏依赖 26.1.2 的 `waypoint_receive_range` 属性；服务端不提供该属性时只记录一次
  WARN 并继续播放（定位条保持可见），不会中断镜头。
- 一条轨道只能属于一个世界。
- 如果 `world` 当前没有加载，轨道仍会加载，但播放时会提示世界缺失；世界加载后无需重载。
- 单条轨道解析失败不会阻止其他轨道加载；`/camerarail reload` 时失败轨道会保留上一个可用
  版本，并在控制台输出具体错误。

本地验证与测试清单见 [testing.md](testing.md)。