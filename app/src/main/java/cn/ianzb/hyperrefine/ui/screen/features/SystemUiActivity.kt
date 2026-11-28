package cn.ianzb.hyperrefine.ui.screen.features

import cn.ianzb.hyperrefine.ui.screen.subpage.AppSubPage
import cn.ianzb.hyperrefine.ui.screen.subpage.LocalOpenAppSubPage
import cn.ianzb.hyperrefine.ui.screen.subpage.openAppSubPage
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.connect.BluetoothAudioLoad
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
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
 * 「系统界面」（`com.android.systemui`）二级页。
 *
 * - 控制中心：柔光玻璃、圆角调整、亮度条、融合设备中心、网络
 * - 音量：控制中心音量条、侧边音量条、多应用音量、音量条布局
 * - 锁屏与息屏：息屏显示
 * - 壁纸：深色模式自动压暗壁纸
 * - 深色模式：定时切换到点立即生效
 */
class SystemUiActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_system_ui

    override val topBarActions: @Composable () -> Unit = SystemUiActions

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        SystemUiContent(isBlurEnabled, contentPadding)
    }
}

internal val SystemUiActions: @Composable () -> Unit = {
    QuickActionsAction(listOf("com.android.systemui", AppVolumeKeys.TARGET_PACKAGE, BluetoothAudioLoad.TARGET))
}

@Composable
internal fun SystemUiContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val openInHost = LocalOpenAppSubPage.current
    val scrollBehavior = LocalSubPageScrollBehavior.current
    val statusBarSection = HookSection(
        titleRes = R.string.sb_title,
        specs = listOf(featureSpec(KEY_STATUS_BAR)),
    )
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
    val notificationSection = HookSection(
        titleRes = R.string.notifications_title,
        specs = listOf(
            featureSpec(KEY_NOTIFICATIONS),
        ),
    )
    val wallpaperSection = HookSection(
        titleRes = R.string.section_wallpaper,
        specs = listOf(
            featureSpec(KEY_WALLPAPER),
        ),
    )
    val darkModeSection = HookSection(
        titleRes = R.string.section_dark_mode,
        specs = listOf(
            featureSpec(KEY_DARK_MODE),
        ),
    )
    // 分屏 / 多分屏等窗口能力单独成页；改动需重启系统界面生效。
    val windowSection = HookSection(
        titleRes = R.string.section_split_window,
        specs = listOf(
            featureSpec(KEY_WINDOW_MANAGER),
        ),
    )

    val openSubPage: (String) -> Unit = { key ->
        when (key) {
            KEY_STATUS_BAR -> openAppSubPage(context, openInHost, AppSubPage.NetworkIcons, null)
            KEY_GLASS ->
                openAppSubPage(context, openInHost, AppSubPage.Glass, null)
            KEY_CC_RADIUS ->
                openAppSubPage(context, openInHost, AppSubPage.CornerRadius, null)
            PercentLocation.entryKey(PercentLocation.CC_BRIGHTNESS) ->
                openAppSubPage(context, openInHost, AppSubPage.CcBrightness, null)
            KEY_DEVICE_CENTER ->
                openAppSubPage(context, openInHost, AppSubPage.DeviceCenter, null)
            KEY_NETWORK ->
                openAppSubPage(context, openInHost, AppSubPage.Network, null)
            PercentLocation.entryKey(PercentLocation.CC_VOLUME) ->
                openAppSubPage(context, openInHost, AppSubPage.CcVolume, null)
            PercentLocation.entryKey(PercentLocation.SIDE_VOLUME) ->
                openAppSubPage(context, openInHost, AppSubPage.SideVolume, null)
            KEY_APP_VOLUME ->
                openAppSubPage(context, openInHost, AppSubPage.AppVolume, null)
            KEY_VOLUME_BAR_LAYOUT ->
                openAppSubPage(context, openInHost, AppSubPage.VolumeBarLayout, null)
            KEY_AOD ->
                openAppSubPage(context, openInHost, AppSubPage.Aod, null)
            KEY_NOTIFICATIONS ->
                openAppSubPage(context, openInHost, AppSubPage.Notifications, null)
            KEY_WALLPAPER ->
                openAppSubPage(context, openInHost, AppSubPage.Wallpaper, null)
            KEY_DARK_MODE ->
                openAppSubPage(context, openInHost, AppSubPage.DarkMode, null)
            KEY_WINDOW_MANAGER ->
                openAppSubPage(context, openInHost, AppSubPage.WindowManager, null)
        }
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
        listOf(
            statusBarSection,
            controlCenterSection,
            volumeSection,
            lockAodSection,
            notificationSection,
            wallpaperSection,
            darkModeSection,
            windowSection,
        ).forEach { section ->
            searchSectionItem(section) {
                HookSectionCard(section) {
                    section.specs.forEach { spec ->
                        HookOptionView(spec = spec, onArrowClick = { openSubPage(spec.key) })
                    }
                }
            }
        }
    }
}
