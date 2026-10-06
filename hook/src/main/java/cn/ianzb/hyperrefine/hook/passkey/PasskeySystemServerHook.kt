package cn.ianzb.hyperrefine.hook.passkey

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 系统服务侧通行密钥修复（目标 `system_server`）。
 *
 * 1. `IntentFactory.getOemOverrideComponentName`：当 GMS 凭据界面可用时，把 OEM 覆盖组件指向
 *    `com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity`；
 * 2. `RequestSession` 构造后把 `mHybridService` 指向 GMS 混合服务，使通行密钥可用；
 * 3. 对 `ProviderGetSession` / `ProviderCreateSession.createNewSession` 去优化，确保 hook 命中。
 */
class PasskeySystemServerHook : BaseHook() {

    override val key: String = PasskeyKeys.SYSTEM_SERVER

    override fun init() {
        val loader = target.classLoader ?: return
        runCatching { hookIntentFactory(loader) }.onFailure { HookHelper.log("$tag: IntentFactory failed", it) }
        runCatching { hookRequestSession(loader) }.onFailure { HookHelper.log("$tag: RequestSession failed", it) }
    }

    private fun hookIntentFactory(loader: ClassLoader) {
        val intentFactory = Reflect.findClassIfExists("android.credentials.selection.IntentFactory", loader) ?: return
        val builder = Reflect.findClassIfExists(
            "android.credentials.selection.IntentCreationResult\$Builder", loader
        ) ?: return
        val method = Reflect.findMethodIfExists(
            intentFactory, "getOemOverrideComponentName", Context::class.java, builder, Int::class.javaPrimitiveType!!
        ) ?: Reflect.findMethodIfExists(
            intentFactory, "getOemOverrideComponentName", Context::class.java, builder
        ) ?: return
        val success = enumConstant(
            "android.credentials.selection.IntentCreationResult\$OemUiUsageStatus", "SUCCESS", loader
        )
        HookHelper.hookBefore(method) { param ->
            val ctx = param.args.getOrNull(0) as? Context ?: return@hookBefore
            val builderObj = param.args.getOrNull(1) ?: return@hookBefore
            val component = ComponentName.unflattenFromString(PasskeyKeys.GMS_CRED_CHOOSER) ?: return@hookBefore
            if (!isOemComponentUsable(ctx, component)) return@hookBefore
            runCatching {
                Reflect.callMethod(builderObj, "setOemUiPackageName", component.packageName)
                if (success != null) Reflect.callMethod(builderObj, "setOemUiUsageStatus", success)
            }.onSuccess { param.setResultValue(component) }
        }
    }

    private fun enumConstant(className: String, name: String, loader: ClassLoader): Any? =
        Reflect.findClassIfExists(className, loader)?.enumConstants
            ?.firstOrNull { (it as? Enum<*>)?.name == name }

    private fun isOemComponentUsable(ctx: Context, component: ComponentName): Boolean {
        val pm = ctx.packageManager
        val info = runCatching {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()))
        }.getOrNull() ?: return false
        var enabled = info.enabled
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> enabled = true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> enabled = false
        }
        return enabled && info.exported
    }

    private fun hookRequestSession(loader: ClassLoader) {
        val cls = Reflect.findClassIfExists("com.android.server.credentials.RequestSession", loader) ?: return
        val field = runCatching { Reflect.findField(cls, "mHybridService") }.getOrNull() ?: return
        cls.declaredConstructors.forEach { ctor ->
            runCatching {
                HookHelper.hookAfter(ctor) { param ->
                    val self = param.thisObject ?: return@hookAfter
                    if (!runCatching { field.isAccessible = true; field.set(self, PasskeyKeys.GMS_REMOTE_SERVICE) }
                            .isSuccess
                    ) {
                        UnsafeField.setObject(field, self, PasskeyKeys.GMS_REMOTE_SERVICE)
                    }
                }
            }
        }
        listOf(
            "com.android.server.credentials.ProviderGetSession",
            "com.android.server.credentials.ProviderCreateSession",
        ).forEach { name ->
            val cls2 = Reflect.findClassIfExists(name, loader) ?: return@forEach
            cls2.declaredMethods.filter { it.name == "createNewSession" }.forEach { HookHelper.deoptimize(it) }
        }
    }
}
