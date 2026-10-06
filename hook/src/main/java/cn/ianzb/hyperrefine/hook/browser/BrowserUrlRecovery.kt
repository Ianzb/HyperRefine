package cn.ianzb.hyperrefine.hook.browser

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 网页链接恢复工具（独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 *
 * 小米系统组件常把原始 `http(s)` 链接改写成 `market://details?id=com.android.browser` 下载页，
 * 或包在 `mi://` / `intent://` 里。这里负责从 Intent 的 data / extras / ClipData / 嵌套对象中
 * 恢复真实网页地址，并维护一个短时缓存以便后续找回原始链接。
 */
object BrowserUrlRecovery {

    private const val CACHE_TTL_MS = 2 * 60 * 1000L
    private const val MAX_DEPTH = 4

    @Volatile private var recentUrl: Uri? = null
    @Volatile private var recentAt: Long = 0L

    fun remember(url: Uri) {
        recentUrl = url
        recentAt = System.currentTimeMillis()
    }

    fun recent(): Uri? {
        val url = recentUrl ?: return null
        val age = System.currentTimeMillis() - recentAt
        return if (age in 0..CACHE_TTL_MS) url else null
    }

    fun hasRecent(): Boolean = recent() != null

    /** 记录 Intent 中疑似用户网页 URL（供后续恢复）。 */
    fun rememberFromIntent(intent: Intent) {
        extractWebUri(intent, ::isLikelyUserWebUrl)?.let { remember(it) }
    }

    /** 记录任意对象中的疑似用户网页 URL。 */
    fun rememberFromValue(value: Any?) {
        extractWebUriFromValue(value, newVisited(), 0, ::isLikelyUserWebUrl)?.let { remember(it) }
    }

    /** 从 Intent 恢复原始网页地址（用于跳转替换）。 */
    fun recoverForRedirect(intent: Intent): Uri? {
        val scheme = intent.data?.scheme?.lowercase()
        return when {
            scheme == "market" -> recoverFromMarket(intent)
            scheme == "intent" -> recoverFromIntentScheme(intent) ?: recent()
            scheme?.startsWith("mi") == true -> recoverFromMiScheme(intent) ?: recent()
            else -> extractWebUri(intent, ::isLikelyUserWebUrl) ?: recent()
        }
    }

    private fun recoverFromMarket(intent: Intent): Uri? {
        extractWebUri(intent, ::isLikelyUserWebUrl)?.let { return it }
        val data = intent.data ?: return recent()
        for (key in arrayOf("url", "referrer", "link", "target_url")) {
            val value = runCatching { data.getQueryParameter(key) }.getOrNull()
            if (!value.isNullOrEmpty() && (value.startsWith("http://") || value.startsWith("https://"))) {
                return Uri.parse(value)
            }
        }
        return recent()
    }

    private fun recoverFromMiScheme(intent: Intent): Uri? {
        val data = intent.data ?: return null
        for (key in arrayOf("url", "web_url", "query", "q", "link", "target_url", "text")) {
            val value = runCatching { data.getQueryParameter(key) }.getOrNull()?.trim()
            if (!value.isNullOrEmpty()) {
                if (value.startsWith("http://") || value.startsWith("https://")) return Uri.parse(value)
                if (looksLikeDomain(value)) return Uri.parse(normalize(value))
            }
        }
        runCatching {
            for (key in data.queryParameterNames) {
                val value = data.getQueryParameter(key)?.trim() ?: continue
                if (value.startsWith("http://") || value.startsWith("https://")) return Uri.parse(value)
                if (looksLikeDomain(value)) return Uri.parse(normalize(value))
            }
        }
        intent.extras?.let { extras ->
            for (key in extras.keySet()) {
                if (extras.get(key) !is String) continue
                val value = (extras.get(key) as String).trim()
                if (value.startsWith("http://") || value.startsWith("https://")) return Uri.parse(value)
                if (looksLikeDomain(value)) return Uri.parse(normalize(value))
            }
        }
        return null
    }

