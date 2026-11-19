package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.experimental.packageinstaller.InstallerExperiment
import cn.ianzb.hyperrefine.hook.phrase.PhraseReorderKeys
import cn.ianzb.hyperrefine.hook.weather.WeatherKeys
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
 * 「实验性功能」二级页。
 *
 * 收纳仍在验证中的功能。
 */
class ExperimentalActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_experimental

    override val topBarActions: (@Composable () -> Unit)? =
        {
            QuickActionsAction(
                listOf(
                    WeatherKeys.TARGET_PACKAGE,
                    PhraseReorderKeys.PKG_PHRASE,
                ),
            )
        }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val context = LocalContext.current
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val sections = listOf(
            HookSection(
                specs = listOf(
                    featureSpec(KEY_ASSISTANT),
                    featureSpec(KEY_IME),
                ),
            ),
            HookSection(
                titleRes = R.string.section_weather,
                specs = listOf(
                    featureSpec(KEY_WEATHER),
                ),
            ),
            HookSection(
                titleRes = R.string.phrase_reorder_section,
                specs = listOf(
                    featureSpec(KEY_PHRASE_REORDER),
                ),
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
            sections.forEach { section ->
                searchSectionItem(section) {
                    HookSectionCard(section) {
                        section.specs.forEach { spec ->
                            HookOptionView(
                                spec = spec,
                                onArrowClick = {
                                    when (spec.key) {
                                        KEY_ASSISTANT -> context.startActivity(Intent(context, AssistantActivity::class.java))
                                        KEY_IME -> context.startActivity(Intent(context, ImeActivity::class.java))
                                        KEY_WEATHER -> context.startActivity(Intent(context, WeatherActivity::class.java))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            searchSectionItem(HookSection(specs = listOf(InstallerExperiment.entrySpec()))) {
                InstallerExperiment.EntryCard()
            }
        }
    }
}
