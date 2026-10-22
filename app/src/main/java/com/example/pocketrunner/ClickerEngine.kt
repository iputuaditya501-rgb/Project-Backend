package com.example.pocketrunner

import android.os.SystemClock
import android.view.MotionEvent
import java.util.Random

/**
 * Mesin auto clicker. Berjalan di thread sendiri dan menyuntik sentuhan langsung ke layar virtual
 * lewat Shizuku, jadi tetap jalan walau preview ditutup (mode backend).
 */
class ClickerEngine(private val svc: RunnerService) {

    @Volatile private var token = 0
    @Volatile private var active = false

    val isActive get() = active

    fun start(cfg: ClickerConfig) {
        val list = (if (cfg.multi) cfg.targets else cfg.targets.take(1)).filter { it.enabled }
        if (list.isEmpty()) { svc.toast("Belum ada target aktif"); return }
        if (!svc.hasPriv) { svc.toast("Shizuku belum terhubung"); return }
        val my: Int
        synchronized(this) {
            if (active) return
            active = true
            my = ++token
        }
        ClickerRuntime.clicks = 0L
        ClickerRuntime.cycles = 0
        ClickerRuntime.elapsedSec = 0L
        ClickerRuntime.countdown = cfg.startDelaySec
        ClickerRuntime.phase = if (cfg.startDelaySec > 0) ClickerRuntime.COUNTDOWN else ClickerRuntime.RUNNING
        Thread({ loop(my, cfg, list) }, "clicker").start()
    }

    fun stop() {
        val was: Boolean
        synchronized(this) {
            was = active
            token++
            active = false
        }
        ClickerRuntime.phase = ClickerRuntime.IDLE
        if (was) svc.onClickerStopped()
    }

    private fun alive(my: Int) = token == my

    private fun loop(my: Int, cfg: ClickerConfig, list: List<ClickTarget>) {
        val rnd = Random()
        try {
            if (cfg.startDelaySec > 0) {
                for (i in cfg.startDelaySec downTo 1) {
                    ClickerRuntime.countdown = i
                    if (!waitMs(my, 1000L, 0L)) return
                }
            }
            if (!alive(my)) return
            ClickerRuntime.phase = ClickerRuntime.RUNNING
            val t0 = SystemClock.elapsedRealtime()
            val deadline = if (cfg.maxMinutes > 0) t0 + cfg.maxMinutes * 60_000L else 0L
            var cycle = 0
            var misses = 0
            while (alive(my)) {
                for (t in list) {
                    for (n in 0 until t.repeat.coerceAtLeast(1)) {
                        if (!alive(my)) return
                        if (!svc.hasPriv) { svc.toast("Shizuku terputus, auto clicker berhenti"); return }
                        // Layar virtual hilang -> berhenti. Klik tidak boleh jatuh ke layar asli HP.
                        if (!svc.displayAlive()) {
                            if (++misses >= 2) { svc.toast("Layar virtual hilang, auto clicker berhenti"); return }
                            if (!waitMs(my, 300L, deadline)) return
                            continue
                        }
                        misses = 0
                        val began = SystemClock.elapsedRealtime()
                        perform(my, t, cfg, rnd)
                        ClickerRuntime.clicks++
                        ClickerRuntime.elapsedSec = (SystemClock.elapsedRealtime() - t0) / 1000
                        var gap = t.intervalMs
                        if (cfg.randomIntervalMs > 0) gap += rnd.nextInt(cfg.randomIntervalMs * 2 + 1) - cfg.randomIntervalMs
                        gap = gap.coerceAtLeast(10L)
                        val rest = gap - (SystemClock.elapsedRealtime() - began)
                        if (!waitMs(my, rest, deadline)) return
                    }
                }
                cycle++
                ClickerRuntime.cycles = cycle
                if (cfg.maxCycles > 0 && cycle >= cfg.maxCycles) return
                if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) return
            }
        } catch (e: Throwable) {
            svc.toast("Auto clicker error: ${e.message}")
        } finally {
            var mine = false
            synchronized(this) {
                if (token == my) { active = false; token++; mine = true }
            }
            if (mine) {
                ClickerRuntime.phase = ClickerRuntime.IDLE
                svc.onClickerStopped()
            }
        }
    }

    /** Tunggu [ms]; false kalau dihentikan atau batas waktu tercapai. */
    private fun waitMs(my: Int, ms: Long, deadline: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + ms.coerceAtLeast(0L)
        while (alive(my)) {
            val now = SystemClock.elapsedRealtime()
            if (deadline > 0 && now >= deadline) return false
            val left = end - now
            if (left <= 0) return true
            try { Thread.sleep(minOf(left, 25L)) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun sleepQuiet(ms: Long) {
        try { Thread.sleep(ms.coerceAtLeast(1L)) } catch (_: InterruptedException) {}
    }

    private fun perform(my: Int, t: ClickTarget, cfg: ClickerConfig, rnd: Random) {
        val s = svc.mapPoint(t.x, t.y, cfg.randomPosPx, rnd)
        val down = SystemClock.uptimeMillis()
        svc.rawTouch(MotionEvent.ACTION_DOWN, s[0], s[1], down, down)
        if (t.type == ActionType.TAP) {
            sleepQuiet(t.holdMs.toLong())
            svc.rawTouch(MotionEvent.ACTION_UP, s[0], s[1], down, SystemClock.uptimeMillis())
        } else {
            val e = svc.mapPoint(t.x2, t.y2, cfg.randomPosPx, rnd)
            val dur = t.swipeMs.coerceAtLeast(50)
            val steps = (dur / 16).coerceAtLeast(3)
            for (i in 1..steps) {
                if (!alive(my)) break
                sleepQuiet((dur / steps).toLong())
                val f = i.toFloat() / steps
                svc.rawTouch(
                    MotionEvent.ACTION_MOVE,
                    s[0] + (e[0] - s[0]) * f, s[1] + (e[1] - s[1]) * f,
                    down, SystemClock.uptimeMillis()
                )
            }
            svc.rawTouch(MotionEvent.ACTION_UP, e[0], e[1], down, SystemClock.uptimeMillis())
        }
    }
}
