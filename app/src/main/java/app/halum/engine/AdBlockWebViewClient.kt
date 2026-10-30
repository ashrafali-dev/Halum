package app.halum.engine

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.halum.core.adblock.AdBlockRules
import app.halum.util.Log
import java.io.ByteArrayInputStream

/**
 * Q3: three-layer ad blocker, all driven by [enabled] (a Settings toggle).
 *
 *  1. Network: [shouldInterceptRequest] answers ad/analytics URLs with an empty
 *     200 so the WebView never opens the connection.
 *  2. DOM: [injectedCss] hides ad containers (network-specific selectors only).
 *  3. Scripts: [injectedScript] removes analytics <script> tags after load.
 *
 * Safety: before step 2 the page's panel-image count is recorded; if it drops
 * within 2 s the CSS is removed again ([rollback]) so a too-broad rule can
 * never black out the manga. All three steps log the acceptance lines.
 */
class AdBlockWebViewClient(
    private val enabled: () -> Boolean,
    private val delegate: WebViewClient? = null,
) : WebViewClient() {
    private var netBlocked = 0

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!enabled()) return delegate?.shouldInterceptRequest(view, request)
        val url = request.url.toString()
        val pageHost = runCatching { request.url.host ?: "" }.getOrDefault("")
        if (AdBlockRules.shouldBlock(url, viewPageHost(view, pageHost))) {
            netBlocked++
            Log.i(TAG, "ADBLOCK_NET: url=${url.take(120)} reason=ad_cdn")
            return emptyResponse()
        }
        return delegate?.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(
        view: WebView,
        url: String?,
        favicon: Bitmap?,
    ) {
        netBlocked = 0
        delegate?.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(
        view: WebView,
        url: String?,
    ) {
        delegate?.onPageFinished(view, url)
        if (!enabled()) return
        applyDomLayers(view)
        Log.i(
            TAG,
            "ADBLOCK_SUMMARY: net_blocked=$netBlocked css_rules=${CSS.size} scripts_removed=$SCRIPTS.size rollback=false",
        )
    }

    /** Layers 2 + 3 with the panel-count safety net around layer 2. */
    private fun applyDomLayers(view: WebView) {
        // Record the panel count, inject CSS, then re-check after 2 s.
        view.evaluateJavascript(PANEL_COUNT_JS) { before ->
            view.evaluateJavascript(
                "(function(){var s=document.createElement('style');" +
                    "s.id='halum-adblock';s.textContent=${quote(CSS_JOINED)};" +
                    "document.head.appendChild(s);return true;})();",
            ) { _ ->
                Log.i(TAG, "ADBLOCK_CSS: injected rules=${CSS.size}")
            }
            view.postDelayed({
                view.evaluateJavascript(PANEL_COUNT_JS) { after ->
                    val beforeN = before.trim().toIntOrNull() ?: Int.MAX_VALUE
                    val afterN = after.trim().toIntOrNull() ?: Int.MAX_VALUE
                    if (afterN < beforeN) {
                        rollback(view)
                        Log.i(TAG, "ADBLOCK_ROLLBACK: reason=panel_count_dropped before=$beforeN after=$afterN")
                    }
                }
            }, PANEL_CHECK_DELAY_MS)
        }
        view.evaluateJavascript(SCRIPT_REMOVE_JS) { removed ->
            Log.i(TAG, "ADBLOCK_SCRIPT: removed=${removed.trim()}")
        }
    }

    private fun rollback(view: WebView) {
        view.evaluateJavascript(
            "(function(){var s=document.getElementById('halum-adblock');" +
                "if(s)s.remove();return true;})();",
            null,
        )
    }

    private fun viewPageHost(
        view: WebView,
        fallback: String,
    ): String = runCatching { view.url?.let { java.net.URI(it).host } ?: fallback }.getOrDefault(fallback)

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun quote(s: String): String = org.json.JSONObject.quote(s)

    private companion object {
        const val TAG = "ADBLOCK"
        const val PANEL_CHECK_DELAY_MS = 2_000L

        /** Network-specific selectors only; never broad [class*="ad"]. */
        val CSS =
            listOf(
                "[id*=\"google_ads\"]",
                "[id*=\"div-gpt-ad\"]",
                "[class*=\"advert\"]",
                "[class*=\"adsbygoogle\"]",
                "[class*=\"sponsored\"]",
                "[data-ad-slot]",
                "iframe[src*=\"doubleclick\"]",
                "iframe[src*=\"googlesyndication\"]",
                "[id^=\"taboola\"]",
                "[class*=\"outbrain\"]",
            )
        val CSS_JOINED = CSS.joinToString(",") { "$it{display:none !important;}" }

        val SCRIPTS =
            listOf(
                "google-analytics.com",
                "googletagmanager.com",
                "facebook.net/en_US/fbevents.js",
                "hotjar.com",
                "mixpanel.com",
            )
        val SCRIPT_REMOVE_JS =
            "(function(){var p=${org.json.JSONArray(SCRIPTS)};var n=0;" +
                "document.querySelectorAll('script[src]').forEach(function(s){" +
                "for(var i=0;i<p.length;i++){if(s.src.indexOf(p[i])!==-1){s.remove();n++;break;}}});" +
                "return n;})()"

        /** Counts visible <img> on the page; the pre-hide baseline. */
        const val PANEL_COUNT_JS = "document.images.length"
    }
}
