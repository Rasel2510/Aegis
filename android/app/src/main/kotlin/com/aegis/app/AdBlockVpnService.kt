package com.aegis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG = "AdBlockVPN"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "adblock_vpn_channel"

        const val ACTION_START = "com.aegis.app.START_VPN"
        const val ACTION_STOP = "com.aegis.app.STOP_VPN"

        // Fake DNS server IP that we intercept inside the VPN tunnel
        private const val FAKE_DNS_IP = "10.0.0.1"
        // Real upstream DNS servers used via protected sockets (bypass our tunnel)
        private val UPSTREAM_DNS = listOf("1.1.1.1", "8.8.8.8")

        var adsBlockedTotal = AtomicInteger(0)
        val isRunning = AtomicBoolean(false)
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val blockedDomains = mutableSetOf<String>()
    // Separate thread pool for upstream DNS forwarding (non-blocking)
    private val dnsExecutor = Executors.newFixedThreadPool(4)

    override fun onCreate() {
        super.onCreate()
        loadBlocklist()
    }

    private fun loadBlocklist() {
        BlocklistUpdater.load(this, blockedDomains) {
            Log.d(TAG, "Blocklist refreshed: ${blockedDomains.size} domains now active")
            updateNotification(adsBlockedTotal.get())
        }
        Log.d(TAG, "Initial blocklist: ${blockedDomains.size} domains loaded")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            else -> {
                startVpn()
                START_STICKY
            }
        }
    }

    private fun startVpn() {
        if (running.get()) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Aegis — Active", "Blocking ads system-wide..."))

        try {
            val builder = Builder()
                .setSession("Aegis")
                .addAddress("10.0.0.2", 32)
                // KEY FIX: Only route our fake DNS IP through the tunnel.
                // Real traffic (YouTube, Messenger, etc.) is NOT routed through us —
                // it goes directly to the internet. We ONLY intercept DNS queries.
                .addRoute(FAKE_DNS_IP, 32)
                // Point the system's DNS resolver at our fake server inside the tunnel
                .addDnsServer(FAKE_DNS_IP)
                .setMtu(1500)
                .setBlocking(true)

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface")
                stopSelf()
                return
            }

            running.set(true)
            isRunning.set(true)

            vpnThread = Thread({ runVpnLoop() }, "VpnThread").apply { start() }

            Log.d(TAG, "VPN started — DNS-only interception active")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VPN: ${e.message}")
            stopSelf()
        }
    }

    private fun runVpnLoop() {
        val vpnFd = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(vpnFd)
        val outputStream = FileOutputStream(vpnFd)
        val packetBuf = ByteBuffer.allocate(32767)

        while (running.get()) {
            try {
                packetBuf.clear()
                val length = inputStream.read(packetBuf.array())
                if (length <= 0) {
                    Thread.sleep(5)
                    continue
                }
                packetBuf.limit(length)

                val ipVersion = (packetBuf.get(0).toInt() shr 4) and 0xF
                if (ipVersion == 4) {
                    handleIPv4Packet(packetBuf, length, outputStream)
                }
                // IPv6 not routed through tunnel so we won't see it here
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "VPN loop error: ${e.message}")
                }
            }
        }
    }

    private fun handleIPv4Packet(packet: ByteBuffer, length: Int, outputStream: FileOutputStream) {
        try {
            val protocol = packet.get(9).toInt() and 0xFF
            if (protocol != 17) return // Only UDP; DNS only uses UDP port 53

            val dstPort = ((packet.get(22).toInt() and 0xFF) shl 8) or (packet.get(23).toInt() and 0xFF)
            if (dstPort != 53) return

            val dnsPayloadOffset = 28 // 20 IP header + 8 UDP header
            if (length <= dnsPayloadOffset + 12) return

            // Make a copy of packet bytes for async forwarding (packet buffer is reused)
            val packetCopy = ByteArray(length)
            System.arraycopy(packet.array(), 0, packetCopy, 0, length)

            val domain = parseDnsQuery(packet, dnsPayloadOffset, length)
            Log.d(TAG, "DNS query: $domain")

            if (domain != null && shouldBlock(domain)) {
                Log.d(TAG, "BLOCKED: $domain")
                adsBlockedTotal.incrementAndGet()
                updateNotification(adsBlockedTotal.get())
                val response = buildNxDomainResponse(packetCopy, length, dnsPayloadOffset)
                if (response != null) {
                    outputStream.write(response)
                }
                return
            }

            // Not blocked — forward to real upstream DNS asynchronously
            forwardDnsToUpstream(packetCopy, length, dnsPayloadOffset, outputStream)

        } catch (e: Exception) {
            Log.e(TAG, "handleIPv4Packet error: ${e.message}")
        }
    }

    /**
     * Forward a DNS query to a real upstream DNS server using a protected socket.
     * protect() is the key call — it routes this socket OUTSIDE our VPN tunnel.
     */
    private fun forwardDnsToUpstream(
        packetBytes: ByteArray,
        length: Int,
        dnsOffset: Int,
        outputStream: FileOutputStream
    ) {
        val dnsLen = length - dnsOffset
        val dnsPayload = ByteArray(dnsLen)
        System.arraycopy(packetBytes, dnsOffset, dnsPayload, 0, dnsLen)

        dnsExecutor.submit {
            for (upstream in UPSTREAM_DNS) {
                try {
                    val socket = DatagramSocket()
                    protect(socket) // Bypass our own VPN so we don't loop forever

                    val upstreamAddr = InetAddress.getByName(upstream)
                    socket.soTimeout = 3000
                    socket.send(DatagramPacket(dnsPayload, dnsLen, upstreamAddr, 53))

                    val responseBuf = ByteArray(4096)
                    val receivePacket = DatagramPacket(responseBuf, responseBuf.size)
                    socket.receive(receivePacket)
                    socket.close()

                    val wrapped = wrapDnsResponse(
                        packetBytes, length, dnsOffset,
                        responseBuf, receivePacket.length
                    )
                    if (wrapped != null) {
                        synchronized(outputStream) {
                            outputStream.write(wrapped)
                        }
                    }
                    return@submit
                } catch (e: Exception) {
                    Log.w(TAG, "Upstream DNS $upstream failed: ${e.message}")
                }
            }
            Log.e(TAG, "All upstream DNS servers failed for query")
        }
    }

    /**
     * Wrap a raw DNS response payload back into an IP+UDP packet
     * addressed to the original querying app (swap src/dst).
     */
    private fun wrapDnsResponse(
        originalPacket: ByteArray,
        originalLength: Int,
        dnsOffset: Int,
        dnsResponse: ByteArray,
        dnsResponseLen: Int
    ): ByteArray? {
        return try {
            val totalLen = dnsOffset + dnsResponseLen
            val response = ByteArray(totalLen)

            // Copy original IP+UDP header as template
            System.arraycopy(originalPacket, 0, response, 0, dnsOffset)

            // Swap src/dst IP (bytes 12-15 ↔ 16-19)
            for (i in 0..3) {
                val tmp = response[12 + i]
                response[12 + i] = response[16 + i]
                response[16 + i] = tmp
            }

            // Swap src/dst port (bytes 20-21 ↔ 22-23)
            val p0 = response[20]; val p1 = response[21]
            response[20] = response[22]; response[21] = response[23]
            response[22] = p0; response[23] = p1

            // Update IP total length
            response[2] = (totalLen shr 8).toByte()
            response[3] = (totalLen and 0xFF).toByte()
            response[8] = 64  // TTL
            response[9] = 17  // Protocol = UDP

            // Update UDP length
            val udpLen = 8 + dnsResponseLen
            response[24] = (udpLen shr 8).toByte()
            response[25] = (udpLen and 0xFF).toByte()
            response[26] = 0; response[27] = 0  // UDP checksum = 0 (valid for VPN loopback)

            // Copy real DNS response payload
            System.arraycopy(dnsResponse, 0, response, dnsOffset, dnsResponseLen)

            // Recompute IP header checksum
            response[10] = 0; response[11] = 0
            val checksum = ipChecksum(response, 0, 20)
            response[10] = (checksum shr 8).toByte()
            response[11] = (checksum and 0xFF).toByte()

            response
        } catch (e: Exception) {
            Log.e(TAG, "wrapDnsResponse error: ${e.message}")
            null
        }
    }

    private fun parseDnsQuery(packet: ByteBuffer, offset: Int, length: Int): String? {
        return try {
            var pos = offset + 12 // Skip 12-byte DNS header
            val sb = StringBuilder()
            var first = true

            while (pos < length) {
                val labelLen = packet.get(pos).toInt() and 0xFF
                pos++
                if (labelLen == 0) break
                if (labelLen > 63 || pos + labelLen > length) return null
                if (!first) sb.append('.')
                first = false
                repeat(labelLen) {
                    sb.append(packet.get(pos++).toChar())
                }
            }
            sb.toString().lowercase()
        } catch (e: Exception) {
            null
        }
    }

    private fun shouldBlock(domain: String): Boolean {
        if (blockedDomains.contains(domain)) return true

        // Iterative parent-domain check — fixed from v2's broken index arithmetic
        // "r3---sn-abc.googlevideo.com" → check "googlevideo.com" etc.
        var check = domain
        while (true) {
            val dot = check.indexOf('.')
            if (dot == -1 || dot == check.length - 1) break
            check = check.substring(dot + 1)
            if (blockedDomains.contains(check)) return true
        }
        return false
    }

    private fun buildNxDomainResponse(packetBytes: ByteArray, length: Int, dnsOffset: Int): ByteArray? {
        return try {
            val response = packetBytes.copyOf(length)

            // Swap src/dst IP
            for (i in 0..3) {
                val tmp = response[12 + i]
                response[12 + i] = response[16 + i]
                response[16 + i] = tmp
            }
            // Swap src/dst port
            val p0 = response[20]; val p1 = response[21]
            response[20] = response[22]; response[21] = response[23]
            response[22] = p0; response[23] = p1

            val dnsLen = length - dnsOffset
            val udpLen = 8 + dnsLen
            response[24] = (udpLen shr 8).toByte()
            response[25] = (udpLen and 0xFF).toByte()
            response[26] = 0; response[27] = 0

            // DNS flags: QR=1 (response), AA=1, RCODE=3 (NXDOMAIN)
            response[dnsOffset + 2] = (response[dnsOffset + 2].toInt() or 0x84).toByte()
            response[dnsOffset + 3] = (response[dnsOffset + 3].toInt() and 0xF0.inv() or 0x03).toByte()

            response[2] = (length shr 8).toByte()
            response[3] = (length and 0xFF).toByte()
            response[8] = 64; response[9] = 17

            response[10] = 0; response[11] = 0
            val checksum = ipChecksum(response, 0, 20)
            response[10] = (checksum shr 8).toByte()
            response[11] = (checksum and 0xFF).toByte()

            response
        } catch (e: Exception) {
            null
        }
    }

    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if ((length and 1) != 0) sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun stopVpn() {
        running.set(false)
        isRunning.set(false)
        vpnThread?.interrupt()
        vpnThread = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(true)
        stopSelf()
        Log.d(TAG, "VPN stopped")
    }

    override fun onDestroy() {
        stopVpn()
        dnsExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Aegis VPN", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows when ad blocking is active" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AdBlockVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openIntent)
                .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(count: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification("Aegis — Active", "\uD83D\uDEE1\uFE0F $count ads blocked"))
    }
}
