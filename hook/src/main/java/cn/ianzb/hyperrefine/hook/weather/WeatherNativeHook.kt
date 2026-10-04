package cn.ianzb.hyperrefine.hook.weather

import cn.ianzb.hyperrefine.hook.nativehook.BaseNativeHook

/**
 * 天气高级外观解锁（原生 hook）。
 *
 * 在天气进程内 hook libc 属性读取入口，强制提升背景模糊 / 玻璃材质能力与算力等级，
 * 从而解锁「渐进模糊」与「动态天气（雨雪）」等高级特效。
 */
class WeatherNativeHook : BaseNativeHook() {

    override val libraryName: String = "nativehook"

    override val key: String = WeatherKeys.ADVANCED

    override val required: Boolean = false

    override val description: String = "解锁天气高级外观（渐进模糊 / 玻璃材质 / 动态天气特效）"
}
