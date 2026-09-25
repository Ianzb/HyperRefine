package cn.ianzb.hyperrefine.hook.xposed

import io.github.libxposed.api.XposedInterface
import org.json.JSONObject
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 把每个 hook 的成功 / 失败状态写入模块共享数据目录，
 * App 端通过 [io.github.libxposed.service.XposedService.listRemoteFiles] / openRemoteFile 读取。
 *
 * 每个进程一个文件，避免多进程并发写冲突。
 */
object HookStatusWriter {

    private var xposed: XposedInterface? = null
    private var processName: String = "unknown"
    private val statuses = ConcurrentHashMap<String, Boolean>()

    fun init(module: XposedInterface) {
        xposed = module
    }

    fun startProcess(name: String) {
        processName = name
        statuses.clear()
    }

    fun record(hookId: String, success: Boolean) {
        statuses[hookId] = success
    }

    fun flush() {
        val module = xposed ?: return
        try {
            val json = JSONObject().apply {
                put("process", processName)
                put("timestamp", System.currentTimeMillis())
                put("status", JSONObject().apply {
                    statuses.forEach { (key, value) -> put(key, value) }
                })
            }
            val fileName = "hook_status_${processName.replace('.', '_').replace(':', '_')}.json"
            val descriptor = module.openRemoteFile(fileName)
            try {
                FileOutputStream(descriptor.fileDescriptor).use { out ->
                    out.write(json.toString().toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } finally {
                runCatching { descriptor.close() }
            }
        } catch (t: Throwable) {
            HookHelper.log("HookStatusWriter.flush failed", t)
        }
    }
}
