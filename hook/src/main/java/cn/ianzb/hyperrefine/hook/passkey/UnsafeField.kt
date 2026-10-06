package cn.ianzb.hyperrefine.hook.passkey

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 通过 `sun.misc.Unsafe` 写入字段值，用于绕过 Android 16+ 对 `static final` 字段的反射写入限制。
 *
 * 独立实现（思路参考 HyperPasskey，GPL-3.0）：以反射访问 `sun.misc.Unsafe`，避免编译期依赖隐藏 API；
 * 先尝试常规反射，失败后再用 Unsafe 写入。偏移字段定位优先使用 `Field.offset`，不可用时用 ART 探测兜底。
 */
internal object UnsafeField {

    private val unsafe: Any? by lazy {
        runCatching {
            val cls = Class.forName("sun.misc.Unsafe")
            val field = cls.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null)
        }.getOrNull()
    }

    private fun unsafeMethod(name: String, vararg params: Class<*>): Method? =
        runCatching { unsafe?.javaClass?.getMethod(name, *params) }.getOrNull()

    private val objectFieldOffset: Method? by lazy { unsafeMethod("objectFieldOffset", Field::class.java) }
    private val getInt: Method? by lazy {
        unsafeMethod("getInt", Any::class.java, Long::class.javaPrimitiveType!!)
    }
    private val putInt: Method? by lazy {
        unsafeMethod("putInt", Any::class.java, Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    }
    private val putBoolean: Method? by lazy {
        unsafeMethod(
            "putBoolean", Any::class.java, Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!,
        )
    }
    private val putObject: Method? by lazy {
        unsafeMethod("putObject", Any::class.java, Long::class.javaPrimitiveType!!, Any::class.java)
    }

    /** `java.lang.reflect.Field.offset` 字段相对 Field 对象的偏移。 */
    private val fieldOffsetOffset: Long? by lazy { resolveFieldOffsetOffset() }

    private fun isStaticFinal(field: Field): Boolean {
        val modifiers = field.modifiers
        return (modifiers and Modifier.STATIC) != 0 && (modifiers and Modifier.FINAL) != 0
    }

    fun setBoolean(field: Field, target: Any?, value: Boolean): Boolean {
        if (!isStaticFinal(field)) {
            if (runCatching { field.isAccessible = true; field.setBoolean(target, value); true }.getOrDefault(false)) {
                return true
            }
        }
        val u = unsafe ?: return false
        val offset = fieldOffset(field) ?: return false
        val receiver = if ((field.modifiers and Modifier.STATIC) != 0) field.declaringClass else target
        return runCatching { putBoolean?.invoke(u, receiver, offset, value); true }.getOrDefault(false)
    }

    fun setObject(field: Field, target: Any?, value: Any?): Boolean {
        if (!isStaticFinal(field)) {
            if (runCatching { field.isAccessible = true; field.set(target, value); true }.getOrDefault(false)) {
                return true
            }
        }
        val u = unsafe ?: return false
        val offset = fieldOffset(field) ?: return false
        val receiver = if ((field.modifiers and Modifier.STATIC) != 0) field.declaringClass else target
        return runCatching { putObject?.invoke(u, receiver, offset, value); true }.getOrDefault(false)
    }

    /** 读取字段偏移：优先读取 Field.offset，不可用时用 ART 探测兜底。 */
    private fun fieldOffset(field: Field): Long? {
        val u = unsafe ?: return null
        val metaOffset = fieldOffsetOffset ?: return null
        val raw = getInt?.invoke(u, field, metaOffset) as? Int ?: return null
        return raw.toLong() and 0xFFFFFFFFL
    }

    private fun resolveFieldOffsetOffset(): Long? {
        val u = unsafe ?: return null
        val offsetOf = objectFieldOffset ?: return null

        runCatching {
            val f = Field::class.java.getDeclaredField("offset")
            runCatching { f.isAccessible = true; f.getInt(f) }
            return (offsetOf.invoke(u, f) as Long)
        }

        // ART 兜底：用一个已知字段的偏移值去探测 Field.offset 字段的位置。
        runCatching {
            val pointClass = Class.forName("android.graphics.Point")
            val probe = pointClass.getDeclaredField("x")
            probe.isAccessible = true
            probe.getInt(pointClass.getDeclaredConstructor().newInstance())
            val probeOffset = (offsetOf.invoke(u, probe) as Long).toInt()
            for (slot in 8 until 256 step 4) {
                val slotOffset = slot.toLong()
                if (getInt?.invoke(u, probe, slotOffset) != probeOffset) continue
                val modified = probeOffset.inv()
                putInt?.invoke(u, probe, slotOffset, modified)
                val after = (offsetOf.invoke(u, probe) as Long).toInt()
                putInt?.invoke(u, probe, slotOffset, probeOffset)
                if (after == modified) return slotOffset
            }
        }
        return null
    }
}
