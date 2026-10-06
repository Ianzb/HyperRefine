package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.connect.ConnectKeys
import cn.ianzb.hyperrefine.hook.connect.MirrorKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备互联子页：跨设备通知流转 / 平板竖屏流转应用 / 多设备流转。
 *
 * 三项在非平板设备上禁用灰显；顶栏提供对 MiLink 与小米互联的重启入口。
 */
class DeviceConnectActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.device_connect

    override val topBarActions: (@Composable () -> Unit)? = {
        OpenCrossDeviceSettingsAction()
        QuickActionsAction(listOf("com.milink.service", "com.xiaomi.mirror"))
    }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val featureSection = remember {
            HookSection(
                titleRes = R.string.section_connect_features,
                specs = listOf(
                    featureSpec(ConnectKeys.CROSS_DEVICE_NOTIFICATION),
                    featureSpec(ConnectKeys.PORTRAIT_STREAMING),
                    featureSpec(ConnectKeys.MILINK_MULTI_CHANNEL),
                    featureSpec(ConnectKeys.DISCOVERY_FREQUENCY),
                ),
            )
        }
        val mirrorSection = remember {
            HookSection(
                titleRes = R.string.section_mirror,
                specs = listOf(
                    featureSpec(MirrorKeys.FLOATING_WINDOW),
                    featureSpec(MirrorKeys.FLOATING_RADIUS),
                    featureSpec(MirrorKeys.HIDE_POLE),
                    featureSpec(MirrorKeys.MINIMIZE_ON_SHADE),
                    featureSpec(MirrorKeys.REFRESH_RATE),
                    featureSpec(MirrorKeys.REFRESH_RATE_VALUE),
                ),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollBehavior != null) {
                        Modifier.pageScrollModifiers(
                            showTopAppBar = true,
                            topAppBarScrollBehavior = scrollBehavior,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentPadding = contentPadding,
        ) {
            item {
                HookSectionCard(featureSection) {
                    featureSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(mirrorSection) {
                    mirrorSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}

/**
 * 顶栏右上角「打开系统跨设备通知流转设置页面」入口。
 *
 * 打开小米互联（`com.milink.service`）的通知流转设置页（订阅应用）。
 */
@Composable
private fun OpenCrossDeviceSettingsAction(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val intent = Intent().apply {
                setClassName(CROSS_DEVICE_SETTINGS_PACKAGE, CROSS_DEVICE_SETTINGS_PROXY_ACTIVITY)
                putExtra(ConnectKeys.EXTRA_OPEN_NOTIFICATION_SETTINGS, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = MiuixIcons.Settings,
            contentDescription = stringResource(R.string.device_connect_open_settings),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private const val CROSS_DEVICE_SETTINGS_PACKAGE = "com.milink.service"

/**
 * 跳板入口。
 *
 * 目标页 `FeatureNotificationActivity` 受签名级权限 `miui.permission.USE_INTERNAL_GENERAL_API`
 * 保护，普通应用无法直接启动；故先打开目标应用的导出入口 `SettingActivity`，
 * 再由 Hook 侧在同一进程内改启目标页面（见 `MiLinkSettingsTrampolineHook`）。
 */
private const val CROSS_DEVICE_SETTINGS_PROXY_ACTIVITY = "com.milink.ui.setting.SettingActivity"
