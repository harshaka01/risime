package lk.codegen.risime.crypto

import lk.codegen.risime.data.backup.BackupCipherReader
import lk.codegen.risime.data.backup.BackupCipherWriter
import lk.codegen.risime.data.backup.BackupException
import lk.codegen.risime.data.backup.BackupFileMeta
import lk.codegen.risime.data.backup.BackupKeyIds as AppKeyIds
import lk.codegen.risime.data.backup.BackupKeys
import lk.codegen.risime.data.backup.BackupSetupResult
import lk.codegen.risime.data.backup.BackupTools
import lk.codegen.risime.data.backup.BackupWrittenInfo
import lk.codegen.risime.data.backup.PassphraseFloor
import lk.codegen.risime.data.backup.SecretKind
import java.io.File

internal fun RisiBackupException.toApp(): BackupException = BackupException(
    when (this) {
        is RisiBackupException.Typo -> BackupException.Kind.Typo
        is RisiBackupException.WrongKey -> BackupException.Kind.WrongKey
        is RisiBackupException.Integrity -> BackupException.Kind.Integrity
        is RisiBackupException.Format -> BackupException.Kind.Format
        is RisiBackupException.Malformed -> BackupException.Kind.Malformed
        is RisiBackupException.Unsupported -> BackupException.Kind.Unsupported
        is RisiBackupException.WrongAccount -> BackupException.Kind.WrongAccount
        is RisiBackupException.NoKey -> BackupException.Kind.NoKey
        is RisiBackupException.WeakPassphrase -> BackupException.Kind.WeakPassphrase
        is RisiBackupException.Io -> BackupException.Kind.Io
        is RisiBackupException.Storage -> BackupException.Kind.Storage
    },
    message,
)

internal inline fun <T> bk(block: () -> T): T = try {
    block()
} catch (e: RisiBackupException) {
    throw e.toApp()
}

internal fun BackupFileInfo.toApp() = BackupFileMeta(backupId, userId, createdAt, appVersion, bkId, schema.toLong(), keyRecord)

/**
 * [BackupKeys] over one device's `MlsClient` (§22.10). [tx] runs a block inside the engine's Room
 * transaction and lock, like every other core call (the keys live in the sealed `mls_kv`). The
 * writer and reader are created there and then stream outside it (they hold their own keys).
 */
class UniffiBackupKeys(private val client: MlsClient, private val tx: (() -> Any?) -> Any?) : BackupKeys {
    @Suppress("UNCHECKED_CAST")
    private fun <T> inTx(block: () -> T): T = tx { bk(block) } as T

    override fun setup(userId: String): BackupSetupResult = inTx { client.backupSetup(userId).let { BackupSetupResult(it.recoveryKey, it.keyRecord) } }

    override fun addPassphrase(userId: String, passphrase: String): String = inTx { client.backupAddPassphrase(userId, passphrase) }

    override fun unlock(userId: String, keyRecord: String, secret: String, kind: SecretKind, makeCurrent: Boolean): String = inTx {
        client.backupUnlock(userId, keyRecord, secret, if (kind == SecretKind.RecoveryKey) BackupSecretKind.RECOVERY_KEY else BackupSecretKind.PASSPHRASE, makeCurrent)
    }

    override fun recoveryKey(): String? = inTx { client.backupRecoveryKey() }

    override fun rotateRecoveryKey(userId: String): BackupSetupResult = inTx { client.backupRotateRecoveryKey(userId).let { BackupSetupResult(it.recoveryKey, it.keyRecord) } }

    override fun forget() = inTx { client.backupForget() }

    override fun keyRecord(): String? = inTx { client.backupKeyRecord() }

    override fun keyIds(): AppKeyIds = inTx { client.backupKeyIds().let { AppKeyIds(it.current, it.old) } }

    override fun dropKey(bkId: String) = inTx { client.backupDropKey(bkId) }

    override fun writer(userId: String, backupId: String, createdAt: String, appVersion: String, keyRecord: String?, out: File): BackupCipherWriter {
        val w = inTx { client.backupWriter(userId, backupId, createdAt, appVersion, keyRecord, out.absolutePath) }
        return object : BackupCipherWriter {
            override fun write(bytes: ByteArray) = bk { w.write(bytes) }

            override fun finish(): BackupWrittenInfo = bk {
                val r = w.finish()
                BackupWrittenInfo(r.backupId, r.bkId, r.size.toLong(), r.sha256, r.dataSize.toLong())
            }
        }
    }

    override fun reader(userId: String, file: File, expectedBackupId: String?, expectedBkId: String?): BackupCipherReader {
        val r = inTx { client.backupReader(userId, file.absolutePath, expectedBackupId, expectedBkId) }
        return object : BackupCipherReader {
            override fun info(): BackupFileMeta = bk { r.info().toApp() }

            override fun verify(): Long = bk { r.verify().dataSize.toLong() }

            override fun read(): ByteArray = bk { r.read() }
        }
    }
}

/** [BackupTools] over the free functions. Found by name from main. */
class UniffiBackupTools : BackupTools {
    override fun fileInfo(file: File): BackupFileMeta = bk { backupFileInfo(file.absolutePath).toApp() }

    override fun normalizeRecoveryKey(input: String): String = bk { backupNormalizeRecoveryKey(input) }

    override fun passphraseFloor(passphrase: String, phone: String?): PassphraseFloor = when (backupPassphraseFloor(passphrase, phone)) {
        BackupPassphraseFloor.OK -> PassphraseFloor.Ok
        BackupPassphraseFloor.TOO_SHORT -> PassphraseFloor.TooShort
        BackupPassphraseFloor.PHONE_NUMBER -> PassphraseFloor.PhoneNumber
    }

    override fun vectorsCheck(json: String): Int = bk { backupVectorsCheck(json).toInt() }
}
