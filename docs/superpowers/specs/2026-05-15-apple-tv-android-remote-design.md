# Apple TV 遥控器（Android）设计文档

- 日期：2026-05-15
- 状态：已通过设计评审，待用户复核 spec
- 定位：开源 Android App，原生 Kotlin 实现，UI/UX 1:1 复刻苹果原生 Apple TV 遥控器

---

## 1. 背景与可行性结论

苹果 Apple TV 的遥控协议未公开，但已被开源社区完整逆向，参考实现为 Python 库 **pyatv**（MIT）。
Google Play 已有商业产品（`com.brkchen.atvremote`）证明此类 App 可行。结论：**可行（有前提）**。

当前 tvOS 17/18+ 的协议现实：

- DMAP/DACP、独立 MRP —— 现代设备上已废弃。
- **Companion 协议**（`_companion-link._tcp`）—— 苹果现代遥控器使用：导航/HID、触摸手势、键盘文字输入、App 启动、电源。
- **MRP over AirPlay 2** —— Now Playing 元数据/封面所需，是协议栈中加密与传输最难、最易被苹果改坏的部分。

两个硬约束：

- **Siri 语音不可行**（音频采集/串流私有，无逆向路径）。
- 没有成熟的 Kotlin/Java Apple TV 协议库 —— 本项目即填补此空白。

法律/合规：开源定位，依赖 pyatv 已公开的实现作为字节级规范而非自行破解，可降低（不消除）风险；
命名/图标不得使用 Apple 商标或暗示官方背书，使用 “for Apple TV” 描述性表述、独立图标。
（非法律意见。）

## 2. 范围

### v1 功能范围（仅 Companion 协议）

- 局域网发现 Apple TV（mDNS `_companion-link._tcp`）
- HAP/SRP 配对（电视显示 PIN → App 输入 → 换取并持久化长期凭证）；每次连接 pair-verify
- 全保真滑动触控板：相对滑动移动焦点 + 惯性 + 边缘方向点按 + 轻点/长按选择 + 触觉反馈
- 菜单/返回、Home/TV、播放暂停、音量 +/−
- 键盘文字输入（Companion Keyboard，与电视输入框实时同步）
- 列出并启动已安装 App
- 电源开关/睡眠（深睡唤醒以 Wake-on-LAN 兜底）

### 明确不做（YAGNI，后续版本）

- Now Playing 信息 + 封面（需 AirPlay 2 隧道 MRP）
- Siri / 语音（无逆向路径）
- Kotlin Multiplatform / 多平台
- 远程 / 公网控制（仅同一局域网）
- 多 Apple TV 并发管理（v1 支持记住并切换已配对设备，但不并发）

### 成功标准

1. 在 tvOS 18 真机上完成配对；重启 App 后免重配自动重连。
2. 上述全部指令稳定可用；滑动手感与苹果原生遥控器盲测难以区分。
3. 协议层在 JVM 上 golden-trace 一致性测试通过（与 pyatv 报文逐字节一致）。
4. `:protocol` 模块可作为独立 Kotlin 库被第三方复用。

## 3. 架构

### 模块划分

```
:protocol   纯 Kotlin/JVM，零 Android 依赖，可 JVM 单测
  ├ discovery   jmDNS 浏览 _companion-link._tcp，解析 host/port/TXT
  ├ crypto      SRP-6a · Ed25519 · X25519 · ChaCha20-Poly1305 · HKDF
  ├ opack       Apple OPACK 序列化/反序列化（手写，以 pyatv opack.py 为规范）
  ├ pairing     HAP TLV8 · pair-setup(M1–M6, PIN) · pair-verify
  ├ companion   加密帧收发 · 会话状态机 · 请求响应 + 事件流
  ├ commands    HID 按钮 / 触摸事件流 / 键盘 / App 列表启动 / 电源
  └ (public) AppleTvRemote 门面：suspend 函数 + Flow，不暴露 Android 类型
:app        Android + Compose，依赖 :protocol
  └ UI · ViewModel · Keystore 加密凭证存储 · 连接生命周期 · 触觉
:trace-tools (dev/test) pyatv golden-trace 抓取 + 一致性测试 + 滑动调校台
```

### 关键技术选型

- **加密**：BouncyCastle（纯 Java，JVM+Android 通吃，SRP/Ed25519/X25519/ChaCha20-Poly1305 全有，单一依赖、可 JVM 测）。排除 Tink（无 SRP）、libsodium（需 native 库，破坏纯 JVM 测试）。
- **mDNS**：jmDNS（JVM 与 Android 都能跑，:protocol 自包含，不依赖 Android NSD）。
- **并发**：kotlinx-coroutines；单 IO 读帧循环 → 分发到响应 continuation + 事件 SharedFlow。
- **OPACK**：手写编解码器，以 pyatv `opack.py` 为字节级规范（Apple 私有格式，无现成库）。
- **UI**：Jetpack Compose。
- **凭证存储**：Android Keystore 包裹密钥 + DataStore，按设备 id 索引。

## 4. 协议层设计（技术核心）

