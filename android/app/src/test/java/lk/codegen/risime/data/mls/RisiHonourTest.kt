package lk.codegen.risime.data.mls

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.GroupMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §24.11 a `risi` object is honoured only from an attested agent leaf in Official; anywhere else the message is plain text. */
class RisiHonourTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val kamal = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val risi = "9e1f0000-0000-4000-8000-000000000001"
    private val official = "grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b"
    private val privateGrp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"

    private val mls = FakeMlsEngine(me, "d-me").apply {
        groups[official] = GroupRef(official, 1, 1)
        groups[privateGrp] = GroupRef(privateGrp, 1, 1)
        metas[official] = GroupMeta(name = "", tab = "official", chatId = privateGrp, agents = listOf(risi))
        metas[privateGrp] = GroupMeta(name = "Site team")
        agents[official] = setOf(risi)
    }
    private val pipeline = MlsPipeline({ mls }, FakeMlsPendingDao(), { 1L }, {}, { 7_000 })

    private val reminder = javaClass.classLoader!!.getResource("contract/v1/examples/envelope_risi_reminder.json")!!.readText().trim()
    private var n = 0

    private fun event(conv: String, from: String, fromDev: String, text: String) = Event(
        "e${++n}", "message",
        buildJsonObject {
            put("message_id", "m$n"); put("client_msg_id", "cm$n"); put("conversation_id", conv)
            put("from", from); put("from_device", fromDev)
            put("ciphertext", FakeMlsEngine.ciphertext(1, 1, from, fromDev, text)); put("generation", 1); put("epoch", 1)
            put("server_ts", "2026-10-09T10:30:00.000Z")
        },
    )

    @Test fun theEnvelopeParsesWithItsRisiObject() {
        val d = MlsPayload.decode(reminder.toByteArray()) as MlsPayload.Decoded.Text
        assertNotNull(d.risi)
        assertTrue(d.body.startsWith("Reminder:"))
        // An old text envelope is unchanged.
        assertNull((MlsPayload.decode(MlsPayload.text("hi")) as MlsPayload.Decoded.Text).risi)
    }

    @Test fun honouredFromTheAgentLeafInOfficial() = runTest {
        val r = pipeline.apply(event(official, risi, "risi-1", reminder)) as MlsResult.Plaintext
        val row = MessageEntity("cm", "m", official, risi, me, r.body, null, 0, "DELIVERED", false, systemJson = r.risi)
        assertEquals(listOf(kamal), RisiMessages.meta(row)!!.notify)
    }

    @Test fun plainTextFromAHumanInOfficial() = runTest {
        val r = pipeline.apply(event(official, kamal, "d-kamal", reminder)) as MlsResult.Plaintext
        assertNull(r.risi)
    }

    @Test fun plainTextInPrivateEvenFromAnAgentId() = runTest {
        mls.agents[privateGrp] = setOf(risi) // never possible (the core refuses), and still ignored here
        val r = pipeline.apply(event(privateGrp, risi, "risi-1", reminder)) as MlsResult.Plaintext
        assertNull(r.risi)
        assertNull(RisiMessages.honoured(buildJsonObject { put("kind", "reminder") }, null, risi, setOf(risi)))
    }
}
