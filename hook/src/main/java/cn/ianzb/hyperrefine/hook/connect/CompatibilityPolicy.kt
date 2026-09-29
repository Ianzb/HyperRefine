package cn.ianzb.hyperrefine.hook.connect

import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * 设备互联功能的纯兼容性判定，与 ROM 反射、libxposed 管道解耦。
 *
 * 移植自 HyperConnectToolkit（Apache-2.0，见应用内「第三方许可」页）。
 */
object CompatibilityPolicy {

    /** 系统 Mirror Provider 中触发 PIN_APP 流转的方法名。 */
    const val PIN_APP_PROVIDER_METHOD = "performPinIconClick"

    /** PIN_APP 流转的启动原因码。 */
    const val PIN_APP_START_REASON = 9

    fun isValidNotificationClick(deviceId: String?, packageName: String?): Boolean =
        !deviceId.isNullOrEmpty() && !packageName.isNullOrEmpty()

    /**
     * 仅把平板 sink Activity 的 `SENSOR_LANDSCAPE` 放开为 `FULL_SENSOR`，其余保持原样。
     */
    fun resolveRequestedOrientation(isTabletSink: Boolean, requestedOrientation: Int): Int =
        if (isTabletSink && requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else {
            requestedOrientation
        }

    /**
     * 竖屏下 reason-9 首包被 ROM 以横屏尺寸发送时，判定需要换回竖屏宽高。
     */
    fun shouldRestorePortraitInitialBounds(
        currentOrientation: Int,
        screenFrom: Int,
        width: Int,
        height: Int,
    ): Boolean =
        currentOrientation == Configuration.ORIENTATION_PORTRAIT &&
            screenFrom == PIN_APP_START_REASON &&
            width > 0 &&
            height > 0 &&
            width > height
}
