package com.example.pocketrunner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class PreviewActivity : ComponentActivity() {

    private var svc by mutableStateOf<RunnerService?>(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = (binder as RunnerService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindService(Intent(this, RunnerService::class.java), conn, Context.BIND_AUTO_CREATE)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = Color.Black) { PreviewScreen() }
            }
        }
    }

    @Composable
    private fun PreviewScreen() {
        val s = svc
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Keluar = tutup preview, app lanjut di mode backend
                Button(onClick = { finish() }) { Text("Keluar") }
                OutlinedButton(onClick = { s?.key(KeyEvent.KEYCODE_BACK) }) { Text("Back") }
                OutlinedButton(onClick = {
                    startService(RunnerService.stopIntent(this@PreviewActivity))
                    finish()
                }) { Text("Hentikan") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (s != null) {
                    AndroidView(
                        modifier = Modifier.aspectRatio(s.dispW.toFloat() / s.dispH.toFloat()),
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) { s.attach(h.surface) }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                                    override fun surfaceDestroyed(h: SurfaceHolder) { s.detach() }
                                })
                                setOnTouchListener { v, e ->
                                    s.touch(e, v.width, v.height)
                                    true
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        try { unbindService(conn) } catch (_: Throwable) {}
        super.onDestroy()
    }
}
