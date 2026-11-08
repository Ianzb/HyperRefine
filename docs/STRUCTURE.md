# 工程结构与开发规范

> **文档定位**
> - 负责：仓库边界、目录地图、文件级改动路线、上下文纪律、验证顺序。
> - 不负责：字段级接口与组件行为（见 [`API.md`](API.md)）、adb 授权与合规（见 [`WORKFLOW.md`](WORKFLOW.md)）、二次开发标识清单（见 [`CUSTOMIZE.md`](CUSTOMIZE.md)）。
> - 前置：先按根目录 [`AGENTS.md`](../AGENTS.md) 的任务路由选择入口；本文不重复任务索引。
>
> 本文回答两个问题：**改一个功能要动哪些文件**，以及**怎样用最小的读取范围定位问题**。
>
> 配套文档：接口与接入细节见 [`API.md`](API.md)，Rust Hook 见 [`NATIVE_HOOK.md`](NATIVE_HOOK.md)，真机 / 逆向 / 合规见 [`WORKFLOW.md`](WORKFLOW.md)，包名与二次开发清单见 [`CUSTOMIZE.md`](CUSTOMIZE.md)。
>
> 快照：`1.16.0`（`versionCode = 25`）。目录和文件数量会随版本变化，结构约定保持稳定。

## 0. 本文导航

- 找文件、确认仓库边界、理解模块依赖 → §1–4
- 改一个已有功能 / 新增开关 / 新增二级页 → §4–5
- 查工程规范、命名、可见性、Hook 侧约束 → §6
- 查具体符号、缩小读取范围 → §7
- 构建后验证、改动前自检 → §8–9
- 改本文的时机 → §10

字段名、函数签名、组件参数不在本文展开；以 [`API.md`](API.md) 为准。

## 1. 仓库边界与上下文控制

仓库里真正需要阅读的源码只有两个模块，但工作区还存在大量**生成物和本地逆向产物**。它们不应进入上下文。

| 路径 | 是否提交 | 用途 | 阅读建议 |
|---|---|---|---|
| `app/` | 是 | Compose UI、配置系统、服务与状态 | 按功能页定点读取 |
| `hook/` | 是 | libxposed 入口、Hook 封装、各领域 Hook、Rust 原生库 | 按包定点读取 |
| `docs/` | 是 | 权威开发文档 | 先读对应主题，不要通读全部 |
| `gradle/` | 是 | Version Catalog 与 Wrapper | 只在改依赖时读 |
| `build/`、`app/build/`、`hook/build/` | 否 | Gradle 产物 | **不要读**；需要时重新构建 |

工作区还可能包含不提交的构建产物、抓包 / 截图、逆向报告和本机 SDK / 签名配置。这些内容都不应进入上下文；具体边界与存放约定见 [`WORKFLOW.md`](WORKFLOW.md)。

### 1.1 为什么不能全读

工作区可能包含构建产物、抓包、逆向报告等本地内容，体积通常远大于 Git 跟踪的源码。**源码规模不等于工作区体积**，所以定位应依赖 `git ls-files`、按路径读取和关键词搜索，而不是递归列目录。

### 1.2 最小读取原则

1. 先确定“功能垂直切片”：配置键 → Hook 类 → Load → UI 声明 → 文案 → 变更记录。
2. 用 `git ls-files` 限定 Git 跟踪的源码，避免把 `build/` 和本地逆向目录扫进来。
3. 先 `grep` 找符号，再按行号 `read` 前后 40–80 行；不要从第 1 行读到文件尾。
4. 看差异先 `git diff --stat`，再看 `git diff -- <path>`；不要对全仓库打印 diff。
5. 构建 / 测试输出重定向到临时文件，只读尾部错误段。
6. 一次性任务结束后，把“结论、文件、未决项”写进任务记录或本文对应章节，避免下次重新探索。

## 2. 模块关系

```text
┌─────────────────────────────┐
│ :app                        │
│ Compose UI / 配置 / 服务状态 │
│  └─ 依赖 :hook 共享键与类    │
└──────────────┬──────────────┘
               │ implementation(project(":hook"))
┌──────────────▼──────────────┐
│ :hook                       │
│ libxposed 入口 / Hook 封装  │
│ 领域 Hook / Native / DexKit │
└──────────────┬──────────────┘
               │ libxposed API 102
        ┌──────▼──────┐
        │ 目标进程     │
        │ SystemUI /  │
        │ system_server│
        └─────────────┘
```

