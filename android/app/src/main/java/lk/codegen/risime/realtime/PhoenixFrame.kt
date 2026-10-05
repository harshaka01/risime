package lk.codegen.risime.realtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import lk.codegen.risime.net.ProtocolJson

/** One Phoenix V2 serializer frame: `[join_ref, ref, topic, event, payload]`. */
data class PhoenixFrame(
    val joinRef: String?,
    val ref: String?,
    val topic: String,
    val event: String,
    val payload: JsonElement,
) {
    fun encode(): String = JsonArray(
        listOf(
            joinRef?.let(::JsonPrimitive) ?: JsonNull,
            ref?.let(::JsonPrimitive) ?: JsonNull,
            JsonPrimitive(topic),
            JsonPrimitive(event),
            payload,
        ),
    ).toString()

    /** For `phx_reply`: status ("ok" / "error") and response. */
    val replyStatus: String?
        get() = (payload as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull

    val replyResponse: JsonObject
        get() = ((payload as? JsonObject)?.get("response") as? JsonObject) ?: JsonObject(emptyMap())

    companion object {
        const val PHX_JOIN = "phx_join"
        const val PHX_LEAVE = "phx_leave"
        const val PHX_REPLY = "phx_reply"
        const val PHX_ERROR = "phx_error"
        const val PHX_CLOSE = "phx_close"
        const val HEARTBEAT = "heartbeat"
        const val PHOENIX_TOPIC = "phoenix"

        fun decode(text: String): PhoenixFrame? = runCatching {
            val arr = ProtocolJson.parseToJsonElement(text).jsonArray
            if (arr.size != 5) return null
            PhoenixFrame(
                joinRef = (arr[0] as? JsonPrimitive)?.contentOrNull,
                ref = (arr[1] as? JsonPrimitive)?.contentOrNull,
                topic = arr[2].jsonPrimitive.content,
                event = arr[3].jsonPrimitive.content,
                payload = arr[4].let { if (it is JsonNull) JsonObject(emptyMap()) else it },
            )
        }.getOrNull()

        fun emptyPayload(): JsonObject = JsonObject(emptyMap())
    }
}
