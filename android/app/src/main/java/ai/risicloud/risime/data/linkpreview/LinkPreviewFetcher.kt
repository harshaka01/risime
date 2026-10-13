package ai.risicloud.risime.data.linkpreview

import ai.risicloud.risime.data.media.ImageThumb
import ai.risicloud.risime.net.LinkPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/*
 * v1.44 §39.3 the SENDER's link-preview fetch: https only on every hop, no private/LAN addresses, no cookies, no
 * cache, small timeouts and size caps. Any failure is silent (the message goes without a preview). Receivers never
 * call this. No agent: infrastructure (decision 085).
 */

/** §39.3 the address filter: loopback, link-local, private, CGNAT, ULA, unspecified, multicast, broadcast. */
object PrivateAddress {
    fun blocked(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        if (a is Inet4Address) {
            val o0 = b[0].toInt() and 0xFF
            val o1 = b[1].toInt() and 0xFF
            if (o0 == 0) return true // 0/8 "this network"
            if (o0 == 100 && o1 in 64..127) return true // 100.64/10 CGNAT
            if (o0 == 192 && o1 == 0 && (b[2].toInt() and 0xFF) == 0) return true // 192.0.0/24 IETF
            if (o0 == 198 && (o1 == 18 || o1 == 19)) return true // benchmarking
            if (o0 >= 240) return true // reserved + broadcast
            return false
        }
        if (a is Inet6Address) {
            val f = b[0].toInt() and 0xFF
            if ((f and 0xFE) == 0xFC) return true // fc00::/7 ULA
            if (f == 0xFE && (b[1].toInt() and 0xC0) == 0xC0) return true // fec0::/10 site-local
            // ::ffff:a.b.c.d (IPv4-mapped) and the NAT64 prefix carry an IPv4 address: judge that one.
            val mapped = (0..9).all { b[it].toInt() == 0 } && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
            val nat64 = (b[0].toInt() and 0xFF) == 0x00 && (b[1].toInt() and 0xFF) == 0x64 && (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B
            if (mapped || nat64) return blocked(InetAddress.getByAddress(b.copyOfRange(12, 16)))
            return false
        }
        return true
    }
}

/** Resolves through [delegate] and refuses the host if ANY address is private (no DNS-rebinding mix). */
class PublicOnlyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        if (all.isEmpty() || all.any(PrivateAddress::blocked)) throw UnknownHostException("link preview: refused address")
        return all
    }
}

/**
 * Debug builds only (§39.3): the device gate routes ONE test host (e.g. example.org) to a local Open Graph page on
 * `http://127.0.0.1:<port>` (adb reverse). [arm] ignores everything unless BuildConfig.DEBUG; release builds have no
 * receiver that calls it.
 */
object LinkPreviewTestRoute {
    @Volatile private var route: Pair<String, HttpUrl>? = null

    fun arm(host: String, origin: String): Boolean {
        if (!ai.risicloud.risime.BuildConfig.DEBUG) return false
        val o = origin.toHttpUrlOrNull()?.takeIf { it.host == "127.0.0.1" } ?: return false
        route = host.lowercase() to o
        return true
    }

    fun clear() { route = null }

    /** The local URL for [u] when [u]'s host is the armed test host (debug only), else null. */
    fun map(u: HttpUrl): HttpUrl? {
        if (!ai.risicloud.risime.BuildConfig.DEBUG) return null
        val (host, origin) = route ?: return null
        if (u.host.lowercase() != host) return null
        return u.newBuilder().scheme(origin.scheme).host(origin.host).port(origin.port).build()
    }
}

