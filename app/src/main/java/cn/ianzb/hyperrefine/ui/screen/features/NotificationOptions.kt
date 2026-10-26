package cn.ianzb.hyperrefine.ui.screen.features

import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.notification.NotificationKeys
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType

const val KEY_NOTIFICATIONS = "feature_notifications"

internal fun notificationFeatureSpecs(): List<OptionSpec> {
    val labels = listOf(
        Triple(NotificationKeys.GROUP_SWIPE_CLIP, R.string.notification_group_swipe_clip, R.string.notification_group_swipe_clip_summary),
        Triple(NotificationKeys.KEEP_ON_LOCKSCREEN, R.string.notification_keep, R.string.notification_keep_summary),
        Triple(NotificationKeys.ALLOW_LOCKSCREEN, R.string.notification_lockscreen, R.string.notification_lockscreen_summary),
        Triple(NotificationKeys.ALLOW_FLOAT, R.string.notification_float, R.string.notification_float_summary),
        Triple(NotificationKeys.ALLOW_EDIT, R.string.notification_edit, R.string.notification_edit_summary),
        Triple(NotificationKeys.IMPORTANCE, R.string.notification_importance, R.string.notification_importance_summary),
        Triple(NotificationKeys.DISABLE_FOLD, R.string.notification_fold, R.string.notification_fold_summary),
        Triple(NotificationKeys.REMOVE_LIMIT, R.string.notification_limit, R.string.notification_limit_summary),
    )
    return listOf(OptionSpec(
        key = KEY_NOTIFICATIONS,
        type = OptionType.ARROW,
        titleRes = R.string.notifications_title,
    )) + labels.map { (key, title, summary) ->
        OptionSpec(
            key = key,
            type = OptionType.SWITCH,
            titleRes = title,
            summaryRes = summary,
            defaultBoolean = false,
            targetPackages = NotificationKeys.targets(key),
            // Existing status evidence is per process. Do not imply that one process
            // installing its part proves the entire multi-process feature is applied.
            showStatus = key != NotificationKeys.ALLOW_EDIT && key != NotificationKeys.IMPORTANCE,
        )
    }
}

internal fun notificationSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    NotificationKeys.ALL.map { key -> specs.first { it.key == key } }
