package cn.ianzb.hyperrefine.hook.notification

/** Shared by the preference page and all three target processes. */
object NotificationKeys {
    const val KEEP_ON_LOCKSCREEN = "notification_keep_on_lockscreen"
    const val ALLOW_LOCKSCREEN = "notification_allow_lockscreen"
    const val ALLOW_FLOAT = "notification_allow_float"
    const val ALLOW_EDIT = "notification_allow_edit"
    const val IMPORTANCE = "notification_importance"
    const val DISABLE_FOLD = "notification_disable_fold"
    const val REMOVE_LIMIT = "notification_remove_limit"
    const val GROUP_SWIPE_CLIP = "notification_group_swipe_clip"

    const val SYSTEM_UI = "com.android.systemui"
    const val SETTINGS = "com.android.settings"
    const val SYSTEM = "system"

    val ALL = listOf(KEEP_ON_LOCKSCREEN, ALLOW_LOCKSCREEN, ALLOW_FLOAT, ALLOW_EDIT,
        IMPORTANCE, DISABLE_FOLD, REMOVE_LIMIT, GROUP_SWIPE_CLIP)

    fun targets(key: String): List<String> = when (key) {
        ALLOW_EDIT -> listOf(SYSTEM_UI, SETTINGS, SYSTEM)
        IMPORTANCE -> listOf(SYSTEM_UI, SETTINGS)
        else -> listOf(SYSTEM_UI)
    }
}
