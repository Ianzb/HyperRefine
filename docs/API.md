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

两者共用同一套「开关配置、状态上报、热重载、安全模式、版本筛选」链路；区别仅在于 Hook 代码写在 Java/Kotlin 还是原生库。选择依据见 [原生 Hook 指南 · 两种 Hook 的区别](NATIVE_HOOK.md#0-两种-hook-的区别)。

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

    fun hookBefore(executable: Executable, priority: Int = ..., callback: (HookParam) -> Unit): HookHandle
    fun hookAfter(executable: Executable, priority: Int = ..., callback: (HookParam) -> Unit): HookHandle
    fun hookReplace(executable: Executable, priority: Int = ..., callback: (HookParam) -> Any?): HookHandle
    fun hookClassInitializer(clazz: Class<*>, priority: Int = ..., callback: (HookParam) -> Unit): HookHandle

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
    fun unhookAll()     // 热重载 / 卸载时统一调用
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
    fun findMethodIfExists(...): Method?
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
    abstract fun init()

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
| `onModuleLoaded` | 初始化 `HookHelper` / `HookPrefs` / `HookStatusWriter` |
| `onPackageReady` | 按包分发 `BaseLoad.onPackageReady`（开机 / 冷启动即应用） |
| `onSystemServerStarting` | 预留 system_server 支持 |
| `onHotReloading` | 刷新状态、保存目标信息到 extras，返回 `true` 允许热重载 |
| `onHotReloaded` | 卸载旧 hook、重新初始化并重装 |

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
    fun load(libraryName: String, key: String = ..., required: Boolean = true): Boolean
    fun loaded(): List<NativeLibrarySpec>
    fun isLoaded(libraryName: String): Boolean
    fun size(): Int
    fun reset()        // 热重载 / 新代码代次（so 无法真正卸载）
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
initNativeHook(hook: BaseNativeHook, enabled: Boolean)
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

**手动覆盖**：设置页「模块 → 当前设备类型」提供 `默认（<模块判定>）` / `手机` / `平板` / `折叠屏` 四项。覆盖值存于配置键 `device_type`（`auto` / `phone` / `pad` / `fold`），经 `PrefsStore` 同步到远程偏好，hook 进程由 `DeviceContext.current` 读取生效；`默认` 即使用 `DeviceContext.detected`。修改后需重启 / 热重载目标进程。

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
    val dependsOn: String? = null,              // 依赖键（绑定机制）
    val dependsOnValue: Boolean = true,
    val masterKey: String? = null,              // 滑块主开关键
    val sliderMin: Float = 0f,
    val sliderMax: Float = 100f,
    val sliderStep: Float = 1f,
    val sliderDecimals: Int = 0,
    val sliderUnitRes: Int = 0,
    val sliderValueLabelRes: Int = 0,           // 数值类型说明（滑块行左侧）
    val hookId: String? = null,
    val demoStatus: HookStatus? = null,         // 仅示例/预览：强制状态（驱动标题染色），非空时覆盖真实状态
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
| `sliderUnitRes` / `sliderValueLabelRes` | SLIDER | 单位、数值类型说明 |
| `targetPackages` | 全部 | 目标包，用于作用域申请与状态判断 |
| `hookId` | 全部 | 状态上报标识，默认取 `key` |
| `demoStatus` | 全部 | 仅示例/预览用：强制指定状态（成功/失败/未应用），用于展示状态效果 |

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
    fun getBoolean/getInt/getLong/getFloat/getString/getStringSet(key, default): ...
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
    fun exportJson(): String
    fun importJson(json: String): Boolean   // 导入后自动 ConfigState.reload()
}
```

### 3.6 Hook 侧读取

`:hook` 的 `HookPrefs`（只读）通过 `getRemotePreferences(REMOTE_GROUP)` 读取同一份配置，键名同样自动加 `prefs_key_` 前缀：

```kotlin
object HookPrefs {
    const val GROUP = "hyperrefine_remote"
    fun init(remote: SharedPreferences)
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun getString/getInt/getLong/getFloat/getStringSet(...)
    fun getAll(): Map<String, *>
}
```

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
    fun checkRoot()                     // 异步检测 Root 权限
    fun getService(): XposedService?
    fun refreshScope()
    fun isInScope(packageName: String): Boolean
    fun ensureScope(packages: List<String>, onResult: ((Boolean, String?) -> Unit)? = null)
    fun removeScope(packages: List<String>)
    fun hotReload(packages: List<String>, onResult: ((String) -> Unit)? = null)
    fun runningTargets(): List<HookedTarget>
}
```

