package com.example.pocketrunner

import android.os.IBinder
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Dijalankan oleh Shizuku dengan hak shell (uid 2000).
 * Dipakai untuk: membuka app di layar virtual, menyuntik sentuhan/tombol, force-stop.
 */
class PrivilegedService : IPrivileged.Stub() {

    private var inputMgr: Any? = null
    private var injectMethod: Method? = null
    private var setDisplayIdMethod: Method? = null

    private var downX = 0f
    private var downY = 0f

    init {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputMgr = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            injectMethod = inputMgr!!.javaClass.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
            setDisplayIdMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        } catch (_: Throwable) {
            injectMethod = null // fallback ke perintah `input`
        }
    }

    private val safe = Regex("^[A-Za-z0-9._/\$]+$")

    private fun sh(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) { "ERR: ${e.message}" }

    override fun destroy() { exitProcess(0) }

    override fun startOnDisplay(displayId: Int, component: String): String {
        if (!safe.matches(component)) return "ERR: komponen tidak valid"
        return sh(
            "settings put global force_resizable_activities 1; " +
                "am start --display $displayId -n '$component'"
        )
    }

    override fun forceStop(pkg: String) {
        if (safe.matches(pkg)) sh("am force-stop '$pkg'")
    }

    override fun restoreSettings() {
        sh("settings put global force_resizable_activities 0")
    }

    override fun injectTouch(displayId: Int, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val m = injectMethod
        if (m == null) { fallbackTouch(displayId, action, x, y); return }
        try {
            val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            setDisplayIdMethod?.invoke(ev, displayId)
            m.invoke(inputMgr, ev, 0) // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            ev.recycle()
        } catch (_: Throwable) {
            fallbackTouch(displayId, action, x, y)
        }
    }

    override fun injectKey(displayId: Int, keyCode: Int) {
        val m = injectMethod
        if (m == null) { sh("input -d $displayId keyevent $keyCode"); return }
        try {
            val now = android.os.SystemClock.uptimeMillis()
            for (act in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val ev = KeyEvent(now, now, act, keyCode, 0)
                ev.source = InputDevice.SOURCE_KEYBOARD
                setDisplayIdMethod?.invoke(ev, displayId)
                m.invoke(inputMgr, ev, 0)
            }
        } catch (_: Throwable) {
            sh("input -d $displayId keyevent $keyCode")
        }
    }

    // Fallback sederhana (lebih lambat): tap / swipe lewat perintah `input`
    private fun fallbackTouch(displayId: Int, action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> { downX = x; downY = y }
            MotionEvent.ACTION_UP -> {
                val dx = Math.abs(x - downX); val dy = Math.abs(y - downY)
                if (dx < 20 && dy < 20) sh("input -d $displayId tap ${x.toInt()} ${y.toInt()}")
                else sh("input -d $displayId swipe ${downX.toInt()} ${downY.toInt()} ${x.toInt()} ${y.toInt()} 200")
            }
        }
    }
}
