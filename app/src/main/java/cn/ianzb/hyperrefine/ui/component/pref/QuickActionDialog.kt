package cn.ianzb.hyperrefine.ui.component.pref

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.SystemRestartConfirmDialog
import cn.ianzb.hyperrefine.xposed.AppRestarter
import cn.ianzb.hyperrefine.xposed.HookStatusReader
import cn.ianzb.hyperrefine.xposed.XposedServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 包名列表快捷操作对话框：对指定包批量执行「热重载」「重启」。
 *
 * - 每项右侧为文本按钮：热重载（次要）、重启（主要）。
 * - 底部提供「全部热重载」「全部重启」。
 *
 * 由 [HookOptionsPage] 在检测到 `PACKAGE_LIST` 选项或传入自定义包名时，通过右上角按钮弹出。
 */
@Composable
fun QuickActionDialog(
    packages: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 目标包含系统进程时，重启会触发系统重启，需二次确认。
    var pendingRestart by remember { mutableStateOf<List<String>?>(null) }
    val requestRestart: (List<String>) -> Unit = { targets ->
        if (targets.any { AppRestarter.isSystemPackage(it) }) {
            pendingRestart = targets
        } else {
            coroutineScope.launch { targets.forEach { restartPackage(context, it) } }
        }
    }

    WindowDialog(
        show = true,
        title = stringResource(R.string.quick_action_title),
        summary = stringResource(R.string.quick_action_summary),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            packages.forEach { packageName ->
                QuickActionRow(
                    context = context,
                    packageName = packageName,
                    onHotReload = {
                        XposedServiceManager.hotReload(listOf(packageName)) { result ->
                            HookStatusReader.refresh()
                            showResult(context, result)
                        }
                    },
                    onRestart = {
                        requestRestart(listOf(packageName))
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.quick_action_hot_reload_all),
                    onClick = {
                        XposedServiceManager.hotReload(packages) { result ->
                            HookStatusReader.refresh()
                            showResult(context, result)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { requestRestart(packages) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                ) {
                    MiuixText(text = stringResource(R.string.quick_action_restart_all))
                }
            }
        }
    }

    pendingRestart?.let { targets ->
        SystemRestartConfirmDialog(
            onConfirm = {
                pendingRestart = null
                coroutineScope.launch { targets.forEach { restartPackage(context, it) } }
            },
            onDismiss = { pendingRestart = null },
        )
    }
}

@Composable
private fun QuickActionRow(
    context: Context,
    packageName: String,
    onHotReload: () -> Unit,
    onRestart: () -> Unit,
) {
    val label = remember(packageName) {
        runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MiuixText(
                text = label,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (label != packageName) {
                MiuixText(
                    text = packageName,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        TextButton(
            text = stringResource(R.string.scope_hot_reload),
            onClick = onHotReload,
            colors = ButtonDefaults.textButtonColors(),
            minWidth = 0.dp,
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onRestart,
            colors = ButtonDefaults.buttonColorsPrimary(),
            minWidth = 0.dp,
        ) {
            MiuixText(text = stringResource(R.string.scope_restart))
        }
    }
}

private suspend fun restartPackage(context: Context, packageName: String) {
    val ok = withContext(Dispatchers.IO) {
        AppRestarter.restart(context, packageName)
    }
    if (!ok) {
        Toast.makeText(
            context,
            context.getString(R.string.scope_restart_need_root),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun showResult(context: Context, result: String) {
    val text = if (result == "no running target") {
        context.getString(R.string.module_hot_reload_no_target)
    } else {
        context.getString(R.string.module_hot_reload_result, result)
    }
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
