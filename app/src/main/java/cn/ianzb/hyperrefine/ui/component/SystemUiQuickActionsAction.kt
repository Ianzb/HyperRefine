package cn.ianzb.hyperrefine.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.SystemUiLoad
import cn.ianzb.hyperrefine.ui.component.pref.QuickActionDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶栏「快捷操作」入口（系统界面）。
 *
 * 点击后弹出 [QuickActionDialog] 二级菜单，对 `com.android.systemui` 执行热重载 / 重启，
 * 与 [cn.ianzb.hyperrefine.ui.component.pref.HookOptionsPage] 右上角行为一致。
 */
@Composable
fun SystemUiQuickActionsAction(modifier: Modifier = Modifier) {
    var showDialog by remember { mutableStateOf(false) }

    IconButton(onClick = { showDialog = true }, modifier = modifier) {
        Icon(
            imageVector = MiuixIcons.Refresh,
            contentDescription = stringResource(R.string.quick_action_title),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }

    if (showDialog) {
        QuickActionDialog(
            packages = listOf(SystemUiLoad.TARGET_PACKAGE),
            onDismiss = { showDialog = false },
        )
    }
}
