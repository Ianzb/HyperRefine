package cn.ianzb.hyperrefine.hook.securitycenter

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import java.lang.reflect.Method

/**
 * 快充加速通知隐藏基类。
 *
 * 90W 及以上快充机型在快充加速过程中，安全服务（`com.miui.securitycenter`）会在
 * `com.miui.powercenter.high`（通知类别「省电与电池重要通知」）上发布两条通知：
 * - 进入提醒（标题 `pc_fast_charge_notifi_title`、动作「立即使用」）；
 * - 退出提醒（标题 `pc_fast_charge_notifi_title_on`、动作「退出」）。
 *
 * 二者由同一个混淆类中的两个静态方法构建并发送，通知 id 均为 `0x7876cce2`。
 * 手机与平板功能一致、仅混淆类名不同（如 `ch.d` / `yg.d`），故统一用 DexKit
 * 按字符串特征定位，不写死类名：以 [CLASS_MARKER] 定位通知类，再在类内以子类提供的
 * [marker] 唯一定位各自的发送方法。两个通知各有独立开关与状态。
 */
abstract class BaseFastChargeNotifyHook : BaseHook() {

    override fun useDexKit(): Boolean = true

    /** 通知发送方法内引用的特征串（在通知类内唯一）。 */
    protected abstract val marker: String

    private var notifyMethod: Method? = null

    override fun initDexKit(): Boolean {
        notifyMethod = optionalMemberList<Method>("notify_method") { bridge ->
            val classData = bridge.findClass {
                matcher { usingStrings(CLASS_MARKER) }
            }.firstOrNull() ?: return@optionalMemberList emptyList<Any>()
            classData.findMethod {
                matcher { usingStrings(marker) }
            }
        }.firstOrNull()
        return notifyMethod != null
    }

    override fun init() {
        val method = notifyMethod ?: return
        runCatching {
            HookHelper.hookBefore(method) { param ->
                if (HookPrefs.getBoolean(key, false)) param.setResultValue(null)
            }
        }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
    }

    companion object {
        /** 仅「退出快充加速」通知使用的日志串，用于唯一定位通知类。 */
        private const val CLASS_MARKER = "showExitFastChargeNotification"
    }
}

/** 进入快充加速提醒：接入充电器且电量低于设定值时的进入提醒。 */
class FastChargeEnterNotifyHook : BaseFastChargeNotifyHook() {

    override val key: String = KEY

    override val marker: String = "fast_charge_enter_notification"

    companion object {
        const val KEY = "security_center_fast_charge_enter_notify"
    }
}

/** 退出快充加速提醒：快充加速过程中的退出提醒。 */
class FastChargeExitNotifyHook : BaseFastChargeNotifyHook() {

    override val key: String = KEY

    override val marker: String = "fast_charge_exit_notification"

    companion object {
        const val KEY = "security_center_fast_charge_exit_notify"
    }
}
