package cn.ianzb.hyperrefine.hook.misound

/**
 * 「多应用音量」配置键。
 *
 * 与 App 侧 `FeaturesPage` 的声明保持一致。
 */
object AppVolumeKeys {

    /** 总开关：在侧边音量条底部显示多应用音量入口。 */
    const val ENTRY = "app_volume_entry"

    /** 隐藏系统左侧的蓝色多应用音量悬浮球。 */
    const val HIDE_FLOAT = "app_volume_hide_float"

    /** 入口常显；关闭时仅在检测到媒体播放时显示。 */
    const val ALWAYS_SHOW = "app_volume_always_show"

    /** 自动显示：打开侧边音量条时自动展开多应用音量面板（入口按钮仍可点击开关）。 */
    const val AUTO_SHOW = "app_volume_auto_show"

    /** 面板垂直位置百分比（0=顶部，50=居中，100=底部）。 */
    const val HEIGHT_PERCENT = "app_volume_height_percent"

    /** 高度自动：面板内的音量条与侧边音量条竖直对齐（与 [HEIGHT_PERCENT] 互斥）。 */
    const val HEIGHT_AUTO = "app_volume_height_auto"

    /** 隐藏面板背景：不显示面板的玻璃 / 背景，只保留音量条。 */
    const val HIDE_PANEL_BG = "app_volume_hide_panel_bg"

    /** 目标包：MiSound（承载多应用音量页面 / 悬浮球）。 */
    const val TARGET_PACKAGE = "com.miui.misound"

    /** 模块自带图标（Miuix Tune）。由 hook 进程经模块资源按名解析。 */
    const val MODULE_PACKAGE = "cn.ianzb.hyperrefine"
    const val ICON_NAME = "ic_app_volume"

    /** 侧边音量条入口点击后，发往 MiSound 进程的展开广播。 */
    const val ACTION_EXPAND = "cn.ianzb.hyperrefine.action.EXPAND_APP_VOLUME"

    /**
     * 系统界面模式：系统界面把「包名 + 音量」转发到 MiSound 进程的广播。
     * MiSound 侧接收后用官方同款机制生效（`AudioManager.setPlayerVolume`）并同步其面板数值。
     */
    const val ACTION_SET_VOLUME = "cn.ianzb.hyperrefine.action.SET_APP_VOLUME"

    /** [ACTION_SET_VOLUME]：目标应用包名。 */
    const val EXTRA_PACKAGE = "hyperrefine_app_volume_package"

    /** [ACTION_SET_VOLUME]：目标音量（0f..1f）。 */
    const val EXTRA_VOLUME = "hyperrefine_app_volume_value"

    /** MiSound 多应用音量控制器（混淆类）。 */
    const val CONTROLLER_CLASS = "com.miui.misound.playervolume.a"

    /** MiSound 控制器静态取实例方法（`a.j(Context)`）。 */
    const val CONTROLLER_GETTER = "j"

    /** VolumeUIService（exported）类名，用于入口点击时显式拉起 MiSound 进程。 */
    const val VOLUME_UI_SERVICE_CLASS = "com.miui.misound.playervolume.VolumeUIService"

    /** 通过 VolumeUIService 启动 Intent 携带的「展开多应用音量面板」标记。 */
    const val EXTRA_EXPAND = "hyperrefine_expand_app_volume"
}
