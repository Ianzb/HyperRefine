// SPDX-License-Identifier: LGPL-3.0
//! nativehook —— 天气高级外观解锁（原生 Hook）。
//!
//! 天气（`com.miui.weather2`）为 Flutter + Rust 应用。经逆向确认，平板被降级的两处
//! 特效开关无需改 Dart 即可在渲染引擎 `libhyper_opengl.so`（MajesticGL）侧绕过：
//!
//! 1. **雨雪物理**：`MajesticGLRenderer::onDrawFrame` 中的开关为
//!    `isHighDeviceLevelOS1() && !isPadDevice()`，平板上 `isPadDevice()==1` 会直接跳过
//!    雨雪粒子物理，因此把 `isPadDevice()` 伪装为非平板即可放行。
//! 2. **主页渐进模糊**：引擎背景模糊由 `sigma>eps && flag` 决定，`sigma` 由 Dart 调
//!    `setBlurRadius()` 写入 `this+0x3B8`；平板主页这条分支不调用该 FFI，导致 sigma
//!    恒为 0。这里在每帧绘制前若发现 sigma≈0 就补一个兜底值。
//!
//! 只有必要的两项判定被 hook，其余（属性伪造 / 设备分级 / 诊断）已移除。
//!
//! 构建（需 `cargo-ndk`）：
//! ```text
//! cargo ndk -t arm64-v8a -o hook/src/main/jniLibs build --release
//! ```
//! 随后把 `libnativehook.so` 写入 `META-INF/xposed/native_init.list`。

#![allow(non_snake_case)]

use core::ffi::{c_char, c_int, c_void};
use core::sync::atomic::{AtomicBool, AtomicPtr, AtomicUsize, Ordering};
use std::ffi::{CStr, CString};

/// 框架提供的内联 hook 原语。
pub type HookFunType =
    unsafe extern "C" fn(func: *mut c_void, replace: *mut c_void, backup: *mut *mut c_void) -> c_int;
/// 框架提供的取消 hook 原语。
pub type UnhookFunType = unsafe extern "C" fn(func: *mut c_void) -> c_int;
/// 每个 so 载入时的回调。
pub type NativeOnModuleLoaded = unsafe extern "C" fn(name: *const c_char, handle: *mut c_void);

/// 框架传入的结构体（只读）。
#[repr(C)]
pub struct NativeAPIEntries {
    pub version: u32,
    pub hook_func: Option<HookFunType>,
    pub unhook_func: Option<UnhookFunType>,
}

static HOOK: AtomicPtr<c_void> = AtomicPtr::new(core::ptr::null_mut());

/// 仅在此进程生效，避免污染其它作用域进程。
const TARGET_PROCESS: &str = "com.miui.weather2";

/// 渲染引擎库名。雨雪物理与背景模糊都在其中。
const OPENGL_LIB: &str = "libhyper_opengl.so";

extern "C" {
    fn __android_log_print(prio: c_int, tag: *const c_char, fmt: *const c_char, ...) -> c_int;
    fn dlsym(handle: *mut c_void, symbol: *const c_char) -> *mut c_void;
}

fn log(msg: &str) {
    let tag = CString::new("NativeHook").unwrap_or_default();
    let fmt = CString::new("%s").unwrap_or_default();
    let text = CString::new(msg).unwrap_or_default();
    unsafe {
        __android_log_print(4, tag.as_ptr(), fmt.as_ptr(), text.as_ptr());
    }
}

fn cstring(s: &str) -> CString {
    CString::new(s).unwrap_or_default()
}

/// 安装 hook：成功返回可调用原函数的 trampoline。
fn install_hook(target: *mut c_void, replace: *mut c_void) -> Result<*mut c_void, c_int> {
    let raw = HOOK.load(Ordering::Acquire);
    if raw.is_null() {
        return Err(-1);
    }
    // SAFETY: 指针来自框架注入的 HookFunType。
    let hook: HookFunType = unsafe { core::mem::transmute(raw) };
    let mut backup: *mut c_void = core::ptr::null_mut();
    let ret = unsafe { hook(target, replace, &mut backup) };
    if ret == 0 {
        Ok(backup)
    } else {
        Err(ret)
    }
}

/// 在指定库句柄内解析符号地址。
fn lookup(handle: *mut c_void, symbol: &str) -> *mut c_void {
    let name = cstring(symbol);
    unsafe { dlsym(handle, name.as_ptr()) }
}

/// 仅当主进程名命中目标包时才安装。
fn is_target_process() -> bool {
    let Ok(bytes) = std::fs::read("/proc/self/cmdline") else {
        return false;
    };
    let end = bytes.iter().position(|b| *b == 0).unwrap_or(bytes.len());
    let name = String::from_utf8_lossy(&bytes[..end]);
    name == TARGET_PROCESS
}

