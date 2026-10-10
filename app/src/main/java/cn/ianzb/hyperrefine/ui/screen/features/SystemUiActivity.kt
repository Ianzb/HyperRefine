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
 * 「系统界面」（`com.android.systemui`）二级页。
 *
 * - 控制中心：柔光玻璃、圆角调整、亮度条、融合设备中心、网络
 * - 音量：控制中心音量条、侧边音量条、多应用音量、音量条布局
 * - 锁屏与息屏：息屏显示
 */
class SystemUiActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_system_ui

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui", AppVolumeKeys.TARGET_PACKAGE)) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val context = LocalContext.current
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val controlCenterSection = HookSection(
            titleRes = R.string.feature_control_center,
            specs = listOf(
                featureSpec(KEY_GLASS),
                featureSpec(KEY_CC_RADIUS),
                featureSpec(PercentLocation.entryKey(PercentLocation.CC_BRIGHTNESS)),
                featureSpec(KEY_DEVICE_CENTER),
                featureSpec(KEY_NETWORK),
            ),
        )
        val volumeSection = HookSection(
            titleRes = R.string.section_volume,
            specs = listOf(
                featureSpec(PercentLocation.entryKey(PercentLocation.CC_VOLUME)),
                featureSpec(PercentLocation.entryKey(PercentLocation.SIDE_VOLUME)),
                featureSpec(KEY_APP_VOLUME),
                featureSpec(KEY_VOLUME_BAR_LAYOUT),
            ),
        )
        val lockAodSection = HookSection(
            titleRes = R.string.section_lock_aod,
            specs = listOf(
                featureSpec(KEY_AOD),
            ),
        )

        val openSubPage: (String) -> Unit = { key ->
            when (key) {
                KEY_GLASS ->
                    context.startActivity(Intent(context, GlassActivity::class.java))
                KEY_CC_RADIUS ->
                    context.startActivity(Intent(context, CornerRadiusActivity::class.java))
                PercentLocation.entryKey(PercentLocation.CC_BRIGHTNESS) ->
                    context.startActivity(percentStyleIntent(context, PercentLocation.CC_BRIGHTNESS))
                KEY_DEVICE_CENTER ->
                    context.startActivity(Intent(context, DeviceCenterActivity::class.java))
                KEY_NETWORK ->
                    context.startActivity(Intent(context, NetworkActivity::class.java))
                PercentLocation.entryKey(PercentLocation.CC_VOLUME) ->
                    context.startActivity(percentStyleIntent(context, PercentLocation.CC_VOLUME))
                PercentLocation.entryKey(PercentLocation.SIDE_VOLUME) ->
                    context.startActivity(percentStyleIntent(context, PercentLocation.SIDE_VOLUME))
                KEY_APP_VOLUME ->
                    context.startActivity(Intent(context, AppVolumeActivity::class.java))
                KEY_VOLUME_BAR_LAYOUT ->
                    context.startActivity(Intent(context, VolumeBarLayoutActivity::class.java))
                KEY_AOD ->
                    context.startActivity(Intent(context, AodActivity::class.java))
            }
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
            listOf(controlCenterSection, volumeSection, lockAodSection).forEach { section ->
                item {
                    HookSectionCard(section) {
                        section.specs.forEach { spec ->
                            HookOptionView(spec = spec, onArrowClick = { openSubPage(spec.key) })
                        }
                    }
                }
            }
        }
    }
}
