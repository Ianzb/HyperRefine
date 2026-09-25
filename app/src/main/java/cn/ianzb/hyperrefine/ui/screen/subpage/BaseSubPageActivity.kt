package cn.ianzb.hyperrefine.ui.screen.subpage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.AppSettings
import cn.ianzb.hyperrefine.LocaleHelper
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.SubPageScaffold
import cn.ianzb.hyperrefine.ui.theme.AppTheme
import cn.ianzb.hyperrefine.ui.util.applyWindowBackground
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 二级页面 Activity 模板。
 *
 * 自动套用模块配置（主题模式、背景模糊等），并带页面切换动画。
 * 子类只需提供标题与内容。
 */
abstract class BaseSubPageActivity : ComponentActivity() {

    @get:StringRes
    protected abstract val titleRes: Int

    /** 是否在顶栏右上角显示「快捷操作」入口（系统界面热重载 / 重启）。 */
    protected open val showSystemUiActions: Boolean = false

    @Composable
    protected abstract fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    )

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(settings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }

        // 与主页一致的窗口背景，避免二级页打开瞬间闪现浅色/深色底。
        applyWindowBackground(settings.themeMode)

        setContent {
            AppTheme(themeMode = themeMode) {
                SubPageScaffold(
                    title = stringResource(titleRes),
                    isBlurEnabled = settings.isBlurEnabled,
                    onBack = { finish() },
                    topBarActions = if (showSystemUiActions) {
                        { cn.ianzb.hyperrefine.ui.component.SystemUiQuickActionsAction() }
                    } else {
                        null
                    },
                ) { padding ->
                    SubPageContent(settings.isBlurEnabled, padding)
                }
            }
        }
    }
}
