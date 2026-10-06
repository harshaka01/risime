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

    sealed interface Decoded {
        data class Text(val body: String) : Decoded

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

    /**
     * Receiving: a JSON object with a string `type` → "text" gives its `body`, anything else is
     * ignored; everything else (not JSON, not an object, no string type) is legacy plain text.
     */
    fun decode(plaintext: ByteArray): Decoded {
        val s = plaintext.toString(Charsets.UTF_8)
        val obj = runCatching { ProtocolJson.parseToJsonElement(s) as? JsonObject }.getOrNull() ?: return Decoded.Text(s)
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return Decoded.Text(s)
        if (type != TYPE_TEXT) return Decoded.Ignored(type)
        val body = (obj["body"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return Decoded.Ignored("text without body")
        return Decoded.Text(body)
    }
}
