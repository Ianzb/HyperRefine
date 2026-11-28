package cn.ianzb.hyperrefine.hook.connect

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

class BluetoothHighestSampleRateHook : BaseHook() {
    override val key = KEY

    override fun init() {
        val loader = target.classLoader
        val service = Reflect.findClass("com.android.bluetooth.a2dp.A2dpService", loader)
        val configClass = Reflect.findClass("android.bluetooth.BluetoothCodecConfig", loader)
        val statusClass = Reflect.findClass("android.bluetooth.BluetoothCodecStatus", loader)
        val deviceClass = BluetoothDevice::class.java
        val activeDevice = Reflect.findMethod(service, "getActiveDevice")
        val connectionState = Reflect.findMethod(service, "getConnectionState", deviceClass)
        val codecStatus = Reflect.findMethod(service, "getCodecStatus", deviceClass)
        val currentConfig = Reflect.findMethod(statusClass, "getCodecConfig")
        val selectable = Reflect.findMethod(statusClass, "getCodecsSelectableCapabilities")
        val mandatory = Reflect.findMethod(configClass, "isMandatoryCodec")
        val setPreference = Reflect.findMethod(service, "setCodecConfigPreference", deviceClass, configClass)
        val sharing = Reflect.findMethod(service, "isMultiA2dpSupported")
        val endingShare = Reflect.findMethod(service, "isMultiA2dpExiting")
        val setActive = Reflect.findMethod(service, "setActiveDevice", deviceClass)
        val boolean = Boolean::class.javaPrimitiveType!!
        val removeActive = Reflect.findMethod(service, "removeActiveDevice", boolean)
        val codecUpdated = Reflect.findMethod(service, "codecConfigUpdated", deviceClass, statusClass, boolean)
        val int = Int::class.javaPrimitiveType!!
        val long = Long::class.javaPrimitiveType!!
        val constructor = configClass.getConstructor(int, int, int, int, int, long, long, long, long)
        val type = Reflect.findMethod(configClass, "getCodecType")
        val priority = Reflect.findMethod(configClass, "getCodecPriority")
        val sampleRate = Reflect.findMethod(configClass, "getSampleRate")
        val bits = Reflect.findMethod(configClass, "getBitsPerSample")
        val channel = Reflect.findMethod(configClass, "getChannelMode")
        val specific1 = Reflect.findMethod(configClass, "getCodecSpecific1")
        val specific2 = Reflect.findMethod(configClass, "getCodecSpecific2")
        val specific3 = Reflect.findMethod(configClass, "getCodecSpecific3")
        val specific4 = Reflect.findMethod(configClass, "getCodecSpecific4")
        val main = Handler(Looper.getMainLooper())
        var currentService: Any? = null
        var currentDevice: BluetoothDevice? = null
        val handledCodecs = mutableSetOf<Int>()
        var enabled = HookPrefs.getBoolean(key, false)
        val apply = Runnable {
            runCatching {
                val host = currentService ?: return@runCatching
                val device = currentDevice ?: return@runCatching
                if (!HookPrefs.getBoolean(key, false) ||
                    activeDevice.invoke(host) != device ||
                    connectionState.invoke(host, device) != BluetoothProfile.STATE_CONNECTED ||
                    sharing.invoke(host) == true || endingShare.invoke(host) == true
                ) return@runCatching

                val status = codecStatus.invoke(host, device) ?: return@runCatching
                val config = currentConfig.invoke(status) ?: return@runCatching
                val codec = type.invoke(config) as Int
                if (codec in handledCodecs) return@runCatching
                val capabilities = selectable.invoke(status) as? List<*> ?: return@runCatching
                val codecCapabilities = capabilities.filter { it != null && type.invoke(it) == codec }
                if (codecCapabilities.isEmpty() || capabilities.none { it != null && mandatory.invoke(it) == true }) {
                    return@runCatching
                }

                val currentRate = sampleRate.invoke(config) as Int
                val currentBits = bits.invoke(config) as Int
                val currentChannel = channel.invoke(config) as Int
                val supported = codecCapabilities.fold(0) { mask, capability ->
                    val supportsBits = currentBits == 0 || ((bits.invoke(capability) as Int) and currentBits) != 0
                    val supportsChannel = currentChannel == 0 ||
                        ((channel.invoke(capability) as Int) and currentChannel) != 0
                    if (supportsBits && supportsChannel) mask or (sampleRate.invoke(capability) as Int) else mask
                }
                // Capabilities can be incomplete during the first codec callback.
                if (supported == 0) return@runCatching
                val rate = preferredSampleRate(supported, currentRate)
                handledCodecs.add(codec)
                if (rate == currentRate) return@runCatching

                val request = constructor.newInstance(
                    codec, priority.invoke(config), rate, currentBits,
                    currentChannel, specific1.invoke(config), specific2.invoke(config), specific3.invoke(config),
                    specific4.invoke(config),
                )
                setPreference.invoke(host, device, request)
                HookHelper.log("$tag: requested sample rate for codec $codec, flag $currentRate -> $rate")
            }.onFailure { HookHelper.log("$tag: sample rate update failed", it) }
        }

        fun queue(host: Any, device: BluetoothDevice?, activeChange: Boolean, codec: Int? = null) {
            main.post {
                if (!activeChange && (host !== currentService || device != currentDevice)) return@post
                if (codec in handledCodecs) return@post
                if (host !== currentService || device != currentDevice) {
                    currentService = host
                    currentDevice = device
                    handledCodecs.clear()
                }
                main.removeCallbacks(apply)
                if (device != null && enabled) main.postDelayed(apply, SETTLE_MS)
            }
        }

        HookHelper.hookAfter(setActive) { param ->
            val host = param.thisObject ?: return@hookAfter
            val device = param.args.firstOrNull() as? BluetoothDevice ?: return@hookAfter
            if (param.result == true) queue(host, device, activeChange = true)
        }
        HookHelper.hookAfter(removeActive) { param ->
            val host = param.thisObject ?: return@hookAfter
            queue(host, null, activeChange = true)
        }
        HookHelper.hookAfter(codecUpdated) { param ->
            val host = param.thisObject ?: return@hookAfter
            val device = param.args.firstOrNull() as? BluetoothDevice ?: return@hookAfter
            val codec = runCatching {
                val status = param.args.getOrNull(1) ?: return@runCatching null
                val config = currentConfig.invoke(status) ?: return@runCatching null
                type.invoke(config) as? Int
            }.getOrNull()
            queue(host, device, activeChange = false, codec = codec)
        }
        HookPrefs.registerListener {
            main.post {
                val next = HookPrefs.getBoolean(key, false)
                if (next == enabled) return@post
                enabled = next
                handledCodecs.clear()
                main.removeCallbacks(apply)
                if (next && currentDevice != null) main.postDelayed(apply, SETTLE_MS)
            }
        }
    }

    companion object {
        const val KEY = "bluetooth_highest_sample_rate"
        private const val SETTLE_MS = 250L
        // HyperOS Settings exposes 44.1, 48, 88.2, 96 and 192 kHz in these flags.
        private const val SETTINGS_SAMPLE_RATES = 1 or 2 or 4 or 8 or 32

        internal fun preferredSampleRate(selectableRates: Int, currentRate: Int): Int {
            val highest = Integer.highestOneBit(selectableRates and SETTINGS_SAMPLE_RATES)
            return if (highest > currentRate) highest else currentRate
        }
    }
}
