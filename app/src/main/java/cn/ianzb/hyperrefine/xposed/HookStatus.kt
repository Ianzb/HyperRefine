package cn.ianzb.hyperrefine.xposed

/**
 * 单个 Hook 配置项在当前模块版本 / 当前开机内的生效状态。
 *
 * 状态来源是目标进程的定向广播：
 * - [WAITING]：目标进程尚未回报（通常是还没启动）；
 * - [APPLIED]：目标进程明确回报该键安装成功；
 * - [NOT_APPLIED]：目标进程已回报，但没有该键的成功记录（安装失败 / 被跳过也归入此类）；
 * - [UNAVAILABLE]：UI 前置条件不满足——作用域未勾选，或当前设备形态不在功能白名单内。
 *
 * “安装失败”不再单独显示灰色：它仍是“已回报但没装上”，应显示红色“未生效”。
 * 灰色只表示“还没轮到判断生效状态”，与目标进程回报无关。
 */
enum class HookStatus {
    WAITING,
    APPLIED,
    NOT_APPLIED,
    UNAVAILABLE,
}

/** [HookStatusStore] 的不可变快照，Compose 只订阅这一个 Flow。 */
data class HookStatusSnapshot(
    val applied: Set<String> = emptySet(),
    val pending: Set<String> = emptySet(),
    val reportedPackages: Set<String> = emptySet(),
)
