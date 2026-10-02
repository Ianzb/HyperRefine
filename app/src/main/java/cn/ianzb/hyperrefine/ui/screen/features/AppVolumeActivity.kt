package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「分应用音量」二级页。
 *
 * 入口 / 悬浮球 / 常显 / 位置 / 高度 / 背景模糊边框。圆角统一在「外观 → 圆角调整」。
 */
class AppVolumeActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.app_volume_section

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui", AppVolumeKeys.TARGET_PACKAGE)) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val appVolumeSection = HookSection(
            titleRes = R.string.app_volume_section,
            specs = listOf(
                featureSpec(AppVolumeKeys.ENTRY),
                featureSpec(AppVolumeKeys.HIDE_FLOAT),
                featureSpec(AppVolumeKeys.ALWAYS_SHOW),
                featureSpec(AppVolumeKeys.ALIGN_RIGHT),
                featureSpec(AppVolumeKeys.HEIGHT_PERCENT),
                featureSpec(AppVolumeKeys.HIDE_BLUR_BG),
            ),
        )
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
                HookSectionCard(appVolumeSection) {
                    appVolumeSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
