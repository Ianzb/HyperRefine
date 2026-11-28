package cn.ianzb.hyperrefine.hook.systemui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeTimerHookTest {
    @Test
    fun includesOffAndSupportsCustomCounts() {
        assertArrayEquals(intArrayOf(0, 900), VolumeTimerHook.parse("15"))
        assertArrayEquals(intArrayOf(0, 900, 1800, 5400), VolumeTimerHook.parse("15， 30,90"))
        assertArrayEquals(intArrayOf(0, 86400), VolumeTimerHook.parse("1440"))
    }

    @Test
    fun rejectsInvalidConfigurations() {
        for (input in listOf("", "0", "1441", "30,15", "30,30", "10,", "1,2,3,4,5,6,7,8,9")) {
            assertNull(input, VolumeTimerHook.parse(input))
        }
    }
}
