package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.xposed.HookStatus
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Text as MiuixText

/**
 * Hook 四态状态标签。
 *
 * 颜色约定（也是文档中的统一格式）：
 * - 未生效：红色；
 * - 等待启动：黄色；
 * - 已生效：绿色；
 * - 不可用：灰色。
 *
 * 使用 Miuix [Badge]，文字走 Badge 的 `LocalTextStyles` / `LocalContentColor`，
 * 因此与系统设置里的 badge 字号、圆角和内边距一致。
 */
@Composable
fun HookStatusBadge(
    status: HookStatus,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val container: Color
    val content: Color
    when (status) {
        HookStatus.NOT_APPLIED -> {
            container = Color(0x1FD93025)
            content = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
        }

        HookStatus.WAITING -> {
            container = Color(0x33F9A825)
            content = if (dark) Color(0xFFFFD54F) else Color(0xFF6A4A00)
        }

        HookStatus.APPLIED -> {
            container = Color(0x332E7D32)
            content = if (dark) Color(0xFF81C784) else Color(0xFF1B5E20)
        }

        HookStatus.UNAVAILABLE -> {
            container = Color(0x1F888888)
            content = if (dark) Color(0xFFBDBDBD) else Color(0xFF5F5F5F)
        }
    }
    Badge(
        modifier = modifier,
        containerColor = container,
        contentColor = content,
    ) {
        MiuixText(
            text = stringResource(
                when (status) {
                    HookStatus.NOT_APPLIED -> R.string.hook_status_not_applied
                    HookStatus.WAITING -> R.string.hook_status_waiting
                    HookStatus.APPLIED -> R.string.hook_status_applied
                    HookStatus.UNAVAILABLE -> R.string.hook_status_unavailable
                }
            ),
            maxLines = 1,
        )
    }
}
