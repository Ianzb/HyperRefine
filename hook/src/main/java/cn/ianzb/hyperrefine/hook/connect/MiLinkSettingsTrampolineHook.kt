package cn.ianzb.hyperrefine.hook.connect

import android.app.Activity
import android.content.Intent
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 跨设备通知流转设置页「跳板」。
 *
 * `FeatureNotificationActivity`（`com.miui.circulate.world.ui.connectivitysettings.Notification`）
 * 受签名级权限 `miui.permission.USE_INTERNAL_GENERAL_API` 保护，普通应用无法直接启动
 * （会抛 `SecurityException: Permission Denial`）。
 *
 * 这里 hook 目标应用自身的导出入口 `com.milink.ui.setting.SettingActivity`：当它由本模块携带
 * [ConnectKeys.EXTRA_OPEN_NOTIFICATION_SETTINGS] 启动时，在同一进程内改启目标页面
 * （同包同 uid，不受该权限限制）并结束跳板。
 *
 * 目标进程：`com.milink.service`（主进程）。
 */
class MiLinkSettingsTrampolineHook : BaseHook() {

    override val key: String = ConnectKeys.CROSS_DEVICE_NOTIFICATION

    override fun init() {
        val cl = target.classLoader ?: return
        val cls = Reflect.findClassIfExists(SETTING_ACTIVITY, cl)
        if (cls == null) {
            HookHelper.log("$tag: $SETTING_ACTIVITY not found")
            return
        }
        var hooked = 0
        cls.declaredMethods
            .filter { it.name == "onCreate" || it.name == "onNewIntent" }
            .forEach { method ->
                method.isAccessible = true
                runCatching { HookHelper.hookAfter(method) { param -> redirect(param) } }
                    .onSuccess { hooked++ }
                    .onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
            }
        HookHelper.log("$tag: Settings trampoline hooked x$hooked")
    }

    private fun redirect(param: HookParam) {
        val activity = param.thisObject as? Activity ?: return
        val intent = activity.intent ?: return
        if (!intent.getBooleanExtra(ConnectKeys.EXTRA_OPEN_NOTIFICATION_SETTINGS, false)) return
        intent.removeExtra(ConnectKeys.EXTRA_OPEN_NOTIFICATION_SETTINGS)
        runCatching {
            activity.startActivity(
                Intent().setClassName(activity.packageName, TARGET_ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { HookHelper.log("$tag: redirect failed", it) }
        activity.finish()
    }

    private companion object {
        const val SETTING_ACTIVITY = "com.milink.ui.setting.SettingActivity"
        const val TARGET_ACTIVITY =
            "com.miui.circulate.world.ui.connectivitysettings.Notification.FeatureNotificationActivity"
    }
}
