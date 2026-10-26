package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.xposed.HookStatus
import cn.ianzb.hyperrefine.xposed.HookStatusStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 副标题：基础文案。
 *
 * 生效状态不再拼进摘要，而是作为大标题后的彩色标签由 [HookTitleWithStatus] 渲染。
 * 功能关闭时不显示历史「已生效」证据；但设备不支持 / 作用域未勾选时仍显示灰色「不可用」。
 */
@Composable
private fun optionSummary(spec: OptionSpec): String? =
    spec.summaryRes.takeIf { it != 0 }?.let { stringResource(it) }

/**
 * 大标题 +（可选）四态状态标签。
 *
 * 标题字号 / 字重与 Miuix `BasicComponent` 默认标题一致；标签紧跟在标题文字后，
 * 标题过长时由标签保持自身宽度、标题自行换行。
 */
@Composable
private fun HookTitleWithStatus(
    title: String,
    status: HookStatus?,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixText(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium,
            color = if (enabled) {
                MiuixTheme.colorScheme.onBackground
            } else {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            },
        )
        if (status != null) {
            Spacer(Modifier.width(8.dp))
            HookStatusBadge(status)
        }
    }
}

@Composable
private fun HookSummary(text: String?, enabled: Boolean) {
    if (text == null) return
    MiuixText(
        text = text,
        modifier = Modifier.padding(top = 2.dp),
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = if (enabled) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            MiuixTheme.colorScheme.disabledOnSecondaryVariant
        },
    )
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
    val status = rememberHookStatus(spec).takeIf { status ->
        spec.showStatus && (status == HookStatus.UNAVAILABLE || (enabled && checked))
    }

    fun update(value: Boolean) {
        ConfigState.set(spec.key, value)
        // 开关变化后需重启目标进程才会生效；先清除旧证据，待其重新回报。
        HookStatusStore.removeKeys(context, listOf(spec.key))
        if (value) ensureScopeFor(spec)
    }

    BasicComponent(
        modifier = modifier,
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = { update(it) },
                enabled = enabled,
            )
        },
        onClick = if (enabled) {
            { update(!checked) }
        } else {
            null
        },
        role = Role.Switch,
        enabled = enabled,
    ) {
        HookTitleWithStatus(
            title = stringResource(spec.titleRes),
            status = status,
            enabled = enabled,
        )
        HookSummary(optionSummary(spec), enabled)
    }
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
    val status = rememberHookStatus(spec).takeIf { status ->
        spec.showStatus && (status == HookStatus.UNAVAILABLE || (enabled && checked))
    }

    fun update(value: Boolean) {
        ConfigState.set(spec.key, value)
        HookStatusStore.removeKeys(context, listOf(spec.key))
        if (value) ensureScopeFor(spec)
    }

    BasicComponent(
        modifier = modifier,
        endActions = {
            Checkbox(
                state = ToggleableState(checked),
                onClick = null,
                enabled = enabled,
            )
        },
        onClick = if (enabled) {
            { update(!checked) }
        } else {
            null
        },
        role = Role.Checkbox,
        enabled = enabled,
    ) {
        HookTitleWithStatus(
            title = stringResource(spec.titleRes),
            status = status,
            enabled = enabled,
        )
        HookSummary(optionSummary(spec), enabled)
    }
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
