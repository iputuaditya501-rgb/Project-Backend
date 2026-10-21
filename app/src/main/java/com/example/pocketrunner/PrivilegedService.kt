package com.example.pocketrunner

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Dijalankan oleh Shizuku dengan hak shell (uid 2000).
 * Dipakai untuk: membuka app di layar virtual, menyuntik sentuhan/tombol, force-stop.
 */
class PrivilegedService : IPrivileged.Stub() {

    private var inputMgr: Any? = null
    private var injectMethod: Method? = null
    private var injectArgs = 2 // 2 = injectInputEvent(ev, mode), 3 = injectInputEventToTarget(ev, mode, uid)
    private var setDisplayIdMethod: Method? = null

    @Volatile private var statusMsg = "belum diinisialisasi"
    @Volatile private var lastError = ""

    private var resizableApplied = false

    private var downX = 0f
    private var downY = 0f

    init {
        bypassHiddenApi()
        try {
            val binder: IBinder = obtainInputBinder()
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val im = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            inputMgr = im

            val ms = im.javaClass.methods
            val m2 = ms.firstOrNull { it.name == "injectInputEvent" && it.parameterTypes.size == 2 }
            val m3 = ms.firstOrNull { it.name == "injectInputEventToTarget" && it.parameterTypes.size == 3 }
            when {
                m2 != null -> { injectMethod = m2; injectArgs = 2 }
                m3 != null -> { injectMethod = m3; injectArgs = 3 }
                else -> throw NoSuchMethodException("method inject tidak ada di IInputManager")
            }
            setDisplayIdMethod = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            statusMsg = "Input OK (inject$injectArgs)"
        } catch (e: Throwable) {
            injectMethod = null
            statusMsg = "Input FALLBACK (lambat): ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun obtainInputBinder(): IBinder {
        try {
            val b = SystemServiceHelper.getSystemService("input")
            if (b != null) return b
        } catch (_: Throwable) {}
        val sm = Class.forName("android.os.ServiceManager")
        return sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
    }

    /** Meta-reflection: membuka akses hidden API tanpa library tambahan. */
    private fun bypassHiddenApi() {
        try {
            val forName = Class::class.java.getDeclaredMethod("forName", String::class.java)
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod", String::class.java, arrayOf<Class<*>>()::class.java
            )
            val vmRuntimeClass = forName.invoke(null, "dalvik.system.VMRuntime") as Class<*>
            val getRuntime = getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", null) as Method
            val setExemptions = getDeclaredMethod.invoke(
                vmRuntimeClass, "setHiddenApiExemptions", arrayOf<Class<*>>(Array<String>::class.java)
            ) as Method
            val vmRuntime = getRuntime.invoke(null)
            setExemptions.invoke(vmRuntime, arrayOf("L"))
        } catch (_: Throwable) {}
    }

    private val safe = Regex("^[A-Za-z0-9._/\$]+$")

    private fun sh(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Throwable) { "ERR: ${e.message}" }

    private fun inject(ev: InputEvent): Boolean {
        val m = injectMethod ?: return false
        return try {
            if (injectArgs == 2) m.invoke(inputMgr, ev, 0) else m.invoke(inputMgr, ev, 0, -1)
            true
        } catch (e: Throwable) {
            lastError = (e.cause ?: e).toString()
            false
        }
    }

    override fun destroy() { exitProcess(0) }

    override fun status(): String =
        statusMsg + if (lastError.isNotEmpty()) " | error terakhir: $lastError" else ""

    override fun startOnDisplay(displayId: Int, component: String): String {
        if (!safe.matches(component)) return "ERR: komponen tidak valid"
        // Setting hanya diubah sekali per sesi (mengubahnya berulang bisa memicu perubahan konfigurasi)
        val prefix = if (!resizableApplied) {
            resizableApplied = true
            "settings put global force_resizable_activities 1; "
        } else ""
        // Intent gaya launcher (MAIN + LAUNCHER, NEW_TASK | RESET_TASK_IF_NEEDED):
        // kalau app sudah jalan, task lama hanya dibawa ke depan, TIDAK dibuat ulang.
        return sh(
            prefix + "am start --display $displayId " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-f 0x10200000 -n '$component'"
        )
    }

    override fun forceStop(pkg: String) {
        if (safe.matches(pkg)) sh("am force-stop '$pkg'")
    }

    override fun setMuted(pkg: String, muted: Boolean) {
        // Bisukan/aktifkan suara KHUSUS app ini lewat appops (tidak memengaruhi app lain)
        if (safe.matches(pkg)) sh("cmd appops set '$pkg' PLAY_AUDIO ${if (muted) "ignore" else "allow"}")
    }

    override fun restoreSettings() {
        sh("settings put global force_resizable_activities 0")
    }

    override fun injectTouch(displayId: Int, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        if (injectMethod != null) {
            try {
                val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
                ev.source = InputDevice.SOURCE_TOUCHSCREEN
                setDisplayIdMethod?.invoke(ev, displayId)
                val ok = inject(ev)
                ev.recycle()
                if (ok) return
            } catch (e: Throwable) {
                lastError = (e.cause ?: e).toString()
            }
        }
        fallbackTouch(displayId, action, x, y)
    }

    override fun injectKey(displayId: Int, keyCode: Int) {
        if (injectMethod != null) {
            try {
                val now = SystemClock.uptimeMillis()
                var ok = true
                for (act in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val ev = KeyEvent(now, now, act, keyCode, 0)
                    ev.source = InputDevice.SOURCE_KEYBOARD
                    setDisplayIdMethod?.invoke(ev, displayId)
                    ok = inject(ev) && ok
                }
                if (ok) return
            } catch (e: Throwable) {
                lastError = (e.cause ?: e).toString()
            }
        }
        sh("input -d $displayId keyevent $keyCode")
    }

    // Fallback (lambat, tiap tap = 1 proses baru): tap / swipe lewat perintah `input`
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
