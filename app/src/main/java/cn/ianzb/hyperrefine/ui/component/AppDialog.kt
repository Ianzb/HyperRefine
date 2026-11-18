package cn.ianzb.hyperrefine.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.ianzb.hyperrefine.ui.util.LocalPreferenceBackdrop
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 模块统一的对话框容器，背后是 miuix 的 OS4 玻璃对话框。
 *
 * 之所以包一层：弹层需要采样「页面自己的」背景模糊层（含弹层之外的页面内容），
 * 直接在各页面里手写 `GlassDialog` 很容易把弹层自身也录进 backdrop（自采样会导致糊成一片）。
 * 这里统一从 [LocalPreferenceBackdrop] 取当前页面的 backdrop，
 * 模糊关闭（两者均为 null）时自动退化为不透明材质，无需调用方判断。
 *
 * 弹层的层级由宿主决定：这里保持 `renderInRootScaffold = true`，弹层注册到应用最外层的
 * 弹层宿主（`MainActivity` 里那个只负责弹层的 Scaffold）。该宿主在绘制顺序上位于悬浮底栏与
 * 宽屏侧栏之上，所以弹层与遮罩天然覆盖底栏，不会被底栏遮挡，也不需要任何避让垫高；
 * 没有底栏的页面同理，位置不受影响（与 miuix 示例 `AppContent` 的层级完全一致）。
 *
 * @param show 是否显示
 * @param title 标题，null 表示不显示标题行
 * @param onDismissRequest 点击遮罩或返回键时回调
 * @param summary 标题下的说明文字
 * @param enableWindowDim 是否绘制窗口遮罩
 * @param content 对话框正文，通常在末尾放一行 [AppDialogButtonRow]
 */
@Composable
fun AppDialog(
    show: Boolean,
    title: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enableWindowDim: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalPreferenceBackdrop.current
    GlassDialog(
        onDismissRequest = onDismissRequest,
        show = show,
        backdrop = backdrop,
        modifier = modifier,
        enableWindowDim = enableWindowDim,
        renderInRootScaffold = true,
        title = title,
        summary = summary,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * 对话框底部按钮行：等距排布、上方留 12.dp，调用方用 `Modifier.weight(1f)` 平分宽度。
 */
@Composable
fun AppDialogButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * 对话框动作按钮。
 *
 * 按钮所在的行是等分宽度的，三按钮并列时「恢复默认」这类较长文案在窄屏/大字体的
 * 手机上会被折成两行（“恢复默 / 认”）。这里固定单行并允许自动缩字号：
 * 放得下时仍是原字号，放不下才按 TextAutoSize 逐级缩小，英文 “Restore Default” 同样不会折行。
 *
 * @param primary 主要动作（蓝色实底），同一对话框最多一个
 * @param destructive 破坏性动作（如重启系统），优先级低于 [primary]
 */
@Composable
fun AppDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val textStyle = MiuixTheme.textStyles.button
    // 主题若未指定字号，TextAutoSize 不接受 Unspecified，这里退回到按钮默认的 17.sp。
    val styleFontSize = textStyle.fontSize
    val maxFontSize = if (styleFontSize.type == TextUnitType.Sp) styleFontSize else 17.sp
    GlassDialogButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        role = when {
            primary -> GlassDialogButtonRole.Primary
            destructive -> GlassDialogButtonRole.Destructive
            else -> GlassDialogButtonRole.Normal
        },
    ) {
        MiuixText(
            text = text,
            style = textStyle,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 11.sp,
                maxFontSize = maxFontSize,
            ),
        )
    }
}
