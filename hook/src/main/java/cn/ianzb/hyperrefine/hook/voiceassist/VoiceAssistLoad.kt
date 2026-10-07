package cn.ianzb.hyperrefine.hook.voiceassist

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 超级小爱（`com.miui.voiceassist`）目标 Load。
 */
class VoiceAssistLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(VoiceAssistKeys.PKG_VOICE_ASSIST)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            ScreenRecognitionRadiusHook(),
            HookPrefs.getBoolean(ScreenRecognitionRadiusHook.KEY, false),
        )
    }
}