### 4.2 `HookStatusReader`

```kotlin
object HookStatusReader {
    fun refresh()                       // 读取远程状态文件并合并
    fun statusOf(hookId: String): Boolean?   // true 成功 / false 失败 / null 无记录
    fun clear()
}

enum class HookStatus { SUCCESS, FAILED, NOT_APPLIED }
```

### 4.3 `HookStatusWriter`（`:hook`）

```kotlin
object HookStatusWriter {
    fun init(module: XposedInterface)
    fun startProcess(name: String)
    fun record(hookId: String, success: Boolean)
    fun flush()
}
```

### 4.4 `RootHelper`

```kotlin
object RootHelper {
    fun isRootAvailable(): Boolean
    fun exec(command: String): Boolean
    fun deleteDexKitCache(packages: List<String>, cacheDirName: String): Boolean
}
```

### 4.5 `AppRestarter`

```kotlin
object AppRestarter {
    fun isSystemPackage(packageName: String): Boolean   // system / android / system_server
    fun restart(context: Context, packageName: String): Boolean  // 无 Root 返回 false
    fun reboot(): Boolean
}
```

- 普通应用：应用在运行时才执行 Root 下 `am force-stop <pkg>` 并拉起启动 Activity；未运行则不更改、不打开。
- 系统目标（`system` / `android` / `system_server`）：执行 `reboot`。
- 无 Root 时返回 `false`，调用方据此提示用户。

### 4.6 `SafeModeReader`（Hook 兜底 / 安全模式）

Hook 进程通过 `:hook` 的 `SafeModeManager` 把崩溃记录写入远程偏好分组 `hyperrefine_safe_mode`，App 侧读取并支持重置。

```kotlin
object SafeModeReader {
    const val GROUP = "hyperrefine_safe_mode"   // 与 :hook SafeModeManager.GROUP 一致
    var safeModePackages: Set<String>              // Compose 可观察：被自动禁用的包
    fun attach(service: XposedService?)            // 服务绑定/断开时调用
    fun refresh()
    fun isInSafeMode(packageName: String): Boolean
    fun crashCount(packageName: String): Int
    fun reset(packageName: String)
    fun resetAll()
}
```

**兜底机制（`:hook` 的 `SafeModeManager`）**

- 每次目标进程加载 hook 前写入「正在加载」时间戳；进程存活超过 15s 后清除并重置计数。
- 若在存活窗口（60s）内再次启动，判定为一次疑似 hook 崩溃并累加；达到阈值（普通 3 次、关键应用 2 次）后自动进入安全模式，跳过该包全部 hook。
- 关键应用：`system` / `android` / `com.android.systemui` / `com.android.settings` / `com.miui.home` / `com.miui.securitycenter`。
- `XposedEntry.onPackageReady` 与 `onSystemServerStarting` 均已接入，避免系统应用反复崩溃导致无法开机。
- 作用域页会标注「安全模式」并提供一键恢复（`SafeModeReader.reset`）。

---

## 5. UI 组件（`:app`）

包名：`cn.ianzb.hyperrefine.ui.component.pref`

