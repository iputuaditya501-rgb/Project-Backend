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
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import rikka.shizuku.Shizuku
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
        const val ACTION_STOP_CLICKER = "com.example.pocketrunner.STOP_CLICKER"
        const val EXTRA_PKG = "pkg"

        fun startIntent(ctx: Context, pkg: String) =
            Intent(ctx, RunnerService::class.java).setAction(ACTION_START).putExtra(EXTRA_PKG, pkg)

        fun stopIntent(ctx: Context) =
            Intent(ctx, RunnerService::class.java).setAction(ACTION_STOP)

        /** Orientasi terakhir yang dipakai (dibaca PreviewActivity sebelum service terhubung). */
        fun savedLandscape(ctx: Context): Boolean =
            ctx.getSharedPreferences("cal", Context.MODE_PRIVATE).getBoolean("landscape", false)
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
    @Volatile private var displayId = -1
    @Volatile private var remote = false   // true = layar virtual trusted milik proses shell
    private var lastComponent: String? = null

    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var priv: IPrivileged? = null
    private var userBound = false
    private var pendingComponent: String? = null
    private var pendingFresh = false

    @Volatile private var destroyed = false
    var started = false; private set
    val ready: Boolean get() = displayId >= 0
    val hasPriv: Boolean get() = priv != null

    // Dinaikkan tiap START/STOP supaya tugas tertunda dari sesi lama tidak membunuh sesi baru
    @Volatile private var sessionGen = 0
    private var launchedPkg: String? = null
    private var rescues = 0
    private var rebindTries = 0
    private var wl: PowerManager.WakeLock? = null

    val clicker by lazy { ClickerEngine(this) }

    private var targetPkg: String? = null
    private var live = false
    @Volatile private var liveSurface: Surface? = null

    // Ukuran dasar (mode portrait). Landscape = lebar & tinggi ditukar.
    private var portW = 0
    private var portH = 0
    var landscape = false; private set

    // Kalibrasi posisi sentuhan (px di ruang layar virtual), disimpan permanen
    private val prefs by lazy { getSharedPreferences("cal", MODE_PRIVATE) }
    var offY = 0; private set
    private val offKey get() = if (landscape) "offY_land" else "offY"
    fun nudge(d: Int) {
        offY += d
        prefs.edit().putInt(offKey, offY).apply()
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
            rebindTries = 0
            bg.post {
                try {
                    val st = priv?.status() ?: return@post
                    Log.d(TAG, "Status injeksi: $st")
                    toast("$st | layar ${dispW}x$dispH", long = true)
                } catch (_: Throwable) {}
            }
            val pend = pendingComponent
            if (pend != null) {
                launchComponent(pend, pendingFresh)
            } else if (started && displayId < 0) {
                // layar trusted ikut mati bersama proses shell -> luncurkan ulang
                lastComponent?.let { launchComponent(it, true) }
            }
            pendingComponent = null
            pendingFresh = false
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            priv = null
            userBound = false
            if (remote) { remote = false; displayId = -1 }
            // Proses Shizuku user service mati -> sambungkan lagi otomatis
            if (started && !destroyed && rebindTries < 5) {
                rebindTries++
                main.postDelayed({
                    if (!destroyed && priv == null && !userBound) ensureBound()
                }, 1500L * rebindTries)
            }
        }
    }

    private fun ensureBound() {
        if (userBound) return
        try {
            try { Shizuku.unbindUserService(userArgs, userConn, false) } catch (_: Throwable) {}
            Shizuku.bindUserService(userArgs, userConn)
            userBound = true
        } catch (e: Throwable) {
            toast("Shizuku belum siap: ${e.message}", long = true)
        }
    }

    fun toast(msg: String, long: Boolean = false) {
        main.post {
            if (!destroyed) Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ClickerStore.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Layar virtual", NotificationManager.IMPORTANCE_LOW)
        )
        val m = resources.displayMetrics
        // Ukuran dibuat sedikit lebih kecil dari layar asli ("agak kurang penuh").
        // Pakai sisi pendek/panjang supaya hasilnya sama walau HP sedang miring saat service dibuat.
        val shortSide = minOf(m.widthPixels, m.heightPixels)
        val longSide = maxOf(m.widthPixels, m.heightPixels)
        portW = (shortSide * 0.9).toInt() and 1.inv()
        portH = (longSide * 0.8).toInt() and 1.inv()
        dpi = m.densityDpi
        landscape = savedLandscape(this)
        applyDims()
        // Kalibrasi lama hanyalah tambalan untuk bug ukuran surface; reset sekali
        if (!prefs.getBoolean("fixedSurfaceV2", false)) {
            prefs.edit().remove("offY").remove("offY_land").putBoolean("fixedSurfaceV2", true).apply()
        }
        offY = prefs.getInt(offKey, 0)
        // Layar virtual dibuat saat START, setelah Shizuku tersambung (lihat ensureDisplay)
    }

    private fun applyDims() {
        if (landscape) { dispW = portH; dispH = portW } else { dispW = portW; dispH = portH }
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(dispW, dispH, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            // Exception di thread ini = crash seluruh app, jadi wajib ditangkap
            try { rd.acquireLatestImage()?.close() } catch (_: Throwable) {}
        }, bg)
        return r
    }

    /**
     * Ganti orientasi layar virtual (portrait <-> landscape) TANPA membuat ulang layarnya,
     * jadi app di dalamnya tidak dipindah ke layar utama. App hanya menerima perubahan
     * konfigurasi, sama seperti saat HP diputar.
     */
    fun setLandscape(on: Boolean) {
        if (on == landscape) return
        landscape = on
        prefs.edit().putBoolean("landscape", on).apply()
        applyDims()
        offY = prefs.getInt(offKey, 0)
        if (displayId < 0) {
            // layar belum dibuat: cukup siapkan ulang dummy reader dengan ukuran baru
            try { reader?.close() } catch (_: Throwable) {}
            reader = null
            return
        }
        try {
            if (remote) priv?.resizeDisplay(dispW, dispH, dpi) else vd?.resize(dispW, dispH, dpi)
            val old = reader
            val r = newReader()
            reader = r
            if (liveSurface == null) setVdSurface(r.surface)   // mode backend: pindah ke dummy baru
            try { old?.close() } catch (_: Throwable) {}
            Log.d(TAG, "Orientasi -> ${if (on) "landscape" else "portrait"} ${dispW}x$dispH")
        } catch (e: Throwable) {
            Log.e(TAG, "Gagal mengubah orientasi", e)
            toast("Gagal ganti orientasi: ${e.message}", long = true)
        }
    }

    private fun ensureReader(): ImageReader = reader ?: newReader().also { reader = it }

    /** Pasang surface ke layar virtual (lokal atau trusted). */
    private fun setVdSurface(s: Surface) {
        try {
            if (remote) priv?.setDisplaySurface(s) else vd?.surface = s
        } catch (e: Throwable) {
            Log.e(TAG, "setSurface gagal", e)
        }
    }

    /** Kalau surface berganti selagi layar dibuat (preview dibuka/ditutup), samakan lagi. */
    private fun syncSurface(used: Surface) {
        val cur = liveSurface ?: reader?.surface
        if (cur != null && cur !== used) setVdSurface(cur)
    }

    /** Dipanggil di thread bg sebelum meluncurkan app. */
    private fun ensureDisplay(p: IPrivileged?): Boolean {
        if (displayId >= 0) return true
        if (p != null && createTrustedDisplay(p)) return true
        return createLocalDisplay()
    }

    private fun createTrustedDisplay(p: IPrivileged): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false   // flag TRUSTED baru ada di Android 13
        return try {
            val s = liveSurface ?: ensureReader().surface
            val id = p.createDisplay(dispW, dispH, dpi, s)
            if (id < 0) {
                val why = p.displayError()
                Log.w(TAG, "Layar trusted gagal: $why")
                toast("Layar trusted gagal, pakai mode biasa: $why", long = true)
                false
            } else {
                remote = true
                displayId = id
                Log.d(TAG, "Layar virtual TRUSTED dibuat id=$id ${dispW}x$dispH")
                syncSurface(s)
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "createTrustedDisplay", e)
            false
        }
    }

    private fun createLocalDisplay(): Boolean {
        try {
            val r = ensureReader()
            val s = liveSurface ?: r.surface
            val dm = getSystemService(DisplayManager::class.java)
            val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val supportsTouch = 1 shl 6   // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (hidden)
            val destroyOnRemoval = 1 shl 8 // VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL (hidden)
            // Tanpa flag destroy, kalau layar virtual hilang app di dalamnya DIPINDAH ke layar utama.
            val candidates = intArrayOf(
                base or supportsTouch or destroyOnRemoval,
                base or destroyOnRemoval,
                base or supportsTouch,
                base
            )
            var created: VirtualDisplay? = null
            var lastErr: Throwable? = null
            for (f in candidates) {
                try {
                    created = dm.createVirtualDisplay("PocketRunner", dispW, dispH, dpi, s, f)
                    Log.d(TAG, "VirtualDisplay lokal flags=$f")
                    break
                } catch (e: Throwable) {
                    lastErr = e
                    Log.w(TAG, "Flags $f ditolak: ${e.message}")
                }
            }
            val d = created ?: throw (lastErr ?: IllegalStateException("createVirtualDisplay gagal"))
            vd = d
            val pt = android.graphics.Point()
            d.display.getRealSize(pt)
            if (pt.x > 0 && pt.y > 0 && (pt.x != dispW || pt.y != dispH)) {
                Log.w(TAG, "Ukuran layar virtual berbeda: diminta ${dispW}x$dispH, sebenarnya ${pt.x}x${pt.y}")
                dispW = pt.x; dispH = pt.y
            }
            remote = false
            displayId = d.display.displayId
            Log.d(TAG, "VirtualDisplay lokal id=$displayId ${dispW}x$dispH")
            syncSurface(s)
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Gagal membuat VirtualDisplay", e)
            toast("Gagal membuat layar virtual: ${e.message}", long = true)
            return false
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                sessionGen++
                started = true
                rescues = 0
                val pkg = intent.getStringExtra(EXTRA_PKG)
                val old = targetPkg
                targetPkg = pkg
                if (old != null && old != pkg) {
                    clicker.stop()
                    if (muted) {
                        muted = false
                        bg.post { try { priv?.setMuted(old, false) } catch (_: Throwable) {} }
                    }
                }
                goForeground()
                acquireWake()
                val comp = pkg?.let {
                    packageManager.getLaunchIntentForPackage(it)?.component?.flattenToString()
                }
                if (comp == null) {
                    toast("App ini tidak bisa dibuka")
                } else {
                    // App baru -> dimatikan dulu supaya tidak ditarik dari layar utama.
                    // App yang sama (task sudah di layar virtual) -> cukup dibawa ke depan.
                    val fresh = pkg != launchedPkg
                    launchedPkg = pkg
                    lastComponent = comp
                    launchComponent(comp, fresh)
                    startMonitor(pkg!!, comp)
                }
            }
            ACTION_STOP -> stopEverything()
            ACTION_STOP_CLICKER -> clicker.stop()
            else -> if (started) goForeground()
        }
        return START_NOT_STICKY
    }

    private fun acquireWake() {
        try {
            if (wl?.isHeld == true) return
            wl = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketRunner:run")
                .apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {}
    }

    // ---------- Shizuku / membuka app di layar virtual ----------

    private fun launchComponent(component: String, fresh: Boolean) {
        val p = priv
        if (p == null) {
            pendingComponent = component
            pendingFresh = pendingFresh || fresh
            ensureBound()
            return
        }
        bg.post {
            try {
                if (!ensureDisplay(p)) {
                    toast("Layar virtual gagal dibuat", long = true)
                    main.post { stopEverything() }
                    return@post
                }
                val did = displayId
                val out = p.startOnDisplay(did, component, fresh)
                Log.d(TAG, "am start: $out")
                if (out.contains("Error", true) || out.startsWith("ERR")) {
                    toast(out.take(160), long = true)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "startOnDisplay gagal", e)
            }
        }
    }

    /**
     * Penjaga berkelanjutan. Banyak app (Roblox, Melolo, dll) membuka activity berikutnya di task baru
     * setelah loading/splash, dan Android menaruhnya di layar utama. Di sini app dipindah kembali ke
     * layar virtual TANPA restart. Restart hanya sebagai jalan terakhir (1x per sesi).
     */
    private fun startMonitor(pkg: String, component: String) {
        monitorTick(sessionGen, pkg, component, 0)
    }

    private fun monitorTick(gen: Int, pkg: String, component: String, n: Int) {
        val delayMs = if (n < 60) 2000L else 5000L
        main.postDelayed({
            if (destroyed || gen != sessionGen) return@postDelayed
            val p = priv
            val did = displayId
            if (p == null || did < 0) { monitorTick(gen, pkg, component, n + 1); return@postDelayed }
            val wasLive = live
            bg.post {
                try {
                    if (gen == sessionGen && p.isOnDefaultDisplay(pkg, did)) {
                        Log.w(TAG, "$pkg muncul di layar utama, dipindah kembali")
                        val ok = p.pullToDisplay(pkg, component, did)
                        if (!ok && rescues < 1) {
                            rescues++
                            Log.w(TAG, "Pindah gagal, restart di layar virtual")
                            main.post { if (gen == sessionGen && !destroyed) launchComponent(component, true) }
                        }
                        if (wasLive) p.bringToFront("$packageName/${PreviewActivity::class.java.name}")
                    }
                } catch (_: Throwable) {}
                main.post { if (gen == sessionGen && !destroyed) monitorTick(gen, pkg, component, n + 1) }
            }
        }, delayMs)
    }

    // ---------- Dipanggil PreviewActivity ----------

    fun attach(surface: Surface) {
        liveSurface = surface
        setVdSurface(surface)
        live = true
        updateNotification()
    }

    fun detach(surface: Surface) {
        // Abaikan kalau SurfaceView lama baru dihancurkan setelah yang baru terpasang
        // (terjadi saat tampilan preview berpindah portrait <-> landscape).
        if (liveSurface !== surface) return
        liveSurface = null
        reader?.surface?.let { setVdSurface(it) }
        live = false
        updateNotification()
    }

    /** Sentuhan mentah ke layar virtual (dipakai auto clicker). */
    fun rawTouch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        try { priv?.injectTouch(displayId, action, x, y, downTime, eventTime) } catch (_: Throwable) {}
    }

    /** Pecahan 0..1 (posisi di preview) -> piksel layar virtual, dengan kalibrasi & acak posisi. */
    fun mapPoint(nx: Float, ny: Float, jitterPx: Int, rnd: Random): FloatArray {
        var x = nx * dispW
        var y = ny * dispH + offY
        if (jitterPx > 0) {
            val a = rnd.nextDouble() * 2 * PI
            val r = sqrt(rnd.nextDouble()) * jitterPx
            x += (cos(a) * r).toFloat()
            y += (sin(a) * r).toFloat()
        }
        return floatArrayOf(x.coerceIn(0f, dispW - 1f), y.coerceIn(0f, dispH - 1f))
    }

    fun startClicker() {
        if (!started) return
        clicker.start(ClickerStore.cfg)
        updateNotification()
    }

    fun stopClicker() { clicker.stop() }

    fun onClickerStopped() {
        main.post { if (!destroyed && started) updateNotification() }
    }

    fun touch(e: MotionEvent, viewW: Int, viewH: Int) {
        if (displayId < 0) return
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
            rescues = 0
            main.post { launchComponent(comp, true) }
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
        if (destroyed || !started) return
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
        val clicking = clicker.isActive
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (live) "Preview: $label" else "Mode backend: $label")
            .setContentText(
                if (clicking) "Auto clicker aktif"
                else if (live) "Ditampilkan di layar app"
                else "Berjalan di layar virtual, state tetap utuh"
            )
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_view, "Buka preview", open)
        if (clicking) {
            val stopClick = PendingIntent.getService(
                this, 3,
                Intent(this, RunnerService::class.java).setAction(ACTION_STOP_CLICKER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(android.R.drawable.ic_media_pause, "Stop klik", stopClick)
        }
        return b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stop).build()
    }

    // ---------- Berhenti ----------

    private fun stopEverything() {
        clicker.stop()
        val gen = ++sessionGen
        started = false
        launchedPkg = null
        val p = priv
        val pkg = targetPkg
        bg.post {
            // Kalau sementara itu ada START baru, jangan matikan app sesi baru
            if (gen == sessionGen) {
                try { if (pkg != null) { p?.setMuted(pkg, false); p?.forceStop(pkg) }; p?.restoreSettings() } catch (_: Throwable) {}
            }
            main.post {
                if (gen == sessionGen) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        try { clicker.stop() } catch (_: Throwable) {}
        main.removeCallbacksAndMessages(null)
        if (remote) { try { priv?.releaseDisplay() } catch (_: Throwable) {} }
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        if (userBound) {
            try { Shizuku.unbindUserService(userArgs, userConn, true) } catch (_: Throwable) {}
        }
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Throwable) {}
        bgThread.quitSafely()
        super.onDestroy()
    }
}
