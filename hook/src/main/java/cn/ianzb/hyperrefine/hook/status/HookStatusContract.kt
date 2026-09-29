package cn.ianzb.hyperrefine.hook.status

/**
 * Hook 生效状态上报协议（hook 进程 → App）。
 *
 * 采用**定向广播**而非「hook 侧写远程偏好」：
 * libxposed 远程文件在多数框架上对 hook 侧为只读，hook 侧无法写入；
 * 而广播由被注入的目标进程以自身 UID 发出，App 侧可通过发送者身份校验，
 * 得到「该进程确实装了哪些 hook」的正向证据。
 *
 * 触发时机：目标进程在 [cn.ianzb.hyperrefine.hook.base.BaseLoad] 中完成一组 hook
 * 注册后，合并本进程已成功安装的配置键，一次性上报。
 */
object HookStatusContract {

    /** 模块包名。 */
    const val MODULE_PACKAGE = "cn.ianzb.hyperrefine"

    /** App 侧接收器完整类名（与清单注册一致）。 */
    const val RECEIVER_CLASS = "cn.ianzb.hyperrefine.xposed.HookStatusReceiver"

    /** 上报动作。 */
    const val ACTION_HOOKS_ACTIVE = "cn.ianzb.hyperrefine.action.HOOKS_ACTIVE"

    /** 来源目标包名。 */
    const val EXTRA_SOURCE_PACKAGE = "source_package"

    /** 来源进程名。 */
    const val EXTRA_SOURCE_PROCESS = "source_process"

    /** 模块 versionCode；0 表示未能解析（App 侧按未知处理）。 */
    const val EXTRA_MODULE_VERSION = "module_version"

    /** 本进程已成功安装的配置键集合（`ArrayList<String>`）。 */
    const val EXTRA_INSTALLED_KEYS = "installed_keys"
}