// ---- 1. 雨雪物理：把 isPadDevice() 伪装为非平板 ----
//
// `onDrawFrame` 的雨雪开关为 `isHighDeviceLevelOS1() && !isPadDevice()`；
// 平板上 `isPadDevice()==1` 会跳过 `WeatherRenderer::onPre/DrawPhysics`。

static ORIG_PAD: AtomicPtr<c_void> = AtomicPtr::new(core::ptr::null_mut());
static ORIG_IN_PAD: AtomicPtr<c_void> = AtomicPtr::new(core::ptr::null_mut());

unsafe extern "C" fn fake_pad(_this: *mut c_void) -> c_int {
    0
}

unsafe extern "C" fn fake_in_pad(_this: *mut c_void) -> c_int {
    0
}

static OPENGL_HOOKED: AtomicBool = AtomicBool::new(false);

/// 渲染引擎加载后安装两处 hook；幂等。
fn hook_engine(handle: *mut c_void) {
    if OPENGL_HOOKED.swap(true, Ordering::AcqRel) {
        return;
    }
    let targets: [(&str, *mut c_void, &AtomicPtr<c_void>); 2] = [
        ("_ZNK11DeviceUtils11isPadDeviceEv", fake_pad as *mut c_void, &ORIG_PAD),
        ("_ZNK11DeviceUtils11isInPadModeEv", fake_in_pad as *mut c_void, &ORIG_IN_PAD),
    ];
    for (symbol, replace, backup) in targets {
        let target = lookup(handle, symbol);
        if target.is_null() {
            log(&format!("symbol not found: {symbol}"));
            continue;
        }
        match install_hook(target, replace) {
            Ok(orig) => {
                backup.store(orig, Ordering::Release);
                log(&format!("hooked {symbol}"));
            }
            Err(code) => log(&format!("hook {symbol} failed: {code}")),
        }
    }
}

// =====================================================================
// AOT 签名补丁引擎
// ---------------------------------------------------------------------
// 目标：不依赖函数符号，对已加载的 ELF（天气的 Dart AOT `libapp.so`）按“字节 +
// 掩码”特征定位目标函数，并改写其中的分支/立即数。这是 HomeTweaks
// （com.miui.home.tweaks）「hook Dart 层」所用的技术。
//
// 流程：dl_iterate_phdr 找模块 → 可执行段内扫描签名 → 相对偏移定位 4 字节指令
//       → mprotect(RWX) → 校验原值 → 写入新值 → 刷 ICache。
//
// SIGS 中每条对应「某版本的一次补丁」；不匹配时原值校验失败会安全跳过。
// 当前天气版本尚未产出可用签名，故表为空（基础设施就绪，见逆向文档 §6）。
// =====================================================================

type DlIteratePhdrCallback =
    unsafe extern "C" fn(info: *mut DlPhdrInfo, size: usize, data: *mut c_void) -> c_int;

#[repr(C)]
struct DlPhdrInfo {
    dlpi_addr: usize,
    dlpi_name: *const c_char,
    dlpi_phdr: *const ElfPhdr,
    dlpi_phnum: u16,
    dlpi_adds: u64,
    dlpi_subs: u64,
    dlpi_tls_modid: usize,
    dlpi_tls_data: *mut c_void,
}

#[repr(C)]
struct ElfPhdr {
    p_type: u32,
    p_flags: u32,
    p_offset: u64,
    p_vaddr: u64,
    p_paddr: u64,
    p_filesz: u64,
    p_memsz: u64,
    p_align: u64,
}

const PT_LOAD: u32 = 1;
const PF_X: u32 = 1;

extern "C" {
    fn dl_iterate_phdr(callback: DlIteratePhdrCallback, data: *mut c_void) -> c_int;
    fn mprotect(addr: *mut c_void, len: usize, prot: c_int) -> c_int;
}

static MOD_BASE: AtomicUsize = AtomicUsize::new(0);
static MOD_EXEC: AtomicUsize = AtomicUsize::new(0);
static MOD_EXEC_LEN: AtomicUsize = AtomicUsize::new(0);

/// 当前要查找的模块名后缀（仅在 `dl_iterate_phdr` 调用期间有效）。
static TARGET_NAME: AtomicPtr<c_char> = AtomicPtr::new(core::ptr::null_mut());

/// 一次补丁：相对签名起点的偏移 + 期望原值 + 新值。
struct AotPatch {
    offset: usize,
    expected: u32,
    replacement: u32,
}

