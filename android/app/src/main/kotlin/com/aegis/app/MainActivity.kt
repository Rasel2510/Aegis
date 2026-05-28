package com.aegis.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.net.DatagramSocket
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {

    companion object {
        private const val METHOD_CHANNEL   = "com.aegis.app/vpn"
        private const val EVENT_CHANNEL    = "com.aegis.app/log"
        private const val VPN_REQUEST_CODE = 100
        private const val PREFS            = "aegis_prefs"
    }

    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var pendingResult: MethodChannel.Result? = null
    private var blocklistEngine: BlocklistEngine? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor  = Executors.newSingleThreadExecutor()

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        CertificateManager.init(this)
        StatsManager.init(this)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {

                // ── VPN control ───────────────────────────────────────────
                "startVpn"      -> startVpn(result)
                "stopVpn"       -> stopVpn(result)
                "isVpnRunning"  -> result.success(AdBlockVpnService.isRunning.get())
                "getAdsBlocked" -> result.success(AdBlockVpnService.adsBlockedTotal.get())

                // ── Blocklist ─────────────────────────────────────────────
                "getDomainCount" -> result.success(getOrCreateEngine().domainCount())
                "getLastUpdate"  -> {
                    val ms = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getLong("last_update_ms", 0L)
                    result.success(ms)
                }

                // ── Log ───────────────────────────────────────────────────
                "getLogSnapshot" -> result.success(
                    ConnectionLog.snapshot().map { it.toWire() }
                )

                // ── Debug ─────────────────────────────────────────────────
                "checkDomain" -> {
                    val domain = call.argument<String>("domain")
                    if (domain == null) {
                        result.error("BAD_ARG", "domain required", null)
                        return@setMethodCallHandler
                    }
                    val blocked = getOrCreateEngine().shouldBlock(domain.trim().lowercase())
                    result.success(mapOf("domain" to domain, "blocked" to blocked))
                }

                "getHealthStatus" -> {
                    val engine = getOrCreateEngine()
                    bgExecutor.submit {
                        val vpnService = AdBlockVpnService.instance
                        val h = HealthChecker.run(engine) { sock: DatagramSocket ->
                            vpnService?.protect(sock) ?: false
                        }
                        mainHandler.post { result.success(h.toMap()) }
                    }
                }

                // ── Stats ─────────────────────────────────────────────────
                "getStats" -> {
                    val days = StatsManager.getLast(7)
                    result.success(days.map { d ->
                        mapOf("date" to d.date, "blocked" to d.blocked, "allowed" to d.allowed)
                    })
                }

                "getTopDomains" -> {
                    val top = StatsManager.getTopDomains(10)
                    result.success(top.map { (domain, count) ->
                        mapOf("domain" to domain, "count" to count)
                    })
                }

                // ── Settings ──────────────────────────────────────────────
                "setDohUrl" -> {
                    val url = call.argument<String>("url")
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("doh_url", url).apply()
                    result.success(true)
                }
                "getDohUrl" -> result.success(
                    getSharedPreferences(PREFS, MODE_PRIVATE).getString("doh_url", null)
                )
                "setHttpsFiltering" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean("https_filtering", enabled).apply()
                    result.success(true)
                }
                "getHttpsFiltering" -> result.success(
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getBoolean("https_filtering", false)
                )
                "isCaInstalled" -> result.success(CertificateManager.isCaInstalled())
                "exportCaCert"  -> {
                    val file = CertificateManager.exportCertForInstall(this)
                    result.success(file?.absolutePath)
                }

                // ── Exclusions ────────────────────────────────────────────
                "getExclusions" -> result.success(ExclusionList.getAll().toList())
                "addExclusion"  -> {
                    val pkg = call.argument<String>("package") ?: ""
                    ExclusionList.add(this, pkg)
                    result.success(true)
                }
                "removeExclusion" -> {
                    val pkg = call.argument<String>("package") ?: ""
                    ExclusionList.remove(this, pkg)
                    result.success(true)
                }

                // ── Custom rules ──────────────────────────────────────────
                "addCustomBlock" -> {
                    val domain = call.argument<String>("domain") ?: ""
                    getOrCreateEngine().addCustomBlock(domain)
                    result.success(true)
                }
                "addCustomAllow" -> {
                    val domain = call.argument<String>("domain") ?: ""
                    getOrCreateEngine().addCustomAllow(domain)
                    result.success(true)
                }
                "getCustomRules" -> result.success(getOrCreateEngine().getCustomRules())

                else -> result.notImplemented()
            }
        }

        eventChannel = EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                ConnectionLog.setListener { entry ->
                    mainHandler.post { events.success(entry.toWire()) }
                }
            }
            override fun onCancel(arguments: Any?) = ConnectionLog.setListener(null)
        })
    }

    private fun getOrCreateEngine(): BlocklistEngine {
        return blocklistEngine
            ?: BlocklistEngine(this).also { it.load(); blocklistEngine = it }
    }

    private fun startVpn(result: MethodChannel.Result) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingResult = result
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
        } else {
            launchVpnService()
            result.success(true)
        }
    }

    private fun stopVpn(result: MethodChannel.Result) {
        startService(
            Intent(this, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_STOP
            }
        )
        result.success(true)
    }

    private fun launchVpnService() {
        startService(
            Intent(this, AdBlockVpnService::class.java).apply {
                action = AdBlockVpnService.ACTION_START
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                launchVpnService()
                pendingResult?.success(true)
            } else {
                pendingResult?.success(false)
            }
            pendingResult = null
        }
    }
}