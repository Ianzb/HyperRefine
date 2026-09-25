package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.ui.util.isInDarkTheme
import cn.ianzb.hyperrefine.xposed.HookStatus
import cn.ianzb.hyperrefine.xposed.HookStatusReader
import cn.ianzb.hyperrefine.xposed.XposedServiceManager
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults

/** 解析依赖项：依赖项满足条件时组件启用。 */
@Composable
fun rememberDependencyEnabled(spec: OptionSpec): Boolean {
    val dependencyKey = spec.dependsOn ?: return true
    val dependencyDefault = OptionRegistry.find(dependencyKey)?.defaultBoolean ?: false
    val dependencyValue = ConfigState.bool(dependencyKey, dependencyDefault)
    return if (spec.dependsOnValue) dependencyValue else !dependencyValue
}

/**
 * 选项被启用时，自动为未授权的作用域目标发起申请。
 */
fun ensureScopeFor(spec: OptionSpec) {
    if (spec.targetPackages.isNotEmpty()) {
        XposedServiceManager.ensureScope(spec.targetPackages)
    }
}

/** 计算某个配置项当前的 hook 状态。 */
@Composable
fun rememberHookStatus(spec: OptionSpec): HookStatus {
    spec.demoStatus?.let { return it }
    val activated = XposedServiceManager.isActivated
    val scope = XposedServiceManager.scope
    val raw = HookStatusReader.statusOf(spec.statusId)
    if (!activated) return HookStatus.NOT_APPLIED
    if (spec.targetPackages.isNotEmpty() && spec.targetPackages.none { it in scope }) {
        return HookStatus.NOT_APPLIED
    }
    return when (raw) {
        true -> HookStatus.SUCCESS
        false -> HookStatus.FAILED
        null -> HookStatus.NOT_APPLIED
    }
}

/**
 * 标题颜色随 hook 状态变化：成功 = 绿色，失败 = 红色，未应用 = 默认色。
 * 作为各卡片的 `titleColor` 传入，不额外占用布局空间。
 */
@Composable
fun HookStatusTitleColor(spec: OptionSpec): BasicComponentColors {
    val status = rememberHookStatus(spec)
    if (status == HookStatus.NOT_APPLIED) return BasicComponentDefaults.titleColor()
    val isDark = isInDarkTheme()
    val color = if (status == HookStatus.SUCCESS) {
        if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
    } else {
        if (isDark) Color(0xFFF87171) else Color(0xFFDC3545)
    }
    return BasicComponentDefaults.titleColor(color = color)
}