/// 一条签名：目标模块 + 定位特征（bytes+mask，mask=0x00 为通配）与其补丁。
struct AotSignature {
    name: &'static str,
    module: &'static str,
    bytes: &'static [u8],
    mask: &'static [u8],
    patches: &'static [AotPatch],
}

/// 全匹配掩码。
const M_ALL: &[u8] = &[0xffu8; 24];

// ---- 签名表：按当前版本硬编码，版本变更即失效（见逆向文档 §6） ----
//
// 「渐进模糊」解锁：`PassBlurWindow`（`libweather_app.so`）用 `effective_tablet`
// 决定是否走平板/降级分支；该函数（vaddr 0x32b3f0）逻辑为
// `MiuiOsBuild_is_tablet() || (persist.sys.<prop>==2 && ...)`，平板恒返回 true，
// 于是 PassBlur（渐进模糊）不启用。把它入口改成 `mov w0,#0; ret`（恒返回 false），
// 即强制 `effective_tablet=false`，放行手机分支。
static WEATHER_TABLET_BYTES: &[u8] = &[
    0xfd, 0x7b, 0xbd, 0xa9, // stp x29, x30, [sp, #-0x30]!
    0xf5, 0x0b, 0x00, 0xf9, // str x21, [sp, #0x10]
    0xf4, 0x4f, 0x02, 0xa9, // stp x20, x19, [sp, #0x20]
    0xfd, 0x03, 0x00, 0x91, // mov x29, sp
    0xf3, 0x03, 0x00, 0xaa, // mov x19, x0
    0xe3, 0x3b, 0x0c, 0x94, // bl MiuiOsBuild_new@plt
];
static WEATHER_TABLET_PATCHES: &[AotPatch] = &[
    AotPatch { offset: 0, expected: 0xa9bd7bfd, replacement: 0x52800000 }, // mov w0, #0
    AotPatch { offset: 4, expected: 0xf9000bf5, replacement: 0xd65f03c0 }, // ret
];

static AOT_SIGNATURES: &[AotSignature] = &[AotSignature {
    name: "weather-effective-tablet",
    module: "libweather_app.so",
    bytes: WEATHER_TABLET_BYTES,
    mask: M_ALL,
    patches: WEATHER_TABLET_PATCHES,
}];

/// 各签名是否已处理（避免每次库载入重复扫描）。
static AOT_APPLIED: [AtomicBool; 1] = [AtomicBool::new(false)];

/// 查找指定模块（按名后缀），返回 `(load_base, exec_addr, exec_len)`；未找到返回 0。
fn find_module(suffix: &str) -> (usize, usize, usize) {
    MOD_BASE.store(0, Ordering::Release);
    MOD_EXEC.store(0, Ordering::Release);
    MOD_EXEC_LEN.store(0, Ordering::Release);
    let name = cstring(suffix);
    TARGET_NAME.store(name.as_ptr() as *mut c_char, Ordering::Release);
    unsafe { dl_iterate_phdr(phdr_cb, core::ptr::null_mut()) };
    (
        MOD_BASE.load(Ordering::Acquire),
        MOD_EXEC.load(Ordering::Acquire),
        MOD_EXEC_LEN.load(Ordering::Acquire),
    )
}

/// 记录目标模块的 load base 与可执行段范围。
unsafe extern "C" fn phdr_cb(info: *mut DlPhdrInfo, _size: usize, _data: *mut c_void) -> c_int {
    let info = unsafe { &*info };
    if info.dlpi_name.is_null() {
        return 0;
    }
    let target = TARGET_NAME.load(Ordering::Acquire);
    if target.is_null() {
        return 0;
    }
    let target = unsafe { CStr::from_ptr(target) }.to_bytes();
    let name = unsafe { CStr::from_ptr(info.dlpi_name) }.to_bytes();
    if name.len() < target.len() || &name[name.len() - target.len()..] != target {
        return 0;
    }
    MOD_BASE.store(info.dlpi_addr, Ordering::Release);
    for i in 0..info.dlpi_phnum as usize {
        let ph = unsafe { &*info.dlpi_phdr.add(i) };
        if ph.p_type == PT_LOAD && (ph.p_flags & PF_X) != 0 {
            MOD_EXEC.store(info.dlpi_addr + ph.p_vaddr as usize, Ordering::Release);
            MOD_EXEC_LEN.store(ph.p_memsz as usize, Ordering::Release);
            break;
        }
    }
    1
}

