package com.example.pocketrunner

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.media.AudioManager
import android.os.IBinder
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

class PreviewActivity : ComponentActivity() {

    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var svc by mutableStateOf<RunnerService?>(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = (binder as RunnerService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { svc = null }
    }

    private fun applyOrientation(land: Boolean) {
        requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClickerStore.init(this)
        applyOrientation(RunnerService.savedLandscape(this)) // langsung sesuai pilihan terakhir
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // layar tidak mati saat preview
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
        var land by remember { mutableStateOf(RunnerService.savedLandscape(this@PreviewActivity)) }
        var showClicker by remember { mutableStateOf(false) }
        var selectId by remember { mutableStateOf<Long?>(null) }
        val openClicker: (Long?) -> Unit = { id -> selectId = id; showClicker = true }
        LaunchedEffect(s) {
            if (s != null) {
                land = s.landscape
                // Service hanya "terbuat" oleh bindService (mis. activity dipulihkan sistem), bukan sesi nyata -> tutup
                delay(1500)
                if (!s.started) finish()
            }
        }
        LaunchedEffect(land) { applyOrientation(land) }

        val onRotate: () -> Unit = {
            s?.let {
                it.setLandscape(!land)
                land = it.landscape
            }
        }

        if (land) {
            // Landscape: panel kontrol di kiri, layar app memenuhi sisanya
            Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Column(
                    Modifier.width(170.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(8.dp)
                ) {
                    ControlPanel(s, land, vertical = true, onRotate = onRotate, onClicker = { openClicker(null) })
                }
                DisplayArea(s, land, Modifier.weight(1f).fillMaxHeight(), openClicker)
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                ControlPanel(s, land, vertical = false, onRotate = onRotate, onClicker = { openClicker(null) })
                DisplayArea(s, land, Modifier.weight(1f).fillMaxWidth(), openClicker)
            }
        }

        if (showClicker) {
            ClickerSettingsDialog(selectId = selectId, svc = s) { showClicker = false }
        }
    }

    @Composable
    private fun ControlPanel(s: RunnerService?, land: Boolean, vertical: Boolean, onRotate: () -> Unit, onClicker: () -> Unit) {
        // Kalibrasi disimpan terpisah per orientasi, jadi dibaca ulang saat `land` berubah
        var offY by remember(s, land) { mutableIntStateOf(s?.offY ?: 0) }
        val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
        var vol by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
        var muted by remember(s) { mutableStateOf(s?.muted ?: false) }
        val rotateLabel = if (land) "Portrait" else "Landscape"

        if (vertical) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Keluar") }
                OutlinedButton(
                    onClick = { s?.key(KeyEvent.KEYCODE_BACK) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back") }
                OutlinedButton(
                    onClick = {
                        startService(RunnerService.stopIntent(this@PreviewActivity))
                        finish()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Hentikan") }
                OutlinedButton(onClick = onRotate, modifier = Modifier.fillMaxWidth()) { Text(rotateLabel) }
                Button(onClick = onClicker, modifier = Modifier.fillMaxWidth()) { Text("Auto Clicker") }

                Text("Kalibrasi Y: $offY px", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { s?.let { it.nudge(-4); offY = it.offY } },
                        modifier = Modifier.weight(1f)
                    ) { Text("−") }
                    OutlinedButton(
                        onClick = { s?.let { it.nudge(4); offY = it.offY } },
                        modifier = Modifier.weight(1f)
                    ) { Text("+") }
                }

                Text("Vol", color = Color.White, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = vol.toFloat(),
                    onValueChange = {
                        vol = it.toInt()
                        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0) } catch (_: Throwable) {}
                    },
                    valueRange = 0f..maxVol.toFloat()
                )
                OutlinedButton(
                    onClick = { s?.let { it.toggleMute(); muted = it.muted } },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (muted) "Unmute" else "Mute app") }
                OutlinedButton(onClick = { s?.restart() }, modifier = Modifier.fillMaxWidth()) { Text("Restart") }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Kalibrasi Y: $offY px", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { s?.let { it.nudge(-4); offY = it.offY } }) { Text("−") }
                    OutlinedButton(onClick = { s?.let { it.nudge(4); offY = it.offY } }) { Text("+") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Vol", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = vol.toFloat(),
                        onValueChange = {
                            vol = it.toInt()
                            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0) } catch (_: Throwable) {}
                        },
                        valueRange = 0f..maxVol.toFloat(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { s?.let { it.toggleMute(); muted = it.muted } }) {
                        Text(if (muted) "Unmute" else "Mute app")
                    }
                    OutlinedButton(onClick = { s?.restart() }) { Text("Restart") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onRotate) { Text(rotateLabel) }
                    Button(onClick = onClicker) { Text("Auto Clicker") }
                }
            }
        }
    }

    @Composable
    private fun DisplayArea(s: RunnerService?, land: Boolean, modifier: Modifier, onOpenClicker: (Long?) -> Unit) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (s != null && s.dispW > 0 && s.dispH > 0) {
                // Rasio dihitung ulang tiap orientasi berganti (dispW/dispH ikut tertukar)
                val ratio = remember(s, land) { s.dispW.toFloat() / s.dispH.toFloat() }
                Box(Modifier.aspectRatio(ratio)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                // Buffer harus sama persis dengan resolusi layar virtual, kalau tidak
                                // gambar terpotong/bergeser dan sentuhan meleset
                                holder.setFixedSize(s.dispW, s.dispH)
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) { s.attach(h.surface) }
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                                    override fun surfaceDestroyed(h: SurfaceHolder) { s.detach(h.surface) }
                                })
                                setOnTouchListener { v, e ->
                                    s.touch(e, v.width, v.height)
                                    true
                                }
                            }
                        },
                        update = { it.holder.setFixedSize(s.dispW, s.dispH) }
                    )
                    ClickerOverlay(s, onOpenClicker, Modifier.fillMaxSize())
                }
            }
        }
    }

    override fun onDestroy() {
        try { unbindService(conn) } catch (_: Throwable) {}
        super.onDestroy()
    }
}
