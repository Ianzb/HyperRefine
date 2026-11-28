package cn.ianzb.hyperrefine.ui.screen.features

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.connect.BluetoothHighestSampleRateHook
import cn.ianzb.hyperrefine.hook.connect.BluetoothAudioLoad
import cn.ianzb.hyperrefine.ui.component.AppArrowPreference
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
 * 「网络」二级页。
 */
class NetworkActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.network_section

    override val topBarActions: @Composable () -> Unit = NetworkActions

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        NetworkContent(isBlurEnabled, contentPadding)
    }
}

internal val NetworkActions: @Composable () -> Unit = {
    QuickActionsAction(listOf("com.android.systemui", BluetoothAudioLoad.TARGET))
}

@Composable
internal fun NetworkContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
    val scrollBehavior = LocalSubPageScrollBehavior.current
    val networkSection = HookSection(
        titleRes = R.string.network_section,
        specs = listOf(
            featureSpec(KEY_FIVE_G),
            featureSpec(BluetoothHighestSampleRateHook.KEY),
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
        searchSectionItem(networkSection) {
            HookSectionCard(networkSection) {
                networkSection.specs.forEach { HookOptionView(it) }
                if (!XposedServiceManager.isInScope(BluetoothAudioLoad.TARGET)) {
                    BluetoothScopeRequest()
                }
            }
        }
    }
}

@Composable
private fun BluetoothScopeRequest() {
    val context = LocalContext.current
    AppArrowPreference(
        title = stringResource(R.string.bluetooth_scope_request),
        summary = stringResource(R.string.bluetooth_scope_request_summary),
        enabled = XposedServiceManager.isActivated,
        onClick = {
            fun showResult(ok: Boolean, message: String?) {
                context.mainExecutor.execute {
                    val text = if (ok) {
                        context.getString(R.string.module_scope_request_success)
                    } else {
                        context.getString(R.string.module_scope_request_failed, message.orEmpty())
                    }
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            }
            runCatching {
                XposedServiceManager.ensureScope(listOf(BluetoothAudioLoad.TARGET), ::showResult)
            }.onFailure { showResult(false, it.localizedMessage) }
        },
    )
}
