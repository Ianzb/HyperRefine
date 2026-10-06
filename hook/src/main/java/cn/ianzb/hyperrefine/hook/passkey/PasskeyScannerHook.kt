package cn.ianzb.hyperrefine.hook.passkey

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 扫描器侧通行密钥修复（目标 `com.xiaomi.scanner`）。
 *
 * 扫码识别 `FIDO:/` 时，`MiFiDoBean.getAppPackageName()` 会返回内置 FIDO 应用包名并强制跳转，
 * 抢占系统通行密钥流程。这里让它返回空串，使跳转 Intent 变为隐式，交由系统凭据流程处理。
 */
class PasskeyScannerHook : BaseHook() {

    override val key: String = PasskeyKeys.SCANNER

    override fun init() {
        val loader = target.classLoader ?: return
        val cls = Reflect.findClassIfExists(
            "com.xiaomi.scanner.module.code.utils.bean.MiFiDoBean", loader
        ) ?: return
        val method = Reflect.findMethodIfExists(cls, "getAppPackageName") ?: return
        HookHelper.hookBefore(method) { param -> param.setResultValue("") }
    }
}
