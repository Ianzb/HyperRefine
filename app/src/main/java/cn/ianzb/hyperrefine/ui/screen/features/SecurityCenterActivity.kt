package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
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
 * 安全服务子页：快充加速通知的独立开关（进入提醒 / 退出提醒）。
 */
class SecurityCenterActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_security_center

    override val topBarActions: @Composable () -> Unit = SecurityCenterActions

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        SecurityCenterContent(isBlurEnabled, contentPadding)
    }
}

internal val SecurityCenterActions: @Composable () -> Unit = { QuickActionsAction(listOf("com.miui.securitycenter")) }

@Composable
internal fun SecurityCenterContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
    val scrollBehavior = LocalSubPageScrollBehavior.current
    val section = HookSection(
        titleRes = R.string.section_notifications,
        specs = listOf(
            featureSpec(KEY_FAST_CHARGE_ENTER),
            featureSpec(KEY_FAST_CHARGE_EXIT),
        ),
    )
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
        searchSectionItem(section) {
            HookSectionCard(section) {
                section.specs.forEach { HookOptionView(it) }
            }
        }
    }
}
