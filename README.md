<!--suppress HtmlDeprecatedAttribute, HtmlDeprecatedTag -->
<div align="center">

<img src="docs/icon.png" width="140" alt="HyperRefine" />

# HyperRefine

### 基于 Miuix 的 LSPosed 模块

**开发先读：[开发文档索引](AGENTS.md)** | [工程结构与规范](docs/STRUCTURE.md) | [接口文档](docs/API.md) | [原生 Hook 指南](docs/NATIVE_HOOK.md) | [二次开发指南](docs/CUSTOMIZE.md) | [模块开发工作流](docs/WORKFLOW.md) | [更新日志](changelog.md) | [Telegram 群组](https://t.me/HyperRefine)

> **文档定位**：本文只负责安装、构建、发布、CI 与签名。开发任务路由从 [`AGENTS.md`](AGENTS.md) 开始；接口、仓库地图、合规分别见 API / STRUCTURE / WORKFLOW。

![Platform](https://img.shields.io/badge/Platform-Android-green)
![LSPosed](https://img.shields.io/badge/LSPosed-libxposed%20102-blue)
[![爱发电](https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E8%B5%9E%E5%8A%A9%E6%94%AF%E6%8C%81-946ce6)](https://afdian.com/a/Ianzb)

</div>

**HyperRefine** 是一个 **LSPosed 模块**项目，基于 [libxposed API 102](https://libxposed.github.io/api/index-all.html) 与 [Miuix](https://github.com/compose-miuix-ui/miuix) Compose 组件库构建，使用 [MiuixGuiTemplate](https://github.com/Ianzb/MiuixGuiTemplate) 脚手架创建，内置完整的 Hook 二次封装接口与可复用 UI 组件，当前包含百种hook功能，不仅合并了一些实用小型模块的功能，还添加了很多原创新功能。

> **开发者入口**：不要从 `docs/` 目录从头翻文档。先看 [开发文档索引](AGENTS.md)，按任务跳到对应文档；仓库目录与多文件改动见 [工程结构与规范](docs/STRUCTURE.md)。

<br>

# 功能

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

- 已安装 **LSPosed**（支持 libxposed API 102，Native Hook等现代特性）
- Android 17+（minSdk 35）
- 所有Hook功能默认仅支持 **HyperOS 4（Android 17）** 系统
- 需要给予应用 **Root 权限**

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

- **`.github/workflows/ci.yml`** — 推送到 `master`（改动 `app/**`、`hook/**`、Gradle 文件）或提交 PR 时构建 Debug APK，上传为 Actions Artifacts（保留 7 天）；配置 Telegram 后可自动推送到测试版分区。
- **`.github/workflows/release.yml`** — 推送 `v*.*.*` 标签或手动触发时构建**签名** Release APK，发布到 GitHub Release（Release Notes 取自 `changelog.md` 对应版本段落）并上传 Artifact（保留 30 天）；配置 Telegram 后可自动推送到正式版分区。

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

开发入口是 [`AGENTS.md`](AGENTS.md)；本文只给最短路径，不重复接口与接入细节。

1. 第一次接触仓库：先读 [`docs/STRUCTURE.md`](docs/STRUCTURE.md) §1–4，按目录地图定位文件。
2. 改一个 Hook / 配置项：按 [`AGENTS.md`](AGENTS.md) 的任务路由进入 [`docs/API.md`](docs/API.md) §7，再顺着配置键 → Hook → Load → UI 改。
3. 构建后在 LSPosed 中启用模块、勾选作用域并重启目标进程；`system_server` 目标需整机重启。

> 需要修改的完整标识 / 图标 / 元数据清单见 [二次开发指南](docs/CUSTOMIZE.md)。
>
> 真机 `adb` 授权、逆向产物与第三方合规见 [模块开发工作流](docs/WORKFLOW.md)。
>
> 具体功能的行为与验证记录见 [功能档案索引](docs/features/README.md)。

<br>

# 第三方库

- [miuix](https://github.com/compose-miuix-ui/miuix) — HyperOS 风格 Compose UI 组件库
- [libxposed API](https://github.com/libxposed/api) — 现代 Xposed 模块 API（102）
- [DexKit](https://github.com/LuckyPray/DexKit) — Dex 解析与缓存
- [AndroidX Compose](https://developer.android.com/jetpack/compose) — 声明式 UI 框架

<br>

# 参考与致谢

HyperRefine 基于个人 Android 模块开发脚手架 MiuixGuiTemplate 创建，开发过程中参考了若干开源项目与其他模块项目，谨向相关作者与贡献者致谢。具体致谢清单统一维护在应用内「关于 → 第三方许可证与致谢」页面，文档不再逐一展开；参考或复刻第三方项目时的合规流程见 [模块开发工作流](docs/WORKFLOW.md)。

通知功能独立实现，未引入参考模块的代码、资源或原生库；参考项目的来源与许可证已登记到应用内参考项目分组。

状态栏的 Wi-Fi 活动指示器隐藏、移动网络类型隐藏与双排堆叠信号同样独立实现，参考来源统一登记在应用内致谢页；功能范围与验证见 [状态栏功能说明](docs/features/STATUSBAR_PORT.md)。

状态栏图标自定义的预览资源（`ic_stat_sys_*`）复用自 GPL-3.0 的 [XiaomiHelper / HyperHelper](https://github.com/HowieHChen/XiaomiHelper)，原始版权与许可见 [资源说明](docs/features/THIRD_PARTY_ICONS.md)；功能与交互为独立实现。

<br>

# 赞助支持

如果 HyperRefine 对你有帮助，欢迎通过 [爱发电](https://afdian.com/a/Ianzb) 赞助支持作者，你的支持是项目持续维护与更新的动力。

<br>

# 许可证

本项目以 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）开源。

本仓库同时包含 Apache-2.0 许可的第三方代码（自 [miuix](https://github.com/compose-miuix-ui/miuix) 等引入的文件保留其原始版权与许可声明）。AGPL-3.0 与 GPL-3.0 兼容（AGPL-3.0 §13）：参考或移植 GPL-3.0 项目（如 [SoundMan](https://github.com/killerprojecte/SoundMan)）时，本项目整体以 AGPL-3.0 授权分发；衍生作品须以 AGPL-3.0 授权公开，并保留应用内“参考与致谢”。
