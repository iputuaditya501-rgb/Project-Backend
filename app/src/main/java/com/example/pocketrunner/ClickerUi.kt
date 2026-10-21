package com.example.pocketrunner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

private val TapColor = Color(0xFFD84315)
private val SwipeColor = Color(0xFF2E7D32)
private val SwipeEndColor = Color(0xFF1565C0)

// ------------------------------------------------------------------ overlay di atas preview

@Composable
fun ClickerOverlay(svc: RunnerService?, onOpen: (Long?) -> Unit, modifier: Modifier = Modifier) {
    val cfg = ClickerStore.cfg
    val phase = ClickerRuntime.phase
    val running = phase != ClickerRuntime.IDLE
    var box by remember { mutableStateOf(IntSize.Zero) }
    var collapsed by remember { mutableStateOf(false) }
    var barOff by remember { mutableStateOf(IntOffset.Zero) }
    val shown = if (cfg.multi) cfg.targets else cfg.targets.take(1)

    Box(modifier.onSizeChanged { box = it }) {
        if (cfg.showMarkers && box.width > 0 && box.height > 0) {
            Canvas(Modifier.fillMaxSize()) {
                shown.forEach { t ->
                    if (t.type == ActionType.SWIPE) {
                        drawLine(
                            Color(0xFFFFC107),
                            Offset(t.x * size.width, t.y * size.height),
                            Offset(t.x2 * size.width, t.y2 * size.height),
                            strokeWidth = 5f
                        )
                    }
                }
            }
            shown.forEachIndexed { i, t ->
                key(t.id) {
                    val a = cfg.markerAlpha * (if (t.enabled) 1f else 0.4f)
                    TargetDot(
                        label = "${i + 1}", nx = t.x, ny = t.y, box = box, sizeDp = cfg.markerDp, alpha = a,
                        color = if (t.type == ActionType.SWIPE) SwipeColor else TapColor, locked = running,
                        onMove = { dx, dy -> shiftTarget(t.id, dx, dy, false) },
                        onDone = { ClickerStore.persist() },
                        onTap = { onOpen(t.id) }
                    )
                    if (t.type == ActionType.SWIPE) {
                        TargetDot(
                            label = "${i + 1}'", nx = t.x2, ny = t.y2, box = box, sizeDp = cfg.markerDp, alpha = a,
                            color = SwipeEndColor, locked = running,
                            onMove = { dx, dy -> shiftTarget(t.id, dx, dy, true) },
                            onDone = { ClickerStore.persist() },
                            onTap = { onOpen(t.id) }
                        )
                    }
                }
            }
        }

        if (collapsed) {
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(8.dp).clickable { collapsed = false },
                shape = CircleShape, color = Color(0xD9202020)
            ) {
                Text(
                    "AC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        } else {
            Surface(
                Modifier.align(Alignment.BottomCenter).offset { barOff }.padding(6.dp),
                shape = RoundedCornerShape(24.dp), color = Color(0xD9202020)
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "⠿", color = Color.White, fontSize = 20.sp,
                        modifier = Modifier.padding(horizontal = 4.dp).pointerInput(Unit) {
                            detectDragGestures { c, d ->
                                c.consume()
                                barOff += IntOffset(d.x.roundToInt(), d.y.roundToInt())
                            }
                        }
                    )
                    Button(
                        onClick = { if (running) svc?.stopClicker() else svc?.startClicker() },
                        enabled = svc != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (running) Color(0xFFC62828) else Color(0xFF2E7D32)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) { Text(if (running) "■ Stop" else "▶ Mulai", fontSize = 13.sp) }
                    Button(
                        onClick = { ClickerStore.addTarget() },
                        enabled = !running,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) { Text("+", fontSize = 16.sp) }
                    Button(
                        onClick = { onOpen(null) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)
                    ) { Text("⚙", fontSize = 15.sp) }
                    val status = when (phase) {
                        ClickerRuntime.COUNTDOWN -> "Mulai ${ClickerRuntime.countdown}s"
                        ClickerRuntime.RUNNING -> "${ClickerRuntime.clicks} klik"
                        else -> "${cfg.targets.size} target"
                    }
                    Text(status, color = Color.White, fontSize = 12.sp)
                    Text(
                        "–", color = Color.White, fontSize = 20.sp,
                        modifier = Modifier.clickable { collapsed = true }.padding(horizontal = 6.dp)
                    )
                }
            }
        }
    }
}