    private fun recoverFromIntentScheme(intent: Intent): Uri? {
        val data = intent.data ?: return null
        for (key in arrayOf("url", "web_url", "link", "target_url", "q")) {
            val value = runCatching { data.getQueryParameter(key) }.getOrNull()?.trim()
            if (!value.isNullOrEmpty() && (value.startsWith("http://") || value.startsWith("https://"))) {
                return Uri.parse(value)
            }
        }
        intent.extras?.let { extras ->
            for (key in extras.keySet()) {
                val value = extras.get(key)
                if (value is String && (value.startsWith("http://") || value.startsWith("https://"))) {
                    return Uri.parse(value)
                }
            }
        }
        return null
    }

    fun extractWebUri(intent: Intent, filter: ((Uri) -> Boolean)? = null): Uri? {
        extractWebUriOrNull(intent.data?.toString())?.let { if (filter == null || filter(it)) return it }

        val known = arrayOf(
            Intent.EXTRA_TEXT,
            Intent.EXTRA_HTML_TEXT,
            Intent.EXTRA_REFERRER_NAME,
            "android.intent.extra.REFERRER",
            "url", "uri", "link", "target_url", "referrer", "text", "share_url", "content",
        )
        for (key in known) {
            val value = runCatching { intent.extras?.get(key) }.getOrNull()
            extractWebUriFromValue(value, newVisited(), 0, filter)?.let { return it }
        }
        extractWebUriFromBundle(intent.extras, filter)?.let { return it }
        extractWebUriFromClipData(intent.clipData, filter)?.let { return it }
        return null
    }

    private fun extractWebUriFromBundle(bundle: Bundle?, filter: ((Uri) -> Boolean)?): Uri? {
        if (bundle == null) return null
        for (key in bundle.keySet()) {
            val value = runCatching { bundle.get(key) }.getOrNull()
            extractWebUriFromValue(value, newVisited(), 0, filter)?.let { return it }
        }
        return null
    }

    private fun extractWebUriFromClipData(clipData: ClipData?, filter: ((Uri) -> Boolean)?): Uri? {
        if (clipData == null) return null
        for (i in 0 until clipData.itemCount) {
            val item = clipData.getItemAt(i) ?: continue
            extractWebUriOrNull(item.uri?.toString())?.let { if (filter == null || filter(it)) return it }
            extractWebUriOrNull(item.text?.toString())?.let { if (filter == null || filter(it)) return it }
            item.intent?.let { extractWebUri(it, filter)?.let { uri -> return uri } }
        }
        return null
    }

    private fun extractWebUriFromValue(
        value: Any?,
        visited: MutableSet<Any>,
        depth: Int,
        filter: ((Uri) -> Boolean)?,
    ): Uri? {
        val uri = when (value) {
            null -> null
            is Uri -> extractWebUriOrNull(value.toString())
            is Intent -> extractWebUri(value, filter)
            is Bundle -> extractWebUriFromBundle(value, filter)
            is CharSequence -> extractWebUriOrNull(value.toString())
            is Array<*> -> value.firstNotNullOfOrNull { extractWebUriFromValue(it, visited, depth + 1, filter) }
            is Iterable<*> -> value.firstNotNullOfOrNull { extractWebUriFromValue(it, visited, depth + 1, filter) }
            else -> extractWebUriOrNull(value.toString())
                ?: extractFromObjectFields(value, visited, depth, filter)
        }
        return if (filter != null && uri != null && !filter(uri)) null else uri
    }

