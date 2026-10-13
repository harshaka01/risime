package ai.risicloud.risime.data.linkpreview

import ai.risicloud.risime.data.media.ImageThumb
import ai.risicloud.risime.data.mls.MlsPayload
import ai.risicloud.risime.net.LinkPreview
import ai.risicloud.risime.net.LinkUrls
import ai.risicloud.risime.net.ProtocolJson
import ai.risicloud.risime.ui.chat.SwipeReply
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** v1.44 §39 link previews: the receiver's validation, links in text, the sender's fetch rules, swipe-to-reply maths. */
class LinkPreviewTest {
    private fun obj(s: String) = ProtocolJson.parseToJsonElement(s)

    @After fun tearDown() = LinkPreviewTestRoute.clear()

    // ---- §39.1 validation ----

    @Test fun validPreviewNeedsHttpsUrlInTheBodyAndATitle() {
        val body = "see https://example.org/a"
        assertNotNull(LinkPreview.validate(obj("""{"url":"https://example.org/a","title":"A","thumb":null}"""), body))
        // not in the body
        assertNull(LinkPreview.validate(obj("""{"url":"https://example.org/b","title":"A"}"""), body))
        // http
        assertNull(LinkPreview.validate(obj("""{"url":"http://example.org/a","title":"A"}"""), "see http://example.org/a"))
        // userinfo
        assertNull(LinkPreview.validate(obj("""{"url":"https://u@example.org/a","title":"A"}"""), "see https://u@example.org/a"))
        // blank / missing / long title
        assertNull(LinkPreview.validate(obj("""{"url":"https://example.org/a","title":"  "}"""), body))
        assertNull(LinkPreview.validate(obj("""{"url":"https://example.org/a"}"""), body))
        assertNull(LinkPreview.validate(obj("""{"url":"https://example.org/a","title":"${"x".repeat(201)}"}"""), body))
        // long description, wrong types
        assertNull(LinkPreview.validate(obj("""{"url":"https://example.org/a","title":"A","description":"${"d".repeat(301)}"}"""), body))
        assertNull(LinkPreview.validate(obj("""{"url":"https://example.org/a","title":"A","site_name":5}"""), body))
        assertNull(LinkPreview.validate(obj("""["https://example.org/a"]"""), body))
        assertNull(LinkPreview.validate(null, body))
    }

    @Test fun thumbFollowsTheImageThumbRules() {
        val body = "https://example.org/"
        fun t(mime: String, w: Int, h: Int, bytes: Int) =
            """{"url":"https://example.org/","title":"A","thumb":{"mime":"$mime","w":$w,"h":$h,"data":"${java.util.Base64.getEncoder().encodeToString(ByteArray(bytes) { 1 })}"}}"""
        assertNotNull(LinkPreview.validate(obj(t("image/jpeg", 128, 67, 4096)), body)!!.thumb)
        assertNull(LinkPreview.validate(obj(t("image/png", 128, 67, 100)), body))
        assertNull(LinkPreview.validate(obj(t("image/jpeg", 129, 67, 100)), body))
        assertNull(LinkPreview.validate(obj(t("image/jpeg", 128, 67, 4097)), body))
    }

    @Test fun aBadPreviewNeverDropsTheMessage() {
        val d = MlsPayload.decode("""{"v":1,"type":"text","body":"hi https://example.org/","link_preview":{"url":"http://x/","title":"T"}}""".toByteArray())
        d as MlsPayload.Decoded.Text
        assertEquals("hi https://example.org/", d.body)
        assertNull(d.linkPreview)
    }

    @Test fun roomColumnRoundTripsAndIsRecheckedAgainstTheBody() {
        val lp = LinkPreview("https://example.org/", "Example", "Desc", "Site", ImageThumb("image/jpeg", 10, 10, byteArrayOf(1, 2, 3)))
        assertEquals(lp, LinkPreview.decode(lp.encode(), "go https://example.org/"))
        assertNull(LinkPreview.decode(lp.encode(), "another body"))
        assertNull(LinkPreview.decode("not json", "https://example.org/"))
    }

