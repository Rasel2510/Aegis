package com.aegis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG       = "BootReceiver"
        private const val PREFS     = "aegis_prefs"
        private const val KEY_AUTO  = "auto_start"

        fun setAutoStart(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO, enabled).apply()
        }

        fun isAutoStart(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO, false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val receivedAction = intent.action
        if (receivedAction != Intent.ACTION_BOOT_COMPLETED &&
            receivedAction != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i(TAG, "Boot received")

        if (!isAutoStart(context)) {
            Log.i(TAG, "Auto-start disabled — skipping")
            return
        }

        val vpnIntent = VpnService.prepare(context)
        if (vpnIntent != null) {
            Log.w(TAG, "VPN permission not pre-granted — cannot auto-start")
            return
        }

        Log.i(TAG, "Auto-starting VPN after boot")
        val serviceIntent = Intent(context, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        context.startForegroundService(serviceIntent)
    }
}