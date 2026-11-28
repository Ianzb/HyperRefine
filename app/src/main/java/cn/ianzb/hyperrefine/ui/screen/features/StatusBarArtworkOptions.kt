package cn.ianzb.hyperrefine.ui.screen.features

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconArtwork
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.ui.component.AppArrowPreference
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.xposed.XposedServiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun StatusBarArtworkOptions(id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = StatusBarIconArtwork.key(id)
    val current = ConfigState.string(key, "")
    val enabled = ConfigState.bool(StatusBarIconKeys.ENABLED, false) && XposedServiceManager.isActivated
    var importing by remember(id) { mutableStateOf(false) }

    fun delete(name: String) {
        if (!StatusBarIconArtwork.validFileName(name)) return
        val service = XposedServiceManager.getService() ?: return
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { service.deleteRemoteFile(name) } }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || importing) return@rememberLauncherForActivityResult
        val service = XposedServiceManager.getService() ?: return@rememberLauncherForActivityResult
        val file = "sbicon-${UUID.randomUUID().toString().replace("-", "")}"
        importing = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val size = StatusBarIconArtwork.SIZE
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        val width = info.size.width
                        val height = info.size.height
                        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 40_000_000L)
                        val factor = size.toFloat() / maxOf(width, height)
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetSize(
                            (width * factor).roundToInt().coerceIn(1, size),
                            (height * factor).roundToInt().coerceIn(1, size),
                        )
                    }
                    val padded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    try {
                        val left = (size - bitmap.width) / 2
                        val top = (size - bitmap.height) / 2
                        val bounds = Rect(left, top, left + bitmap.width, top + bitmap.height)
                        Canvas(padded).drawBitmap(bitmap, null, bounds, Paint(Paint.FILTER_BITMAP_FLAG))
                        ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(file)).use { output ->
                            check(padded.compress(Bitmap.CompressFormat.PNG, 100, output))
                        }
                    } finally {
                        padded.recycle()
                        bitmap.recycle()
                    }
                }
                ConfigState.set(key, file)
                delete(current)
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { service.deleteRemoteFile(file) }
                }
                if (error is CancellationException) throw error
                Toast.makeText(context, R.string.sb_icon_artwork_failed, Toast.LENGTH_LONG).show()
            } finally {
                importing = false
            }
        }
    }

    HookSectionCard(HookSection(titleRes = R.string.sb_icon_artwork, specs = emptyList())) {
        AppArrowPreference(
            title = stringResource(R.string.sb_icon_artwork_import),
            summary = stringResource(
                if (current.isEmpty()) R.string.sb_icon_artwork_summary else R.string.sb_icon_artwork_imported,
            ),
            enabled = enabled && !importing,
            onClick = { picker.launch("image/*") },
        )
        if (current.isNotEmpty()) {
            AppArrowPreference(
                title = stringResource(R.string.sb_icon_artwork_reset),
                enabled = enabled && !importing,
                onClick = {
                    ConfigState.set(key, "")
                    delete(current)
                },
            )
        }
    }
}
