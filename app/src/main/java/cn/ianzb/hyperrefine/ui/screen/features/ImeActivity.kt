package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.voiceassist.VoiceAssistImeKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import cn.ianzb.hyperrefine.xposed.XposedServiceManager

/**
 * 「输入法」二级页：收纳已验证可用的输入法适配。
 *
 * 搜狗输入法的玻璃材质适配尚未达到预期，暂时移到「实验性功能」，
 * 本页只保留超级小爱输入法。
 */
class ImeActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.ime_title

    override val topBarActions: (@Composable () -> Unit)? = {
        QuickActionsAction(listOf(VoiceAssistImeKeys.PKG_VOICE_ASSIST_IME))
    }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val voiceSpec = featureSpec(KEY_VOICE_ASSIST_IME_GLOBAL_MATERIAL)
        val activated = XposedServiceManager.isActivated
        val voiceInScope = XposedServiceManager.isInScope(VoiceAssistImeKeys.PKG_VOICE_ASSIST_IME)

        // 开关打开但作用域被重置时，进入本页自动补齐缺失作用域。
        LaunchedEffect(activated, voiceInScope, voiceSpec.key) {
            if (activated && !voiceInScope && ConfigState.bool(voiceSpec.key, false)) {
                XposedServiceManager.ensureScope(voiceSpec.targetPackages)
            }
        }

        val voiceSection = HookSection(
            titleRes = R.string.voice_assist_ime_title,
            specs = listOf(
                voiceSpec,
                featureSpec(KEY_VOICE_ASSIST_IME_MATERIAL_OPACITY),
            ),
        )

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
                HookSectionCard(voiceSection) {
                    voiceSection.specs.forEach { spec ->
                        HookOptionView(spec = spec)
                    }
                }
            }
        }
    }
}
