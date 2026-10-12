package ai.risicloud.risime.data.backup

import android.app.Application
import kotlinx.coroutines.runBlocking
import ai.risicloud.risime.data.backup.BackupData.DM
import ai.risicloud.risime.data.backup.BackupData.ME
import ai.risicloud.risime.data.backup.BackupData.PEER
import ai.risicloud.risime.data.backup.BackupData.msg
import ai.risicloud.risime.data.db.MessageEntity
import ai.risicloud.risime.data.media.MediaFormat
import ai.risicloud.risime.data.media.VoiceEnvelope
import ai.risicloud.risime.data.media.VoiceMeta
import ai.risicloud.risime.net.ProtocolJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.41 §37.5: a voice note in a backup is by reference (the key, never the audio); `played_at` on incoming rows,
 * `status: "played"` on outgoing ones; restore re-seals the key, keeps the played state and owes no second ack.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupV141Test {
    @get:Rule val tmp = TemporaryFolder()
    private val phones = mutableListOf<BackupPhone>()

    private fun phone() = BackupPhone(dir = tmp.newFolder()).also { phones += it }

    @After fun close() = phones.forEach { it.close() }

    private fun voice(blobId: String): VoiceEnvelope {
        val o = ProtocolJson.parseToJsonElement(javaClass.classLoader!!.getResource("contract/v1/examples/voice_payload.json")!!.readText()).jsonObject
        val plain = o["enc"]!!.jsonObject["plain_size"]!!.jsonPrimitive.long
        val blob = JsonObject(o["blob"]!!.jsonObject + mapOf("size" to JsonPrimitive(MediaFormat.cipherSize(plain)!!), "blob_id" to JsonPrimitive(blobId)))
        return VoiceEnvelope.validate(JsonObject(o + ("blob" to blob)))!!
    }

    @Test fun voiceNotesSurviveABackupRoundTripByReference() = runBlocking {
        val a = phone()
        val m = a.db.messages()
        val vin = voice("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b81")
        val vout = voice("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b82")
        val vnew = voice("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b83")
        val played = msg(DM, PEER, 1, body = "").copy(kind = MessageEntity.KIND_VOICE, blobId = vin.blob.blobId, systemJson = VoiceMeta.of(vin).encode(), playedAt = 1_760_000_000_000L, ackedStatus = MessageEntity.ACKED_PLAYED)
        val mine = msg(DM, ME, 2, body = "", status = "READ").copy(kind = MessageEntity.KIND_VOICE, blobId = vout.blob.blobId, systemJson = VoiceMeta.of(vout).encode(), playedAt = 1_760_000_100_000L)
        val unplayed = msg(DM, PEER, 3, body = "").copy(kind = MessageEntity.KIND_VOICE, blobId = vnew.blob.blobId, systemJson = VoiceMeta.of(vnew).encode())
        listOf(played, mine, unplayed).forEach { m.insert(it) }
        a.images.storedVoice(played, vin)
        a.images.storedVoice(mine, vout)
        a.images.storedVoice(unplayed, vnew)

        val lines = bundleOf(a)
        val voiceLines = lines.filter { it.contains("\"type\":\"voice\"") }
        assertEquals(3, voiceLines.size)
        // By reference: the key and the waveform travel, never the audio.
        assertTrue(voiceLines.all { it.contains("\"enc\":{") && it.contains("\"waveform\":\"") && !it.contains("OggS") })
        val parsed = voiceLines.map { ProtocolJson.decodeFromString(BackupMessageLine.serializer(), it) }.associateBy { it.messageId }
        assertNotNull(parsed[played.messageId]!!.playedAt)
        assertNull(parsed[unplayed.messageId]!!.playedAt)
        assertEquals("played", parsed[mine.messageId]!!.status)
        assertEquals(1, lines.count { it.contains("\"played_at\"") })

        val b = phone()
        b.importer().import(lines.iterator())
        val p = b.db.messages().byMessageId(played.messageId!!)!!
        assertEquals(MessageEntity.KIND_VOICE, p.kind)
        assertEquals(1_760_000_000_000L, p.playedAt)
        assertEquals(MessageEntity.ACKED_PLAYED, p.ackedStatus)
        assertEquals(12_400L, VoiceMeta.decode(p.systemJson)!!.durationMs)
        assertNotNull("the note's key, re-sealed on this phone", b.db.media().get(p.clientMsgId))
        val u = b.db.messages().byMessageId(unplayed.messageId!!)!!
        assertNull(u.playedAt)
        assertEquals("READ", u.ackedStatus)
        val o = b.db.messages().byMessageId(mine.messageId!!)!!
        assertNotNull("blue mic kept", o.playedAt)
        assertEquals("READ", o.status)
        // Nothing owed to the server: the restored played note is not acked again.
        assertTrue(b.db.messages().unackedPlayed().isEmpty())
    }
}
