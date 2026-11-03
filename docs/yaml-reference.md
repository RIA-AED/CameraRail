# tracks.yml 字段参考

时间单位全部是 **tick**，20 tick = 1 秒。

```yaml
schema-version: 1
tracks:
  <id>:
    display-name: "..."
    description: "..."
    world: world
    start: { x: 0.5, y: 100.0, z: 0.5, yaw: 0, pitch: 10 }
    # exit 可省略；省略时回到玩家开始过场前的位置
    exit:  { x: 0.5, y: 65.0, z: 0.5, yaw: 0, pitch: 0 }
    sway: natural
    follow: true
    entry-effects:
      - effect: blindness
    steps:
      - { type: hold, duration: 40 }
```

## 根节点

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `schema-version` | int | 否 | 当前只支持 `1`，默认 1 |
| `tracks` | map | 是 | 轨道 id → 轨道定义 |

## 轨道

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `display-name` | string | 否 | 列表/信息中显示的名称；默认 id |
| `description` | string | 否 | 管理信息中的说明 |
| `world` | string | 是 | 轨道所在世界名，必须与服务器加载的世界名一致 |
| `start` | pose | 是 | 起始镜头姿态（眼睛位置） |
| `exit` | pose | 否 | 结束后玩家脚部位置和朝向；省略时回到过场开始前的位置 |
| `sway` | preset / map | 否 | 轨道默认摇摆，默认取 `config.yml` |
| `follow` | boolean | 否 | 玩家实体是否跟随镜头跨区块；默认取配置 |
| `entry-effects` | list | 否 | 进入镜头（第一次传送前）施加的效果，只能 add |
| `steps` | list | 否 | 有序步骤；允许为空，此时播放会立即结束 |

轨道 id 规则：小写字母或数字开头，只含 `a-z0-9_-`，最长 64 字符。id 不区分大小写，但保存时统一小写。

### pose

| 字段 | 类型 | 必填 | 默认 |
| --- | --- | --- | --- |
| `x` | number | 是 | — |
| `y` | number | 是 | — |
| `z` | number | 是 | — |
| `yaw` | number | 否 | 0 |
| `pitch` | number | 否 | 0，范围 -90..90 |

`start` 的 `y` 是**镜头眼睛**高度；`exit` 的 `y` 是**玩家脚部**高度。两者不要混用。

### sway

预设写法：

```yaml
sway: off
sway: gentle
sway: natural
sway: strong
```

自定义写法：

```yaml
sway:
  preset: natural       # 先取预设作为基础（可省略）
  x: 0.03               # 方块/水平
  y: 0.016
  z: 0.022
  yaw: 0.28             # 角度
  pitch: 0.10
  period: 160           # tick，至少 20
  seed: 123456          # 可复现的随机相位
```

所有振幅必须非负；`x/y/z` 建议不超过 5，`yaw/pitch` 建议不超过 30。步骤可以覆盖为 `inherit`（默认）、`off`、`gentle`、`natural`、`strong`。步骤覆盖只改变振幅，周期/种子仍来自轨道，以保证切换时相位连续。

> YAML 1.1 会把未加引号的 `off`/`on` 解析成布尔值。插件对 `false` 按 `off` 处理，`true` 按 `inherit` 处理；写成加引号的 `'off'` 也可。

### entry-effects

在保存恢复文件后、第一次硬切到镜头起点之前施加，适合用 `blindness` 遮住进入瞬间：

```yaml
entry-effects:
  - effect: blindness
  - effect: slowness
    amplifier: 2
```

只支持添加，不支持 `action`。字段与 `effect` 步骤相同。正常结束或中止时，插件会恢复进入前的原效果。

## 步骤

每个步骤必须有 `type`。除 `effect`/`title` 外，镜头步骤支持 `sway` 覆盖。

未知字段会直接报错，避免拼写错误被静默忽略。

### move

```yaml
- type: move
  to: { x: 8.5, y: 68.0, z: -6.5, yaw: 28, pitch: 6 }
  duration: 100            # 默认取 defaults.move-duration
  easing: ease-in-out      # 默认取 defaults.easing
  sway: natural
```

从步骤开始时的当前姿态移动到 `to`。位置线性插值，yaw 走最短角，pitch 线性。

### path

```yaml
- type: path
  duration: 120
  easing: ease-in-out
  points:
    - { x: 2.5, y: 67.5, z: 4.5, yaw: 10, pitch: 3 }
    - { x: 0.5, y: 66.5, z: 2.5, yaw: 0, pitch: 0 }
    - { x: 0.5, y: 66.2, z: 0.5, yaw: 0, pitch: 0 }
```

