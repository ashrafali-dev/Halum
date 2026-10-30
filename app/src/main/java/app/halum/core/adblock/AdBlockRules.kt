package app.halum.core.adblock

/**
 * Q3 layer 1: decide whether a network request is an ad/analytics call. Pure so
 * the allow/deny lists are unit-tested; the WebViewClient only calls [shouldBlock].
 *
 * The rules are path/host specific on purpose. A hostname blacklist alone breaks
 * sites that serve panels and ads from the same CDN, so panel CDNs are on an
 * explicit allowlist that always wins, and the current page's own host is never
 * blocked.
 */
object AdBlockRules {
    /** Substrings that identify an ad/analytics request path or host. */
    val BLOCK_MARKERS =
        listOf(
            "/pagead/",
            "/adsbygoogle/",
            "/doubleclick/",
            "/googlesyndication/",
            "/amazon-adsystem/",
            "/adsrvr/",
            "/criteo/",
            "/taboola/",
            "/outbrain/",
            "/scorecardresearch/",
            "/quantserve/",
            "/google-analytics/",
            "/googletagmanager/",
            "/facebook.com/tr",
            "/hotjar/",
            "/mixpanel/",
            "/segment.io/",
        )

    /** Hosts that must never be blocked — they carry manga panels. */
    val ALLOW_HOSTS =
        listOf(
            "image-comic.pstatic.net",
            "cdn-ak-img.shonenjumpplus.com",
            "cdn-ak.f.st-hatena.com",
            "i.tngcdn.com",
        )

    enum class Reason { AD_CDN, ALLOWED_PANEL_CDN, SAME_HOST, NOT_AD }

    data class Decision(
        val block: Boolean,
        val reason: Reason,
    )

    /**
     * @param pageHost the hostname of the page being shown; its own requests
     *   are never blocked (the reader loads its images from there).
     */
    fun decide(
        url: String,
        pageHost: String,
    ): Decision {
        val lower = url.lowercase()
        if (ALLOW_HOSTS.any { lower.contains(it) }) return Decision(false, Reason.ALLOWED_PANEL_CDN)
        if (pageHost.isNotBlank() && hostOf(lower) == pageHost.lowercase()) {
            return Decision(false, Reason.SAME_HOST)
        }
        if (BLOCK_MARKERS.any { lower.contains(it) }) return Decision(true, Reason.AD_CDN)
        return Decision(false, Reason.NOT_AD)
    }

    fun shouldBlock(
        url: String,
        pageHost: String,
    ): Boolean = decide(url, pageHost).block

    private fun hostOf(url: String): String {
        val scheme = url.indexOf("://")
        val start = if (scheme >= 0) scheme + 3 else 0
        val slash = url.indexOf('/', start)
        val hostPort = if (slash >= 0) url.substring(start, slash) else url.substring(start)
        return hostPort.substringBefore(':')
    }
}
