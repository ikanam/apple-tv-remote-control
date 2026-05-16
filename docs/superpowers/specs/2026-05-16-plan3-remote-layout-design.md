# Plan-3 遥控器布局 —— 设计(修订原 spec §5 + Plan-3 plan)

> 日期:2026-05-16。状态:**已批准**(已与 owner 头脑风暴并确认)。
> 本文档是一份**修订**,依据 2026-05-16 真机会话(客厅 tvOS 26.5)+ owner
> 范围决策,修订:
> - `docs/superpowers/specs/2026-05-15-apple-tv-android-remote-design.md` §5(UI/UX)
> - `docs/superpowers/plans/2026-05-15-atv-remote-plan-3-android-app-ui.md`
> 原 spec/plan 的其余部分(架构、连接生命周期、Keystore 凭证、发现/配对、
> SwipeEngine、测试策略)除本文明确变更处外,保持不变。`:protocol` 为 LOCKED,
> Plan 3 不修改它。

## 为什么需要这份修订

2026-05-16 真机会话确立了原 spec 当时无法知道的 tvOS-26.5 真相(已全部记入
项目 memory + CLAUDE.md + docs/PROTOCOL.md;提交
`2efdbce`/`cc2aa10`/`5fa4504`/`b91681a`):

- `FetchLaunchableApplicationsEvent`(`listApps`)与 `FetchAttentionState`
  (`powerStatus`)在 **tvOS 26.5 上无响应** —— 已确认的上游 Apple 回归
  (pyatv #2823 / home-assistant #168210);pyatv 自身同样失败,故我们的移植
  是正确的,受限的是设备固件。
- Companion HID 指令集(pyatv 权威 `HidCommand` 枚举)**没有 Mute** —— 实体
  Siri Remote 的静音走 HDMI-CEC,不在 App↔ATV 的 Companion 链路内。
- 滑动方向是 **tvOS 按当前界面滚动轴解释的**(投影到聚焦视图的滚动轴),
  不是固定的 `(dx,dy)` 契约;确定性方向导航必须用 HID 方向键。
- RTI 文本输入(`_tiStart`/`_tiC`/`_tiD`)在 tvOS 26.5 上**可用**;真实
  `_tiD` RTIKeyedArchiver 数据已采集并提交(`cc2aa10`)。但 `:protocol` 的
  键盘成员(`textGet/Set/Clear/Append`、`keyboardFocus`)仍是
  `NotImplementedError` 桩,待延后的 **T16/T15**(纯代码,真机数据已存)。
- owner 批准的 Plan-2 §7 变更(`b91681a`):`Reconnecting` 期间
  `touch`/`button`/`click` **直接丢弃,不入队、不重放**。

## 要做:Hero(主)遥控屏

单一主遥控屏,布局 **1:1 模仿实体 Apple TV Siri Remote(2021+)**,自上而下。
每个控件都映射到一个**已实现、Companion 支持**的 `:protocol` 调用。无死按钮。

| 位置(对应实体遥控器) | 控件 | `:protocol` 调用 | 说明 |
|---|---|---|---|
| 顶部,右对齐 | **电源 ⏻** | `power(on: Boolean)`(Wake/Sleep HID) | Wake/Sleep 在 tvOS 26.5 有效。**不显示电源状态**(`powerStatus`/FetchAttentionState 在 26.5 已坏)。交互见「关键决策」。 |
| 上部环形 Clickpad — 边缘区 | **方向键 上/下/左/右** | `button(RemoteButton.Up/Down/Left/Right, down)` | 确定性方向导航走 HID(不依赖自由滑动方向)。 |
| Clickpad — 中心 | **确定 / OK** | 轻点 → `click(InputAction.SingleTap)`;长按 → `click(InputAction.Hold)` | |
| Clickpad — 触摸面 | **自由滑动**(触控板擦动) | `touch(x,y,phase)`,经 SwipeEngine 节流 ≤120 Hz | 屏幕方向由 tvOS 按界面决定;这是擦动,不是方向键。 |
| 第 1 行 | **返回 `<`** · **TV/Home** | `button(Menu)` · `button(Home)` | Apple TV 的「Menu」即返回。 |
| 第 2 行 | **播放/暂停 ▷‖** · **音量摇杆 +/−** | `media(MediaCommand.Play/Pause)` · `button(VolumeUp/VolumeDown)` | 音量经下游 HDMI-CEC 作用于电视/功放。 |
| 左下(实体遥控器静音位) | **键盘 ⌨** → 实时文本输入屏 | `textGet/Set/Clear/Append`、`keyboardFocus` | 替代静音(Companion 无 Mute HID;守「无死按钮」)。**依赖 T16**(见「依赖与排序」)。 |

保持原 spec/plan 不变并保留:发现列表 + 已配对设备切换屏、电视端 PIN 配对屏、
实时键盘屏(现由键盘键进入)、Keystore 加密凭证存储、生命周期感知连接
(`ConnectionService`/`ConnectionManager`)、触觉、SwipeEngine/SwipeTuning
调试屏、连接状态指示(「重连中…」非报错 UI)。

## 不做(明确排除)

- **应用启动器屏 —— 整屏移除。** `AppLauncherScreen`、`LauncherViewModel`、
  `LauncherViewModelTest` 及其导航入口从 Plan-3 范围删除(owner 决策:
  `listApps` 在 tvOS 26.5 已死,该屏核心价值在当前固件无法实现)。`:protocol`
  的 `listApps`/`launchApp` 保留在 LOCKED API(v1 无 UI 消费者)。
- **静音键** —— 实体遥控器有;Companion 无 Mute HID;槽位改作键盘。
- **Siri / 语音键** —— 原 spec §5 已排除(无第三方路径);继续排除。
- **电源状态显示** —— 无任何 UI 呈现 `powerStatus`;凡出现该类型处必须容忍
  `PowerStatus.Unknown`。
- **Now Playing / 封面** —— 不变的 YAGNI 排除(需 AirPlay2/MRP)。

## 关键决策

1. **布局还原 vs 无死按钮:** 还原实体遥控器的几何布局,但每个槽位都必须是
   可用的 Companion 动作。静音→键盘 即该冲突的具体解(与原 spec 移除 Siri 键
   的理由一致)。
2. **键盘占据黄金槽位。** 砍掉应用启动器后,实时文本输入是单键里价值最高的
   手机遥控功能,且原 spec 本就以它为核心;它占据实体静音位。
3. **方向导航走 HID 键,不靠滑动。** SwipeEngine 仍输出节流的 `touch` 流用于
   自由擦动,但方向意图映射到 `button(Up/Down/Left/Right)`(真机发现:滑动
   方向由内容决定)。Plan-3 的 SwipeEngine 已建模为「TouchPhase 或方向
   button」—— 方向路径按 HID 实现。
4. **电源交互:** Hero 顶栏单一电源控件。无 `powerStatus` 时「轻点切换」不
   可靠,因此 **轻点 → `power(true)`(Wake)**,**长按 → `power(false)`
   (Sleep)**,配触觉确认,不回读状态。(理由:Wake 是常用且有价值的动作;
   Sleep 是刻意操作;无 powerStatus 可驱动切换。)
5. **重连期 UX:** 依 Plan-2 §7(`b91681a`),装饰器在 `Reconnecting` 期间
   丢弃 touch/button/click 且不重放;Hero 屏显示非报错的「重连中…」指示,
   用户在 `connectionState` 回到 `Connected` 后重按。客户端不做输入缓冲。

## 依赖与排序(无占位符 —— 均为具体项)

- **键盘键 → T15/T16/T19(延后,纯代码)。** 键盘键从第一天起就在布局中,
  但其目标屏仅在延后的键盘链落地后可用:T15(移植 `RtiPayloads`,用已提交的
  `text-set.json` 做 golden 校验)、T16(移植 `KeyedArchiver` + 实现
  `KeyboardController` 替换 `NotImplementedError` 桩;若真实 1987 B
  RTIKeyedArchiver `$objects` 图超出文档化的 UID/ref-255 上限则加宽 `Plist`
  —— 见 CLAUDE.md)、T19(零桩/flows 关卡)。真实 `_tiD`/`_tiC` 采集数据已
  提交(`cc2aa10`:`keyed-archiver-tiD.json`、`text-set.json`)。**Plan-3
  实现顺序:键盘链(T15/T16/T19)必须先于 `KeyboardScreen` 接线完成**;在此
  之前键盘键渲染为禁用 + 「即将支持」/不可用样式(可接受的临时态 —— 这是
  构建顺序状态,不是永久死按钮)。
- **tvOS-26.5 限制属设备固件,非 App 缺陷。** 任何 Plan-3 工作都无法在
  tvOS 26.5 上恢复 `listApps`/`powerStatus`;本设计移除/省略它们的 UI,
  而不是上线坏掉的界面。

## 测试路径(供后续 plan 使用)

- 正常路径:每个 Hero 控件 → 恰好触发所映射的 `:protocol` 调用(ViewModel
  单测,用测试内 `FakeProtocol`)。
- 方向 vs 滑动:SwipeEngine 对边缘意图输出方向 `button`,对自由拖拽输出节流
  `touch`;轻点/长按分类 → `click`。
- 电源:轻点 → `power(true)`,长按 → `power(false)`。
- 重连:`connectionState==Reconnecting` 时控件点击仍发出,但(依 §7)被
  装饰器丢弃;UI 显示指示;无客户端队列。回到 `Connected` 后控件恢复。
- 键盘键 T16 前:渲染为禁用/不可用;T16 后:打开文本屏,编辑经 `text*`
  传播,`keyboardFocus` 驱动可见性。
- 已移除范围守卫:UI 测试断言 Hero 屏不存在应用启动器入口、不存在
  静音/Siri 按钮。

## 回滚

纯 Plan-3(`:app`)范畴;`:protocol` 不动。回滚即删除/不构建 `:app` 模块。
无数据迁移。
