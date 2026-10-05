package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassApi
import cn.ianzb.hyperrefine.hook.systemui.glass.VolumeColumnGlass
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 官方「竖向音量条」列的创建 / 驱动（反射系统界面控制中心插件的
 * `com.android.systemui.miui.volume.VolumeColumn`）。
 *
 * 思路参考 SoundMan（GPL-3.0，系统界面多应用音量面板），按 HyperOS 4 插件类签名自主实现：
 * 独立列只需给出 `initColumn` 所需的临时 parent 与一个不与系统音频流冲突的「假流」id，
 * 之后手动驱动官方滑条（`toProgressWithAnim` / `updateSliderRatio`），不接入官方流控制器。
 */
object OfficialVolumeColumnFactory {

    /** 控制中心插件内的竖向列类。 */
    const val CLASS_NAME = "com.android.systemui.miui.volume.VolumeColumn"

    /** 官方滑条以 1000 为满值（百分比 0..100 → 0..1000）。 */
    private const val SLIDER_MAX = 1000

    /** 多应用音量滑条标记，用于让入口 hook 的边缘动画镜像跳过本面板的滑条。 */
    const val SLIDER_TAG = "hyperrefine_multi_app_slider"

    private const val FIRST_FAKE_STREAM = 10_000
    private const val LAST_FAKE_STREAM = 999_999

    /** 分配不会与系统音频流常量冲突的稳定假流 id。 */
    fun allocateFakeStreams(packageNames: List<String>): Map<String, Int> {
        val used = HashSet<Int>()
        return packageNames.distinct().sorted().associateWith { pkg ->
            var candidate = FIRST_FAKE_STREAM + (pkg.hashCode() and Int.MAX_VALUE) %
                (LAST_FAKE_STREAM - FIRST_FAKE_STREAM + 1)
            while (!used.add(candidate)) {
                candidate = if (candidate == LAST_FAKE_STREAM) FIRST_FAKE_STREAM else candidate + 1
            }
            candidate
        }
    }

    /** 探测插件列类是否可用。 */
    fun isAvailable(pluginClassLoader: ClassLoader?): Boolean =
        pluginClassLoader != null && Reflect.findClassIfExists(CLASS_NAME, pluginClassLoader) != null

    /** 已创建的官方列句柄。 */
    class Column(
        private val instance: Any,
        val view: View,
        val slider: SeekBar,
        private val progressView: View,
        private val releaseMethod: java.lang.reflect.Method,
        private val updateProgressMethod: java.lang.reflect.Method,
    ) {
        /** 释放官方列资源。 */
        fun release() {
            runCatching { releaseMethod.invoke(instance) }
                .onFailure { HookHelper.log("OfficialVolumeColumn: release failed", it) }
        }

        /** 原地更新进度（不重建列）。 */
        fun updateProgress(percent: Int) {
            slider.progress = (percent.coerceIn(0, 100)) * (SLIDER_MAX / 100)
            runCatching { updateProgressMethod.invoke(progressView, false, slider) }
        }
    }

    /**
     * 创建一根官方竖向列。
     *
     * @param onPercent 滑条进度变化（含程序更新）时回调 0..100。
     * @param onCommit 用户拖动时回调 0..100（用于转发音量）。
     */
    fun create(
        context: Context,
        pluginClassLoader: ClassLoader,
        fakeStream: Int,
        initialPercent: Int,
        barRadiusPx: Int = 0,
        glass: Boolean = false,
        onPercent: (Int) -> Unit,
        onCommit: (Int) -> Unit,
    ): Column? {
        val columnClass = Reflect.findClassIfExists(CLASS_NAME, pluginClassLoader) ?: return null
        val booleanType = Boolean::class.javaPrimitiveType!!
        val intType = Int::class.javaPrimitiveType!!

        // initColumn 需要一个 parent 完成挂载；用临时容器，随后把列视图迁到我们自己的布局。
        val tempParent = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }

