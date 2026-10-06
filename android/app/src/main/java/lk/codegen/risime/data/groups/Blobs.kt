package lk.codegen.risime.data.groups

import lk.codegen.risime.net.BlobRef
import java.security.MessageDigest
import java.util.Base64

/** §12.6: a downloaded blob is used only if its size and SHA-256 match the reference. */
fun blobMatches(bytes: ByteArray, ref: BlobRef): Boolean =
    bytes.size.toLong() == ref.size && Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)) == ref.sha256

/** Inline values are at most 64 KiB decoded (§12.4); anything larger goes by blob reference. */
const val INLINE_MAX_BYTES = 64 * 1024
