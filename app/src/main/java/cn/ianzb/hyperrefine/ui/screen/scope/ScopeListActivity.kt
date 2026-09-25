package cn.ianzb.hyperrefine.ui.screen.scope

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.SystemRestartConfirmDialog
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import cn.ianzb.hyperrefine.xposed.AppRestarter
import cn.ianzb.hyperrefine.xposed.HookStatusReader
import cn.ianzb.hyperrefine.xposed.SafeModeReader
import cn.ianzb.hyperrefine.xposed.XposedServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 作用域列表二级页面。
 *
 * - 页面可见期间轮询刷新作用域，实时反映 LSPosed 中的授权变化。
 * - 每项右侧提供「热重载」「重启」按钮（重启需要 Root）。
 * - 被兜底机制自动禁用的应用会标注「安全模式」，并提供一键恢复。
 */
class ScopeListActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.home_scope

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        // 进入页面前已在主页刷新过；这里延后刷新，避免与打开动画冲突（动画优先）。
        LaunchedEffect(Unit) {
            delay(400)
            XposedServiceManager.refreshScope()
            SafeModeReader.refresh()
            // 轮询保持列表实时更新（含安全模式状态）。
            while (true) {
                delay(1500)
                XposedServiceManager.refreshScope()
                SafeModeReader.refresh()
            }
        }

        val context = LocalContext.current
        val density = LocalDensity.current
        val scope = XposedServiceManager.scope
        val safeModePackages = SafeModeReader.safeModePackages
        val coroutineScope = rememberCoroutineScope()
        val scrollBehavior = LocalSubPageScrollBehavior.current

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollBehavior != null) {
                        Modifier.pageScrollModifiers(
                            showTopAppBar = true,
                            topAppBarScrollBehavior = scrollBehavior,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentPadding = contentPadding,
        ) {
            if (scope.isEmpty()) {
                item {
                    MiuixText(
                        text = stringResource(R.string.scope_empty),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                    )
                }
            } else {
                items(scope, key = { it }) { packageName ->
                    val packageManager = context.packageManager
                    val appInfo = remember(packageName) {
                        runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
                    }
                    val appName = remember(packageName, appInfo) {
                        appInfo?.let {
                            runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull()
                        } ?: packageName
                    }
                    val version = remember(packageName) {
                        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
                            .getOrNull().orEmpty()
                    }
                    val iconSizePx = with(density) { 40.dp.roundToPx() }
                    val icon = remember(packageName, appInfo, iconSizePx) {
                        appInfo?.let {
                            runCatching {
                                packageManager.getApplicationIcon(it)
                                    .toBitmap(iconSizePx, iconSizePx)
                                    .asImageBitmap()
                            }.getOrNull()
                        }
                    }
                    val inSafeMode = packageName in safeModePackages

                    val summary = buildString {
                        append(packageName)
                        if (version.isNotBlank()) append(" · v").append(version)
                        if (inSafeMode) {
                            append(" · ")
                            append(context.getString(R.string.scope_safe_mode))
                        }
                    }

                    var showRebootConfirm by remember(packageName) { mutableStateOf(false) }
                    val doRestart: () -> Unit = {
                        coroutineScope.launch {
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
                    }

                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                    ) {
                        BasicComponent(
                            title = appName,
                            summary = summary,
                            startAction = icon?.let { bitmap ->
                                {
                                    Icon(
                                        bitmap = bitmap,
                                        contentDescription = null,
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(40.dp),
                                    )
                                }
                            },
                            endActions = {
                                if (inSafeMode) {
                                    TextButton(
                                        text = stringResource(R.string.scope_safe_mode_reset),
                                        onClick = {
                                            SafeModeReader.reset(packageName)
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.scope_safe_mode_reset_done, appName),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        },
                                        colors = ButtonDefaults.textButtonColors(textColor = Color(0xFFDC3545)),
                                        minWidth = 0.dp,
                                    )
                                }
                                TextButton(
                                    text = stringResource(R.string.scope_hot_reload),
                                    onClick = {
                                        XposedServiceManager.hotReload(listOf(packageName)) { result ->
                                            HookStatusReader.refresh()
                                            val text = if (result == "no running target") {
                                                context.getString(R.string.module_hot_reload_no_target)
                                            } else {
                                                context.getString(R.string.module_hot_reload_result, result)
                                            }
                                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColors(),
                                    minWidth = 0.dp,
                                )
                                Button(
                                    onClick = {
                                        if (AppRestarter.isSystemPackage(packageName)) {
                                            showRebootConfirm = true
                                        } else {
                                            doRestart()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColorsPrimary(),
                                    minWidth = 0.dp,
                                ) {
                                    MiuixText(text = stringResource(R.string.scope_restart))
                                }
                            },
                        )
                    }

                    if (showRebootConfirm) {
                        SystemRestartConfirmDialog(
                            onConfirm = {
                                showRebootConfirm = false
                                doRestart()
                            },
                            onDismiss = { showRebootConfirm = false },
                        )
                    }
                }
            }
        }
    }
}
