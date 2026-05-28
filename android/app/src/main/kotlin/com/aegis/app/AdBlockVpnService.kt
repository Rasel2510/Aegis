package com.aegis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AdBlockVpnService : VpnService() {

    companion object {
        private const val TAG             = "AdBlockVpnService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID      = "aegis_channel"
        private const val PREFS           = "aegis_prefs"
        private const val KEY_DOH_URL     = "doh_url"
        private const val KEY_HTTPS_ON    = "https_filtering"

        const val ACTION_START = "com.aegis.app.START"
        const val ACTION_STOP  = "com.aegis.app.STOP"

        val isRunning       = AtomicBoolean(false)
        val adsBlockedTotal = AtomicInteger(0)

        // Exposed so MainActivity can call protect() via the live service instance
        var instance: AdBlockVpnService? = null
            private set
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var dnsServer:    LocalDnsServer?       = null
    private var httpsProxy:   LocalHttpsProxy?      = null
    private var tcpForwarder: TcpForwarder?         = null
    private lateinit var engine: BlocklistEngine
    private lateinit var prefs: SharedPreferences

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs  = getSharedPreferences(PREFS, MODE_PRIVATE)
        engine = BlocklistEngine(this)
        engine.load { count ->
            Log.i(TAG, "Blocklist ready: $count domains")
            updateNotification()
        }
        ExclusionList.load(this)
        StatsManager.init(this)
        CertificateManager.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY }
        }
    }

    override fun onDestroy() {
        instance = null
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() { stopVpn() }

    // ── Start ──────────────────────────────────────────────────────────────────

    private fun startVpn() {
        if (isRunning.get()) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val httpsEnabled = prefs.getBoolean(KEY_HTTPS_ON, false) &&
                           CertificateManager.isCaInstalled()

        if (httpsEnabled) {
            val proxy = LocalHttpsProxy(engine) { sock: Socket -> protect(sock) }
            proxy.start()
            httpsProxy = proxy
            Log.i(TAG, "HTTPS proxy enabled")
        }

        val dohUrl = prefs.getString(KEY_DOH_URL, null)
        val server = LocalDnsServer(
            engine        = engine,
            protectSocket = { sock: DatagramSocket -> protect(sock) },
            onBlocked     = { _ ->
                adsBlockedTotal.incrementAndGet()
                updateNotification()
            },
            dohUrl        = dohUrl,
        )
        server.start()
        dnsServer = server

        val builder = Builder()
            .setSession("Aegis")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("127.0.0.1")
            .setMtu(1500)
            .setBlocking(httpsEnabled)

        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        if (httpsEnabled) {
            ExclusionList.getAll().forEach { pkg ->
                try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
            }
            builder.addRoute("0.0.0.0", 1)
            builder.addRoute("128.0.0.0", 1)
        }

        val pfd = builder.establish()
        if (pfd == null) {
            Log.e(TAG, "VPN establish failed")
            dnsServer?.stop(); dnsServer = null
            httpsProxy?.stop(); httpsProxy = null
            stopSelf(); return
        }
        vpnInterface = pfd

        if (httpsEnabled) {
            val fwd = TcpForwarder(
                tunFd         = pfd.fileDescriptor,
                protectSocket = { sock: Socket -> protect(sock) },
                httpsEnabled  = true,
            )
            fwd.start()
            tcpForwarder = fwd
        }

        VpnWatchdog.start(this)

        isRunning.set(true)
        BootReceiver.setAutoStart(this, true)
        Log.i(TAG, "VPN started (httpsProxy=$httpsEnabled)")
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        isRunning.set(false)
        VpnWatchdog.stop()
        tcpForwarder?.stop();  tcpForwarder = null
        dnsServer?.stop();     dnsServer    = null
        httpsProxy?.stop();    httpsProxy   = null
        StatsManager.save()
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(true)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(CHANNEL_ID, "Aegis",
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ad blocking status"; setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        val count   = adsBlockedTotal.get()
        val domains = engine.domainCount()
        val open    = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop    = PendingIntent.getService(this, 1,
            Intent(this, AdBlockVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (domains > 0) "🛡 $count blocked · ${domains/1000}k rules"
                   else             "🛡 $count blocked · Loading rules..."
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Aegis — Active").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_delete, "Stop", stop)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Aegis — Active").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(open).setOngoing(true).build()
        }
    }

    private fun updateNotification() {
        if (!isRunning.get()) return
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }
}