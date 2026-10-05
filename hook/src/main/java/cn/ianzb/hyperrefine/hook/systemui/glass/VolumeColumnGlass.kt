package cn.ianzb.hyperrefine.hook.systemui.glass

import android.view.View
import android.view.ViewGroup

/**
 * 官方竖向音量列（`VolumeColumn`）的玻璃 / 深色兜底处理。
 *
 * 官方侧边音量条（[CcGlassHook]）与模块自绘的多应用音量面板（`OfficialVolumeColumnFactory`）
 * 使用的都是官方 `VolumeColumn`，这里抽出一套**完全一致**的处理，避免两处参数漂移：
 * 1. 列根套材质 token（`DefaultContentBgMaterialToken`）；
 * 2. 清除各层深色实心兜底背景（`volume_column_view` / `volume_column_slider_bg_blend`）；
 * 3. 对滑条 `volume_column_slider` 清背景并套 SDF 玻璃（`DEFAULT_GLASS_TOKEN`）。
 *
 * 侧边音量条路径需要按状态签名缓存（高频 `updateVolumeColumnSliderH`），因此把套用动作以
 * [styleRoot] / [styleSlider] 回调注入，缓存由调用方决定。
 */
object VolumeColumnGlass {

    /** 列根材质 token（与侧边音量列一致）。 */
    const val COLUMN_TOKEN = "DefaultContentBgMaterialToken"

    /** 滑条底层 SDF 玻璃 token（与侧边音量列一致）。 */
    const val SLIDER_TOKEN = "DEFAULT_GLASS_TOKEN"

    private const val ID_VIEW = "volume_column_view"
    private const val ID_SLIDER_BG_BLEND = "volume_column_slider_bg_blend"
    private const val ID_SLIDER = "volume_column_slider"

    /**
     * 对一根音量列套玻璃。
     *
     * @param styleRoot 列根材质套用（侧边音量条路径带状态缓存）。
     * @param styleSlider 滑条 SDF 玻璃套用（侧边音量条路径带状态缓存）。
     */
    fun apply(columnView: View, styleRoot: (View) -> Unit, styleSlider: (View) -> Unit) {
        styleRoot(columnView)
        traverse(columnView) { v ->
            when (idName(v)) {
                ID_VIEW, ID_SLIDER_BG_BLEND -> v.background = null
                ID_SLIDER -> {
                    v.background = null
                    styleSlider(v)
                }
            }
        }
    }

    /** 只清除各层不透明深色兜底（玻璃由镜像 / 模块材质负责）。 */
    fun clearDarkBackgrounds(columnView: View) {
        traverse(columnView) { v ->
            when (idName(v)) {
                ID_VIEW, ID_SLIDER_BG_BLEND -> v.background = null
            }
        }
    }

    private fun traverse(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) traverse(view.getChildAt(i), action)
        }
    }

    private fun idName(v: View): String? =
        runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
}
