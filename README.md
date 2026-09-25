<div align="center">

# HyperRefine

### 基于 Miuix 的 LSPosed 模块

[接口文档](docs/API.md) | [原生 Hook 指南](docs/NATIVE_HOOK.md) | [二次开发指南](docs/CUSTOMIZE.md) | [模块开发工作流](docs/WORKFLOW.md) | [更新日志](changelog.md)

![Platform](https://img.shields.io/badge/Platform-Android-green)
![LSPosed](https://img.shields.io/badge/LSPosed-libxposed%20102-blue)

</div>

**HyperRefine** 是一个 **LSPosed 模块**项目，基于 [libxposed API 102](https://libxposed.github.io/api/index-all.html) 与 [Miuix](https://github.com/compose-miuix-ui/miuix) Compose 组件库构建，使用 [MiuixGuiTemplate](https://github.com/your-name/MiuixGuiTemplate) 脚手架（`Based on MiuixGuiTemplate 0.4.0`）创建，提供完整的 Hook 二次封装接口与可复用 UI 组件。当前 Hook 目标为**系统桌面**（`com.miui.home`）。

<br>

# 功能

- **Hook 封装** — `hookBefore` / `hookAfter` / `hookReplace` / `intercept` / `findAndHook*` / `hookAll*` / `hookClassInitializer` / `invokeOriginal`，统一句柄管理
- **原生 Hook 封装** — `NativeHookHelper` / `BaseNativeHook` / `BaseLoad.initNativeHook`，与 JavaHook 对称的一键接入（声明、加载、开关、状态、热重载、安全兜底）；面向 Rust 应用（`flutter_rust_bridge` / 纯 Rust 库），详见[原生 Hook 指南](docs/NATIVE_HOOK.md)
- **版本 / 设备筛选** — 统一的 `HookVersionGate` 与 `deviceScope`：版本支持 `>` `<`、多重规则（AND/OR），可按 Android / HyperOS / MIUI / 应用版本；设备支持手机 / 平板 / 折叠屏区分，默认各设备通用，并可在设置中手动覆盖当前设备类型
- **安全模式** — 设置页「模块」分区声明安全模式状态并提供入口，二级页逐应用开关安全模式并查看崩溃计数
- **热重载适配** — 基于 libxposed 102 的热重载机制，自动保存状态并在新代码代次重装 hook
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
git clone <your-repo-url>
cd HyperRefine

# 设置 JDK 17+ 和 Android SDK
# 编辑 local.properties 指向你的 SDK 路径

# 构建 Debug APK
./gradlew assembleDebug

# APK 输出位置
# app/build/outputs/apk/debug/app-debug.apk
```

<br>

# 开发

1. 在 `:hook` 模块 `META-INF/xposed/scope.list` 中声明作用域包名（当前为 `com.android.systemui`、`com.miui.home`）。
2. 新建 `BaseLoad` 并在 `HookEntryRegistry` 中登记目标包（当前占位：`HomeLoad` → `com.miui.home`）。
3. 新建 `BaseHook` 实现具体 Hook 逻辑，必要时用 DexKit 定位成员。
4. 在 `exampleSpecs()`（或自定义注册处）声明 `OptionSpec`，UI 会自动渲染对应组件。
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

# Based on 约定

按脚手架约定，本项目在此保留如下标注（版本号 = 所依据的脚手架版本）：

```text
Based on MiuixGuiTemplate 0.4.0
```

基于本项目的衍生项目同样**须**在 `README.md` 与应用内「关于」页保留形如 `Based on MiuixGuiTemplate <版本号>` 的文本，以便后续同步脚手架的修复与改进。该约定连同开源协议义务已汇总为一张核对清单，见[二次开发指南 · 开源协议与致谢](docs/CUSTOMIZE.md#11-开源协议与致谢必读)。

<br>

# 许可证

本项目以 [GNU Lesser General Public License v3.0](LICENSE)（LGPL-3.0）开源。

本仓库同时包含 Apache-2.0 许可的第三方代码（自 [miuix](https://github.com/compose-miuix-ui/miuix) 等引入的文件保留其原始版权与许可声明）。按照 LGPL-3.0 的传染性要求，本项目整体以 LGPL-3.0 授权分发；衍生作品须以 LGPL-3.0 或 GPL-3.0 授权公开，并保留上述「参考与致谢」与 Based on 标注。
