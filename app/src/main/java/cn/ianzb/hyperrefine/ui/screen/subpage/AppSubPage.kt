package cn.ianzb.hyperrefine.ui.screen.subpage

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.screen.features.PercentLocation
import cn.ianzb.hyperrefine.ui.screen.features.PercentStyleContent
import cn.ianzb.hyperrefine.ui.screen.features.PercentStyleActivity
import cn.ianzb.hyperrefine.ui.screen.features.NetworkIconsActivity
import cn.ianzb.hyperrefine.ui.screen.features.NetworkIconsPage
import cn.ianzb.hyperrefine.ui.screen.features.GlassActivity
import cn.ianzb.hyperrefine.ui.screen.features.GlassActions
import cn.ianzb.hyperrefine.ui.screen.features.GlassContent
import cn.ianzb.hyperrefine.ui.screen.features.CornerRadiusActivity
import cn.ianzb.hyperrefine.ui.screen.features.CornerRadiusActions
import cn.ianzb.hyperrefine.ui.screen.features.CornerRadiusContent
import cn.ianzb.hyperrefine.ui.screen.features.DeviceCenterActivity
import cn.ianzb.hyperrefine.ui.screen.features.DeviceCenterActions
import cn.ianzb.hyperrefine.ui.screen.features.DeviceCenterContent
import cn.ianzb.hyperrefine.ui.screen.features.NetworkActivity
import cn.ianzb.hyperrefine.ui.screen.features.NetworkActions
import cn.ianzb.hyperrefine.ui.screen.features.NetworkContent
import cn.ianzb.hyperrefine.ui.screen.features.AppVolumeActivity
import cn.ianzb.hyperrefine.ui.screen.features.AppVolumeActions
import cn.ianzb.hyperrefine.ui.screen.features.AppVolumeContent
import cn.ianzb.hyperrefine.ui.screen.features.VolumeBarLayoutActivity
import cn.ianzb.hyperrefine.ui.screen.features.VolumeBarLayoutActions
import cn.ianzb.hyperrefine.ui.screen.features.VolumeBarLayoutContent
import cn.ianzb.hyperrefine.ui.screen.features.AodActivity
import cn.ianzb.hyperrefine.ui.screen.features.AodActions
import cn.ianzb.hyperrefine.ui.screen.features.AodContent
import cn.ianzb.hyperrefine.ui.screen.features.NotificationsActivity
import cn.ianzb.hyperrefine.ui.screen.features.NotificationsActions
import cn.ianzb.hyperrefine.ui.screen.features.NotificationsContent
import cn.ianzb.hyperrefine.ui.screen.features.WallpaperActivity
import cn.ianzb.hyperrefine.ui.screen.features.WallpaperActions
import cn.ianzb.hyperrefine.ui.screen.features.WallpaperContent
import cn.ianzb.hyperrefine.ui.screen.features.DarkModeActivity
import cn.ianzb.hyperrefine.ui.screen.features.DarkModeActions
import cn.ianzb.hyperrefine.ui.screen.features.DarkModeContent
import cn.ianzb.hyperrefine.ui.screen.features.WindowManagerActivity
import cn.ianzb.hyperrefine.ui.screen.features.WindowManagerActions
import cn.ianzb.hyperrefine.ui.screen.features.WindowManagerContent
import cn.ianzb.hyperrefine.ui.screen.features.TopBarBlurActivity
import cn.ianzb.hyperrefine.ui.screen.features.TopBarBlurActions
import cn.ianzb.hyperrefine.ui.screen.features.TopBarBlurContent
import cn.ianzb.hyperrefine.ui.screen.features.AssistantActivity
import cn.ianzb.hyperrefine.ui.screen.features.AssistantActions
import cn.ianzb.hyperrefine.ui.screen.features.AssistantContent
import cn.ianzb.hyperrefine.ui.screen.features.ImeActivity
import cn.ianzb.hyperrefine.ui.screen.features.ImeActions
import cn.ianzb.hyperrefine.ui.screen.features.ImeContent
import cn.ianzb.hyperrefine.ui.screen.features.WeatherActivity
import cn.ianzb.hyperrefine.ui.screen.features.WeatherActions
import cn.ianzb.hyperrefine.ui.screen.features.WeatherContent
import cn.ianzb.hyperrefine.experimental.packageinstaller.PackageInstallerActivity
import cn.ianzb.hyperrefine.experimental.packageinstaller.PackageInstallerActions
import cn.ianzb.hyperrefine.experimental.packageinstaller.PackageInstallerContent
import cn.ianzb.hyperrefine.ui.screen.features.IconSettingsActivity
import cn.ianzb.hyperrefine.ui.screen.features.IconSettingsActions
import cn.ianzb.hyperrefine.ui.screen.features.IconSettingsContent
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconRegistry
import cn.ianzb.hyperrefine.LicenseActivity
import cn.ianzb.hyperrefine.R
import androidx.compose.runtime.CompositionLocalProvider
import cn.ianzb.hyperrefine.ui.component.pref.LocalSearchTarget
import cn.ianzb.hyperrefine.ui.component.pref.withSearchTarget
import cn.ianzb.hyperrefine.ui.screen.features.SystemUiActivity
import cn.ianzb.hyperrefine.ui.screen.features.SystemUiActions
import cn.ianzb.hyperrefine.ui.screen.features.SystemUiContent
import cn.ianzb.hyperrefine.ui.screen.features.AppBeautifyActivity
import cn.ianzb.hyperrefine.ui.screen.features.AppBeautifyActions
import cn.ianzb.hyperrefine.ui.screen.features.AppBeautifyContent
import cn.ianzb.hyperrefine.ui.screen.features.DeviceConnectActivity
import cn.ianzb.hyperrefine.ui.screen.features.DeviceConnectActions
import cn.ianzb.hyperrefine.ui.screen.features.DeviceConnectContent
import cn.ianzb.hyperrefine.ui.screen.features.SecurityCenterActivity
import cn.ianzb.hyperrefine.ui.screen.features.SecurityCenterActions
import cn.ianzb.hyperrefine.ui.screen.features.SecurityCenterContent
import cn.ianzb.hyperrefine.ui.screen.features.VoiceAssistActivity
import cn.ianzb.hyperrefine.ui.screen.features.VoiceAssistActions
import cn.ianzb.hyperrefine.ui.screen.features.VoiceAssistContent
import cn.ianzb.hyperrefine.ui.screen.features.PasskeyActivity
import cn.ianzb.hyperrefine.ui.screen.features.PasskeyActions
import cn.ianzb.hyperrefine.ui.screen.features.PasskeyContent
import cn.ianzb.hyperrefine.ui.screen.features.BrowserActivity
import cn.ianzb.hyperrefine.ui.screen.features.BrowserActions
import cn.ianzb.hyperrefine.ui.screen.features.BrowserContent
import cn.ianzb.hyperrefine.ui.screen.features.ExperimentalActivity
import cn.ianzb.hyperrefine.ui.screen.features.ExperimentalActions
import cn.ianzb.hyperrefine.ui.screen.features.ExperimentalContent
import cn.ianzb.hyperrefine.ui.component.SubPageScaffold
import cn.ianzb.hyperrefine.ui.screen.about.LicensePageContent
import cn.ianzb.hyperrefine.ui.screen.safemode.SafeModeActivity
import cn.ianzb.hyperrefine.ui.screen.safemode.SafeModeContent
import cn.ianzb.hyperrefine.ui.screen.scope.ScopeListActivity
import cn.ianzb.hyperrefine.ui.screen.scope.ScopeListContent
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

