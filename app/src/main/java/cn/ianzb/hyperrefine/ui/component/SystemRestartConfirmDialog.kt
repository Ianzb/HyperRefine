package cn.ianzb.hyperrefine.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R

/**
 * 重启系统前的二次确认对话框。
 *
 * 目标包含 `system` / `android` / `system_server` 时，重启会触发系统重启，属于破坏性操作，
 * 必须再次确认。
 */
@Composable
fun SystemRestartConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        show = true,
        title = stringResource(R.string.restart_system_confirm_title),
        summary = stringResource(R.string.restart_system_confirm_message),
        onDismissRequest = onDismiss,
    ) {
        AppDialogButtonRow {
            AppDialogButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            AppDialogButton(
                text = stringResource(R.string.restart_system_confirm_action),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                destructive = true,
            )
        }
    }
}
