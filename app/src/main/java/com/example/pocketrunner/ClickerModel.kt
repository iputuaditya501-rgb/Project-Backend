package com.example.pocketrunner

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

enum class ActionType { TAP, SWIPE }

/** Posisi disimpan dalam pecahan 0..1 dari ukuran layar virtual. */
data class ClickTarget(
    val id: Long,
    val x: Float,
    val y: Float,
    val x2: Float,
    val y2: Float,
    val type: ActionType = ActionType.TAP,
    val enabled: Boolean = true,
    val intervalMs: Long = 1000L,
    val holdMs: Int = 20,
    val swipeMs: Int = 400,
    val repeat: Int = 1
)

data class ClickerConfig(
    val multi: Boolean = true,
    val targets: List<ClickTarget> = emptyList(),
    val randomIntervalMs: Int = 0,
    val randomPosPx: Int = 0,
    val startDelaySec: Int = 0,
    val maxCycles: Int = 0,
    val maxMinutes: Int = 0,
    val markerDp: Int = 44,
    val markerAlpha: Float = 0.75f,
    val showMarkers: Boolean = true
)

object ClickerRuntime {
    const val IDLE = 0
    const val COUNTDOWN = 1
    const val RUNNING = 2

    var phase by mutableIntStateOf(IDLE)
    var countdown by mutableIntStateOf(0)
    var clicks by mutableLongStateOf(0L)
    var cycles by mutableIntStateOf(0)
    var elapsedSec by mutableLongStateOf(0L)
}

private fun ClickTarget.toJson() = JSONObject()
    .put("id", id).put("x", x.toDouble()).put("y", y.toDouble())
    .put("x2", x2.toDouble()).put("y2", y2.toDouble())
    .put("type", type.name).put("enabled", enabled)
    .put("interval", intervalMs).put("hold", holdMs)
    .put("swipe", swipeMs).put("repeat", repeat)

private fun targetFromJson(o: JSONObject) = ClickTarget(
    id = o.optLong("id", System.nanoTime()),
    x = o.optDouble("x", 0.5).toFloat(),
    y = o.optDouble("y", 0.5).toFloat(),
    x2 = o.optDouble("x2", 0.5).toFloat(),
    y2 = o.optDouble("y2", 0.7).toFloat(),
    type = runCatching { ActionType.valueOf(o.optString("type", "TAP")) }.getOrDefault(ActionType.TAP),
    enabled = o.optBoolean("enabled", true),
    intervalMs = o.optLong("interval", 1000L),
    holdMs = o.optInt("hold", 20),
    swipeMs = o.optInt("swipe", 400),
    repeat = o.optInt("repeat", 1).coerceAtLeast(1)
)

private fun ClickerConfig.toJson(): JSONObject {
    val arr = JSONArray()
    targets.forEach { arr.put(it.toJson()) }
    return JSONObject()
        .put("multi", multi).put("targets", arr)
        .put("randInterval", randomIntervalMs).put("randPos", randomPosPx)
        .put("startDelay", startDelaySec).put("maxCycles", maxCycles).put("maxMinutes", maxMinutes)
        .put("markerDp", markerDp).put("markerAlpha", markerAlpha.toDouble())
        .put("showMarkers", showMarkers)
}

private fun configFromJson(o: JSONObject): ClickerConfig {
    val arr = o.optJSONArray("targets") ?: JSONArray()
    val list = (0 until arr.length()).map { targetFromJson(arr.getJSONObject(it)) }
    return ClickerConfig(
        multi = o.optBoolean("multi", true),
        targets = list,
        randomIntervalMs = o.optInt("randInterval", 0),
        randomPosPx = o.optInt("randPos", 0),
        startDelaySec = o.optInt("startDelay", 0),
        maxCycles = o.optInt("maxCycles", 0),
        maxMinutes = o.optInt("maxMinutes", 0),
        markerDp = o.optInt("markerDp", 44),
        markerAlpha = o.optDouble("markerAlpha", 0.75).toFloat(),
        showMarkers = o.optBoolean("showMarkers", true)
    )
}

object ClickerStore {
    private var prefs: SharedPreferences? = null
    private const val PROFILE = "profile:"

    var cfg by mutableStateOf(ClickerConfig())
        private set
    var profiles by mutableStateOf<List<String>>(emptyList())
        private set

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("clicker", Context.MODE_PRIVATE)
        prefs = p
        cfg = p.getString("current", null)
            ?.let { runCatching { configFromJson(JSONObject(it)) }.getOrNull() }
            ?: ClickerConfig()
        refreshProfiles()
    }

    private fun refreshProfiles() {
        profiles = prefs?.all?.keys.orEmpty()
            .filter { it.startsWith(PROFILE) }
            .map { it.removePrefix(PROFILE) }
            .sortedBy { it.lowercase() }
    }

    fun update(save: Boolean = true, f: (ClickerConfig) -> ClickerConfig) {
        cfg = f(cfg)
        if (save) persist()
    }

    fun persist() {
        prefs?.edit()?.putString("current", cfg.toJson().toString())?.apply()
    }

    // ---- target ----
    fun addTarget() = update { c ->
        val shift = (c.targets.size % 5) * 0.07f
        val x = 0.3f + shift
        val y = 0.4f + shift
        c.copy(targets = c.targets + ClickTarget(System.nanoTime(), x, y, x, (y + 0.2f).coerceAtMost(0.95f)))
    }

    fun updateTarget(id: Long, f: (ClickTarget) -> ClickTarget) = update { c ->
        c.copy(targets = c.targets.map { if (it.id == id) f(it) else it })
    }

    fun removeTarget(id: Long) = update { c -> c.copy(targets = c.targets.filter { it.id != id }) }

    fun duplicateTarget(id: Long) = update { c ->
        val i = c.targets.indexOfFirst { it.id == id }
        if (i < 0) c else {
            val t = c.targets[i]
            val copy = t.copy(
                id = System.nanoTime(),
                x = (t.x + 0.04f).coerceAtMost(1f), y = (t.y + 0.04f).coerceAtMost(1f),
                x2 = (t.x2 + 0.04f).coerceAtMost(1f), y2 = (t.y2 + 0.04f).coerceAtMost(1f)
            )
            c.copy(targets = c.targets.toMutableList().apply { add(i + 1, copy) })
        }
    }

    fun moveTarget(id: Long, delta: Int) = update { c ->
        val i = c.targets.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in c.targets.indices) c else {
            val l = c.targets.toMutableList()
            val t = l.removeAt(i)
            l.add(j, t)
            c.copy(targets = l)
        }
    }

    // ---- profil ----
    fun saveProfile(name: String): Boolean {
        val n = name.trim().take(24)
        if (n.isEmpty()) return false
        prefs?.edit()?.putString(PROFILE + n, cfg.toJson().toString())?.apply()
        // apply() async: perbarui daftar secara manual
        profiles = (profiles + n).distinct().sortedBy { it.lowercase() }
        return true
    }

    fun loadProfile(name: String): Boolean {
        val s = prefs?.getString(PROFILE + name, null) ?: return false
        val c = runCatching { configFromJson(JSONObject(s)) }.getOrNull() ?: return false
        cfg = c
        persist()
        return true
    }

    fun deleteProfile(name: String) {
        prefs?.edit()?.remove(PROFILE + name)?.apply()
        profiles = profiles - name
    }
}
