package cn.ianzb.hyperrefine.hook.passkey

import android.content.Context
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * 安全中心侧通行密钥修复（目标 `com.miui.securitycenter`，DexKit 定位）。
 *
 * CN 版安全服务在初始化 / 缓存时会把 `autofill_service`、`credential_service`、
 * `credential_service_primary` 等 secure settings 覆写为内置提供方，导致 GMS 通行密钥被顶掉。
 * 这里按字符串 + 调用特征定位这两个「写入默认配置」的方法并拦截（返回 null），保留用户 / 系统设置。
 *
 * 方法名随版本混淆，故不写死；定位特征参考 HyperPasskey（GPL-3.0，独立实现）。
 */
class PasskeySecurityCenterHook : BaseHook() {

    override val key: String = PasskeyKeys.BLOCK_SECURITY_CENTER

    override fun useDexKit(): Boolean = true

    private var autofillConfigMethod: Method? = null
    private var credentialConfigMethod: Method? = null

    override fun initDexKit(): Boolean {
        autofillConfigMethod = optionalMember<Method>("sc_block_autofill") { bridge ->
            findConfigMethod(bridge, AUTOFILL_KEYS) { b, classes -> findSetStringConfig(b, classes) }
                ?: throw NoSuchMethodException("autofill config not found")
        }
        credentialConfigMethod = optionalMember<Method>("sc_block_credential") { bridge ->
            findConfigMethod(bridge, CREDENTIAL_KEYS) { b, classes -> findSetStringArrayConfig(b, classes) }
                ?: throw NoSuchMethodException("credential config not found")
        }
        return autofillConfigMethod != null || credentialConfigMethod != null
    }

    override fun init() {
        listOfNotNull(autofillConfigMethod, credentialConfigMethod).forEach { method ->
            runCatching { HookHelper.hookBefore(method) { param -> param.setResultValue(null) } }
                .onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
        }
    }

    private fun findConfigMethod(
        bridge: DexKitBridge,
        keys: Array<String>,
        helperFinder: (DexKitBridge, List<ClassData>) -> MethodData?,
    ): MethodData? {
        val classes = securityCenterClasses(bridge)
        if (classes.isEmpty()) return null
        val helper = helperFinder(bridge, classes) ?: return null
        return bridge.findMethod {
            searchInClass(classes)
            matcher {
                usingEqStrings(*keys)
                addInvoke(helper.descriptor)
            }
        }.firstOrNull()
    }

    private fun securityCenterClasses(bridge: DexKitBridge): List<ClassData> =
        listOf(
            "Lcom/miui/securitycenter/Application;",
            "Lcom/miui/securitycenter/service/CacheService;",
        ).mapNotNull { bridge.getClassData(it) }

    private fun findSetStringConfig(bridge: DexKitBridge, classes: List<ClassData>): MethodData? =
        bridge.findMethod {
            searchInClass(classes)
            matcher {
                anyOf(
                    MethodMatcher().paramTypes(Context::class.java, String::class.java, Int::class.javaPrimitiveType),
                    MethodMatcher().paramTypes(String::class.java, Int::class.javaPrimitiveType),
                )
                    .addInvoke("Landroid/content/res/Resources;->getString(I)Ljava/lang/String;")
                    .addInvoke(
                        "Landroid/provider/Settings\$Secure;->putString" +
                            "(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z"
                    )
            }
        }.firstOrNull()

    private fun findSetStringArrayConfig(bridge: DexKitBridge, classes: List<ClassData>): MethodData? =
        bridge.findMethod {
            searchInClass(classes)
            matcher {
                anyOf(
                    MethodMatcher().paramTypes(Context::class.java, String::class.java, Int::class.javaPrimitiveType),
                    MethodMatcher().paramTypes(String::class.java, Int::class.javaPrimitiveType),
                )
                    .addInvoke("Landroid/content/res/Resources;->getStringArray(I)[Ljava/lang/String;")
                    .addInvoke(
                        "Landroid/provider/Settings\$Secure;->putString" +
                            "(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z"
                    )
            }
        }.firstOrNull()

    private companion object {
        val AUTOFILL_KEYS = arrayOf("autofill_service")
        val CREDENTIAL_KEYS = arrayOf("credential_service", "credential_service_primary")
    }
}
