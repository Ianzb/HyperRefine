package cn.ianzb.hyperrefine.hook.passkey

import android.credentials.CredentialManager
import android.view.View
import android.widget.CompoundButton
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 设置侧通行密钥修复（目标 `com.android.settings`）。
 *
 * CN 版以 `miui.os.Build.IS_INTERNATIONAL_BUILD` 过滤掉 Google 凭据提供方。这里在相关方法执行期间
 * 临时把它置为国际版，使「通行密钥 / 凭据」设置项显示并可选择 GMS 提供方：
 * - `DefaultCombinedPreferenceController.getCombinedProviderInfos` / `updateState`
 * - `DefaultCombinedPicker.setDefaultKey` / `getDefaultKey`
 * - `CombinedProviderInfo.launchSettingsActivityIntent`
 *
 * 另对 Android 17（`CINNAMON_BUN`）的 `CredentialManagerPreferenceController$CombiPreference`：
 * 当 MIUI 未把开关绑定到 `mSwitch` 时，手动接管开关键，使开关可切换。
 */
class PasskeySettingsHook : BaseHook() {

    override val key: String = PasskeyKeys.SETTINGS

    override fun init() {
        val loader = target.classLoader ?: return
        val buildClass = Reflect.findClassIfExists("miui.os.Build", loader) ?: return
        val flagField = runCatching { Reflect.findField(buildClass, "IS_INTERNATIONAL_BUILD") }.getOrNull()
            ?: return
        val flag = MiuiInternationalFlag(flagField)

        hookWrapped(loader, flag)
        runCatching { hookCombiPreference(loader) }
            .onFailure { HookHelper.log("$tag: CombiPreference failed", it) }
    }

    private fun hookWrapped(loader: ClassLoader, flag: MiuiInternationalFlag) {
        val controller = Reflect.findClassIfExists(
            "com.android.settings.applications.credentials.DefaultCombinedPreferenceController", loader
        )
        controller?.let {
            Reflect.findMethodIfExists(
                it, "getCombinedProviderInfos", CredentialManager::class.java, Int::class.javaPrimitiveType!!
            )?.let { m -> wrap(m, flag) }
            methodByName(it, "updateState", 1)?.let { m -> wrap(m, flag) }
        }

        val picker = Reflect.findClassIfExists(
            "com.android.settings.applications.credentials.DefaultCombinedPicker", loader
        )
        picker?.let {
            Reflect.findMethodIfExists(it, "setDefaultKey", String::class.java)?.let { m -> wrap(m, flag) }
            methodByName(it, "getDefaultKey", 0)?.let { m -> wrap(m, flag) }
        }

        val providerInfo = Reflect.findClassIfExists(
            "com.android.settings.applications.credentials.CombinedProviderInfo", loader
        )
        providerInfo?.let {
            methodByName(it, "launchSettingsActivityIntent", 4)?.let { m -> wrap(m, flag) }
        }
    }

    private fun wrap(method: Method, flag: MiuiInternationalFlag) {
        runCatching { HookHelper.intercept(method) { chain -> flag.run { chain.proceed() } } }
            .onFailure { HookHelper.log("$tag: wrap ${method.name} failed", it) }
    }

    private fun hookCombiPreference(loader: ClassLoader) {
        val combi = Reflect.findClassIfExists(
            "com.android.settings.applications.credentials.CredentialManagerPreferenceController\$CombiPreference",
            loader,
        ) ?: return
        val onBind = methodByName(combi, "onBindViewHolder", 1) ?: return
        val mChecked = runCatching { Reflect.findField(combi, "mChecked") }.getOrNull() ?: return
        val mOnClickListener = runCatching { Reflect.findField(combi, "mOnClickListener") }.getOrNull() ?: return
        val mSwitch = runCatching { Reflect.findField(combi, "mSwitch") }.getOrNull() ?: return
        val maybeUpdate = methodByName(combi, "maybeUpdateContentDescription", 0)
        val switchId = runCatching {
            val rId = Reflect.findClassIfExists("com.android.settingslib.R\$id", loader) ?: return@runCatching null
            Reflect.findField(rId, "switchWidget").getInt(null)
        }.getOrNull() ?: return

        val listener = Reflect.findClassIfExists(
            "com.android.settings.applications.credentials.CredentialManagerPreferenceController" +
                "\$CombiPreference\$OnCombiPreferenceClickListener",
            loader,
        )
        val onCheckChanged = listener?.let {
            Reflect.findMethodIfExists(it, "onCheckChanged", combi, Boolean::class.javaPrimitiveType!!)
        }

        HookHelper.hookAfter(onBind) { param ->
            val preference = param.thisObject ?: return@hookAfter
            val existing = runCatching { mSwitch.get(preference) }.getOrNull()
            if (existing != null) return@hookAfter
            val holder = param.args.getOrNull(0) ?: return@hookAfter
            val itemView = runCatching { Reflect.getObjectField(holder, "itemView") }.getOrNull() as? View
                ?: return@hookAfter
            val switchView = itemView.findViewById(switchId) as? CompoundButton ?: return@hookAfter
            val checked = runCatching { mChecked.getBoolean(preference) }.getOrDefault(false)
            switchView.isChecked = checked
            switchView.setOnClickListener {
                val clickListener = runCatching { mOnClickListener.get(preference) }.getOrNull()
                if (clickListener == null) return@setOnClickListener
                val accepted = onCheckChanged?.let {
                    runCatching { it.invoke(clickListener, preference, switchView.isChecked) as? Boolean }
                        .getOrNull()
                } ?: false
                if (!accepted) {
                    setBoolean(mChecked, preference, false)
                    switchView.isChecked = false
                }
            }
            setObject(mSwitch, preference, switchView)
            maybeUpdate?.let { runCatching { it.invoke(preference) } }
        }
    }

    private fun setBoolean(field: Field, target: Any, value: Boolean) {
        if (!runCatching { field.isAccessible = true; field.setBoolean(target, value) }.isSuccess) {
            UnsafeField.setBoolean(field, target, value)
        }
    }

    private fun setObject(field: Field, target: Any, value: Any?) {
        if (!runCatching { field.isAccessible = true; field.set(target, value) }.isSuccess) {
            UnsafeField.setObject(field, target, value)
        }
    }

    private fun methodByName(clazz: Class<*>, name: String, paramCount: Int): Method? =
        clazz.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == paramCount }
            ?.also { it.isAccessible = true }
}
