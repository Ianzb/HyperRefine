package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassHook
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.hook.systemui.glass.CcMaterialGateHook
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusHook
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusKeys
import cn.ianzb.hyperrefine.hook.systemui.wallpaper.LockGlassDimHook
import cn.ianzb.hyperrefine.hook.systemui.wallpaper.WallpaperDarkDimHook
import cn.ianzb.hyperrefine.hook.systemui.wallpaper.WallpaperDimKeys
import cn.ianzb.hyperrefine.hook.systemui.statusbar.HideWifiActivityHook
import cn.ianzb.hyperrefine.hook.systemui.statusbar.DualSimSignalHook
import cn.ianzb.hyperrefine.hook.systemui.statusbar.MobileIndicatorVisibilityHook
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconHook
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconKeys
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarKeys

/**
 * 系统界面（com.android.systemui）目标 Load：
 * 控制中心音量条 / 控制中心亮度条 / 侧边音量条的百分比数值显示，
 * 融合设备中心隐藏末尾「…」省略卡片、缩小设备点击判定范围，
 * 状态栏图标自定义（显示模式 / 状态条件 / 排序 / 布局），
 * 以及深色模式自动压暗壁纸。
 */
class SystemUiLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(HideWifiActivityHook(), HookPrefs.getBoolean(StatusBarKeys.WIFI_ACTIVITY))
        initHook(MobileIndicatorVisibilityHook(), HookPrefs.getBoolean(StatusBarKeys.HIDE_TYPE))
        initHook(DualSimSignalHook(), HookPrefs.getBoolean(StatusBarKeys.DUAL_ROWS))
        initHook(
            StatusBarIconHook(),
            HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false),
        )
        initHook(CcVolumePercentHook(), HookPrefs.getBoolean(CcVolumePercentHook.KEY, false))
        initHook(CcBrightnessPercentHook(), HookPrefs.getBoolean(CcBrightnessPercentHook.KEY, false))
        initHook(SideVolumePercentHook(), HookPrefs.getBoolean(SideVolumePercentHook.KEY, false))
        initHook(SideVolumeLongPressHook(), HookPrefs.getBoolean(SideVolumeLongPressHook.KEY, false))
        initHook(DeviceCenterMoreHook(), HookPrefs.getBoolean(DeviceCenterMoreHook.KEY, false))
        initHook(
            DeviceCenterFastLoadHook(),
            HookPrefs.getBoolean(DeviceCenterFastLoadHook.KEY, false),
        )
        initHook(FiveGSwitchHook(), HookPrefs.getBoolean(FiveGSwitchHook.KEY, false))
        initHook(AodBatteryStyleHook(), HookPrefs.getBoolean(AodBatteryStyleHook.KEY, false))
        initHook(AodFullScreenKeepHook(), HookPrefs.getBoolean(AodFullScreenKeepHook.KEY, false))
        initHook(AodBottomIconsHook(), HookPrefs.getBoolean(AodBottomIconsHook.KEY, false))
        initHook(
            DeviceCenterHitAreaHook(),
            HookPrefs.getBoolean(DeviceCenterHitAreaHook.KEY, false),
        )
        initHook(
            DeviceCenterLandscapeRightHook(),
            HookPrefs.getBoolean(DeviceCenterLandscapeRightHook.KEY, false),
        )
        initHook(CcGlassHook(), HookPrefs.getBoolean(CcGlassHook.KEY, false))
        initHook(SideGlassMirrorHook(), HookPrefs.getBoolean(CcGlassKeys.MASTER, false))
        initHook(CcMaterialGateHook(), HookPrefs.getBoolean(CcGlassKeys.THEME_MATERIAL, false))
        initHook(WallpaperDarkDimHook(), HookPrefs.getBoolean(WallpaperDimKeys.ENABLED, false))
        // 常驻：锁屏玻璃的压暗跟随系统壁纸压暗开关/滑条实时变化，不要求重启 SystemUI。
        initHook(LockGlassDimHook(), true)
        // 总开关开启，或任一组件的单项自定义开启，即挂载圆角 hook
        // （总开关关闭时单项自定义仍生效，未自定义的组件保持系统默认）。
        val radiusEnabled = HookPrefs.getBoolean(CcRadiusKeys.MASTER, false) ||
            CcRadiusKeys.ITEMS.any {
                HookPrefs.getBoolean(CcRadiusKeys.customKey(it), CcRadiusKeys.itemCustomDefault(it))
            }
        initHook(CcRadiusHook(), radiusEnabled)
        initHook(
            AppVolumeEntryHook(),
            HookPrefs.getBoolean(AppVolumeKeys.ENTRY, false) ||
                HookPrefs.getBoolean(AppVolumeKeys.HIDE_RINGER, false) ||
                HookPrefs.getBoolean(AppVolumeKeys.HIDE_DND, false),
        )
        initHook(VolumeBarSizeHook(), HookPrefs.getBoolean(VolumeBarKeys.BAR_SIZE, false))
        initHook(SideVolumePositionHook(), HookPrefs.getBoolean(VolumeBarKeys.SIDE_POS, false))
        initHook(SidePanelPositionHook(), HookPrefs.getBoolean(VolumeBarKeys.L2_POS, false))
        initHook(
            SplitDirectionUnlockHook(),
            HookPrefs.getBoolean(SplitScreenKeys.UNLOCK_DIRECTION, false),
        )
        initHook(
            MultipleSplitStagesHook(),
            HookPrefs.getBoolean(SplitScreenKeys.MULTIPLE_SPLIT_CUSTOM, false),
        )
        initHook(
            SingleSplitFreeRatioHook(),
            HookPrefs.getBoolean(SplitScreenKeys.SINGLE_SPLIT_FREE_RATIO, false) ||
                HookPrefs.getBoolean(SplitScreenKeys.SPLIT_FREE_RATIO_GLOBAL, false),
        )
        initHook(
            SplitRatioMemoryHook(),
            HookPrefs.getBoolean(SplitScreenKeys.SPLIT_RATIO_MEMORY, false),
        )
        initHook(
            SplitPeekTransitionHook(),
            HookPrefs.getBoolean(SplitScreenKeys.SPLIT_PEEK_TRANSITION, false),
        )
        initHook(
            MultipleSplitFreeRatioHook(),
            HookPrefs.getBoolean(SplitScreenKeys.MULTIPLE_SPLIT_FREE_RATIO, false),
        )
        initHook(
            CaptionSplitBothDirectionsHook(),
            HookPrefs.getBoolean(SplitScreenKeys.CAPTION_BOTH_DIRECTIONS, false),
        )
        initHook(
            DisableCornerFreeformHook(),
            HookPrefs.getBoolean(SplitScreenKeys.DISABLE_CORNER_FREEFORM, false),
        )
        initHook(
            FreeformFreeRatioHook(),
            HookPrefs.getBoolean(SplitScreenKeys.FREEFORM_FREE_RATIO, false),
        )
        // 方向判定兼容层：两个入口任一开启都需要（本身没有配置项）。
        initHook(
            SplitDirectionCompatHook(),
            HookPrefs.getBoolean(SplitScreenKeys.UNLOCK_DIRECTION, false) ||
                HookPrefs.getBoolean(SplitScreenKeys.CAPTION_BOTH_DIRECTIONS, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.systemui"
    }
}
