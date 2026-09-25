# 原生 Hook（NativeHook）开发指南

本文档面向使用本模板做**原生 Hook** 的开发者。本项目的 NativeHook **只服务 Rust 应用**（`flutter_rust_bridge` 生成的库、纯 Rust `.so` 等），因此全流程不涉及 C/C++：模块侧与目标侧都以 Rust 为中心。

> 阅读前置：[接口文档](API.md) · [模块开发工作流](WORKFLOW.md)（真机 `adb` 授权、隐私与合规）。
>
> **术语约定**：本项目统一使用 **NativeHook**（面向 Rust 应用的原生 Hook）与 **JavaHook**（Java/Kotlin Hook）两个词；早期草稿中的 `rusthook` 已全部更名为 `nativehook`（含目录、Cargo 包名与日志 TAG）。

---

## 目录

0. [两种 Hook 的区别](#0-两种-hook-的区别)
1. [为什么需要 NativeHook](#1-为什么需要-nativehook)
2. [libxposed 原生 API 契约（Rust）](#2-libxposed-原生-api-契约rust)
3. [能力矩阵](#3-能力矩阵)
4. [目标定位（Rust 符号）](#4-目标定位rust-符号)
5. [修改手段](#5-修改手段)
6. [环境与构建（cargo-ndk）](#6-环境与构建cargo-ndk)
7. [一键封装 API](#7-一键封装-api)
8. [完整工作流（Rust 应用 Hook）](#8-完整工作流rust-应用-hook)
9. [最小模板（Rust）](#9-最小模板rust)
10. [常见坑与排查清单](#10-常见坑与排查清单)
11. [安全与合规](#11-安全与合规)
12. [文件索引](#12-文件索引)

---

## 0. 两种 Hook 的区别

| 维度 | JavaHook | NativeHook |
|---|---|---|
| 作用对象 | ART 方法调用 | Rust 编译产物的机器码 / 符号 |
| 入口 | `XposedModule`（`java_init.list`） | `native_init`（`native_init.list`） |
| Kotlin 封装 | `HookHelper` + `BaseHook` | `NativeHookHelper` + `BaseNativeHook` |
| 代码位置 | 模块 dex | 模块 Rust 库（`libnativehook.so`） |
| 定位方式 | 反射 / DexKit | Rust 符号表 / 指令签名 / 反汇编 |
| 目标场景 | Java/Kotlin 逻辑 | `flutter_rust_bridge` / 纯 Rust 库 |
| 失效场景 | Flutter/Dart AOT、纯 Rust 库 | 需自行处理 ABI、指令与内存保护 |

**选择建议**：

- 目标逻辑是 Java/Kotlin 方法 → 用 **JavaHook**（配合 DexKit）。
- 目标逻辑是 Rust（`lib*_frb.so`、`rust_*`、含 Rust mangled 符号的 `.so`）→ 用 **NativeHook**。
- 二者可同时使用，且共享同一套配置、状态、热重载、安全模式与版本筛选链路。

---

## 1. 为什么需要 NativeHook

JavaHook 作用于 ART 方法调用，前提是目标逻辑以 Java/Kotlin 方法存在。当下很多应用把核心逻辑写在 Rust 里：

| 场景 | 说明 |
|---|---|
| `flutter_rust_bridge` | Flutter UI + Rust 核心逻辑，导出 `frb_*` / `wire_*` 的 C ABI 函数 |
| 纯 Rust 库 | 如 `rust_maml_sdk`、`libresources_frb.so` 一类 `.so`，Java 层只是薄壳 |
| 安全 / 校验逻辑 | 授权校验、签名、风控等常下沉到 Rust |

NativeHook 的本质差异：改的是 **Rust 编译产物的机器码 / 符号**；没有反射与元数据，靠 **Rust 符号表、指令特征或反汇编** 定位；模块只在作用域命中的目标进程内加载，入口极早，不能做阻塞工作。

---

## 2. libxposed 原生 API 契约（Rust）

框架在目标进程加载 `native_init.list` 中声明的 `.so` 后，调用其导出的 **`native_init`**，把内联 hook 原语交给模块。契约在 [`nativehook/src/lib.rs`](../hook/src/main/rust/nativehook/src/lib.rs) 中以 Rust 类型定义（导出为 C ABI）：

```rust
pub type HookFunType =
    unsafe extern "C" fn(func: *mut c_void, replace: *mut c_void, backup: *mut *mut c_void) -> c_int;
pub type UnhookFunType = unsafe extern "C" fn(func: *mut c_void) -> c_int;
pub type NativeOnModuleLoaded = unsafe extern "C" fn(name: *const c_char, handle: *mut c_void);

#[repr(C)]
pub struct NativeAPIEntries {
    pub version: u32,
    pub hook_func: Option<HookFunType>,
    pub unhook_func: Option<UnhookFunType>,
}

#[no_mangle]
pub extern "C" fn native_init(entries: *const NativeAPIEntries) -> Option<NativeOnModuleLoaded>;
```

要点：

- `native_init` 必须 `#[no_mangle] pub extern "C"`，否则符号会被 mangle 或 strip。
- 返回值是**库加载回调**：目标进程每载入一个 `.so` 都会回调（用于「等目标 Rust 库出现后再 hook」）；不需要则返回 `None`。
- `NativeAPIEntries` 只读，禁止修改。
- `native_init` 里可做三件事：存下 `hook_func` / `unhook_func`；挂系统库 hook；返回加载回调。

---

## 3. 能力矩阵

| 能力 | 说明 | 典型做法 |
|---|---|---|
| 符号 hook | 替换 Rust 导出函数，保留原函数 trampoline | `lookup(handle, "frb_verify")` + `install_hook` |
| 系统库/PLT hook | 拦截 libc / 系统库调用 | `install_hook(fopen, ...)` |
| 指令级 patch | 改写目标函数中的指令（立即数、分支） | 自写引擎 + `mprotect` |
| 符号解析 | 从 `.dynsym` / `.symtab` / `.gnu_debugdata` 取地址 | `dlsym` / 自解析 ELF |
| 库加载拦截 | 任意 so 载入时回调，按路径匹配 | `NativeOnModuleLoaded` |
| JNI 表 hook | 拦截 `JNIEnv` 函数表 | `install_hook(env->functions->FindClass, ...)` |
| 内存读写 | 读/写目标内存、改权限 | `mprotect` / `process_vm_*` |

---

## 4. 目标定位（Rust 符号）

按可靠性从高到低：

1. **C ABI 导出**：`flutter_rust_bridge` 生成的 `frb_*` / `wire_*`（`#[no_mangle] extern "C"`），`dlsym` 最稳。
2. **Rust mangled 符号**：形如 `_ZN...17h<hash>E`（legacy）或 `_RNv...`（v0 mangling）。Rust release 常保留 `.symtab`；用 `rustfilt` 还原可读名后定位。
3. **符号表 / 调试信息**：`.symtab`；`.gnu_debugdata`（MiniDebugInfo，XZ 压缩的迷你符号表）。
4. **指令签名**：稳定机器码字节特征在全 `.text` 扫描；注意 LTO 后函数边界可能被抹平。
5. **字符串反查**：Rust 的 `&str`、错误文案常留在 `.rodata`，可反查引用。

> 原则：**优先 C ABI，其次 Rust 符号，最后才是指令签名；尽量不写死绝对地址**。

```bash
# 动态符号（C ABI 导出）
nm -D --defined-only libresources_frb.so | head
# 全部符号（含 Rust mangled），过滤目标模块
nm libresources_frb.so | grep -i 'verify\|token\|sign'
# 还原可读名（legacy / v0 都可用 rustfilt）
rustfilt < symbol.txt | grep 'mycrate::security::verify'
```

---

## 5. 修改手段

| 手段 | 粒度 | 适用 | 代价 |
|---|---|---|---|
| 符号替换（inline hook） | 整个函数 | 有 C ABI 导出 / 可解析的 Rust 符号 | 需 hook 引擎、处理 trampoline |
| JNI 函数表 hook | 整个 JNI 方法 | 只改 Java 侧看到的 native 行为 | 只影响 JNI 表调用 |
| PLT/GOT hook | 单个导入调用 | 只改对某外部符号的调用 | 只对 PLT 调用生效 |
| 指令级 patch | 单条/若干条指令 | 改常量、分支；函数被内联时 | 需指令编解码、`mprotect`、ICache 一致性、安全校验 |

指令级 patch 三要素：**改权限**（`mprotect` 到 `R|W|X`）、**指令编码**（ARM64 定长 4 字节）、**安全校验**（偏移范围 + 原值比对 + 幂等，写完按需冲刷 ICache）。

---

## 6. 环境与构建（cargo-ndk）

NativeHook 只涉及 Rust，用 `cargo-ndk` 交叉编译 `cdylib`，产物 `libnativehook.so`：

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
    -o hook/src/main/jniLibs build --release
```

参考 crate：[`hook/src/main/rust/nativehook`](../hook/src/main/rust/nativehook)（Gradle 不参与 Rust 编译，`.so` 由 cargo-ndk 产出后放入 `jniLibs/`）。

通用约束：

- **多 ABI**：至少 `arm64-v8a`；兼容 32 位加 `armeabi-v7a`；模拟器 `x86_64`。
- **`extractNativeLibs`**：模块与应用不一致会导致 `dlopen` 行为差异。
- **`multiArch`**：64 位进程只加载 64 位库，缺失会 `UnsatisfiedLinkError`。
- **声明入口**：把 `.so` 名逐行写入 `hook/src/main/resources/META-INF/xposed/native_init.list`。

---

## 7. 一键封装 API

原生 Hook 由 Rust 库实现，但「声明 / 加载 / 开关 / 状态 / 热重载 / 安全模式 / 版本筛选」全部由 Kotlin 封装接管，用法与 JavaHook 对称。

包名：`cn.ianzb.hyperrefine.hook.nativehook`

```kotlin
// 声明一条原生规则：只需要库名
class FrbNative : BaseNativeHook() {
    override val libraryName = "nativehook"
    override val key = "native_resources_verify"
    // 版本筛选（可选）：不满足时不会把库载入目标进程
    override val versionGate = hookVersionGate { android { ge("33") } }
    // 设备筛选（可选）：默认各设备通用；不匹配时同样不加载
    override val deviceScope = setOf(DeviceType.PHONE, DeviceType.FOLD)
}

// 在 BaseLoad 中一行声明（与 initHook 对称）
override fun onPackageLoaded(target: PackageTarget) {
    initNativeHook(FrbNative(), HookPrefs.getBoolean("native_resources_verify", false))
}
```

- `NativeHookHelper`：库名归一化、`System.loadLibrary`、去重、异常兜底、状态上报、热重载 `reset()`。
- `BaseNativeHook`：`libraryName` / `key` / `required` / `description` / `versionGate` / `deviceScope` / `appliesTo(ctx)`。
- 版本筛选 DSL 见 [接口文档 · 1.11](API.md#111-版本筛选hookversiongate)；设备筛选见 [接口文档 · 1.12](API.md#112-设备筛选devicescope)。
- 设备类型默认由模块自动判定，也可在设置页「模块 → 当前设备类型」手动覆盖（`auto` / `phone` / `pad` / `fold`，经远程偏好下发，重启 / 热重载后生效）。

---

## 8. 完整工作流（Rust 应用 Hook）

> 涉及真机的 `adb` 操作必须先取得用户逐次授权（见 [模块开发工作流](WORKFLOW.md)）。

### Step 0 — 明确目标

确定：目标包的哪个进程、要改哪段 Rust 逻辑、期望效果；是否需要按版本分支。

### Step 1 — 定位目标

- 静态分析：`adb pull` 目标 APK（经授权）→ 解包 → 找 `.so`。
- 识别 Rust 产物：文件名（`*_frb.so`、`libresources_frb.so`）、`flutter_rust_bridge`、`rust_*`；符号含 `_ZN...17h...E` / `_RNv...`，字符串含 `rustc`、`core::`、`alloc::`。
- 记录：库名、符号 / 签名、偏移、指令特征。

### Step 2 — 选择修改方式

C ABI 导出 → inline hook；只有 Rust mangled 符号 → `rustfilt` 还原后 hook；被内联 / 内部函数 → 指令 patch。

### Step 3 — 写 `native_init`

在 `nativehook/src/lib.rs` 实现 `native_init`，存下 hook 原语并返回 `on_module_loaded`。见 [第 9 节](#9-最小模板rust)。

### Step 4 — 编译并声明

`cargo ndk ... -o hook/src/main/jniLibs build --release`；`.so` 名写入 `native_init.list`；确认 ABIs 齐全。

### Step 5 — Kotlin 一键接入

`BaseNativeHook` + `initNativeHook(...)`；配置键与 `OptionSpec.key` 一致；需要按版本 / 设备区分时加 `versionGate` / `deviceScope`（默认各设备通用）。

### Step 6 — 状态回传与安全兜底

加载结果写入 `HookStatusWriter`；关键应用重复崩溃会自动进入安全模式，可在「设置 → 模块 → 安全模式」逐项开关（见 [接口文档 6.3](API.md#63-safemodeactivity安全模式页)）。

### Step 7 — 热重载与清理

`NativeHookHelper.reset()` 在热重载时清空跟踪记录；Rust hook **必须幂等**（重复安装要么覆盖、要么跳过）。

### Step 8 — 联调与验证

- `adb logcat -s NativeHook` 观察 `library loaded` 与 hook 结果；
- `dlsym` 失败时结合 `nm` 判断是「符号被 strip」还是「名字写错」；
- 崩溃看 `SIGSEGV fault addr` 与 backtrace 是否落在 `fake_*`，据此区分 ABI / 调用约定问题。

---

## 9. 最小模板（Rust）

| 文件 | 说明 |
|---|---|
| [`hook/src/main/rust/nativehook/src/lib.rs`](../hook/src/main/rust/nativehook/src/lib.rs) | Rust 入口（`native_init` + `install_hook`） |
| [`hook/src/main/rust/nativehook/Cargo.toml`](../hook/src/main/rust/nativehook/Cargo.toml) | `cdylib` 配置 |

核心骨架：

```rust
// 改成你要 hook 的 Rust 库与符号（优先 C ABI 导出）
const TARGET_LIB: &str = "libresources_frb.so";
const TARGET_SYMBOL: &str = "frb_verify_license";

#[no_mangle]
pub extern "C" fn native_init(entries: *const NativeAPIEntries) -> Option<NativeOnModuleLoaded> {
    if entries.is_null() { return None; }
    let entries = unsafe { &*entries };
    if let Some(hook) = entries.hook_func {
        HOOK.store(hook as *mut c_void, Ordering::Release);
    }
    Some(on_module_loaded)
}

unsafe extern "C" fn on_module_loaded(name: *const c_char, handle: *mut c_void) {
    let lib = unsafe { CStr::from_ptr(name) }.to_string_lossy();
    if !lib.ends_with(TARGET_LIB) { return; }
    if !ORIGINAL.load(Ordering::Acquire).is_null() { return; } // 幂等
    let sym = lookup(handle, TARGET_SYMBOL);
    if sym.is_null() { return; }
    if let Ok(backup) = install_hook(sym, fake_target as *mut c_void) {
        ORIGINAL.store(backup, Ordering::Release);
    }
}
```

> 目标 Rust 库常被 `strip`：优先找 C ABI 导出（`frb_*` / `wire_*`），没有再用 mangled 符号或指令签名。

---

## 10. 常见坑与排查清单

| 现象 | 可能原因 | 处理 |
|---|---|---|
| 没有 `native_init called` | `.so` 未写入 `native_init.list`；ABI 不匹配；符号被 strip | 检查 list、`lib/<abi>/`、导出符号与 `#[no_mangle] pub extern "C"` |
| `UnsatisfiedLinkError` | so 未打包 / ABI 缺失 | 补齐 ABIs，或 `multiArch` |
| `SIGSEGV` 写入代码页 | 未 `mprotect` / 未对齐 / 未冲刷 ICache | 页对齐、`R|W|X`、flush |
| hook 不生效 | 目标被内联 / 泛型多实例 / 未走到调用点 | 改用 C ABI 边界或调用点，见 [第 4 节](#4-目标定位rust-符号) |
| 找不到目标函数 | 符号被剥离 / 版本漂移 | 用 `.gnu_debugdata`，或用指令签名 |
| 启动卡顿 | `native_init` 做了阻塞 / Binder | 移到加载回调或异步线程 |
| 热重载后重复 hook 崩溃 | Rust hook 不幂等 | 用状态位保证只安装一次 |
| 只在部分机型生效 | 架构 / 版本差异 | `versionGate` 分支 + 运行时探测 |

---

## 11. 安全与合规

- **改机需授权**：`adb` 操作逐次取得用户同意；只读优先，遵守 [白名单](WORKFLOW.md#13-adb-安全白名单)。
- **隐私优先**：不抓取、不保存、不上传用户数据。
- **合规**：不得复制第三方专有应用的代码或资源；引用第三方 hook 引擎前先核对许可证（见 [WORKFLOW.md](WORKFLOW.md#2-场景-b参考--复刻其他模块项目)）。

---

## 12. 文件索引

| 关注点 | 文件 |
|---|---|
| Kotlin 一键封装 | `hook/src/main/java/.../hook/nativehook/NativeHookApi.kt` |
| 原生规则基类 | `hook/src/main/java/.../hook/nativehook/BaseNativeHook.kt` |
| 版本筛选 API | `hook/src/main/java/.../hook/rule/`（`Version`、`VersionRules`、`HookVariant`） |
| Load 声明入口 | `hook/src/main/java/.../hook/base/BaseLoad.kt`（`initNativeHook`） |
| Rust 模板 | `hook/src/main/rust/nativehook/` |
| 原生入口声明 | `hook/src/main/resources/META-INF/xposed/native_init.list` |

参考：

- libxposed API：<https://libxposed.github.io/api/index-all.html>
- LSPosed Native Hook Wiki：<https://github.com/LSPosed/LSPosed/wiki/Native-Hook>