private fun shiftTarget(id: Long, dx: Float, dy: Float, end: Boolean) =
    ClickerStore.update(save = false) { c ->
        c.copy(targets = c.targets.map {
            when {
                it.id != id -> it
                end -> it.copy(x2 = (it.x2 + dx).coerceIn(0f, 1f), y2 = (it.y2 + dy).coerceIn(0f, 1f))
                else -> it.copy(x = (it.x + dx).coerceIn(0f, 1f), y = (it.y + dy).coerceIn(0f, 1f))
            }
        })
    }

@Composable
private fun TargetDot(
    label: String, nx: Float, ny: Float, box: IntSize, sizeDp: Int, alpha: Float, color: Color,
    locked: Boolean, onMove: (Float, Float) -> Unit, onDone: () -> Unit, onTap: () -> Unit
) {
    val sizePx = with(LocalDensity.current) { sizeDp.dp.roundToPx() }
    Box(
        Modifier
            .offset { IntOffset((nx * box.width - sizePx / 2f).roundToInt(), (ny * box.height - sizePx / 2f).roundToInt()) }
            .size(sizeDp.dp)
            .alpha(alpha)
            .background(color, CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .pointerInput(box) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(box, locked) {
                detectDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, drag ->
                    change.consume()
                    if (!locked) onMove(drag.x / box.width, drag.y / box.height)
                }
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

// ------------------------------------------------------------------ dialog pengaturan

@Composable
fun ClickerSettingsDialog(selectId: Long?, svc: RunnerService?, onDismiss: () -> Unit) {
    val cfg = ClickerStore.cfg
    val running = ClickerRuntime.phase != ClickerRuntime.IDLE
    var expanded by remember { mutableStateOf(selectId) }
    var profileName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(8.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Auto Clicker", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { if (running) svc?.stopClicker() else { svc?.startClicker(); onDismiss() } },
                        enabled = svc != null
                    ) { Text(if (running) "■ Stop" else "▶ Mulai") }
                    OutlinedButton(onClick = onDismiss) { Text("Tutup") }
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (running) {
                        Text(
                            "Sedang berjalan: ${ClickerRuntime.clicks} klik • ${ClickerRuntime.cycles} siklus • ${ClickerRuntime.elapsedSec}s. " +
                                "Perubahan pengaturan berlaku saat Mulai berikutnya.",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // ---- mode
                    Title("Mode")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Satu target", !cfg.multi, Modifier.weight(1f)) { ClickerStore.update { it.copy(multi = false) } }
                        Choice("Multi target", cfg.multi, Modifier.weight(1f)) { ClickerStore.update { it.copy(multi = true) } }
                    }
                    Text(
                        if (cfg.multi) "Target dijalankan berurutan lalu mengulang dari awal."
                        else "Hanya target #1 yang dijalankan berulang.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    // ---- target
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Title("Target (${cfg.targets.size})", Modifier.weight(1f))
                        Button(onClick = { ClickerStore.addTarget() }, enabled = !running) { Text("+ Tambah") }
                        if (cfg.targets.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { ClickerStore.update { it.copy(targets = emptyList()) } },
                                enabled = !running
                            ) { Text("Hapus semua") }
                        }
                    }
                    if (cfg.targets.isEmpty()) {
                        Text(
                            "Belum ada target. Tekan + Tambah, lalu geser titiknya di preview ke posisi yang diinginkan.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    cfg.targets.forEachIndexed { i, t ->
                        TargetCard(
                            index = i, t = t, last = i == cfg.targets.lastIndex, open = expanded == t.id, running = running,
                            onToggle = { expanded = if (expanded == t.id) null else t.id }
                        )
                    }

                    // ---- acak
                    Title("Acak (anti-pola)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Acak jeda ± ms", cfg.randomIntervalMs.toLong(), Modifier.weight(1f)) { n ->
                            ClickerStore.update { it.copy(randomIntervalMs = n.toInt()) }
                        }
                        NumField("Acak posisi px", cfg.randomPosPx.toLong(), Modifier.weight(1f)) { n ->
                            ClickerStore.update { it.copy(randomPosPx = n.toInt()) }
                        }
                    }

                    // ---- berhenti
                    Title("Mulai & berhenti")
                    NumField("Tunda sebelum mulai (detik)", cfg.startDelaySec.toLong(), Modifier.fillMaxWidth(), max = 3600) { n ->
                        ClickerStore.update { it.copy(startDelaySec = n.toInt()) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("Berhenti setelah siklus (0 = ∞)", cfg.maxCycles.toLong(), Modifier.weight(1f)) { n ->
                            ClickerStore.update { it.copy(maxCycles = n.toInt()) }
                        }
                        NumField("Berhenti setelah menit (0 = ∞)", cfg.maxMinutes.toLong(), Modifier.weight(1f), max = 100000) { n ->
                            ClickerStore.update { it.copy(maxMinutes = n.toInt()) }
                        }
                    }

                    // ---- tampilan
                    Title("Tampilan")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tampilkan titik target", Modifier.weight(1f))
                        Switch(checked = cfg.showMarkers, onCheckedChange = { v -> ClickerStore.update { it.copy(showMarkers = v) } })
                    }
                    Text("Ukuran titik: ${cfg.markerDp} dp", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = cfg.markerDp.toFloat(),
                        onValueChange = { v -> ClickerStore.update(save = false) { it.copy(markerDp = v.roundToInt()) } },
                        onValueChangeFinished = { ClickerStore.persist() },
                        valueRange = 24f..80f
                    )
                    Text("Transparansi titik: ${(cfg.markerAlpha * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = cfg.markerAlpha,
                        onValueChange = { v -> ClickerStore.update(save = false) { it.copy(markerAlpha = v) } },
                        onValueChangeFinished = { ClickerStore.persist() },
                        valueRange = 0.2f..1f
                    )

                    // ---- profil
                    Title("Profil tersimpan")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = profileName, onValueChange = { profileName = it.take(24) },
                            label = { Text("Nama profil") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { if (ClickerStore.saveProfile(profileName)) profileName = "" }) { Text("Simpan") }
                    }
                    if (ClickerStore.profiles.isEmpty()) {
                        Text("Belum ada profil.", style = MaterialTheme.typography.bodySmall)
                    }
                    ClickerStore.profiles.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f))
                            TextButton(onClick = { ClickerStore.loadProfile(name) }, enabled = !running) { Text("Muat") }
                            TextButton(onClick = { ClickerStore.deleteProfile(name) }) { Text("Hapus") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun TargetCard(index: Int, t: ClickTarget, last: Boolean, open: Boolean, running: Boolean, onToggle: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("#${index + 1}", fontWeight = FontWeight.Bold)
                Text(
                    "${if (t.type == ActionType.SWIPE) "Swipe" else "Tap"} • ${(t.x * 100).roundToInt()}%, ${(t.y * 100).roundToInt()}%",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
                )
                Switch(checked = t.enabled, onCheckedChange = { v -> ClickerStore.updateTarget(t.id) { it.copy(enabled = v) } })
                TextButton(onClick = onToggle) { Text(if (open) "Tutup" else "Ubah") }
            }
            if (open) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice("Tap", t.type == ActionType.TAP, Modifier.weight(1f)) {
                        ClickerStore.updateTarget(t.id) { it.copy(type = ActionType.TAP) }
                    }
                    Choice("Swipe", t.type == ActionType.SWIPE, Modifier.weight(1f)) {
                        ClickerStore.updateTarget(t.id) {
                            if (it.type == ActionType.SWIPE) it
                            else it.copy(type = ActionType.SWIPE, x2 = it.x, y2 = (it.y + 0.2f).coerceAtMost(0.95f))
                        }
                    }
                }
                Text("Jeda setelah aksi (jam / menit / detik / ms)", style = MaterialTheme.typography.bodySmall)
                IntervalEditor(t.intervalMs) { v -> ClickerStore.updateTarget(t.id) { it.copy(intervalMs = v) } }
                if (t.type == ActionType.TAP) {
                    NumField("Durasi tekan (ms)", t.holdMs.toLong(), Modifier.fillMaxWidth(), min = 1, max = 60000) { n ->
                        ClickerStore.updateTarget(t.id) { it.copy(holdMs = n.toInt()) }
                    }
                } else {
                    NumField("Durasi swipe (ms)", t.swipeMs.toLong(), Modifier.fillMaxWidth(), min = 50, max = 60000) { n ->
                        ClickerStore.updateTarget(t.id) { it.copy(swipeMs = n.toInt()) }
                    }
                    Text("Geser titik biru (${index + 1}') di preview untuk titik akhir.", style = MaterialTheme.typography.bodySmall)
                }
                NumField("Ulangi aksi (x)", t.repeat.toLong(), Modifier.fillMaxWidth(), min = 1, max = 100000) { n ->
                    ClickerStore.updateTarget(t.id) { it.copy(repeat = n.toInt()) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { ClickerStore.moveTarget(t.id, -1) }, enabled = index > 0 && !running,
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) { Text("↑") }
                    OutlinedButton(onClick = { ClickerStore.moveTarget(t.id, 1) }, enabled = !last && !running,
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) { Text("↓") }
                    OutlinedButton(onClick = { ClickerStore.duplicateTarget(t.id) }, enabled = !running,
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(2f)) { Text("Duplikat") }
                    OutlinedButton(onClick = { ClickerStore.removeTarget(t.id) }, enabled = !running,
                        contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(2f)) { Text("Hapus") }
                }
                OutlinedButton(
                    onClick = {
                        ClickerStore.update { c -> c.copy(targets = c.targets.map { it.copy(intervalMs = t.intervalMs) }) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Pakai jeda ini untuk semua target") }
            }
        }
    }
}

