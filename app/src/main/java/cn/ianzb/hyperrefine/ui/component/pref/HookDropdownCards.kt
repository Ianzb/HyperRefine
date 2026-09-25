package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionSpec
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * 下拉卡片：第一个选项为“默认（不 hook）”，其它选项按值 hook。
 */
@Composable
fun HookDropdownCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val enabled = rememberDependencyEnabled(spec)
    val value = ConfigState.string(spec.key, spec.defaultString)
    val items = spec.entryResIds.map { stringResource(it) }
    val selectedIndex = spec.entryValues.indexOf(value).takeIf { it >= 0 } ?: 0
    WindowDropdownPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = stringResource(spec.titleRes),
        summary = spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) },
        enabled = enabled,
        modifier = modifier,
        titleColor = HookStatusTitleColor(spec),
        onSelectedIndexChange = { index ->
            ConfigState.set(spec.key, spec.entryValues.getOrElse(index) { spec.defaultString })
            if (index != defaultIndex(spec)) ensureScopeFor(spec)
        },
    )
}

/**
 * 单选卡片：右侧 checkbox 表示选中项（单选语义）。
 */
@Composable
fun HookRadioCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val enabled = rememberDependencyEnabled(spec)
    val value = ConfigState.string(spec.key, spec.defaultString)
    val statusTitleColor = HookStatusTitleColor(spec)
    Column(modifier = modifier) {
        spec.entryResIds.forEachIndexed { index, resId ->
            val entryValue = spec.entryValues.getOrElse(index) { spec.defaultString }
            val selected = value == entryValue
            CheckboxPreference(
                title = stringResource(resId),
                checked = selected,
                onCheckedChange = {
                    ConfigState.set(spec.key, entryValue)
                    if (index != defaultIndex(spec)) ensureScopeFor(spec)
                },
                enabled = enabled,
                checkboxLocation = CheckboxLocation.End,
                titleColor = if (selected) statusTitleColor else BasicComponentDefaults.titleColor(),
            )
        }
    }
}

private fun defaultIndex(spec: OptionSpec): Int =
    spec.entryValues.indexOf(spec.defaultString).takeIf { it >= 0 } ?: 0
