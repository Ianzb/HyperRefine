package cn.ianzb.hyperrefine.hook.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class BluetoothHighestSampleRateHookTest {
    @Test
    fun prefersTheHighestKnownSelectableRateWithoutDowngrading() {
        assertEquals(32, BluetoothHighestSampleRateHook.preferredSampleRate(0x2b, 2))
        assertEquals(8, BluetoothHighestSampleRateHook.preferredSampleRate(0x0b, 2))
        assertEquals(2, BluetoothHighestSampleRateHook.preferredSampleRate(0x41, 2))
        assertEquals(8, BluetoothHighestSampleRateHook.preferredSampleRate(0, 8))
    }
}
