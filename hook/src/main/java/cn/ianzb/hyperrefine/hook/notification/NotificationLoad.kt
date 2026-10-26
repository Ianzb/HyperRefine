package cn.ianzb.hyperrefine.hook.notification

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

class NotificationLoad : BaseLoad() {
    override val targetPackages = listOf(NotificationKeys.SYSTEM_UI, NotificationKeys.SETTINGS, NotificationKeys.SYSTEM)

    override fun onPackageLoaded(target: PackageTarget) {
        fun enabled(key: String) = HookPrefs.getBoolean(key, false)
        when {
            target.isSystemServer -> initHook(NotificationBlockabilityHook(), enabled(NotificationKeys.ALLOW_EDIT))
            target.processName == NotificationKeys.SYSTEM_UI -> {
                initHook(
                    NotificationGroupSwipeClipHook(),
                    HookPrefs.getBoolean(NotificationKeys.GROUP_SWIPE_CLIP, false),
                )
                initHook(KeepLockscreenNotificationsHook(), enabled(NotificationKeys.KEEP_ON_LOCKSCREEN))
                initHook(AllowLockscreenNotificationsHook(), enabled(NotificationKeys.ALLOW_LOCKSCREEN))
                initHook(AllowFloatingNotificationsHook(), enabled(NotificationKeys.ALLOW_FLOAT))
                initHook(NotificationRestrictionsHook(), enabled(NotificationKeys.ALLOW_EDIT))
                initHook(NotificationImportanceIconsHook(), enabled(NotificationKeys.IMPORTANCE))
                initHook(DisableNotificationFoldHook(), enabled(NotificationKeys.DISABLE_FOLD))
                initHook(RemoveNotificationLimitHook(), enabled(NotificationKeys.REMOVE_LIMIT))
            }
            target.processName == NotificationKeys.SETTINGS -> {
                initHook(NotificationRestrictionsHook(includeChannel = true), enabled(NotificationKeys.ALLOW_EDIT))
                initHook(NotificationImportanceSettingsHook(), enabled(NotificationKeys.IMPORTANCE))
            }
        }
    }
}
