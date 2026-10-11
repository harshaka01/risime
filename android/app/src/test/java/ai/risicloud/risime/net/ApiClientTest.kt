package ai.risicloud.risime.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ApiClientTest {
    private val server = MockWebServer()
    private lateinit var api: ApiClient

    @Before
    fun setUp() {
        server.start()
        api = ApiClient(OkHttpClient(), { server.url("/").toString() }, { "tok" })
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun verifyPostsJsonWithoutAuthAndParsesUser() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"token":"t1","user":{"id":"u1","phone":"+94770000001","display_name":"A","company":"Risi"}}""",
            ),
        )
        val r = api.verify("+94770000001", "123456", "Pixel")
        assertEquals("t1", (r as ApiResult.Ok).value.token)
        val req = server.takeRequest()
        assertEquals("/api/v1/auth/verify", req.path)
        assertNull(req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"device_name\":\"Pixel\""))
    }

    @Test
    fun devicePutSendsThisBuildsAppId() = runBlocking {
        // v1.37 (decision 079): app_id = BuildConfig.APPLICATION_ID, with and without MLS.
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        api.putDevice("dev-1", DevicePut(DevicePut.PLATFORM_ANDROID, "fcm-t", "0.3.0"))
        api.putDevice("dev-1", DevicePut(DevicePut.PLATFORM_ANDROID, null, "0.3.0", DeviceMls("sig", listOf("calls"))))
        repeat(2) {
            val req = server.takeRequest()
            assertEquals("PUT", req.method)
            assertEquals("/api/v1/me/devices/dev-1", req.path)
            val body = ProtocolJson.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals(ai.risicloud.risime.BuildConfig.APPLICATION_ID, body["app_id"]!!.jsonPrimitive.content)
        }
        assertTrue(ai.risicloud.risime.BuildConfig.APPLICATION_ID.startsWith("ai.risicloud.risime"))
    }

    @Test
    fun errorEnvelopeIsDecoded() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"invalid_code","message":"nope"}}"""),
        )
        val r = api.verify("+94770000001", "000000", "Pixel") as ApiResult.Error
        assertEquals(401, r.httpStatus)
        assertEquals("invalid_code", r.code)
    }

    @Test
    fun authenticatedCallsSendBearerAndLogoutHandles204() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"contacts":[]}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(api.contacts() is ApiResult.Ok)
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
        assertEquals(ApiResult.Ok(Unit), api.logout())
        assertEquals("/api/v1/auth/logout", server.takeRequest().path)
    }

    @Test
    fun groupReadsCarryTheDeviceHeaderWhenTheClientKnowsOne() = runBlocking {
        val withDev = ApiClient(OkHttpClient(), { server.url("/").toString() }, { "tok" }, deviceId = { "dev-1" })
        repeat(4) { server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":""}}""")) }
        withDev.groups()
        withDev.group("grp:x")
        withDev.mlsGroup("grp:x")
        withDev.mlsCommits("grp:x", 0)
        repeat(4) {
            val req = server.takeRequest()
            assertEquals(req.path, "dev-1", req.getHeader("X-Device-Id"))
        }
    }

    @Test
    fun everyAuthenticatedCallCarriesTheDeviceHeader() = runBlocking {
        val withDev = ApiClient(OkHttpClient(), { server.url("/").toString() }, { "tok" }, deviceId = { "dev-1" })
        repeat(4) { server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":""}}""")) }
        withDev.risiFacts()
        withDev.risiCommitments("open")
        withDev.contacts()
        withDev.me()
        repeat(4) {
            val req = server.takeRequest()
            assertEquals(req.path, "dev-1", req.getHeader("X-Device-Id"))
        }
    }

    @Test
    fun groupReadsSendNoDeviceHeaderWithoutADeviceId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":""}}"""))
        api.group("grp:x")
        assertNull(server.takeRequest().getHeader("X-Device-Id"))
    }
}
