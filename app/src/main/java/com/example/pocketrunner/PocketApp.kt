package com.example.pocketrunner

import android.app.Application

/** Menyimpan stack trace crash terakhir supaya bisa dilihat di layar utama. */
class PocketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                getSharedPreferences("crash", MODE_PRIVATE).edit()
                    .putString("last", "${java.util.Date()} [${t.name}]\n$sw".take(6000))
                    .commit()
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }
}
