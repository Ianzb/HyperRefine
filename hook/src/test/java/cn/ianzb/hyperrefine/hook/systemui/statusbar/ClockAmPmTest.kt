package cn.ianzb.hyperrefine.hook.systemui.statusbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ClockAmPmTest {
    @Test
    fun replacesChinesePeriods() {
        assertEquals("AM 9:41", ClockAmPm.replace("上午 9:41"))
        assertEquals("PM 9:41", ClockAmPm.replace("下午 9:41"))
        assertEquals("AM 12:05", ClockAmPm.replace("凌晨 12:05"))
        assertEquals("PM 12:05", ClockAmPm.replace("中午 12:05"))
    }

    @Test
    fun replacesEveryOccurrence() {
        assertEquals("AM 9:41 / PM 9:41", ClockAmPm.replace("上午 9:41 / 下午 9:41"))
    }

    @Test
    fun leavesAlreadyEnglishAnd24HourTextAlone() {
        val english = "9:41 AM"
        val twentyFourHour = "21:41"
        assertSame(english, ClockAmPm.replace(english))
        assertSame(twentyFourHour, ClockAmPm.replace(twentyFourHour))
    }

    @Test
    fun handlesNullAndEmpty() {
        assertNull(ClockAmPm.replace(null))
        assertEquals("", ClockAmPm.replace(""))
    }
}