1. **发现**：jmDNS 浏览 `_companion-link._tcp.local`，解析地址与 TXT（含 MAC，用于 Wake-on-LAN）。
2. **pair-setup**：Companion 建 TCP → HAP pair-setup；SRP-6a（HAP 3072-bit group），电视显示 PIN → 用户输入 → 交换 Ed25519 长期公钥与签名 → 派生长期凭证 → Keystore 加密持久化。
3. **pair-verify（每次连接）**：X25519 临时 ECDH → HKDF 派生会话密钥 → 之后所有 Companion 帧走 ChaCha20-Poly1305 加密通道。
4. **会话**：OPACK 请求/响应；实现 opcode：握手/systemInfo、HID 按钮、HID 触摸事件流（滑动）、键盘 set/get/focus/clear、App 列表/启动、电源 sleep/wake。
5. **公开 API**：`AppleTvRemote` 门面，suspend 函数 + Flow，不暴露 Android 类型。

### 去风险方法（贯穿三层）

在 tvOS 18 真机上让 pyatv 执行各操作，抓取原始帧存为 golden fixture；Kotlin 构造的报文与之**逐字节对照**，响应解码用 pyatv 抓到的真实响应验证。将“盲猜协议”转为“对答案”。

### 已知风险

- OPACK/Companion 文档比 MRP 少，pyatv `companion/` + `opack.py` 是唯一规范。
- tvOS 18.4 有 Companion 断连报告（pyatv issue #2656）—— 以真机行为为准并内建重连。
- 深睡唤醒不稳 —— Wake-on-LAN 兜底，失败则提示手动唤醒。
- 苹果跨小版本改行为是最大持续风险 —— 解码失败降级不崩溃 + 诊断日志。

## 5. UI/UX 设计

- **主界面（Hero）** 1:1 复刻苹果布局：顶栏（设备切换 / 右上菜单）→ 圆形触控板 → 按钮行 → 键盘入口 + 音量摇杆。
- **按钮行**：返回/菜单 · TV/Home · 播放暂停，居中均布。**去除 Siri 麦克风键**并重新平衡（Siri 不可第三方实现，不保留死按钮）。
- **滑动触控板交互**：单指拖动 = 相对移动焦点（位移增量 → Companion HID 触摸事件流，带速度/惯性曲线）；轻点 = 选择；上/下/左/右边缘区域点按 = 方向步进；长按 = 长按选择。目标与苹果盲测难分。
- **触觉反馈**：点按/边缘步进/选择触发轻微 haptic（Android `VibrationEffect`，对齐苹果手感）。
- **次级界面**：设备发现 + PIN 配对；实时同步键盘（电视出现文本框时自动弹出）；App 启动器（图标网格）。
- **外观**：跟随系统浅/深色（与苹果一致）；竖屏手机为主。
- **导航结构**：主遥控界面为主屏；顶栏「设备」进设备/配对；右上菜单进 App 启动器/电源；文本框出现时自动弹出键盘界面。

## 6. 数据流

- **配对流**：设备列表选 ATV → `:protocol` 建 TCP + pair-setup → 电视显示 PIN → 用户输入 → SRP M1–M6 → 长期凭证 → `:app` Keystore 包裹后存 DataStore（按设备 id）。
- **连接流**：选已配对设备 → 读凭证 → pair-verify → 会话就绪 → UI 进入可操作态。
- **指令流**：UI 手势/按钮 → ViewModel → `AppleTvRemote` suspend 调用 → OPACK 编码 → 加密帧写出。滑动手势在 UI 层采样为位移序列，按节流频率（≤120Hz、合并）发 HID 触摸事件流。
- **事件流**：读帧循环把电视推送（键盘焦点变化、文本同步等）发到 SharedFlow → ViewModel → UI。

## 7. 容错与韧性

- **配对失败**（PIN 错/超时/拒绝）：明确错误态可重试，不残留半套凭证。
- **连接掉线**（Doze/切网/电视睡眠）：指数退避自动重连 + pair-verify；UI 显示“重连中”非报错；重连期间滑动事件丢弃、按钮短暂入队。
- **凭证失效**（电视被重置/取消配对）：提示重新配对，清除该设备旧凭证。
- **协议漂移**：解码失败不崩溃，降级为“已连接但该功能不可用” + 诊断日志（便于开源社区提 issue）。
- **深睡唤醒**：尝试 Wake-on-LAN（用 TXT MAC），失败提示手动唤醒。

## 8. 测试策略

- **协议层（JVM 单测，核心资产）**：
  - Golden-trace 一致性测试：tvOS 18 真机上用 pyatv 执行操作抓原始帧存 fixture；Kotlin 编码输出逐字节断言；解码用真实响应验证。
  - 加密原语单测（SRP/Ed25519/X25519/ChaCha20-Poly1305 已知测试向量）；OPACK 编解码往返；TLV8 往返。
- **设备集成测试**：真机冒烟脚本 —— 配对、重连、各指令、键盘同步、App 启动、电源。
- **滑动手感调校台**（`:trace-tools`）：可视化记录手势输入 → 发出的触摸事件，与苹果原生并排盲测，迭代速度/惯性曲线参数。
- **UI**：ViewModel 单测；Compose 关键交互（边缘判定、节流）测试。

## 9. 参考

- pyatv 文档与源码：https://pyatv.dev/ ，https://github.com/postlund/pyatv （`companion/`、`opack.py`）
- MRP 协议逆向：https://github.com/jeanregisser/mediaremotetv-protocol ，https://edc.me/posts/dissecting-the-media-remote-protocol/
- pyatv issues：#1526（MRP→AirPlay 迁移）、#2656（tvOS 18.4 Companion 断连）
- 现有 Android 商业产品参考：`com.brkchen.atvremote`
