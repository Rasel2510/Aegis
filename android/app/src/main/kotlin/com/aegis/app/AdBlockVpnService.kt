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

        /** Held while the service is alive so MainActivity can call protect(). */
        @Volatile var instance: AdBlockVpnService? = null
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
        // FIX #3: CertificateManager is an object (singleton) — init() is idempotent
        // but we must NOT call it here to avoid a race with MainActivity's call
        // on first launch. It is called in MainActivity.configureFlutterEngine()
        // which always runs before any user interaction that could trigger startVpn.
        // We only call it here if MainActivity somehow missed it (e.g. boot receiver path).
        if (CertificateManager.getCaCert() == null) {
            CertificateManager.init(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY }
        }
    }

    override fun onDestroy() { stopVpn(); instance = null; super.onDestroy() }
    override fun onRevoke()  { stopVpn() }

    // ── Start ──────────────────────────────────────────────────────────────────

    private fun startVpn() {
        if (isRunning.get()) return

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val httpsEnabled = prefs.getBoolean(KEY_HTTPS_ON, false) &&
                           CertificateManager.isCaInstalled()

        // 1. HTTPS proxy (must start before VPN so port is ready)
        if (httpsEnabled) {
            val proxy = LocalHttpsProxy(engine) { sock: Socket -> protect(sock) }
            proxy.start()
            httpsProxy = proxy
            Log.i(TAG, "HTTPS proxy enabled")
        }

        // 2. DNS server
        val dohUrl = prefs.getString(KEY_DOH_URL, null)
        val server = LocalDnsServer(
            engine       = engine,
            protectSocket = { sock: DatagramSocket -> protect(sock) },
            onBlocked    = { _ ->
                adsBlockedTotal.incrementAndGet()
                updateNotification()
            },
            dohUrl       = dohUrl,
        )
        server.start()
        dnsServer = server

        // 3. VPN interface
        val builder = Builder()
            .setSession("Aegis")
            .addAddress("10.0.0.2", 32)
            // FIX #2: Point DNS to our local server on port 5053.
            // The VPN DNS API sends queries to port 53 on the declared DNS server IP.
            // We use a loopback alias 10.0.0.2 itself won't work for DNS interception —
            // instead we must declare our LOCAL server IP so the OS sends DNS here.
            // The correct approach: addDnsServer points to our tun address, and
            // LocalDnsServer listens on port 53 (privileged). Since we can't bind :53
            // without root, we use the VPN trick: add a DNS route to a fake IP (10.0.0.1)
            // and the VPN intercepts that, forwarding internally to LocalDnsServer:5053.
            //
            // Simplest working fix: LocalDnsServer already listens on 127.0.0.1:5053.
            // We tell the VPN the DNS server is 10.0.0.1. We then add a host route
            // for 10.0.0.1/32 through the tun. All DNS traffic (port 53, dest 10.0.0.1)
            // enters the tun. LocalDnsServer handles it on 5053 via the redirect below.
            //
            // ACTUAL simplest fix: listen on port 53 is impossible without root.
            // The only rootless approach that works is: use addDnsServer("127.0.0.1")
            // and LocalDnsServer must listen on port 5053, but the VPN builder's
            // addDnsServer does NOT add :53 — it sets the DNS server IP. Android
            // sends DNS queries to UDP port 53 of that IP. So we need LocalDnsServer
            // on 127.0.0.1:53 OR we redirect via iptables (no root) OR we use the
            // tun approach where DNS server IP is a virtual IP we own (10.0.0.1)
            // and LocalDnsServer listens on 127.0.0.1:5053 with the TcpForwarder/
            // packet-reader redirecting port-53 packets to 5053.
            //
            // FIX: Change LocalDnsServer to listen on port 53 using the loopback
            // address via the VPN's own declared address space. Since we declare
            // 10.0.0.2/32 as our VPN address, we can use 10.0.0.1 as DNS server —
            // traffic to 10.0.0.1:53 enters the tun fd. We must then read those
            // UDP packets and forward to LocalDnsServer. This is complex.
            //
            // REAL FIX (no raw packets needed): Declare addDnsServer("10.0.0.1"),
            // add route 10.0.0.1/32, and in LocalDnsServer listen on ALL interfaces
            // on the tun address. But we can't bind 10.0.0.1 — it's a virtual addr.
            //
            // WORKING SOLUTION: use the OS loopback. The VPN DNS must be 127.0.0.1
            // and LocalDnsServer must listen on 127.0.0.1. Android does route DNS
            // queries to the declared DNS server. The catch is the port: Android
            // always sends to port 53. So LocalDnsServer needs to listen on :53.
            // Non-root apps CAN bind ports below 1024 inside a VPN context IF they
            // own the socket and it's on loopback — actually they cannot.
            //
            // DEFINITIVE FIX: Use a virtual DNS server IP (10.0.0.1) inside our
            // VPN subnet, add a /32 route for it, and use the TcpForwarder's
            // readLoop to intercept UDP port-53 packets destined for 10.0.0.1 and
            // forward them to LocalDnsServer on 127.0.0.1:5053. This is the
            // standard approach used by NetGuard, Blokada, etc.
            //
            // For now (minimal fix): declare DNS as 127.0.0.1 and change
            // LocalDnsServer port to 5353 (mDNS — also privileged) ... no.
            //
            // THE CORRECT MINIMAL FIX: point DNS to a virtual IP we control,
            // add route, use raw packet reading already present in TcpForwarder
            // to also handle UDP DNS packets. See LocalDnsServer refactor below.
            // However that's a large change. The QUICKEST real fix:
            // Keep LocalDnsServer on :5053 but use the VPN's "addDnsServer" with
            // a virtual address 10.0.0.1, add host route, and upgrade TcpForwarder
            // to forward UDP port 53 packets to 127.0.0.1:5053.
            // We implement this now.
            .addDnsServer("10.0.0.1")          // virtual DNS IP inside our VPN
            .addRoute("10.0.0.1", 32)          // route virtual DNS IP through tun
            .setMtu(1500)
            .setBlocking(true)                 // FIX #1: always blocking so readLoop works

        // Exclude our own app to prevent DNS loops
        try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}

        // Apply per-app exclusions
        ExclusionList.getAll().forEach { pkg ->
            try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
        }

        if (httpsEnabled) {
            // Route all traffic through tun so TcpForwarder can redirect TCP:443
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

        // 4. Packet forwarder — handles DNS (UDP:53 → :5053) and optionally HTTPS
        val fwd = TcpForwarder(
            tunFd         = pfd.fileDescriptor,
            protectSocket = { sock: Socket -> protect(sock) },
            protectDgramSocket = { sock: DatagramSocket -> protect(sock) },
            httpsEnabled  = httpsEnabled,
        )
        fwd.start()
        tcpForwarder = fwd

        // 5. Watchdog — pass protect() so it can protect its probe socket
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

    // ── Public socket protect (for Watchdog) ──────────────────────────────────

    fun protectSocket(sock: DatagramSocket): Boolean = protect(sock)
}
