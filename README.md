<div align="center">

# HyperRefine

### 基于 Miuix 的 LSPosed 模块

[接口文档](docs/API.md) | [原生 Hook 指南](docs/NATIVE_HOOK.md) | [二次开发指南](docs/CUSTOMIZE.md) | [模块开发工作流](docs/WORKFLOW.md) | [更新日志](changelog.md) | [反馈渠道](https://t.me/HyperRefine)

![Platform](https://img.shields.io/badge/Platform-Android-green)
![LSPosed](https://img.shields.io/badge/LSPosed-libxposed%20102-blue)
[![爱发电](https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E8%B5%9E%E5%8A%A9%E6%94%AF%E6%8C%81-946ce6)](https://afdian.com/a/Ianzb)

</div>

**HyperRefine** 是一个 **LSPosed 模块**项目，基于 [libxposed API 102](https://libxposed.github.io/api/index-all.html) 与 [Miuix](https://github.com/compose-miuix-ui/miuix) Compose 组件库构建，使用 [MiuixGuiTemplate](https://github.com/Ianzb/MiuixGuiTemplate) 脚手架（`Based on MiuixGuiTemplate 0.4.0`）创建，提供完整的 Hook 二次封装接口与可复用 UI 组件。当前 Hook 目标为**系统桌面**（`com.miui.home`）。

<br>

# 功能

- **Hook 封装** — `hookBefore` / `hookAfter` / `hookReplace` / `intercept` / `findAndHook*` / `hookAll*` / `hookClassInitializer` / `invokeOriginal`，统一句柄管理
- **原生 Hook 封装** — `NativeHookHelper` / `BaseNativeHook` / `BaseLoad.initNativeHook`，与 JavaHook 对称的一键接入（声明、加载、开关、状态、安全兜底）；面向 Rust 应用（`flutter_rust_bridge` / 纯 Rust 库），详见[原生 Hook 指南](docs/NATIVE_HOOK.md)
- **版本 / 设备筛选** — 统一的 `HookVersionGate` 与 `deviceScope`：版本支持 `>` `<`、多重规则（AND/OR），可按 Android / HyperOS / MIUI / 应用版本；设备支持手机 / 平板 / 折叠屏区分，默认各设备通用，并可在设置中手动覆盖当前设备类型
- **安全模式** — 设置页「模块」分区声明安全模式状态并提供入口，二级页逐应用开关安全模式并查看崩溃计数
- **主动申请作用域** — 启用选项时自动为未授权目标申请作用域
- **DexKit 缓存** — 带 JSON 持久化、版本失效校验与文件锁的 DexKit 缓存，支持 Root 清空
- **统一配置系统** — 自定义键名、默认值、持久化、跨进程镜像、JSON 导出导入
- **组件与 Hook 绑定** — 开关 / 箭头 / 下拉 / 滑块 / 复选框 / 单选 / 文本卡片，标题接入全局搜索
- **Hook 状态展示** — 规则生效时标题显示为绿色、失败为红色，未应用时保持默认色（零额外占位）
- **二级页面模板** — 独立 Activity，自动套用主题与背景模糊配置
- **音量条 / 亮度条百分比数值显示** — 控制中心音量条 / 控制中心亮度条 / 侧边音量条三处独立显示百分比数值，支持字号、字重自定义与颜色实时跟随图标（HyperOS 4）

<br>

# 系统要求

- 已安装 **LSPosed**（支持 libxposed API 102）
- Android 15+（minSdk 35）
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

**4. 发布**：手动运行 `Release Build` 工作流并填写版本号（会自动 `versionCode` +1 并提交），或先改好 `versionName` 再推送 `v1.0.0` 形式的标签。

> 只开发 / 只构建 Debug 时无需上述配置，`assembleDebug` 不受影响；未放置 `release.keystore` 或未设置环境变量时请勿运行 `assembleRelease`。

<br>

# 开发

1. 在 `:hook` 模块 `META-INF/xposed/scope.list` 中声明作用域包名（当前为 `com.android.systemui`、`com.miui.home`）。
2. 新建 `BaseLoad` 并在 `HookEntryRegistry` 中登记目标包（当前占位：`HomeLoad` → `com.miui.home`）。
3. 新建 `BaseHook` 实现具体 Hook 逻辑，必要时用 DexKit 定位成员。
4. 在 `featureSpecs()`（或自定义注册处）声明 `OptionSpec`，UI 会自动渲染对应组件；子页面功能可用 `HookSubPage` 并入功能页搜索。
5. 构建并在 LSPosed 中验证，页面标题变绿即生效。

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

本项目以 [GNU Lesser General Public License v3.0](LICENSE)（LGPL-3.0）开源。

本仓库同时包含 Apache-2.0 许可的第三方代码（自 [miuix](https://github.com/compose-miuix-ui/miuix) 等引入的文件保留其原始版权与许可声明）。按照 LGPL-3.0 的传染性要求，本项目整体以 LGPL-3.0 授权分发；衍生作品须以 LGPL-3.0 或 GPL-3.0 授权公开，并保留应用内「参考与致谢」与 Based on 标注。
