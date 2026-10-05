package lk.codegen.risime.net

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
                """{"token":"t1","user":{"id":"u1","phone":"+94770000001","display_name":"A","company":"CodeGen"}}""",
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
}
