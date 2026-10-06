package cn.ianzb.hyperrefine.hook.browser

import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * HyperAI 复制直达（Copy Direct）改写（独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 *
 * - 改写 `ActionCoreProvider` 返回的 `copyDirectData.intentUri` 与 UI 元数据，改投默认浏览器；
 * - 改写渲染用 `CueData` 里的目标包名 / 文案。
 *
 * 图标因 HyperAI 仅识别内置标识表，改由渲染层替换（见 [BrowserCopyDirectHook]）。
 */
object BrowserCopyDirect {

    private val BROWSER_LABELS = listOf("小米浏览器", "Mi Browser", "Xiaomi Browser")

    fun isCopyDirect(request: Bundle?): Boolean =
        request?.getString("type")?.contains("context/get_copy_direct_data") == true

    fun rewriteActionResponse(request: Bundle?, response: Bundle?): Boolean {
        if (!isCopyDirect(request)) return false
        if (response == null || response.getInt("target_code", -1) != 0) return false
        val targetOutText = response.getString("target_out") ?: return false

        return runCatching {
            val targetOut = JSONObject(targetOutText)
            if (targetOut.optInt("status", -1) != 0) return false
            val rawCopyDirect = targetOut.opt("copyDirectData")
            val copyDirect = when (rawCopyDirect) {
                is String -> JSONObject(rawCopyDirect)
                is JSONObject -> rawCopyDirect
                else -> return false
            }
            val intentUri = copyDirect.optString("intentUri", "")
            if (intentUri.isBlank()) return false

            val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
            val targetPkg = intent.`package`
            val targetComp = intent.component?.packageName
            if (!XiaomiBrowserPackages.isBrowser(targetPkg) &&
                !XiaomiBrowserPackages.isBrowser(targetComp)
            ) {
                return false
            }
            val data = intent.data
            val scheme = data?.scheme?.lowercase()
            if (data == null || (scheme != "http" && scheme != "https")) return false

            val context = BrowserCompat.currentApplication() ?: return false
            val browser = BrowserDefaultResolver.resolve(context) ?: return false
            if (!browser.isDefault || XiaomiBrowserPackages.isBrowser(browser.packageName)) return false
            val label = BrowserCompat.defaultBrowserLabel(context) ?: browser.packageName

            intent.component = null
            intent.setPackage(browser.packageName)
            copyDirect.put("intentUri", intent.toUri(Intent.URI_INTENT_SCHEME))
            rewriteMetadata(copyDirect, browser.packageName, label)

            if (rawCopyDirect is String) {
                targetOut.put("copyDirectData", copyDirect.toString())
            } else {
                targetOut.put("copyDirectData", copyDirect)
            }
            response.putString("target_out", targetOut.toString())
            true
        }.getOrDefault(false)
    }

    fun rewriteMetadata(value: Any?, browserPkg: String, label: String): Int = when (value) {
        is JSONObject -> {
            var changes = 0
            for (key in value.keys().asSequence().toList()) {
                val child = value.opt(key)
                if (child is String) {
                    val rewritten = rewriteString(child, browserPkg, label)
                    if (rewritten != child) {
                        value.put(key, rewritten)
                        changes++
                    }
                } else {
                    changes += rewriteMetadata(child, browserPkg, label)
                }
            }
            changes
        }
        is JSONArray -> {
            var changes = 0
            for (index in 0 until value.length()) {
                val child = value.opt(index)
                if (child is String) {
                    val rewritten = rewriteString(child, browserPkg, label)
                    if (rewritten != child) {
                        value.put(index, rewritten)
                        changes++
                    }
                } else {
                    changes += rewriteMetadata(child, browserPkg, label)
                }
            }
            changes
        }
        else -> 0
    }

    /** 递归改写已渲染的 CueData（目标包名 / 文案）。返回改动数。 */
    fun rewriteCueData(cueData: Any, browserPkg: String, label: String): Int =
        rewriteObject(cueData, browserPkg, label, IdentityHashMap(), 0)

    fun isCopyDirectCueData(cueData: Any): Boolean =
        instanceFields(cueData.javaClass).any { field ->
            val value = runCatching { field.isAccessible = true; field.get(cueData) as? String }.getOrNull()
            value == "copy_jump" || value?.contains("copy_text_jump_app") == true
        }

    private fun rewriteObject(
        value: Any,
        browserPkg: String,
        label: String,
        visited: IdentityHashMap<Any, Boolean>,
        depth: Int,
    ): Int {
        if (depth > 7) return 0
        if (visited.put(value, true) != null) return 0
        if (!value.javaClass.name.startsWith("com.xiaomi.ai.bubble.core.model.CueData")) return 0
        var changes = 0
        try {
            for (field in instanceFields(value.javaClass)) {
                field.isAccessible = true
                val child = runCatching { field.get(value) }.getOrNull() ?: continue
                when (child) {
                    is String -> {
                        val rewritten = rewriteString(child, browserPkg, label)
                        if (rewritten != child && runCatching { field.set(value, rewritten) }.isSuccess) changes++
                    }
                    is Number, is Boolean, is Char -> Unit
                    else -> changes += rewriteObject(child, browserPkg, label, visited, depth + 1)
                }
            }
        } finally {
            visited.remove(value)
        }
        return changes
    }

    private fun rewriteString(value: String, browserPkg: String, label: String): String {
        var out = value
        for (pkg in XiaomiBrowserPackages.BROWSER) out = out.replace(pkg, browserPkg)
        for (browserLabel in BROWSER_LABELS) out = out.replace(browserLabel, label)
        return out
    }

    fun instanceFields(clazz: Class<*>): List<Field> {
        val fields = ArrayList<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            fields += current.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
            current = current.superclass
        }
        return fields
    }
}
