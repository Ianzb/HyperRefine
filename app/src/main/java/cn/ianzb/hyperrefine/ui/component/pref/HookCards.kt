package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.xposed.HookStatusStore
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 副标题：基础文案 +（可选）Hook 生效状态。 */
@Composable
private fun optionSummary(spec: OptionSpec): String? {
    val base = spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) }
    if (!spec.showStatus) return base
    val applied = rememberHookApplied(spec.key)
    val status = stringResource(
        if (applied) R.string.hook_status_applied else R.string.hook_status_not_applied
    )
    return listOfNotNull(base, status).joinToString(" · ")
}

/** 带 switch 的卡片：关时不 hook，开时 hook。 */
@Composable
fun HookSwitchCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = rememberOptionEnabled(spec)
    val checked = ConfigState.bool(spec.key, spec.defaultBoolean)
    SwitchPreference(
        title = stringResource(spec.titleRes),
        summary = optionSummary(spec),
        checked = checked,
        onCheckedChange = {
            ConfigState.set(spec.key, it)
            // 开关变化后需重启目标进程才会生效；先清除旧证据，待其重新回报。
            HookStatusStore.removeKeys(context, listOf(spec.key))
            if (it) ensureScopeFor(spec)
        },
        enabled = enabled,
        modifier = modifier,
    )
}

/** 带 checkbox 的卡片。 */
@Composable
fun HookCheckboxCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = rememberOptionEnabled(spec)
    val checked = ConfigState.bool(spec.key, spec.defaultBoolean)
    CheckboxPreference(
        title = stringResource(spec.titleRes),
        summary = optionSummary(spec),
        checked = checked,
        onCheckedChange = {
            ConfigState.set(spec.key, it)
            HookStatusStore.removeKeys(context, listOf(spec.key))
            if (it) ensureScopeFor(spec)
        },
        enabled = enabled,
        modifier = modifier,
    )
}

/** 带 arrow 的卡片：点击进入下级页面。 */
@Composable
fun HookArrowCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val enabled = rememberOptionEnabled(spec)
    ArrowPreference(
        title = stringResource(spec.titleRes),
        summary = spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) },
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    )
}
