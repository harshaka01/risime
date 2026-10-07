package lk.codegen.risime.data.mls

import lk.codegen.risime.data.history.HistoryAad
import lk.codegen.risime.data.media.MediaFormat
import java.io.File
import java.security.MessageDigest

/**
 * Stand-in for the core's history_seal/open: the blob is the plaintext padded to the §14.3 size;
 * `hpke_enc` = rpk, `sealed_key` binds (request, requester, provider, part, parts, sha256), so a
 * wrong identity, part or blob fails like HPKE would.
 */
class FakeHistoryCrypto : HistoryCrypto {
    override fun seal(rpk: ByteArray, ctx: HistoryCtx, plaintext: ByteArray, out: File): SealedPartInfo {
        val size = MediaFormat.cipherSize(plaintext.size.toLong())!!
        val bytes = plaintext + ByteArray((size - plaintext.size).toInt())
        out.writeBytes(bytes)
        val sha = sha(bytes)
        return SealedPartInfo(rpk, binding(ctx, sha, plaintext.size.toLong()), plaintext.size.toLong(), size, sha)
    }

    override fun aadEncode(requestId: String) = HistoryAad.encode(requestId)
    override fun aadDecode(aad: ByteArray) = HistoryAad.decode(aad)
    override fun vectorsCheck(vectorsJson: String, workDir: File) = 0

    companion object {
        fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

        fun rpkFor(requestId: String) = sha("rpk:$requestId".toByteArray())

        fun binding(ctx: HistoryCtx, sha: ByteArray, plain: Long): ByteArray =
            sha("${ctx.requestId}|${ctx.conversationId}|${ctx.requester}|${ctx.provider}|${ctx.part}|${ctx.parts}|$plain".toByteArray() + sha) + ByteArray(16)

        fun open(rpk: ByteArray, ctx: HistoryCtx, hpkeEnc: ByteArray, sealedKey: ByteArray, blob: File): ByteArray {
            val bytes = blob.readBytes()
            if (!sha(bytes).contentEquals(ctx.sha256)) throw HistoryException(HistoryException.Kind.OpenFailed, "integrity: sha256")
            if (!hpkeEnc.contentEquals(rpk) || !sealedKey.contentEquals(binding(ctx, ctx.sha256, ctx.plainSize))) throw HistoryException(HistoryException.Kind.OpenFailed, "hpke")
            return bytes.copyOf(ctx.plainSize.toInt())
        }
    }
}
