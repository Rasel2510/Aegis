package com.aegis.app

/**
 * DAY 12 — YoutubeFilter
 *
 * Rules for blocking YouTube ads at the HTTPS layer.
 * Called by LocalHttpsProxy when the SNI is youtube.com or
 * related Google domains, AFTER TLS is established.
 *
 * Two levels:
 *   1. Domain-level  — block the entire connection if the SNI
 *      is a known YouTube ad-serving domain.
 *   2. Path-level    — allow the domain but block specific URL
 *      paths used for ad loading / tracking.
 *
 * YouTube's ad infrastructure (as of 2024–2025):
 *   imasdk.googleapis.com  — IMA SDK, loads all pre-roll ads
 *   pagead2.googlesyndication.com — display ads
 *   pubads.g.doubleclick.net — programmatic video ads
 *   googleads.g.doubleclick.net — ad serving
 *   youtubei/v1/player responses with adBreaks — server-side ad injection
 *   /api/stats/ads — ad impression tracking
 *   /pagead/  — display ad paths
 *   /ptracking — ad click tracking
 */
object YoutubeFilter {

    // ── Domain-level blocks ───────────────────────────────────────────────────
    // These domains serve ONLY ads — block the entire TLS connection.

    private val BLOCKED_DOMAINS = setOf(
        "imasdk.googleapis.com",
        "pagead2.googlesyndication.com",
        "pagead.googlesyndication.com",
        "tpc.googlesyndication.com",
        "pubads.g.doubleclick.net",
        "googleads.g.doubleclick.net",
        "static.doubleclick.net",
        "ad.doubleclick.net",
        "cm.g.doubleclick.net",
        "stats.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "partner.googleadservices.com",
        "www.googleadservices.com",
        "googleadservices.com",
        "ads.youtube.com",
    )

    // ── Path-level blocks ─────────────────────────────────────────────────────
    // These domains serve legitimate content AND ads.
    // We block only the ad-specific paths.

    private val BLOCKED_PATHS = listOf(
        // Ad impression / tracking
        "/api/stats/ads",
        "/api/stats/qoe",
        "/pagead/",
        "/ptracking",
        "/videoplayback?.*&adt=",   // ad-tagged video segment
        "/get_video_info?.*adformat",

        // Youtubei player API — contains adBreaks in response
        // We match the path; body inspection is done in shouldBlockBody()
        "/youtubei/v1/player",

        // Ad measurement
        "/youtubei/v1/stats/watchtime",
        "/youtubei/v1/log_event",

        // Thumbnail tracking pixels
        "/vi_webp/",
        "/generate_204",
    )

    // Domains where path-level filtering applies
    private val PATH_FILTER_DOMAINS = setOf(
        "www.youtube.com",
        "m.youtube.com",
        "youtube.com",
        "youtubei.googleapis.com",
        "www.googleapis.com",
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns true if the entire TLS connection for this SNI should be blocked. */
    fun shouldBlockDomain(sni: String): Boolean = BLOCKED_DOMAINS.contains(sni)

    /**
     * Returns true if a specific request path within an allowed domain should
     * be blocked. Called after TLS handshake when we have the full HTTP request.
     */
    fun shouldBlockPath(host: String, path: String): Boolean {
        if (!PATH_FILTER_DOMAINS.contains(host)) return false
        val lPath = path.lowercase()
        return BLOCKED_PATHS.any { pattern ->
            if (pattern.contains(".*")) {
                lPath.matches(Regex(pattern))
            } else {
                lPath.startsWith(pattern)
            }
        }
    }

    /**
     * Inspect an HTTP response body from youtubei/v1/player.
     * If it contains "adBreaks" the server injected ads — strip them.
     * Returns the sanitised body, or null if no modification needed.
     *
     * This is a lightweight string-level strip — not full JSON parsing —
     * because the response can be several MB.
     */
    fun sanitisePlayerResponse(body: String): String? {
        if (!body.contains("\"adBreaks\"") &&
            !body.contains("\"adPlacements\"") &&
            !body.contains("\"playerAds\"")) return null

        // Remove adBreaks array and its contents
        var result = body
            .replace(Regex("\"adBreaks\":\\[.*?\\](,)?", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\"adPlacements\":\\[.*?\\](,)?", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\"playerAds\":\\[.*?\\](,)?", RegexOption.DOT_MATCHES_ALL), "")
            // Clean up trailing commas that would break JSON
            .replace(Regex(",\\s*}"), "}")
            .replace(Regex(",\\s*]"), "]")

        return if (result != body) result else null
    }

    /** True if this SNI is relevant to YouTube filtering. */
    fun isYoutubeRelated(sni: String): Boolean =
        BLOCKED_DOMAINS.contains(sni) || PATH_FILTER_DOMAINS.contains(sni)
}
