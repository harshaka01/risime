package lk.codegen.risime.data.messaging

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.util.Locale

/** §33.20 gate 1 (unit part): every case of contract/v1/copy_format_cases.json. */
class CopyFormatTest {
    private val me = "u-me"

    /** ICU4J, as android.icu on the phone. */
    private object Icu4jDates : CopyFormat.Dates {
        override fun bestPattern(locale: Locale, skeleton: String): String =
            com.ibm.icu.text.DateTimePatternGenerator.getInstance(locale).getBestPattern(skeleton, com.ibm.icu.text.DateTimePatternGenerator.MATCH_HOUR_FIELD_LENGTH)

        override fun format(pattern: String, locale: Locale, ms: Long, zone: ZoneId): String {
            val f = com.ibm.icu.text.SimpleDateFormat(pattern, com.ibm.icu.util.ULocale.forLocale(CopyFormat.latn(locale)))
            f.timeZone = com.ibm.icu.util.TimeZone.getTimeZone(zone.id)
            return f.format(java.util.Date(ms))
        }
    }

    private val cases: List<JsonObject> by lazy {
        val f = File(System.getProperty("risime.contract"), "copy_format_cases.json")
        ProtocolJson.parseToJsonElement(f.readText()).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
    }

    /** A fixture message as the chat engine stores it. */
    private fun row(i: Int, m: JsonObject): MessageEntity {
        val own = m["own"]!!.jsonPrimitive.boolean
        val from = if (own) me else "u-" + m["from_name"]!!.jsonPrimitive.content
        val serverTs = m["server_ts"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
        val localTs = m["local_ts"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content?.let { java.time.Instant.parse(it).toEpochMilli() } ?: 0L
        val payload = m["payload"]!!.jsonObject
        val base = MessageEntity("c$i", serverTs?.let { "m$i" }, "grp:x", from, "grp:x", "", serverTs, localTs, if (serverTs == null) "PENDING" else "SENT", own)
        return when (val d = MlsPayload.decode(payload.toString().toByteArray())) {
            is MlsPayload.Decoded.Text -> base.copy(body = d.body, systemJson = d.risi?.toString(), forwardHops = d.extras.forwardHops)
            is MlsPayload.Decoded.Image -> base.copy(kind = MessageEntity.KIND_IMAGE, body = d.envelope.caption.orEmpty())
            is MlsPayload.Decoded.File -> base.copy(kind = MessageEntity.KIND_FILE, body = d.envelope.caption.orEmpty(), systemJson = FileMeta.of(d.envelope).encode())
            is MlsPayload.Decoded.RisiControl -> base.copy(kind = MessageEntity.KIND_RISI_CTL, systemJson = d.obj.toString())
            else -> error("unexpected payload $d")
        }
    }

    @Test fun everyFixtureCase() {
        assertEquals(11, cases.size)
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val msgs = c["messages"]!!.jsonArray.map { it.jsonObject }
            val rows = msgs.mapIndexed(::row)
            val names = msgs.associate { ("u-" + it["from_name"]!!.jsonPrimitive.content) to it["from_name"]!!.jsonPrimitive.content }
            val out = CopyFormat.format(
                rows, me, c["own_name"]!!.jsonPrimitive.content, { names[it] },
                Locale.forLanguageTag(c["locale"]!!.jsonPrimitive.content), c["hour24"]!!.jsonPrimitive.boolean,
                ZoneId.of(c["zone"]!!.jsonPrimitive.content), Icu4jDates,
            )!!
            c["expected"]?.let { assertEquals(name, it.jsonPrimitive.content, out) }
            c["expected_regex"]?.let { re ->
                val r = Regex(re.jsonPrimitive.content)
                val lines = out.split("\n")
                assertEquals(name, msgs.size, lines.size)
                lines.forEach { assertTrue("$name: '$it'", r.matches(it)) }
            }
        }
    }

    @Test fun notCopyableRowsAreSkippedAndAPhotoWithoutCaptionCopiesNothingAlone() {
        val base = MessageEntity("c1", "m1", "dm:a_b", "u-k", me, "", "2026-10-10T01:56:00.000Z", 0, "READ", false)
        assertNull(CopyFormat.format(listOf(base.copy(kind = MessageEntity.KIND_IMAGE)), me, "Me", { "Kamal" }, Locale.UK, true, ZoneId.of("UTC"), Icu4jDates))
        assertNull(CopyFormat.format(listOf(base.copy(body = "x", viewOnce = true)), me, "Me", { "Kamal" }, Locale.UK, true, ZoneId.of("UTC"), Icu4jDates))
        assertNull(CopyFormat.format(listOf(base.copy(kind = MessageEntity.KIND_DELETED)), me, "Me", { "Kamal" }, Locale.UK, true, ZoneId.of("UTC"), Icu4jDates))
        // Unknown sender: "Unknown", never a phone number.
        val two = listOf(base.copy(body = "a"), base.copy(clientMsgId = "c2", messageId = "m2", body = "b"))
        assertEquals("[10/10/26, 01:56] Unknown: a\n[10/10/26, 01:56] Unknown: b", CopyFormat.format(two, me, "Me", { "+94771234567" }, Locale.UK, true, ZoneId.of("UTC"), Icu4jDates))
    }
}
