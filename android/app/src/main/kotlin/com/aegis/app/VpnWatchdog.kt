package com.aegis.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * DAY 18 — VpnWatchdog
 *
 * Monitors VPN service health and restarts it if it dies.
 * Checks every 30 seconds. If the VPN is supposed to be running
 * (auto_start = true) but AdBlockVpnService.isRunning is false,
 * re-issues the start intent.
 *
 * Also monitors DNS server health via a lightweight probe:
 * sends a DNS query to 127.0.0.1:5053 and checks for a response.
 * If no response in 2 seconds, the DNS server thread likely crashed.
 */
object VpnWatchdog {

    private const val TAG           = "VpnWatchdog"
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

        if (!shouldRun) return   // user hasn't enabled auto-start

        if (!isRunning) {
            Log.w(TAG, "VPN not running — restarting")
            restart(context)
            return
        }

        // Probe DNS server
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
     * Send a DNS query for "a.b" to our local DNS server and check for a response.
     * Returns true if the server responds within DNS_PROBE_TIMEOUT ms.
     */
    private fun probeDns(): Boolean {
        return try {
            val sock = java.net.DatagramSocket()
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
