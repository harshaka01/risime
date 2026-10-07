package lk.codegen.risime.crypto

import lk.codegen.risime.data.mls.HistoryCtx
import lk.codegen.risime.data.mls.HistoryException
import lk.codegen.risime.data.mls.SealedPartInfo
import java.io.File

internal fun HistoryCtx.toFfi() = HistoryContext(requestId, conversationId, requester, provider, part.toUInt(), parts.toUInt(), sha256, plainSize.toULong())

internal fun RisiHistoryException.toApp(): HistoryException = HistoryException(
    when (this) {
        is RisiHistoryException.Malformed -> HistoryException.Kind.Malformed
        is RisiHistoryException.SealRefused -> HistoryException.Kind.SealRefused
        is RisiHistoryException.OpenFailed -> HistoryException.Kind.OpenFailed
        is RisiHistoryException.UnknownRequest -> HistoryException.Kind.UnknownRequest
        is RisiHistoryException.Io -> HistoryException.Kind.Io
        is RisiHistoryException.Storage -> HistoryException.Kind.Storage
    },
    message,
)

/** [lk.codegen.risime.data.mls.HistoryCrypto] over risime-mls-ffi's free §17.3 functions. Found by name from main. */
class UniffiHistoryCrypto : lk.codegen.risime.data.mls.HistoryCrypto {
    override fun seal(rpk: ByteArray, ctx: HistoryCtx, plaintext: ByteArray, out: File): SealedPartInfo = try {
        val s = historySeal(rpk, ctx.toFfi(), plaintext, out.absolutePath)
        SealedPartInfo(s.hpkeEnc, s.sealedKey, s.plainSize.toLong(), s.size.toLong(), s.sha256)
    } catch (e: RisiHistoryException) {
        throw e.toApp()
    }

    override fun aadEncode(requestId: String): ByteArray = historyAadEncode(requestId)

    override fun aadDecode(aad: ByteArray): String? = try {
        historyAadDecode(aad)
    } catch (e: RisiMlsException) {
        null
    }

    override fun vectorsCheck(vectorsJson: String, workDir: File): Int = try {
        historyVectorsCheck(vectorsJson, workDir.absolutePath).toInt()
    } catch (e: RisiHistoryException) {
        throw e.toApp()
    }
}
