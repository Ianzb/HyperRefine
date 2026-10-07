package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * 「外观」二级页。
 *
 * - 美化：柔光玻璃、圆角调整
 * - 控制中心：融合设备中心
 */
class AppearanceActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_appearance

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui", AppVolumeKeys.TARGET_PACKAGE)) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val context = LocalContext.current
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val beautifySection = HookSection(
            titleRes = R.string.section_beautify,
            specs = listOf(
                featureSpec(KEY_GLASS),
                featureSpec(KEY_TOP_BAR),
                featureSpec(KEY_CC_RADIUS),
                featureSpec(KEY_LAYOUT_ADJUST),
            ),
        )
        val componentSection = HookSection(
            titleRes = R.string.section_components,
            specs = listOf(
                featureSpec(KEY_DEVICE_CENTER),
                featureSpec(KEY_NETWORK),
                featureSpec(KEY_AOD),
                featureSpec(KEY_VOICE_ASSIST),
                featureSpec(PercentLocation.entryKey(PercentLocation.CC_BRIGHTNESS)),
                featureSpec(PercentLocation.entryKey(PercentLocation.CC_VOLUME)),
                featureSpec(PercentLocation.entryKey(PercentLocation.SIDE_VOLUME)),
                featureSpec(KEY_APP_VOLUME),
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
                HookSectionCard(beautifySection) {
                    beautifySection.specs.forEach { spec ->
                        HookOptionView(
                            spec = spec,
                            onArrowClick = {
                                when (spec.key) {
                                    KEY_GLASS ->
                                        context.startActivity(Intent(context, GlassActivity::class.java))
                                    KEY_TOP_BAR ->
                                        context.startActivity(Intent(context, TopBarActivity::class.java))
                                    KEY_CC_RADIUS ->
                                        context.startActivity(Intent(context, CornerRadiusActivity::class.java))
                                    KEY_LAYOUT_ADJUST ->
                                        context.startActivity(Intent(context, LayoutAdjustActivity::class.java))
                                }
                            },
                        )
                    }
                }
            }
            item {
                HookSectionCard(componentSection) {
                    componentSection.specs.forEach { spec ->
                        HookOptionView(
                            spec = spec,
                            onArrowClick = {
                                when (spec.key) {
                                    KEY_DEVICE_CENTER ->
                                        context.startActivity(Intent(context, DeviceCenterActivity::class.java))
                                    KEY_NETWORK ->
                                        context.startActivity(Intent(context, NetworkActivity::class.java))
                                    KEY_AOD ->
                                        context.startActivity(Intent(context, AodActivity::class.java))
                                    KEY_VOICE_ASSIST ->
                                        context.startActivity(Intent(context, VoiceAssistActivity::class.java))
                                    PercentLocation.entryKey(PercentLocation.CC_BRIGHTNESS) ->
                                        context.startActivity(percentStyleIntent(context, PercentLocation.CC_BRIGHTNESS))
                                    PercentLocation.entryKey(PercentLocation.CC_VOLUME) ->
                                        context.startActivity(percentStyleIntent(context, PercentLocation.CC_VOLUME))
                                    PercentLocation.entryKey(PercentLocation.SIDE_VOLUME) ->
                                        context.startActivity(percentStyleIntent(context, PercentLocation.SIDE_VOLUME))
                                    KEY_APP_VOLUME ->
                                        context.startActivity(Intent(context, AppVolumeActivity::class.java))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
