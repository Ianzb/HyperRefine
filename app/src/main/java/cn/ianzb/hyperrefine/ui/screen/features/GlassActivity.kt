package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
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
 * 「柔光玻璃」二级页。
 */
class GlassActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_glass

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui", "com.milink.service", "com.xiaomi.smarthome")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val glassSection = HookSection(
            titleRes = R.string.section_cc_glass,
            specs = listOf(featureSpec(CcGlassKeys.MASTER)) + glassScenes.keys.map(::featureSpec),
        )
        val themeSection = HookSection(
            specs = listOf(featureSpec(CcGlassKeys.THEME_MATERIAL)),
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
            listOf(glassSection, themeSection).forEach { section ->
                searchSectionItem(section) {
                    HookSectionCard(section) {
                        section.specs.forEach { HookOptionView(it) }
                    }
                }
            }
        }
    }
}