所有组件均基于 Miuix 组件库，并接入配置系统、多语言、依赖绑定、全局搜索与 Hook 状态。

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
| `HookPackageListCard` | `ArrowPreference` + `WindowDialog` | 包名列表输入（多行），驱动页面右上角「快捷操作」按钮 |
| `HookOptionView` | 分发器 | 按 `OptionSpec.type` 渲染对应组件 |
| `HookOptionsPage` | `Scaffold` + `SearchBar` | 通用组件页面（顶栏 + 搜索 + 分区列表） |
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
@Composable fun rememberDependencyEnabled(spec: OptionSpec): Boolean
@Composable fun rememberHookStatus(spec: OptionSpec): HookStatus
@Composable fun HookStatusTitleColor(spec: OptionSpec): BasicComponentColors
@Composable fun hookSectionTitle(section: HookSection): String
fun ensureScopeFor(spec: OptionSpec)
```

- `rememberDependencyEnabled`：根据 `spec.dependsOn` / `spec.dependsOnValue` 返回是否启用；依赖项不满足时组件 `enabled = false`。
- `rememberHookStatus`：计算当前状态（成功 / 失败 / 未应用）；若 `spec.demoStatus != null` 则直接返回该值。
- `HookStatusTitleColor`：返回标题 `titleColor`（成功=绿色 / 失败=红色），未应用时返回默认标题色；零额外占位。
- `hookSectionTitle`：把 `HookSection` 拼成 `中文（English）` 标题。
- `ensureScopeFor`：为 `spec.targetPackages` 中未授权的包申请作用域。

**状态判定顺序（`rememberHookStatus`）：**

1. `spec.demoStatus != null` → 直接返回（仅示例 / 预览）。
2. 模块未激活（`XposedServiceManager.isActivated == false`）→ `NOT_APPLIED`。
3. `spec.targetPackages` 非空且都不在作用域内 → `NOT_APPLIED`。
4. 否则查 `HookStatusReader.statusOf(spec.statusId)`：`true` → `SUCCESS`，`false` → `FAILED`，`null` → `NOT_APPLIED`。

**绑定机制（`dependsOn`）：**

`dependsOn = "masterKey"` 时，仅当 `masterKey` 的布尔值等于 `dependsOnValue`（默认 `true`）时组件才启用；否则置灰不可交互。

### 5.4 通用页面 `HookOptionsPage`

一次性获得「顶栏 + 可折叠搜索栏 + 分区列表」的完整布局，搜索结果为可点击列表项，点击后收起搜索并滚动定位到对应分区（不内联渲染组件）。

```kotlin
data class HookSection(
    val titleRes: Int,               // 分区标题（中文描述）字符串资源
    val specs: List<OptionSpec>,     // 分区内配置项（按顺序渲染）
    val titleEn: String = "",        // 英文组件名，以「中文（English）」拼接
)

@Composable
fun HookOptionsPage(
    title: String,
    sections: List<HookSection>,
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
    onArrowClick: (OptionSpec) -> Unit = {},
    customActionPackages: List<String> = emptyList(),   // 额外注入快捷操作包名
)

@Composable
fun HookSectionCard(
    section: HookSection,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)