internal enum class AppSubPage(
    val titleRes: Int,
    val activity: Class<out Activity>,
    val parent: AppSubPage? = null,
    val percentLocation: String? = null,
) {
    SystemUi(R.string.feature_system_ui, SystemUiActivity::class.java),
    AppBeautify(R.string.feature_app_beautify, AppBeautifyActivity::class.java),
    DeviceConnect(R.string.device_connect, DeviceConnectActivity::class.java),
    SecurityCenter(R.string.section_security_center, SecurityCenterActivity::class.java),
    VoiceAssist(R.string.section_voice_assist, VoiceAssistActivity::class.java),
    Ime(R.string.ime_title, ImeActivity::class.java),
    Passkey(R.string.section_passkey, PasskeyActivity::class.java),
    Browser(R.string.section_browser, BrowserActivity::class.java),
    Experimental(R.string.section_experimental, ExperimentalActivity::class.java),
    Glass(R.string.feature_glass, GlassActivity::class.java, SystemUi),
    CornerRadius(R.string.cc_radius_title, CornerRadiusActivity::class.java, SystemUi),
    DeviceCenter(R.string.section_device_center, DeviceCenterActivity::class.java, SystemUi),
    Network(R.string.network_section, NetworkActivity::class.java, SystemUi),
    AppVolume(R.string.app_volume_section, AppVolumeActivity::class.java, SystemUi),
    VolumeBarLayout(R.string.volume_bar_layout_title, VolumeBarLayoutActivity::class.java, SystemUi),
    Aod(R.string.aod_section, AodActivity::class.java, SystemUi),
    Notifications(R.string.notifications_title, NotificationsActivity::class.java, SystemUi),
    Wallpaper(R.string.section_wallpaper, WallpaperActivity::class.java, SystemUi),
    DarkMode(R.string.section_dark_mode, DarkModeActivity::class.java, SystemUi),
    WindowManager(R.string.feature_window_manager, WindowManagerActivity::class.java, SystemUi),
    TopBarBlur(R.string.feature_top_bar_blur, TopBarBlurActivity::class.java, AppBeautify),
    Assistant(R.string.feature_assistant, AssistantActivity::class.java, Experimental),
    Weather(R.string.feature_weather, WeatherActivity::class.java, Experimental),
    CcBrightness(R.string.appearance_cc_brightness, PercentStyleActivity::class.java, SystemUi, percentLocation = PercentLocation.CC_BRIGHTNESS),
    CcVolume(R.string.appearance_cc_volume, PercentStyleActivity::class.java, SystemUi, percentLocation = PercentLocation.CC_VOLUME),
    SideVolume(R.string.appearance_side_volume, PercentStyleActivity::class.java, SystemUi, percentLocation = PercentLocation.SIDE_VOLUME),
    NetworkIcons(R.string.sb_icon_title, NetworkIconsActivity::class.java, SystemUi),
    IconSettings(R.string.sb_icon_detail, IconSettingsActivity::class.java, NetworkIcons),
    PackageInstaller(R.string.section_packageinstaller, PackageInstallerActivity::class.java, Experimental),
    Scope(R.string.home_scope, ScopeListActivity::class.java),
    SafeMode(R.string.safe_mode_manage, SafeModeActivity::class.java),
    Licenses(R.string.third_party_licenses_title, LicenseActivity::class.java),
}

