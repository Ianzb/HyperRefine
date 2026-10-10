package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.weather.WeatherKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「实验性功能」二级页。
 *
 * 收纳仍在验证中的功能；目前包含「天气高级外观」。
 */
class ExperimentalActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_experimental

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf(WeatherKeys.TARGET_PACKAGE)) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val context = LocalContext.current
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val section = HookSection(
            titleRes = R.string.section_weather,
            specs = listOf(
                featureSpec(KEY_WEATHER),
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
                                if (spec.key == KEY_WEATHER) {
                                    context.startActivity(Intent(context, WeatherActivity::class.java))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
