package cn.ianzb.hyperrefine.ui.component.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * 搜索结果条目的位置索引：配置键 → 标题路径（本页分区 / 子页面路径）。
 *
 * 索引由组件页面（`HookOptionsPage`）在组合时登记，搜索界面只读它来渲染结果摘要，
 * 这样搜索可以跨页面复用，同时摘要仍能显示「功能所在分区 / 子页面」。
 */
object AppSearchIndex {

    private val labels = mutableStateMapOf<String, List<Int>>()

    /** 批量登记（幂等）：同一个键重复登记会覆盖为最新路径。 */
    fun putAll(mapping: Map<String, List<Int>>) {
        if (mapping.isNotEmpty()) labels.putAll(mapping)
    }

    /** 返回该配置项的标题路径（字符串资源 id 列表），未登记时返回 null。 */
    fun labelFor(key: String): List<Int>? = labels[key]
}

/**
 * 全局搜索结果列表。
 *
 * 结果来自 [OptionRegistry]（功能页在启动时注册了全部配置项，子页面内的功能同样在内），
 * 摘要显示该项所在的分区 / 子页面路径（见 [AppSearchIndex]）。
 *
 * @param query 搜索关键词。
 * @param onPick 点击结果时回调，参数为配置键。
 */
@Composable
fun AppSearchResults(
    query: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    if (query.isBlank()) return
    val keyword = query.trim()
    val results = OptionRegistry.all().mapNotNull { spec ->
        val path = AppSearchIndex.labelFor(spec.key) ?: return@mapNotNull null
        val title = context.getString(spec.titleRes)
        val breadcrumb = path.joinToString(" / ") { context.getString(it) }
        val description = spec.summaryRes.takeIf { it != 0 }?.let { context.getString(it) }.orEmpty()
        val rank = when {
            title.equals(keyword, ignoreCase = true) -> 0
            title.startsWith(keyword, ignoreCase = true) -> 1
            title.contains(keyword, ignoreCase = true) -> 2
            breadcrumb.contains(keyword, ignoreCase = true) -> 3
            description.contains(keyword, ignoreCase = true) -> 4
            else -> return@mapNotNull null
        }
        val matchedDescription = description.takeIf { it.contains(keyword, ignoreCase = true) }
        val summary = listOfNotNull(breadcrumb.takeIf { it.isNotBlank() }, matchedDescription)
            .distinct()
            .joinToString("\n")
            .ifBlank { description }
        SearchResult(spec.key, title, summary, rank)
    }.sortedWith(compareBy<SearchResult> { it.rank }.thenBy { it.title })
    if (results.isEmpty()) {
        MiuixText(
            text = stringResource(R.string.search_empty),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote2,
            modifier = modifier.padding(horizontal = 28.dp, vertical = 12.dp),
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
    ) {
        item(key = "results") {
            Card {
                results.forEach { result ->
                    SearchResultRow(result = result, keyword = keyword, onPick = onPick)
                }
            }
        }
    }
}

private data class SearchResult(val key: String, val title: String, val summary: String, val rank: Int)

@Composable
private fun SearchResultRow(result: SearchResult, keyword: String, onPick: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    BasicComponent(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onPick(result.key) },
    ) {
        MiuixText(
            text = highlightMatch(result.title, keyword, colors.primary),
            style = MiuixTheme.textStyles.main,
            color = colors.onSurface,
        )
        if (result.summary.isNotBlank()) {
            MiuixText(
                text = highlightMatch(result.summary, keyword, colors.primary),
                style = MiuixTheme.textStyles.body2,
                color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun highlightMatch(text: String, keyword: String, color: Color) = buildAnnotatedString {
    append(text)
    val style = SpanStyle(color = color)
    var start = text.indexOf(keyword, ignoreCase = true)
    while (start >= 0) {
        val end = start + keyword.length
        addStyle(style, start, end)
        start = text.indexOf(keyword, startIndex = end, ignoreCase = true)
    }
}
