package com.dhruv.blackout

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Usage sessions (start/end of each armed period) plus a plain-text health log for overnight runs.
 * Read the log with: adb shell run-as com.dhruv.blackout cat files/health.log
 */
object Stats {
    private const val MAX_SESSIONS = 300
    private const val BEAT_GAP_MS = 15 * 60_000L

    private fun p(c: Context) = c.getSharedPreferences("stats", Context.MODE_PRIVATE)

    private fun sessions(c: Context): List<Pair<Long, Long>> =
        p(c).getString("sessions", "").orEmpty().split(",").mapNotNull {
            val s = it.split("-")
            if (s.size == 2) (s[0].toLongOrNull() ?: return@mapNotNull null) to (s[1].toLongOrNull() ?: return@mapNotNull null) else null
        }

    private fun append(c: Context, start: Long, end: Long) {
        if (end <= start) return
        val total = closedTotal(c) + (end - start)
        val all = (sessions(c) + (start to end)).takeLast(MAX_SESSIONS)
        p(c).edit()
            .putString("sessions", all.joinToString(",") { "${it.first}-${it.second}" })
            .putLong("total", total)
            .apply()
    }

    /** Running total of closed sessions; the list is capped, so this is kept separately. */
    private fun closedTotal(c: Context): Long =
        if (p(c).contains("total")) p(c).getLong("total", 0L) else sessions(c).sumOf { it.second - it.first }

    /** Opens a session if none is open. */
    fun begin(c: Context, now: Long = System.currentTimeMillis()) {
        if (p(c).getLong("start", 0L) == 0L) p(c).edit().putLong("start", now).putLong("beat", now).apply()
    }

    fun end(c: Context, now: Long = System.currentTimeMillis()) {
        val start = p(c).getLong("start", 0L)
        if (start != 0L) append(c, start, now)
        p(c).edit().remove("start").remove("beat").apply()
    }

    fun beat(c: Context, now: Long = System.currentTimeMillis()) = p(c).edit().putLong("beat", now).apply()

    /**
     * Called when the service (re)connects while armed. If the last heartbeat is old, the phone was
     * off or the process dead: close the session at the last beat so the gap is not counted as use.
     */
    fun resume(c: Context, now: Long = System.currentTimeMillis()) {
        val start = p(c).getLong("start", 0L)
        val beat = p(c).getLong("beat", 0L)
        if (start == 0L) { begin(c, now); return }
        if (beat != 0L && now - beat > BEAT_GAP_MS) {
            append(c, start, beat)
            p(c).edit().putLong("start", now).putLong("beat", now).apply()
            health(c, "gap: no heartbeat for ${(now - beat) / 60_000} min, session split")
        }
    }

    private fun fmt(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "${m} min" else "${m / 60} h ${m % 60} min"
    }

    private fun startOfDay(now: Long) = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun overlap(list: List<Pair<Long, Long>>, from: Long, to: Long) =
        list.sumOf { (s, e) -> (minOf(e, to) - maxOf(s, from)).coerceAtLeast(0L) }

    /** Label/value rows for the setup screen. */
    fun summary(c: Context, now: Long = System.currentTimeMillis()): List<Pair<String, String>> {
        val open = if (Blackout.isArmed(c)) p(c).getLong("start", 0L) else 0L
        val all = sessions(c) + if (open != 0L) listOf(open to now) else emptyList()
        if (all.isEmpty()) return emptyList()
        val day = startOfDay(now)
        val rows = mutableListOf(
            "Today" to fmt(overlap(all, day, now)),
            "Last 7 days" to fmt(overlap(all, day - 6 * 86_400_000L, now)),
            "All time" to fmt(closedTotal(c) + if (open != 0L) now - open else 0L),
            "Sessions" to "${all.size}",
            "Longest" to fmt(all.maxOf { it.second - it.first }),
        )
        all.lastOrNull()?.let { (s, e) ->
            rows += "Last session" to
                "${SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(s))}, ${fmt(e - s)}"
        }
        return rows
    }

    fun health(c: Context, line: String) {
        runCatching {
            val f = File(c.filesDir, "health.log")
            if (f.length() > 100_000) f.writeText(f.readText().takeLast(50_000))
            f.appendText("${SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())} $line\n")
        }
    }
}
