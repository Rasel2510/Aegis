package com.aegis.app

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * DAY 1 — BlocklistEngine
 *
 * Thread-safe in-memory domain blocklist.
 * Uses a ReadWriteLock so DNS lookups (reads) never block each other,
 * and background updates (writes) pause reads only for the instant of a swap.
 *
 * Load order (highest priority last wins for allowlist):
 *   1. Bundled assets/blocklist.txt  — always available, works offline
 *   2. Cached download               — persisted from last successful fetch
 *   3. Background download           — runs if cache is >24 hours old
 */
class BlocklistEngine(private val context: Context) {

    companion object {
        private const val TAG = "BlocklistEngine"
        private const val CACHE_FILE = "blocklist_cache.txt"
        private const val PREFS = "aegis_prefs"
        private const val KEY_LAST_UPDATE = "last_update_ms"
        private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

        // Blocklists that actually work for ad blocking.
        // All are plain hosts-file format (0.0.0.0 domain or plain domain).
        private val SOURCES = listOf(
            // Steven Black — best general coverage, ~70k domains
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            // hagezi Pro — excellent YouTube + tracker coverage
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/pro.txt",
            // AdAway default — clean and well-maintained
            "https://adaway.org/hosts.txt",
        )

        // Domains that MUST never be blocked — blocking these breaks internet for
        // legitimate apps. Subdomains are also protected (checked by suffix).
        private val NEVER_BLOCK = setOf(
            "google.com",
            "googleapis.com",
            "gstatic.com",
            "youtube.com",
            "googlevideo.com",   // video delivery — must stay unblocked
            "ytimg.com",
            "ggpht.com",
            "facebook.com",
            "fbcdn.net",
            "messenger.com",
            "instagram.com",
            "whatsapp.com",
            "whatsapp.net",
            "apple.com",
            "icloud.com",
            "microsoft.com",
            "windows.com",
            "live.com",
            "office.com",
            "amazonaws.com",    // AWS — too many legitimate services
            "cloudfront.net",
            "fastly.net",
            "akamaihd.net",
            "cdn.cloudflare.com",
        )
    }

