package lk.codegen.risime.data.history

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lk.codegen.risime.data.media.MediaFormat
import lk.codegen.risime.net.BlobRef
import lk.codegen.risime.net.HistoryRange
import lk.codegen.risime.net.ProtocolJson
import java.util.Base64
import java.util.UUID

/**
 * §17.3 the `'H'` MLS authenticated data of `history_request`/`history_share`: `0x01 0x48` ‖
 * `request_id` (16 raw bytes), exactly 18 bytes. The core has the same canonical parser
 * (`historyAadEncode`/`Decode`); the vectors' `aad_h` cases run against both.
 */
object HistoryAad {
    const val VERSION: Byte = 0x01
    const val TAG: Byte = 0x48
    const val SIZE = 18

    fun encode(requestId: String): ByteArray {
        val u = UUID.fromString(requestId)
        return java.nio.ByteBuffer.allocate(SIZE).put(VERSION).put(TAG).putLong(u.mostSignificantBits).putLong(u.leastSignificantBits).array()
    }

    /** The lowercase request id of an exact `'H'` AAD; null for anything else (drop the envelope). */
    fun decode(aad: ByteArray): String? {
        if (aad.size != SIZE || aad[0] != VERSION || aad[1] != TAG) return null
        val bb = java.nio.ByteBuffer.wrap(aad, 2, 16)
        return UUID(bb.long, bb.long).toString()
    }
}

/** §17.6 constants (equal to the core's `historyLimits()`; checked in the crypto tests). */
object HistoryLimits {
    const val LABEL = "risime-history-v1"
    const val ALG = MediaFormat.ALG
    const val KEM = "x25519"
    const val RPK_LEN = 32
    const val HPKE_ENC_LEN = 32
    const val SEALED_KEY_LEN = 48
    const val MAX_PARTS = 20
    const val MAX_PLAIN = 16_515_072L
    const val MAX_CIPHER = 16L * 1024 * 1024
    const val MAX_ENTRIES = 5_000
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

private fun b64(s: String?): ByteArray? = s?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

private val b64e = Base64.getEncoder()

/** A canonical lowercase UUID string, or null. */
internal fun canonicalUuid(s: String?): String? =
    s?.let { runCatching { UUID.fromString(it) }.getOrNull()?.toString()?.takeIf { u -> u.equals(s, true) } }?.lowercase()

private fun range(o: JsonObject?): HistoryRange? {
    o ?: return null
    val from = o.str("from") ?: return null
    val to = o.str("to") ?: return null
    if (runCatching { java.time.Instant.parse(from); java.time.Instant.parse(to) }.isFailure) return null
    return HistoryRange(from, to)
}

private fun JsonObject.versionOk() = (this["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull == "1"

/** §17.6 `history_request` (MLS application payload; the `'H'` AAD carries the same request id). */
class HistoryRequestEnvelope(val requestId: String, val range: HistoryRange, val gapCount: Int, val rpk: ByteArray) {
    fun encode(): ByteArray = ProtocolJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", 1)
            put("type", TYPE)
            put("request_id", requestId)
            put("range", buildJsonObject { put("from", range.from); put("to", range.to) })
            put("gap_count", gapCount)
            put("hpke", buildJsonObject { put("kem", HistoryLimits.KEM); put("pk", b64e.encodeToString(rpk)) })
        },
    ).toByteArray(Charsets.UTF_8)

    companion object {
        const val TYPE = "history_request"

        /** Strict validation (§17.6); null = drop and log by count. */
        fun validate(o: JsonObject): HistoryRequestEnvelope? {
            if (!o.versionOk() || o.str("type") != TYPE) return null
            val id = canonicalUuid(o.str("request_id")) ?: return null
            val r = range(o.obj("range")) ?: return null
            val n = o.num("gap_count")?.takeIf { it in 0..1_000_000 } ?: return null
            val hpke = o.obj("hpke") ?: return null
            if (hpke.str("kem") != HistoryLimits.KEM) return null
            val pk = b64(hpke.str("pk"))?.takeIf { it.size == HistoryLimits.RPK_LEN } ?: return null
            return HistoryRequestEnvelope(id, r, n.toInt(), pk)
        }
    }
}

/** §17.6 `history_share`: one sealed bundle part, uploaded as a `history` blob. */
class HistoryShareEnvelope(
    val requestId: String,
    val part: Int,
    val parts: Int,
    val blob: BlobRef,
    val plainSize: Long,
    val hpkeEnc: ByteArray,
    val sealedKey: ByteArray,
    val count: Int,
    val range: HistoryRange,
) {
    fun encode(): ByteArray = ProtocolJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", 1)
            put("type", TYPE)
            put("request_id", requestId)
            put("part", part)
            put("parts", parts)
            put("blob", buildJsonObject { put("blob_id", blob.blobId); put("size", blob.size); put("sha256", blob.sha256) })
            put(
                "enc",
                buildJsonObject {
                    put("alg", HistoryLimits.ALG)
                    put("label", HistoryLimits.LABEL)
                    put("plain_size", plainSize)
                    put("hpke_enc", b64e.encodeToString(hpkeEnc))
                    put("sealed_key", b64e.encodeToString(sealedKey))
                },
            )
            put("count", count)
            put("range", buildJsonObject { put("from", range.from); put("to", range.to) })
        },
    ).toByteArray(Charsets.UTF_8)

    companion object {
        const val TYPE = "history_share"

        /** Strict validation (§17.6); null = drop and log by count. */
        fun validate(o: JsonObject): HistoryShareEnvelope? {
            if (!o.versionOk() || o.str("type") != TYPE) return null
            val id = canonicalUuid(o.str("request_id")) ?: return null
            val part = o.num("part") ?: return null
            val parts = o.num("parts") ?: return null
            if (part < 1 || part > parts || parts > HistoryLimits.MAX_PARTS) return null
            val blob = o.obj("blob") ?: return null
            val blobId = blob.str("blob_id")?.takeIf { it.isNotBlank() } ?: return null
            val size = blob.num("size") ?: return null
            val sha = blob.str("sha256")?.takeIf { b64(it)?.size == 32 } ?: return null
            val enc = o.obj("enc") ?: return null
            if (enc.str("alg") != HistoryLimits.ALG || enc.str("label") != HistoryLimits.LABEL) return null
            val plain = enc.num("plain_size")?.takeIf { it in 1..HistoryLimits.MAX_PLAIN } ?: return null
            if (size > HistoryLimits.MAX_CIPHER || MediaFormat.cipherSize(plain) != size) return null
            val hpkeEnc = b64(enc.str("hpke_enc"))?.takeIf { it.size == HistoryLimits.HPKE_ENC_LEN } ?: return null
            val sealedKey = b64(enc.str("sealed_key"))?.takeIf { it.size == HistoryLimits.SEALED_KEY_LEN } ?: return null
            val count = o.num("count")?.takeIf { it in 0..HistoryLimits.MAX_ENTRIES.toLong() } ?: return null
            val r = range(o.obj("range")) ?: return null
            return HistoryShareEnvelope(id, part.toInt(), parts.toInt(), BlobRef(blobId, size, sha), plain, hpkeEnc, sealedKey, count.toInt(), r)
        }
    }
}
