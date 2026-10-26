package cn.ianzb.hyperrefine.hook.systemui.statusbar

/**
 * 把中文时段替换为 AM/PM。
 *
 * 只处理 12 小时制里 DateFormat 可能输出的时段词；24 小时制文本不含这些词，
 * 因此无需判断系统制式。替换可重复执行，已经包含 AM/PM 的文本保持原样。
 */
internal object ClockAmPm {
    private val LABELS = linkedMapOf(
        "凌晨" to "AM",
        "早上" to "AM",
        "上午" to "AM",
        "中午" to "PM",
        "下午" to "PM",
        "晚上" to "PM",
        "夜里" to "PM",
        "午夜" to "AM",
    )

    fun replace(text: CharSequence?): CharSequence? {
        if (text.isNullOrEmpty()) return text
        val raw = text.toString()
        var result = raw
        for ((from, to) in LABELS) {
            result = result.replace(from, to)
        }
        return if (result == raw) text else result
    }
}
