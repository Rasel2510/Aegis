package com.aegis.app

import android.util.Log
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * DAY 17 — DoHResolver
 *
 * DNS-over-HTTPS upstream resolver.
 * Sends DNS queries as wireformat bytes over HTTPS (RFC 8484).
 * Responses are cached by DnsCache.
 *
 * Used by LocalDnsServer as a drop-in replacement for the plain
 * UDP upstream when the user enables DoH in settings.
 *
 * Providers:
 *   Cloudflare  https://1.1.1.1/dns-query
 *   Google      https://8.8.8.8/dns-query
 *   AdGuard     https://dns.adguard.com/dns-query  (blocks ads at DNS level too)
 *   Custom      user-supplied URL
 *
 * Falls back to plain UDP if DoH fails.
 */
class DoHResolver(
    private val serverUrl: String,
    private val protectSocket: (Socket) -> Boolean,
) {
    companion object {
        private const val TAG        = "DoHResolver"
        private const val TIMEOUT_MS = 5_000

        // Well-known DoH endpoints
        const val CLOUDFLARE = "https://1.1.1.1/dns-query"
        const val GOOGLE     = "https://8.8.8.8/dns-query"
        const val ADGUARD    = "https://dns.adguard.com/dns-query"
        const val NEXTDNS    = "https://dns.nextdns.io/dns-query"
    }

    /**
     * Resolve [query] (raw DNS wireformat bytes) via DoH.
     * Returns raw DNS response bytes, or null on failure.
     */
    fun resolve(query: ByteArray): ByteArray? {
        // Check cache first
        val domain = extractDomain(query)
        if (domain != null) {
            DnsCache.get(domain)?.let {
                Log.d(TAG, "Cache hit: $domain")
                return updateQueryId(it, query)  // match the query ID
            }
        }

        return try {
            val response = sendDoH(query)
            if (response != null && domain != null) {
                DnsCache.put(domain, response)
            }
            response
        } catch (e: Exception) {
            Log.w(TAG, "DoH failed for $domain: ${e.message}")
            null
        }
    }

    // ── HTTP request ──────────────────────────────────────────────────────────

    private fun sendDoH(query: ByteArray): ByteArray? {
        val conn = URL(serverUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/dns-message")
        conn.setRequestProperty("Accept", "application/dns-message")
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout    = TIMEOUT_MS
        conn.doOutput = true

        // The underlying socket needs protect() so it bypasses our VPN tunnel.
        // We can't directly protect an HttpURLConnection socket, but since
        // DoH goes to a hardcoded trusted IP (not through our tun device when
        // using addDisallowedApplication for ourselves), it routes correctly.
        // If needed, use OkHttp with a custom socket factory for full protect().

        return try {
            conn.connect()
            conn.outputStream.use { it.write(query) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "DoH HTTP ${conn.responseCode}")
                return null
            }

            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Extract domain from DNS query wireformat (same as LocalDnsServer.parseDomain). */
    private fun extractDomain(query: ByteArray): String? {
        if (query.size < 13) return null
        return try {
            val sb = StringBuilder()
            var pos = 12
            var first = true
            while (pos < query.size) {
                val labelLen = query[pos].toInt() and 0xFF
                pos++
                if (labelLen == 0) break
                if (labelLen and 0xC0 == 0xC0) break
                if (labelLen > 63 || pos + labelLen > query.size) return null
                if (!first) sb.append('.')
                first = false
                repeat(labelLen) { sb.append(query[pos++].toInt().toChar()) }
            }
            sb.toString().lowercase()
        } catch (_: Exception) { null }
    }

    /**
     * Copy the 2-byte query ID from [query] into [response].
     * DNS: ID is the first 2 bytes. Cache stores responses with the
     * original query ID; when replaying we need to match the new query ID.
     */
    private fun updateQueryId(response: ByteArray, query: ByteArray): ByteArray {
        if (response.size < 2 || query.size < 2) return response
        val updated = response.copyOf()
        updated[0] = query[0]
        updated[1] = query[1]
        return updated
    }
}
