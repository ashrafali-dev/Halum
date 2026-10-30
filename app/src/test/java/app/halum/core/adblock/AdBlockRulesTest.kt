package app.halum.core.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockRulesTest {
    private val page = "viewer.example.com"

    @Test
    fun `known ad CDNs are blocked`() {
        assertTrue(AdBlockRules.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", page))
        assertTrue(AdBlockRules.shouldBlock("https://ssp.criteo.com/adsrvr/", page))
        assertTrue(AdBlockRules.shouldBlock("https://cdn.taboola.com/libtrc/foo.js", page))
        assertTrue(AdBlockRules.shouldBlock("https://www.google-analytics.com/collect", page))
    }

    @Test
    fun `panel CDNs are never blocked even if a marker appears`() {
        // A path that would otherwise match, on an allow-listed host.
        val url = "https://image-comic.pstatic.net/pagead/panel1.jpg"
        val d = AdBlockRules.decide(url, page)
        assertFalse(d.block)
        assertEquals(AdBlockRules.Reason.ALLOWED_PANEL_CDN, d.reason)
    }

    @Test
    fun `the page's own host is never blocked`() {
        val d = AdBlockRules.decide("https://viewer.example.com/adsbygoogle/panel", page)
        assertFalse(d.block)
        assertEquals(AdBlockRules.Reason.SAME_HOST, d.reason)
    }

    @Test
    fun `ordinary panel image requests pass`() {
        val d = AdBlockRules.decide("https://cdn.reader.example/panel/12.jpg", page)
        assertFalse(d.block)
        assertEquals(AdBlockRules.Reason.NOT_AD, d.reason)
    }

    @Test
    fun `whitelisted manga CDNs pass`() {
        listOf(
            "https://cdn-ak-img.shonenjumpplus.com/x.png",
            "https://cdn-ak.f.st-hatena.com/y.png",
            "https://i.tngcdn.com/z.png",
        ).forEach {
            assertFalse("$it must pass", AdBlockRules.shouldBlock(it, page))
        }
    }
}
