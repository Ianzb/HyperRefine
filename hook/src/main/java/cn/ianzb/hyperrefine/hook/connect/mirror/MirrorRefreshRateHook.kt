package cn.ianzb.hyperrefine.hook.connect.mirror

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Display
import android.view.Surface
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.connect.MirrorKeys
import cn.ianzb.hyperrefine.hook.device.DeviceContext
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 投屏刷新率请求（发送端 + 接收端）。
 *
 * 移植自「妙享桌面增强」（酷安 @ayyya）的 `HookEntry.hookRefreshRate` / `hookDisplayConfiguration`
 * 与 `r3.u`：统一把帧率协商值、解码 Surface、编码器 MediaFormat、窗口 preferredRefreshRate
 * 覆写为用户选择的刷新率（60 / 90 / 120）。
 *
 * 目标进程：`com.xiaomi.mirror`。
 */
class MirrorRefreshRateHook : BaseHook() {

    override val key: String = MirrorKeys.REFRESH_RATE

    private lateinit var classLoader: ClassLoader
    private var modern: Boolean = false

    override fun init() {
        classLoader = target.classLoader ?: throw IllegalStateException("$tag: classLoader unavailable")
        modern = MirrorCompat.isModern(classLoader, target.appVersionCode)
        if (DeviceContext.current.isTablet) {
            installReceiver()
        } else {
            installSender()
        }
    }

    // ---------------- 接收端 ----------------

    private fun installReceiver() {
        val sink = firstClass(MIRROR_CONTROL_SINK)
            ?: throw HookSkippedException("MirrorControlSink not found")

        for (ctor in sink.declaredConstructors) {
            HookHelper.hookAfter(ctor) { applyFps(it.thisObject) }
        }
        hookBeforeAll(sink, "setVideoSinkOption") { param ->
            if (param.args.getOrNull(0) == 259 && param.args.getOrNull(1) is Int) {
                param.setArg(1, MirrorConfig.fps())
            }
        }
        hookBeforeAll(sink, "startMirror") { param ->
            applyFps(param.thisObject)
            val target = param.thisObject
            if (target != null) {
                runCatching { Reflect.callMethod(target, "setMirrorSinkOption", 37, MirrorConfig.fps()) }
                    .onFailure { HookHelper.log("$tag: sink option 37 update failed", it) }
            }
        }

        installDisplayConfig()

        firstClass(MIRROR_CONTROL)?.let { control ->
            hookBeforeAll(control, "setDeviceInfo") { param ->
                val type = param.args.getOrNull(1) as? Int
                if ((type == 259 || type == 275) && param.args.getOrNull(2) is Int) {
                    param.setArg(2, MirrorConfig.fps())
                }
            }
        }
        HookHelper.log("$tag: receiver refresh-rate hooks installed (modern=$modern)")
    }

    private fun applyFps(obj: Any?) {
        MirrorCompat.setIntFieldIfPresent(obj, "mSinkVideoMaxFps", MirrorConfig.fps())
        MirrorCompat.setIntFieldIfPresent(obj, "mSinkVideoCurrentFps", MirrorConfig.fps())
    }

    private fun installDisplayConfig() {
        val config = firstClass(
            *(if (modern) arrayOf("s3.I", "r3.J") else arrayOf("r3.J", "s3.I")),
        )
        if (config != null) {
            for (ctor in config.declaredConstructors) {
                HookHelper.hookAfter(ctor) { MirrorCompat.setIntFieldIfPresent(it.thisObject, "a", MirrorConfig.fps()) }
            }
            hookBeforeAll(config, "K") { param ->
                if (param.args.size == 1) param.setArg(0, MirrorConfig.fps())
            }
        }
        val builder = firstClass(
            *(if (modern) arrayOf("s3.I\$b", "r3.J\$b") else arrayOf("r3.J\$b", "s3.I\$b")),
        )
        if (builder != null) {
            for (ctor in builder.declaredConstructors) {
                HookHelper.hookAfter(ctor) { MirrorCompat.setIntFieldIfPresent(it.thisObject, "a", MirrorConfig.fps()) }
            }
            hookBeforeAll(builder, "h") { param ->
                if (param.args.size == 1) param.setArg(0, MirrorConfig.fps())
            }
            hookAfterAll(builder, "a") { MirrorCompat.setIntFieldIfPresent(it.thisObject, "a", MirrorConfig.fps()) }
        }
    }

    // ---------------- 发送端 ----------------

    private fun installSender() {
        runCatching {
            val method = Display::class.java.getDeclaredMethod("getRefreshRate")
            HookHelper.hookAfter(method) { it.setResultValue(MirrorConfig.fps().toFloat()) }
        }

        installSource()
        installEncoder()
        installStartCapture()
        installMediaCodec()
        HookHelper.log("$tag: sender refresh-rate hooks installed (modern=$modern)")
    }

