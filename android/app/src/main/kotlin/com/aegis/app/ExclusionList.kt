package com.aegis.app

import android.content.Context
import android.util.Log

/**
 * DAY 9 — ExclusionList
 *
 * Apps in this list bypass HTTPS filtering entirely.
 * Their traffic is NOT routed through the TLS proxy.
 * DNS blocking still applies (they can still be blocked at DNS level).
 *
 * Pre-populated with apps known to use certificate pinning
 * (they will break if subjected to TLS MITM):
 *   - Banking apps (generic — user adds specific ones)
 *   - Signal, WhatsApp (pinned certs)
 *   - System apps
 *
 * The exclusion list is applied in AdBlockVpnService via
 * VpnBuilder.addDisallowedApplication(), which routes those
 * apps' traffic outside the VPN tunnel entirely.
 */
object ExclusionList {

    private const val TAG   = "ExclusionList"
    private const val PREFS = "aegis_exclusions"
    private const val KEY   = "excluded_packages"

    // Default exclusions — apps with certificate pinning that break under HTTPS MITM.
    // User can add/remove via the UI.
    private val DEFAULTS = setOf(
        // Messaging (pinned certs)
        "org.whatsapp",
        "org.signal.android",
        "org.telegram.messenger",
        "com.viber.voip",

        // Banking (certificate pinning is common)
        // User should add their specific banking app package name
        // e.g. "com.chase.sig.android", "com.bofa.digitalbanking"

        // Google Play — pinned, breaks app updates if filtered
        "com.android.vending",
        "com.google.android.gms",

        // System
        "android",
        "com.android.systemui",
        "com.android.settings",
    )

    private val _packages = mutableSetOf<String>()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY, null)
        _packages.clear()
        _packages.addAll(DEFAULTS)
        if (saved != null) _packages.addAll(saved)
        loaded = true
        Log.i(TAG, "Exclusions loaded: ${_packages.size} packages")
    }

    fun isExcluded(packageName: String): Boolean = _packages.contains(packageName)

    fun getAll(): Set<String> = _packages.toSet()

    fun add(context: Context, packageName: String) {
        _packages.add(packageName)
        persist(context)
        Log.i(TAG, "Added exclusion: $packageName")
    }

    fun remove(context: Context, packageName: String) {
        if (DEFAULTS.contains(packageName)) {
            Log.w(TAG, "Cannot remove default exclusion: $packageName")
            return
        }
        _packages.remove(packageName)
        persist(context)
        Log.i(TAG, "Removed exclusion: $packageName")
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, _packages)
            .apply()
    }
}
