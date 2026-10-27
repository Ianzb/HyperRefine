package cn.ianzb.hyperrefine.hook.voiceassist

import android.os.Looper
import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 让超级小爱输入法在所有应用中启用「顶部圆角 + 高级材质（背景模糊）」。
 *
 * 官方 `jb.f0` 把材质判断拆成两层：包名允许表决定哪些应用启用，`h()` / `g()` 再决定
 * 是否创建透明材质层、`e0` / `u1` 是否取顶部圆角。这里不重绘输入法，只复用官方状态：
 *
 * 1. 让 `h()` / `g()` 返回 true（字段解析失败时仍能兜底）；
 * 2. `k()` 前把当前包名加入允许表、从黑名单移除；
 * 3. `k()` 后刷新圆角 Outline；官方 `f()` 会完成材质层与模糊。
 *
 * 字段名以 dex 为准，不能使用 JADX 反混淆后的 `f102xx` 名称：
 * `w` 当前包名、`x` 允许表、`y` 黑名单、`z` 圆角状态、`f` 材质状态、
 * `v` 平台模糊能力、`j` 官方材质 View。
 *
 * 关闭开关时完全不干预，保持官方行为。
 */
class VoiceAssistImeMaterialHook : BaseHook() {

    override val key: String = VoiceAssistImeKeys.GLOBAL_MATERIAL

    private val failureLogged = AtomicBoolean(false)

    @Volatile
    private var lastForcedPackage: String? = null

    override fun init() {
        HookHelper.log("$tag: init, pref=${isEnabled()}, pkg=${target.packageName}")
        val helperClass = Reflect.findClassIfExists(HELPER_CLASS, target.classLoader)
        if (helperClass == null) {
            HookHelper.log("$tag: $HELPER_CLASS not found")
            return
        }

        val members = HelperMembers(helperClass)
        installPredicateHooks(members)

        val refs = runCatching { HelperRefs(helperClass, members.capability) }
            .onFailure { HookHelper.log("$tag: resolve helper fields failed", it) }
            .getOrNull()
        if (refs == null || members.reapply == null) {
            HookHelper.log(
                "$tag: partial install @ ${target.packageName} " +
                    "(h=${members.materialActive != null}, " +
                    "g=${members.roundedActive != null}, " +
                    "fields=${refs != null}, k=${members.reapply != null})"
            )
            return
        }

        installPreferenceHook()
        installReapplyHooks(members.reapply, refs)
        installOpacityHook()

        HookHelper.log(
            "$tag: installed @ ${target.packageName} " +
                "(h=${members.materialActive != null}, " +
                "g=${members.roundedActive != null}, fields=true)"
        )
    }

    /**
     * 先安装两条状态谓词 Hook。即使后续字段解析失败，官方渲染器仍能按
     * “材质已启用 + 有圆角”工作；`v` 为 false 时不强行启用，避免没有真实模糊的透明层。
     */
    private fun installPredicateHooks(members: HelperMembers) {
        members.materialActive?.let { method ->
            HookHelper.hookBefore(method) { param ->
                if (!isEnabled()) return@hookBefore
                val helper = param.thisObject ?: return@hookBefore
                val capable = runCatching {
                    members.capability?.getBoolean(helper) == true
                }.getOrDefault(false)
                if (capable) param.setResultValue(true)
            }
        }
        members.roundedActive?.let { method ->
            HookHelper.hookBefore(method) { param ->
                if (isEnabled()) param.setResultValue(true)
            }
        }
    }

