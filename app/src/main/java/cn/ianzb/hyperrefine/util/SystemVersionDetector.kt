package cn.ianzb.hyperrefine.util

import android.annotation.SuppressLint
import android.os.Build

/**
 * 系统版本检测工具（与 HyperNavBar 保持一致）。
 */
object SystemVersionDetector {

    /** 设备市场名称，如 `REDMI K90 Pro Max`；取不到时回退 [Build.MODEL]。 */
    fun getMarketName(): String =
        getProp("ro.product.marketname").ifEmpty { Build.MODEL }

    /** HyperOS / MIUI 版本增量号。 */
    fun getHyperOsVersion(): String =
        getProp("ro.mi.os.version.incremental").ifEmpty { getProp("ro.system.build.version.incremental") }

    /** Android 版本描述，如 `Android 15 (SDK 35)`。 */
    fun getAndroidVersion(): String =
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    @SuppressLint("PrivateApi")
    private fun getProp(property: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("get", String::class.java)
        method.invoke(null, property) as? String ?: ""
    } catch (_: Exception) {
        ""
    }
}
