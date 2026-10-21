package com.example.pocketrunner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap)

class MainActivity : ComponentActivity() {

    // 0 = Shizuku tidak jalan, 1 = belum diberi izin, 2 = siap
    private var shizukuState by mutableIntStateOf(0)
    private var batteryOk by mutableStateOf(true)
    private var crash by mutableStateOf<String?>(null)

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }
    private val binderListener = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val deadListener = Shizuku.OnBinderDeadListener { refreshShizuku() }

    private fun refreshShizuku() {
        shizukuState = try {
            if (!Shizuku.pingBinder()) 0
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 2
            else 1
        } catch (_: Throwable) { 0 }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        refreshShizuku()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppListScreen(
                        shizukuState = shizukuState,
                        batteryOk = batteryOk,
                        crash = crash,
                        onCopyCrash = {
                            val cm = getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crash ?: ""))
                            Toast.makeText(this, "Disalin", Toast.LENGTH_SHORT).show()
                        },
                        onClearCrash = {
                            getSharedPreferences("crash", MODE_PRIVATE).edit().remove("last").apply()
                            crash = null
                        },
                        onRequestBattery = {
                            try {
                                startActivity(
                                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:$packageName"))
                                )
                            } catch (_: Throwable) {
                                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        },
                        onRequestShizuku = {
                            try { Shizuku.requestPermission(100) } catch (_: Throwable) {
                                Toast.makeText(this, "Buka app Shizuku dan start dulu", Toast.LENGTH_LONG).show()
                            }
                        },
                        onPick = { launchApp(it) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
        batteryOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        crash = getSharedPreferences("crash", MODE_PRIVATE).getString("last", null)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    private fun launchApp(pkg: String) {
        if (shizukuState != 2) {
            Toast.makeText(this, "Shizuku belum siap", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            ContextCompat.startForegroundService(this, RunnerService.startIntent(this, pkg))
            startActivity(Intent(this, PreviewActivity::class.java))
        } catch (e: Throwable) {
            Toast.makeText(this, "Gagal memulai: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun AppListScreen(
    shizukuState: Int,
    batteryOk: Boolean,
    crash: String?,
    onCopyCrash: () -> Unit,
    onClearCrash: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestShizuku: () -> Unit,
    onPick: (String) -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val apps by produceState<List<AppItem>>(emptyList()) {
        value = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(main, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .distinctBy { it.activityInfo.packageName } // satu app bisa punya >1 launcher activity
                .map {
                    AppItem(
                        label = it.loadLabel(pm).toString(),
                        pkg = it.activityInfo.packageName,
                        icon = it.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    val shown = apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Text("Pocket Runner", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        when (shizukuState) {
            0 -> Text(
                "Shizuku belum berjalan. Buka app Shizuku lalu start (Wireless Debugging), kemudian kembali ke sini.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shizuku jalan, tapi belum diberi izin.", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = onRequestShizuku) { Text("Beri izin") }
            }
            else -> Text("Shizuku siap ✓", style = MaterialTheme.typography.bodySmall)
        }

        if (!batteryOk) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Hemat baterai aktif: Android bisa mematikan layar virtual dan memunculkan app di layar utama.",
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onRequestBattery) { Text("Matikan") }
            }
        }

        if (crash != null) {
            Spacer(Modifier.height(8.dp))
            Text("Crash terakhir:", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(crash, maxLines = 5, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopyCrash) { Text("Salin") }
                OutlinedButton(onClick = onClearCrash) { Text("Hapus") }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Cari aplikasi") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { app ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(app.pkg) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(app.icon, null, Modifier.size(44.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