    /** 覆盖输入法设置里的「高级材质」总开关；只拦截这一个 key。 */
    private fun installPreferenceHook() {
        val getter = Reflect.findClassIfExists(PREF_CLASS, target.classLoader)
            ?.declaredMethods
            ?.firstOrNull {
                it.name == PREF_GETTER &&
                    it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            ?.apply { isAccessible = true }
        if (getter == null) {
            HookHelper.log("$tag: $PREF_CLASS.$PREF_GETTER not found; master switch not overridden")
            return
        }
        HookHelper.hookBefore(getter) { param ->
            if (param.args.getOrNull(0) == MATERIAL_PREF_KEY && isEnabled()) {
                param.setResultValue(true)
            }
        }
    }

    /**
     * 高级材质的不透明度由 MIUI 混色列表里每个颜色的 alpha 决定。
     * `mf.j.m(View, ArrayList<Point>)` 只服务输入法材质层，按比例缩放 alpha 即可，
     * 不改模糊半径、材质类型或输入法内容。
     */
    private fun installOpacityHook() {
        val clazz = Reflect.findClassIfExists(BLEND_CLASS, target.classLoader)
        if (clazz == null) {
            HookHelper.log("$tag: $BLEND_CLASS not found; opacity unavailable")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == BLEND_METHOD &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == View::class.java &&
                it.parameterTypes[1] == ArrayList::class.java
        }?.apply { isAccessible = true }
        if (method == null) {
            HookHelper.log("$tag: $BLEND_CLASS.$BLEND_METHOD not found; opacity unavailable")
            return
        }
        HookHelper.hookBefore(method) { param ->
            if (!isEnabled()) return@hookBefore
            val percent = HookPrefs.getFloat(
                VoiceAssistImeKeys.MATERIAL_OPACITY,
                VoiceAssistImeKeys.DEFAULT_MATERIAL_OPACITY,
            ).coerceIn(0f, 100f)
            if (percent >= 100f) return@hookBefore
            val colors = param.args.getOrNull(1) as? ArrayList<*> ?: return@hookBefore
            val ratio = percent / 100f
            val scaled = ArrayList<Any?>(colors.size)
            colors.forEach { item ->
                val point = item as? android.graphics.Point
                if (point == null) {
                    scaled.add(item)
                } else {
                    val alpha = ((point.x ushr 24) and 0xFF) * ratio
                    val color = (point.x and 0x00FFFFFF) or
                        (alpha.toInt().coerceIn(0, 255) shl 24)
                    scaled.add(android.graphics.Point(color, point.y))
                }
            }
            param.setArg(1, scaled)
        }
        HookHelper.log("$tag: material opacity installed")
    }

    private fun installReapplyHooks(method: Method, refs: HelperRefs) {
        HookHelper.hookBefore(method) { param ->
            if (!isEnabled()) return@hookBefore
            val helper = param.thisObject ?: return@hookBefore
            runCatching { refs.forceMaterial(helper) }
                .onFailure { logFailure("force material failed", it) }
        }

        HookHelper.hookAfter(method) { param ->
            if (!isEnabled()) return@hookAfter
            val helper = param.thisObject ?: return@hookAfter
            val forced = runCatching { refs.refreshRoundedOutline(helper) }
                .onFailure { logFailure("refresh rounded outline failed", it) }
                .getOrDefault(false)
            if (!forced) return@hookAfter

            val pkg = refs.packageName(helper)
            if (pkg != null && pkg != lastForcedPackage) {
                lastForcedPackage = pkg
                HookHelper.log("$tag: material forced for $pkg")
            }
        }
    }

    private fun isEnabled(): Boolean = HookPrefs.getBoolean(key, false)

    private fun logFailure(message: String, throwable: Throwable) {
        if (failureLogged.compareAndSet(false, true)) {
            HookHelper.log("$tag: $message", throwable)
        }
    }

    /** 目标类中真正参与材质判定的方法/字段，集中解析并保留缺失状态。 */
    private class HelperMembers(clazz: Class<*>) {
        val roundedActive: Method? = method(clazz, METHOD_ROUNDED, Boolean::class.javaPrimitiveType)
        val materialActive: Method? = method(clazz, METHOD_MATERIAL, Boolean::class.javaPrimitiveType)
        val reapply: Method? = method(clazz, METHOD_REAPPLY)
        val capability: Field? = fieldOrNull(clazz, FIELD_CAPABILITY)
    }

    private class HelperRefs(
        clazz: Class<*>,
        capability: Field?,
    ) {
        private val currentPackage: Field = field(clazz, FIELD_CURRENT_PACKAGE)
        private val allowedPackages: Field = field(clazz, FIELD_ALLOWED_PACKAGES)
        private val blacklistPackages: Field = field(clazz, FIELD_BLACKLIST_PACKAGES)
        private val materialActive: Field = field(clazz, FIELD_MATERIAL_ACTIVE)
        private val roundedActive: Field = field(clazz, FIELD_ROUNDED_ACTIVE)
        private val materialView: Field = field(clazz, FIELD_MATERIAL_VIEW)
        private val materialCapability: Field? = capability

        private val setStateValue: Method =
            materialActive.type.getMethod("setValue", Any::class.java).apply { isAccessible = true }

        fun forceMaterial(helper: Any) {
            if (!isBlurCapable(helper)) return
            val pkg = packageName(helper) ?: return

            val allowed = LinkedHashMap<Any?, Any?>()
            (allowedPackages.get(helper) as? Map<*, *>)?.forEach { (key, value) ->
                allowed[key] = value
            }
            allowed[pkg] = 1
            allowedPackages.set(helper, allowed)

            val blacklist = LinkedHashMap<Any?, Any?>()
            (blacklistPackages.get(helper) as? Map<*, *>)?.forEach { (key, value) ->
                blacklist[key] = value
            }
            blacklist.remove(pkg)
            blacklistPackages.set(helper, blacklist)
        }

        fun refreshRoundedOutline(helper: Any): Boolean {
            if (!isBlurCapable(helper)) return false
            setStateValue.invoke(roundedActive.get(helper), true)
            setStateValue.invoke(materialActive.get(helper), true)

            val view = materialView.get(helper) as? View ?: return true
            val invalidate = Runnable { runCatching { view.invalidateOutline() } }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                invalidate.run()
            } else {
                view.post(invalidate)
            }
            return true
        }

        fun packageName(helper: Any): String? =
            (currentPackage.get(helper) as? String)?.takeIf { it.isNotEmpty() }

        private fun isBlurCapable(helper: Any): Boolean =
            materialCapability?.getBoolean(helper) == true
    }

