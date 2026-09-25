package cn.ianzb.hyperrefine

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

data class AppSettings(
    val themeMode: String = "System",
    val isFloatingNavbar: Boolean = false,
    val isLiquidGlass: Boolean = false,
    val isBlurEnabled: Boolean = true,
    val checkUpdateOnLaunch: Boolean = true,
    val language: String = "",
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("themeMode", themeMode)
        json.put("isFloatingNavbar", isFloatingNavbar)
        json.put("isLiquidGlass", isLiquidGlass)
        json.put("isBlurEnabled", isBlurEnabled)
        json.put("checkUpdateOnLaunch", checkUpdateOnLaunch)
        json.put("language", language)
        return json.toString(2)
    }

    companion object {
        fun fromJson(json: String): AppSettings {
            return try {
                val obj = JSONObject(json)
                AppSettings(
                    themeMode = obj.optString("themeMode", "System"),
                    isFloatingNavbar = obj.optBoolean("isFloatingNavbar", false),
                    isLiquidGlass = obj.optBoolean("isLiquidGlass", false),
                    isBlurEnabled = obj.optBoolean("isBlurEnabled", true),
                    checkUpdateOnLaunch = obj.optBoolean("checkUpdateOnLaunch", true),
                    language = obj.optString("language", ""),
                )
            } catch (_: Exception) {
                AppSettings()
            }
        }

        private const val PREFS_NAME = "app_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FLOATING_NAVBAR = "floating_navbar"
        private const val KEY_LIQUID_GLASS = "liquid_glass"
        private const val KEY_BLUR_ENABLED = "blur_enabled"
        private const val KEY_CHECK_UPDATE_ON_LAUNCH = "check_update_on_launch"

        fun load(context: Context): AppSettings {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val language = LocaleHelper.getSavedLanguage(context).code
            return AppSettings(
                themeMode = prefs.getString(KEY_THEME_MODE, "System") ?: "System",
                isFloatingNavbar = prefs.getBoolean(KEY_FLOATING_NAVBAR, false),
                isLiquidGlass = prefs.getBoolean(KEY_LIQUID_GLASS, false),
                isBlurEnabled = prefs.getBoolean(KEY_BLUR_ENABLED, true),
                checkUpdateOnLaunch = prefs.getBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, true),
                language = language,
            )
        }

        fun save(context: Context, settings: AppSettings) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_THEME_MODE, settings.themeMode)
                putBoolean(KEY_FLOATING_NAVBAR, settings.isFloatingNavbar)
                putBoolean(KEY_LIQUID_GLASS, settings.isLiquidGlass)
                putBoolean(KEY_BLUR_ENABLED, settings.isBlurEnabled)
                putBoolean(KEY_CHECK_UPDATE_ON_LAUNCH, settings.checkUpdateOnLaunch)
            }
            // Restore language
            val lang = LocaleHelper.Language.entries.find { it.code == settings.language } ?: LocaleHelper.Language.SYSTEM
            LocaleHelper.setLanguage(context, lang)
        }

        fun importFromJson(context: Context, json: String): AppSettings {
            val settings = fromJson(json)
            save(context, settings)
            return settings
        }
    }
}
