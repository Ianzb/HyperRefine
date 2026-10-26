# HyperRefine 接口文档

本文档描述模板对外暴露的全部接口，按模块划分：Hook 封装、DexKit 缓存、配置系统、服务与状态、UI 组件、二级页面模板，以及接入步骤。

- 模块结构：`:app`（UI + 服务绑定 + 配置）与 `:hook`（libxposed 入口 + Hook 封装 + 原生 Hook 封装 + DexKit）
- 底层：`io.github.libxposed:api:102.0.0`（compileOnly）、`io.github.libxposed:service:102.0.0`、`org.luckypray:dexkit:2.2.0`
- UI：`top.yukonga.miuix.kmp`

---

## 目录

1. [Hook 封装（`:hook`）](#1-hook-封装hook)
2. [DexKit 缓存（`:hook`）](#2-dexkit-缓存hook)
3. [配置系统（`:app`）](#3-配置系统app)
4. [服务与状态（`:app`）](#4-服务与状态app)
5. [UI 组件（`:app`）](#5-ui-组件app)
6. [二级页面模板](#6-二级页面模板)
7. [接入步骤](#7-接入步骤)
8. [关键文件索引](#8-关键文件索引)

---

## 1. Hook 封装（`:hook`）

包名：`cn.ianzb.hyperrefine.hook`

模板把 Hook 分为两类，命名与落点严格区分：

| 类别 | 作用对象 | 入口 | 封装 | 声明文件 |
|---|---|---|---|---|
| **JavaHook** | ART 方法调用 | `XposedModule` | `HookHelper` + `BaseHook` | `META-INF/xposed/java_init.list` |
| **NativeHook** | 机器码 / 符号 / 函数表 | `native_init` | `NativeHookHelper` + `BaseNativeHook` | `META-INF/xposed/native_init.list` |

两者共用同一套「开关配置、安全模式、版本筛选、设备筛选」链路；区别仅在于 Hook 代码写在 Java/Kotlin 还是原生库。选择依据见 [原生 Hook 指南 · 两种 Hook 的区别](NATIVE_HOOK.md#0-两种-hook-的区别)。

### 1.1 `HookHelper`

`object`，对 libxposed `XposedInterface` 的二次封装。所有创建的 `HookHandle` 会自动登记到 `HookRegistry`。

```kotlin
object HookHelper {
    fun init(module: XposedInterface)            // 由 XposedEntry 调用，勿手动调用
    fun log(message: String)
    fun log(message: String, throwable: Throwable)

    // 基础挂载
    fun intercept(
        executable: Executable,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        mode: ExceptionMode = ExceptionMode.DEFAULT,
        hooker: Hooker,
    ): HookHandle

    fun hookBefore(executable: Executable, priority: Int = XposedInterface.PRIORITY_DEFAULT, callback: (HookParam) -> Unit): HookHandle
    fun hookAfter(executable: Executable, priority: Int = XposedInterface.PRIORITY_DEFAULT, callback: (HookParam) -> Unit): HookHandle
    fun hookReplace(executable: Executable, priority: Int = XposedInterface.PRIORITY_DEFAULT, callback: (HookParam) -> Any?): HookHandle
    fun hookClassInitializer(clazz: Class<*>, priority: Int = XposedInterface.PRIORITY_DEFAULT, callback: (HookParam) -> Unit): HookHandle

    // 查找并挂载
    fun findAndHookMethod(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>, callback: (HookParam) -> Unit): HookHandle
    fun findAndHookMethodAfter(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>, callback: (HookParam) -> Unit): HookHandle
    fun findAndHookMethodReplace(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>, callback: (HookParam) -> Any?): HookHandle
    fun findAndHookMethod(className: String, classLoader: ClassLoader?, methodName: String, vararg parameterTypes: Class<*>, callback: (HookParam) -> Unit): HookHandle
    fun findAndHookConstructor(clazz: Class<*>, vararg parameterTypes: Class<*>, callback: (HookParam) -> Unit): HookHandle
    fun findAndHookConstructorAfter(clazz: Class<*>, vararg parameterTypes: Class<*>, callback: (HookParam) -> Unit): HookHandle
    fun hookAllMethods(clazz: Class<*>, methodName: String, callback: (HookParam) -> Unit): List<HookHandle>
    fun hookAllConstructors(clazz: Class<*>, callback: (HookParam) -> Unit): List<HookHandle>

    // 调用原方法
    fun invokeOriginal(method: Method, thisObject: Any?, vararg args: Any?): Any?
    fun invokeOriginal(constructor: Constructor<*>, vararg args: Any?): Any?
}
```

### 1.2 `HookParam`

```kotlin
class HookParam(
    val executable: Any?,       // Method / Constructor / Class（<clinit>）
    val thisObject: Any?,
    val args: List<Any?>,
) {
    var result: Any?
    var hasResult: Boolean
    fun setResultValue(value: Any?)   // 在 hookBefore 中设置可跳过原方法
}
```

### 1.3 `HookRegistry`

```kotlin
object HookRegistry {
    fun register(handle: HookHandle)
    fun unhookAll()     // 卸载时统一调用
    fun size(): Int
}
```

### 1.4 `Reflect`

常用反射工具：

```kotlin
object Reflect {
    fun findClass(name: String, classLoader: ClassLoader? = null): Class<*>
    fun findClassIfExists(name: String, classLoader: ClassLoader? = null): Class<*>?
    fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method
    fun findMethodIfExists(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method?
    fun findField(clazz: Class<*>, name: String): Field
    fun callMethod(instance: Any, name: String, vararg args: Any?): Any?
    fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any?
    fun getObjectField(instance: Any, name: String): Any?
    fun setObjectField(instance: Any, name: String, value: Any?)
    fun getStaticObjectField(clazz: Class<*>, name: String): Any?
    fun setStaticObjectField(clazz: Class<*>, name: String, value: Any?)
    fun newInstance(clazz: Class<*>, vararg args: Any?): Any
}
```

### 1.5 `BaseHook`

单条 Hook 规则基类。

```kotlin
abstract class BaseHook {
    open val tag: String            // 默认类名
    open val key: String            // 配置键 / 状态标识，默认类名
    open fun useDexKit(): Boolean = false
    open fun initDexKit(): Boolean = true
    open fun init()

    // 当前目标包信息，由 BaseLoad 在安装前注入，可在 init() 中读取（如 target.classLoader）
    protected lateinit var target: PackageTarget

    // DexKit 辅助（仅在 initDexKit 中调用）
    protected fun <T> requiredMember(key: String, finder: IDexKit): T
    protected fun <T> requiredMemberList(key: String, finder: IDexKitList): List<T>
    protected fun <T> optionalMember(key: String, finder: IDexKit): T?
    protected fun <T> optionalMemberList(key: String, finder: IDexKitList): List<T>
}
```

### 1.6 `BaseLoad`

按目标包组织的一组 Hook。

```kotlin
abstract class BaseLoad {
    abstract val targetPackages: List<String>
    protected open fun onPackageLoaded(target: PackageTarget)
    protected fun initHook(hook: BaseHook, enabled: Boolean)   // 声明本包需要的 hook
    fun onPackageReady(target: PackageTarget)                  // 由框架调用
}
```

### 1.7 `HookEntryRegistry`

目标包注册表，新增目标时在此登记。

```kotlin
object HookEntryRegistry {
    fun loadsFor(packageName: String): List<BaseLoad>
    fun all(): List<BaseLoad>
}
```

### 1.8 `PackageTarget`

```kotlin
class PackageTarget(
    val packageName: String,
    val processName: String,
    val applicationInfo: ApplicationInfo?,
    val classLoader: ClassLoader?,
    val isSystemServer: Boolean,
)
```

### 1.9 `XposedEntry`

`io.github.libxposed.api.XposedModule` 入口，声明于 `hook/src/main/resources/META-INF/xposed/java_init.list`。

生命周期：

| 回调 | 说明 |
|---|---|
| `onModuleLoaded` | 初始化 `HookHelper` / `HookPrefs` / `SafeModeManager` |
| `onPackageReady` | 按包分发 `BaseLoad.onPackageReady`（开机 / 冷启动即应用） |
| `onSystemServerStarting` | 预留 system_server 支持 |

### 1.10 原生 Hook 封装

包名：`cn.ianzb.hyperrefine.hook.nativehook`。与 [1.1 `HookHelper`](#11-hookhelper) 对称，负责把原生库「声明 / 加载 / 开关 / 状态」接进模板链路；真正的 hook 由原生库导出的 `native_init` 完成。完整原理与工作流见 [原生 Hook 指南](NATIVE_HOOK.md)。

```kotlin
data class NativeLibrarySpec(
    val libraryName: String,                 // "native_hook" 或 "libnative_hook.so" 均可
    val key: String = "native_$libraryName", // 状态 / 配置键
    val required: Boolean = true,            // 加载失败是否判定规则失败
    val description: String = "",
)

object NativeHookHelper {
    fun load(spec: NativeLibrarySpec): Boolean
    fun load(libraryName: String, key: String = libraryName, required: Boolean = true): Boolean
    fun loaded(): List<NativeLibrarySpec>
    fun isLoaded(libraryName: String): Boolean
    fun size(): Int
}

abstract class BaseNativeHook {
    abstract val libraryName: String
    open val key: String get() = "native_$libraryName"
    open val required: Boolean get() = true
    open val description: String get() = ""
    fun install(): Boolean
}
```

`BaseLoad` 新增声明入口（与 `initHook` 对称）：

```kotlin
fun initNativeHook(hook: BaseNativeHook, enabled: Boolean)
```

用法：

```kotlin
class MyLoad : BaseLoad() {
    override val targetPackages = listOf("com.example.target")
    override fun onPackageLoaded(target: PackageTarget) {
        initNativeHook(MyNativeHook(), HookPrefs.getBoolean(MyNativeHook.KEY, false))
    }
}
```

原生侧入口契约（Rust，导出 C ABI）见 `hook/src/main/rust/nativehook/src/lib.rs`；`.so` 文件名需写入 `META-INF/xposed/native_init.list`；用 `cargo-ndk` 构建，Gradle 不参与 Rust 编译。完整流程见 [原生 Hook 指南](NATIVE_HOOK.md)。

### 1.11 版本筛选（`HookVersionGate`）

包名：`cn.ianzb.hyperrefine.hook.rule`。为所有 Hook 封装（JavaHook 与 NativeHook）提供统一的版本筛选，支持 `>` `<` 比较、多重规则（AND / OR），可按 **Android / HyperOS / MIUI / 指定应用版本** 应用不同 Hook 代码。

```kotlin
// 1) 整体门禁：不满足则整条规则跳过（不安装、不记录失败）
override val versionGate = hookVersionGate {
    android { ge("35") }
    hyperOs { range("1.0", "2.0") }
    app("com.miui.home") { gt("8.01.02.7709") }
}

// 2) 版本分支：不同版本区间执行不同 Hook 代码（按声明顺序命中第一个）
override val variants = listOf(
    hookVariant("new", { app("com.miui.home") { ge("8.01.02.7709") } }) {
        // 新版本：按指令签名定位
    },
    hookVariant("legacy", { app("com.miui.home") { lt("8.01.02.7709") } }) {
        // 旧版本：按类名定位
    },
)
```

| 元素 | 说明 |
|---|---|
| `hookVersionGate(mode) { ... }` | 构建门禁；`mode` 为 `MatchMode.ALL`（默认，AND）或 `ANY`（OR） |
| `android {}` / `hyperOs {}` / `miui {}` / `app(pkg) {}` | 选择版本来源 |
| `gt/ge/lt/le/eq/ne(value)` / `range(min, max)` | 比较运算（`range` 为闭区间） |
| `VersionRule(source, op, value, value2, packageName)` | 单条约束，可 `builder.rule(...)` 直接加入 |
| `hookVariant(name, mode) { gate } { body }` | 版本分支；`variants` 非空但全部不匹配时抛出 `HookSkippedException` |

- **JavaHook**：`BaseHook.versionGate` / `BaseHook.variants`，由 `BaseLoad` 在安装前判定；跳过时状态留空（UI 视为未应用）。
- **NativeHook**：`BaseNativeHook.versionGate`，不满足时不会把原生库载入目标进程。
- 版本值采用宽松比较（数字段按数值、文本段按字典序），可正确处理 `8.01.02.7722-260904-...-R`、`OS4.0.0.33.XPMCNXM`、`35` 等形态。
- 应用版本通过当前进程的 `PackageManager` 尽力解析；极早期取不到时按空值处理（此时 `app {}` 规则不命中）。

### 1.12 设备筛选（`DeviceScope`）

包名：`cn.ianzb.hyperrefine.hook.device`。所有 Hook 封装默认**各设备通用**；需要按设备区分时声明 `deviceScope`（白名单），不匹配即跳过该规则（不安装、不记录失败）。

```kotlin
// JavaHook
override val deviceScope = setOf(DeviceType.PAD, DeviceType.FOLD)

// NativeHook
override val deviceScope = setOf(DeviceType.PAD)

// 版本分支里也可按设备区分
override val variants = listOf(
    hookVariant("pad", setOf(DeviceType.PAD), gate = { app("com.miui.home") { ge("8.01.02.7709") } }) { /* 平板 */ },
    hookVariant("others", { app("com.miui.home") { ge("8.01.02.7709") } }) { /* 其余设备 */ },
)
```

| 元素 | 说明 |
|---|---|
| `DeviceType.PHONE` / `PAD` / `FOLD` | 手机 / 平板 / 折叠屏 |
| `BaseHook.deviceScope` / `BaseNativeHook.deviceScope` | 设备白名单；null / 空 = 各设备通用 |
| `hookVariant(name, devices) { gate } { body }` | 按设备（可叠加版本）选择分支 |
| `DeviceContext.current` | 运行期设备上下文（`type`、`isFoldable`、`isFlip`、`isTablet`、`smallestWidthDp` 等） |

**判定方式**（思路参考 HyperCeiler，未复用其代码）：

- 平板：`miui.os.Build.IS_TABLET`，回退 `Resources.getSystem().configuration.smallestScreenWidthDp >= 600`；
- 折叠屏：`persist.sys.multi_display_type` 低 4 位（2 后屏 / 3 内折 / 4 翻盖 / 5 外折），回退 `persist.sys.muiltdisplay_type`（1 后屏 / 2 内折）；
- 归类优先级：MIUI 平板标记 → 折叠屏 → 宽度 ≥ 600dp → 手机（避免展开态折叠屏被误判为平板）。

hook 代码内需要按设备分支时，直接读 `DeviceContext.current.type`。

**手动覆盖**：设置页「模块 → 当前设备类型」提供 `默认（<模块判定>）` / `手机` / `平板` / `折叠屏` 四项。覆盖值存于配置键 `device_type`（`auto` / `phone` / `pad` / `fold`），经 `PrefsStore` 同步到远程偏好，hook 进程由 `DeviceContext.current` 读取生效；`默认` 即使用 `DeviceContext.detected`。修改后需重启目标进程。

### 1.13 设备互联功能（`connect` 包）

包名：`cn.ianzb.hyperrefine.hook.connect`。移植自 [HyperConnectToolkit](https://github.com/silverpoetry/HyperConnectToolkit)（Apache-2.0，见应用内「第三方许可」页），目标为 `com.milink.service` 与 `com.xiaomi.mirror`。

| 类 | 目标进程 | 作用 |
|---|---|---|
| `MiLinkLoad` | `com.milink.service`（按进程路由） | 分发以下三条 |
| `MiLinkDiscoveryHook` | `com.milink.service:ui` | 本机发现前置检查的 Cast 位深度绕过（仅平板） |
| `MiLinkNotificationFlowHook` | `com.milink.crossdeviceservice` | 通知点击桥接为原生 `PIN_APP`，并吞掉成对的重复被动串流命令（仅平板） |
| `MiLinkMultiChannelHook` | `com.milink.service:core` | 通道上限 1 → 2 + `HostNotBound(215)` 官方绑定修复 |
| `XiaomiMirrorLoad` / `PortraitStreamingHook` | `com.xiaomi.mirror` | 首包尺寸纠正 + sink Activity 方向放开（仅平板） |

- 纯判定逻辑抽到 `CompatibilityPolicy`，结构适配器为 `ChannelLimitPolicy` / `HostBindingRepair` / `NotificationClickSession`，便于独立测试。
- 各功能为独立 `BaseHook`：反射解析失败即抛错、不安装、不回报状态（fail-closed 失败隔离）。
- 配置键统一定义在 `ConnectKeys`（与 App 侧 `OptionSpec.key` 共用）。

### 1.14 输入法 / 窗口材质类 Hook 规范

适用于自带窗口渲染器的输入法（如 `com.xiaomi.type`）、系统界面材质、圆角与模糊类需求。
首要原则：**复用官方状态谓词和官方绘制入口，不复制渲染层**。自己往 Window 上叠一个
`View`、`RenderEffect` 或悬浮窗，通常会与官方层级、输入法 Insets、窗口动画互相打架。

以超级小爱输入法 `0.2.1246` 的 `jb.f0`（HyperMaterialHelper）为例：

| 成员 | 含义 |
|---|---|
| `w` | 当前输入框所属包名 |
| `x` | 包名 → 材质版本允许表 |
| `y` | 包名 → 材质版本黑名单 |
| `z` | 当前包名是否命中圆角名单；`g()` 读取，`e0` / `u1` 据此决定上边缘圆角 |
| `f` | 当前是否已启用材质；`h()` 读取 |
| `v` | 平台能力位（系统模糊开关是否可用） |

`k()` 会在 `onStartInput` / `onStartInputView` / `onWindowShown` 等时机按当前包名重算上述状态；
`o9.e.a("hyper_material_enabled", true)` 是输入法设置里的「高级材质」总开关。官方用包名允许表
决定哪些应用启用，因此“只在部分官方应用生效”不是绘制差异，而是**状态谓词差异**。

正确接法（`VoiceAssistImeMaterialHook` 即此模式）：

0. 优先 hook 两个布尔谓词：`h()`（是否启用材质）与 `g()`（是否命中圆角名单）。它们直接决定
   官方是否创建透明材质层、`e0` / `u1` 是否取顶部圆角；字段表只用于扩展允许列表，字段解析
   失败时仍保留这两条谓词 Hook。
1. `k()` **before**：把 `w` 以版本 `1` 写入 `x`，从 `y` 删除；让官方
   `k()` 自己完成材质状态、透明层、模糊与圆角的全部计算。`v` 同时反映系统“背景模糊”
   总开关，**不要强行置 true**，否则系统未开模糊时会留下没有真实模糊的透明层。
2. 只对 `hyper_material_enabled` 这一个 key 拦截 `o9.e.a(String, Boolean)`，让模块开关可以覆盖
   输入法设置里的总开关；不要替换整个偏好读取方法。
3. `k()` **after**：把 `z` / `f` 置为 `true`，并对官方材质 `View`（`j`）调用
   `invalidateOutline()`；OutlineProvider 的圆角只在 invalidate 后重新取值。
4. `g()` 只影响形状，不能替代允许表；如果只 hook `g()`，材质层可能仍不会创建。
5. **禁止**用 `InputMonitor`、`InputManager.injectInputEvent`、透明悬浮窗或自建 `RenderEffect`
   去“补”输入法效果：既拿不到已经开始的输入手势，也会造成输入分发超时 / ANR。

生命周期与安全要求：

- `invalidateOutline()` 必须回主线程（非主线程用 `View.post`）。
- 只改当前 helper 实例的字段，不修改系统 Settings、不写目标应用偏好。
- 混淆类名 / 字段名随版本变化，必须 `versionGate` + `findClassIfExists` + 字段解析失败即
  fail-closed（只记录日志，不向输入法进程抛异常）；定位信息只落 `reverse_report/<包名>/`。
- 开关默认关闭；关闭时完全不干预官方包名白名单。Hook 可以始终安装并在回调里实时读
  `HookPrefs`（后续切换免重启生效；模块首次安装 / 更新后仍需重启一次目标进程），此时不要给
  `OptionSpec.showStatus = true`，否则关闭状态也会显示「已生效」；若按启动开关决定是否安装，
  则必须在 UI 说明需要重启目标进程。

---

## 2. DexKit 缓存（`:hook`）

包名：`cn.ianzb.hyperrefine.hook.dexkit`

### 2.1 `DexKit`

```kotlin
object DexKit {
    fun ready(target: PackageTarget, tag: String)
    fun <T> findMember(key: String, finder: IDexKit): T?
    fun <T> findMemberList(key: String, finder: IDexKitList): List<T>?
    fun close()
    fun clearAllCache()
}
```

### 2.2 `IDexKit` / `IDexKitList`

```kotlin
fun interface IDexKit {
    @Throws(ReflectiveOperationException::class)
    fun dexkit(bridge: DexKitBridge): BaseData
}

fun interface IDexKitList {
    @Throws(ReflectiveOperationException::class)
    fun dexkit(bridge: DexKitBridge): List<*>
}
```

### 2.3 `DexKitCacheManager`

```kotlin
object DexKitCacheManager {
    const val CACHE_DIR = "hyperrefine"
    const val CACHE_FILE = "dexkit_cache.json"

    fun init(target: PackageTarget, tag: String)
    fun <T> findMember(key: String, finder: IDexKit): T?
    fun <T> findMemberList(key: String, finder: IDexKitList): List<T>?
    fun releaseBridge()
    fun clearAllCache()
    fun deleteAllCacheFiles(context: Context, scopeList: Collection<String>?)
}
```

缓存文件位于目标应用 `dataDir/cache/hyperrefine/dexkit_cache.json`，带 `version / pkgVersion / osVersion` 失效校验与文件锁。

### 2.4 在 `BaseHook` 中使用

```kotlin
class MyHook : BaseHook() {
    override fun useDexKit() = true

    private lateinit var target: Method

    override fun initDexKit(): Boolean {
        target = requiredMember("TargetMethod") { bridge ->
            bridge.findMethod { name("doSomething") }.first()
        }
        return true
    }

    override fun init() {
        HookHelper.hookBefore(target) { param -> /* ... */ }
    }
}
```

### 2.5 清空缓存

App 侧通过 `RootHelper.deleteDexKitCache(scope, DexKitCacheManager.CACHE_DIR)` 以 `su` 删除各作用域应用缓存目录。

> 是否具备 Root 权限由主页「模块状态」卡片标注，无需在文案中额外说明；无 Root 时该操作会失败。

---

## 3. 配置系统（`:app`）

包名：`cn.ianzb.hyperrefine.prefs`

### 3.1 `OptionSpec`（组件声明）

```kotlin
data class OptionSpec(
    val key: String,                    // 配置键（持久化时自动加 prefs_key_ 前缀）
    val type: OptionType,
    val titleRes: Int,
    val summaryRes: Int = 0,
    val defaultBoolean: Boolean = false,
    val defaultInt: Int = 0,
    val defaultFloat: Float = 0f,
    val defaultString: String = "",
    val entryResIds: List<Int> = emptyList(),   // 下拉 / 单选选项文本
    val entryValues: List<String> = emptyList(),
    val targetPackages: List<String> = emptyList(),
    val deviceScope: Set<DeviceType>? = null,   // 设备形态白名单；空 = 各设备通用（非白名单设备禁用不隐藏）
    val showStatus: Boolean = false,            // 标题后展示四态 Hook 徽标（等待启动 / 已生效 / 未生效 / 不可用）
    val dependsOn: String? = null,              // 依赖键（绑定机制）
    val dependsOnValue: Boolean = true,
    val masterKey: String? = null,              // 滑块主开关键
    val sliderMin: Float = 0f,
    val sliderMax: Float = 100f,
    val sliderStep: Float = 1f,
    val sliderDecimals: Int = 0,
    val sliderUnitRes: Int = 0,
    val sliderValueLabelRes: Int = 0,           // 数值类型说明（滑块行左侧）
)
```

`OptionType`：`SWITCH` / `CHECKBOX` / `ARROW` / `DROPDOWN` / `SPINNER` / `RADIO` / `SLIDER` / `TEXT` / `PACKAGE_LIST`

字段用途对照：

| 字段 | 适用类型 | 说明 |
|---|---|---|
| `key` | 全部 | 持久化键，自动加 `prefs_key_` 前缀 |
| `titleRes` / `summaryRes` | 全部 | 标题 / 副标题字符串资源 |
| `defaultBoolean` | SWITCH / CHECKBOX | 默认开关 |
| `defaultString` / `entryResIds` / `entryValues` | DROPDOWN / RADIO / SPINNER | 选项文本与取值，默认取第一项 |
| `defaultString` | TEXT / PACKAGE_LIST | 文本内容 / 包名列表（换行、逗号、分号或空格分隔） |
| `dependsOn` / `dependsOnValue` | 全部 | 依赖其他键启用 / 禁用 |
| `masterKey` | SLIDER | 主开关键，控制滑块显隐与生效 |
| `sliderMin/Max/Step/Decimals` | SLIDER | 范围、步长、小数位 |
| `sliderUnitRes` / `sliderValueLabelRes` | SLIDER | `sliderUnitRes`=数值**右侧**单位；`sliderValueLabelRes`=数值**左侧**标签。**两者都不设时**标题才会显示在滑块外面，详见下文「`HookSliderCard` 细则」 |
| `targetPackages` | 全部 | 目标包，用于作用域申请 |
| `deviceScope` | 全部 | 设备形态白名单（`PHONE` / `PAD` / `FOLD`）；非白名单设备上组件**禁用灰显不隐藏**，更改「当前设备类型」后实时刷新。空 = 各设备通用 |
| `showStatus` | SWITCH / CHECKBOX | 在标题后展示该键的四态徽标（见 [4.5 Hook 生效状态](#45-hook-生效状态四态徽标)），目标进程重启后刷新；仅在功能已启用且开关打开时展示 |

### 3.2 `OptionRegistry`

```kotlin
object OptionRegistry {
    fun register(spec: OptionSpec)
    fun registerAll(specs: List<OptionSpec>)
    fun all(): List<OptionSpec>          // 可观察（mutableStateMapOf）
    fun find(key: String): OptionSpec?
    fun search(context: Context, query: String): List<OptionSpec>
}
```

### 3.3 `PrefsStore`

```kotlin
object PrefsStore {
    const val PREFS_NAME = "hyperrefine_prefs"
    const val REMOTE_GROUP = "hyperrefine_remote"   // 与 :hook HookPrefs.GROUP 一致

    fun init(context: Context)
    fun attachRemote(remotePrefs: SharedPreferences?)  // 服务绑定后调用，并同步本地→远程
    fun syncToRemote()
    fun getBoolean(key: String, default: Boolean = false): Boolean
    fun getInt(key: String, default: Int = 0): Int
    fun getLong(key: String, default: Long = 0L): Long
    fun getFloat(key: String, default: Float = 0f): Float
    fun getString(key: String, default: String? = null): String?
    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String>
    fun put(key: String, value: Any?)                  // 写物理存储 + 远程偏好
    fun remove(key: String)
    fun getAll(): Map<String, Any?>
    fun clearAll()
}
```

### 3.4 `ConfigState`

响应式配置状态（Compose 可观察）。

```kotlin
object ConfigState {
    fun init(context: Context)
    fun reload()
    fun get(key: String): Any?
    fun bool(key: String, defaultValue: Boolean): Boolean
    fun int(key: String, defaultValue: Int): Int
    fun float(key: String, defaultValue: Float): Float
    fun string(key: String, defaultValue: String): String
    fun set(key: String, value: Any?)
}
```

### 3.5 `ConfigBackup`

```kotlin
object ConfigBackup {
    data class ImportResult(val success: Boolean, val changedCount: Int)
    fun exportJson(): String
    fun importJson(json: String): ImportResult   // 导入后自动 ConfigState.reload()
}
```

`changedCount` 只统计导入后实际发生变化的配置项（迁移标记与 `runtime_` 运行态不计入）。

### 3.6 Hook 侧读取

`:hook` 的 `HookPrefs`（只读）通过 `getRemotePreferences(REMOTE_GROUP)` 读取同一份配置，键名同样自动加 `prefs_key_` 前缀：

```kotlin
object HookPrefs {
    const val GROUP = "hyperrefine_remote"
    fun init(remote: SharedPreferences)
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun getInt(key: String, defaultValue: Int = 0): Int
    fun getLong(key: String, defaultValue: Long = 0L): Long
    fun getFloat(key: String, defaultValue: Float = 0f): Float
    fun getString(key: String, defaultValue: String? = null): String?
    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String>
    fun getAll(): Map<String, *>
    fun registerListener(onChange: () -> Unit)   // 偏好变化回调（不支持时静默忽略）
}
```

`registerListener` 用于「改配置即时生效」的功能（例如滑条调整后立刻重算画面）：宿主 / 框架不支持
偏好变化监听时会静默忽略，此时功能退化为目标进程重启后生效，因此不要把它当作唯一生效路径，
仍要在 `init()` 中按当前配置套用一次。

---

## 4. 服务与状态（`:app`）

包名：`cn.ianzb.hyperrefine.xposed`

### 4.1 `XposedServiceManager`

```kotlin
object XposedServiceManager {
    var isActivated: Boolean            // Compose 可观察
    var isRootAvailable: Boolean        // Compose 可观察（异步检测）
    var rootChecked: Boolean            // 是否已完成 Root 检测
    var scope: List<String>             // Compose 可观察

    fun init()                          // 在 Application.onCreate 调用（含 Root 检测）
    fun checkRoot()                     // 异步检测 Root 权限（会真正拉起一次 su）
    fun getService(): XposedService?
    fun refreshScope()
    fun isInScope(packageName: String): Boolean
    fun ensureScope(packages: List<String>, onResult: ((Boolean, String?) -> Unit)? = null)
    fun removeScope(packages: List<String>)
}
```

**Root 检测时机**：`checkRoot()` 会真正拉起一次 `su`，在部分 Root 管理器上会弹出授权 / 使用
提示，因此只在两个时机调用——应用启动（`init()`）与用户在主页手动点按状态卡片；不要做定时轮询
或每次回到前台都检测，其余场景复用上一次结果（`isRootAvailable` / `rootChecked`）。

### 4.2 `RootHelper`

```kotlin
object RootHelper {
    fun isRootAvailable(): Boolean
    fun exec(command: String): Boolean
    fun deleteDexKitCache(packages: List<String>, cacheDirName: String): Boolean
}
```

### 4.3 `AppRestarter`

```kotlin
object AppRestarter {
    fun isSystemPackage(packageName: String): Boolean   // system / android / system_server
    fun restart(packageName: String): Boolean  // 无 Root 返回 false
    fun reboot(): Boolean
    fun restartSystemUi(processName: String = "com.android.systemui"): Boolean
}
```

- 普通应用：应用在运行时才执行 Root 下 `am force-stop <pkg>`，**只结束进程，不主动拉起应用**；未运行则不更改、不打开。
- 系统界面（`com.android.systemui`）：`restart()` 内部改走 `restartSystemUi()`，结束进程由系统自动拉起（`pkill -f` → `killall` → `am force-stop` 兜底），**不触发系统重启**。
- 系统目标（`system` / `android` / `system_server`）：执行 `reboot`。
- 无 Root 时返回 `false`，调用方据此提示用户。

### 4.4 `SafeModeReader`（Hook 兜底 / 安全模式）

Hook 进程通过 `:hook` 的 `SafeModeManager` 把崩溃记录写入远程偏好分组 `hyperrefine_safe_mode`，App 侧读取并支持重置。

```kotlin
object SafeModeReader {
    const val GROUP = "hyperrefine_safe_mode"   // 与 :hook SafeModeManager.GROUP 一致
    var safeModePackages: Set<String>              // Compose 可观察：被自动禁用的包
    fun attach(service: XposedService?)            // 服务绑定/断开时调用
    fun refresh()
    fun isInSafeMode(packageName: String): Boolean
    fun crashCount(packageName: String): Int
    fun noteUserRestart(packageName: String)   // 应用内主动重启目标后调用，排除误判
    fun reset(packageName: String)
    fun resetAll()
}
```

**兜底机制（`:hook` 的 `SafeModeManager`）**

- 进程每次成功装载 hook 后由入口统一上报一次「启动」；上报来自含 `:` 的子进程时不计入，避免与主进程混合误判。
- 15s 存活窗口内的重复启动计为一次疑似崩溃并累加，达到阈值（3 次）后自动进入安全模式，跳过该包全部 hook；启动间隔超过 15s 会重置计数。
- 在应用内主动重启目标应用（含「重启系统界面」）后，其 30s 内的下一次启动不计入计数。
- 关键进程：`android` / `system` / `com.android.systemui` / `com.miui.home`。
- `XposedEntry.onPackageReady` 与 `onSystemServerStarting` 均已接入，避免系统应用反复崩溃导致无法开机。
- 作用域页会标注「安全模式」并提供一键恢复（`SafeModeReader.reset`）。

### 4.5 Hook 生效状态（四态徽标）

判断某功能「是否真的在目标进程装上了 Hook」不能靠读远程偏好（Hook 侧只读）或「模块已启用」来猜，而应由**被注入的目标进程主动回报**。App 侧把回报归一为四种状态，并在选项标题后显示 Miuix `Badge`：

| 状态 | 含义 | 颜色 |
|---|---|---|
| `WAITING` | 目标进程尚未回报（通常是还没启动），或开关刚变更、尚未重新回报 | 黄色「等待启动」 |
| `APPLIED` | 目标进程明确回报该键安装成功 | 绿色「已生效」 |
| `NOT_APPLIED` | 目标进程已回报，但没有该键的成功记录（安装失败 / 被跳过也归入此类） | 红色「未生效」 |
| `UNAVAILABLE` | UI 前置条件不满足：作用域未勾选，或设备形态不在 `deviceScope` 白名单内 | 灰色「不可用」 |

「不可用」只由 App 侧的作用域 / 设备形态判断，**不再**依赖目标进程回报。安装失败仍然是「进程已回报但没装上」，应显示红色「未生效」，不能显示灰色。

**Hook 侧（`:hook`，包 `...hook.status`）**

```kotlin
object HookStatusReporter {
    fun markEvaluated(key: String)                            // BaseLoad 开始尝试安装前调用
    fun markInstalled(key: String)                            // 安装成功后调用
    fun markFailed(key: String)                               // 安装失败 / 跳过时调用
    fun flush(sourcePackage: String, sourceProcess: String)   // XposedEntry 在全部 Load 完成后调用
}
```

- `BaseLoad` 对每条启用 hook 先 `markEvaluated(hook.key)`，成功再 `markInstalled`，异常 `markFailed`；`XposedEntry` 在本包全部 Load 完成后统一 `flush(...)` 一次（每个进程一次广播，多 Load 不会互相顶掉）；无尝试则自动跳过。
- `flush` 通过 `ActivityThread.currentApplication()` 反射取目标进程 Application 作为发送上下文；若此时 Application 尚未就绪，则挂起 `Application.attach` 并在就绪后补发一次，把本进程的 `applied` / `failed` / `evaluated` **合并为一次定向广播** `ACTION_HOOKS_ACTIVE` 发往 App。广播失败时保留待发队列，下次 `flush` 重试。
- Android 16+ 开启 `BroadcastOptions.setShareIdentityEnabled(true)`，使 App 侧可校验发送者 UID；协议常量见 `HookStatusContract`。

**App 侧（`:app`，包 `...xposed`）**

```kotlin
enum class HookStatus { WAITING, APPLIED, NOT_APPLIED, UNAVAILABLE }

data class HookStatusSnapshot(
    val applied: Set<String> = emptySet(),
    val pending: Set<String> = emptySet(),
    val reportedPackages: Set<String> = emptySet(),
)

object HookStatusStore {
    val snapshot: StateFlow<HookStatusSnapshot>   // Compose 只订阅这一个 Flow
    fun initialize(context: Context)
    fun statusFor(key: String, targetPackages: Collection<String>): HookStatus
    fun record(
        context: Context,
        applied: Collection<String>,
        failed: Collection<String>,
        evaluated: Collection<String>,
        sourcePackage: String,
    )
    fun removeKeys(context: Context, keys: Collection<String>)
    fun clear(context: Context)
}

@Composable
fun rememberHookStatus(spec: OptionSpec): HookStatus   // 见 5.3
```

- `HookStatusReceiver` 校验：动作匹配 → 发送者身份可用（`sentFromUid`）→ `getPackagesForUid` 含来源包 → 模块 `versionCode` 一致（0 视为未知放行）→ `evaluated` 非空且上报键均为 App 已声明键。任一不满足即拒绝。
- `HookStatusStore.statusFor()` 只负责「等待 / 已生效 / 未生效」；「不可用」由 `rememberHookStatus()` 在 UI 层先判断作用域与 `deviceScope`，再决定是否查询存储。`record()` 中的 `failed` 键会从成功集合移除，后续按「未生效」展示。
- `HookStatusStore` 使用 **device-protected** 偏好，按 `versionCode` + `Settings.Global.BOOT_COUNT` 作用域：旧版本 / 上一次开机的证据自动丢弃。
- **优化点**：不依赖系统签名级 `INTERACT_ACROSS_USERS` 权限（普通模块即可用），改用发送者 UID → 包集合校验防伪造；上报为 `applied` / `failed` / `evaluated` 键集合而非定长位掩码，天然支持任意数量与多进程功能合并。
- 开关变更时卡片会 `removeKeys` 清除旧证据并把该键放入 `pending`，因此立即回到「等待启动」，需**重启目标进程**才会再次回报（libxposed 无法热卸载已注册的 hook）；目标进程回报后 `pending` 自动清除。
- 卡片显示规则：作用域未勾选或 `deviceScope` 不匹配时，无论开关状态都直接显示灰色「不可用」；前置条件满足时，开关打开才显示「等待 / 已生效 / 未生效」，开关关闭不展示历史证据。
- **多目标限制**：一个选项声明多个 `targetPackages` 时，只要任一目标包回报，键又不在该包 `applied` / `failed` 中，就会显示「未生效」；四态协议目前按键而不是按「键 + 包」存储。
- 徽标由 `HookSwitchCard` / `HookCheckboxCard` 渲染；滑块、下拉、跳转等值型选项没有独立的「安装成功」语义，不显示生效状态。
- 全量核对 145 个 `OptionSpec`：没有非开关类型声明 `showStatus`；剩余未声明的开关中，只有具备独立 `BaseHook` / 上报键的功能才能安全接入，子选项若与父 Hook 共用安装点则不应单独声明，否则会永久「未生效」。

**文案与颜色约定**：四种徽标文案固定为 `等待启动` / `已生效` / `未生效` / `不可用`，资源键为 `hook_status_waiting` / `hook_status_applied` / `hook_status_not_applied` / `hook_status_unavailable`；红 / 黄 / 绿 / 灰的亮色与暗色值集中定义在 `HookStatusBadge.kt`，组件使用 Miuix `Badge` 渲染，禁止在其它卡片内散落十六进制色值。

---

## 5. UI 组件（`:app`）

包名：`cn.ianzb.hyperrefine.ui.component.pref`

所有组件均基于 Miuix 组件库，并接入配置系统、多语言、依赖绑定、设备形态白名单与全局搜索。

### 5.1 组件一览

| 组件 | 基于 | 说明 |
|---|---|---|
| `HookSwitchCard` | `SwitchPreference` | 关不 hook、开 hook |
| `HookCheckboxCard` | `CheckboxPreference` | 复选框卡片 |
| `HookArrowCard` | `ArrowPreference` | 跳转二级页面 |
| `HookDropdownCard` | `WindowDropdownPreference` | 第一项为默认（不 hook），其余按值 hook |
| `HookRadioCard` | `CheckboxPreference`(End) | 右侧复选框表示选中项（单选语义） |
| `HookSliderCard` | `SliderPreference` | 主开关控制显隐与生效，支持整数/小数/范围，数值类型说明居左、当前值紧邻箭头，点击弹出输入对话框 |
| `HookTextCard` | `ArrowPreference` + `WindowDialog` | 点击弹出文本输入对话框 |
| `HookPackageListCard` | `ArrowPreference` + `WindowDialog` | 包名列表输入（多行），驱动页面右上角「重启应用」按钮 |
| `HookOptionView` | 分发器 | 按 `OptionSpec.type` 渲染对应组件 |
| `HookOptionsPage` | `Scaffold` + `SearchBar` | 通用功能页面（顶栏 + 搜索 + 分区列表 + 子页面搜索） |
| `HookSubPage` | — | 子页面搜索入口：把子页面（支持多级嵌套）内的功能并入父页搜索 |
| `HookSectionCard` | `SmallTitle` + `Card` | 组件分区卡片容器 |

### 5.2 组件签名

```kotlin
@Composable fun HookSwitchCard(spec: OptionSpec, modifier: Modifier = Modifier)
@Composable fun HookCheckboxCard(spec: OptionSpec, modifier: Modifier = Modifier)
@Composable fun HookArrowCard(spec: OptionSpec, modifier: Modifier = Modifier, onClick: () -> Unit)
@Composable fun HookDropdownCard(spec: OptionSpec, modifier: Modifier = Modifier)
@Composable fun HookRadioCard(spec: OptionSpec, modifier: Modifier = Modifier)
@Composable fun HookSliderCard(spec: OptionSpec, modifier: Modifier = Modifier)
@Composable fun HookTextCard(spec: OptionSpec, modifier: Modifier = Modifier)

@Composable fun HookOptionView(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
    onArrowClick: () -> Unit = {},
)
```

### 5.3 辅助接口

```kotlin
@Composable fun rememberEffectiveDeviceType(): DeviceType
@Composable fun rememberDeviceScopeEnabled(spec: OptionSpec): Boolean
@Composable fun rememberDependencyEnabled(spec: OptionSpec): Boolean
@Composable fun rememberOptionEnabled(spec: OptionSpec): Boolean
@Composable fun rememberHookStatus(spec: OptionSpec): HookStatus
@Composable fun hookSectionTitle(section: HookSection): String
fun ensureScopeFor(spec: OptionSpec)
```

- `rememberEffectiveDeviceType`：返回当前生效的设备形态（优先设置页「当前设备类型」覆盖值，否则用模块自动判定）。读取 `ConfigState`，更改设备类型后**实时刷新**。
- `rememberDeviceScopeEnabled`：设备形态是否在 `spec.deviceScope` 白名单内；未声明白名单（null / 空）视为各设备通用。
- `rememberDependencyEnabled`：根据 `spec.dependsOn` / `spec.dependsOnValue` 返回是否启用。
- `rememberOptionEnabled`：**统一的组件可用性判定** = `rememberDependencyEnabled && rememberDeviceScopeEnabled`。所有 Hook 卡片以它作为 `enabled`，从而让设备独占功能在非白名单设备上**禁用灰显而不隐藏**。
- `rememberHookStatus`：读取 `HookStatusStore.snapshot`，按 `spec.key` / `spec.targetPackages` 返回四态；目标进程重新回报后自动刷新。渲染约定见 [4.5](#45-hook-生效状态四态徽标)。
- `hookSectionTitle`：渲染分区标题；默认仅 `titleRes`，`titleEn` 非空时拼成 `中文（English）`（仅示例 / API 展示用，实际功能页应省略 `titleEn`）。
- `ensureScopeFor`：为 `spec.targetPackages` 中未授权的包申请作用域。

**绑定机制（`dependsOn`）：**

`dependsOn = "masterKey"` 时，仅当 `masterKey` 的布尔值等于 `dependsOnValue`（默认 `true`）时组件才启用；否则置灰不可交互。

依赖支持**多级链**（如 总开关 → 功能开关 → 滑条）：链上任意一环节未满足时，组件同样置灰不可交互。

**设备独占（`deviceScope`）：**

在 `OptionSpec.deviceScope` 声明白名单（如 `setOf(DeviceType.PHONE)`）后，其它设备形态上组件保持可见但**禁用灰显**；用户切换「设置 → 当前设备类型」后立即生效。hook 侧由 `BaseHook.deviceScope` / `BaseNativeHook.deviceScope` 使用同一份设备形态（`DeviceContext.current`）同步跳过，保证 UI 与运行时一致。

### 5.4 通用页面 `HookOptionsPage`

一次性获得「顶栏 + 可折叠搜索栏 + 分区列表」的完整布局，搜索结果为可点击列表项，点击后收起搜索并滚动定位到对应分区（不内联渲染组件）；子页面（含多级嵌套）功能可通过 `subPages` 并入同一搜索。

```kotlin
data class HookSection(
    val titleRes: Int,               // 分区标题字符串资源（默认单语言）
    val specs: List<OptionSpec>,     // 分区内配置项（按顺序渲染）
    val titleEn: String = "",        // 可选英文组件名；非空时拼成「中文（English）」，仅示例展示用
)

data class HookSubPage(
    val titleRes: Int,               // 子页面标题，作为搜索结果摘要（多级路径）的一部分
    val specs: List<OptionSpec>,     // 该层子页面直接包含的配置项（并入搜索）
    val onOpen: () -> Unit,          // 点击搜索结果时打开该子页面
    val subPages: List<HookSubPage> = emptyList(), // 更深一层子页面，递归并入搜索
)

@Composable
fun HookOptionsPage(
    title: String,
    sections: List<HookSection>,
    subPages: List<HookSubPage> = emptyList(),          // 子页面功能并入搜索
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
    topContent: (@Composable () -> Unit)? = null,        // 页面顶部提示区（搜索展开时隐藏）
    onArrowClick: (OptionSpec) -> Unit = {},
    customActionPackages: List<String> = emptyList(),   // 额外注入重启应用包名
    topBarActions: (@Composable () -> Unit)? = null,     // 顶栏右侧扩展槽
)

@Composable
fun HookSectionCard(
    section: HookSection,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
```

分区标题默认**单语言**（仅 `titleRes`）。`titleEn` 仅为模板示例保留，用 `hookSectionTitle(section)` 可拼出 `中文（English）`（例如 `开关卡片（SwitchPreference）`）；**实际功能页不要传 `titleEn`**，英文组件名以本文档 5.1 为准。

行为：
- 自动把本页分区与 `subPages`（**含多级嵌套 `HookSubPage.subPages`**）内的全部 `OptionSpec` 注册到 `OptionRegistry`，标题接入全局搜索。
- 搜索栏基于 Miuix `SearchBar` + `InputField`（胶囊输入框、清除按钮、取消按钮）。
- 搜索结果为可点击列表项（标题 = 配置项标题，摘要 = 所属分区 / 子页面多级路径「父 / 子」）；点击后收起搜索、清空输入：本页分区则滚动定位，子页面功能则调用对应层级 `HookSubPage.onOpen()` **直接打开目标页面**，**不内联渲染组件**。
- 结果按目标去重（同一分区 / 子页面只显示一条），并包含通过 `masterKey` 引用的配置项（如滑块主开关）。
- 箭头卡片点击回调 `onArrowClick(spec)`。
- **重启应用**：当分区内存在 `PACKAGE_LIST` 选项，或传入 `customActionPackages` 时，顶栏右上角出现「重启」图标按钮。点击弹出 `QuickActionDialog`：每行右侧勾选应用（默认全选），底部左侧「全选 / 全不选」、右侧「重启」（无勾选时禁用），对选中包批量结束进程（需 Root；普通应用不会自动重新打开，系统界面由系统自动拉起），完成后弹出成功提示（`quick_action_restart_success`；缺少 Root 时为 `scope_restart_need_root`）。
  - 包名来源 = 全部 `PACKAGE_LIST` 选项解析结果 + `customActionPackages`，去重。
  - 二次开发可通过 `customActionPackages` 直接暴露任意自定义应用，无需用户手动输入。
- **顶栏扩展**：`topBarActions` 渲染在自动生成的「重启应用」按钮之前；子页面可用 `QuickActionsAction(packages)` 注入同样式的右上角入口（见 5.6）。
- **顶部提示区**：`topContent` 渲染在搜索栏上方（搜索展开时隐藏），用于放置系统版本要求等说明卡片；为空则不显示。

### 5.5 完整使用示例

**（1）声明配置项**

```kotlin
val featureSwitch = OptionSpec(
    key = "feature_switch",
    type = OptionType.SWITCH,
    titleRes = R.string.feature_switch,
    summaryRes = R.string.feature_switch_summary,
    defaultBoolean = false,
    targetPackages = listOf("com.example.target"),
)

val featureLevel = OptionSpec(
    key = "feature_level",
    type = OptionType.DROPDOWN,
    titleRes = R.string.feature_level,
    defaultString = "default",
    entryResIds = listOf(R.string.level_default, R.string.level_high),
    entryValues = listOf("default", "high"),
    targetPackages = listOf("com.example.target"),
)
```

**（2）构建页面**

```kotlin
@Composable
fun MyFeaturePage(isBlurEnabled: Boolean, extraBottomPadding: Dp) {
    val context = LocalContext.current
    val sections = listOf(
        HookSection(
            titleRes = R.string.section_switch,
            specs = listOf(featureSwitch),
        ),
        HookSection(
            titleRes = R.string.section_dropdown,
            specs = listOf(featureLevel),
        ),
    )
    // 子页面功能并入本页搜索（支持多级嵌套）；命中即打开对应层级页面。
    val subPages = listOf(
        HookSubPage(
            titleRes = R.string.my_subpage,
            specs = listOf(featureSub),
            onOpen = { context.startActivity(Intent(context, MySubPageActivity::class.java)) },
            subPages = listOf(
                HookSubPage(
                    titleRes = R.string.my_deep_subpage,
                    specs = listOf(featureDeep),
                    onOpen = { context.startActivity(Intent(context, MyDeepSubPageActivity::class.java)) },
                ),
            ),
        ),
    )
    HookOptionsPage(
        title = stringResource(R.string.my_feature_page),
        sections = sections,
        subPages = subPages,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onArrowClick = { /* 打开二级页面 */ },
    )
}
```

**（3）单独使用某个组件**

```kotlin
Card {
    HookSwitchCard(featureSwitch)
    HookDropdownCard(featureLevel)
}
```

**（4）自定义分区容器**

```kotlin
HookSectionCard(
    HookSection(R.string.section_switch, listOf(featureSwitch)),
) {
    HookSwitchCard(featureSwitch)
}
```

### 5.6 布局规范（间距统一，强制要求）

> **所有页面必须遵循以下间距规范**，新增页面 / 组件时请复用 `HookOptionsPage`、`HookSectionCard`、`SubPageScaffold`，禁止自行硬编码间距，以保证全局视觉一致。

| 位置 | 规范 | 说明 |
|---|---|---|
| 卡片水平内边距 | `Modifier.padding(horizontal = 12.dp)` | 所有卡片统一左右 12dp |
| 卡片间距 | `Modifier.padding(bottom = 12.dp)` | **统一用 `bottom`，不要用 `top`**；避免与标题上间距叠加导致不一致 |
| 分组标题 | `SmallTitle(text = hookSectionTitle(section))` | 单语言标题（仅 `titleRes`）；`titleEn` 仅示例展示用；**不要额外加 `Modifier.padding(top = ...)`**，`SmallTitle` 默认 `insideMargin = PaddingValues(28.dp, 8.dp)` 已提供标准间距 |
| 组件行内边距 | `BasicComponentDefaults.InsideMargin`（16dp） | 由 Miuix 组件默认提供，不要覆盖 |
| 页面顶/底内边距 | 使用 `Scaffold` 的 `innerPadding` + `extraBottomPadding` | 不要额外加固定 top 间距 |
| 顶部搜索栏间距 | `Modifier.padding(top = 12.dp, bottom = 8.dp)` | 搜索栏与上方顶栏、下方首个分区的间距 |
| 设备独占 | `OptionSpec.deviceScope` + `rememberOptionEnabled` | 非白名单设备上组件**禁用灰显不隐藏**；切换「当前设备类型」后实时生效 |
| 二级页面 | 继承 `BaseSubPageActivity`，内容用 `SubPageScaffold` 提供的 `contentPadding` | 不要自行处理系统栏 / 顶栏间距 |

**标题规范（强制）：** 实际功能页的分区标题使用**单一语言**（仅 `HookSection.titleRes`）。`titleEn` 仅为模板示例保留：非空时由 `HookSection(titleRes, specs, titleEn)` + `hookSectionTitle()` 拼成单行 `中文（English）`（例如 `开关卡片（SwitchPreference）`），用于展示 API 英文组件名；无论哪种情形都不得拆成「英文标题 + 中文副标题」两行。

**入口卡片文案（强制）：** 作为**二级菜单入口**的卡片（`HookArrowCard` 跳转二级页、`HookTextCard` / `HookPackageListCard` 弹出对话框，以及二级页面里指向更深一层的入口等）按以下要求书写：

- **尽量不加小标题（`summaryRes`，即副标题）**：入口本身已通过箭头 / 点击语义表达「可进入」，不要再叠加一行解释；确需提示时才写极短补充（如「需 Root」）。
- **大标题（`titleRes`）用总结性名词短语**：如「高级选项」「重启应用列表」；**不要**写成描述性句子（如「输入包名后可在右上角批量重启」）。
- 描述性、说明性内容放到**二级页面内部**，不放在入口卡片上。

> 与 5.6「重启应用」对话框一致：标题固定为总结性短语（「重启应用」），且不显示小标题。

**显示 / 隐藏动画（强制）：** 组件出现 / 隐藏统一使用 Miuix 标准弹簧 `MiuixExpandSpec`（`folmeSpring(damping = 1.0f, response = 0.4f)`，见 `ui/util/MiuixAnimations.kt`）：

```kotlin
AnimatedVisibility(
    visible = showContent,
    enter = expandVertically(animationSpec = MiuixExpandSpec),
    exit = shrinkVertically(animationSpec = MiuixExpandSpec),
) {
    // 内容
}
```

禁止使用默认 `expandVertically()` / `shrinkVertically()` 或自定义时长，以保证全局动效一致。

**右上角重启样式（强制）：** 需要对目标批量「重启」时，顶栏右上角统一使用 `MiuixIcons.Refresh`（重启）图标按钮（`IconButton` + `tint = onSurface`）→ `QuickActionDialog`：

- 入口：`HookOptionsPage` 在检测到 `PACKAGE_LIST` 选项或传入 `customActionPackages` 时自动生成；二级页面通过 `topBarActions = { QuickActionsAction(packages) }` 注入，不要自行实现；
- 对话框标题固定为「重启应用」（`quick_action_title`），**不显示小标题 / `summary`**；
- 应用列表必须用 Miuix `Card` 圆角容器包裹（可滚动，`heightIn(max = 420.dp)`），**不得**用裸 `Column` 直接平铺；
- 列表每行用 `CheckboxPreference`：`checkboxLocation = CheckboxLocation.End`（对勾必须在应用右侧），且默认**全选**；行标题为应用名、摘要为包名（两者不同时）；
- 底部一行：左侧「全选 / 全不选」`TextButton`（`ButtonDefaults.textButtonColors()`）——**全部勾选时显示「全不选」，否则显示「全选」**；右侧「重启」主按钮（`Button(buttonColorsPrimary)`，`enabled = 已勾选项非空`）；
- 重启只作用于**已勾选**的包；普通应用只结束进程，不主动拉起，系统界面由系统自动恢复；
- 重启完成后弹出提示：全部成功显示 `quick_action_restart_success`（「重启成功」），否则显示 `scope_restart_need_root`（缺少 Root）；
- `system_server`（`system` / `android` / `system_server`）重启前弹出 `SystemRestartConfirmDialog` 二次确认；
- `com.android.systemui` 由 `AppRestarter.restartSystemUi()` 结束进程（系统自动拉起），**不触发系统重启**。

**为什么统一用 `bottom` 而不是 `top`：** 若卡片使用 `top` 间距，同时标题又带 `top` 修饰符，会导致「标题上间距」与其它页面不一致。统一由「上一张卡片的 `bottom = 12.dp` + `SmallTitle` 默认上内边距」提供间距，可保证任意页面、任意分区数量下的间距完全一致。

### 5.7 组件行为细则

| 组件 | 配置写入 | 作用域申请 | 备注 |
|---|---|---|---|
| `HookSwitchCard` | 开/关均写入 | 仅开启时 | 关=不 hook，开=hook |
| `HookCheckboxCard` | 勾选/取消均写入 | 仅勾选时 | 语义同开关 |
| `HookArrowCard` | 不写入 | 无 | 仅触发 `onClick` 跳转二级页 |
| `HookDropdownCard` | 选中即写入 | 选中非默认项时 | 第一项为默认（不 hook） |
| `HookRadioCard` | 选中即写入 | 选中非默认项时 | 右侧复选框，单选语义 |
| `HookSliderCard` | 拖动/输入即写入 | 主开关开启时 | 见下方细则 |
| `HookTextCard` | 确认时写入 | 无 | 弹窗输入 |
| `HookOptionView` | 由具体组件决定 | 由具体组件决定 | 按 `type` 分发，`SPINNER` 复用下拉 |

> **依赖前置规范（强制）**：需要前置选项解锁的功能，必须**始终显示**；前置未满足时以**禁用（灰显）**呈现，**禁止隐藏**——不得使用 `AnimatedVisibility` 或条件渲染根据配置值隐藏组件 / 整个分区。
>
> 依赖关系一律用 `OptionSpec.dependsOn`（+ `dependsOnValue`）声明，由 `rememberOptionEnabled(spec)` 统一判定 `enabled`；`HookSliderCard.masterKey` 的开关只控制滑块的**启用 / 禁用**，不再控制显隐。设备独占同理（`deviceScope` 不满足时禁用灰显）。

**下拉 / 单选默认项约定：** `entryValues[0]` 为默认值（`defaultString` 应等于它），第一项文本建议形如「默认（中速）」，表示该状态下不 hook；选择非默认项才申请作用域。

**`HookSliderCard` 细则：**
- `masterKey` 非空时，卡片顶部渲染一个 `SwitchPreference`（标题取 `spec.titleRes`），其开关状态只控制滑块**启用 / 禁用**（`enabled = enabled && masterEnabled`）；滑块**始终显示**，不随开关隐藏（见 5.7 顶部的依赖前置规范）。
- `sliderMin` / `sliderMax` / `sliderStep` / `sliderDecimals` 定义范围、步长与小数位；内部用 `BigDecimal` 定点换算，避免浮点精度丢失。
- `sliderValueLabelRes` 作为滑块行的**标题居左**显示（位于数值左侧）；当前值显示在行末、**紧邻箭头**。
- ⚠️ **标签位置（重要，务必遵守）**：滑块的文字分两处——
  - `sliderValueLabelRes` → 显示在**数值左侧**的行内标签；
  - `sliderUnitRes` → 显示在**数值右侧**的单位后缀（如 `%`、`dp`）。
  仅当 `masterKey == null && sliderValueLabelRes == 0` 时，`HookSliderCard` 才会退化为把 `titleRes` 作为**滑动组件外面**的独立标题显示（见 `HookSliderCard.kt` 中 `if (masterKey == null && spec.sliderValueLabelRes == 0)` 分支）。
  **因此：若希望标题与数值同行、显示在数值左侧，必须设置 `sliderValueLabelRes`（可直接复用 `titleRes` 的字符串资源），否则标题会显示在滑动组件外面。** 指定 `sliderValueLabelRes` 后不会再额外渲染外部标题。
- 点击滑块行空白弹出居中 `WindowDialog`：第一行左右显示最小值 / 最大值，中间显示「当前值 · 默认值」；下方 `TextField` 手动输入（超范围提示错误）；底部按钮 **取消 / 恢复默认 / 确定**。

**`HookTextCard` 细则：** 主界面为一行（标题 + 当前值摘要），点击弹出与滑块一致的对话框：当前值 / 默认值同行左右显示 + `TextField` + 取消 / 恢复默认 / 确定。

**设备独占：** 需要仅在特定设备形态显示 / 生效的功能，声明 `OptionSpec.deviceScope`；非白名单设备上组件保持可见但**禁用灰显**，hook 侧由 `BaseHook.deviceScope` / `BaseNativeHook.deviceScope` 用同一份设备形态同步跳过。示例：功能页「控制中心 → 融合设备中心 → 横屏融合设备中心右置」（`deviceScope = setOf(DeviceType.PHONE)`）。

---

### 5.8 主页 Pager 手势冲突（横向拖拽子组件）

主页 `HorizontalPager` 采用 Cross-Axis 拦截模式（`userScrollEnabled = false` + 自实现拖拽接管，见 `MainActivity`）。该模式在 `PointerEventPass.Initial` 阶段（父级先收到事件）消费横向拖拽，因此**页面内需要横向拖动的组件（如 `SliderPreference`）默认收不到手势，无法拖动**。

本项目在 `ui/util/PagerSwipeOverride.kt`（改编自 miuix `PagerGestureUtils.horizontalPagerSwipeOverride`，Apache-2.0）提供放行机制：

- **Pager 侧**：以 `Modifier.pagerSwipeOverride(pagerState = pagerState)` 替代 miuix 的 `pagerGestureOverride`；其余配置（`userScrollEnabled = false`、`pageNestedScrollConnection = PagerGestureNestedScrollConnection`）保持不变。
- **子组件侧**：`Modifier.claimPagerSwipe()` 在按下时同步「认领」本次手势（只观察、不消费事件），被认领的手势 Pager 不再拦截。`HookSliderCard` 已内置；新增其它横向拖拽子组件时手工挂上即可。
- 非拖拽区域的左右滑动切页、惯性分页、触控板滚轮 / Pan 翻页、无障碍翻页均不受影响。

---

## 6. 二级页面模板

包名：`cn.ianzb.hyperrefine.ui.component` / `...ui.screen.subpage`

### 6.1 `SubPageScaffold`

```kotlin
@Composable
fun SubPageScaffold(
    title: String,
    isBlurEnabled: Boolean,
    onBack: () -> Unit,
    topBarActions: (@Composable () -> Unit)? = null,   // 顶栏右侧扩展槽
    content: @Composable (PaddingValues) -> Unit,
)
```

提供顶栏返回、背景模糊与主题统一的脚手架；`topBarActions` 用于注入「重启应用」等右上角入口。

### 6.2 `BaseSubPageActivity`

```kotlin
abstract class BaseSubPageActivity : ComponentActivity() {
    protected abstract val titleRes: Int
    protected open val topBarActions: (@Composable () -> Unit)? = null   // 顶栏右侧扩展槽，默认不显示
    @Composable
    protected abstract fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues)
}
```

自动套用模块配置（主题模式、背景模糊），使用系统默认页面切换动画；`topBarActions` 会透传给 `SubPageScaffold`，子类可覆写为 `{ QuickActionsAction(listOf("com.android.systemui")) }`。子类示例：

```kotlin
class MySubPageActivity : BaseSubPageActivity() {
    override val titleRes = R.string.my_subpage

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item { Card { BasicComponent(title = "Hello") } }
        }
    }
}
```

在 `AndroidManifest.xml` 注册：

```xml
<activity android:name=".ui.screen.mysub.MySubPageActivity"
    android:exported="false"
    android:theme="@style/Theme.HyperRefine" />
```

> 若希望子页面（及其嵌套子页面）内的功能也能被父页面搜索到，在父页 `HookOptionsPage(subPages = ...)` 中传入 `HookSubPage(titleRes, specs, onOpen, subPages = ...)`，子页面自身无需再放搜索栏（见 5.4）。

### 6.3 `SafeModeActivity`（安全模式页）

在设置页「模块」分区提供入口，点击进入 `ui.screen.safemode.SafeModeActivity`：

- 列出全部被 Hook 应用（即当前作用域），逐项用 `Switch` 控制其安全模式；
- 打开：该应用下次启动跳过全部 Hook（JavaHook + NativeHook）；关闭：恢复并清空崩溃计数；
- 顶部固定声明文案（`safe_mode_declaration`）；若已有应用被自动禁用，设置页会额外显示 `safe_mode_active_title` 声明。

`SafeModeReader` 新增：

```kotlin
fun setSafeMode(packageName: String, enabled: Boolean)  // 手动开关
fun reset(packageName: String)                          // = setSafeMode(pkg, false)
```

---

## 7. 接入步骤

> 以本模板为基础二次开发时需要修改的**全部内容**（包名、应用名、图标、关于页链接、许可证列表、模块元数据、Hook 代码、配置项、导出文件名等）见 [二次开发指南](CUSTOMIZE.md)。
>
> 实现指定应用的 Hook、或参考其他模块复刻功能时的完整流程（真机 `adb` 扫描授权、隐私边界与开源合规）见 [模块开发工作流](WORKFLOW.md)。

### 7.1 新增一个 Hook 目标

1. 在 `:hook` 的 `META-INF/xposed/scope.list` 加入目标包名。
2. 新建 `BaseHook` 子类实现 `init()`。
3. 新建 `BaseLoad` 子类，在 `onPackageLoaded` 中通过 `initHook(hook, HookPrefs.getBoolean(key, false))` 声明。
4. 在 `HookEntryRegistry` 中登记该 `BaseLoad`。

```kotlin
class MyLoad : BaseLoad() {
    override val targetPackages = listOf("com.example.target")
    override fun onPackageLoaded(target: PackageTarget) {
        initHook(MyHook(), HookPrefs.getBoolean(MyHook.KEY, false))
    }
}

class MyHook : BaseHook() {
    override val key = KEY
    override fun init() {
        val clazz = Reflect.findClass("com.example.target.Foo", target.classLoader)
        HookHelper.findAndHookMethodAfter(clazz, "bar") { param ->
            HookHelper.log("bar called")
        }
    }
    companion object { const val KEY = "example_target_bar" }
}

// HookEntryRegistry
private val loads = listOf(MyLoad())
```

### 7.2 新增一个配置项并接入 UI

```kotlin
val spec = OptionSpec(
    key = "example_target_bar",
    type = OptionType.SWITCH,
    titleRes = R.string.example_target_bar,
    summaryRes = R.string.example_target_bar_summary,
    defaultBoolean = false,
    targetPackages = listOf("com.example.target"),
)
OptionRegistry.register(spec)

// 方式一：通用页面
HookOptionsPage(
    title = stringResource(R.string.page_title),
    sections = listOf(
        HookSection(R.string.section_switch, listOf(spec)),
    ),
)

// 方式二：单组件
Card { HookSwitchCard(spec) }
```

启用开关时，`HookSwitchCard` 会：
1. 写入配置（`ConfigState.set` → `PrefsStore` → 远程偏好）
2. 自动 `ensureScopeFor(spec)` 申请作用域
3. 若声明了 `deviceScope`，非白名单设备上保持可见但禁用灰显

---

## 8. 关键文件索引

| 关注点 | 文件 |
|---|---|
| libxposed 入口 | `hook/src/main/java/.../hook/xposed/XposedEntry.kt` |
| Hook 封装 | `hook/.../hook/xposed/HookApi.kt` |
| 原生 Hook 封装 | `hook/.../hook/nativehook/NativeHookApi.kt`、`BaseNativeHook.kt` |
| 原生入口契约 / 模板 | `hook/src/main/rust/nativehook/`（`src/lib.rs`、`Cargo.toml`） |
| 反射工具 | `hook/.../hook/xposed/Reflect.kt` |
| 规则基类 | `hook/.../hook/base/BaseHook.kt`、`BaseLoad.kt`、`HookEntryRegistry.kt` |
| Hook 配置读取 | `hook/.../hook/prefs/HookPrefs.kt` |
| DexKit 缓存 | `hook/.../hook/dexkit/` |
| 模块元数据 | `hook/src/main/resources/META-INF/xposed/{java_init.list,module.prop,scope.list}` |
| 配置系统 | `app/.../prefs/` |
| 服务与状态 | `app/.../xposed/XposedServiceManager.kt`、`RootHelper.kt`、`SafeModeReader.kt` |
| 组件 | `app/.../ui/component/pref/`（`HookCards.kt`、`HookDropdownCards.kt`、`HookSliderCard.kt`、`HookTextCard.kt`、`HookOptionView.kt`、`HookOptionSupport.kt`、`HookOptionsPage.kt`） |
| 顶栏重启应用 | `app/.../ui/component/QuickActionsAction.kt` |
| 显隐动画 | `app/.../ui/util/MiuixAnimations.kt`（`MiuixExpandSpec`） |
| 二级页面模板 | `app/.../ui/component/SubPageScaffold.kt`、`app/.../ui/screen/subpage/BaseSubPageActivity.kt` |
| 功能页 | `app/.../ui/screen/features/FeaturesPage.kt`（子页面示例 `FeatureSubPageActivity.kt`） |
