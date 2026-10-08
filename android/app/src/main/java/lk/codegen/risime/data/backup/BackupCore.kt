package lk.codegen.risime.data.backup

import java.io.File

/**
 * §22.2/§22.10 the crypto core's backup functions as the app sees them. `BK`, `KEK` and `DEK` never
 * cross this interface; the recovery key crosses only as its display string (never logged).
 * Implemented over risime-mls-ffi's `MlsClient` (crypto source set) inside the engine's
 * transaction and lock; JVM tests use a fake. Argon2id runs in [setup], [addPassphrase], [unlock]
 * and [rotateRecoveryKey]: never call them on the main thread.
 */
interface BackupKeys {
    /** Makes `BK` + `R` (or reuses the stored pair, also a silent local one): the recovery key and the record to `PUT`. */
    fun setup(userId: String): BackupSetupResult

    /** Adds or replaces the passphrase wrap; returns the record to `PUT` (same `bk_id`). */
    fun addPassphrase(userId: String, passphrase: String): String

    /** Unlocks a record (server or file header) and stores its `BK`; returns its `bk_id` (base64). */
    fun unlock(userId: String, keyRecord: String, secret: String, kind: SecretKind, makeCurrent: Boolean): String

    fun recoveryKey(): String?

    fun rotateRecoveryKey(userId: String): BackupSetupResult

    /** Deletes every local backup key (confirmed wipe, "Reset backup key"). Idempotent. */
    fun forget()

    fun keyRecord(): String?

    fun keyIds(): BackupKeyIds

    fun dropKey(bkId: String)

    /** A fresh `DEK`; [keyRecord] null = the stored record. The file appears at [out] only on [BackupCipherWriter.finish]. */
    fun writer(userId: String, backupId: String, createdAt: String, appVersion: String, keyRecord: String?, out: File): BackupCipherWriter

    /** §22.4 opening order; a server backup passes the listing's ids. [BackupException.Kind.NoKey]: unlock first. */
    fun reader(userId: String, file: File, expectedBackupId: String?, expectedBkId: String?): BackupCipherReader
}

/** The core's free functions (no client state). Found by name: only builds with the Rust toolchain have it. */
interface BackupTools {
    /** The header without a key (unauthenticated): the restore screen and a file's key record. */
    fun fileInfo(file: File): BackupFileMeta

    /** The display form of a typed recovery key, or [BackupException.Kind.Typo] / [BackupException.Kind.Malformed]. */
    fun normalizeRecoveryKey(input: String): String

    fun passphraseFloor(passphrase: String, phone: String?): PassphraseFloor

    /** Test support: every case of `backup_vectors.json`; returns the count (44). */
    fun vectorsCheck(json: String): Int

    companion object {
        private const val IMPL = "lk.codegen.risime.crypto.UniffiBackupTools"

        fun get(): BackupTools? = runCatching { Class.forName(IMPL).getDeclaredConstructor().newInstance() as BackupTools }.getOrNull()
    }
}

enum class SecretKind { RecoveryKey, Passphrase }

enum class PassphraseFloor { Ok, TooShort, PhoneNumber }

class BackupSetupResult(val recoveryKey: String, val keyRecord: String) {
    override fun toString() = "BackupSetupResult(***)"
}

data class BackupKeyIds(val current: String?, val old: List<String>)

data class BackupFileMeta(
    val backupId: String,
    val userId: String,
    val createdAt: String,
    val appVersion: String,
    val bkId: String,
    val schema: Long,
    val keyRecord: String?,
)

/** What `finish` wrote (for `POST /backups`). [sha256] raw 32 bytes of the whole file. */
class BackupWrittenInfo(val backupId: String, val bkId: String, val size: Long, val sha256: ByteArray, val dataSize: Long)

/** Feed it the app's raw DEFLATE output (any split); a failed writer is dead (start a new backup id). */
interface BackupCipherWriter {
    fun write(bytes: ByteArray)

    fun finish(): BackupWrittenInfo
}

/** [verify] (the whole pass) first, then [read] until it returns an empty array (the DEFLATE stream). */
interface BackupCipherReader {
    fun info(): BackupFileMeta

    /** Returns the DEFLATE size. Nothing may be imported before this succeeded. */
    fun verify(): Long

    fun read(): ByteArray
}

/** A §22 core failure. */
class BackupException(val kind: Kind, message: String? = null) : Exception(message ?: kind.name) {
    enum class Kind { Typo, WrongKey, Integrity, Format, Malformed, Unsupported, WrongAccount, NoKey, WeakPassphrase, Io, Storage }
}
