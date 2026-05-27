package com.aegis.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * DAY 15 — StatsManager
 *
 * Persists daily blocking statistics to a JSON file.
 * No Room dependency needed — simple JSON is sufficient
 * for 7–30 days of daily rollup data.
 *
 * Data model (per day):
 *   { "2024-01-15": { "blocked": 1234, "allowed": 5678, "topDomains": {...} } }
 *
 * Updated by ConnectionLog on every entry (called from DNS thread pool).
 * Persisted to disk every 60 seconds to avoid excessive I/O.
 */
object StatsManager {

    private const val TAG       = "StatsManager"
    private const val FILE      = "aegis_stats.json"
    private const val MAX_DAYS  = 30
    private const val SAVE_INTERVAL_MS = 60_000L

    data class DayStat(
        val date: String,
        val blocked: Int,
        val allowed: Int,
        val topDomains: Map<String, Int>,   // domain → count, top 10
    )

    private val lock        = ReentrantReadWriteLock()
    private val dailyStats  = LinkedHashMap<String, DayStat>()
    private var dirty       = false
    private var lastSave    = 0L
    private var ctx: Context? = null

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
    }

    // ── Record ────────────────────────────────────────────────────────────────

    fun record(domain: String, blocked: Boolean) {
        val today = fmt.format(Date())
        lock.write {
            val existing = dailyStats[today] ?: DayStat(today, 0, 0, emptyMap())
            val newTop   = existing.topDomains.toMutableMap()

            if (blocked) {
                newTop[domain] = (newTop[domain] ?: 0) + 1
                // Keep top 20 only
                if (newTop.size > 20) {
                    val minEntry = newTop.minByOrNull { it.value }
                    if (minEntry != null) newTop.remove(minEntry.key)
                }
                dailyStats[today] = existing.copy(
                    blocked    = existing.blocked + 1,
                    topDomains = newTop,
                )
            } else {
                dailyStats[today] = existing.copy(allowed = existing.allowed + 1)
            }
            dirty = true
        }

        // Periodic flush (don't write every single query)
        val now = System.currentTimeMillis()
        if (dirty && now - lastSave > SAVE_INTERVAL_MS) {
            save()
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** Last [days] days of stats, most recent first. */
    fun getLast(days: Int = 7): List<DayStat> {
        lock.read {
            return dailyStats.values.toList().takeLast(days).reversed()
        }
    }

    fun getToday(): DayStat? {
        val today = fmt.format(Date())
        return lock.read { dailyStats[today] }
    }

    fun getTotalBlocked(): Int = lock.read { dailyStats.values.sumOf { it.blocked } }
    fun getTotalAllowed(): Int = lock.read { dailyStats.values.sumOf { it.allowed } }

    fun getTopDomains(limit: Int = 10): List<Pair<String, Int>> {
        return lock.read {
            val merged = mutableMapOf<String, Int>()
            dailyStats.values.forEach { day ->
                day.topDomains.forEach { (d, c) ->
                    merged[d] = (merged[d] ?: 0) + c
                }
            }
            merged.entries.sortedByDescending { it.value }
                .take(limit)
                .map { it.key to it.value }
        }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun load() {
        val file = file() ?: return
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            lock.write {
                dailyStats.clear()
                json.keys().forEach { date ->
                    val day = json.getJSONObject(date)
                    val topJson = day.optJSONObject("topDomains") ?: JSONObject()
                    val top = mutableMapOf<String, Int>()
                    topJson.keys().forEach { d -> top[d] = topJson.getInt(d) }
                    dailyStats[date] = DayStat(
                        date       = date,
                        blocked    = day.getInt("blocked"),
                        allowed    = day.getInt("allowed"),
                        topDomains = top,
                    )
                }
                // Trim to MAX_DAYS
                while (dailyStats.size > MAX_DAYS) {
                    dailyStats.remove(dailyStats.keys.first())
                }
            }
            Log.i(TAG, "Stats loaded: ${dailyStats.size} days")
        } catch (e: Exception) {
            Log.e(TAG, "Stats load failed: ${e.message}")
        }
    }

    fun save() {
        val file = file() ?: return
        try {
            val json = JSONObject()
            lock.read {
                dailyStats.forEach { (date, stat) ->
                    val day = JSONObject()
                    day.put("blocked", stat.blocked)
                    day.put("allowed", stat.allowed)
                    val top = JSONObject()
                    stat.topDomains.forEach { (d, c) -> top.put(d, c) }
                    day.put("topDomains", top)
                    json.put(date, day)
                }
            }
            file.writeText(json.toString())
            lastSave = System.currentTimeMillis()
            dirty    = false
        } catch (e: Exception) {
            Log.e(TAG, "Stats save failed: ${e.message}")
        }
    }

    private fun file(): File? = ctx?.let { File(it.filesDir, FILE) }
}
