package cn.ianzb.hyperrefine.hook.misound

/**
 * 「分应用音量」配置键。
 *
 * 与 App 侧 `FeaturesPage` 的声明保持一致。
 */
object AppVolumeKeys {

    /** 总开关：在侧边音量条底部显示分应用音量入口。 */
    const val ENTRY = "app_volume_entry"

    /** 隐藏系统左侧的蓝色分应用音量悬浮球。 */
    const val HIDE_FLOAT = "app_volume_hide_float"

    /** 入口常显；关闭时仅在检测到媒体播放时显示。 */
    const val ALWAYS_SHOW = "app_volume_always_show"

    /** 面板位置：关闭时居中，开启时靠右（保留右边距）。 */
    const val ALIGN_RIGHT = "app_volume_align_right"

    /** 面板垂直位置百分比（0=顶部，50=居中，100=底部）。 */
    const val HEIGHT_PERCENT = "app_volume_height_percent"

    /** 隐藏面板背景模糊边框（使面板透明，仅保留音量条）。默认关。 */
    const val HIDE_BLUR_BG = "app_volume_hide_blur_bg"

    /** 目标包：MiSound（承载分应用音量页面 / 悬浮球）。 */
    const val TARGET_PACKAGE = "com.miui.misound"

    /** 模块自带图标（Miuix Tune）。由 hook 进程经模块资源按名解析。 */
    const val MODULE_PACKAGE = "cn.ianzb.hyperrefine"
    const val ICON_NAME = "ic_app_volume"

    /** 侧边音量条入口点击后，发往 MiSound 进程的展开广播。 */
    const val ACTION_EXPAND = "cn.ianzb.hyperrefine.action.EXPAND_APP_VOLUME"

    /** VolumeUIService（exported）类名，用于入口点击时显式拉起 MiSound 进程。 */
    const val VOLUME_UI_SERVICE_CLASS = "com.miui.misound.playervolume.VolumeUIService"

    /** 通过 VolumeUIService 启动 Intent 携带的「展开分应用音量面板」标记。 */
    const val EXTRA_EXPAND = "hyperrefine_expand_app_volume"
}
