package com.example.pocketrunner

import android.Manifest
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

data class AppItem(val label: String, val pkg: String, val icon: ImageBitmap)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppListScreen(onPick = { launchApp(it) })
                }
            }
        }
    }

    private fun launchApp(pkg: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan 'Tampil di atas aplikasi lain' dulu", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        val target = packageManager.getLaunchIntentForPackage(pkg)
        if (target == null) {
            Toast.makeText(this, "App ini tidak bisa dibuka", Toast.LENGTH_SHORT).show()
            return
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Mulai service (overlay + mode backend)
        ContextCompat.startForegroundService(this, RunnerService.startIntent(this, pkg))

        // Layar "agak kurang penuh": hanya berpengaruh jika HP mendukung freeform window
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val bounds = Rect((w * 0.04).toInt(), (h * 0.06).toInt(), (w * 0.96).toInt(), (h * 0.84).toInt())
        val opts = ActivityOptions.makeBasic().setLaunchBounds(bounds)
        startActivity(target, opts.toBundle())
    }
}

@Composable
fun AppListScreen(onPick: (String) -> Unit) {
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
        Text(
            "Pilih aplikasi untuk dijalankan. Tekan 'Keluar' di overlay untuk pindah ke mode backend.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
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