    private fun extractFromObjectFields(
        value: Any,
        visited: MutableSet<Any>,
        depth: Int,
        filter: ((Uri) -> Boolean)?,
    ): Uri? {
        if (depth >= MAX_DEPTH) return null
        if (!visited.add(value)) return null
        var clazz: Class<*>? = value.javaClass
        if (clazz == null || skipObjectScan(clazz.name)) return null
        while (clazz != null && clazz != Any::class.java) {
            if (skipObjectScan(clazz.name)) break
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val fieldValue = runCatching { field.isAccessible = true; field.get(value) }.getOrNull() ?: continue
                extractWebUriFromValue(fieldValue, visited, depth + 1, filter)?.let { return it }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun skipObjectScan(className: String): Boolean =
        className.startsWith("java.") ||
            className.startsWith("kotlin.") ||
            className.startsWith("android.") ||
            className.startsWith("androidx.") ||
            className.startsWith("dalvik.") ||
            className.startsWith("com.android.") ||
            className.endsWith("ClassLoader")

    fun extractWebUriOrNull(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        val direct = raw.trim()
        if (direct.startsWith("http://") || direct.startsWith("https://")) return Uri.parse(direct)

        val decoded = runCatching { Uri.decode(direct) }.getOrDefault(direct)
        fromKnownParameter(decoded)?.let { return it }

        Regex("""https?://[^\s"'<>]+""").find(decoded)?.let {
            return Uri.parse(it.value.trimEnd(')', ']', '}', ',', '.', ';'))
        }
        return Regex("""(?i)\b(?:www\.)?[a-z0-9][a-z0-9-]*(?:\.[a-z0-9][a-z0-9-]*)+\b(?:/[^\s"'<>#;]*)?""")
            .findAll(decoded)
            .map { it.value.trimEnd(')', ']', '}', ',', '.', ';') }
            .firstOrNull { looksLikeDomain(it) }
            ?.let { Uri.parse(normalize(it)) }
    }

    private fun fromKnownParameter(decoded: String): Uri? {
        val regex = Regex("""(?i)(?:^|[?&#;])(?:url|web_url|link|target_url|q)=([^&#;\s"'<>]+)""")
        for (match in regex.findAll(decoded)) {
            val value = runCatching { Uri.decode(match.groupValues[1].trim()) }.getOrDefault("")
            val candidate = when {
                value.startsWith("http://") || value.startsWith("https://") -> value
                looksLikeDomain(value) -> normalize(value)
                else -> null
            } ?: continue
            val uri = Uri.parse(candidate)
            if (isLikelyUserWebUrl(uri)) return uri
        }
        return null
    }

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    fun looksLikeDomain(raw: String): Boolean {
        val value = raw.trim()
        if (value.length <= 4 || value.contains(" ")) return false
        if (value.any { it.isUpperCase() }) return false
        if (!value.contains(".")) return false
        if (value.startsWith("0x")) return false
        if (value.contains("com.miui.") || value.contains("com.xiaomi.") || value.contains("com.android.")) return false
        if (XiaomiBrowserPackages.isBrowser(value) || XiaomiBrowserPackages.isMarket(value)) return false
        val host = value.substringBefore('/').substringBefore('?').substringBefore('#')
        val labels = host.split('.')
        if (labels.size < 2) return false
        val tld = labels.last()
        return tld.length in 2..24 && tld.all { it.isLetter() }
    }

    fun isLikelyUserWebUrl(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (XiaomiBrowserPackages.isBrowser(host) || XiaomiBrowserPackages.isMarket(host)) return false
        val path = uri.path?.lowercase().orEmpty()
        if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".ico")
        ) {
            return false
        }
        return true
    }

    fun isRouterAdminUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (host == "miwifi.com" || host.endsWith(".miwifi.com")) return true
        if (host == "router.miwifi.com" || host == "www.miwifi.com") return true
        val path = uri.path?.lowercase().orEmpty()
        if ((path.contains("miwifi") || path.contains("luci")) && isPrivateHost(host)) return true
        return isGatewayHost(host)
    }

    private fun isGatewayHost(host: String): Boolean {
        val nums = host.split('.').map { it.toIntOrNull() ?: return false }
        if (nums.size != 4 || nums.any { it !in 0..255 }) return false
        return when {
            nums[0] == 10 && nums[3] == 1 -> true
            nums[0] == 192 && nums[1] == 168 && nums[3] == 1 -> true
            nums[0] == 172 && nums[1] in 16..31 && nums[3] == 1 -> true
            else -> false
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val nums = host.split('.').map { it.toIntOrNull() ?: return false }
        if (nums.size != 4 || nums.any { it !in 0..255 }) return false
        return nums[0] == 10 || (nums[0] == 192 && nums[1] == 168) || (nums[0] == 172 && nums[1] in 16..31)
    }

    private fun newVisited(): MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
}
