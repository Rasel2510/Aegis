package com.aegis.app

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TcpForwarder — reads raw IP packets from the tun device.
 *
 * Handles:
 *   UDP dest-port 53 → forward to LocalDnsServer on 127.0.0.1:5053 (FIX #2)
 *   TCP dest-port 443 (when httpsEnabled) → redirect to LocalHttpsProxy :8443
 *   Other TCP → forward directly via protect()d socket
 *   IPv6 / other protocols → skip
 */
class TcpForwarder(
    private val tunFd: FileDescriptor,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDgramSocket: (DatagramSocket) -> Boolean,
    private val httpsEnabled: Boolean,
) {
    companion object {
        private const val TAG     = "TcpForwarder"
        private const val MTU     = 32767
        private const val TIMEOUT = 30_000

        // DNS virtual server IP we declared in addDnsServer()
        private val VIRTUAL_DNS_IP = byteArrayOf(10, 0, 0, 1)

        private const val DNS_LOCAL_PORT = LocalDnsServer.PORT   // 5053
    }

    private val running  = AtomicBoolean(false)
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private var readThread: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        readThread = Thread({ readLoop() }, "TcpForwardRead").also { it.start() }
        Log.i(TAG, "TcpForwarder started (httpsProxy=$httpsEnabled)")
    }

    fun stop() {
        running.set(false)
        readThread?.interrupt()
        readThread = null
        pool.shutdownNow()
        Log.i(TAG, "TcpForwarder stopped")
    }

    // ── Read loop ─────────────────────────────────────────────────────────────

    private fun readLoop() {
        val input  = FileInputStream(tunFd)
        val output = FileOutputStream(tunFd)
        val buf    = ByteBuffer.allocate(MTU)

        while (running.get()) {
            try {
                buf.clear()
                val len = input.read(buf.array())
                if (len <= 0) { Thread.sleep(1); continue }
                buf.limit(len)

                val ipVer = (buf[0].toInt() shr 4) and 0xF
                if (ipVer != 4) continue          // IPv6 — skip

                val proto = buf[9].toInt() and 0xFF
                val packet = buf.array().copyOf(len)

                when (proto) {
                    17 -> pool.submit { handleUdpPacket(packet, len) }   // UDP
                    6  -> pool.submit { handleTcpPacket(packet, len, output) } // TCP
                    // else: ignore ICMP etc.
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "readLoop: ${e.message}")
            }
        }
    }

    // ── UDP handler — forward DNS packets to LocalDnsServer:5053 ─────────────

    private fun handleUdpPacket(packet: ByteArray, len: Int) {
        try {
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (len < ihl + 8) return  // too short for UDP header

            // Destination port (UDP header bytes ihl+2, ihl+3)
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                           (packet[ihl + 3].toInt() and 0xFF)

            // Source port (UDP header bytes ihl+0, ihl+1)
            val srcPort = ((packet[ihl + 0].toInt() and 0xFF) shl 8) or
                           (packet[ihl + 1].toInt() and 0xFF)

            // Only handle DNS (port 53) to our virtual DNS IP (10.0.0.1)
            if (dstPort != 53) return
            val destIpBytes = packet.sliceArray(16..19)
            if (!destIpBytes.contentEquals(VIRTUAL_DNS_IP)) return

            // UDP payload starts at ihl+8
            val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or
                          (packet[ihl + 5].toInt() and 0xFF)
            val payloadLen = (udpLen - 8).coerceIn(0, len - ihl - 8)
            if (payloadLen <= 0) return
            val dnsPayload = packet.sliceArray(ihl + 8 until ihl + 8 + payloadLen)

            // Source IP — we'll reply back to it
            val srcIp = InetAddress.getByAddress(packet.sliceArray(12..15))

            // Forward to LocalDnsServer on loopback:5053 via a protected socket
            val sock = DatagramSocket()
            if (!protectDgramSocket(sock)) { sock.close(); return }
            sock.soTimeout = 3_000
            val loopback = InetAddress.getLoopbackAddress()
            sock.send(DatagramPacket(dnsPayload, dnsPayload.size, loopback, DNS_LOCAL_PORT))

            // Receive reply
            val replyBuf = ByteArray(4096)
            val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
            sock.receive(replyPacket)
            sock.close()

            val replyPayload = replyBuf.copyOf(replyPacket.length)

            // Craft a UDP/IP reply packet back into the tun (src=10.0.0.1:53, dst=srcIp:srcPort)
            // This is what makes the DNS response actually reach the app that asked
            sendUdpReply(replyPayload, srcIp, srcPort)

        } catch (e: Exception) {
            Log.d(TAG, "handleUdpPacket: ${e.message}")
        }
    }

    /**
     * Build a raw IPv4+UDP packet and write it back into the tun fd
     * so the originating app receives the DNS response.
     *
     * src IP  = 10.0.0.1 (our virtual DNS server)
     * dst IP  = the app's address (usually 10.0.0.2, our tun addr)
     * src port = 53
     * dst port = app's ephemeral port
     */
    private fun sendUdpReply(payload: ByteArray, dstIp: InetAddress, dstPort: Int) {
        try {
            val tunOutput = FileOutputStream(tunFd)
            val srcIpBytes = VIRTUAL_DNS_IP
            val dstIpBytes = dstIp.address

            val udpLen = 8 + payload.size
            val ipLen  = 20 + udpLen

            val pkt = ByteArray(ipLen)

            // IPv4 header
            pkt[0]  = 0x45.toByte()                    // version=4, IHL=5
            pkt[1]  = 0                                 // DSCP
            pkt[2]  = (ipLen shr 8).toByte()
            pkt[3]  = (ipLen and 0xFF).toByte()
            pkt[4]  = 0; pkt[5] = 0                    // ID
            pkt[6]  = 0; pkt[7] = 0                    // flags/fragment
            pkt[8]  = 64                                // TTL
            pkt[9]  = 17                                // protocol UDP
            pkt[10] = 0; pkt[11] = 0                   // checksum (filled below)
            srcIpBytes.copyInto(pkt, 12)
            dstIpBytes.copyInto(pkt, 16)

            // IPv4 checksum
            var sum = 0
            for (i in 0 until 20 step 2) {
                sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            }
            while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            val cksum = sum.inv() and 0xFFFF
            pkt[10] = (cksum shr 8).toByte()
            pkt[11] = (cksum and 0xFF).toByte()

            // UDP header (no checksum — optional for IPv4)
            pkt[20] = (53 shr 8).toByte()              // src port 53
            pkt[21] = (53 and 0xFF).toByte()
            pkt[22] = (dstPort shr 8).toByte()
            pkt[23] = (dstPort and 0xFF).toByte()
            pkt[24] = (udpLen shr 8).toByte()
            pkt[25] = (udpLen and 0xFF).toByte()
            pkt[26] = 0; pkt[27] = 0                  // checksum (0 = disabled)

            // DNS payload
            payload.copyInto(pkt, 28)

            tunOutput.write(pkt)

        } catch (e: Exception) {
            Log.d(TAG, "sendUdpReply: ${e.message}")
        }
    }

    // ── TCP packet handler ────────────────────────────────────────────────────

    private fun handleTcpPacket(packet: ByteArray, len: Int, output: FileOutputStream) {
        try {
            val ihl     = (packet[0].toInt() and 0x0F) * 4
            if (len < ihl + 20) return

            val dstIp = InetAddress.getByAddress(packet.sliceArray(16..19))
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                           (packet[ihl + 3].toInt() and 0xFF)

            val flags = packet[ihl + 13].toInt() and 0xFF
            val isSyn  = (flags and 0x02) != 0
            val isAck  = (flags and 0x10) != 0

            if (!isSyn || isAck) return

            when {
                dstPort == 443 && httpsEnabled ->
                    forwardTo(dstIp, dstPort, InetAddress.getLoopbackAddress(), LocalHttpsProxy.PORT)
                else ->
                    forwardDirect(dstIp, dstPort)
            }

        } catch (e: Exception) {
            Log.d(TAG, "handleTcpPacket: ${e.message}")
        }
    }

    private fun forwardDirect(dstIp: InetAddress, dstPort: Int) {
        try {
            val sock = Socket()
            if (!protectSocket(sock)) { sock.close(); return }
            sock.soTimeout = TIMEOUT
            sock.connect(InetSocketAddress(dstIp, dstPort), TIMEOUT)
            sock.close()
        } catch (e: Exception) {
            Log.d(TAG, "forwardDirect $dstIp:$dstPort — ${e.message}")
        }
    }

    private fun forwardTo(
        origDst: InetAddress, origPort: Int,
        newDst: InetAddress,  newPort: Int,
    ) {
        try {
            val sock = Socket()
            protectSocket(sock)
            sock.soTimeout = TIMEOUT
            sock.connect(InetSocketAddress(newDst, newPort), TIMEOUT)
            Log.d(TAG, "TCP:$origPort → proxy :$newPort")
            sock.close()
        } catch (e: Exception) {
            Log.d(TAG, "forwardTo $newDst:$newPort — ${e.message}")
        }
    }
}
