package cn.ianzb.hyperrefine.ui.screen.safemode

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import cn.ianzb.hyperrefine.xposed.SafeModeReader
import cn.ianzb.hyperrefine.xposed.XposedServiceManager
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 安全模式二级页面。
 *
 * 列出全部被 hook 的应用，逐项控制其安全模式开关：
 * - 打开：该应用下次启动时跳过全部 hook（原生与 Java）；
 * - 关闭：恢复 hook，并清空崩溃计数与加载时间戳。
 *
 * 进入方式：设置 → 模块 → 安全模式。
 */
class SafeModeActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.safe_mode_manage

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        LaunchedEffect(Unit) {
            delay(400.milliseconds)
            XposedServiceManager.refreshScope()
            SafeModeReader.refresh()
            while (true) {
                delay(1500.milliseconds)
                XposedServiceManager.refreshScope()
                SafeModeReader.refresh()
            }
        }

        val context = LocalContext.current
        val resources = LocalResources.current
        val density = LocalDensity.current
        val scope = XposedServiceManager.scope
        val safeModePackages = SafeModeReader.safeModePackages
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
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp)
                ) {
                    MiuixText(
                        text = stringResource(R.string.safe_mode_declaration),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

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
                    val crashes = SafeModeReader.crashCount(packageName)

                    val summary = buildString {
                        append(packageName)
                        if (version.isNotBlank()) append(" · v").append(version)
                        if (crashes > 0) {
                            append(" · ")
                            append(resources.getString(R.string.safe_mode_crash_count, crashes))
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
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
                                Switch(
                                    checked = inSafeMode,
                                    onCheckedChange = { checked ->
                                        SafeModeReader.setSafeMode(packageName, checked)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
