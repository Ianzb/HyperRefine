package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.sogou.SogouImeKeys
import cn.ianzb.hyperrefine.hook.voiceassist.VoiceAssistImeKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.SearchLandingLazyColumn
import cn.ianzb.hyperrefine.ui.component.pref.searchSectionItem
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import cn.ianzb.hyperrefine.xposed.XposedServiceManager

/**
 * 实验性功能下的「输入法」页，收纳超级小爱与搜狗输入法适配。
 */
class ImeActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.ime_title

    override val topBarActions: @Composable () -> Unit = ImeActions

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        ImeContent(isBlurEnabled, contentPadding)
    }
}

internal val ImeActions: @Composable () -> Unit = {
        QuickActionsAction(listOf(VoiceAssistImeKeys.PKG_VOICE_ASSIST_IME, SogouImeKeys.PKG_SOGOU_IME))
    }

@Composable
internal fun ImeContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
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
    val sogouSection = HookSection(
        titleRes = R.string.sogou_ime_title,
        specs = listOf(
            featureSpec(KEY_SOGOU_IME_GLASS),
            featureSpec(KEY_SOGOU_IME_BLUR_RADIUS),
            featureSpec(KEY_SOGOU_IME_CORNER_RADIUS),
        ),
    )

    val listState = rememberLazyListState()
    SearchLandingLazyColumn(
        scrollBehavior = scrollBehavior,
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (scrollBehavior != null) {
                    Modifier.pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                        contentState = listState,
                    )
                } else {
                    Modifier
                }
            ),
        contentPadding = contentPadding,
    ) {
        listOf(voiceSection, sogouSection).forEach { section ->
            searchSectionItem(section) {
                HookSectionCard(section) {
                    section.specs.forEach { spec ->
                        HookOptionView(spec = spec)
                    }
                }
            }
        }
    }
}
