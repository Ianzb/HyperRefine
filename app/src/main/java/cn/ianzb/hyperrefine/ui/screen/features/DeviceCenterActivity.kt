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
 * 「融合设备中心」二级页。
 */
class DeviceCenterActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_device_center

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui", "com.milink.service")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val deviceSection = HookSection(
            titleRes = R.string.section_device_center,
            specs = listOf(
                featureSpec(KEY_DEVICE_CENTER_FAST_LOAD),
                featureSpec(KEY_DEVICE_CENTER_HIDE_MORE),
                featureSpec(KEY_DEVICE_CENTER_SHRINK_HIT_AREA),
                featureSpec(KEY_DEVICE_CENTER_CARD_GLASS),
                featureSpec(KEY_DEVICE_CENTER_LANDSCAPE_RIGHT),
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
            searchSectionItem(deviceSection) {
                HookSectionCard(deviceSection) {
                    deviceSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
