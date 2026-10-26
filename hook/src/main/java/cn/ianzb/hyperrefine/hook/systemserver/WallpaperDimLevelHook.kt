package cn.ianzb.hyperrefine.hook.systemserver

import android.app.WallpaperManager
import android.content.res.Configuration
import android.content.res.Resources
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.wallpaper.WallpaperDimKeys
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Field
import kotlin.math.abs

/**
 * 桌面 / 锁屏壁纸分别压暗。
 *
 * `WallpaperManager#setWallpaperDimAmount(float)` 最终落到
 * `WallpaperManagerService#setWallpaperDimAmountForUid`，该方法会把同一个值同时写入
 * 桌面与锁屏两个 [android.app.WallpaperData]，所以只靠公开 API 无法得到两个值。
 *
 * 这里不复制系统逻辑，而是在系统把最终值下发给每个壁纸引擎的入口
 * `WallpaperManagerService$DisplayConnector#applyDimming(float)` 前，按连接器的
 * `mWhich`（`FLAG_SYSTEM` / `FLAG_LOCK`）替换参数：桌面用 [WallpaperDimKeys.AMOUNT]，
 * 锁屏用 [WallpaperDimKeys.LOCK_AMOUNT]。系统仍负责临时压暗、动画与恢复；
 * 只在模块已经开启、当前为深色且传入值仍是桌面值时改写，避免覆盖系统的临时压暗。
 *
 * 目标进程：system_server（`system`）。hook 常驻安装；首次注入后，开关与滑条变更无需再次重启，
 * 下一次系统下发压暗时生效。
 */
class WallpaperDimLevelHook : BaseHook() {

    /** 仅用于 hook 状态与日志，没有对应的 UI 选项。 */
    override val key: String = HOOK_KEY

    override fun init() {
        val cl = target.classLoader ?: return
        val connector = Reflect.findClassIfExists(DISPLAY_CONNECTOR, cl)
        if (connector == null) {
            HookHelper.log("$tag: $DISPLAY_CONNECTOR not found")
            return
        }
        val method = connector.declaredMethods.firstOrNull {
            it.name == "applyDimming" &&
                it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
        }
        if (method == null) {
            HookHelper.log("$tag: applyDimming(float) not found")
            return
        }
        val whichField = runCatching {
            connector.getDeclaredField("mWhich").apply { isAccessible = true }
        }.onFailure {
            HookHelper.log("$tag: mWhich not found", it)
        }.getOrNull() ?: return

        runCatching {
            method.isAccessible = true
            HookHelper.deoptimize(method)
            HookHelper.hookBefore(method) { param ->
                rewriteDim(param.thisObject, whichField, param)
            }
        }.onFailure {
            HookHelper.log("$tag: hook applyDimming failed", it)
            return
        }
        HookHelper.log("$tag: desktop / lock wallpaper dim levels installed")
    }

    private fun rewriteDim(connector: Any?, whichField: Field, param: HookParam) {
        if (!HookPrefs.getBoolean(WallpaperDimKeys.ENABLED, false)) return
        if (!isNight()) return
        val which = runCatching { whichField.getInt(connector) }.getOrDefault(0)
        if (which != WallpaperManager.FLAG_SYSTEM && which != WallpaperManager.FLAG_LOCK) return

        val home = amount(WallpaperDimKeys.AMOUNT, WallpaperDimKeys.DEFAULT_AMOUNT)
        val lock = if (HookPrefs.contains(WallpaperDimKeys.LOCK_AMOUNT)) {
            amount(WallpaperDimKeys.LOCK_AMOUNT, WallpaperDimKeys.DEFAULT_AMOUNT)
        } else {
            // 旧版本只有一个值；未写入锁屏键时保持升级前行为。
            home
        }
        val current = (param.args.getOrNull(0) as? Float) ?: return
        // 系统临时压暗 / 其它调用方可能传入不同值，不要覆盖。
        if (abs(current - home) > 0.001f) return

        val target = if (which == WallpaperManager.FLAG_LOCK) lock else home
        if (abs(target - current) <= 0.001f) return
        param.setArg(0, target)
    }

    private fun amount(key: String, fallback: Float): Float =
        HookPrefs.getFloat(key, fallback).coerceIn(0f, 100f) / 100f

    private fun isNight(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    companion object {
        private const val HOOK_KEY = "wallpaper_dark_dim_level"
        private const val DISPLAY_CONNECTOR =
            "com.android.server.wallpaper.WallpaperManagerService\$DisplayConnector"
    }
}
