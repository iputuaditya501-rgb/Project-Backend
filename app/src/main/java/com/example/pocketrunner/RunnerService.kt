package com.example.pocketrunner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import rikka.shizuku.Shizuku

/**
 * Pemilik layar virtual. Layar ini TIDAK PERNAH dihancurkan selama service hidup:
 * - mode langsung  : surface layar virtual = SurfaceView di PreviewActivity
 * - mode backend   : surface layar virtual = ImageReader dummy (layar tetap "menyala",
 *                    app di dalamnya tidak pernah pause/refresh)
 */
class RunnerService : Service() {

    companion object {
        private const val TAG = "RunnerService"
        private const val CHANNEL_ID = "runner"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.example.pocketrunner.START"
        const val ACTION_STOP = "com.example.pocketrunner.STOP"
        const val EXTRA_PKG = "pkg"

        fun startIntent(ctx: Context, pkg: String) =
            Intent(ctx, RunnerService::class.java).setAction(ACTION_START).putExtra(EXTRA_PKG, pkg)

        fun stopIntent(ctx: Context) =
            Intent(ctx, RunnerService::class.java).setAction(ACTION_STOP)
    }

    inner class LocalBinder : Binder() {
        val service: RunnerService get() = this@RunnerService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("runner-bg").apply { start() }
    private val bg = Handler(bgThread.looper)

    var dispW = 0; private set
    var dispH = 0; private set
    private var dpi = 0
    private var displayId = -1

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var priv: IPrivileged? = null
    private var userBound = false
    private var pendingComponent: String? = null

    private var targetPkg: String? = null
    private var live = false

    // Kalibrasi posisi sentuhan (px di ruang layar virtual), disimpan permanen
    private val prefs by lazy { getSharedPreferences("cal", MODE_PRIVATE) }
    var offY = 0; private set
    fun nudge(d: Int) {
        offY += d
        prefs.edit().putInt("offY", offY).apply()
    }

    private val userArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, PrivilegedService::class.java.name))
            .daemon(false)
            .processNameSuffix("priv")
            .version(1)
    }

    private val userConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            priv = IPrivileged.Stub.asInterface(service)
            bg.post {
                try {
                    val st = priv?.status() ?: return@post
                    Log.d(TAG, "Status injeksi: $st")
                    main.post { Toast.makeText(this@RunnerService, "$st | layar ${dispW}x$dispH", Toast.LENGTH_LONG).show() }
                } catch (_: Throwable) {}
            }
            pendingComponent?.let { launchComponent(it) }
            pendingComponent = null
        }
        override fun onServiceDisconnected(name: ComponentName?) { priv = null }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Layar virtual", NotificationManager.IMPORTANCE_LOW)
        )
        val m = resources.displayMetrics
        // Ukuran dibuat tetap & sedikit lebih kecil dari layar asli ("agak kurang penuh").
        // JANGAN diubah-ubah setelah dibuat, supaya app di dalamnya tidak ter-refresh.
        dispW = (m.widthPixels * 0.9).toInt() and 1.inv()
        dispH = (m.heightPixels * 0.8).toInt() and 1.inv()
        dpi = m.densityDpi
        offY = prefs.getInt("offY", 0)
        createDisplay()
    }

    private fun createDisplay() {
        try {
            val r = ImageReader.newInstance(dispW, dispH, PixelFormat.RGBA_8888, 2)
            r.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, bg)
            reader = r
            val dm = getSystemService(DisplayManager::class.java)
            val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val supportsTouch = 1 shl 6 // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (hidden)
            val d = try {
                dm.createVirtualDisplay("PocketRunner", dispW, dispH, dpi, r.surface, base or supportsTouch)
            } catch (e: Throwable) {
                Log.w(TAG, "Flag SUPPORTS_TOUCH ditolak, coba tanpa: ${e.message}")
                dm.createVirtualDisplay("PocketRunner", dispW, dispH, dpi, r.surface, base)
            }
            vd = d
            displayId = d.display.displayId
            val pt = android.graphics.Point()
            d.display.getRealSize(pt)
            if (pt.x > 0 && pt.y > 0 && (pt.x != dispW || pt.y != dispH)) {
                Log.w(TAG, "Ukuran layar virtual berbeda: diminta ${dispW}x$dispH, sebenarnya ${pt.x}x${pt.y}")
                dispW = pt.x; dispH = pt.y
            }
            Log.d(TAG, "VirtualDisplay dibuat id=$displayId ${dispW}x$dispH")
        } catch (e: Throwable) {
            Log.e(TAG, "Gagal membuat VirtualDisplay", e)
            Toast.makeText(this, "Gagal membuat layar virtual: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val old = targetPkg
                targetPkg = intent.getStringExtra(EXTRA_PKG)
                if (old != null && old != targetPkg && muted) {
                    muted = false
                    bg.post { try { priv?.setMuted(old, false) } catch (_: Throwable) {} }
                }
                goForeground()
                if (vd == null) { stopSelf(); return START_NOT_STICKY }
                val comp = targetPkg?.let {
                    packageManager.getLaunchIntentForPackage(it)?.component?.flattenToString()
                }
                if (comp == null) {
                    Toast.makeText(this, "App ini tidak bisa dibuka", Toast.LENGTH_SHORT).show()
                } else {
                    launchComponent(comp)
                }
            }
            ACTION_STOP -> stopEverything()
            else -> goForeground()
        }
        return START_NOT_STICKY
    }

    // ---------- Shizuku / membuka app di layar virtual ----------

    private fun launchComponent(component: String) {
        val p = priv
        if (p == null) {
            pendingComponent = component
            if (!userBound) {
                try {
                    Shizuku.bindUserService(userArgs, userConn)
                    userBound = true
                } catch (e: Throwable) {
                    Toast.makeText(this, "Shizuku belum siap: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        bg.post {
            try {
                val out = p.startOnDisplay(displayId, component)
                Log.d(TAG, "am start: $out")
                if (out.contains("Error", true) || out.startsWith("ERR")) {
                    main.post { Toast.makeText(this, out.take(160), Toast.LENGTH_LONG).show() }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "startOnDisplay gagal", e)
            }
        }
    }

    // ---------- Dipanggil PreviewActivity ----------

    fun attach(surface: Surface) {
        vd?.surface = surface
        live = true
        updateNotification()
    }

    fun detach() {
        reader?.surface?.let { vd?.surface = it } // alihkan ke dummy -> mode backend
        live = false
        updateNotification()
    }

    fun touch(e: MotionEvent, viewW: Int, viewH: Int) {
        val p = priv ?: return
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> e.actionMasked
            else -> return
        }
        val x = (e.x / viewW * dispW).coerceIn(0f, dispW - 1f)
        val y = (e.y / viewH * dispH + offY).coerceIn(0f, dispH - 1f)
        try { p.injectTouch(displayId, action, x, y, e.downTime, e.eventTime) } catch (_: Throwable) {}
    }

    // ---------- Fitur tambahan ----------

    var muted = false; private set

    fun toggleMute() {
        val pkg = targetPkg ?: return
        muted = !muted
        val m = muted
        bg.post { try { priv?.setMuted(pkg, m) } catch (_: Throwable) {} }
    }

    /** Matikan paksa app lalu buka lagi di layar virtual (state akan hilang, memang itu tujuannya). */
    fun restart() {
        val pkg = targetPkg ?: return
        val comp = packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString() ?: return
        bg.post {
            try { priv?.forceStop(pkg) } catch (_: Throwable) {}
            Thread.sleep(400)
            main.post { launchComponent(comp) }
        }
    }

    fun key(code: Int) {
        try { priv?.injectKey(displayId, code) } catch (_: Throwable) {}
    }

    // ---------- Notifikasi ----------

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pkg = targetPkg ?: ""
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }

        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, PreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (live) "Preview: $label" else "Mode backend: $label")
            .setContentText(if (live) "Ditampilkan di layar app" else "Berjalan di layar virtual, state tetap utuh")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_view, "Buka preview", open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stop)
            .build()
    }

    // ---------- Berhenti ----------

    private fun stopEverything() {
        val p = priv
        val pkg = targetPkg
        bg.post {
            try { if (pkg != null) { p?.setMuted(pkg, false); p?.forceStop(pkg) }; p?.restoreSettings() } catch (_: Throwable) {}
            main.post {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        if (userBound) {
            try { Shizuku.unbindUserService(userArgs, userConn, true) } catch (_: Throwable) {}
        }
        bgThread.quitSafely()
        super.onDestroy()
    }
}
