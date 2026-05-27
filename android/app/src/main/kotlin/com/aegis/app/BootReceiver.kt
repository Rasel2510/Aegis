package com.aegis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * DAY 14 — BootReceiver
 *
 * Starts the VPN service automatically when the device boots,
 * IF the user had it running before the reboot.
 *
 * We only auto-start if:
 *   1. The user previously granted VPN permission (VpnService.prepare returns null)
 *   2. The "auto_start" preference is true (set when user first enables VPN)
 *
 * We cannot call VpnService.prepare() from a BroadcastReceiver (no Activity),
 * so we rely on the permission persisting from the previous grant.
 * If permission was revoked, the service will fail silently and the
 * user will need to open the app to re-grant.
 */
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
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i(TAG, "Boot received")

        if (!isAutoStart(context)) {
            Log.i(TAG, "Auto-start disabled — skipping")
            return
        }

        // Check VPN permission is still granted (prepare returns null = already granted)
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