    private companion object {
        const val HELPER_CLASS = "jb.f0"

        const val METHOD_ROUNDED = "g"
        const val METHOD_MATERIAL = "h"
        const val METHOD_REAPPLY = "k"

        const val FIELD_CURRENT_PACKAGE = "w"
        const val FIELD_ALLOWED_PACKAGES = "x"
        const val FIELD_BLACKLIST_PACKAGES = "y"
        const val FIELD_CAPABILITY = "v"
        const val FIELD_MATERIAL_ACTIVE = "f"
        const val FIELD_ROUNDED_ACTIVE = "z"
        const val FIELD_MATERIAL_VIEW = "j"

        const val PREF_CLASS = "o9.e"
        const val PREF_GETTER = "a"
        const val MATERIAL_PREF_KEY = "hyper_material_enabled"

        const val BLEND_CLASS = "mf.j"
        const val BLEND_METHOD = "m"

        fun method(clazz: Class<*>, name: String, returnType: Class<*>? = null): Method? =
            clazz.declaredMethods.firstOrNull {
                it.name == name &&
                    it.parameterCount == 0 &&
                    (returnType == null || it.returnType == returnType)
            }?.apply { isAccessible = true }

        fun fieldOrNull(clazz: Class<*>, name: String): Field? =
            runCatching { field(clazz, name) }.getOrNull()

        fun field(clazz: Class<*>, name: String): Field =
            clazz.getDeclaredField(name).apply { isAccessible = true }
    }
}
