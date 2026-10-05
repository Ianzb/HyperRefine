package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「布局调整」二级页（外观 → 美化）。
 *
 * 收纳布局相关设置；目前包含「音量条」。
 */
class LayoutAdjustActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_layout_adjust

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val context = LocalContext.current
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val section = HookSection(
            titleRes = R.string.section_layout_adjust,
            specs = listOf(
                featureSpec(KEY_VOLUME_BAR),
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
                HookSectionCard(section) {
                    section.specs.forEach { spec ->
                        HookOptionView(
                            spec = spec,
                            onArrowClick = {
                                if (spec.key == KEY_VOLUME_BAR) {
                                    context.startActivity(Intent(context, VolumeBarActivity::class.java))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
