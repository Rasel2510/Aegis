package com.aegis.app

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object BlocklistUpdater {

    private const val TAG = "BlocklistUpdater"
    private const val CACHE_FILE = "blocklist_cache.txt"
    private const val LAST_UPDATE_KEY = "last_update"
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours

    // Curated blocklists — includes YouTube-specific ad lists
    private val BLOCKLIST_URLS = listOf(
        // Steven Black unified (ads + malware) ~70k domains
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        // AdAway default ~15k domains
        "https://adaway.org/hosts.txt",
        // hagezi's YouTube ad list — specifically targets YouTube ad servers
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/hosts/pro.txt",
        // oisd big list — excellent coverage
        "https://big.oisd.nl/domainswild"
    )

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Load blocklist into the provided set.
     * 1. Always loads the bundled fallback first (works offline)
     * 2. Loads cached download if available
     * 3. Downloads fresh lists in background if 24h have passed
     */
    fun load(context: Context, domains: MutableSet<String>, onUpdated: (() -> Unit)? = null) {
        // Step 1: Load bundled fallback (always available, works offline)
        loadBundled(context, domains)
        Log.d(TAG, "Bundled list loaded: ${domains.size} domains")

        // Step 2: Load cached download if exists
        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (cacheFile.exists()) {
            loadFromFile(cacheFile, domains)
            Log.d(TAG, "Cache loaded: ${domains.size} domains total")
        }

        // Step 3: Background refresh if stale
        val prefs = context.getSharedPreferences("aegis_prefs", Context.MODE_PRIVATE)
        val lastUpdate = prefs.getLong(LAST_UPDATE_KEY, 0L)
        val now = System.currentTimeMillis()

        if (now - lastUpdate > UPDATE_INTERVAL_MS) {
            Log.d(TAG, "Blocklist stale — downloading fresh lists...")
            executor.submit {
                downloadAndMerge(context, domains, onUpdated)
                prefs.edit().putLong(LAST_UPDATE_KEY, System.currentTimeMillis()).apply()
            }
        } else {
            val hoursAgo = (now - lastUpdate) / 3600000
            Log.d(TAG, "Blocklist fresh (updated ${hoursAgo}h ago)")
        }
    }

    private fun loadBundled(context: Context, domains: MutableSet<String>) {
        try {
            context.assets.open("blocklist.txt").bufferedReader().forEachLine { line ->
                parseLine(line)?.let { domains.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled list: ${e.message}")
        }
    }

    private fun loadFromFile(file: File, domains: MutableSet<String>) {
        try {
            file.bufferedReader().forEachLine { line ->
                parseLine(line)?.let { domains.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache file: ${e.message}")
        }
    }

    private fun downloadAndMerge(
        context: Context,
        domains: MutableSet<String>,
        onUpdated: (() -> Unit)?
    ) {
        val newDomains = mutableSetOf<String>()
        var anySuccess = false

        for (url in BLOCKLIST_URLS) {
            try {
                Log.d(TAG, "Downloading: $url")
                val count = downloadList(url, newDomains)
                Log.d(TAG, "Got $count domains from $url")
                anySuccess = true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download $url: ${e.message}")
            }
        }

        if (anySuccess && newDomains.isNotEmpty()) {
            saveCacheFile(context, newDomains)

            val before = domains.size
            domains.addAll(newDomains)
            val added = domains.size - before
            Log.d(TAG, "Blocklist updated: ${domains.size} total (+$added new)")

            onUpdated?.invoke()
        }
    }

    private fun downloadList(urlString: String, domains: MutableSet<String>): Int {
        var count = 0
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Aegis-AdBlocker/2.0")

        try {
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().forEachLine { line ->
                parseLine(line)?.let {
                    domains.add(it)
                    count++
                }
            }
        } finally {
            conn.disconnect()
        }
        return count
    }

    private fun saveCacheFile(context: Context, domains: Set<String>) {
        try {
            val file = File(context.filesDir, CACHE_FILE)
            file.bufferedWriter().use { out ->
                domains.forEach { out.write(it + "\n") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache file: ${e.message}")
        }
    }

    /**
     * Parse a hosts file or blocklist line into a plain domain.
     * Handles:
     *   0.0.0.0 ads.example.com
     *   127.0.0.1 ads.example.com
     *   ||ads.example.com^        (AdBlock Plus format)
     *   ads.example.com           (plain domain)
     *   # comment / ! comment
     */
    private fun parseLine(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('!')) return null

        val domain: String = when {
            trimmed.startsWith("0.0.0.0 ") || trimmed.startsWith("127.0.0.1 ") -> {
                val parts = trimmed.split("\\s+".toRegex())
                if (parts.size < 2) return null
                parts[1].lowercase().trim()
            }
            trimmed.startsWith("||") && trimmed.endsWith("^") -> {
                trimmed.removePrefix("||").removeSuffix("^").lowercase().trim()
            }
            else -> trimmed.split('#')[0].trim().lowercase()
        }

        return if (isValidDomain(domain)) domain else null
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (!domain.contains('.')) return false
        if (domain.startsWith('.') || domain.endsWith('.')) return false
        if (domain == "localhost" || domain == "local") return false
        if (domain.startsWith("local.")) return false
        // Allowlist: never block domains that would break normal apps
        if (NEVER_BLOCK.any { domain == it || domain.endsWith(".$it") }) return false
        return domain.matches(Regex("^[a-z0-9][a-z0-9.\\-]*[a-z0-9]$"))
    }

    /**
     * Domains that must NEVER be blocked — their subdomains are used by
     * the apps that should work normally (Messenger, YouTube video, etc.)
     */
    private val NEVER_BLOCK = setOf(
        // Facebook / Meta — Messenger depends on these
        "facebook.com",
        "messenger.com",
        "fbcdn.net",
        "instagram.com",
        "whatsapp.com",
        "whatsapp.net",
        // Google core — blocking breaks everything
        "google.com",
        "googleapis.com",
        "gstatic.com",
        "ggpht.com",
        // YouTube core — blocking breaks video playback
        "youtube.com",
        "youtu.be",
        "ytimg.com",
        // YouTube video delivery — must stay unblocked for video to play
        // Ad subdomains within googlevideo.com are blocked via specific entries
        "googlevideo.com"
    )

    fun getCachedDomainCount(context: Context): Int {
        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (!cacheFile.exists()) return 0
        return try {
            cacheFile.bufferedReader().lineSequence()
                .count { !it.startsWith('#') && it.isNotBlank() }
        } catch (e: Exception) { 0 }
    }

    fun getLastUpdateTime(context: Context): Long {
        return context.getSharedPreferences("aegis_prefs", Context.MODE_PRIVATE)
            .getLong(LAST_UPDATE_KEY, 0L)
    }
}