/// 在可执行段内按签名扫描，返回命中地址。
fn scan_signature(sig: &AotSignature) -> *mut u8 {
    let start = MOD_EXEC.load(Ordering::Acquire) as *const u8;
    let len = MOD_EXEC_LEN.load(Ordering::Acquire);
    let n = sig.bytes.len();
    if start.is_null() || len == 0 || n == 0 || sig.mask.len() != n {
        return core::ptr::null_mut();
    }
    let limit = len.saturating_sub(n);
    let mut i = 0usize;
    while i <= limit {
        let mut ok = true;
        let mut j = 0usize;
        while j < n {
            // SAFETY: i+j < len 由 limit 保证。
            let cur = unsafe { *start.add(i + j) };
            if (sig.mask[j] & cur) != (sig.mask[j] & sig.bytes[j]) {
                ok = false;
                break;
            }
            j += 1;
        }
        if ok {
            return unsafe { start.add(i) as *mut u8 };
        }
        i += 4; // AArch64 指令 4 字节对齐
    }
    core::ptr::null_mut()
}

/// 刷新指令缓存（AArch64）。
unsafe fn flush_icache(begin: usize, end: usize) {
    let mut addr = begin & !63usize;
    while addr < end {
        unsafe { core::arch::asm!("dc cvau, {a}", a = in(reg) addr, options(nostack, preserves_flags)) };
        addr += 64;
    }
    unsafe { core::arch::asm!("dsb ish", options(nostack, preserves_flags)) };
    let mut addr = begin & !63usize;
    while addr < end {
        unsafe { core::arch::asm!("ic ivau, {a}", a = in(reg) addr, options(nostack, preserves_flags)) };
        addr += 64;
    }
    unsafe { core::arch::asm!("dsb ish", options(nostack, preserves_flags)) };
    unsafe { core::arch::asm!("isb", options(nostack, preserves_flags)) };
}

/// 在 `at` 处写入 4 字节：改权限、校验原值、写回、刷 ICache。
unsafe fn apply_patch(at: *mut u8, expected: u32, replacement: u32) -> bool {
    if at.is_null() {
        return false;
    }
    let cur = unsafe { *(at as *const u32) };
    if cur != expected {
        return false;
    }
    let page = 4096usize;
    let addr = at as usize;
    let page_start = addr & !(page - 1);
    let span = (addr + 4) - page_start;
    let rc = unsafe { mprotect(page_start as *mut c_void, span, 7) };
    if rc != 0 {
        return false;
    }
    unsafe { *(at as *mut u32) = replacement };
    unsafe { flush_icache(addr, addr + 4) };
    true
}

/// 逐个应用签名表；模块未就绪则下次库载入时重试。
fn apply_aot_signatures() {
    for (idx, sig) in AOT_SIGNATURES.iter().enumerate() {
        if AOT_APPLIED[idx].load(Ordering::Acquire) {
            continue;
        }
        let (base, exec, exec_len) = find_module(sig.module);
        if base == 0 || exec == 0 {
            continue; // 模块尚未加载，等下次回调再试
        }
        let hit = scan_signature(sig);
        if hit.is_null() {
            log(&format!(
                "AOT: {} not found in {} (base=0x{base:x}, exec=0x{exec:x}, len=0x{exec_len:x})",
                sig.name, sig.module
            ));
            AOT_APPLIED[idx].store(true, Ordering::Release);
            continue;
        }
        let mut ok = 0usize;
        for p in sig.patches {
            let at = unsafe { hit.add(p.offset) };
            if unsafe { apply_patch(at, p.expected, p.replacement) } {
                ok += 1;
            }
        }
        log(&format!("AOT: {} patched {ok}/{}", sig.name, sig.patches.len()));
        AOT_APPLIED[idx].store(true, Ordering::Release);
    }
}

/// 载入回调：渲染引擎就绪后安装 hook，并在任一库载入时尝试 AOT 补丁。
unsafe extern "C" fn on_module_loaded(name: *const c_char, handle: *mut c_void) {
    if name.is_null() || handle.is_null() {
        return;
    }
    let lib_name = unsafe { CStr::from_ptr(name) }.to_string_lossy();
    if lib_name.ends_with(OPENGL_LIB) {
        hook_engine(handle);
    }
    apply_aot_signatures();
}

/// 框架入口：与 libxposed 原生入口 `native_init` 一一对应（C ABI）。
#[no_mangle]
pub extern "C" fn native_init(entries: *const NativeAPIEntries) -> Option<NativeOnModuleLoaded> {
    if entries.is_null() {
        return None;
    }
    let entries = unsafe { &*entries };
    if let Some(hook) = entries.hook_func {
        HOOK.store(hook as *mut c_void, Ordering::Release);
    }
    if !is_target_process() {
        return None;
    }
    log(&format!("native_init called, api version={}", entries.version));
    // 目标 Rust 库可能已先于本模块加载，这里先尝试一次 AOT 补丁。
    apply_aot_signatures();
    Some(on_module_loaded)
}
