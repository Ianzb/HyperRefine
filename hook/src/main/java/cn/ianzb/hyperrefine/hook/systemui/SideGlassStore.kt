package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import android.view.ViewGroup

/**
 * 记录其它模块（如 HyperLight）对官方侧边一级音量列实际调用的框架玻璃方法（方法名 + 参数 + 子视图路径），
 * 供多应用面板音量列在创建时按同一路径回放，使两者玻璃参数一致。
 */
object SideGlassStore {

    private class Call(val name: String, val path: List<Int>, val args: Array<Any?>)

    @Volatile
    private var calls: List<Call> = emptyList()

    /** 记录 / 覆盖某路径上的某方法调用。 */
    fun record(path: List<Int>, name: String, args: Array<Any?>) {
        val kept = calls.filterNot { it.path == path && it.name == name }
        calls = kept + Call(name, path, args.copyOf())
    }

    /** 把已记录的所有调用按路径回放到一根列视图上。 */
    fun applyTo(columnView: View) {
        val snapshot = calls
        if (snapshot.isEmpty()) return
        snapshot.forEach { call ->
            val target = viewAtPath(columnView, call.path) ?: return@forEach
            runCatching { invoke(target, call.name, call.args) }
        }
    }

    private fun invoke(target: View, name: String, args: Array<Any?>) {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && paramsMatch(it.parameterTypes, args)
        } ?: return
        method.isAccessible = true
        method.invoke(target, *args)
    }

    private fun paramsMatch(types: Array<Class<*>>, args: Array<Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { i ->
            val a = args[i] ?: return@all !types[i].isPrimitive
            types[i].isInstance(a) ||
                (types[i] == Int::class.javaPrimitiveType && a is Int) ||
                (types[i] == Float::class.javaPrimitiveType && a is Float)
        }
    }

    private fun viewAtPath(root: View, path: List<Int>): View? {
        var current: View = root
        for (index in path) {
            val group = current as? ViewGroup ?: return null
            if (index < 0 || index >= group.childCount) return null
            current = group.getChildAt(index)
        }
        return current
    }
}
