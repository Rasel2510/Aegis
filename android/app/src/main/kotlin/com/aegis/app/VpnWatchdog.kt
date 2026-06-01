package com.aegis.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import java.net.DatagramSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * VpnWatchdog — monitors VPN health and restarts if it dies.
 *
 * FIX #4: probeDns() must call protect() on its socket before sending.
 * Without protect(), while the VPN is active the probe packet re-enters
 * the tun tunnel and never reaches LocalDnsServer, causing a spurious
 * timeout → false-positive restart loop → VPN crash cycle.
 */
object VpnWatchdog {

    private const val TAG            = "VpnWatchdog"
    private const val CHECK_INTERVAL = 30L   // seconds
    private const val DNS_PROBE_TIMEOUT = 2_000

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "VpnWatchdog").apply { isDaemon = true }
    }
    private var task: ScheduledFuture<*>? = null

    fun start(context: Context) {
        if (task != null) return
        val appCtx = context.applicationContext
        task = scheduler.scheduleWithFixedDelay(
            { check(appCtx) },
            CHECK_INTERVAL,
            CHECK_INTERVAL,
            TimeUnit.SECONDS,
        )
        Log.i(TAG, "Watchdog started")
    }

    fun stop() {
        task?.cancel(false)
        task = null
        Log.i(TAG, "Watchdog stopped")
    }

    private fun check(context: Context) {
        val shouldRun = BootReceiver.isAutoStart(context)
        val isRunning = AdBlockVpnService.isRunning.get()

        if (!shouldRun) return

        if (!isRunning) {
            Log.w(TAG, "VPN not running — restarting")
            restart(context)
            return
        }

        // FIX #4: protect the probe socket so it exits the tun rather than looping back
        if (!probeDns()) {
            Log.w(TAG, "DNS server not responding — restarting VPN")
            restart(context)
        }
    }

    private fun restart(context: Context) {
        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            Log.e(TAG, "VPN permission lost — cannot auto-restart")
            return
        }
        val intent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    /**
     * Send a DNS query to our local DNS server (127.0.0.1:5053) and wait for a reply.
     * The socket is protected so it bypasses the VPN tunnel.
     */
    private fun probeDns(): Boolean {
        return try {
            val sock = DatagramSocket()

            // FIX #4: protect the socket so it goes to loopback directly,
            // not back into our own VPN tun
            val vpnService = AdBlockVpnService.instance
            if (vpnService != null) {
                if (!vpnService.protectSocket(sock)) {
                    sock.close()
                    // If we can't protect, skip the probe rather than false-positive restart
                    Log.w(TAG, "probeDns: could not protect socket — skipping probe")
                    return true
                }
            }

            sock.soTimeout = DNS_PROBE_TIMEOUT
            val addr  = java.net.InetAddress.getLoopbackAddress()
            val query = buildMinimalQuery("a.b")
            sock.send(java.net.DatagramPacket(query, query.size, addr, LocalDnsServer.PORT))
            val buf = ByteArray(512)
            sock.receive(java.net.DatagramPacket(buf, buf.size))
            sock.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildMinimalQuery(domain: String): ByteArray {
        val labels = domain.split('.')
        val name = labels.flatMap { label ->
            listOf(label.length.toByte()) + label.map { it.code.toByte() }
        } + listOf(0.toByte())
        return byteArrayOf(0x00, 0x01, 0x01, 0x00, 0x00, 0x01,
                           0x00, 0x00, 0x00, 0x00, 0x00, 0x00) +
               name.toByteArray() +
               byteArrayOf(0x00, 0x01, 0x00, 0x01)
    }
}

private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }
