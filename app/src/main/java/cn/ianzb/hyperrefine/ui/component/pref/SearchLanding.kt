package cn.ianzb.hyperrefine.ui.component.pref

import android.content.Intent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults

internal const val FIRST_SEARCH_SECTION = ""
internal const val SEARCH_TARGET_EXTRA = "cn.ianzb.hyperrefine.search_target"
internal val LocalSearchTarget = staticCompositionLocalOf<String?> { null }
internal val LocalSearchFlash = staticCompositionLocalOf<(() -> Boolean)?> { null }

internal fun Intent.withSearchTarget(key: String?): Intent =
    if (key == null) this else putExtra(SEARCH_TARGET_EXTRA, key)

@Composable
internal fun SearchLandingLazyColumn(
    state: LazyListState,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit,
) {
    val key = LocalSearchTarget.current
    val landing = remember(key) { SectionLanding() }
    var handled by rememberSaveable(key) { mutableStateOf(false) }
    var flashedIndex by remember(key) { mutableIntStateOf(-1) }
    val rampDistance = with(LocalDensity.current) {
        maxOf(
            GlassTopAppBarDefaults.RampStart + GlassTopAppBarDefaults.RampDistance,
            GlassTopAppBarDefaults.ProgressiveBlurRampDistance,
        ).toPx()
    }

    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        if (key == null) {
            content()
        } else {
            val scope = SectionLandingScope(this, key) { index ->
                landing.claimFlash.takeIf { index == flashedIndex }
            }
            scope.content()
            landing.index = scope.targetIndex
        }
    }

    LaunchedEffect(key, state) {
        if (key == null || handled) return@LaunchedEffect
        val count = snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        val target = landing.index
        if (target !in 0 until count) return@LaunchedEffect

        state.animateScrollToItem(target)

        // Programmatic list scrolling does not update the nested-scroll app-bar state.
        val bar = scrollBehavior?.state
        if (bar != null) {
            withFrameNanos { }
            val limit = bar.heightOffsetLimit.takeIf { it in -10_000f..0f }
            if (limit != null) {
                val startContentOffset = bar.contentOffset
                animate(bar.heightOffset, limit, animationSpec = tween(200)) { value, _ ->
                    bar.heightOffset = value
                }
                bar.contentOffset = minOf(startContentOffset, -rampDistance)
                withFrameNanos { }
            }
        }
        snapshotFlow { state.layoutInfo.visibleItemsInfo.any { it.index == target } }.first { it }
        flashedIndex = target
        handled = true
    }
}

internal fun LazyListScope.searchSectionItem(
    section: HookSection,
    content: @Composable LazyItemScope.() -> Unit,
) {
    (this as? SectionLandingScope)?.markSection(section)
    item(content = content)
}

private class SectionLanding {
    var index: Int = -1
    private var flashClaimed = false
    val claimFlash: () -> Boolean = {
        if (flashClaimed) false else {
            flashClaimed = true
            true
        }
    }
}

/** Tracks positions in the same DSL that builds the list, including non-section items. */
private class SectionLandingScope(
    private val delegate: LazyListScope,
    private val key: String,
    private val flashForItem: (Int) -> (() -> Boolean)?,
) : LazyListScope by delegate {
    private var nextIndex = 0
    var targetIndex = -1
        private set

    fun markSection(section: HookSection) {
        if (targetIndex < 0 && (key == FIRST_SEARCH_SECTION || section.specs.any { it.key == key || it.masterKey == key })) {
            targetIndex = nextIndex
        }
    }

    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        val index = nextIndex++
        delegate.item(key, contentType) {
            CompositionLocalProvider(LocalSearchFlash provides this@SectionLandingScope.flashForItem(index)) {
                content()
            }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        nextIndex += count
        delegate.items(count, key, contentType, itemContent)
    }
}
