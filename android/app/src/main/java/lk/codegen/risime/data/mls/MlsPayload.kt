package lk.codegen.risime.data.mls

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import lk.codegen.risime.net.ProtocolJson

/**
 * The MLS application payload envelope (decision 039, contract §10.3): what goes *inside* an MLS
 * application message. Reactions, group events and images will be new `type`s; apps from
 * nightly.8 on ignore types they don't know instead of showing them as text.
 */
object MlsPayload {
    const val VERSION = 1
    const val TYPE_TEXT = "text"
    const val TYPE_REACTION = "reaction"

    sealed interface Decoded {
        data class Text(val body: String) : Decoded

        /** §11.2: an encrypted reaction (op "add" sets, "remove" clears). */
        data class Reaction(val target: String, val emoji: String, val op: String) : Decoded

        /** §14.4: a strictly validated image envelope. */
        data class Image(val envelope: lk.codegen.risime.data.media.ImageEnvelope) : Decoded

        /** §15.3: a strictly validated delete control (1..100 distinct lowercase TimeUUIDs). */
        data class Delete(val targets: List<String>) : Decoded

        /** §16.2 a strictly validated call envelope (`call_end` in a message event, the rest in `call_signal` events). */
        data class Call(val env: lk.codegen.risime.calls.CallEnvelope.Env) : Decoded

        /** §17.6 a strictly validated `history_request` (valid only in a `history_request` event). */
        data class HistoryRequest(val env: lk.codegen.risime.data.history.HistoryRequestEnvelope) : Decoded

        /** §17.6 a strictly validated `history_share` (valid only in a `history_share` event). */
        data class HistoryShare(val env: lk.codegen.risime.data.history.HistoryShareEnvelope) : Decoded

        /** A type this app doesn't know yet: store nothing visible. */
        data class Ignored(val type: String) : Decoded
    }

    /** Sending: UTF-8 JSON `{"v":1,"type":"text","body":"…"}`. */
    fun text(body: String): ByteArray = ProtocolJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", VERSION)
            put("type", TYPE_TEXT)
            put("body", body)
        },
    ).toByteArray(Charsets.UTF_8)

    /** §11.2 envelope `{"v":1,"type":"reaction","target","emoji","op"}`. */
    fun reaction(target: String, emoji: String, op: String): ByteArray = ProtocolJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", VERSION)
            put("type", TYPE_REACTION)
            put("target", target)
            put("emoji", emoji)
            put("op", op)
        },
    ).toByteArray(Charsets.UTF_8)

    const val TYPE_DELETE = "delete"

    /** §15.3 envelope `{"v":1,"type":"delete","targets":[…]}` (nothing else). */
    fun delete(targets: List<String>): ByteArray = ProtocolJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("v", VERSION)
            put("type", TYPE_DELETE)
            put("targets", kotlinx.serialization.json.JsonArray(targets.map { JsonPrimitive(it) }))
        },
    ).toByteArray(Charsets.UTF_8)

    /** §15.3 strict validation: `v` = 1 and 1..100 distinct TimeUUID strings, else null (drop the whole control). */
    fun validateDelete(obj: JsonObject): List<String>? {
        val v = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
        if (v != VERSION) return null
        val arr = obj["targets"] as? kotlinx.serialization.json.JsonArray ?: return null
        if (arr.isEmpty() || arr.size > lk.codegen.risime.net.MsgDelete.MAX_TARGETS) return null
        val ids = arr.map { el ->
            val p = (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
            val u = runCatching { java.util.UUID.fromString(p) }.getOrNull()?.takeIf { it.version() == 1 && it.toString().equals(p, true) } ?: return null
            u.toString()
        }
        return ids.takeIf { it.toSet().size == it.size }
    }

    /**
     * Receiving: a JSON object with a string `type` → "text" gives its `body`, anything else is
     * ignored; everything else (not JSON, not an object, no string type) is legacy plain text.
     */
    fun decode(plaintext: ByteArray): Decoded {
        val s = plaintext.toString(Charsets.UTF_8)
        val obj = runCatching { ProtocolJson.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: return Decoded.Text(s)
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return Decoded.Text(s)
        if (type == TYPE_REACTION) {
            fun str(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val target = str("target"); val emoji = str("emoji"); val op = str("op")
            return if (target != null && emoji != null && (op == "add" || op == "remove")) Decoded.Reaction(target, emoji, op) else Decoded.Ignored("reaction (malformed)")
        }
        if (type == TYPE_DELETE) {
            return validateDelete(obj)?.let { Decoded.Delete(it) } ?: Decoded.Ignored("delete (malformed)")
        }
        if (type == lk.codegen.risime.data.media.ImageEnvelope.TYPE) {
            // §14.4: malformed → dropped and logged like an unknown type (never stored, fetched or decoded).
            return lk.codegen.risime.data.media.ImageEnvelope.validate(obj)?.let { Decoded.Image(it) } ?: Decoded.Ignored("image (malformed)")
        }
        if (lk.codegen.risime.calls.CallEnvelope.isCallType(type)) {
            // §16.2: malformed → dropped and logged (no line, no marker).
            var why = ""
            return lk.codegen.risime.calls.CallEnvelope.decode(obj, plaintext.size) { why = it }?.let { Decoded.Call(it) } ?: Decoded.Ignored("$type (malformed: $why)")
        }
        if (type == lk.codegen.risime.data.history.HistoryRequestEnvelope.TYPE) {
            return lk.codegen.risime.data.history.HistoryRequestEnvelope.validate(obj)?.let { Decoded.HistoryRequest(it) } ?: Decoded.Ignored("$type (malformed)")
        }
        if (type == lk.codegen.risime.data.history.HistoryShareEnvelope.TYPE) {
            return lk.codegen.risime.data.history.HistoryShareEnvelope.validate(obj)?.let { Decoded.HistoryShare(it) } ?: Decoded.Ignored("$type (malformed)")
        }
        if (type != TYPE_TEXT) return Decoded.Ignored(type)
        val body = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return Decoded.Ignored("text without body")
        return Decoded.Text(body)
    }
}