private val percentActions: @Composable () -> Unit =
    { QuickActionsAction(listOf("com.android.systemui")) }

internal sealed interface AppPageRoute : NavKey {
    data object Main : AppPageRoute
    data class SubPage(val page: AppSubPage, val searchTarget: String? = null, val argument: String? = null) : AppPageRoute
}

private val appPageBackStackSaver = Saver<NavBackStack, ArrayList<String>>(
    save = { stack ->
        ArrayList(stack.filterIsInstance<AppPageRoute.SubPage>().map {
            "${it.page.name}|${it.argument.orEmpty()}|${it.searchTarget.orEmpty()}"
        })
    },
    restore = { entries ->
        navBackStackOf(AppPageRoute.Main).apply {
            entries.forEach { entry ->
                val fields = entry.split("|", limit = 3)
                AppSubPage.entries.find { fields.size == 3 && it.name == fields[0] }?.let { page ->
                    add(AppPageRoute.SubPage(page, fields[2].ifEmpty { null }, fields[1].ifEmpty { null }))
                }
            }
        }
    },
)
@Composable
internal fun rememberAppPageBackStack(): NavBackStack =
    rememberSaveable(saver = appPageBackStackSaver) { navBackStackOf(AppPageRoute.Main) }

internal typealias OpenAppSubPage = (AppPageRoute.SubPage) -> Unit

internal val LocalOpenAppSubPage = staticCompositionLocalOf<OpenAppSubPage?> { null }

@Composable
internal fun rememberOpenAppSubPage(page: AppSubPage): () -> Unit {
    val context = LocalContext.current
    val openInHost = LocalOpenAppSubPage.current
    return remember(context, openInHost, page) {
        { openAppSubPage(context, openInHost, page, searchTarget = null) }
    }
}

internal fun openAppSubPage(
    context: Context,
    openInHost: OpenAppSubPage?,
    page: AppSubPage,
    searchTarget: String? = null,
    argument: String? = null,
) {
    if (openInHost != null) openInHost(AppPageRoute.SubPage(page, searchTarget, argument))
    else context.startActivity(
        Intent(context, page.activity)
            .putExtra(PercentStyleActivity.EXTRA_LOCATION, page.percentLocation)
            .putExtra(IconSettingsActivity.EXTRA_COMPONENT, argument)
            .withSearchTarget(searchTarget),
    )
}