```

分区标题统一渲染为 **`中文（English）`** 单行标题（例如 `开关卡片（SwitchPreference）`），由 `hookSectionTitle(section)` 生成；不再使用副标题行。

行为：
- 自动把分区内全部 `OptionSpec` 注册到 `OptionRegistry`，标题接入全局搜索。
- 搜索栏基于 Miuix `SearchBar` + `InputField`（胶囊输入框、清除按钮、取消按钮）。
- 搜索结果为可点击列表项（标题 = 配置项标题，摘要 = 所属分区标题）；点击后收起搜索、清空输入并滚动定位到对应分区，**不内联渲染组件**。
- 结果按分区去重（同一分区只显示一条），并包含通过 `masterKey` 引用的配置项（如滑块主开关）。
- 箭头卡片点击回调 `onArrowClick(spec)`。
- **快捷操作**：当分区内存在 `PACKAGE_LIST` 选项，或传入 `customActionPackages` 时，顶栏右上角出现「更多」按钮。点击弹出 `QuickActionDialog`，对汇总后的包名批量执行「热重载」「重启」（重启需 Root），并提供「全部热重载」。
  - 包名来源 = 全部 `PACKAGE_LIST` 选项解析结果 + `customActionPackages`，去重。
  - 二次开发可通过 `customActionPackages` 直接暴露任意自定义应用，无需用户手动输入。

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
    val sections = listOf(
        HookSection(
            titleRes = R.string.section_switch,
            titleEn = "SwitchPreference",
            specs = listOf(featureSwitch),
        ),
        HookSection(
            titleRes = R.string.section_dropdown,
            titleEn = "WindowDropdownPreference",
            specs = listOf(featureLevel),
        ),
    )
    HookOptionsPage(
        title = stringResource(R.string.my_feature_page),
        sections = sections,
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
    HookSection(R.string.section_switch, listOf(featureSwitch), "SwitchPreference"),
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
| 分组标题 | `SmallTitle(text = hookSectionTitle(section))` | 单行 `中文（English）`；**不要额外加 `Modifier.padding(top = ...)`**，`SmallTitle` 默认 `insideMargin = PaddingValues(28.dp, 8.dp)` 已提供标准间距 |
| 组件行内边距 | `BasicComponentDefaults.InsideMargin`（16dp） | 由 Miuix 组件默认提供，不要覆盖 |
| 页面顶/底内边距 | 使用 `Scaffold` 的 `innerPadding` + `extraBottomPadding` | 不要额外加固定 top 间距 |
| 顶部搜索栏间距 | `Modifier.padding(top = 12.dp, bottom = 8.dp)` | 搜索栏与上方顶栏、下方首个分区的间距 |
| 状态提示 | 标题颜色 `titleColor` | 成功=绿色标题，失败=红色标题，未应用保持默认色 |
| 二级页面 | 继承 `BaseSubPageActivity`，内容用 `SubPageScaffold` 提供的 `contentPadding` | 不要自行处理系统栏 / 顶栏间距 |

**标题规范（强制）：** 分区标题必须为 **单行 `中文（English）`**（例如 `开关卡片（SwitchPreference）`），不得拆成「英文标题 + 中文副标题」两行；由 `HookSection(titleRes, specs, titleEn)` + `hookSectionTitle()` 统一生成。

**为什么统一用 `bottom` 而不是 `top`：** 若卡片使用 `top` 间距，同时标题又带 `top` 修饰符，会导致「标题上间距」与其它页面不一致。统一由「上一张卡片的 `bottom = 12.dp` + `SmallTitle` 默认上内边距」提供间距，可保证任意页面、任意分区数量下的间距完全一致。

### 5.7 组件行为细则

| 组件 | 配置写入 | 作用域申请 | 状态提示 | 备注 |
|---|---|---|---|---|
| `HookSwitchCard` | 开/关均写入 | 仅开启时 | 有 | 关=不 hook，开=hook |
| `HookCheckboxCard` | 勾选/取消均写入 | 仅勾选时 | 有 | 语义同开关 |
| `HookArrowCard` | 不写入 | 无 | 无 | 仅触发 `onClick` 跳转二级页 |
| `HookDropdownCard` | 选中即写入 | 选中非默认项时 | 有 | 第一项为默认（不 hook） |
| `HookRadioCard` | 选中即写入 | 选中非默认项时 | 有 | 右侧复选框，单选语义 |
| `HookSliderCard` | 拖动/输入即写入 | 主开关开启时 | 有（主开关标题） | 见下方细则 |
| `HookTextCard` | 确认时写入 | 无 | 有 | 弹窗输入 |
| `HookOptionView` | 由具体组件决定 | 由具体组件决定 | 由具体组件决定 | 按 `type` 分发，`SPINNER` 复用下拉 |

**下拉 / 单选默认项约定：** `entryValues[0]` 为默认值（`defaultString` 应等于它），第一项文本建议形如「默认（中速）」，表示该状态下不 hook；选择非默认项才申请作用域。

**`HookSliderCard` 细则：**
- `masterKey` 非空时，卡片顶部渲染一个 `SwitchPreference`（标题取 `spec.titleRes`），其开关状态控制滑块是否**出现**（`AnimatedVisibility` 展开/收起动画）与是否**生效**。
- `sliderMin` / `sliderMax` / `sliderStep` / `sliderDecimals` 定义范围、步长与小数位；内部用 `BigDecimal` 定点换算，避免浮点精度丢失。
- `sliderValueLabelRes` 作为滑块行的**标题居左**显示；当前值显示在行末、**紧邻箭头**。
- 点击滑块行空白弹出居中 `WindowDialog`：第一行左右显示最小值 / 最大值，中间显示「当前值 · 默认值」；下方 `TextField` 手动输入（超范围提示错误）；底部按钮 **取消 / 恢复默认 / 确定**。

**`HookTextCard` 细则：** 主界面为一行（标题 + 当前值摘要），点击弹出与滑块一致的对话框：当前值 / 默认值同行左右显示 + `TextField` + 取消 / 恢复默认 / 确定。

**状态示例：** 通过 `OptionSpec.demoStatus` 可强制指定状态，仅用于示例 / 预览。示例页 `HookStatus` 分区演示了成功 / 失败 / 未应用三种状态。

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
    content: @Composable (PaddingValues) -> Unit,
)
```

