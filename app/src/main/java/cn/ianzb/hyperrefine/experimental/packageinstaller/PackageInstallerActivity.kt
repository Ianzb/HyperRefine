package cn.ianzb.hyperrefine.experimental.packageinstaller

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.packageinstaller.PackageInstallerKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.SearchLandingLazyColumn
import cn.ianzb.hyperrefine.ui.component.pref.searchSectionItem
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

class PackageInstallerActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_packageinstaller

    override val topBarActions: @Composable () -> Unit = PackageInstallerActions

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        PackageInstallerContent(contentPadding)
    }
}

internal val PackageInstallerActions: @Composable () -> Unit = { QuickActionsAction(listOf(PackageInstallerKeys.PACKAGE_NAME)) }

@Composable
internal fun PackageInstallerContent(contentPadding: PaddingValues) {
    val scrollBehavior = LocalSubPageScrollBehavior.current
    val section = HookSection(specs = packageInstallerSpecs())
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
        item {
            // 声明卡片沿用安全模式页样式：灰色脚注正文，不使用组件标题样式。
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                MiuixText(
                    text = stringResource(R.string.installer_experiment_notice),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        searchSectionItem(section) {
            HookSectionCard(section) {
                section.specs.forEach { HookOptionView(it) }
            }
        }
        item { InstallerDiagnosticCard() }
    }
}
