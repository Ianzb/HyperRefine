package cn.ianzb.hyperrefine.experimental.packageinstaller

import android.content.Context
import androidx.compose.runtime.Composable
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.component.pref.HookSubPage
import cn.ianzb.hyperrefine.ui.screen.subpage.AppSubPage
import cn.ianzb.hyperrefine.ui.screen.subpage.OpenAppSubPage
import cn.ianzb.hyperrefine.ui.screen.subpage.openAppSubPage
import cn.ianzb.hyperrefine.ui.screen.subpage.rememberOpenAppSubPage

/** The app's only integration surface for this removable experimental feature. */
internal object InstallerExperiment {
    private const val ENTRY_KEY = "feature_packageinstaller"

    fun entrySpec() = OptionSpec(
        key = ENTRY_KEY,
        type = OptionType.ARROW,
        titleRes = R.string.section_packageinstaller,
    )

    fun registrySpecs(): List<OptionSpec> = listOf(entrySpec()) + packageInstallerSpecs()

    fun searchPage(context: Context, openInHost: OpenAppSubPage?) = HookSubPage(
        titleRes = R.string.section_packageinstaller,
        specs = packageInstallerSpecs(),
        onOpen = { key -> openAppSubPage(context, openInHost, AppSubPage.PackageInstaller, key) },
    )

    @Composable
    fun EntryCard() {
        val open = rememberOpenAppSubPage(AppSubPage.PackageInstaller)
        val entry = entrySpec()
        HookSectionCard(HookSection(specs = listOf(entry))) {
            HookOptionView(spec = entry, onArrowClick = { open() })
        }
    }

}
