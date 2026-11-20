package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.notification.NotificationKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.SearchLandingLazyColumn
import cn.ianzb.hyperrefine.ui.component.pref.searchSectionItem
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

class NotificationsActivity : BaseSubPageActivity() {
    override val titleRes = R.string.notifications_title
    override val topBarActions: @Composable () -> Unit = NotificationsActions

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        NotificationsContent(isBlurEnabled, contentPadding)
    }
}

internal val NotificationsActions: @Composable () -> Unit = {
        QuickActionsAction(listOf(NotificationKeys.SYSTEM_UI, NotificationKeys.SETTINGS, NotificationKeys.SYSTEM))
    }

@Composable
internal fun NotificationsContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
    val scrollBehavior = LocalSubPageScrollBehavior.current
    val section = HookSection(
        titleRes = R.string.notification_section,
        specs = notificationSpecs(featureSpecs()),
    )
    val listState = rememberLazyListState()
    SearchLandingLazyColumn(
        scrollBehavior = scrollBehavior,
        state = listState,
        modifier = Modifier.fillMaxSize().then(
            if (scrollBehavior != null) Modifier.pageScrollModifiers(
                showTopAppBar = true,
                topAppBarScrollBehavior = scrollBehavior,
                contentState = listState,
            ) else Modifier,
        ),
        contentPadding = contentPadding,
    ) {
        searchSectionItem(section) {
            HookSectionCard(section) {
                section.specs.forEach { HookOptionView(it) }
            }
        }
    }
}
