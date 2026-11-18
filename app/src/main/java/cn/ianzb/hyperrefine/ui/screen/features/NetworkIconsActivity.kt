package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import cn.ianzb.hyperrefine.AppSettings
import cn.ianzb.hyperrefine.LocaleHelper
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconComponent
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconKeys
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconRegistry
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionsPage
import cn.ianzb.hyperrefine.ui.component.pref.rememberEffectiveDeviceType
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.component.pref.HookSubPage
import cn.ianzb.hyperrefine.ui.component.pref.withSearchTarget
import cn.ianzb.hyperrefine.ui.component.pref.HookSwitchCard
import cn.ianzb.hyperrefine.ui.component.statusbar.StatusBarIconRow
import cn.ianzb.hyperrefine.ui.component.statusbar.componentTitleRes
import cn.ianzb.hyperrefine.ui.theme.AppTheme
import cn.ianzb.hyperrefine.ui.util.applyWindowBackground
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 状态栏图标主页面。
 *
 * 顺序以本机 SystemUI 实际布局为准：
 * - 左侧：phone_status_bar_left_container 的 operator → clock → 平板日期；
 * - 右侧：statusIcons slot（network_speed … wifi）→ battery →
 *   根布局 END 对齐的 privacy_area。
 *
 * 只有 statusIcons 的 slot 可重排；通知区、电池和隐私指示是独立宿主，保持固定位置。
 */
class NetworkIconsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            LocaleHelper.wrapContext(newBase, LocaleHelper.getSavedLanguage(newBase)),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = AppSettings.load(this)
        val themeMode = runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
            .getOrDefault(ColorSchemeMode.System)
        applyWindowBackground(settings.themeMode)

        setContent {
            AppTheme(themeMode = themeMode) {
                val context = LocalContext.current
                val subPages = remember(context) { detailSubPages(context) }
                var dragging by remember { mutableStateOf(false) }
                HookOptionsPage(
                    title = stringResource(R.string.sb_icon_title),
                    sections = emptyList(),
                    subPages = subPages,
                    isBlurEnabled = settings.isBlurEnabled,
                    topContent = {
                        NetworkIconsContent(
                            onDragStateChange = { dragging = it },
                        )
                    },
                    onBack = { finish() },
                    userScrollEnabled = !dragging,
                    showSearch = false,
                    topBarActions = {
                        QuickActionsAction(listOf(StatusBarKeys.TARGET))
                    },
                )
            }
        }
    }
}

@Composable
private fun NetworkIconsContent(onDragStateChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val enabled = ConfigState.bool(StatusBarIconKeys.ENABLED, false)
    val deviceType = rememberEffectiveDeviceType()
    val supportsPadClock = deviceType == DeviceType.PAD || deviceType == DeviceType.FOLD
    val leftComponents = remember(supportsPadClock) {
        StatusBarIconRegistry.left.filter {
            it.id != StatusBarIconRegistry.PAD_CLOCK || supportsPadClock
        }
    }
    val masterSpec = remember {
        OptionSpec(
            key = StatusBarIconKeys.ENABLED,
            type = OptionType.SWITCH,
            titleRes = R.string.sb_icon_custom,
            summaryRes = R.string.sb_icon_custom_summary,
            targetPackages = listOf(StatusBarKeys.TARGET),
            showStatus = true,
        )
    }

    LaunchedEffect(masterSpec) { OptionRegistry.register(masterSpec) }

    Column(modifier = Modifier.fillMaxWidth()) {
        HookSectionCard(HookSection(specs = listOf(masterSpec))) {
            HookSwitchCard(masterSpec)
        }

        HookSectionCard(HookSection(titleRes = R.string.sb_icon_left, specs = emptyList())) {
            Column {
                leftComponents.forEach { component ->
                    StatusBarIconRow(
                        component = component,
                        enabled = enabled,
                        onOpen = {
                            if (enabled) openDetail(context, component.id)
                            else toast(context, R.string.sb_icon_disabled)
                        },
                    )
                }
            }
        }

        HookSectionCard(HookSection(titleRes = R.string.sb_icon_right, specs = emptyList())) {
            RightIconGroup(
                enabled = enabled,
                onDragStateChange = onDragStateChange,
                onOpen = { id ->
                    if (enabled) openDetail(context, id)
                    else toast(context, R.string.sb_icon_disabled)
                },
            )
        }
    }
}

