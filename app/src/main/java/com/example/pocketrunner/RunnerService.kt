package com.example.pocketrunner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class RunnerService : Service() {

    companion object {
        private const val CHANNEL_ID = "runner"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "com.example.pocketrunner.START"
        const val ACTION_EXIT_LIVE = "com.example.pocketrunner.EXIT_LIVE"
        const val ACTION_STOP = "com.example.pocketrunner.STOP"
        const val EXTRA_PKG = "pkg"

        fun startIntent(ctx: Context, pkg: String) =
            Intent(ctx, RunnerService::class.java).setAction(ACTION_START).putExtra(EXTRA_PKG, pkg)
    }

    private var targetPkg: String? = null
    private var overlay: View? = null
    private lateinit var wm: WindowManager
    private lateinit var nm: NotificationManager

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mode jalan", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                targetPkg = intent.getStringExtra(EXTRA_PKG)
                goForeground(live = true)
                showOverlay()
            }
            ACTION_EXIT_LIVE -> exitLiveMode()
            ACTION_STOP -> {
                removeOverlay()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ---------- Notifikasi / foreground ----------

    private fun goForeground(live: Boolean) {
        val notif = buildNotification(live)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    private fun buildNotification(live: Boolean): Notification {
        val pkg = targetPkg ?: ""
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { pkg }

        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (live) "Berjalan: $label" else "Mode backend: $label")
            .setContentText(
                if (live) "Tekan Keluar di overlay untuk pindah ke backend"
                else "App tetap hidup di latar belakang"
            )
            .setOngoing(true)

        if (!live) {
            packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val open = PendingIntent.getActivity(
                    this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                b.addAction(android.R.drawable.ic_menu_view, "Buka lagi", open)
            }
        }
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RunnerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stop)
        return b.build()
    }

    // ---------- Overlay ----------

    private fun showOverlay() {
        removeOverlay()
        val dp = resources.displayMetrics.density

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6202124"))
                cornerRadius = 28 * dp
            }
        }
        val label = TextView(this).apply {
            text = "Mode langsung"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val btn = Button(this).apply {
            text = "Keluar"
            isAllCaps = false
            setOnClickListener {
                startService(Intent(this@RunnerService, RunnerService::class.java).setAction(ACTION_EXIT_LIVE))
            }
        }
        bar.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btn)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (40 * dp).toInt()
        }
        wm.addView(bar, lp)
        overlay = bar
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    // ---------- Keluar dari mode langsung -> backend ----------

    private fun exitLiveMode() {
        removeOverlay()
        goForeground(live = false)
        // Kembali ke home; app target tetap hidup di background.
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(home)
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
