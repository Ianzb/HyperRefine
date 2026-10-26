package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cn.ianzb.hyperrefine.hook.device.DeviceContext
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.xposed.HookStatus
import cn.ianzb.hyperrefine.xposed.HookStatusStore
import cn.ianzb.hyperrefine.xposed.XposedServiceManager

/**
 * 当前生效的设备形态：优先设置页「当前设备类型」的覆盖值，否则使用模块自动判定。
 *
 * 读取 [ConfigState]，因此更改设备类型时会实时重组刷新，无需重启页面。
 */
@Composable
fun rememberEffectiveDeviceType(): DeviceType {
    val override = ConfigState.string(DeviceContext.KEY_DEVICE_TYPE, DeviceType.OVERRIDE_AUTO)
    return DeviceType.fromKey(override) ?: DeviceContext.detected.type
}

/**
 * 设备形态是否在 [OptionSpec.deviceScope] 白名单内；未声明白名单（null / 空）视为各设备通用。
 *
 * 非白名单设备返回 false，用于把设备独占功能**禁用而不隐藏**。
 */
@Composable
fun rememberDeviceScopeEnabled(spec: OptionSpec): Boolean {
    val scope = spec.deviceScope
    if (scope.isNullOrEmpty()) return true
    return rememberEffectiveDeviceType() in scope
}

/**
 * 解析依赖项：沿 [OptionSpec.dependsOn] 链向上逐级校验，任一级未满足即禁用（传递依赖）。
 *
 * 例如「笔记」依赖「应用专属适配」，「应用专属适配」依赖顶栏总开关；顶栏总开关关闭时，
 * 「笔记」也会一并禁用。
 */
@Composable
fun rememberDependencyEnabled(spec: OptionSpec): Boolean {
    var current = spec
    val visited = HashSet<String>()
    while (true) {
        val dependencyKey = current.dependsOn ?: return true
        if (!visited.add(dependencyKey)) return true
        val dependencySpec = OptionRegistry.find(dependencyKey) ?: return true
        if (current.dependsOnValues.isNotEmpty()) {
            val value = ConfigState.string(dependencyKey, dependencySpec.defaultString)
            if (value !in current.dependsOnValues) return false
        } else {
            val dependencyValue = ConfigState.bool(dependencyKey, dependencySpec.defaultBoolean)
            if (current.dependsOnValue != dependencyValue) return false
        }
        current = dependencySpec
    }
}

/**
 * 组件是否可用：同时满足「依赖项」与「设备形态白名单」。
 *
 * 各 Hook 卡片统一以它作为 `enabled`，保证设备独占功能在非白名单设备上禁用（灰显）而非隐藏。
 */
@Composable
fun rememberOptionEnabled(spec: OptionSpec): Boolean =
    rememberDependencyEnabled(spec) && rememberDeviceScopeEnabled(spec)

/**
 * 该配置项在当前版本 / 当前开机内的四态状态。
 *
 * 读取 [HookStatusStore.snapshot]；目标进程重新回报后自动刷新。
 * 灰色「不可用」只表示 UI 前置条件不满足：作用域未勾选，或设备形态不在白名单内。
 * 条件满足后才看目标进程回报：有成功记录为「已生效」，已回报但无成功记录为「未生效」，
 * 完全没回报为「等待启动」。安装失败不再单独显示灰色。
 */
@Composable
fun rememberHookStatus(spec: OptionSpec): HookStatus {
    val snapshot by HookStatusStore.snapshot.collectAsState()
    val grantedScope = XposedServiceManager.scope
    val deviceSupported = rememberDeviceScopeEnabled(spec)
    val scopeGranted = spec.targetPackages.isEmpty() || spec.targetPackages.all { it in grantedScope }
    // 订阅快照 / 作用域 / 设备形态以触发重组；状态判定统一走 statusFor，避免 UI 与存储两套规则漂移。
    return remember(snapshot, spec, deviceSupported, scopeGranted) {
        when {
            !deviceSupported || !scopeGranted -> HookStatus.UNAVAILABLE
            else -> HookStatusStore.statusFor(spec.key, spec.targetPackages)
        }
    }
}

/**
 * 选项被启用时，自动为未授权的作用域目标发起申请。
 */
fun ensureScopeFor(spec: OptionSpec) {
    if (spec.targetPackages.isNotEmpty()) {
        XposedServiceManager.ensureScope(spec.targetPackages)
    }
}
