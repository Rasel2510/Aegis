package com.aegis.app

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * DAY 5 — HealthChecker
 *
 * Runs a set of diagnostic checks and returns results that the
 * Flutter debug screen can display. Called on demand (not a loop).
 *
 * Checks:
 *   1. VPN service running
 *   2. DNS server socket alive on 127.0.0.1:5053
 *   3. Blocklist loaded (domain count > 0)
 *   4. Upstream DNS reachable (real DNS query via protected socket)
 *   5. Known ad domain is correctly blocked
 *   6. Known safe domain is correctly allowed
 */
object HealthChecker {

    private const val TAG = "HealthChecker"

    data class Result(
        val vpnRunning: Boolean,
        val dnsServerAlive: Boolean,
        val blocklistLoaded: Boolean,
        val domainCount: Int,
        val upstreamReachable: Boolean,
        val upstreamLatencyMs: Long,
        val adDomainBlocked: Boolean,       // doubleclick.net → should be blocked
        val safeDomainAllowed: Boolean,     // youtube.com → should be allowed
        val errorMessage: String?,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "vpnRunning"         to vpnRunning,
            "dnsServerAlive"     to dnsServerAlive,
            "blocklistLoaded"    to blocklistLoaded,
            "domainCount"        to domainCount,
            "upstreamReachable"  to upstreamReachable,
            "upstreamLatencyMs"  to upstreamLatencyMs,
            "adDomainBlocked"    to adDomainBlocked,
            "safeDomainAllowed"  to safeDomainAllowed,
            "errorMessage"       to errorMessage,
        )
    }

    fun run(engine: BlocklistEngine, protectSocket: (DatagramSocket) -> Boolean): Result {
        val vpnRunning     = AdBlockVpnService.isRunning.get()
        val domainCount    = engine.domainCount()
        val blocklistLoaded = domainCount > 0

        // Check 1: Can we bind a socket to our DNS server's port?
        // If the server is running, port 5053 is taken — a bind FAILS, which means it's alive.
        val dnsServerAlive = isDnsServerAlive()

        // Check 2: Upstream reachability — send a real DNS query for "example.com"
        var upstreamReachable = false
        var upstreamLatencyMs = -1L
        var errorMessage: String? = null
        try {
            val t0  = System.currentTimeMillis()
            val ok  = pingUpstream("example.com", protectSocket)
            upstreamLatencyMs = System.currentTimeMillis() - t0
            upstreamReachable = ok
        } catch (e: Exception) {
            errorMessage = "Upstream DNS: ${e.message}"
            Log.w(TAG, "Upstream check failed: ${e.message}")
        }

        // Check 3: Known ad domain must be blocked
        val adDomainBlocked = engine.shouldBlock("doubleclick.net")

        // Check 4: Known safe domain must NOT be blocked
        val safeDomainAllowed = !engine.shouldBlock("youtube.com")

        return Result(
            vpnRunning       = vpnRunning,
            dnsServerAlive   = dnsServerAlive,
            blocklistLoaded  = blocklistLoaded,
            domainCount      = domainCount,
            upstreamReachable = upstreamReachable,
            upstreamLatencyMs = upstreamLatencyMs,
            adDomainBlocked  = adDomainBlocked,
            safeDomainAllowed = safeDomainAllowed,
            errorMessage     = errorMessage,
        )
    }

    private fun isDnsServerAlive(): Boolean {
        return try {
            // Try to bind to port 5053. If it's already in use (our server is running),
            // this throws BindException — which means the server IS alive.
            val sock = DatagramSocket(LocalDnsServer.PORT, InetAddress.getLoopbackAddress())
            sock.close()
            false   // Bound successfully → port was free → server is NOT running
        } catch (_: Exception) {
            true    // Bind failed → port is in use → server IS running
        }
    }

    /**
     * Build a minimal DNS query for [domain] and send it to 1.1.1.1.
     * Returns true if we get any response back within 3 seconds.
     */
    private fun pingUpstream(domain: String, protect: (DatagramSocket) -> Boolean): Boolean {
        val query = buildDnsQuery(domain)
        val sock  = DatagramSocket()
        if (!protect(sock)) { sock.close(); return false }

        return try {
            sock.soTimeout = 3_000
            val dest = InetAddress.getByName("1.1.1.1")
            sock.send(DatagramPacket(query, query.size, dest, 53))
            val buf = ByteArray(512)
            sock.receive(DatagramPacket(buf, buf.size))
            true
        } catch (_: Exception) {
            false
        } finally {
            sock.close()
        }
    }

    /** Minimal DNS A-record query in wire format. */
    private fun buildDnsQuery(domain: String): ByteArray {
        val labels = domain.split('.')
        val nameBytes = labels.flatMap { label ->
            listOf(label.length.toByte()) + label.map { it.code.toByte() }
        } + listOf(0.toByte())

        val buf = mutableListOf<Byte>(
            0x00, 0x01,  // ID
            0x01, 0x00,  // Flags: standard query, recursion desired
            0x00, 0x01,  // QDCOUNT = 1
            0x00, 0x00,  // ANCOUNT = 0
            0x00, 0x00,  // NSCOUNT = 0
            0x00, 0x00,  // ARCOUNT = 0
        )
        buf.addAll(nameBytes)
        buf.addAll(listOf(
            0x00, 0x01,  // QTYPE  = A
            0x00, 0x01,  // QCLASS = IN
        ))
        return buf.toByteArray()
    }
}
