package com.aegis.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DAY 1 — LocalDnsServer
 *
 * A real UDP DNS server that listens on 127.0.0.1:5053.
 * The VPN points addDnsServer() here. Every DNS query on the phone
 * comes through this socket — no raw packet parsing needed.
 *
 * For blocked domains → replies NXDOMAIN instantly.
 * For allowed domains → forwards to upstream (1.1.1.1) via a protect()d
 *                       socket so the query exits the device normally.
 */
class LocalDnsServer(
    private val engine: BlocklistEngine,
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val onBlocked: (domain: String) -> Unit,
    dohUrl: String? = null,
) {
    private val dohResolver: DoHResolver? = dohUrl?.let {
        DoHResolver(it) { _ -> true }  // loopback sockets don't need protect()
    }
    companion object {
        private const val TAG = "LocalDnsServer"
        const val PORT = 5053

        // Upstream resolvers — tried in order, first success wins
        private val UPSTREAM = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        private const val UPSTREAM_PORT = 53
        private const val TIMEOUT_MS = 3_000
        private const val BUF_SIZE = 4096
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: DatagramSocket? = null

    // One thread accepts queries; a pool handles upstream forwarding
    private var acceptThread: Thread? = null
    private val forwardPool: ExecutorService = Executors.newFixedThreadPool(8)

    fun start() {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Already running")
            return
        }
        try {
            serverSocket = DatagramSocket(PORT, InetAddress.getLoopbackAddress())
            serverSocket!!.reuseAddress = true
            Log.i(TAG, "DNS server listening on 127.0.0.1:$PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind port $PORT: ${e.message}")
            running.set(false)
            return
        }

        acceptThread = Thread({ acceptLoop() }, "DnsAcceptThread").also { it.start() }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        forwardPool.shutdownNow()
        Log.i(TAG, "DNS server stopped")
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        val buf = ByteArray(BUF_SIZE)

        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)                  // blocks until a query arrives

                // Copy packet data so the buffer can be reused immediately
                val data = packet.data.copyOf(packet.length)
                val clientAddr = packet.address
                val clientPort = packet.port

                forwardPool.submit { handleQuery(data, clientAddr, clientPort) }

            } catch (e: SocketException) {
                if (running.get()) Log.e(TAG, "Socket error: ${e.message}")
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Accept error: ${e.message}")
            }
        }
    }

    // ── Per-query handler (runs on forwardPool thread) ─────────────────────────

    private fun handleQuery(query: ByteArray, clientAddr: InetAddress, clientPort: Int) {
        try {
            val domain = parseDomain(query) ?: run {
                // Malformed query — forward as-is, don't drop
                forwardToUpstream(query, clientAddr, clientPort)
                return
            }

            Log.d(TAG, "DNS? $domain")

            if (engine.shouldBlock(domain)) {
                Log.d(TAG, "BLOCK → $domain")
                ConnectionLog.add(domain, blocked = true)
                StatsManager.record(domain, blocked = true)
                onBlocked(domain)
                sendNxDomain(query, clientAddr, clientPort)
            } else {
                ConnectionLog.add(domain, blocked = false)
                StatsManager.record(domain, blocked = false)
                forwardToUpstream(query, clientAddr, clientPort)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleQuery error: ${e.message}")
        }
    }

    // ── DNS domain parser ─────────────────────────────────────────────────────

    /**
     * Parse the QNAME from a raw DNS query packet.
     * DNS wire format: series of length-prefixed labels ending with 0x00.
     * Example: 3 w w w 6 g o o g l e 3 c o m 0  →  "www.google.com"
     */
    fun parseDomain(packet: ByteArray): String? {
        // DNS header is 12 bytes; QNAME starts at offset 12
        if (packet.size < 13) return null
        return try {
            val sb = StringBuilder()
            var pos = 12
            var first = true
            while (pos < packet.size) {
                val labelLen = packet[pos].toInt() and 0xFF
                pos++
                if (labelLen == 0) break                  // end of QNAME
                if (labelLen and 0xC0 == 0xC0) break      // pointer — ignore, query won't have these
                if (labelLen > 63 || pos + labelLen > packet.size) return null
                if (!first) sb.append('.')
                first = false
                repeat(labelLen) { sb.append(packet[pos++].toInt().toChar()) }
            }
            sb.toString().lowercase().trimEnd('.')
        } catch (_: Exception) { null }
    }

    // ── NXDOMAIN reply ────────────────────────────────────────────────────────

    /**
     * Build a minimal NXDOMAIN response and send it back to the asking app.
     * We copy the original query, flip the QR bit, set RCODE=3 (NXDOMAIN).
     */
    private fun sendNxDomain(query: ByteArray, addr: InetAddress, port: Int) {
        if (query.size < 12) return
        val response = query.copyOf()

        // Byte 2-3 are the FLAGS field
        // QR=1 (response), Opcode=0, AA=0, TC=0, RD=1 (copy), RA=1, RCODE=3
        response[2] = (response[2].toInt() or 0x80).toByte()   // set QR bit
        response[3] = (0x80 or 0x03).toByte()                  // RA=1, RCODE=3 (NXDOMAIN)

        // Zero out ANCOUNT, NSCOUNT, ARCOUNT — no records in a NXDOMAIN
        response[6] = 0; response[7] = 0   // ANCOUNT
        response[8] = 0; response[9] = 0   // NSCOUNT
        response[10] = 0; response[11] = 0 // ARCOUNT

        send(response, addr, port)
    }

    // ── Upstream forwarding ────────────────────────────────────────────────────

    /**
     * Forward a DNS query to a real upstream server using a VPN-protected socket.
     * protect() is what makes this work: it marks the socket so Android routes it
     * OUTSIDE our VPN tunnel, preventing an infinite loop.
     */
    private fun forwardToUpstream(query: ByteArray, clientAddr: InetAddress, clientPort: Int) {
        val domain = parseDomain(query)

        // 1. Cache hit — reply instantly
        if (domain != null) {
            DnsCache.get(domain)?.let { cached ->
                val reply = cached.copyOf()
                reply[0] = query[0]; reply[1] = query[1]  // match query ID
                send(reply, clientAddr, clientPort)
                return
            }
        }

        // 2. DoH resolver (if enabled)
        if (dohResolver != null) {
            val response = dohResolver.resolve(query)
            if (response != null) {
                if (domain != null) DnsCache.put(domain, response)
                send(response, clientAddr, clientPort)
                return
            }
        }

        // 3. Plain UDP upstream
        for (upstream in UPSTREAM) {
            try {
                val sock = DatagramSocket()
                if (!protectSocket(sock)) { sock.close(); continue }
                sock.soTimeout = TIMEOUT_MS
                val upAddr = InetAddress.getByName(upstream)
                sock.send(DatagramPacket(query, query.size, upAddr, UPSTREAM_PORT))
                val responseBuf = ByteArray(BUF_SIZE)
                val receivePacket = DatagramPacket(responseBuf, responseBuf.size)
                sock.receive(receivePacket)
                sock.close()
                val responseData = responseBuf.copyOf(receivePacket.length)
                if (domain != null) DnsCache.put(domain, responseData)
                send(responseData, clientAddr, clientPort)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Upstream $upstream failed: ${e.message}")
            }
        }
        sendServFail(query, clientAddr, clientPort)
    }

    private fun sendServFail(query: ByteArray, addr: InetAddress, port: Int) {
        if (query.size < 12) return
        val response = query.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = (0x80 or 0x02).toByte()  // RCODE=2 (SERVFAIL)
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        send(response, addr, port)
    }

    // ── Send helper ───────────────────────────────────────────────────────────

    private fun send(data: ByteArray, addr: InetAddress, port: Int) {
        try {
            serverSocket?.send(DatagramPacket(data, data.size, addr, port))
        } catch (e: Exception) {
            if (running.get()) Log.e(TAG, "send() failed: ${e.message}")
        }
    }
}
