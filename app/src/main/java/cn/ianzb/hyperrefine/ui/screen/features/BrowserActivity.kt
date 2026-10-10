package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.browser.BrowserKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 浏览器子页：阻止小米浏览器强制跳转，改投系统默认浏览器。
 *
 * 顶栏提供相关应用重启入口。
 */
class BrowserActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_browser

    override val topBarActions: (@Composable () -> Unit)? = {
        QuickActionsAction(
            listOf(
                BrowserKeys.PKG_MISHARE,
                BrowserKeys.PKG_AI_ENGINE,
                BrowserKeys.PKG_VOICE_ASSIST,
                BrowserKeys.PKG_SETTINGS,
                BrowserKeys.PKG_MARKET,
                BrowserKeys.PKG_CONTENT_CATCHER,
                BrowserKeys.PKG_AI_VISION,
            )
        )
    }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val general = remember {
            HookSection(
                titleRes = R.string.browser_section_general,
                specs = listOf(
                    featureSpec(BrowserKeys.MASTER),
                    featureSpec(BrowserKeys.INTENT_INTERCEPT),
                    featureSpec(BrowserKeys.FAKE_INSTALLED),
                    featureSpec(BrowserKeys.PENDING_INTENT),
                ),
            )
        }
        val scenarios = remember {
            HookSection(
                titleRes = R.string.browser_section_scenarios,
                specs = listOf(
                    featureSpec(BrowserKeys.NOTIFICATION_ICON),
                    featureSpec(BrowserKeys.MISHARE),
                    featureSpec(BrowserKeys.VOICE_ASSIST),
                    featureSpec(BrowserKeys.COPY_DIRECT),
                    featureSpec(BrowserKeys.ROUTER_SETTINGS),
                ),
            )
        }

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
                HookSectionCard(general) { general.specs.forEach { HookOptionView(it) } }
            }
            item {
                HookSectionCard(scenarios) { scenarios.specs.forEach { HookOptionView(it) } }
            }
        }
    }
}
