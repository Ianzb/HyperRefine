package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.os.Handler
import android.os.Looper
import android.view.View
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.WeakHashMap

/** FlaresView belongs to its own window in fullscreen, outside the status bar view hierarchy. */
internal object FullscreenPrivacyIndicator {
    private const val CONTROLLER = "com.android.systemui.statusbar.privacy.MiuiPrivacyFlaresDotController"

    fun install(loader: ClassLoader?, guard: StatusBarGuard) {
        val controller = Reflect.findClassIfExists(CONTROLLER, loader) ?: return
        val root = Reflect.findField(controller, "mPrivacyFlares")
        if (!View::class.java.isAssignableFrom(root.type)) return
        val show = controller.declaredMethods.filter { it.name == "show" && it.returnType == Void.TYPE }
        if (show.isEmpty()) return

        val windows = WeakHashMap<View, Boolean>()
        fun apply(view: View, hide: Boolean) {
            val hiddenByModule = windows[view] == true
            if (hide) {
                val wasVisible = view.visibility == View.VISIBLE
                windows[view] = hiddenByModule || wasVisible
                if (wasVisible) view.visibility = View.INVISIBLE
            } else {
                windows[view] = false
                // Native teardown can change visibility before a preference refresh arrives.
                if (hiddenByModule && view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
            }
        }

        StatusBarMembers.install(show.map { method ->
            {
                method.isAccessible = true
                StatusBarMembers.after(method) { param ->
                    guard.run {
                        val receiver = param.thisObject ?: return@run
                        val view = root.get(receiver) as? View ?: return@run
                        apply(view, shouldHide())
                    }
                }
            }
        })
        val main = Handler(Looper.getMainLooper())
        val refresh = Runnable {
            guard.run {
                if (windows.isEmpty()) return@run
                val hide = shouldHide()
                windows.keys.toList().forEach { view -> apply(view, hide) }
            }
        }
        HookPrefs.registerListener {
            main.removeCallbacks(refresh)
            main.post(refresh)
        }
        HookHelper.log("StatusBarIcon: fullscreen privacy window hook installed")
    }

    private fun shouldHide(): Boolean {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return false
        return when (StatusBarIconConfigReader.display(StatusBarIconRegistry.PRIVACY)) {
            StatusBarIconMode.HIDDEN.value, StatusBarIconMode.CC_ONLY_SHOW.value -> true
            else -> false
        }
    }
}
