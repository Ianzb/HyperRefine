package cn.ianzb.hyperrefine.hook.passkey

import java.lang.reflect.Field

/**
 * 在调用期间临时把 `miui.os.Build.IS_INTERNATIONAL_BUILD` 置为 `true`，结束后还原。
 *
 * 用于让 CN 版「设置」把 Google 凭据提供方视作可用（国际版行为）。支持嵌套调用，
 * 仅在最外层真正修改与还原。
 */
internal class MiuiInternationalFlag(private val field: Field) {

    private val depth = ThreadLocal.withInitial { 0 }
    private val previous = ThreadLocal<Boolean>()

    fun <T> run(block: () -> T): T {
        val current = depth.get() ?: 0
        if (current == 0) {
            val prev = runCatching {
                field.isAccessible = true
                field.getBoolean(null)
            }.getOrDefault(false)
            previous.set(prev)
            if (!prev) UnsafeField.setBoolean(field, null, true)
        }
        depth.set(current + 1)
        try {
            return block()
        } finally {
            val remaining = (depth.get() ?: 1) - 1
            if (remaining <= 0) {
                val prev = previous.get()
                if (prev != null && !prev) UnsafeField.setBoolean(field, null, prev)
                previous.remove()
                depth.remove()
            } else {
                depth.set(remaining)
            }
        }
    }
}