    @Test fun envelopeBoundDropsTheThumbThenThePreview() {
        val thumb = ImageThumb("image/jpeg", 128, 128, ByteArray(4096) { 7 })
        val small = LinkPreview("https://example.org/", "T", thumb = thumb)
        val withThumb = ProtocolJson.parseToJsonElement(MlsPayload.text("x https://example.org/", linkPreview = small).decodeToString()).jsonObject
        assertTrue((withThumb["link_preview"] as JsonObject)["thumb"] is JsonObject)
        // A body of ~17 KB + a 5.5 KB base64 thumb is over 22 KiB: the thumb goes, the preview stays.
        val long = "x".repeat(17_000) + " https://example.org/"
        val noThumb = ProtocolJson.parseToJsonElement(MlsPayload.text(long, linkPreview = small).decodeToString()).jsonObject
        assertEquals("null", (noThumb["link_preview"] as JsonObject)["thumb"].toString())
        // Still over without the thumb: no preview at all.
        val huge = "x".repeat(22_500) + " https://example.org/"
        val none = ProtocolJson.parseToJsonElement(MlsPayload.text(huge, linkPreview = small).decodeToString()).jsonObject
        assertFalse(none.containsKey("link_preview"))
    }

    @Test fun cutAtAGraphemeBoundaryWithAnEllipsis() {
        assertEquals("abc", LinkPreview.cut("  abc \n ", 10))
        val c = LinkPreview.cut("a".repeat(250), 200)!!
        assertTrue(c.length <= 200 && c.endsWith("…"))
        // a surrogate pair is never split
        val flags = "😀".repeat(120)
        val cf = LinkPreview.cut(flags, 200)!!
        assertTrue(cf.removeSuffix("…").length % 2 == 0 && !cf.removeSuffix("…").last().isHighSurrogate())
        assertNull(LinkPreview.cut("   ", 10))
    }

    // ---- §39.4 links in text ----

    @Test fun linksAreFoundWithoutTrailingPunctuation() {
        val s = LinkUrls.find("Look: https://example.org/a?b=1, and www.risicloud.ai. (see https://en.wikipedia.org/wiki/Foo_(bar))")
        assertEquals(listOf("https://example.org/a?b=1", "www.risicloud.ai", "https://en.wikipedia.org/wiki/Foo_(bar)"), s.map { it.text })
        assertEquals("https://www.risicloud.ai", s[1].target)
        assertEquals("https://example.org/a?b=1", LinkUrls.firstHttps("http://plain.example/ then https://example.org/a?b=1, ok"))
        assertNull(LinkUrls.firstHttps("only http://example.org/ here"))
        assertTrue(LinkUrls.find("no links, just https:// and www.").isEmpty())
    }

    // ---- §39.3 the address filter ----

