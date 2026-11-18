package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.ui.util.LocalPreferenceBackdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference

/**
 * 下拉卡片：第一个选项为“默认（不 hook）”，其它选项按值 hook。
 */
@Composable
fun HookDropdownCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = LocalPreferenceBackdrop.current,
) {
    val enabled = rememberOptionEnabled(spec)
    val value = ConfigState.string(spec.key, spec.defaultString)
    val items = spec.entryResIds.map { stringResource(it) }
    val selectedIndex = spec.entryValues.indexOf(value).takeIf { it >= 0 } ?: 0
    GlassDropdownPreference(
        items = items,
        selectedIndex = selectedIndex,
        title = stringResource(spec.titleRes),
        summary = spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) },
        enabled = enabled,
        backdrop = backdrop,
        renderInRootScaffold = true,
        modifier = modifier,
        onSelectedIndexChange = { index ->
            ConfigState.set(spec.key, spec.entryValues.getOrElse(index) { spec.defaultString })
            if (index != defaultIndex(spec)) ensureScopeFor(spec)
        },
    )
}

/** 单选卡片。 */
@Composable
fun HookRadioCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val enabled = rememberOptionEnabled(spec)
    val value = ConfigState.string(spec.key, spec.defaultString)
    Column(modifier = modifier) {
        spec.entryResIds.forEachIndexed { index, resId ->
            val entryValue = spec.entryValues.getOrElse(index) { spec.defaultString }
            RadioButtonPreference(
                title = stringResource(resId),
                selected = value == entryValue,
                onClick = {
                    ConfigState.set(spec.key, entryValue)
                    if (index != defaultIndex(spec)) ensureScopeFor(spec)
                },
                enabled = enabled,
            )
        }
    }
}

private fun defaultIndex(spec: OptionSpec): Int =
    spec.entryValues.indexOf(spec.defaultString).takeIf { it >= 0 } ?: 0
