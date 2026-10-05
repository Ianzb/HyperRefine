package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 音量条高度 / 宽度「统一调节」。
 *
 * 官方音量列的所有尺寸都来自插件内的静态工具类
 * `com.android.systemui.miui.volume.VolumeColumnRes#getWidth / getHeight`：
 * `VolumeColumn.setSize` 会用它们设置滑块、进度视图与背景的宽高。这里在方法返回后覆写为配置值，
 * 于是**多应用面板（模块自绘、内部同样是官方 VolumeColumn）与官方侧边音量条**同时生效，
 * 且只改宽高、不触碰 `getMarginRight`（音量条间距）——满足「统一调节、间距不受影响」。
 *
 * 仅覆盖「收起态 + 弹窗（侧边 / 多应用）」这一分支（`isNeedShowDialog=true && expanded=false`），
 * 官方侧边**展开面板**与控制中心二级音量条保持系统默认尺寸，避免破坏其布局。
 */
class VolumeBarSizeHook : BaseHook() {

    override val key: String = VolumeBarKeys.BAR_SIZE

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl ->
            val cls = Reflect.findClassIfExists(VolumeBarKeys.VOLUME_COLUMN_RES_CLASS, pluginCl)
            if (cls == null) {
                HookHelper.log("$tag: ${VolumeBarKeys.VOLUME_COLUMN_RES_CLASS} not found")
                return@register
            }
            hookWidth(cls)
            hookHeight(cls)
            // 本 hook 实现「音量条高度 / 宽度统一调节」，据实上报其生效状态。
            HookStatusReporter.markInstalled(VolumeBarKeys.BAR_SIZE)
            HookHelper.log("$tag: hooked VolumeColumnRes size")
        }
    }

    /** `getWidth(Context, boolean needDialog, boolean expanded, boolean)`。 */
    private fun hookWidth(cls: Class<*>) {
        cls.declaredMethods
            .filter {
                it.name == "getWidth" &&
                    it.returnType == Int::class.javaPrimitiveType &&
                    it.parameterCount == 4 &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[3] == Boolean::class.javaPrimitiveType
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        if (!targetsCollapsedSide(param.args.getOrNull(1), param.args.getOrNull(2))) {
                            return@hookAfter
                        }
                        val context = param.args.getOrNull(0) as? Context ?: return@hookAfter
                        param.setResultValue(
                            dp(context, HookPrefs.getFloat(VolumeBarKeys.BAR_WIDTH, VolumeBarKeys.DEFAULT_WIDTH)),
                        )
                    }
                }.onFailure { HookHelper.log("$tag: hook getWidth failed", it) }
            }
    }

    /** `getHeight(Context, boolean needDialog, boolean expanded)`。 */
    private fun hookHeight(cls: Class<*>) {
        cls.declaredMethods
            .filter {
                it.name == "getHeight" &&
                    it.returnType == Int::class.javaPrimitiveType &&
                    it.parameterCount == 3 &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        if (!targetsCollapsedSide(param.args.getOrNull(1), param.args.getOrNull(2))) {
                            return@hookAfter
                        }
                        val context = param.args.getOrNull(0) as? Context ?: return@hookAfter
                        param.setResultValue(
                            dp(context, HookPrefs.getFloat(VolumeBarKeys.BAR_HEIGHT, VolumeBarKeys.DEFAULT_HEIGHT)),
                        )
                    }
                }.onFailure { HookHelper.log("$tag: hook getHeight failed", it) }
            }
    }

    /**
     * 仅命中「收起态 + 弹窗」的官方音量列：
     * - `needDialog` 为 true 表示侧边音量条 / 多应用面板（控制中心二级面板为 false）；
     * - `expanded` 为 false 表示收起态（侧边展开面板为 true，保持原生尺寸）。
     */
    private fun targetsCollapsedSide(needDialog: Any?, expanded: Any?): Boolean =
        needDialog == true && expanded == false

    private fun dp(context: Context, value: Float): Int =
        (value.coerceIn(0f, 400f) * context.resources.displayMetrics.density).toInt()
}
