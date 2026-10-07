package cn.ianzb.hyperrefine.hook.base

import cn.ianzb.hyperrefine.hook.connect.MiLinkLoad
import cn.ianzb.hyperrefine.hook.connect.XiaomiMirrorLoad
import cn.ianzb.hyperrefine.hook.browser.BrowserLoad
import cn.ianzb.hyperrefine.hook.home.HomeLoad
import cn.ianzb.hyperrefine.hook.misound.MiSoundLoad
import cn.ianzb.hyperrefine.hook.miuix.MiuixAppLoad
import cn.ianzb.hyperrefine.hook.passkey.PasskeyLoad
import cn.ianzb.hyperrefine.hook.securitycenter.SecurityCenterLoad
import cn.ianzb.hyperrefine.hook.systemui.SystemUiLoad
import cn.ianzb.hyperrefine.hook.voiceassist.VoiceAssistLoad
import cn.ianzb.hyperrefine.hook.weather.WeatherLoad

/**
 * 目标 Load 注册表。
 *
 * 新增目标包时，在这里登记对应的 [BaseLoad] 实现即可。
 */
object HookEntryRegistry {

    /** 全局通配：声明该目标的 Load 会安装到任意进程（配合 `android` 框架作用域）。 */
    const val GLOBAL = "*"

    private val loads: List<BaseLoad> = listOf(
        HomeLoad(),
        SystemUiLoad(),
        SecurityCenterLoad(),
        MiLinkLoad(),
        XiaomiMirrorLoad(),
        MiSoundLoad(),
        MiuixAppLoad(),
        WeatherLoad(),
        PasskeyLoad(),
        BrowserLoad(),
        VoiceAssistLoad(),
    )

    fun loadsFor(packageName: String): List<BaseLoad> =
        loads.filter {
            it.targetPackages.contains(packageName) || it.targetPackages.contains(GLOBAL)
        }

    /** 需要在 system_server 中安装的 Load（`targetPackages` 含 `system`）。 */
    fun systemServerLoads(): List<BaseLoad> =
        loads.filter { it.targetPackages.contains("system") }

    fun all(): List<BaseLoad> = loads
}
