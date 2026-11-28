package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.Collections
import java.util.WeakHashMap

object StatusBarIconArtwork {
    const val SIZE = 96
    private val filePattern = Regex("sbicon-[0-9a-f]{32}")

    fun supports(component: StatusBarIconComponent): Boolean =
        component.slot != null && component.reorderable && !component.special && !component.textual &&
            component.id != StatusBarIconRegistry.WIFI && component.id != StatusBarIconRegistry.MOBILE

    // ConfigBackup excludes device-local file references under runtime_.
    fun key(id: String) = "runtime_sb_icon_artwork_$id"
    fun validFileName(name: String) = filePattern.matches(name)

    internal fun install(iconView: Class<*>) {
        val assignments = StatusBarIconRegistry.components.asSequence()
            .filter(::supports)
            .mapNotNull { component ->
                val slot = component.slot ?: return@mapNotNull null
                val file = HookPrefs.getString(key(component.id), "").orEmpty()
                if (validFileName(file)) slot to file else null
            }.toMap()
        if (assignments.isEmpty()) return

        val getIcon = iconView.declaredMethods.singleOrNull {
            it.name == "getIcon" && it.parameterTypes.singleOrNull()?.name ==
                "com.android.internal.statusbar.StatusBarIcon" && Drawable::class.java.isAssignableFrom(it.returnType)
        } ?: return
        val getSlot = Reflect.findMethod(iconView, "getSlot")
        val update = Reflect.findMethodIfExists(iconView, "updateDrawable", Boolean::class.javaPrimitiveType!!)
        val pending = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        val main = Handler(Looper.getMainLooper())
        var bitmaps: Map<String, Bitmap>? = null
        HookHelper.hookAfter(getIcon) { param ->
            val view = param.thisObject as? View ?: return@hookAfter
            val native = param.result as? Drawable ?: return@hookAfter
            if (native.intrinsicWidth <= 0 || native.intrinsicHeight <= 0) return@hookAfter
            val slot = getSlot.invoke(view) as? String ?: return@hookAfter
            if (slot !in assignments) return@hookAfter
            val ready = bitmaps
            if (ready == null) {
                if (update != null) pending.add(view)
            } else {
                val bitmap = ready[slot] ?: return@hookAfter
                val drawable = BitmapDrawable(view.resources, bitmap).apply { isFilterBitmap = true }
                param.setResultValue(ArtworkDrawable(drawable, native.intrinsicWidth, native.intrinsicHeight))
            }
        }
        Thread({
            val loaded = assignments.mapNotNull { (slot, name) -> load(name)?.let { slot to it } }.toMap()
            main.post {
                bitmaps = loaded
                pending.toList().forEach { view ->
                    runCatching { update?.invoke(view, false) }
                        .onFailure { HookHelper.log("StatusBarIconArtwork: icon refresh failed", it) }
                }
                pending.clear()
            }
        }, "HyperRefine-StatusBarArtwork").apply { isDaemon = true }.start()
    }

    private fun load(name: String): Bitmap? = runCatching {
        ParcelFileDescriptor.AutoCloseInputStream(HookHelper.openRemoteFile(name)).use { input ->
            val data = input.readNBytes(SIZE * SIZE * 5)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth != SIZE || bounds.outHeight != SIZE) return null
            BitmapFactory.decodeByteArray(data, 0, data.size)
        }
    }.getOrNull()

    private class ArtworkDrawable(
        bitmap: BitmapDrawable,
        private val width: Int,
        private val height: Int,
    ) : DrawableWrapper(bitmap) {
        override fun onBoundsChange(bounds: Rect) {
            val side = minOf(bounds.width(), bounds.height())
            val left = bounds.left + (bounds.width() - side) / 2
            val top = bounds.top + (bounds.height() - side) / 2
            drawable?.setBounds(left, top, left + side, top + side)
        }

        override fun getIntrinsicWidth() = width
        override fun getIntrinsicHeight() = height
    }
}
