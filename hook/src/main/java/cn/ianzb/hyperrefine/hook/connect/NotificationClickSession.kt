package cn.ianzb.hyperrefine.hook.connect

/**
 * 关联一次通知点击的两段同步调用。
 *
 * 状态为线程封闭：小米在同一单线程 executor 上顺序执行两次调用。消费为一次性
 * （即使不匹配也移除），并带短过期，避免厂商控制流变化后误吞线程池上后续无关命令。
 */
class NotificationClickSession {

    private val pending = ThreadLocal<PendingClick?>()

    fun begin(deviceId: String, packageName: String, nowMillis: Long) {
        if (!CompatibilityPolicy.isValidNotificationClick(deviceId, packageName)) {
            pending.remove()
            return
        }
        pending.set(PendingClick(deviceId, packageName, nowMillis))
    }

    fun consumeMatching(deviceId: String?, packageName: String?, nowMillis: Long): Boolean {
        val click = pending.get()
        pending.remove()
        if (click == null) return false
        val ageMillis = nowMillis - click.createdAtMillis
        return ageMillis in 0..MAX_AGE_MILLIS &&
            click.deviceId == deviceId &&
            click.packageName == packageName
    }

    fun clear() {
        pending.remove()
    }

    private class PendingClick(
        val deviceId: String,
        val packageName: String,
        val createdAtMillis: Long,
    )

    companion object {
        const val MAX_AGE_MILLIS = 5_000L
    }
}
