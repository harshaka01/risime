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

    /** §15.1: `deletes` once the core answers AAD + sender_is_admin (the example's order: groups, images, deletes). */
    @Test fun deletesAreAdvertisedWithADeleteCapableCore() = runBlocking {
        mls.deletesOn = true
        val reg = DeviceRegistrar(api, { "dev-1" }, "0.3.0", { mls }, imagesSupported = { true }, groupsReplacedFor = { "x" })
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        reg.register(null)
        val put = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("[\"groups\",\"images\",\"deletes\"]", put["mls"]!!.jsonObject["capabilities"].toString())
    }

    /**
     * nightly.16 finding: a fresh install registers at sign-in, before the user answers the
     * notification prompt, so `calls` is left out. Allowing notifications later must re-advertise
     * every capability (calls included), once, and nothing when nothing changed.
     */
    @Test fun callsAreReAdvertisedWhenNotificationsAreAllowedAfterRegistration() = runBlocking {
        mls.deletesOn = true
        var notificationsAllowed = false
        val reg = DeviceRegistrar(
            api, { "dev-1" }, "0.2.0-nightly.16", { mls }, imagesSupported = { true },
            callsSupported = { notificationsAllowed }, groupsReplacedFor = { "x" },
        )
        assertNull("nothing registered yet: nothing to refresh", reg.refreshCapabilities(null))
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        reg.register("push-1")
        val first = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("[\"groups\",\"images\",\"deletes\"]", first["mls"]!!.jsonObject["capabilities"].toString())
        server.takeRequest() // key package count
        assertNull("unchanged: no PUT", reg.refreshCapabilities("push-1"))

        notificationsAllowed = true
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        assertTrue(reg.refreshCapabilities("push-1") is Registration.Mls)
        val again = server.takeRequest()
        assertEquals("PUT", again.method)
        val put = ProtocolJson.parseToJsonElement(again.body.readUtf8()).jsonObject
        assertEquals("[\"groups\",\"images\",\"deletes\",\"calls\"]", put["mls"]!!.jsonObject["capabilities"].toString())
        assertEquals("push-1", put["push_token"]!!.jsonPrimitive.content)
        server.takeRequest()
        assertNull("advertised now: no further PUT", reg.refreshCapabilities("push-1"))
        assertEquals(4, server.requestCount)
    }

    /** v1.14 §12.1: `member_devices` only when the core reports it (`core_capabilities()`), never on the app's own say. */
    @Test fun memberDevicesIsAdvertisedOnlyWhenTheCoreReportsIt() = runBlocking {
        mls.deletesOn = true
        val reg = DeviceRegistrar(api, { "dev-1" }, "0.3.0", { mls }, imagesSupported = { true }, callsSupported = { true }, groupsReplacedFor = { "x" })
        assertEquals(listOf("groups", "images", "deletes", "calls"), reg.capabilities(mls))
        mls.coreCaps = setOf("something_else")
        assertFalse("member_devices" in reg.capabilities(mls)!!)
        mls.coreCaps = setOf("member_devices")
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        reg.register(null)
        val put = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("[\"groups\",\"images\",\"deletes\",\"calls\",\"member_devices\"]", put["mls"]!!.jsonObject["capabilities"].toString())
        server.takeRequest()
        // A core without groups never advertises it, whatever it reports.
        mls.groupsOn = false
        assertNull(reg.capabilities(mls))
        mls.groupsOn = true
        // The same capabilities again: nothing to re-advertise; a core that drops it: re-advertised.
        assertNull(reg.refreshCapabilities(null))
        mls.coreCaps = emptySet()
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        assertTrue(reg.refreshCapabilities(null) is Registration.Mls)
        val again = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("[\"groups\",\"images\",\"deletes\",\"calls\"]", again["mls"]!!.jsonObject["capabilities"].toString())
    }

/** v1.24 §24.7: `tabs` only while the server switch is on AND the bundled core enforces §24.1; re-advertised when the switch flips. */
    @Test fun tabsAreAdvertisedOnlyWithTheServerSwitchAndATabsCore() = runBlocking {
        var serverOn = false
        var seen: List<String>? = null
        val reg = DeviceRegistrar(api, { "dev-1" }, "0.3.0", { mls }, groupsReplacedFor = { "x" }, tabsSupported = { serverOn }, onAdvertised = { seen = it })
        fun caps() = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["mls"]!!.jsonObject["capabilities"].toString()

        mls.tabsOn = true // the core enforces §24.1, the server says off
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        reg.register(null)
        assertEquals("[\"groups\"]", caps())
        server.takeRequest()
        assertEquals(listOf("groups"), seen)

        serverOn = true // the switch flips on: re-advertised with `tabs`
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        assertTrue(reg.refreshCapabilities(null) != null)
        assertEquals("[\"groups\",\"tabs\"]", caps())
        server.takeRequest()
        assertEquals(listOf("groups", "tabs"), seen)

        mls.tabsOn = false // a core without the §24.1 rules: never `tabs`, whatever the server says
        server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
        server.enqueue(json(200, """{"count":30}"""))
        reg.register(null)
        assertEquals("[\"groups\"]", caps())
    }

    /** v1.25 §25.8: `risi_tools` only with `tabs`, the `risi_tools` server switch and a core that does Risi chats. */
    @Test fun risiToolsAdvertisedOnlyWithTabsTheSwitchAndARisiCore() = runBlocking {
        var tabsOn = true
        var risiOn = false
        val reg = DeviceRegistrar(api, { "dev-1" }, "0.3.0", { mls }, groupsReplacedFor = { "x" }, tabsSupported = { tabsOn }, risiToolsSupported = { risiOn })
        fun caps() = ProtocolJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["mls"]!!.jsonObject["capabilities"].toString()
        suspend fun advertise(): String {
            server.enqueue(json(200, """{"attestation":"a.b.c"}"""))
            server.enqueue(json(200, """{"count":30}"""))
            reg.register(null)
            return caps().also { server.takeRequest() }
        }
        mls.tabsOn = true
        mls.risiChatOn = true
        assertEquals("[\"groups\",\"tabs\"]", advertise()) // server switch off
        risiOn = true
        assertEquals("[\"groups\",\"tabs\",\"risi_tools\"]", advertise())
        tabsOn = false // never without tabs
        assertEquals("[\"groups\"]", advertise())
        tabsOn = true
        mls.risiChatOn = false // a core without the Risi-chat rules
        assertEquals("[\"groups\",\"tabs\"]", advertise())
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
