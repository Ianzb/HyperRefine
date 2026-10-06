<!--suppress HtmlDeprecatedAttribute, HtmlDeprecatedTag -->
<div align="center">

<img src="docs/icon.png" width="140" alt="HyperRefine" />

# HyperRefine

### 基于 Miuix 的 LSPosed 模块

[接口文档](docs/API.md) | [原生 Hook 指南](docs/NATIVE_HOOK.md) | [二次开发指南](docs/CUSTOMIZE.md) | [模块开发工作流](docs/WORKFLOW.md) | [更新日志](changelog.md) | [Telegram 群组](https://t.me/HyperRefine)

![Platform](https://img.shields.io/badge/Platform-Android-green)
![LSPosed](https://img.shields.io/badge/LSPosed-libxposed%20102-blue)
[![爱发电](https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E8%B5%9E%E5%8A%A9%E6%94%AF%E6%8C%81-946ce6)](https://afdian.com/a/Ianzb)

</div>

**HyperRefine** 是一个 **LSPosed 模块**项目，基于 [libxposed API 102](https://libxposed.github.io/api/index-all.html) 与 [Miuix](https://github.com/compose-miuix-ui/miuix) Compose 组件库构建，使用 [MiuixGuiTemplate](https://github.com/Ianzb/MiuixGuiTemplate) 脚手架（`Based on MiuixGuiTemplate 0.5.2`）创建，提供完整的 Hook 二次封装接口与可复用 UI 组件。当前 Hook 目标为**系统界面**（`com.android.systemui`）：控制中心音量条 / 亮度条与侧边音量条的百分比数值显示；**安全服务**（`com.miui.securitycenter`）：隐藏快充加速通知；**MiLink 与小米互联**（`com.milink.service` / `com.xiaomi.mirror`）：设备互联三项功能；另预留系统桌面（`com.miui.home`）占位。

<br>

# 功能

**模块功能**（目标应用 `com.android.systemui` / `com.miui.securitycenter`）

- **音量条 / 亮度条百分比数值显示**（适配 HyperOS 4）— 控制中心音量条 / 控制中心亮度条（含二级亮度条）/ 侧边音量条三处独立显示百分比数值
  - 三处各自独立配置：开关、字号、字重、颜色
  - 字号 8–24（默认 13）；字重：细体 / 常规 / 中等 / 半粗 / 粗体 / 特粗（默认特粗）
  - 颜色跟随图标：低值灰色、高值彩色；关闭跟随则固定灰色
  - 侧边音量条附加：百分比位置（音量区域上方悬浮 / 音量条内部上方）、长按打开音量面板并隐藏三个点按钮（默认关闭）
- **隐藏快充加速通知**（适配 HyperOS 4，目标 `com.miui.securitycenter`）— 隐藏 90W 及以上快充机型在快充加速时由安全服务发布的快充加速通知（「省电与电池重要通知」类别）；「进入提醒」与「退出提醒」各自独立开关
- **设备互联**（适配 HyperOS 4，目标 `com.milink.service` / `com.xiaomi.mirror`，移植自 [HyperConnectToolkit](https://github.com/silverpoetry/HyperConnectToolkit)，Apache-2.0）— 功能页「设备互联」二级页集中以下功能，顶栏可一键重启目标服务：
  - **解锁跨设备通知流转**（仅平板）— 纠正平板本机能力误判，通知点击复用原生 `PIN_APP` 流转路径
  - **允许平板竖屏流转应用**（仅平板）— 纠正首包横竖尺寸倒置，并允许目标画面按完整方向运行
  - **MiLink Multi-Channel** — AndroidPad 共享通道上限 1 → 2，需在手机上开启同名选项
- **Hook 生效状态** — 所有由目标进程安装的 Hook 功能，其配置项副标题显示「已生效 / 未生效」（需重启目标进程后刷新）
- **横屏融合设备中心右置**（适配 HyperOS 4，仅手机）— 手机横屏时把融合设备中心从控制中心左侧移到右侧（非手机设备上该开关禁用灰显）
- **缩小融合设备中心设备点击范围**（适配 HyperOS 4）— 把每个设备的点击判定范围缩小到图标本身，点击卡片内空白处改为打开融合设备中心，而不是对应设备
- **流转卡片玻璃**（适配 HyperOS 4，目标 `com.milink.service`）— 为融合设备中心点击设备后弹出的流转卡片补上系统柔光玻璃，设备不可用（灰色卡片）时不再只有模糊
- **多级页面搜索** — 功能页搜索支持多级嵌套（「系统界面 → 外观 → 各位置」），摘要显示父 / 子路径，命中直接打开对应页面
- **密码 · 通行密钥修复**（目标 `system` / `com.android.settings` / `com.miui.securitycenter` / `com.xiaomi.scanner`）— 功能页「密码」页：修复 HyperOS 通行密钥（Passkey），含系统服务路由、设置项显示、阻止安全中心覆盖、阻止扫码劫持（需启用 Google 基础服务）
- **浏览器 · 阻止强制小米浏览器**（目标 `android` 框架 + 小米互传 / AI 引擎 / 超级小爱 / 设置 / 应用商店等）— 功能页「浏览器」页：把被强制交给小米浏览器的网页链接改投系统默认浏览器，含跳转拦截、伪装已安装、通知图标替换、复制直达等

**底层框架**（构建于脚手架 MiuixGuiTemplate）

- **Hook 封装** — `hookBefore` / `hookAfter` / `hookReplace` / `intercept` / `findAndHook*` / `hookAll*` / `hookClassInitializer` / `invokeOriginal`，统一句柄管理
- **原生 Hook 封装** — `NativeHookHelper` / `BaseNativeHook` / `BaseLoad.initNativeHook`，与 JavaHook 对称的一键接入（声明、加载、开关、安全兜底）；面向 Rust 应用（`flutter_rust_bridge` / 纯 Rust 库），详见[原生 Hook 指南](docs/NATIVE_HOOK.md)
- **版本 / 设备筛选** — 统一的 `HookVersionGate` 与 `deviceScope`：版本支持 `>` `<`、多重规则（AND/OR），可按 Android / HyperOS / MIUI / 应用版本；设备支持手机 / 平板 / 折叠屏区分，默认各设备通用，并可在设置中手动覆盖当前设备类型
- **安全模式** — 设置页「模块」分区声明安全模式状态并提供入口，二级页逐应用开关安全模式并查看崩溃计数
- **主动申请作用域** — 启用选项时自动为未授权目标申请作用域
- **DexKit 缓存** — 带 JSON 持久化、版本失效校验与文件锁的 DexKit 缓存，支持 Root 清空
- **统一配置系统** — 自定义键名、默认值、持久化、跨进程镜像、JSON 导出导入
- **组件与 Hook 绑定** — 开关 / 箭头 / 下拉 / 滑块 / 复选框 / 单选 / 文本卡片，标题接入全局搜索
- **设备独占控制** — 配置项声明 `deviceScope`（手机 / 平板 / 折叠屏）后，非白名单设备上**禁用灰显不隐藏**，切换「当前设备类型」实时生效；hook 侧同步跳过
- **二级页面模板** — 独立 Activity，自动套用主题与背景模糊配置
- **应用内检查更新** — 设置页「更新」分区支持「启动时自动检查」与手动检查；发现新版本弹窗展示版本对比与 Release 更新说明，确认后跳转 GitHub Release 下载页

<br>

# 系统要求

- 已安装 **LSPosed**（支持 libxposed API 102）
- Android 15+（minSdk 35）
- 音量条 / 亮度条百分比功能面向 **HyperOS 4** 系统界面
- 清空 DexKit 缓存需要 **Root 权限**

<br>

# 构建

```bash
# 克隆项目
git clone https://github.com/Ianzb/HyperRefine
cd HyperRefine

# 设置 JDK 25（CI 使用 temurin 25）和 Android SDK
# 编辑 local.properties 指向你的 SDK 路径

# 构建 Debug APK
./gradlew assembleDebug

# APK 输出位置（仅 arm64-v8a）
# app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

<br>

# 发布与 CI

仓库内置 GitHub Actions 工作流：

- **`.github/workflows/ci.yml`** — 推送到 `master`（改动 `app/**`、`hook/**`、Gradle 文件）或提交 PR 时构建 Debug APK，上传为 Actions Artifacts（保留 7 天）。
- **`.github/workflows/release.yml`** — 推送 `v*.*.*` 标签或手动触发时构建**签名** Release APK，发布到 GitHub Release（Release Notes 取自 `changelog.md` 对应版本段落）并上传 Artifact（保留 30 天）；配置 Telegram 后可自动推送。

## Release 签名配置（二次开发须自行配置）

Release 构建通过 `signingConfigs.release` 读取环境变量（见 `app/build.gradle.kts`），keystore 固定为根目录 `release.keystore`（已加入 `.gitignore`，切勿提交）：

| 环境变量 / Secret | 说明 |
|---|---|
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名（未设置时默认 `release`） |
| `KEY_PASSWORD` | 密钥密码 |

**1. 生成本地 keystore**（alias 换成你自己的）：

```bash
keytool -genkeypair -v -keystore release.keystore \
  -alias release -keyalg RSA -keysize 2048 -validity 10000
```

**2. 本地构建签名包**（PowerShell）：

```powershell
$env:KEYSTORE_PASSWORD="你的keystore密码"
$env:KEY_ALIAS="release"
$env:KEY_PASSWORD="你的密钥密码"
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

**3. 配置 GitHub Secrets**（仓库 Settings → Secrets and variables → Actions）：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `release.keystore` 的 Base64：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))` |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |
| `CHANNEL_ID` / `BOT_TOKEN` | （可选）Telegram 推送；不需要时删除 `release.yml` 的「上传到Telegram」步骤 |
| `MESSAGE_THREAD_ID` | （可选）多话题群（Forum）指定话题的 `message_thread_id`；不填则发到默认 / General 话题 |
| `TEST_MESSAGE_THREAD_ID` | （可选）测试版推送的话题 `message_thread_id`（同一群的另一个话题），与 `CHANNEL_ID` / `BOT_TOKEN` 配合使用；不填则跳过 CI 的「上传到Telegram」步骤 |

**4. 发布**：手动运行 `Release Build` 工作流并填写版本号（会自动 `versionCode` +1 并提交），或先改好 `versionName` 再推送 `v1.0.0` 形式的标签。

> 只开发 / 只构建 Debug 时无需上述配置，`assembleDebug` 不受影响；未放置 `release.keystore` 或未设置环境变量时请勿运行 `assembleRelease`。

<br>

# 开发

1. 在 `:hook` 模块 `META-INF/xposed/scope.list` 中声明作用域包名（当前为 `com.android.systemui`、`com.miui.home`）。
2. 新建 `BaseLoad` 并在 `HookEntryRegistry` 中登记目标包（当前为 `SystemUiLoad` → `com.android.systemui`；`HomeLoad` → `com.miui.home` 为占位）。
3. 新建 `BaseHook` 实现具体 Hook 逻辑，必要时用 DexKit 定位成员。
4. 在 `featureSpecs()`（或自定义注册处）声明 `OptionSpec`，UI 会自动渲染对应组件；子页面功能可用 `HookSubPage` 并入功能页搜索。
5. 构建并在 LSPosed 中验证功能生效（启用开关并重启目标应用）。

> **需要修改的完整清单（图标、链接、模块元数据、Hook、配置项等）见 [二次开发指南](docs/CUSTOMIZE.md)。**

> **实现指定应用的 Hook、或参考其他模块复刻功能时的完整流程（含真机 `adb` 扫描授权、隐私边界与开源合规）见 [模块开发工作流](docs/WORKFLOW.md)。**

详细接口说明见 [接口文档](docs/API.md)。

<br>

# 第三方库

- [miuix](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格 Compose UI 组件库
- [libxposed API](https://github.com/libxposed/api) — 现代 Xposed 模块 API（102）
- [DexKit](https://github.com/LuckyPray/DexKit) — Dex 解析与缓存
- [AndroidX Compose](https://developer.android.com/jetpack/compose) — 声明式 UI 框架

<br>

# 参考与致谢

HyperRefine 基于个人 Android 模块开发脚手架 MiuixGuiTemplate 创建，开发过程中参考了若干开源项目与其他模块项目，谨向相关作者与贡献者致谢。具体致谢清单统一维护在应用内「关于 → 第三方许可证与致谢」页面，文档不再逐一展开；参考或复刻第三方项目时的合规流程见 [模块开发工作流](docs/WORKFLOW.md)。

<br>

# 赞助支持

如果 HyperRefine 对你有帮助，欢迎通过 [爱发电](https://afdian.com/a/Ianzb) 赞助支持作者，你的支持是项目持续维护与更新的动力。

<br>

# 许可证

本项目以 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）开源。

本仓库同时包含 Apache-2.0 许可的第三方代码（自 [miuix](https://github.com/compose-miuix-ui/miuix) 等引入的文件保留其原始版权与许可声明）。AGPL-3.0 与 GPL-3.0 兼容（AGPL-3.0 §13）：参考或移植 GPL-3.0 项目（如 [SoundMan](https://github.com/killerprojecte/SoundMan)）时，本项目整体以 AGPL-3.0 授权分发；衍生作品须以 AGPL-3.0 授权公开，并保留应用内「参考与致谢」与 Based on 标注。
