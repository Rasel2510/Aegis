package com.aegis.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.aegis.app/vpn"
        private const val VPN_REQUEST_CODE = 100
    }

    private lateinit var methodChannel: MethodChannel
    private var pendingResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startVpn" -> startVpn(result)
                "stopVpn" -> stopVpn(result)
                "isVpnRunning" -> result.success(AdBlockVpnService.isRunning.get())
                "getAdsBlocked" -> result.success(AdBlockVpnService.adsBlockedTotal.get())
                "getDomainCount" -> result.success(BlocklistUpdater.getCachedDomainCount(this))
                "getLastUpdate" -> result.success(BlocklistUpdater.getLastUpdateTime(this))
                else -> result.notImplemented()
            }
        }
    }

    private fun startVpn(result: MethodChannel.Result) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // Need to ask user for VPN permission
            pendingResult = result
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
        } else {
            // Permission already granted
            launchVpnService()
            result.success(true)
        }
    }

    private fun stopVpn(result: MethodChannel.Result) {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_STOP
        }
        startService(intent)
        result.success(true)
    }

    private fun launchVpnService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        startService(intent)
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