- `:app` 是普通 Android 应用，负责界面、配置持久化、作用域申请、Hook 状态展示和重启入口。
- `:hook` 是 Android Library，只包含 Hook 运行时、配置键常量、设备/版本规则和原生库；**不要**在 `:hook` 里引用 `:app` 的 Compose、`R` 或页面类。
- 两侧共享的配置键放在 `:hook` 的 `*Keys.kt` 或 Hook 的 `companion object`，`:app` 直接引用同一常量，避免字符串漂移。

## 3. 目录地图

### 3.1 `:app`（`app/src/main/java/cn/ianzb/hyperrefine/`）

| 路径 | 职责 | 关键文件 |
|---|---|---|
| `prefs/` | 配置声明、持久化、迁移、导入导出 | `OptionSpec.kt`、`OptionType.kt`、`OptionRegistry.kt`、`ConfigState.kt`、`PrefsStore.kt`、`PrefsMigrations.kt`、`ConfigBackup.kt` |
| `ui/screen/features/` | 各功能域页面与二级页 | `FeaturesPage.kt`（顶层功能注册）、`SystemUiActivity.kt`、`WindowManagerActivity.kt`、`ImeActivity.kt`、`IconSettingsActivity.kt` 等 |
| `ui/component/pref/` | 通用配置组件与 `HookOptionsPage` | `HookOptionsPage.kt`、`HookTextCard.kt` 等 |
| `ui/screen/subpage/` | 二级页基类与脚手架 | `BaseSubPageActivity.kt`、`SubPageScaffold` |
| `ui/screen/{home,settings,about,scope,safemode}/` | 主页、设置、关于、作用域、安全模式 | 按页面定位 |
| `ui/theme/`、`ui/icons/`、`ui/util/` | 主题、图标、模糊等 UI 工具 | 只在改视觉基础设施时读 |
| `xposed/` | 模块服务、Hook 状态、Root、重启、安全模式读取 | `XposedServiceManager.kt`、`HookStatus*.kt`、`AppRestarter.kt`、`RootHelper.kt` |
| `util/`、`experimental/` | 通用工具、实验性功能 | 按引用定位 |

`FeaturesPage.kt` 是顶层功能清单的单一入口：`featureSpecs()` 声明 `OptionSpec`，页面再把 spec 交给 `HookOptionsPage` 渲染。**新增普通开关优先改这里，不要新建一套平行注册表。**

### 3.2 `:hook`（`hook/src/main/java/cn/ianzb/hyperrefine/hook/`）

| 路径 | 职责 |
|---|---|
| `base/` | `BaseHook`、`BaseLoad`、`HookEntryRegistry` 等生命周期基类 |
| `xposed/` | `XposedEntry`、`HookHelper` 等 libxposed 适配层 |
| `prefs/` | `HookPrefs`，Hook 侧读取配置的唯一入口 |
| `dexkit/` | DexKit 查询与缓存 |
| `nativehook/` | Rust 原生 Hook 的 Java 侧封装 |
| `device/` | `DeviceType`、设备白名单与覆盖逻辑 |
| `rule/` | 版本 / 条件筛选 |
| `safemode/` | 安全模式兜底 |
| `systemui/` | 状态栏、控制中心、锁屏、通知、音量等 SystemUI Hook |
| `systemserver/` | `system_server` 侧 Hook |
| `connect/`、`browser/`、`passkey/`、`home/`、`miuix/`、`misound/`、`notification/`、`securitycenter/`、`sogou/`、`status/`、`volume/`、`weather/`、`assistant/`、`voiceassist/`、`packageinstaller/` | 按目标应用 / 领域分包 |

领域包内常见命名：

- `*Keys.kt`：配置键常量，App 与 Hook 共用。
- `*Hook.kt`：一个功能单元，继承 `BaseHook`，实现 `init()`。
- `*Runtime.kt`：跨 Hook 共享的运行时状态 / 视图操作。
- `*Load.kt`：把一个或多个 Hook 挂到目标包的生命周期上。
- `*Config.kt`：纯数据、枚举、默认值。

### 3.3 资源与模块元数据

