package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---- Encrypted backups (§22, v1.22). The server is blind: ids, sizes and ciphertext only. ----

/** §22.3 one part of a backup file (a consecutive byte range uploaded as a `backup` blob). */
@Serializable
data class BackupPartRef(
    @SerialName("blob_id") val blobId: String,
    val size: Long,
    /** base64 SHA-256 of the part. */
    val sha256: String,
)

/** §22.3 `POST /backups` (`backup_create_request.json`). */
@Serializable
data class BackupCreateRequest(
    @SerialName("backup_id") val backupId: String,
    @SerialName("created_at") val createdAt: String,
    /** The whole file. */
    val size: Long,
    val sha256: String,
    val schema: Int,
    @SerialName("app_version") val appVersion: String,
    @SerialName("bk_id") val bkId: String,
    val parts: List<BackupPartRef>,
    @SerialName("replace_device") val replaceDevice: Boolean,
)

/** §22.3 a committed backup (current or replaced). */
@Serializable
data class Backup(
    @SerialName("backup_id") val backupId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("uploaded_at") val uploadedAt: String,
    val size: Long,
    val sha256: String,
    val schema: Int,
    @SerialName("app_version") val appVersion: String,
    @SerialName("bk_id") val bkId: String,
    val parts: List<BackupPartRef>,
    val current: Boolean,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** §22.3 `POST /backups` reply (`backup_create_reply.json`). */
@Serializable
data class BackupCreateReply(val backup: Backup)

@Serializable
data class BackupQuota(val used: Long, val limit: Long)

/** §22.3 `GET /backups` (`backups_reply.json`): newest `uploaded_at` first. */
@Serializable
data class BackupsReply(val backups: List<Backup>, val key: Boolean, val quota: BackupQuota? = null) {
    /** The restore offer (§22.7): the newest current backup, else the newest listed. */
    fun newest(): Backup? = backups.firstOrNull { it.current } ?: backups.firstOrNull()
}

/**
 * §22.3 `BackupKey`: kept as JSON (the crypto core parses and produces it; the app only reads
 * `bk_id`). `GET/PUT /backup_key` reply: `{"backup_key": BackupKey}` (`backup_key_reply.json`).
 */
@Serializable
data class BackupKeyReply(@SerialName("backup_key") val backupKey: JsonObject) {
    val bkId: String? get() = (backupKey["bk_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
}

/** §22.4 the file header (`backup_file_header.json`); the app reads it only through the core's `backupFileInfo`. */
@Serializable
data class BackupFileHeader(
    val v: Int,
    val schema: Int,
    @SerialName("backup_id") val backupId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("bk_id") val bkId: String,
    val dek: JsonObject,
    val stream: JsonObject,
    val key: JsonObject? = null,
)

object BackupErrors {
    const val UNAVAILABLE = "backup_unavailable"
    const val NO_KEY = "no_backup_key"
    const val KEY_CONFLICT = "backup_key_conflict"
    const val DEVICE_MISMATCH = "backup_device_mismatch"
}
