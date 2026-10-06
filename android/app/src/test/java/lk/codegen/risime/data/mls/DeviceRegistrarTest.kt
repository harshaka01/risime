package lk.codegen.risime.data.mls

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ProtocolJson
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §10.1 registration paths against a scripted server. */
class DeviceRegistrarTest {
    private val server = MockWebServer().apply { start() }
    private val api = ApiClient(OkHttpClient(), { server.url("/").toString() }, { "tok" })
    private val mls = FakeMlsEngine("u1", "d1")

    @After fun stop() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body).setHeader("Content-Type", "application/json")

    private fun registrar(engine: MlsEngine?) = DeviceRegistrar(api, { "dev-1" }, "0.3.0-nightly.1", { engine })

    @Test fun mlsDeviceWithoutFirebaseIsAttestedAndToppedUp() = runBlocking {
        mls.groupsOn = false
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":5}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(Registration.Mls(50), registrar(mls).register(pushToken = null))
        assertEquals("a.b.c", mls.lastAttestation)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/v1/me/devices/dev-1", put.path)
        val body = ProtocolJson.parseToJsonElement(put.body.readUtf8()).jsonObject
        assertEquals("null", body["push_token"].toString())
        assertEquals(44, body["mls"]!!.jsonObject["signature_key"]!!.jsonPrimitive.content.length) // b64 of 32 bytes
        assertEquals("/api/v1/me/devices/dev-1/key_packages/count", server.takeRequest().path)
        val up = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(45, up["key_packages"].toString().count { it == ',' } + 1)
        assertTrue(up["last_resort"] != null)
    }

    @Test fun groupsCapableCoreAdvertisesGroupsAndReplacesKeyPackagesOnce() = runBlocking {
        var replacedFor: String? = null
        val reg = DeviceRegistrar(api, { "dev-1" }, "0.3.0", { mls }, groupsReplacedFor = { replacedFor }, setGroupsReplacedFor = { replacedFor = it })
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(Registration.Mls(50), reg.register(null))
        val put = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("[\"groups\"]", put["mls"]!!.jsonObject["capabilities"].toString())
        val up = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("true", up["replace"].toString())
        assertTrue(up["last_resort"] != null)
        assertEquals(java.util.Base64.getEncoder().encodeToString(mls.signatureKey()), replacedFor)

        // Next registration: a normal top-up, no replace.
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        assertEquals(Registration.Mls(30), reg.register(null))
        server.takeRequest()
        assertEquals("/api/v1/me/devices/dev-1/key_packages/count", server.takeRequest().path)
    }

    @Test fun imagesAreAdvertisedOnlyWhenTheAppCanRenderThem() = runBlocking {
        val reg = DeviceRegistrar(api, { "dev-1" }, "0.3.0", { mls }, imagesSupported = { true }, groupsReplacedFor = { "x" })
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        reg.register(null)
        val put = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("[\"groups\",\"images\"]", put["mls"]!!.jsonObject["capabilities"].toString())
    }

    @Test fun coreWithoutGroupsKeepsTheV17Registration() = runBlocking {
        mls.groupsOn = false
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        registrar(mls).register(null)
        val put = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse("capabilities" in put["mls"]!!.jsonObject)
    }

    @Test fun mlsUnavailableFallsBackToPushOnly() = runBlocking {
        server.enqueue(json(503, """{"error":{"code":"mls_unavailable","message":"E2EE is off"}}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(Registration.MlsUnavailable, registrar(mls).register("fcm-1"))
        server.takeRequest()
        val push = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse("mls" in push) // exactly a v1.5 registration
        assertNull(mls.lastAttestation)
    }

    @Test fun invalidDeviceAndNoEnginePaths() = runBlocking {
        server.enqueue(json(422, """{"error":{"code":"invalid_device","message":"bad key"}}"""))
        assertEquals(Registration.Failed("invalid_device"), registrar(mls).register(null))
        assertEquals(Registration.Skipped, registrar(null).register(null)) // no MLS core, no Firebase
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(Registration.PushOnly, registrar(null).register("fcm-1"))
        // Enough key packages: no upload.
        server.enqueue(json(200, """{"count":30}"""))
        assertEquals(Registration.Mls(30), registrar(mls).topUp())
    }
}

class MlsDeviceHeaderTest {
    @Test fun commitAndClaimCarryXDeviceId() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = ApiClient(OkHttpClient(), { server.url("/").toString() }, { "tok" })
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"epoch":1}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"devices":[]}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"devices":[]}"""))
            api.mlsCommit("dm:a_b", lk.codegen.risime.net.MlsCommitRequest(1, 0, "Yw=="), "dev-1")
            api.claimKeyPackages(listOf("b"), "dev-1")
            api.claimKeyPackages(listOf("b"))
            assertEquals("dev-1", server.takeRequest().getHeader(ApiClient.DEVICE_HEADER))
            assertEquals("dev-1", server.takeRequest().getHeader(ApiClient.DEVICE_HEADER))
            assertNull(server.takeRequest().getHeader(ApiClient.DEVICE_HEADER)) // optional on claim
        } finally {
            server.shutdown()
        }
    }
}
