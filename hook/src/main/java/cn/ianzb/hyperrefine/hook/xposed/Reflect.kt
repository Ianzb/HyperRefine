package cn.ianzb.hyperrefine.hook.xposed

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 常用反射工具，便于在 hook 代码里定位类 / 方法 / 字段。
 *
 * 字段 / 方法查找结果会按类缓存，避免在高频回调（如音量拖动逐帧）里反复
 * `getDeclaredMethods()`（每次都会复制整个数组）造成掉帧。
 */
object Reflect {

    private val fieldCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<String, Field>>()
    private val methodCache = ConcurrentHashMap<Class<*>, ConcurrentHashMap<MethodKey, Method>>()

    private data class MethodKey(
        val name: String,
        val static: Boolean,
        val argTypes: List<Class<*>>,
    )

    private fun defaultClassLoader(): ClassLoader =
        Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

    fun findClass(name: String, classLoader: ClassLoader? = null): Class<*> =
        Class.forName(name, false, classLoader ?: defaultClassLoader())

    fun findClassIfExists(name: String, classLoader: ClassLoader? = null): Class<*>? =
        runCatching { findClass(name, classLoader) }.getOrNull()

    fun findMethod(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
        val method = clazz.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method
    }

    fun findMethodIfExists(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>): Method? =
        runCatching { findMethod(clazz, name, *parameterTypes) }.getOrNull()

    fun findField(clazz: Class<*>, name: String): Field {
        val byName = fieldCache.getOrPut(clazz) { ConcurrentHashMap() }
        byName[name]?.let { return it }
        val field = clazz.getDeclaredField(name)
        field.isAccessible = true
        byName[name] = field
        return field
    }

    fun callMethod(instance: Any, name: String, vararg args: Any?): Any? {
        val method = bestMatch(instance.javaClass, name, args, static = false)
            ?: throw NoSuchMethodException("${instance.javaClass.name}#$name")
        return method.invoke(instance, *args)
    }

    fun callStaticMethod(clazz: Class<*>, name: String, vararg args: Any?): Any? {
        val method = bestMatch(clazz, name, args, static = true)
            ?: throw NoSuchMethodException("${clazz.name}#$name")
        return method.invoke(null, *args)
    }

    fun getObjectField(instance: Any, name: String): Any? =
        findField(instance.javaClass, name).get(instance)

    fun setObjectField(instance: Any, name: String, value: Any?) {
        findField(instance.javaClass, name).set(instance, value)
    }

    fun getStaticObjectField(clazz: Class<*>, name: String): Any? =
        findField(clazz, name).get(null)

    fun setStaticObjectField(clazz: Class<*>, name: String, value: Any?) {
        findField(clazz, name).set(null, value)
    }

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val constructor = clazz.declaredConstructors.firstOrNull { matches(it.parameterTypes, args) }
            ?: throw NoSuchMethodException("${clazz.name}(${args.joinToString { it?.javaClass?.simpleName ?: "null" }})")
        constructor.isAccessible = true
        return constructor.newInstance(*args)
    }

    private fun bestMatch(
        clazz: Class<*>,
        name: String,
        args: Array<out Any?>,
        static: Boolean,
    ): Method? {
        val key = MethodKey(name, static, args.map { it?.javaClass ?: NULL_TYPE })
        val byKey = methodCache.getOrPut(clazz) { ConcurrentHashMap() }
        byKey[key]?.let { return it }

        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods
                .firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) == static && matches(it.parameterTypes, args) }
                ?.let {
                    it.isAccessible = true
                    byKey[key] = it
                    return it
                }
            current = current.superclass
        }
        return null
    }

    private fun matches(parameterTypes: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (parameterTypes.size != args.size) return false
        return parameterTypes.indices.all { i ->
            val arg = args[i]
            arg == null || boxed(parameterTypes[i]).isInstance(arg)
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }

    /** 供缓存键使用的 null 参数占位类型。 */
    private val NULL_TYPE: Class<*> = Any::class.java
}