    // The live blocklist — swapped atomically on update
    private var blockedDomains = HashSet<String>(100_000)
    private val lock = ReentrantReadWriteLock()
    private val bgExecutor = Executors.newSingleThreadExecutor()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Load blocklist. Call once on startup.
     * Returns immediately after loading bundled + cache; downloads in background.
     */
    fun load(onReady: ((count: Int) -> Unit)? = null) {
        val initial = HashSet<String>(100_000)

        // Step 1: bundled list (fast, synchronous)
        loadBundled(initial)
        Log.i(TAG, "Bundled: ${initial.size} domains")

        // Step 2: cached download
        val cache = File(context.filesDir, CACHE_FILE)
        if (cache.exists()) {
            loadFile(cache, initial)
            Log.i(TAG, "Cache loaded → ${initial.size} total domains")
        }

        swap(initial)
        onReady?.invoke(initial.size)

        // Step 3: background refresh if stale
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL_MS) {
            bgExecutor.submit { downloadAndRefresh(onReady) }
        }
    }

    private fun shouldBlockInternal(d: String): Boolean {
        lock.read {
            // Exact match
            if (blockedDomains.contains(d)) return true

            // Parent-domain walk: "sub.ads.example.com"
            //   → check "ads.example.com", "example.com"
            var check = d
            while (true) {
                val dot = check.indexOf('.')
                if (dot == -1 || dot == check.lastIndex) break
                check = check.substring(dot + 1)
                if (blockedDomains.contains(check)) return true
            }
        }
        return false
    }

    fun domainCount(): Int = lock.read { blockedDomains.size }

    // ── Custom rules (Day 16) ─────────────────────────────────────────────────

    private val customBlock = mutableSetOf<String>()
    private val customAllow = mutableSetOf<String>()

    fun addCustomBlock(domain: String) {
        val d = domain.lowercase().trim()
        if (d.isNotEmpty()) { customBlock.add(d); Log.i(TAG, "Custom block: $d") }
    }

    fun removeCustomBlock(domain: String) {
        val d = domain.lowercase().trim()
        customBlock.remove(d)
        Log.i(TAG, "Custom block removed: $d")
    }

    fun addCustomAllow(domain: String) {
        val d = domain.lowercase().trim()
        if (d.isNotEmpty()) { customAllow.add(d); Log.i(TAG, "Custom allow: $d") }
    }

    fun removeCustomAllow(domain: String) {
        val d = domain.lowercase().trim()
        customAllow.remove(d)
        Log.i(TAG, "Custom allow removed: $d")
    }

    fun getCustomRules(): Map<String, List<String>> = mapOf(
        "block" to customBlock.toList(),
        "allow" to customAllow.toList(),
    )

    // Override shouldBlock to check custom rules first
    fun shouldBlock(domain: String): Boolean {
        val d = domain.lowercase().trimEnd('.')
        // Custom allowlist always wins
        if (customAllow.contains(d)) return false
        var check = d
        while (true) {
            val dot = check.indexOf('.')
            if (dot == -1 || dot == check.lastIndex) break
            check = check.substring(dot + 1)
            if (customAllow.contains(check)) return false
        }
        // Custom blocklist
        if (customBlock.contains(d)) return true
        // Main engine
        return shouldBlockInternal(d)
    }

    // ── Loading helpers ───────────────────────────────────────────────────────

    private fun loadBundled(into: HashSet<String>) {
        try {
            context.assets.open("blocklist.txt").bufferedReader().forEachLine { line ->
                parseLine(line)?.let { into.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bundled load failed: ${e.message}")
        }
    }

    private fun loadFile(file: File, into: HashSet<String>) {
        try {
            file.bufferedReader().forEachLine { line ->
                parseLine(line)?.let { into.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "File load failed (${file.name}): ${e.message}")
        }
    }

    // ── Background download ───────────────────────────────────────────────────

    private fun downloadAndRefresh(onReady: ((count: Int) -> Unit)?) {
        Log.i(TAG, "Downloading fresh blocklists...")
        val fresh = HashSet<String>(300_000)

        // Always include bundled entries so the cache file is self-contained
        loadBundled(fresh)

        var anySuccess = false
        for (url in SOURCES) {
            try {
                val count = downloadSource(url, fresh)
                Log.i(TAG, "  $url → +$count domains")
                anySuccess = true
            } catch (e: Exception) {
                Log.w(TAG, "  $url FAILED: ${e.message}")
            }
        }

        if (!anySuccess) {
            Log.w(TAG, "All downloads failed — keeping existing list")
            return
        }

        // Save cache
        try {
            val cache = File(context.filesDir, CACHE_FILE)
            cache.bufferedWriter().use { w ->
                fresh.forEach { w.write(it); w.newLine() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cache write failed: ${e.message}")
        }

        swap(fresh)

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()

        Log.i(TAG, "Blocklist updated: ${fresh.size} domains")
        onReady?.invoke(fresh.size)
    }

    private fun downloadSource(urlStr: String, into: HashSet<String>): Int {
        var count = 0
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout    = 45_000
        conn.setRequestProperty("User-Agent", "Aegis-AdBlocker/1.0")
        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().forEachLine { line ->
                parseLine(line)?.let { into.add(it); count++ }
            }
        } finally {
            conn.disconnect()
        }
        return count
    }

    // ── Line parser ───────────────────────────────────────────────────────────

    /**
     * Parses one line from a hosts file or plain domain list.
     * Handles:
     *   # comment / ! comment
     *   0.0.0.0 ads.example.com
     *   127.0.0.1 ads.example.com
     *   ads.example.com
     *   ||ads.example.com^   (ABP format)
     */
    private fun parseLine(raw: String): String? {
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '#' || line[0] == '!') return null

        val domain: String = when {
            line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") -> {
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return null
                parts[1].lowercase()
            }
            line.startsWith("||") && line.endsWith("^") ->
                line.removePrefix("||").removeSuffix("^").lowercase()
            else ->
                line.split('#')[0].split('!')[0].trim().lowercase()
        }

        return if (isValid(domain)) domain else null
    }

    private fun isValid(domain: String): Boolean {
        if (domain.length < 3 || domain.length > 253) return false
        if (!domain.contains('.')) return false
        if (domain.startsWith('.') || domain.endsWith('.')) return false
        if (domain == "localhost") return false
        if (!domain.matches(Regex("^[a-z0-9][a-z0-9.\\-]*$"))) return false

        // Never block protected domains or their subdomains
        if (NEVER_BLOCK.contains(domain)) return false
        for (safe in NEVER_BLOCK) {
            if (domain.endsWith(".$safe")) return false
        }
        return true
    }

    // ── Atomic swap ───────────────────────────────────────────────────────────

    private fun swap(newSet: HashSet<String>) {
        lock.write { blockedDomains = newSet }
    }
}
