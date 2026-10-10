package lk.codegen.risime.data.gcal

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A scripted authorizer: [tokens] are handed out in order; null = the user must consent again. */
class FakeAuthorizer(var tokens: MutableList<String?> = mutableListOf("T1"), var unavailable: Boolean = false) : GcalAuthorizer {
    var authorizeCalls = 0
    var invalidations = 0
    var revoked = 0
    override suspend fun authorize(): AuthResult {
        authorizeCalls++
        if (unavailable) return AuthResult.Unavailable("no_play_services")
        val t = if (tokens.size > 1) tokens.removeAt(0) else tokens.firstOrNull()
        return if (t == null) AuthResult.NeedsResolution(null) else AuthResult.Token(t, Long.MAX_VALUE)
    }

    override fun invalidate() { invalidations++ }
    override suspend fun revoke(account: String?, token: String?): Boolean { revoked++; return true }
}

class GcalApiTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: FakeAuthorizer
    private lateinit var api: GcalApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        auth = FakeAuthorizer()
        api = GcalApi(OkHttpClient(), auth, baseUrl = { server.url("/calendar/v3/").toString() })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")

    @Test fun calendarListFollowsEveryPageAndAsksForTheContractFields() = runBlocking {
        server.enqueue(json("""{"items":[{"id":"a@x","summary":"Work","primary":true,"accessRole":"owner","selected":true}],"nextPageToken":"p2"}"""))
        server.enqueue(json("""{"items":[{"id":"b","summary":"Personal","summaryOverride":"Mine","accessRole":"writer"},{"id":"c","summary":"Gone","deleted":true}]}"""))
        val r = api.calendarList() as GcalResult.Ok
        assertEquals(listOf("Work", "Mine"), r.value.map { it.name })
        assertTrue(r.value[0].primary && r.value[1].canWrite)
        val first = server.takeRequest()
        assertEquals("Bearer T1", first.getHeader("Authorization"))
        assertEquals("freeBusyReader", first.requestUrl!!.queryParameter("minAccessRole"))
        assertEquals("items(id,summary,summaryOverride,primary,accessRole,selected,hidden,deleted),nextPageToken", first.requestUrl!!.queryParameter("fields"))
        assertEquals("p2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }

    @Test fun busyEventsPageAndUseTheSection314Fields() = runBlocking {
        server.enqueue(json("""{"items":[{"id":"e1","status":"confirmed","start":{"dateTime":"2026-10-12T08:30:00Z"},"end":{"dateTime":"2026-10-12T09:30:00Z"}}],"nextPageToken":"n"}"""))
        server.enqueue(json("""{"items":[{"id":"e2","transparency":"transparent","start":{"date":"2026-10-13"},"end":{"date":"2026-10-14"},"attendees":[{"self":true,"responseStatus":"declined"}],"extendedProperties":{"private":{"risi_event_id":"x"}}}]}"""))
        val ev = (api.busyEvents("a@x", 0, 1_000_000_000_000) as GcalResult.Ok).value
        assertEquals(2, ev.size)
        assertTrue(ev[1].allDay && ev[1].selfDeclined && ev[1].transparency == "transparent" && ev[1].private["risi_event_id"] == "x")
        val u = server.takeRequest().requestUrl!!
        assertEquals("/calendar/v3/calendars/a@x/events", u.encodedPath.replace("%40", "@"))
        assertEquals("true", u.queryParameter("singleEvents"))
        assertEquals("250", u.queryParameter("maxResults"))
        assertEquals(GcalApi.FIELDS_BUSY, u.queryParameter("fields"))
    }

    @Test fun a401IsRetriedOnceAfterASilentReauthorize() = runBlocking {
        auth.tokens = mutableListOf("T1", "T2")
        server.enqueue(json("{}", 401))
        server.enqueue(json("""{"items":[]}"""))
        assertTrue(api.busyEvents("c", 0, 1) is GcalResult.Ok)
        assertEquals("Bearer T1", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer T2", server.takeRequest().getHeader("Authorization"))
        assertEquals(1, auth.invalidations)
    }

    @Test fun aSecond401OrANeededConsentIsReauthNeeded() = runBlocking {
        auth.tokens = mutableListOf("T1", "T2")
        server.enqueue(json("{}", 401))
        server.enqueue(json("{}", 401))
        assertEquals(GcalErr.REAUTH, (api.busyEvents("c", 0, 1) as GcalResult.Fail).err)
        // the silent authorize now needs the user
        auth.tokens = mutableListOf("T1", null)
        server.enqueue(json("{}", 401))
        assertEquals(GcalErr.REAUTH, (api.busyEvents("c", 0, 1) as GcalResult.Fail).err)
        auth.tokens = mutableListOf(null)
        assertEquals(GcalErr.REAUTH, (api.calendarList() as GcalResult.Fail).err)
        auth.unavailable = true
        assertEquals(GcalErr.UNAVAILABLE, (api.calendarList() as GcalResult.Fail).err)
    }

    @Test fun everyWriteCarriesSendUpdatesNone() = runBlocking {
        val body = buildJsonObject { put("id", "risi00") }
        repeat(6) { server.enqueue(json("{}")) }
        api.insert("w", body); api.put("w", "risi00", body); api.patch("w", "risi00", body); api.delete("w", "risi00"); api.move("w", "risi00", "z")
        val reqs = (1..5).map { server.takeRequest() }
        assertEquals(listOf("POST", "PUT", "PATCH", "DELETE", "POST"), reqs.map { it.method })
        assertTrue(reqs.all { it.requestUrl!!.queryParameter("sendUpdates") == "none" })
        assertEquals("z", reqs[4].requestUrl!!.queryParameter("destination"))
    }

    @Test fun insertOfAnExistingIdIs409AndGoneIs404() = runBlocking {
        server.enqueue(json("{}", 409))
        server.enqueue(json("{}", 404))
        server.enqueue(json("{}", 410))
        server.enqueue(json("{}", 503))
        assertEquals(GcalErr.CONFLICT, (api.insert("w", buildJsonObject { }) as GcalResult.Fail).err)
        assertEquals(GcalErr.GONE, (api.delete("w", "x") as GcalResult.Fail).err)
        assertEquals(GcalErr.GONE, (api.patch("w", "x", buildJsonObject { }) as GcalResult.Fail).err)
        assertEquals(GcalErr.API, (api.get("w", "x") as GcalResult.Fail).err)
    }

    @Test fun aSlowCalendarTimesOutAndADeadServerIsNetwork() = runBlocking {
        val slow = GcalApi(OkHttpClient.Builder().build(), auth, baseUrl = { server.url("/calendar/v3/").toString() })
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t0 = System.currentTimeMillis()
        val r = slow.busyEvents("c", 0, 1) as GcalResult.Fail
        assertEquals(GcalErr.TIMEOUT, r.err)
        assertTrue(System.currentTimeMillis() - t0 in 7_000..11_000)
        server.shutdown()
        assertEquals(GcalErr.NETWORK, (slow.busyEvents("c", 0, 1) as GcalResult.Fail).err)
    }

    @Test fun theTokenNeverShowsInToString() {
        assertFalse(AuthResult.Token("secret", 1).toString().contains("secret"))
    }
}
