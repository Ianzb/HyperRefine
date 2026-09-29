package cn.ianzb.hyperrefine

import android.app.Application
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import cn.ianzb.hyperrefine.prefs.PrefsStore
import cn.ianzb.hyperrefine.ui.screen.features.featureSpecs
import cn.ianzb.hyperrefine.xposed.HookStatusStore
import cn.ianzb.hyperrefine.xposed.SafeModeReader
import cn.ianzb.hyperrefine.xposed.XposedServiceManager

class TemplateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PrefsStore.init(this)
        ConfigState.init(this)
        OptionRegistry.registerAll(featureSpecs())
        HookStatusStore.initialize(this)
        SafeModeReader.initialize(this)
        XposedServiceManager.init()
    }
}
