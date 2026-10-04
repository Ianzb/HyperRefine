package cn.ianzb.hyperrefine.hook.connect.mirror

import android.view.View
import android.view.SurfaceView
import java.util.Collections
import java.util.WeakHashMap

/**
 * 浮窗状态登记表（弱引用），供各 hook 回调在实例间共享。
 */
internal object MirrorFloatStore {

    val floatingStates: MutableMap<View, FloatState> =
        Collections.synchronizedMap(WeakHashMap())

    val rootStates: MutableMap<View, FloatState> =
        Collections.synchronizedMap(WeakHashMap())

    val bubbleControllerStates: MutableMap<Any, FloatState> =
        Collections.synchronizedMap(WeakHashMap())

    val bubbleControllerHooked: MutableSet<Class<*>> =
        Collections.newSetFromMap(WeakHashMap())

    val surfaceRateCallbacks: MutableSet<SurfaceView> =
        Collections.newSetFromMap(WeakHashMap())

    @Volatile
    var activeFloatingState: FloatState? = null

    /** 折叠 / 恢复动画进行中的状态（原生气泡回调期间使用）。 */
    fun pendingCollapseState(): FloatState? =
        activeFloatingState?.takeIf { it.pendingCollapse }

    fun stateForBubbleController(obj: Any?): FloatState? {
        val state = activeFloatingState ?: return null
        val controller = state.bubbleController ?: return null
        return if (obj != null && controller.javaClass == obj.javaClass) state else null
    }
}
