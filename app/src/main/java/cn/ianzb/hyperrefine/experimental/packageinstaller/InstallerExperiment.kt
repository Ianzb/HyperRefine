package cn.ianzb.hyperrefine.experimental.packageinstaller

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.component.pref.HookSubPage
import cn.ianzb.hyperrefine.ui.component.pref.withSearchTarget

/** The app's only integration surface for this removable experimental feature. */
internal object InstallerExperiment {
    private const val ENTRY_KEY = "feature_packageinstaller"

    fun entrySpec() = OptionSpec(
        key = ENTRY_KEY,
        type = OptionType.ARROW,
        titleRes = R.string.section_packageinstaller,
    )

    fun registrySpecs(): List<OptionSpec> = listOf(entrySpec()) + packageInstallerSpecs()

    fun searchPage(context: Context) = HookSubPage(
        titleRes = R.string.section_packageinstaller,
        specs = packageInstallerSpecs(),
        onOpen = { key -> open(context, key) },
    )

    @Composable
    fun EntryCard() {
        val context = LocalContext.current
        val entry = entrySpec()
        HookSectionCard(HookSection(specs = listOf(entry))) {
            HookOptionView(spec = entry, onArrowClick = { open(context) })
        }
    }

    private fun open(context: Context, searchTarget: String? = null) {
        context.startActivity(Intent(context, PackageInstallerActivity::class.java).withSearchTarget(searchTarget))
    }
}