private fun detailSubPages(context: Context): List<HookSubPage> =
    (StatusBarIconRegistry.left + StatusBarIconRegistry.right).map { component ->
        HookSubPage(
            titleRes = componentTitleRes(component.id),
            specs = emptyList(),
            onOpen = { openDetail(context, component.id) },
        )
    }

internal fun openDetail(context: Context, id: String, searchTarget: String? = null) {
    context.startActivity(
        Intent(context, IconSettingsActivity::class.java)
            .putExtra(IconSettingsActivity.EXTRA_COMPONENT, id)
            .withSearchTarget(searchTarget),
    )
}

private fun toast(context: Context, res: Int) {
    Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
}

@Composable
private fun RightIconGroup(
    enabled: Boolean,
    onDragStateChange: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
) {
    val right = StatusBarIconRegistry.right
    val movable = remember(right) { right.filter { it.reorderable } }
    val firstMovable = right.indexOfFirst { it.reorderable }
    val lastMovable = right.indexOfLast { it.reorderable }
    val pinnedBefore = remember(right) {
        if (firstMovable > 0) right.take(firstMovable).filter { !it.reorderable } else emptyList()
    }
    val pinnedAfter = remember(right) {
        if (lastMovable >= 0) right.drop(lastMovable + 1).filter { !it.reorderable } else emptyList()
    }
    val defaultOrder = remember(movable) { movable.map { it.id } }
    val saved = ConfigState.string(StatusBarIconKeys.ORDER, "")
    var order by remember { mutableStateOf(loadOrder(saved, movable)) }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(56f) }
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(saved) {
        if (dragId == null) order = loadOrder(saved, movable)
    }

    fun persistOrder() {
        val value = if (order == defaultOrder) "" else order.joinToString(",")
        ConfigState.set(StatusBarIconKeys.ORDER, value)
    }

    @Composable
    fun row(
        component: StatusBarIconComponent,
        dragHandle: Modifier? = null,
    ) {
        val dragging = dragId == component.id
        key(component.id) {
            StatusBarIconRow(
                component = component,
                enabled = enabled,
                modifier = Modifier
                    .zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragging) dragOffset else 0f
                    }
                    .onGloballyPositioned {
                        rowHeight = it.size.height.toFloat().coerceAtLeast(1f)
                    },
                dragHandleModifier = dragHandle,
                onOpen = { onOpen(component.id) },
            )
        }
    }

    Column {
        pinnedBefore.forEach { component -> row(component) }

        order.forEach { id ->
            val component = movable.firstOrNull { it.id == id } ?: return@forEach
            val handle = if (!enabled) {
                null
            } else {
                Modifier.pointerInput(component.id, enabled) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        dragId = component.id
                        dragOffset = 0f
                        onDragStateChange(true)
                        var moved = false
                        try {
                            drag(down.id) { change ->
                                change.consume()
                                moved = true
                                dragOffset += change.position.y - change.previousPosition.y
                                val threshold = rowHeight * 0.55f
                                while (dragOffset > threshold) {
                                    val next = move(order, component.id, 1)
                                    if (next == order) {
                                        dragOffset = threshold
                                        break
                                    }
                                    order = next
                                    dragOffset -= rowHeight
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                while (dragOffset < -threshold) {
                                    val next = move(order, component.id, -1)
                                    if (next == order) {
                                        dragOffset = -threshold
                                        break
                                    }
                                    order = next
                                    dragOffset += rowHeight
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        } finally {
                            dragId = null
                            dragOffset = 0f
                            onDragStateChange(false)
                            if (moved) persistOrder()
                        }
                    }
                }
            }
            row(component, handle)
        }

        pinnedAfter.forEach { component -> row(component) }

        if (enabled) {
            BasicComponent(
                title = stringResource(R.string.sb_icon_reset_order),
                summary = stringResource(R.string.sb_icon_reset_order_summary),
                onClick = {
                    order = defaultOrder
                    ConfigState.set(StatusBarIconKeys.ORDER, "")
                },
            )
        }
    }
}

private fun loadOrder(saved: String, movable: List<StatusBarIconComponent>): List<String> {
    val available = movable.map { it.id }
    val parsed = saved.split(',').map(String::trim).filter { it in available }
    return (parsed + available.filter { it !in parsed }).distinct()
}

private fun move(order: List<String>, id: String, delta: Int): List<String> {
    val current = order.indexOf(id)
    if (current < 0) return order
    val target = (current + delta).coerceIn(0, order.lastIndex)
    if (target == current) return order
    return order.toMutableList().apply {
        removeAt(current)
        add(target, id)
    }
}