| 路径 | 用途 |
|---|---|
| `app/src/main/res/values/strings.xml` | 默认中文文案 |
| `app/src/main/res/values-en/strings.xml` | 英文文案；新增中文键必须同步 |
| `hook/src/main/resources/META-INF/xposed/java_init.list` | Java 入口，当前为 `cn.ianzb.hyperrefine.hook.xposed.XposedEntry` |
| `hook/src/main/resources/META-INF/xposed/module.prop` | `minApiVersion=102`、`targetApiVersion=102`、`staticScope=false` |
| `hook/src/main/resources/META-INF/xposed/scope.list` | 允许的作用域包（当前 50 个），新增目标包必须同步 |
| `hook/src/main/resources/META-INF/xposed/native_init.list` | 原生库名，当前 `libnativehook.so` |
| `hook/src/main/jniLibs/arm64-v8a/` | 预编译原生库 |
| `hook/src/main/rust/nativehook/` | Rust 源码与 `Cargo.toml` |

`AndroidManifest.xml` 中，功能页 Activity 默认不导出；只有 `MainActivity`（模块设置入口）和启动图标 alias 对外。新增二级页必须同时注册 Activity 并在父页登记，详见 §5.3。

## 4. 一条功能的完整链路

以“新增一个布尔开关 + SystemUI Hook”为例：

```text
OptionSpec(key = X)                 BaseHook(key = X)
        │                                  │
        ▼                                  ▼
FeaturesPage / 二级页              BaseLoad.targetPackages
        │                                  │
        ▼                                  ▼
ConfigState / PrefsStore  ───────►  HookPrefs.getBoolean(X)
        │                                  │
        ▼                                  ▼
HookOptionsPage 渲染              目标进程 BaseHook.init()
```

必须按顺序检查的 7 个连接点：

1. **配置键**：放在 `:hook` 的 `*Keys.kt` 或 `companion object`，小写下划线，不带 `prefs_key_` 前缀。
2. **Hook 类**：继承 `BaseHook`，`key` 与 `OptionSpec.key` 完全一致；`init()` 内完成 hook 注册。
3. **Load**：在对应 `BaseLoad.onPackageLoaded` 中用 `HookPrefs.getBoolean(key, false)` 决定是否初始化，并加入 `targetPackages`。
4. **注册**：`HookEntryRegistry` 登记该 Load；`system_server` 目标还要进入 `systemServerLoads()` 的过滤范围。
5. **作用域**：目标包写入 `scope.list`；原生库写入 `native_init.list`。
6. **UI**：`OptionSpec` 加入 `featureSpecs()` 或领域页；需要二级页时按 §5.3 登记。
7. **文案与记录**：中英文 `strings.xml`、`changelog.md`；涉及许可证 / 致谢时同步 `LicensePage.kt` 等。

> `OptionSpec.key` 与 `BaseHook.key` 不一致，或 `scope.list` 漏包，是最常见的“开关无效”原因。先查这两处，再查 Hook 逻辑。

## 5. 文件级改动路线

> 本节只回答“改哪些文件、按什么顺序检查”。字段、示例、注册代码和组件参数统一见 [`API.md`](API.md) §7，不在这里重复。

### 5.1 给已有功能加一个布尔开关

| 顺序 | 文件 | 动作 |
|---|---|---|
| 1 | `hook/.../<domain>/*Keys.kt` | 新增 `const val` |
| 2 | `hook/.../<domain>/*Hook.kt` | 读取 `HookPrefs.getBoolean`，在 `init()` 中分支 |
| 3 | `app/.../ui/screen/features/FeaturesPage.kt`（或领域页） | 新增 `OptionSpec(type = SWITCH)` |
| 4 | `res/values/strings.xml` + `values-en` | 标题 / 描述 |
| 5 | `changelog.md` | 当前未发布版本段落 |

如果开关依赖某个主开关，用 `OptionSpec.dependsOn = 主开关 key`，不要用 `AnimatedVisibility` 隐藏组件。

### 5.2 只改已有开关的文案 / 默认值

- 只改 `OptionSpec.titleRes` / `summaryRes` / `default*` 与两份 `strings.xml`。
- 改默认值时考虑 `PrefsMigrations.kt`：已安装用户不会被新默认值覆盖，需要迁移时写显式迁移。
- 不要改 `key` 来表示“新默认值”，那会让老配置变成另一项。

### 5.3 新增一个二级功能页

