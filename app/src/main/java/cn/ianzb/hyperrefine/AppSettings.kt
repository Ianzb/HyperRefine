package cn.ianzb.hyperrefine

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * 底部悬浮导航栏样式。
 *
 * 应用只提供悬浮胶囊底栏（没有贴底的经典底栏）：
 * - [HyperOS]：HyperOS 4 玻璃胶囊（`GlassNavigationBar`），默认样式
 * - [IOS]：iOS 液态玻璃胶囊（`IosLiquidGlassNavigationBar`）
 */
enum class BottomBarStyle(val value: Int) {
    HyperOS(1),
    IOS(2);

    companion object {
        fun fromValue(value: Int): BottomBarStyle = entries.firstOrNull { it.value == value } ?: HyperOS
    }
}

/**
 * 宽屏侧边导航栏样式（`GlassNavigationRail`）。
 *
 * 侧栏只在宽屏生效，窄屏始终显示底部悬浮胶囊。
 * - [None]：不使用侧栏，宽屏也用底部悬浮胶囊
 * - [Top]：进入宽屏时停在顶部折叠胶囊
 * - [Left]：进入宽屏时停在左侧展开面板
 *
 * 运行时仍可点击胶囊上的按钮在「折叠胶囊 ⇄ 展开面板」之间切换。
 */
enum class RailStyle(val value: Int) {
    None(0),
    Top(1),
    Left(2);

    /** 是否启用侧栏。 */
    val isEnabled: Boolean get() = this != None

    companion object {
        val Default = Left

        fun fromValue(value: Int): RailStyle = entries.firstOrNull { it.value == value } ?: Default
    }
}

/** 应用设置快照，同时用于 SharedPreferences 与 JSON 配置备份。 */
data class AppSettings(
    val themeMode: String = "System",
    val bottomBarStyle: BottomBarStyle = BottomBarStyle.HyperOS,
    val railStyle: RailStyle = RailStyle.Default,
    val isBlurEnabled: Boolean = true,
    val checkUpdateOnLaunch: Boolean = true,
    val language: String = "",
    val hideLauncherIcon: Boolean = false,
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("themeMode", themeMode)
        json.put("bottomBarStyle", bottomBarStyle.value)
        json.put("railStyle", railStyle.value)
        json.put("isBlurEnabled", isBlurEnabled)
        json.put("checkUpdateOnLaunch", checkUpdateOnLaunch)
        json.put("language", language)
        json.put("hideLauncherIcon", hideLauncherIcon)
        return json.toString(2)
    }

    companion object {
        private const val PREFS_NAME = "app_settings"

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BOTTOM_BAR_STYLE = "bottom_bar_style"
        private const val KEY_RAIL_STYLE = "rail_style"
        private const val KEY_BLUR_ENABLED = "blur_enabled"
        private const val KEY_CHECK_UPDATE_ON_LAUNCH = "check_update_on_launch"
        private const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"

        private fun legacyBottomBarStyle(floating: Boolean, liquid: Boolean): BottomBarStyle =
            if (floating && liquid) BottomBarStyle.IOS else BottomBarStyle.HyperOS

        fun fromJson(json: String): AppSettings = try {
            val obj = JSONObject(json)
            AppSettings(
                themeMode = obj.optString("themeMode", "System"),
                bottomBarStyle = BottomBarStyle.fromValue(
                    obj.optInt(
                        "bottomBarStyle",
                        legacyBottomBarStyle(
                            obj.optBoolean("isFloatingNavbar"),
                            obj.optBoolean("isLiquidGlass"),
                        ).value,
                    )
                ),
                railStyle = RailStyle.fromValue(obj.optInt("railStyle", RailStyle.Default.value)),
                isBlurEnabled = obj.optBoolean("isBlurEnabled", true),
                checkUpdateOnLaunch = obj.optBoolean("checkUpdateOnLaunch", true),
                language = obj.optString("language", ""),
                hideLauncherIcon = obj.optBoolean("hideLauncherIcon", false),
            )
        } catch (_: Exception) {
            AppSettings()
        }

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return AppSettings(
                themeMode = prefs.getString(KEY_THEME_MODE, "System") ?: "System",
                bottomBarStyle = BottomBarStyle.fromValue(
                    prefs.getInt(
                        KEY_BOTTOM_BAR_STYLE,
                        legacyBottomBarStyle(
                            prefs.getBoolean("floating_navbar", false),
                            prefs.getBoolean("liquid_glass", false),
                        ).value,
                    )
                ),
                railStyle = RailStyle.fromValue(prefs.getInt(KEY_RAIL_STYLE, RailStyle.Default.value)),
                isBlurEnabled = prefs.getBoolean(KEY_BLUR_ENABLED, true),
                checkUpdateOnLaunch = prefs.getBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, true),
                language = LocaleHelper.getSavedLanguage(context).code,
                hideLauncherIcon = prefs.getBoolean(KEY_HIDE_LAUNCHER_ICON, false),
            )
        }

        fun save(context: Context, settings: AppSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_THEME_MODE, settings.themeMode)
                putInt(KEY_BOTTOM_BAR_STYLE, settings.bottomBarStyle.value)
                putInt(KEY_RAIL_STYLE, settings.railStyle.value)
                putBoolean(KEY_BLUR_ENABLED, settings.isBlurEnabled)
                putBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, settings.checkUpdateOnLaunch)
                putBoolean(KEY_HIDE_LAUNCHER_ICON, settings.hideLauncherIcon)
            }
            val language = LocaleHelper.Language.entries
                .firstOrNull { it.code == settings.language }
                ?: LocaleHelper.Language.SYSTEM
            LocaleHelper.setLanguage(context, language)
        }

        fun importFromJson(context: Context, json: String): AppSettings {
            val settings = fromJson(json)
            save(context, settings)
            return settings
        }
    }
}