class LinkPreviewFetcher(
    /** §39.3: decode the og:image and re-encode it to a §14.4 thumbnail (no metadata). */
    private val makeThumb: (ByteArray) -> ImageThumb?,
    private val base: OkHttpClient = OkHttpClient(),
) {
    private val client: OkHttpClient = base.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false).followSslRedirects(false)
        .dns(PublicOnlyDns())
        .connectTimeout(CONNECT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TOTAL_MS, TimeUnit.MILLISECONDS)
        .callTimeout(TOTAL_MS, TimeUnit.MILLISECONDS)
        .build()

    /** Debug test route only: loopback is the point, so the public-only DNS is not used for it. */
    private val testClient: OkHttpClient by lazy { client.newBuilder().dns(Dns.SYSTEM).build() }

    /** The preview of [url] within [TOTAL_MS], or null (silently) on any failure or refusal. */
    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TOTAL_MS) { runCatching { fetchOrNull(url) }.getOrNull() }
    }

    private fun fetchOrNull(url: String): LinkPreview? {
        if (!LinkPreview.isHttps(url)) return null
        val start = url.toHttpUrlOrNull() ?: return null
        val (finalUrl, html) = get(start, MAX_HTML_BYTES, html = true) ?: return null
        val og = OpenGraph.parse(html.toString(Charsets.UTF_8))
        val title = LinkPreview.cut(og.title, LinkPreview.MAX_TITLE) ?: return null
        val thumb = og.image?.let { finalUrl.resolve(it) }?.takeIf { it.isHttps }?.let { img ->
            runCatching { get(img, MAX_IMAGE_BYTES, html = false)?.second?.let(makeThumb) }.getOrNull()
        }
        return LinkPreview(
            url = url,
            title = title,
            description = LinkPreview.cut(og.description, LinkPreview.MAX_DESCRIPTION),
            siteName = LinkPreview.cut(og.siteName, LinkPreview.MAX_SITE_NAME),
            thumb = thumb,
        )
    }

    /** GET with manual redirects (https only, ≤ [MAX_REDIRECTS]); the body capped at [max] bytes. */
    private fun get(first: HttpUrl, max: Int, html: Boolean): Pair<HttpUrl, ByteArray>? {
        var u = first
        repeat(MAX_REDIRECTS + 1) {
            if (!u.isHttps) return null
            val routed = LinkPreviewTestRoute.map(u)
            if (routed == null && u.host.let { h -> h.all { it.isDigit() || it == '.' } || h.contains(':') }) {
                // An IP-literal host follows the same address rule (§39.3).
                if (runCatching { PrivateAddress.blocked(InetAddress.getByName(u.host)) }.getOrDefault(true)) return null
            }
            val req = Request.Builder().url(routed ?: u).get()
                .header("User-Agent", USER_AGENT)
                .header("Accept", if (html) "text/html" else "image/*")
                .build()
            (if (routed != null) testClient else client).newCall(req).execute().use { r ->
                if (r.isRedirect) {
                    val loc = r.header("Location") ?: return null
                    u = u.resolve(loc) ?: return null
                    return@repeat
                }
                if (!r.isSuccessful) return null
                val body = r.body
                val type = body.contentType()
                if (html && (type == null || type.subtype != "html")) return null
                if (!html && type != null && type.type != "image") return null
                if (body.contentLength() > max) return null
                val src = body.source()
                val buf = okio.Buffer()
                while (buf.size < max) { if (src.read(buf, (max - buf.size).coerceAtMost(8192)) == -1L) break }
                // HTML: the first 512 KiB are enough (the head); an image over the cap is refused.
                if (!html && !src.exhausted()) return null
                return u to buf.readByteArray()
            }
        }
        return null
    }

    companion object {
        const val CONNECT_MS = 3_000L
        const val TOTAL_MS = 5_000L
        const val MAX_REDIRECTS = 3
        const val MAX_HTML_BYTES = 512 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val USER_AGENT = "RisiMe-LinkPreview/1"
    }
}

/** §39.3 the Open Graph fields (regex over the page's head; no HTML library). */
object OpenGraph {
    data class Og(val title: String?, val description: String?, val siteName: String?, val image: String?)

    private val META = Regex("""<meta\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""([a-zA-Z_:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
    private val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun parse(html: String): Og {
        val head = html.substringBefore("</head>", html).take(MAX_SCAN)
        val props = mutableMapOf<String, String>()
        for (m in META.findAll(head)) {
            val attrs = ATTR.findAll(m.groupValues[1]).associate { a ->
                a.groupValues[1].lowercase() to (a.groups[2]?.value ?: a.groups[3]?.value ?: a.groups[4]?.value ?: "")
            }
            val key = (attrs["property"] ?: attrs["name"])?.lowercase() ?: continue
            val content = attrs["content"] ?: continue
            props.putIfAbsent(key, decode(content))
        }
        val title = props["og:title"]?.takeIf { it.isNotBlank() } ?: TITLE.find(head)?.groupValues?.get(1)?.let(::decode)
        return Og(
            title = title?.clean(),
            description = (props["og:description"] ?: props["description"])?.clean(),
            siteName = props["og:site_name"]?.clean(),
            image = (props["og:image:secure_url"] ?: props["og:image"] ?: props["og:image:url"])?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun String.clean(): String? = replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }

    private val ENTITY = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp|#39);""")

    fun decode(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos", "#39" -> "'"; "nbsp" -> " "
            else -> {
                val cp = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                if (cp != null && cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else m.value
            }
        }
    }

    private const val MAX_SCAN = 256 * 1024
}