@Composable
internal fun AppSubPageContent(page: AppSubPage, isBlurEnabled: Boolean, onBack: () -> Unit, searchTarget: String? = null, argument: String? = null) {
    if (page == AppSubPage.Licenses) {
        LicensePageContent(onBack = onBack, isBlurEnabled = isBlurEnabled)
        return
    }
    if (page == AppSubPage.NetworkIcons) {
        NetworkIconsPage(isBlurEnabled, onBack)
        return
    }
    val iconId = argument?.takeIf { StatusBarIconRegistry.byId(it) != null } ?: StatusBarIconRegistry.CLOCK
    val iconActions: @Composable () -> Unit = { IconSettingsActions(iconId) }
    SubPageScaffold(
        title = stringResource(page.titleRes),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
        topBarActions = when (page) {
            AppSubPage.SystemUi -> SystemUiActions
            AppSubPage.AppBeautify -> AppBeautifyActions
            AppSubPage.DeviceConnect -> DeviceConnectActions
            AppSubPage.SecurityCenter -> SecurityCenterActions
            AppSubPage.VoiceAssist -> VoiceAssistActions
            AppSubPage.Passkey -> PasskeyActions
            AppSubPage.Browser -> BrowserActions
            AppSubPage.Experimental -> ExperimentalActions
            AppSubPage.Glass -> GlassActions
            AppSubPage.CornerRadius -> CornerRadiusActions
            AppSubPage.DeviceCenter -> DeviceCenterActions
            AppSubPage.Network -> NetworkActions
            AppSubPage.AppVolume -> AppVolumeActions
            AppSubPage.VolumeBarLayout -> VolumeBarLayoutActions
            AppSubPage.Aod -> AodActions
            AppSubPage.Notifications -> NotificationsActions
            AppSubPage.Wallpaper -> WallpaperActions
            AppSubPage.DarkMode -> DarkModeActions
            AppSubPage.WindowManager -> WindowManagerActions
            AppSubPage.TopBarBlur -> TopBarBlurActions
            AppSubPage.Assistant -> AssistantActions
            AppSubPage.Ime -> ImeActions
            AppSubPage.Weather -> WeatherActions
            AppSubPage.CcBrightness, AppSubPage.CcVolume, AppSubPage.SideVolume -> percentActions
            AppSubPage.IconSettings -> iconActions
            AppSubPage.PackageInstaller -> PackageInstallerActions
            else -> null
        },
    ) { padding ->
        CompositionLocalProvider(LocalSearchTarget provides searchTarget) {
            when (page) {
                AppSubPage.SystemUi -> SystemUiContent(isBlurEnabled, padding)
                AppSubPage.AppBeautify -> AppBeautifyContent(isBlurEnabled, padding)
                AppSubPage.DeviceConnect -> DeviceConnectContent(isBlurEnabled, padding)
                AppSubPage.SecurityCenter -> SecurityCenterContent(isBlurEnabled, padding)
                AppSubPage.VoiceAssist -> VoiceAssistContent(isBlurEnabled, padding)
                AppSubPage.Passkey -> PasskeyContent(isBlurEnabled, padding)
                AppSubPage.Browser -> BrowserContent(isBlurEnabled, padding)
                AppSubPage.Experimental -> ExperimentalContent(isBlurEnabled, padding)
                AppSubPage.Glass -> GlassContent(isBlurEnabled, padding)
                AppSubPage.CornerRadius -> CornerRadiusContent(isBlurEnabled, padding)
                AppSubPage.DeviceCenter -> DeviceCenterContent(isBlurEnabled, padding)
                AppSubPage.Network -> NetworkContent(isBlurEnabled, padding)
                AppSubPage.AppVolume -> AppVolumeContent(isBlurEnabled, padding)
                AppSubPage.VolumeBarLayout -> VolumeBarLayoutContent(isBlurEnabled, padding)
                AppSubPage.Aod -> AodContent(isBlurEnabled, padding)
                AppSubPage.Notifications -> NotificationsContent(isBlurEnabled, padding)
                AppSubPage.Wallpaper -> WallpaperContent(isBlurEnabled, padding)
                AppSubPage.DarkMode -> DarkModeContent(isBlurEnabled, padding)
                AppSubPage.WindowManager -> WindowManagerContent(isBlurEnabled, padding)
                AppSubPage.TopBarBlur -> TopBarBlurContent(isBlurEnabled, padding)
                AppSubPage.Assistant -> AssistantContent(isBlurEnabled, padding)
                AppSubPage.Ime -> ImeContent(isBlurEnabled, padding)
                AppSubPage.Weather -> WeatherContent(isBlurEnabled, padding)
                AppSubPage.CcBrightness -> PercentStyleContent(PercentLocation.CC_BRIGHTNESS, padding)
                AppSubPage.CcVolume -> PercentStyleContent(PercentLocation.CC_VOLUME, padding)
                AppSubPage.SideVolume -> PercentStyleContent(PercentLocation.SIDE_VOLUME, padding)
                AppSubPage.NetworkIcons -> Unit
                AppSubPage.IconSettings -> IconSettingsContent(iconId, padding)
                AppSubPage.PackageInstaller -> PackageInstallerContent(padding)
                AppSubPage.Scope -> ScopeListContent(padding)
                AppSubPage.SafeMode -> SafeModeContent(padding)
                AppSubPage.Licenses -> Unit
            }
        }
    }
}