| 顺序 | 文件 | 动作 |
|---|---|---|
| 1 | `app/.../ui/screen/features/<Domain>Activity.kt` | 继承 `BaseSubPageActivity`，用 `HookOptionsPage(sections = ...)` |
| 2 | `app/src/main/AndroidManifest.xml` | 注册 Activity，`exported=false` |
| 3 | 父页 `FeaturesPage.kt` / 领域页 | `HookSubPage(..., subPages = listOf(...))` 登记，供搜索直达 |
| 4 | `res/values/strings.xml` + `values-en` | 页面标题、分区标题 |
| 5 | `FeaturesPageView` | 确认一级入口能打开该页 |

### 5.4 新增一个目标包 / 新领域

1. 在 `hook/.../<domain>/` 新建 `*Keys.kt`、`*Hook.kt`、`*Load.kt`。
2. `HookEntryRegistry` 注册 Load，`targetPackages` 写目标包。
3. `scope.list` 加同一包名；如果是原生库，`native_init.list` 加库名。
4. App 侧建领域页或复用现有页；`featureSpecs()` 加 `targetPackages`。
5. 更新本文 §3 的目录表（新增领域包时）。

### 5.5 新增 Native Hook

按 [`NATIVE_HOOK.md`](NATIVE_HOOK.md) 执行；本仓库的固定接线点是：

- `hook/src/main/rust/nativehook/` 写 `#[no_mangle] pub extern "C"` 入口。
- `cargo ndk -t arm64-v8a -o hook/src/main/jniLibs build --release`。
- `native_init.list` 写 `.so` 文件名。
- Java 侧 `BaseNativeHook` / `initNativeHook(...)`，并确保幂等。

### 5.6 改版本 / 写变更记录

- `app/build.gradle.kts`：`versionCode` 自增，`versionName` 与 `changelog.md` 标题一致。
- `changelog.md`：新内容写在**未发布**版本段，不要写进已发布段。
- Release Notes 直接取对应版本段落；发布前确认版本段没有空段 / 错位。

## 6. 工程规范

### 6.1 命名与分包

- 按**功能领域**分包，不按 `utils/`、`helpers/` 堆杂项。
- Hook 类名用 `<Feature>Hook`，Load 用 `<Target>Load`，键常量集中在 `*Keys.kt`。
- 配置键使用小写下划线；不要手写 `prefs_key_` 前缀，存储层会自动加。
- 一个 Hook 只做一件事；跨 Hook 的共享逻辑放 `*Runtime.kt`，不要互相直接调用 `init()`。

### 6.2 配置与可见性

- `OptionSpec` 是 UI 的单一事实来源；不要在页面里硬编码标题、默认值或 key。
- 依赖 / 设备不满足时**禁用灰显，不隐藏**：用 `dependsOn`、`dependsOnValue(s)`、`rememberOptionEnabled(spec)`、`deviceScope`。
- `masterKey` 只控制启用 / 禁用，不控制显隐；需要显隐用 `dependsOn`。
- 滑块的标题与数值同行时设 `sliderValueLabelRes`。
- 分区标题只传 `titleRes`；描述性内容放二级页，不塞进一级卡片 summary。

### 6.3 Hook 侧

- 所有 Hook 继承 `BaseHook`，在 `init()` 内完成注册；不要在构造函数里做重活。
- 读取配置只走 `HookPrefs`，不要在 Hook 里直接读 `SharedPreferences`。
- 反射 / 混淆成员解析失败必须 fail-closed，不要猜测字段或强行调用。
- 视觉 / 输入法 / 窗口材质类改动优先修改官方状态谓词与渲染入口；不要新增悬浮窗、`RenderEffect` 或输入监听补效果。
- 目标进程重启：SystemUI 用 `AppRestarter.restartSystemUi()`；`system` / `android` / `system_server` 二次确认后重启设备。
- 需要“已生效”状态时设 `OptionSpec.showStatus = true`，由目标进程通过 `HookStatusReporter` 回报。

### 6.4 UI 与资源

- 组件出现 / 隐藏统一用 `MiuixExpandSpec`（`expandVertically` / `shrinkVertically`），不要默认动画参数或自定时长。
- 新增文案必须同时写 `values/strings.xml` 与 `values-en/strings.xml`；不要硬编码中文。
- 二级页继承 `BaseSubPageActivity`，在 Manifest 注册，并在父页 `HookSubPage(..., subPages = ...)` 登记；多级页面逐层登记。
- 二级页自身不放搜索栏；搜索由父级注册表统一处理。

### 6.5 文档与合规