提供顶栏返回、背景模糊与主题统一的脚手架。

### 6.2 `BaseSubPageActivity`

```kotlin
abstract class BaseSubPageActivity : ComponentActivity() {
    protected abstract val titleRes: Int
    @Composable
    protected abstract fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues)
}
```

自动套用模块配置（主题模式、背景模糊），使用系统默认页面切换动画。子类示例：

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
        HookSection(R.string.section_switch, listOf(spec), "SwitchPreference"),
    ),
)

// 方式二：单组件
Card { HookSwitchCard(spec) }
```

启用开关时，`HookSwitchCard` 会：
1. 写入配置（`ConfigState.set` → `PrefsStore` → 远程偏好）
2. 自动 `ensureScopeFor(spec)` 申请作用域
3. 标题颜色显示成功（绿色）/ 失败（红色）/ 未应用（默认色）

---

## 8. 关键文件索引

| 关注点 | 文件 |
|---|---|
| libxposed 入口 | `hook/src/main/java/.../hook/xposed/XposedEntry.kt` |
| Hook 封装 | `hook/.../hook/xposed/HookApi.kt` |
| 原生 Hook 封装 | `hook/.../hook/nativehook/NativeHookApi.kt`、`BaseNativeHook.kt` |
| 原生入口契约 / 模板 | `hook/src/main/rust/nativehook/`（`src/lib.rs`、`Cargo.toml`） |
| 反射工具 | `hook/.../hook/xposed/Reflect.kt` |
| Hook 状态写入 | `hook/.../hook/xposed/HookStatusWriter.kt` |
| 规则基类 | `hook/.../hook/base/BaseHook.kt`、`BaseLoad.kt`、`HookEntryRegistry.kt` |
| Hook 配置读取 | `hook/.../hook/prefs/HookPrefs.kt` |
| DexKit 缓存 | `hook/.../hook/dexkit/` |
| 模块元数据 | `hook/src/main/resources/META-INF/xposed/{java_init.list,module.prop,scope.list}` |
| 配置系统 | `app/.../prefs/` |
| 服务与状态 | `app/.../xposed/XposedServiceManager.kt`、`HookStatusReader.kt`、`RootHelper.kt` |
| 组件 | `app/.../ui/component/pref/`（`HookCards.kt`、`HookDropdownCards.kt`、`HookSliderCard.kt`、`HookTextCard.kt`、`HookOptionView.kt`、`HookOptionSupport.kt`、`HookOptionsPage.kt`） |
| 二级页面模板 | `app/.../ui/component/SubPageScaffold.kt`、`app/.../ui/screen/subpage/BaseSubPageActivity.kt` |
| 功能页 | `app/.../ui/screen/features/FeaturesPage.kt` |
