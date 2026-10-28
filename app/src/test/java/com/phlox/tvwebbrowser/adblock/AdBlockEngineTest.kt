package com.phlox.tvwebbrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockEngineTest {
    private fun engine(vararg lines: String, trusted: Boolean = true): AdBlockEngine {
        val b = AdBlockEngine.Builder()
        b.addList(lines.joinToString("\n"), trusted)
        return b.build()
    }

    private fun AdBlockEngine.blocks(url: String, page: String = "https://news.example/", type: Int = RequestType.SCRIPT): Boolean {
        val req = Request(url, DomainUtils.hostOf(url), DomainUtils.hostOf(page), type)
        return network.match(req) is MatchResult.Blocked
    }

    @Test
    fun domainUtils() {
        assertEquals("bbc.co.uk", DomainUtils.registrableDomain("www.bbc.co.uk"))
        assertEquals("example.com", DomainUtils.registrableDomain("a.b.example.com"))
        assertEquals("example.com", DomainUtils.hostOf("https://user@Example.com:8080/path?q"))
        assertTrue(DomainUtils.matches("www.google.co.uk", "google.*"))
        assertTrue(DomainUtils.matches("sub.example.com", "example.com"))
        assertFalse(DomainUtils.matches("notexample.com", "example.com"))
        assertTrue(DomainUtils.isThirdParty("ads.tracker.net", "example.com"))
        assertFalse(DomainUtils.isThirdParty("cdn.example.com", "www.example.com"))
    }

    @Test
    fun hostnameFilters() {
        val e = engine("||doubleclick.net^", "||ads.example.com^\$image")
        assertTrue(e.blocks("https://ad.doubleclick.net/x.js"))
        assertTrue(e.blocks("https://doubleclick.net/"))
        assertFalse(e.blocks("https://notdoubleclick.net/"))
        assertFalse(e.blocks("https://ads.example.com/a.js", type = RequestType.SCRIPT))
        assertTrue(e.blocks("https://ads.example.com/a.png", type = RequestType.IMAGE))
    }

    @Test
    fun patternFilters() {
        val e = engine("/banner/*/ad_", "||example.org/ads/*.js|", "|https://track.", "-advert-", "/\\/pagead[0-9]\\.js/")
        assertTrue(e.blocks("https://site.com/banner/123/ad_top.png"))
        assertTrue(e.blocks("https://example.org/ads/x/y.js"))
        assertFalse(e.blocks("https://example.org/ads/x/y.js?v=1"))
        assertTrue(e.blocks("https://track.somewhere.com/p"))
        assertFalse(e.blocks("https://cdn.com/?u=https://track.x"))
        assertTrue(e.blocks("https://cdn.com/img-advert-1.gif"))
        assertTrue(e.blocks("https://cdn.com/pagead2.js"))
    }

    @Test
    fun separatorAndOptions() {
        val e = engine("||cdn.com/ad^", "||tracker.io^\$third-party", "||widget.com^\$domain=a.com|~b.a.com")
        assertTrue(e.blocks("https://cdn.com/ad?x"))
        assertTrue(e.blocks("https://cdn.com/ad"))
        assertFalse(e.blocks("https://cdn.com/adx"))
        assertTrue(e.blocks("https://tracker.io/p", page = "https://site.com/"))
        assertFalse(e.blocks("https://tracker.io/p", page = "https://www.tracker.io/"))
        assertTrue(e.blocks("https://widget.com/w.js", page = "https://a.com/"))
        assertFalse(e.blocks("https://widget.com/w.js", page = "https://b.a.com/"))
        assertFalse(e.blocks("https://widget.com/w.js", page = "https://c.com/"))
    }

    @Test
    fun exceptionsImportantAndBadfilter() {
        val e = engine(
            "||ads.com^", "@@||ads.com/allowed^",
            "||evil.com^\$important", "@@||evil.com^",
            "||broken.com^", "||broken.com^\$badfilter"
        )
        assertTrue(e.blocks("https://ads.com/x"))
        assertFalse(e.blocks("https://ads.com/allowed/x"))
        assertTrue(e.blocks("https://evil.com/x"))
        assertFalse(e.blocks("https://broken.com/x"))
    }

    @Test
    fun popups() {
        val e = engine("||popads.example^\$popup,third-party", "||adserver.example^", "||cdn.example^\$script",
            "@@||popads.example/ok^\$popup", "/clickunder/*\$popup")
        val page = "https://player.site/"
        assertTrue(e.blocks("https://popads.example/go?id=1", page, RequestType.POPUP))
        assertFalse(e.blocks("https://popads.example/go", "https://www.popads.example/", RequestType.POPUP))
        assertFalse(e.blocks("https://popads.example/x.js", page, RequestType.SCRIPT)) //$popup only
        assertTrue(e.blocks("https://adserver.example/redirect", page, RequestType.POPUP)) //plain host filter
        assertFalse(e.blocks("https://cdn.example/watch", page, RequestType.POPUP)) //other types don't
        assertFalse(e.blocks("https://popads.example/ok/1", page, RequestType.POPUP))
        assertTrue(e.blocks("https://any.example/clickunder/5", page, RequestType.POPUP))
    }

    @Test
    fun pageFlags() {
        val e = engine("@@||trusted.com^\$document", "@@||nogeneric.com^\$ghide", "##.ad", "nogeneric.com##.specific")
        assertEquals(PageFlags.DOCUMENT, e.network.pageFlags("https://www.trusted.com/page"))
        assertEquals(PageFlags.GENERICHIDE, e.network.pageFlags("https://nogeneric.com/"))
        val data = e.pageData("https://nogeneric.com/", PageFlags.GENERICHIDE)
        assertTrue(data.contains(".specific{display:none!important}"))
        assertTrue(data.contains("\"generic\":false"))
        assertEquals("null", e.pageData("https://trusted.com/", PageFlags.DOCUMENT))
    }

    @Test
    fun preprocessor() {
        val e = engine(
            "!#if env_chromium", "||chromium-only.com^", "!#else", "||not-chromium.com^", "!#endif",
            "!#if !cap_html_filtering", "||nohtml.com^", "!#endif",
            "!#if env_firefox", "||firefox.com^", "!#endif"
        )
        assertTrue(e.blocks("https://chromium-only.com/"))
        assertFalse(e.blocks("https://not-chromium.com/"))
        assertTrue(e.blocks("https://nohtml.com/"))
        assertFalse(e.blocks("https://firefox.com/"))
    }

    @Test
    fun cosmetic() {
        val e = engine(
            "##.ad-banner", "##div[id^=\"google_ads\"]", "example.com##.sponsored",
            "example.com#@#.ad-banner", "~skip.com##.side-ad",
            "site.com##.post:has-text(Sponsored)", "site.com##.box:style(margin: 0 !important)",
            "site.com##.overlay:remove()", "google.*##.g-ad"
        )
        val data = e.pageData("https://www.example.com/", 0)
        assertTrue(data.contains(".sponsored{display:none!important}"))
        assertTrue(data.contains("div[id^=\\\"google_ads\\\"]"))
        assertTrue(data.contains(".side-ad"))
        //low generic selector is served on demand
        assertEquals("", e.genericCss("https://www.example.com/", listOf(".ad-banner")))
        assertEquals(".ad-banner{display:none!important}\n", e.genericCss("https://other.com/", listOf(".ad-banner")))
        assertFalse(e.pageData("https://skip.com/", 0).contains(".side-ad"))

        val site = e.pageData("https://site.com/", 0)
        assertTrue(site, site.contains("[\"has-text\",\"Sponsored\"]"))
        assertTrue(site, site.contains(".box{margin: 0 !important}"))
        assertTrue(site, site.contains("\"a\":[\"remove\",\"\"]"))
        assertTrue(e.pageData("https://www.google.de/", 0).contains(".g-ad"))
    }

    @Test
    fun proceduralParser() {
        val p = CosmeticParser.parse("div:has(> a:has-text(/ad/i)):upward(2) > span") as CosmeticParser.Parsed.Procedural
        assertEquals(
            "{\"s\":\"div\",\"t\":[[\"has\",{\"s\":\"> a\",\"t\":[[\"has-text\",\"/ad/i\"]]}],[\"upward\",\"2\"],[\"spath\",\" > span\"]]}",
            p.json
        )
        //native :has / :not stay plain css
        assertTrue(CosmeticParser.parse("div:has(> .ad):not(.x)") is CosmeticParser.Parsed.Hide)
    }

    @Test
    fun scriptlets() {
        assertEquals(listOf("set-constant", "ytInitialPlayerResponse.adPlacements", "undefined"),
            ScriptletParser.parse("+js(set, ytInitialPlayerResponse.adPlacements, undefined)"))
        assertEquals(listOf("trusted-replace-xhr-response", "\"adPlacements\"", "\"no_ads\"", "/playlist\\?list=|\\/player(?:\\?.+)?$/"),
            ScriptletParser.parse("+js(trusted-replace-xhr-response, '\"adPlacements\"', '\"no_ads\"', /playlist\\?list=|\\/player(?:\\?.+)?$/)"))
        assertEquals(listOf("trusted-replace-xhr-response", "/a\\,b/", "", "/c/"),
            ScriptletParser.parse("+js(trusted-replace-xhr-response, /a\\,b/, , /c/)").let { it!!.toMutableList().also { l -> l[1] = l[1].replace(",", "\\,") } })

        val e = engine(
            "youtube.com##+js(set, a.b, true)", "youtube.com##+js(trusted-set, c, {\"value\":1})",
            "music.youtube.com#@#+js(set, a.b, true)"
        )
        val yt = e.pageData("https://www.youtube.com/", 0)
        assertTrue(yt, yt.contains("[\"set-constant\",\"a.b\",\"true\"]"))
        assertTrue(yt, yt.contains("trusted-set-constant"))
        assertFalse(e.pageData("https://music.youtube.com/", 0).contains("\"a.b\""))

        val untrusted = engine("youtube.com##+js(trusted-set, c, 1)", trusted = false)
        assertFalse(untrusted.pageData("https://www.youtube.com/", 0).contains("trusted"))
    }
}
