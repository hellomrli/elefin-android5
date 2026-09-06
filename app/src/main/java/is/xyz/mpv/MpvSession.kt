package `is`.xyz.mpv

import android.content.Context
import android.view.Surface
import com.flex.elefin.player.NativeSessionGate

/** All JNI access belongs to one view. MPVLib method names remain unchanged for the JNI ABI. */
class MpvSession {
    companion object {
        private val gate = NativeSessionGate()
    }

    private var used = false

    fun create(context: Context) {
        check(MPVLib.isAvailable()) { "MPV native libraries are unavailable" }
        check(!used) { "A released MPV session cannot be reused" }
        used = true
        gate.create(this) { MPVLib.create(context.applicationContext) }
    }

    fun init() { gate.call(this) { MPVLib.init() } }
    fun destroy() = gate.destroy(this) { MPVLib.destroy() }
    fun attachSurface(surface: Surface) { gate.call(this) { MPVLib.attachSurface(surface) } }
    fun detachSurface() { gate.call(this) { MPVLib.detachSurface() } }
    fun command(cmd: Array<out String>) { gate.call(this) { MPVLib.command(cmd) } }
    fun setOptionString(name: String, value: String): Int? = gate.call(this) { MPVLib.setOptionString(name, value) }
    fun getPropertyInt(name: String): Int? = gate.call(this) { MPVLib.getPropertyInt(name) }
    fun getPropertyDouble(name: String): Double? = gate.call(this) { MPVLib.getPropertyDouble(name) }
    fun getPropertyBoolean(name: String): Boolean? = gate.call(this) { MPVLib.getPropertyBoolean(name) }
    fun getPropertyString(name: String): String? = gate.call(this) { MPVLib.getPropertyString(name) }
    fun setPropertyInt(name: String, value: Int) { gate.call(this) { MPVLib.setPropertyInt(name, value) } }
    fun setPropertyDouble(name: String, value: Double) { gate.call(this) { MPVLib.setPropertyDouble(name, value) } }
    fun setPropertyBoolean(name: String, value: Boolean) { gate.call(this) { MPVLib.setPropertyBoolean(name, value) } }
    fun setPropertyString(name: String, value: String) { gate.call(this) { MPVLib.setPropertyString(name, value) } }
    fun observeProperty(name: String, format: Int) { gate.call(this) { MPVLib.observeProperty(name, format) } }
}
