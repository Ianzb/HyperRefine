package cn.ianzb.hyperrefine.ui.component.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.ProvidableCompositionLocal

/**
 * 全局搜索的展开状态。
 *
 * 搜索界面（[AppSearchOverlay]）挂在主页的导航容器上，而不是某一页里：
 * 因此底部玻璃底栏的搜索按钮、任意主页面顶栏的搜索按钮都能唤起同一个搜索界面，
 * 搜索界面也始终绘制在底栏 / 侧栏之上（与 miuix 示例 GlassPage 的搜索层级一致）。
 */
@Stable
class AppSearchState {

    var expanded by mutableStateOf(false)
        private set

    fun open() {
        expanded = true
    }

    fun close() {
        expanded = false
    }
}

/**
 * 当前页面是否处于「可唤起全局搜索」的容器内。
 *
 * `null` 表示该页面不提供搜索入口（例如未接入搜索的二级页面 Activity），
 * 此时 `AppPageScaffold` 不会在顶栏渲染搜索按钮。
 */
val LocalAppSearchState: ProvidableCompositionLocal<AppSearchState?> = staticCompositionLocalOf { null }

/** [LocalAppSearchState] 的便捷读取：返回唤起搜索的可调用对象，不可用时为 null。 */
@Composable
fun rememberAppSearchOpen(): (() -> Unit)? {
    val state = LocalAppSearchState.current ?: return null
    return state::open
}