// ------------------------------------------------------------------ komponen kecil

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun IntervalEditor(valueMs: Long, onChange: (Long) -> Unit) {
    val h = valueMs / 3_600_000
    val m = valueMs / 60_000 % 60
    val s = valueMs / 1000 % 60
    val ms = valueMs % 1000
    fun total(h: Long, m: Long, s: Long, ms: Long) = h * 3_600_000 + m * 60_000 + s * 1000 + ms
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        NumField("jam", h, Modifier.weight(1f), max = 99) { onChange(total(it, m, s, ms)) }
        NumField("mnt", m, Modifier.weight(1f), max = 999) { onChange(total(h, it, s, ms)) }
        NumField("dtk", s, Modifier.weight(1f), max = 9999) { onChange(total(h, m, it, ms)) }
        NumField("ms", ms, Modifier.weight(1f), max = 99999) { onChange(total(h, m, s, it)) }
    }
}

@Composable
private fun NumField(
    label: String, value: Long, modifier: Modifier = Modifier,
    min: Long = 0, max: Long = 999_999_999, onChange: (Long) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        // Sinkron kalau nilai berubah dari luar (muat profil, normalisasi), tanpa mengganggu saat mengetik
        if ((text.toLongOrNull() ?: 0L).coerceIn(min, max) != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            val d = s.filter { it.isDigit() }.take(9)
            text = d
            onChange((d.toLongOrNull() ?: 0L).coerceIn(min, max))
        },
        label = { Text(label, maxLines = 1, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