    @Test fun privateAndSpecialAddressesAreRefused() {
        val blocked = listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "224.0.0.1", "255.255.255.255", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:10.0.0.1", "::",
        )
        for (a in blocked) assertTrue(a, PrivateAddress.blocked(InetAddress.getByName(a)))
        for (a in listOf("93.184.215.14", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:4700::1111")) assertFalse(a, PrivateAddress.blocked(InetAddress.getByName(a)))
    }

    @Test fun publicOnlyDnsRefusesAMixedAnswer() {
        val dns = PublicOnlyDns { listOf(InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.0.0.5")) }
        assertTrue(runCatching { dns.lookup("rebind.example") }.isFailure)
        assertEquals(1, PublicOnlyDns { listOf(InetAddress.getByName("93.184.215.14")) }.lookup("ok.example").size)
    }

    // ---- §39.3 Open Graph ----

    @Test fun openGraphWithFallbacksAndEntities() {
        val og = OpenGraph.parse(
            """<html><head><title>Fallback &amp; title</title>
            <meta content="A &quot;quoted&quot; page" property="og:title">
            <meta name='description' content='Plain   description'>
            <meta property="og:site_name" content="Example&#39;s site">
            <meta property="og:image" content="/img/og.jpg"></head><body><meta property="og:title" content="late"></body></html>""",
        )
        assertEquals("A \"quoted\" page", og.title)
        assertEquals("Plain description", og.description)
        assertEquals("Example's site", og.siteName)
        assertEquals("/img/og.jpg", og.image)
        assertEquals("Fallback & title", OpenGraph.parse("<head><title>\n Fallback &amp; title </title></head>").title)
    }

    // ---- §39.3 the fetch (MockWebServer through the debug test route) ----

    private fun server(vararg r: MockResponse): MockWebServer = MockWebServer().apply {
        r.forEach(::enqueue)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

    private fun html(s: String) = MockResponse().setResponseCode(200).addHeader("Content-Type", "text/html; charset=utf-8").setBody(s)

    @Test fun fetchesThePreviewThroughTheDebugRouteWithoutCookies() = runBlocking {
        val png = run {
            val img = java.awt.image.BufferedImage(300, 150, java.awt.image.BufferedImage.TYPE_INT_RGB)
            java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        }
        val s = server(
            html("""<head><meta property="og:title" content="Test page"><meta property="og:description" content="Desc"><meta property="og:image" content="https://example.org/og.png"></head>"""),
            MockResponse().setResponseCode(200).addHeader("Content-Type", "image/png").setBody(okio.Buffer().write(png)),
        )
        try {
            assertTrue(LinkPreviewTestRoute.arm("example.org", "http://127.0.0.1:${s.port}"))
            var thumbIn = 0
            val f = LinkPreviewFetcher(makeThumb = { b -> thumbIn = b.size; ImageThumb("image/jpeg", 128, 64, byteArrayOf(1)) })
            val lp = f.fetch("https://example.org/page")!!
            assertEquals("https://example.org/page", lp.url)
            assertEquals("Test page", lp.title)
            assertEquals("Desc", lp.description)
            assertEquals(png.size, thumbIn)
            val page = s.takeRequest()
            assertEquals("/page", page.path)
            assertNull(page.getHeader("Cookie"))
            assertNull(page.getHeader("Referer"))
            assertEquals(LinkPreviewFetcher.USER_AGENT, page.getHeader("User-Agent"))
        } finally {
            s.shutdown()
        }
    }

    @Test fun refusesNonHtmlPrivateHostsHttpRedirectsAndOtherHosts() = runBlocking {
        val s = server(
            MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody("{}"),
            MockResponse().setResponseCode(302).addHeader("Location", "http://example.org/plain"),
        )
        try {
            LinkPreviewTestRoute.arm("example.org", "http://127.0.0.1:${s.port}")
            val f = LinkPreviewFetcher(makeThumb = { null })
            assertNull("not text/html", f.fetch("https://example.org/json"))
            assertNull("a redirect to http", f.fetch("https://example.org/r"))
            // Not the test host: an IP-literal private host and loopback by name are refused before any request.
            assertNull(f.fetch("https://127.0.0.1:${s.port}/"))
            assertNull(f.fetch("https://10.0.0.1/"))
            assertNull(f.fetch("http://example.org/"))
            assertEquals(2, s.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test fun theSwitchOffMeansNoFetch() = runBlocking {
        val s = server(html("""<head><meta property="og:title" content="T"></head>"""))
        try {
            LinkPreviewTestRoute.arm("example.org", "http://127.0.0.1:${s.port}")
            val lp = LinkPreviews(null, LinkPreviewFetcher(makeThumb = { null }))
            assertTrue(lp.on.value) // default on
            lp.set(false)
            assertNull(lp.fetchIfOn("https://example.org/"))
            assertEquals("no request with the switch off", 0, s.requestCount)
            lp.set(true)
            assertEquals("T", lp.fetchIfOn("https://example.org/")!!.title)
            assertEquals(1, s.requestCount)
        } finally {
            s.shutdown()
        }
    }

    @Test fun testRouteOnlyForLoopbackOrigins() {
        assertFalse(LinkPreviewTestRoute.arm("example.org", "http://10.0.0.2:8000"))
        assertFalse(LinkPreviewTestRoute.arm("example.org", "not a url"))
    }

    // ---- PART 11 (d) swipe-to-reply ----

    @Test fun swipeFollowsRightOnlyCappedAndRepliesPastTheThreshold() {
        assertEquals(0f, SwipeReply.follow(0f, -40f, 64f, 96f))
        assertEquals(30f, SwipeReply.follow(0f, 30f, 64f, 96f))
        assertEquals(96f, SwipeReply.follow(90f, 200f, 64f, 96f))
        assertEquals(74f, SwipeReply.follow(70f, 10f, 64f, 96f)) // resistance past the threshold
        assertFalse(SwipeReply.reached(63f, 64f))
        assertTrue(SwipeReply.reached(64f, 64f))
    }
}
