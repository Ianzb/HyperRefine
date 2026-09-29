package cn.ianzb.hyperrefine.hook.connect

import java.lang.reflect.Field

/**
 * MiLink 私有共享通道策略对象的结构适配器。
 *
 * 只接受精确的三项 `{1, 2, 4}` 数值上限表，且仅当 AndroidPad 当前值为 `1` 时改为 `2`；
 * 已是 `2` 幂等接受，大于 `2` 或未知结构不处理。
 *
 * 移植自 HyperConnectToolkit（Apache-2.0，见应用内「第三方许可」页）。
 */
object ChannelLimitPolicy {

    enum class Result {
        UPDATED,
        ALREADY_SUPPORTED,
        UNSUPPORTED,
    }

    fun ensureAndroidPadDualChannel(policy: Any?): Result {
        if (policy == null) return Result.UNSUPPORTED

        var type: Class<*>? = policy.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (!Map::class.java.isAssignableFrom(field.type)) continue
                val result = inspectField(policy, field)
                if (result != Result.UNSUPPORTED) return result
            }
            type = type.superclass
        }
        return Result.UNSUPPORTED
    }

    private fun inspectField(policy: Any, field: Field): Result {
        return try {
            field.isAccessible = true
            val limits = field.get(policy) as? Map<*, *> ?: return Result.UNSUPPORTED
            if (!isLimitTable(limits)) return Result.UNSUPPORTED

            val currentLimit = (limits[ANDROID_PAD] as? Number)?.toInt() ?: return Result.UNSUPPORTED
            if (currentLimit == DUAL_CHANNEL_LIMIT) return Result.ALREADY_SUPPORTED
            if (currentLimit != SINGLE_CHANNEL_LIMIT) return Result.UNSUPPORTED

            put(limits, ANDROID_PAD, DUAL_CHANNEL_LIMIT)
            if ((limits[ANDROID_PAD] as? Number)?.toInt() == DUAL_CHANNEL_LIMIT) {
                Result.UPDATED
            } else {
                Result.UNSUPPORTED
            }
        } catch (_: ReflectiveOperationException) {
            Result.UNSUPPORTED
        } catch (_: RuntimeException) {
            Result.UNSUPPORTED
        }
    }

    private fun isLimitTable(limits: Map<*, *>): Boolean =
        limits.size == 3 &&
            isReasonableLimit(limits[ANDROID_PHONE]) &&
            isReasonableLimit(limits[ANDROID_PAD]) &&
            isReasonableLimit(limits[WINDOWS_PC])

    private fun isReasonableLimit(value: Any?): Boolean =
        value is Number && value.toInt() in 1..8

    @Suppress("UNCHECKED_CAST", "SameParameterValue")
    private fun put(map: Map<*, *>, key: Int, value: Int) {
        (map as MutableMap<Any?, Any?>)[key] = value
    }

    private const val ANDROID_PHONE = 1
    private const val ANDROID_PAD = 2
    private const val WINDOWS_PC = 4
    private const val SINGLE_CHANNEL_LIMIT = 1
    private const val DUAL_CHANNEL_LIMIT = 2
}
