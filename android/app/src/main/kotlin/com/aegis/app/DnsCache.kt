package com.aegis.app

import android.util.LruCache
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * DAY 18 — DnsCache
 *
 * TTL-respecting in-memory cache for DNS responses.
 * Reduces upstream queries by ~80% for typical browsing —
 * most domains are queried repeatedly (every image load,
 * every API call) so caching the response saves round-trips.
 *
 * Key: domain name (lowercase)
 * Value: raw DNS response bytes + expiry timestamp
 *
 * Max entries: 2000 (LruCache evicts least-recently-used)
 * Min TTL: 60s  (we don't honour TTLs under 1 minute)
 * Max TTL: 1800s (cap at 30 minutes regardless of server value)
 */
object DnsCache {

    private const val MAX_ENTRIES = 2_000
    private const val MIN_TTL_MS  = 60_000L
    private const val MAX_TTL_MS  = 1_800_000L

    data class Entry(
        val response: ByteArray,
        val expiryMs: Long,
    ) {
        val isExpired get() = System.currentTimeMillis() > expiryMs
    }

    private val cache = LruCache<String, Entry>(MAX_ENTRIES)
    private val lock  = ReentrantReadWriteLock()

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns cached response bytes if present and not expired, else null. */
    fun get(domain: String): ByteArray? {
        lock.read {
            val entry = cache[domain.lowercase()] ?: return null
            if (entry.isExpired) { lock.write { cache.remove(domain.lowercase()) }; return null }
            return entry.response
        }
    }

    /** Store a DNS response. TTL is parsed from the response bytes. */
    fun put(domain: String, response: ByteArray) {
        val ttlMs = (parseTtl(response) * 1000L)
            .coerceIn(MIN_TTL_MS, MAX_TTL_MS)
        val entry = Entry(
            response = response.copyOf(),
            expiryMs = System.currentTimeMillis() + ttlMs,
        )
        lock.write { cache.put(domain.lowercase(), entry) }
    }

    fun clear() = lock.write { cache.evictAll() }

    fun size(): Int = lock.read { cache.size() }

    // ── TTL parser ────────────────────────────────────────────────────────────

    /**
     * Parse the TTL from the first answer record in a DNS response.
     * DNS response format (simplified):
     *   12 bytes header → question section → answer records
     * Each answer record: name(var) type(2) class(2) ttl(4) rdlen(2) rdata(var)
     * Returns TTL in seconds, default 300 if parsing fails.
     */
    private fun parseTtl(response: ByteArray): Int {
        if (response.size < 12) return 300
        return try {
            val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
            if (qdCount == 0) return 300

            var pos = 12
            // Skip question section
            repeat(qdCount) {
                while (pos < response.size) {
                    val len = response[pos].toInt() and 0xFF
                    if (len == 0) { pos++; break }
                    if (len and 0xC0 == 0xC0) { pos += 2; break }  // pointer
                    pos += len + 1
                }
                pos += 4  // skip QTYPE + QCLASS
            }

            val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
            if (anCount == 0) return 300

            // Skip name in first answer (pointer or labels)
            if (pos < response.size) {
                if (response[pos].toInt() and 0xC0 == 0xC0) {
                    pos += 2  // compressed name pointer
                } else {
                    while (pos < response.size) {
                        val len = response[pos].toInt() and 0xFF
                        if (len == 0) { pos++; break }
                        pos += len + 1
                    }
                }
            }

            if (pos + 8 > response.size) return 300

            // TTL is at offset +4 from start of answer record (after type + class)
            val ttl = ((response[pos + 4].toInt() and 0xFF) shl 24) or
                      ((response[pos + 5].toInt() and 0xFF) shl 16) or
                      ((response[pos + 6].toInt() and 0xFF) shl 8) or
                       (response[pos + 7].toInt() and 0xFF)
            ttl.coerceIn(60, 1800)
        } catch (_: Exception) { 300 }
    }
}
