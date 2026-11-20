package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.passkey.PasskeyKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.SearchLandingLazyColumn
import cn.ianzb.hyperrefine.ui.component.pref.searchSectionItem
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 密码子页：HyperOS 通行密钥（Passkey / Credential Manager）修复。
 *
 * 顶栏提供目标应用重启入口（设置 / 安全中心 / 扫描器）。system_server 部分需重启设备生效。
 */
class PasskeyActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_passkey

    override val topBarActions: @Composable () -> Unit = PasskeyActions

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        PasskeyContent(isBlurEnabled, contentPadding)
    }
}

internal val PasskeyActions: @Composable () -> Unit = {
        QuickActionsAction(
            listOf(
                PasskeyKeys.TARGET_SETTINGS,
                PasskeyKeys.TARGET_SECURITY_CENTER,
                PasskeyKeys.TARGET_SCANNER,
            )
        )
    }

@Composable
internal fun PasskeyContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
    val scrollBehavior = LocalSubPageScrollBehavior.current
    val routeSection = remember {
        HookSection(
            titleRes = R.string.passkey_section_route,
            specs = listOf(
                featureSpec(PasskeyKeys.MASTER),
                featureSpec(PasskeyKeys.SYSTEM_SERVER),
            ),
        )
    }
    val appSection = remember {
        HookSection(
            titleRes = R.string.passkey_section_apps,
            specs = listOf(
                featureSpec(PasskeyKeys.SETTINGS),
                featureSpec(PasskeyKeys.BLOCK_SECURITY_CENTER),
                featureSpec(PasskeyKeys.SCANNER),
            ),
        )
    }

    val listState = rememberLazyListState()
    SearchLandingLazyColumn(
        scrollBehavior = scrollBehavior,
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (scrollBehavior != null) {
                    Modifier.pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                        contentState = listState,
                    )
                } else {
                    Modifier
                }
            ),
        contentPadding = contentPadding,
    ) {
        searchSectionItem(routeSection) {
            HookSectionCard(routeSection) {
                routeSection.specs.forEach { HookOptionView(it) }
            }
        }
        searchSectionItem(appSection) {
            HookSectionCard(appSection) {
                appSection.specs.forEach { HookOptionView(it) }
            }
        }
    }
}