起点是当前镜头姿态，`points` 是后续路点。位置使用向心 Catmull-Rom 曲线，按弧长参数化，因此整体速度更均匀；yaw 先展开再插值，不会在 ±180° 处绕远路。`points` 至少一个，最多 `limits.max-path-points`。

### hold

```yaml
- type: hold
  duration: 40
  sway: natural
```

保持当前姿态，只有摇摆微动。

### wait

```yaml
- type: wait
  signal: authme:authenticated
  timeout: 6000     # tick；省略用 session.wait-timeout；0 表示永不超时
  sway: gentle
```

进入等待时镜头保持不动。外部发送同名信号后继续。同一轨道内信号名不能重复。信号提前到达会排队；每个信号调用最多推进一个匹配的 wait。

### cut

```yaml
- type: cut
  to: { x: 0.5, y: 70.0, z: 0.5, yaw: 180, pitch: 0 }
```

瞬切。用于有意的硬切/分镜。

### effect

```yaml
- type: effect
  effect: blindness
  action: add          # add（默认）或 remove
  amplifier: 0         # 0 起，0 = I 级
  duration: 0          # 省略/0 = 持续到移除或轨道结束；正整数 = tick
```

常见效果名：`blindness`、`darkness`、`slowness`/`slow`、`night_vision`、`nausea`、`speed`、`invisibility`、`glowing`、`slow_falling`、`levitation`、`regeneration`、`resistance`、`fire_resistance`、`water_breathing`、`conduit_power`、`dolphins_grace`、`bad_omen`、`hero_of_the_village`。也接受带命名空间的注册 id，如 `minecraft:slowness`。

`slowness` 会降低客户端 FOV，可作为“长焦”近似；它不是精确的镜头焦距控制。所有插件施加的效果都隐藏粒子和 HUD 图标。

### title

```yaml
- type: title
  title: "<gray>欢迎回到 <white>Zeroth</white></gray>"
  subtitle: "<dark_gray>正在同步你的位置</dark_gray>"
  fade-in: 20
  stay: 60
  fade-out: 20
```

文本使用 MiniMessage。`title`/`subtitle` 至少一个非空。时间单位为 tick。

## 完整示例

```yaml
schema-version: 1
tracks:
  login:
    display-name: "登录 · 晨雾"
    description: "AuthMe 登录过场"
    world: world
    start: { x: 0.5, y: 100.0, z: 0.5, yaw: 0, pitch: 10 }
    # exit 可省略；省略时回到玩家开始过场前的位置
    exit:  { x: 0.5, y: 65.0, z: 0.5, yaw: 0, pitch: 0 }
    sway:
      preset: natural
      period: 180
      seed: 42
    follow: true
    entry-effects:
      - effect: blindness
    steps:
      - { type: hold, duration: 40, sway: off }
      - { type: effect, effect: blindness, action: remove }
      - { type: effect, effect: slowness, amplifier: 2 }
      - type: move
        to: { x: 8.5, y: 68.0, z: -6.5, yaw: 28, pitch: 6 }
        duration: 100
        easing: ease-in-out
      - { type: wait, signal: authme:authenticated, timeout: 6000, sway: gentle }
      - { type: effect, effect: slowness, action: remove }
      - type: path
        duration: 120
        easing: ease-in-out
        points:
          - { x: 2.5, y: 67.5, z: 4.5, yaw: 10, pitch: 3 }
          - { x: 0.5, y: 66.5, z: 2.5, yaw: 0, pitch: 0 }
          - { x: 0.5, y: 65.0, z: 0.5, yaw: 0, pitch: 0 }
      - { type: hold, duration: 10, sway: gentle }
```

## 限制与校验

- 单条轨道只能有一个世界。
- 所有 `move`/`path`/`hold` 的时长总和不能超过 `limits.max-track-ticks`。
- `steps` 数量不能超过 `limits.max-steps`。
- `path.points` 不能超过 `limits.max-path-points`。
- 未知字段、重复 wait 信号、越界 pitch、未知效果名、未知缓动/摇摆预设都会报错，错误信息包含具体 YAML 路径。
- 如果 `world` 当前没有加载，轨道仍会加载，但播放时会提示世界缺失；世界加载后无需重载。
- 单条轨道解析失败不会阻止其他轨道加载；`/zth-camera reload` 时失败轨道会保留上一个可用版本，并在控制台输出具体错误。
- 编辑器保存会重写整个 `tracks.yml`，只保留文件头注释。