- 改功能行为时同步 `changelog.md`；改接口 / 接入方式时同步 `docs/API.md`；改原生 Hook 时同步 `docs/NATIVE_HOOK.md`。
- 逆向产物只放 [`WORKFLOW.md`](WORKFLOW.md) 约定的本地目录，永不提交、不 `git add -f`。
- 第三方代码 / 资源先查许可证；无许可证的项目只借鉴思路并在 `LicensePage.kt` 致谢，不复制代码或文案。
- 真机 adb 操作逐次获得授权，默认只读；不要用屏幕点击 / 滑动 / 截图代替日志验证。

## 7. 上下文预算操作手册

### 7.1 定位顺序

```text
1. git ls-files | grep <关键词>          # 先限定 Git 跟踪源码
2. grep -RIn --include='*.kt' '<符号>' app hook
3. 读命中行前后 40–80 行
4. 沿“配置键 → Hook → Load → UI”一条链走完
5. 只改这条链上的文件，不做全仓库重构
```

### 7.2 搜索关键词对照

| 要查 | 搜索 |
|---|---|
| 配置键定义 | `grep -RIn --include='*.kt' 'const val .*KEY' app hook` |
| 某个 key 的使用 | `grep -RIn --include='*.kt' 'the_key_name' app hook` |
| Hook 注册 | `grep -RIn --include='*.kt' 'class .*Load' hook` / `HookEntryRegistry` |
| 页面入口 | `grep -RIn --include='*.kt' 'HookSubPage' app` |
| 字符串资源 | `grep -RIn 'name="the_string"' app/src/main/res` |
| 构建产物 / 版本 | `grep -n 'versionCode\\|versionName' app/build.gradle.kts` |

### 7.3 不要做的事

- 不要 `Get-ChildItem -Recurse` / `find .` 扫仓库根；本地逆向目录和 `build/` 会把上下文撑爆。
- 不要整文件读 `strings.xml`、`FeaturesPage.kt`、`changelog.md`；用 `grep -n` 定位段落。
- 不要读 `.apk`、`.so`、`.dex`、`.jar`、`build/` 下的中间产物；需要时重新构建。
- 不要把完整 Gradle / 测试日志贴回上下文；只保留 `FAILURE`、错误文件和首个 stack。
- 不要同时打开多个领域包“看看有没有关系”；先沿一条链验证，再扩展。

## 8. 构建与验证

构建命令、JDK 要求与产物路径以 [`README.md`](../README.md)「构建」为准；本文只保留验证顺序。

验证顺序：

1. 构建通过，确认没有新增 `e:` 编译错误。
2. 安装 APK（需用户授权），在 LSPosed 中启用模块并勾选作用域。
3. 打开开关，重启目标进程（SystemUI / 目标应用 / 设备）。
4. 卡片显示「已生效」；必要时只读 `adb logcat -s <TAG>`。
5. 涉及 `system_server` 的 Hook 必须整机重启后验证。

> 长驻命令不要用管道截断输出；重定向到临时文件后只读尾部。不要用屏幕点击 / 滑动 / 截图代替日志验证。

## 9. 改动前检查表

- [ ] 配置键在 `:hook` 定义，`OptionSpec.key == BaseHook.key`
- [ ] 目标包同时出现在 `BaseLoad.targetPackages` 与 `scope.list`
- [ ] 新 Load 已加入 `HookEntryRegistry`，`system` 目标进入 `systemServerLoads()`
- [ ] 依赖 / 设备条件用 `dependsOn` / `deviceScope` 灰显，没有条件隐藏
- [ ] 新文案同时写中文与英文资源
- [ ] 二级页已在 Manifest 注册并在父页 `HookSubPage` 登记
- [ ] 动画使用 `MiuixExpandSpec`
- [ ] 反射 / 混淆成员失败时 fail-closed
- [ ] `changelog.md` 写在未发布版本段
- [ ] 构建通过，真机验证路径明确

## 10. 本文维护规则

- 新增**领域包**、**基础组件**、**新的接入层**时更新 §3 / §4。
- 只改具体功能实现时，不需要改本文；把细节写进对应 `docs/features/` 或代码注释。
- 本文只做“地图与规则”，不复制 `API.md` 的接口签名；接口变化以 `API.md` 为准。
- 发现某条路径经常被误读，把它加入 §1.2 或 §7.3 的“不要读”清单。
