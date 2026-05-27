package com.aegis.app

/**
 * DAY 13 — CosmeticFilter
 *
 * Injects a JavaScript snippet into HTML responses to hide
 * ad-related DOM elements in the browser (cosmetic filtering).
 *
 * Works via LocalHttpsProxy: when a text/html response passes
 * through and the host matches, we inject a <script> tag into
 * the <head> that runs our CSS-based element hider.
 *
 * Covers:
 *   - YouTube web player ad overlay elements
 *   - Generic ad containers (class/id pattern matching)
 *   - Sponsored content labels
 *
 * This is a best-effort layer on top of DNS + HTTPS domain blocking.
 * It hides residual ad UI that slips through (e.g. house ads,
 * self-promoted content YouTube doesn't load from an ad domain).
 */
object CosmeticFilter {

    // Hosts where cosmetic injection applies
    private val INJECT_HOSTS = setOf(
        "www.youtube.com",
        "m.youtube.com",
        "youtube.com",
    )

    // CSS selectors to hide — these map to YouTube's ad DOM elements
    private val YOUTUBE_SELECTORS = listOf(
        // Pre-roll / mid-roll overlay
        ".ytp-ad-overlay-container",
        ".ytp-ad-player-overlay",
        ".ytp-ad-player-overlay-instream-info",
        ".ytp-ad-progress",
        ".ytp-ad-progress-list",
        ".ytp-ad-skip-button-container",
        ".ytp-ad-text",
        // Masthead / banner ads
        "#masthead-ad",
        "ytd-display-ad-renderer",
        "ytd-promoted-sparkles-web-renderer",
        "ytd-promoted-video-renderer",
        "ytd-ad-slot-renderer",
        "ytd-in-feed-ad-layout-renderer",
        // Search ads
        "ytd-search-pyv-renderer",
        "ytd-promoted-sparkles-text-search-renderer",
        // Sidebar ads
        "#player-ads",
        "#panels > ytd-engagement-panel-section-list-renderer",
        // Generic
        "[id^='ad-']",
        "[class*='ad-showing']",
        "[class*='ad-interrupting']",
    )

    // The injected script — runs immediately and sets up a MutationObserver
    // so dynamically inserted ad elements are hidden as soon as they appear.
    private val INJECT_SCRIPT: String by lazy {
        val selectors = YOUTUBE_SELECTORS.joinToString(",\n    ")
        """
<script>(function(){
'use strict';
var SELECTORS=[
    $selectors
];
function hide(){
    SELECTORS.forEach(function(s){
        try{
            document.querySelectorAll(s).forEach(function(el){
                el.style.cssText='display:none!important;visibility:hidden!important;';
            });
        }catch(e){}
    });
}
hide();
var obs=new MutationObserver(function(){hide();});
obs.observe(document.documentElement,{childList:true,subtree:true});
// Skip ad button auto-clicker
setInterval(function(){
    var skip=document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button');
    if(skip)skip.click();
    var overlay=document.querySelector('.ytp-ad-overlay-close-button');
    if(overlay)overlay.click();
},300);
})();</script>
        """.trimIndent()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns true if cosmetic injection should apply to this host. */
    fun shouldInject(host: String): Boolean = INJECT_HOSTS.contains(host)

    /**
     * Inject the cosmetic script into an HTML response body.
     * Inserts immediately after <head> or <html> tag.
     * Returns modified HTML, or null if no injection point found.
     */
    fun inject(html: String): String? {
        // Try to insert after opening <head>
        val headIdx = html.indexOf("<head>", ignoreCase = true)
        if (headIdx != -1) {
            val insertAt = headIdx + "<head>".length
            return html.substring(0, insertAt) + INJECT_SCRIPT + html.substring(insertAt)
        }
        // Fallback: insert after <html ...>
        val htmlTagEnd = html.indexOf('>', html.indexOf("<html", ignoreCase = true))
        if (htmlTagEnd != -1) {
            return html.substring(0, htmlTagEnd + 1) +
                   INJECT_SCRIPT +
                   html.substring(htmlTagEnd + 1)
        }
        return null
    }
}
