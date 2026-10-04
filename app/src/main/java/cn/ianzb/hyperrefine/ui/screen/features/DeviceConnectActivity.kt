package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

/**
 * 设备互联子页：跨设备通知流转 / 平板竖屏流转应用 / 多设备流转。
 *
 * 三项在非平板设备上禁用灰显；顶栏提供对 MiLink 与小米互联的重启入口。
 */
class DeviceConnectActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.device_connect

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.milink.service", "com.xiaomi.mirror")) }

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