    private fun installSource() {
        val source = firstClass(MIRROR_CONTROL_SOURCE) ?: return
        for (ctor in source.declaredConstructors) {
            HookHelper.hookAfter(ctor) { param ->
                sourceFpsFields(param.thisObject)
                sourceSetOption(param.thisObject)
            }
        }
        for (name in arrayOf("initVideo", "initVideo2", "initVideoCapture", "initVideoCapture2")) {
            hookBeforeAll(source, name) { param ->
                val index = if (name.endsWith("2")) param.args.size - 2 else param.args.size - 1
                if (index in param.args.indices && param.args[index] is Int) param.setArg(index, MirrorConfig.fps())
                sourceFpsFields(param.thisObject)
            }
        }
        hookBeforeAll(source, "setMirrorSourceOption") { param ->
            if (param.args.getOrNull(0) == 37 && param.args.getOrNull(1) is Int) {
                param.setArg(1, MirrorConfig.fps())
            }
        }
    }

    private fun sourceFpsFields(obj: Any?) {
        MirrorCompat.setIntFieldIfPresent(obj, "support_maxfps", MirrorConfig.fps())
        MirrorCompat.setIntFieldIfPresent(obj, "negotiate_fps", MirrorConfig.fps())
    }

    private fun sourceSetOption(obj: Any?) {
        if (obj == null) return
        runCatching { Reflect.callMethod(obj, "setMirrorSourceOption", 37, MirrorConfig.fps()) }
            .onFailure { HookHelper.log("$tag: source refresh option failed", it) }
    }

    private fun installEncoder() {
        val encoder = firstClass(
            *(if (modern) arrayOf("u3.H", "t3.H") else arrayOf("t3.H", "u3.H")),
        ) ?: return
        for (ctor in encoder.declaredConstructors) {
            HookHelper.hookAfter(ctor) { encoderFps(it.thisObject) }
        }
        if (!modern) {
            hookAfterAll(encoder, "U0") { it.setResultValue(MirrorConfig.fps()) }
        }
        hookBeforeAll(encoder, if (modern) "u1" else "q1") { param ->
            if (param.args.getOrNull(0) is Int) param.setArg(0, MirrorConfig.fps())
        }
        hookBeforeAll(encoder, "t1") { param ->
            if (param.args.getOrNull(1) is Int) param.setArg(1, MirrorConfig.fps())
        }
        for (name in arrayOf("N0", "Q0")) {
            hookAfterAll(encoder, name) { param ->
                (param.result as? MediaFormat)?.let { applyFormatFps(it) }
            }
        }
        hookAfterAll(encoder, "X") { encoderFps(it.thisObject) }
    }

    private fun encoderFps(obj: Any?) {
        if (obj == null) return
        MirrorCompat.setIntFieldIfPresent(obj, "T", MirrorConfig.fps())
        (runCatching { Reflect.getObjectField(obj, "N") }.getOrNull() as? MediaFormat)?.let { applyFormatFps(it) }
    }

    private fun installStartCapture() {
        val names = if (modern) {
            arrayOf("s3.z", "s3.m", "r3.A", "r3.m")
        } else {
            arrayOf("r3.A", "r3.m", "s3.z", "s3.m")
        }
        for (name in names) {
            firstClass(name)?.let { clazz ->
                hookBeforeAll(clazz, "onStartCapture") { param ->
                    if (param.args.size >= 3 && param.args[2] is Int) param.setArg(2, MirrorConfig.fps())
                }
            }
        }
    }

    private fun installMediaCodec() {
        runCatching {
            MediaCodec::class.java.declaredMethods.filter { it.name == "configure" }.forEach { method ->
                HookHelper.hookBefore(method) { param ->
                    (param.args.getOrNull(0) as? MediaFormat)?.let { applyFormatFps(it) }
                }
            }
            MediaCodec::class.java.declaredMethods.filter { it.name == "createInputSurface" }.forEach { method ->
                HookHelper.hookAfter(method) { param ->
                    MirrorCompat.requestSurfaceFrameRate(param.result as? Surface, MirrorConfig.fps())
                }
            }
        }
    }

    private fun applyFormatFps(format: MediaFormat) {
        val mime = runCatching { format.getString("mime") }.getOrNull() ?: return
        if (!mime.startsWith("video/")) return
        val fps = MirrorConfig.fps()
        runCatching {
            format.setInteger("frame-rate", fps)
            format.setFloat("max-fps-to-encoder", fps.toFloat())
            format.setLong("repeat-previous-frame-after", 2_000_000L / fps)
            HookHelper.log("$tag: MediaCodec format fps = $fps")
        }
    }

    // ---------------- 工具 ----------------

    private fun hookAfterAll(clazz: Class<*>, name: String, callback: (HookParam) -> Unit) {
        clazz.declaredMethods.filter { it.name == name }.forEach { HookHelper.hookAfter(it, callback = callback) }
    }

    private fun hookBeforeAll(clazz: Class<*>, name: String, callback: (HookParam) -> Unit) {
        clazz.declaredMethods.filter { it.name == name }.forEach { HookHelper.hookBefore(it, callback = callback) }
    }

    private fun firstClass(vararg names: String): Class<*>? =
        names.firstNotNullOfOrNull { Reflect.findClassIfExists(it, classLoader) }

    private companion object {
        const val MIRROR_CONTROL_SINK = "com.xiaomi.mirrorcontrol.MirrorControlSink"
        const val MIRROR_CONTROL = "com.xiaomi.mirrorcontrol.MirrorControl"
        const val MIRROR_CONTROL_SOURCE = "com.xiaomi.mirrorcontrol.MirrorControlSource"
    }
}
