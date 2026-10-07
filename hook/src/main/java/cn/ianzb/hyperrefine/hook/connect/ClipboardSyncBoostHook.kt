package cn.ianzb.hyperrefine.hook.connect

import android.os.Message
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 跨设备剪贴板同步加速。
 *
 * 目标进程：`com.milink.service` 中加载了跨设备剪贴板实现的进程。
 *
 * 跨设备剪贴板由 `com.xiaomi.dist.universalclipboardservice` 实现，从「本机剪贴板变化 → 发布 →
 * 对方接收 → 写入本机剪贴板」链路里散落着若干固定短延迟，例如发布前的 100ms 去抖、
 * 对方剪贴板服务上线后 100ms 才启动服务、客户端会话处理的数百毫秒延迟等。
 *
 * 本 Hook 统一把该实现内部 **≤ [MAX_BOOST_DELAY_MS] 的短延迟压缩为 0**（保留 2/3 分钟级别的
 * 清理与有效期定时，不改），让识别、发布、接收、写入各环节都更快。
 */
class ClipboardSyncBoostHook : BaseHook() {

    override val key: String = ConnectKeys.CLIPBOARD_SYNC_BOOST

    override fun init() {
        val loader = target.classLoader ?: return
        val handlerCl = Reflect.findClassIfExists("android.os.Handler", loader) ?: return
        val method = Reflect.findMethodIfExists(
            handlerCl,
            "sendMessageDelayed",
            Message::class.java,
            Long::class.javaPrimitiveType!!,
        ) ?: run {
            HookHelper.log("$tag: Handler.sendMessageDelayed not found")
            return
        }
        HookHelper.hookBefore(method) { param ->
            if (!HookPrefs.getBoolean(ConnectKeys.CLIPBOARD_SYNC_BOOST, false)) return@hookBefore
            val delay = param.args.getOrNull(1) as? Long ?: return@hookBefore
            if (delay <= 0L || delay > MAX_BOOST_DELAY_MS) return@hookBefore
            if (isClipboardTarget(param.thisObject, param.args.getOrNull(0))) {
                param.setArg(1, 0L)
            }
        }
    }

    /** 仅调整跨设备剪贴板实现内部投递的延迟，避免影响进程内其它 Handler。 */
    private fun isClipboardTarget(handler: Any?, message: Any?): Boolean {
        if (handler?.javaClass?.name?.startsWith(PACKAGE) == true) return true
        val msg = message as? Message ?: return false
        return msg.callback?.javaClass?.name?.startsWith(PACKAGE) == true
    }

    private companion object {
        const val PACKAGE = "com.xiaomi.dist.universalclipboardservice"

        /** 只压缩该量级以内的短延迟；2/3 分钟的清理与有效期定时保持不变。 */
        const val MAX_BOOST_DELAY_MS = 2000L
    }
}