        val instance = runCatching { columnClass.getConstructor().newInstance() }
            .onFailure { HookHelper.log("OfficialVolumeColumn: newInstance failed", it) }
            .getOrNull() ?: return null

        runCatching {
            // 第 5 个布尔 = `isNeedShowDialog`：与侧边音量条一致传 true，填充 / 尺寸 / 材质
            // 完全走官方音量条配置（材质层由官方方法应用，外部材质 hook 可一并生效）。
            columnClass.getMethod(
                "initColumn",
                Context::class.java,
                ViewGroup::class.java,
                intType,
                booleanType,
                booleanType,
                booleanType,
            ).invoke(instance, context, tempParent, fakeStream, true, true, false)
        }.onFailure {
            HookHelper.log("OfficialVolumeColumn: initColumn failed", it)
            return null
        }

        // 与侧边**一级**音量条一致：用收起态样式（展开态会给滑条套 flat 深色，导致面板条偏暗）。
        runCatching {
            columnClass.getMethod("setExpanded", booleanType).invoke(instance, false)
            columnClass.getMethod("setSliderResource", booleanType).invoke(instance, false)
            columnClass.getMethod("setSliderTintColorList", booleanType).invoke(instance, false)
            columnClass.getMethod("setSize", booleanType, booleanType).invoke(instance, false, false)
            columnClass.getMethod("setSliderBlendColor", booleanType).invoke(instance, false)
        }.onFailure { HookHelper.log("OfficialVolumeColumn: style setup failed", it) }

        val view = runCatching { columnClass.getMethod("getView").invoke(instance) as? View }.getOrNull()
            ?: return null
        val slider = runCatching { columnClass.getMethod("getSlider").invoke(instance) as? SeekBar }.getOrNull()
            ?: return null
        // 标记本面板滑条：入口 hook 的边缘动画镜像会跳过它，避免带着入口 / 勿扰按钮一起动。
        slider.tag = SLIDER_TAG
        val progressView = runCatching { columnClass.getMethod("getProgressView").invoke(instance) as? View }
            .getOrNull() ?: return null

        // 关闭列根背景模糊，并隐藏官方流类型图标（由模块叠加应用图标）。
        runCatching {
            view.javaClass.methods.firstOrNull { it.name == "setBlurEnabled" && it.parameterCount == 1 }
                ?.invoke(view, false)
        }.onFailure { HookHelper.log("OfficialVolumeColumn: setBlurEnabled failed", it) }
        runCatching {
            val icon = columnClass.getMethod("getIcon").invoke(instance) as? android.widget.ImageView
            icon?.apply {
                setImageDrawable(null)
                imageTintList = null
                background = null
                visibility = View.INVISIBLE
            }
        }.onFailure { HookHelper.log("OfficialVolumeColumn: hide icon failed", it) }

        (view.parent as? ViewGroup)?.removeView(view)

        // 多应用面板音量列的玻璃：与侧边一级音量条使用同一套模块玻璃。
        if (glass) applyModuleGlass(view, pluginClassLoader)

        val setTracking = runCatching { columnClass.getMethod("setTracking", booleanType) }.getOrNull()
        val updateSliderRatio = runCatching { columnClass.getMethod("updateSliderRatio") }.getOrNull()
        val setMaxLevel = runCatching { progressView.javaClass.getMethod("setMaxLevel", intType) }.getOrNull()
        val toProgressWithAnim = runCatching {
            progressView.javaClass.getMethod("toProgressWithAnim", booleanType, SeekBar::class.java)
        }.getOrNull() ?: return null

        // 模块自定义：音量条圆角（官方列自身半径）。
        if (barRadiusPx > 0) {
            runCatching { columnClass.getMethod("setRadius", intType).invoke(instance, barRadiusPx) }
                .onFailure { HookHelper.log("OfficialVolumeColumn: setRadius failed", it) }
        }

