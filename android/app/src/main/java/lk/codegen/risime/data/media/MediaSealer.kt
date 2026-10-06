package lk.codegen.risime.data.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.net.ProtocolJson
import java.util.Base64

/**
 * Android R1: an image's content key and its thumbnail are stored sealed with the database key
 * (KvSealer under MlsDbKey). The AAD binds each value to its message row (`media` ‖ client_msg_id,
 * `media-thumb` ‖ client_msg_id), so rows can't be swapped. A copied `risime.db` plus the cache
 * directory are useless without the Keystore key.
 */
class MediaSealer(private val sealer: () -> KvSealer) {
    @Serializable
    private class EncJson(val alg: String, val key: String, @SerialName("plain_size") val plainSize: Long)

    @Serializable
    private class ThumbJson(val mime: String, val w: Int, val h: Int, val data: String)

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun sealEnc(clientMsgId: String, enc: ImageEnc): ByteArray =
        sealer().seal(NS_ENC, clientMsgId.toByteArray(), ProtocolJson.encodeToString(EncJson.serializer(), EncJson(enc.alg, b64e.encodeToString(enc.key), enc.plainSize)).toByteArray())

    fun openEnc(clientMsgId: String, sealed: ByteArray): ImageEnc {
        val j = ProtocolJson.decodeFromString(EncJson.serializer(), sealer().open(NS_ENC, clientMsgId.toByteArray(), sealed).toString(Charsets.UTF_8))
        return ImageEnc(j.alg, b64d.decode(j.key), j.plainSize)
    }

    fun sealThumb(clientMsgId: String, t: ImageThumb): ByteArray =
        sealer().seal(NS_THUMB, clientMsgId.toByteArray(), ProtocolJson.encodeToString(ThumbJson.serializer(), ThumbJson(t.mime, t.w, t.h, b64e.encodeToString(t.data))).toByteArray())

    fun openThumb(clientMsgId: String, sealed: ByteArray): ImageThumb {
        val j = ProtocolJson.decodeFromString(ThumbJson.serializer(), sealer().open(NS_THUMB, clientMsgId.toByteArray(), sealed).toString(Charsets.UTF_8))
        return ImageThumb(j.mime, j.w, j.h, b64d.decode(j.data))
    }

    companion object {
        const val NS_ENC = "media"
        const val NS_THUMB = "media-thumb"
    }
}
