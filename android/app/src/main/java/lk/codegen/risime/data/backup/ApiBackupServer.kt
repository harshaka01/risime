package lk.codegen.risime.data.backup

import kotlinx.serialization.json.JsonObject
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.Backup
import lk.codegen.risime.net.BackupCreateRequest
import lk.codegen.risime.net.BlobUploadReply
import lk.codegen.risime.net.ProtocolJson
import java.io.File

/** [BackupServer] over the app's REST client (§22.3); [deviceId] goes out as X-Device-Id on writes. */
class ApiBackupServer(private val api: ApiClient, private val deviceId: suspend () -> String) : BackupServer {
    private fun <T> ApiResult<T>.json(f: (T) -> JsonObject): ApiResult<String> = when (this) {
        is ApiResult.Ok -> ApiResult.Ok(ProtocolJson.encodeToString(JsonObject.serializer(), f(value)))
        is ApiResult.Error -> this
        is ApiResult.NetworkError -> this
    }

    override suspend fun switchOn(): Boolean = (api.authConfig() as? ApiResult.Ok)?.value?.backupOn == true

    override suspend fun backups() = api.backups()

    override suspend fun backupKey(): ApiResult<String> = api.backupKey().json { it.backupKey }

    override suspend fun putBackupKey(recordJson: String): ApiResult<String> = api.putBackupKey(recordJson, deviceId()).json { it.backupKey }

    override suspend fun uploadPart(backupId: String, clientBlobId: String, file: File, offset: Long, length: Long): ApiResult<BlobUploadReply> =
        api.uploadBackupPart(backupId, clientBlobId, file, offset, length, deviceId())

    override suspend fun commit(body: BackupCreateRequest): ApiResult<Backup> = when (val r = api.createBackup(body, deviceId())) {
        is ApiResult.Ok -> ApiResult.Ok(r.value.backup)
        is ApiResult.Error -> r
        is ApiResult.NetworkError -> r
    }

    override suspend fun download(blobId: String, into: File): ApiResult<Long> {
        into.delete()
        return api.downloadBlobTo(blobId, into, 0, null)
    }

    override suspend fun deleteAll() = api.deleteBackups(deviceId())
}