        slider.max = SLIDER_MAX
        slider.progress = initialPercent.coerceIn(0, 100) * (SLIDER_MAX / 100)
        setMaxLevel?.invoke(progressView, 100)
        runCatching { toProgressWithAnim.invoke(progressView, false, slider) }
        updateSliderRatio?.let { runCatching { it.invoke(instance) } }

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                runCatching { toProgressWithAnim.invoke(progressView, fromUser, seekBar) }
                    .onFailure { HookHelper.log("OfficialVolumeColumn: progress anim failed", it) }
                val percent = (progress * 100f / SLIDER_MAX).toInt().coerceIn(0, 100)
                onPercent(percent)
                if (fromUser) onCommit(percent)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                setTracking?.let { runCatching { it.invoke(instance, true) } }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                setTracking?.let { runCatching { it.invoke(instance, false) } }
            }
        })

        installAnimListener(slider, view, pluginClassLoader)

        return Column(instance, view, slider, progressView, columnClass.getMethod("release"), toProgressWithAnim)
    }

    /**
     * 给官方滑条安装 `SeekBarAnimListener`，把官方拖动动画（缩放 / 位移）映射到列视图。
     * 否则官方 `dispatchTouchEvent` 每帧空转，拖动会严重卡顿。
     */
    private fun installAnimListener(slider: SeekBar, columnView: View, classLoader: ClassLoader) {
        runCatching {
            val setter = slider.javaClass.methods.firstOrNull {
                it.name == "setSeekBarAnimListener" && it.parameterCount == 1
            } ?: return@runCatching
            val listenerType = setter.parameterTypes.single()
            if (!listenerType.isInterface) return@runCatching
            val listener = java.lang.reflect.Proxy.newProxyInstance(classLoader, arrayOf(listenerType)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "HyperRefineColumnAnimListener"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.singleOrNull()
                    "getHeightArray" -> intArrayOf(0, 0, 0, columnView.height)
                    "resetView" -> {
                        columnView.scaleX = 1f
                        columnView.scaleY = 1f
                        columnView.translationY = 0f
                        null
                    }
                    "setScale" -> {
                        val before = args?.getOrNull(0) as? Float
                        val after = args?.getOrNull(1) as? Float
                        if (before != null && after != null) {
                            columnView.scaleX += after - before
                            columnView.scaleY += after - before
                        }
                        null
                    }
                    "setVolY" -> {
                        val before = args?.getOrNull(0) as? Float
                        val after = args?.getOrNull(1) as? Float
                        if (before != null && after != null) columnView.translationY += after - before
                        null
                    }
                    "setRingerY", "setDndY", "setSuperVolumeY" -> null
                    else -> defaultValue(method.returnType)
                }
            }
            setter.invoke(slider, listener)
        }.onFailure { HookHelper.log("OfficialVolumeColumn: anim listener failed", it) }
    }

    /**
     * 多应用面板音量列的玻璃：与侧边一级音量条使用**同一套**模块玻璃（[VolumeColumnGlass]）——列根套内容材质、
     * 清掉各层深色兜底、滑条套 SDF 玻璃。这样面板条与侧边一级条一致。
     */
    private fun applyModuleGlass(view: View, classLoader: ClassLoader) {
        runCatching {
            CcGlassApi.init(classLoader)
            // 尺寸为 0 时会按 0 尺寸算出无效材质，等布局完成后再套。
            if (view.width == 0 || view.height == 0) {
                view.post { if (view.isAttachedToWindow) applyModuleGlass(view, classLoader) }
                return
            }
            VolumeColumnGlass.apply(
                view,
                styleRoot = { CcGlassApi.apply(it, VolumeColumnGlass.COLUMN_TOKEN) },
                styleSlider = {
                    CcGlassApi.applyStyle(it, CcGlassApi.bionics(VolumeColumnGlass.SLIDER_TOKEN))
                },
            )
            // 回放其它模块（如 HyperLight 柔光玻璃）对侧边一级列实际套用的框架玻璃参数（按子视图路径）。
            runCatching { SideGlassStore.applyTo(view) }
        }.onFailure { HookHelper.log("OfficialVolumeColumn: applyModuleGlass failed", it) }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        Character.TYPE -> '\u0000'
        else -> null
    }
}
