package cn.ianzb.hyperrefine.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle

/** The card owns the gap before the next group; the title uses Miuix's default margins. */
@Composable
internal fun AppSectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) SmallTitle(text = title)
        Card(
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            colors = colors,
            content = content,
        )
    }
}
