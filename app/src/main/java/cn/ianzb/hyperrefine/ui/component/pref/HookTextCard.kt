package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.ui.component.AppDialog
import cn.ianzb.hyperrefine.ui.component.AppDialogButton
import cn.ianzb.hyperrefine.ui.component.AppDialogButtonRow
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import cn.ianzb.hyperrefine.ui.component.AppArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 文本输入卡片：点击后弹出与滑块卡片一致的居中输入对话框。
 */
@Composable
fun HookTextCard(
    spec: OptionSpec,
    modifier: Modifier = Modifier,
) {
    val enabled = rememberOptionEnabled(spec)
    val masterKey = spec.masterKey
    val masterEnabled = if (masterKey != null) {
        ConfigState.bool(masterKey, spec.masterDefault)
    } else {
        true
    }
    val value = ConfigState.string(spec.key, spec.defaultString)
    var showDialog by remember { mutableStateOf(false) }
    val notSet = stringResource(R.string.hook_text_not_set)
    val emptyHint = when {
        spec.summaryText.isNotEmpty() -> spec.summaryText
        spec.summaryRes != 0 -> stringResource(spec.summaryRes)
        else -> notSet
    }

    Column(modifier = modifier) {
        if (masterKey != null) {
            SwitchPreference(
                title = stringResource(spec.masterTitleRes.takeIf { it != 0 } ?: spec.titleRes),
                summary = spec.masterSummaryRes.takeIf { it != 0 }?.let { stringResource(it) },
                checked = masterEnabled,
                onCheckedChange = {
                    ConfigState.set(masterKey, it)
                    if (it) ensureScopeFor(spec)
                },
                enabled = enabled,
            )
        }
        AppArrowPreference(
            title = stringResource(spec.titleRes),
            summary = value.ifEmpty { spec.defaultString }.ifEmpty { emptyHint },
            onClick = { if (enabled && masterEnabled) showDialog = true },
            enabled = enabled && masterEnabled,
        )
    }

    if (showDialog) {
        TextValueDialog(spec = spec, onDismiss = { showDialog = false })
    }
}

@Composable
private fun TextValueDialog(
    spec: OptionSpec,
    onDismiss: () -> Unit,
) {
    val current = ConfigState.string(spec.key, spec.defaultString).ifEmpty { spec.defaultString }
    val input = rememberTextFieldState(current)

    AppDialog(
        show = true,
        title = stringResource(spec.titleRes),
        summary = spec.summaryText.ifEmpty {
            stringResource(spec.summaryRes.takeIf { it != 0 } ?: R.string.hook_text_dialog_summary)
        },
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val notSet = stringResource(R.string.hook_text_not_set)
            Row(modifier = Modifier.fillMaxWidth()) {
                MiuixText(
                    text = stringResource(
                        R.string.hook_text_current,
                        current.ifEmpty { notSet },
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                )
                Spacer(Modifier.weight(1f))
                MiuixText(
                    text = stringResource(
                        R.string.hook_text_default,
                        spec.defaultString.ifEmpty { notSet },
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
            Spacer(Modifier.padding(top = 8.dp))
            GlassTextField(
                state = input,
                label = stringResource(R.string.hook_text_placeholder),
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth(),
            )
            AppDialogButtonRow {
                AppDialogButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                AppDialogButton(
                    text = stringResource(R.string.hook_slider_restore_default),
                    onClick = {
                        ConfigState.set(spec.key, spec.defaultString)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                AppDialogButton(
                    text = stringResource(R.string.action_confirm),
                    enabled = spec.textValidator?.invoke(input.text.toString()) != false,
                    onClick = {
                        ConfigState.set(spec.key, input.text.toString())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    primary = true,
                )
            }
        }
    }
}
