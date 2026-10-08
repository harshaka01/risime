package lk.codegen.risime.data.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import lk.codegen.risime.net.ProtocolJson
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * The §22.3 server in MockWebServer, enough for the client's paths: the switch, part uploads
 * (owner-only, `backup_id`, X-Device-Id), the commit (key record and `bk_id`, the backup device
 * rule, part sizes and digests), the listing, `backup_key` (no silent key change) and `DELETE`.
 */
class FakeBackupApi {
    val server = MockWebServer()
    var switch = "on"
    var keyRecord: JsonObject? = null
    val blobs = mutableMapOf<String, Pair<String, ByteArray>>() // blob_id -> (backup_id, bytes)
    val backups = mutableListOf<JsonObject>() // newest first
    var backupDevice: String? = null
    var backupDeviceName = "Pixel 8"
    val requests = mutableListOf<String>()
    var commits = 0

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private fun sha(b: ByteArray) = b64(MessageDigest.getInstance("SHA-256").digest(b))

    private fun json(code: Int, body: Any) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun error(code: Int, c: String, extra: JsonObject.() -> JsonObject = { this }) =
        json(code, buildJsonObject { put("error", JsonObject(buildJsonObject { put("code", c); put("message", c) }.let(extra))) })

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeBackupApi) { handle(request) }
        }
    }

    fun url(): String = server.url("/").toString().trimEnd('/')

    private fun handle(r: RecordedRequest): MockResponse {
        val path = r.requestUrl!!.encodedPath.removePrefix("/api/v1/")
        val q = r.requestUrl!!
        requests += "${r.method} $path${q.query?.let { "?$it" } ?: ""}"
        val device = r.getHeader("X-Device-Id")
        return when {
            r.method == "GET" && path == "auth/config" -> json(200, """{"modes":["dev"],"backup":"$switch"}""")
            r.method == "GET" && path == "backup_key" -> keyRecord?.let { json(200, buildJsonObject { put("backup_key", it) }) } ?: error(404, "no_backup_key")
            r.method == "PUT" && path == "backup_key" -> {
                if (switch != "on") return error(503, "backup_unavailable")
                if (device == null) return error(403, "invalid_device")
                val rec = ProtocolJson.parseToJsonElement(r.body.readUtf8()) as JsonObject
                val bk = rec["bk_id"]!!.jsonPrimitive.content
                val stored = keyRecord?.get("bk_id")?.jsonPrimitive?.content
                if (stored != null && stored != bk && backups.isNotEmpty()) return error(409, "backup_key_conflict") { JsonObject(this + ("bk_id" to JsonPrimitive(stored))) }
                keyRecord = JsonObject(rec + ("updated_at" to JsonPrimitive("2026-10-08T01:58:02.120Z")))
                json(200, buildJsonObject { put("backup_key", keyRecord!!) })
            }
            r.method == "POST" && path == "blobs" && q.queryParameter("purpose") == "backup" -> {
                if (switch != "on") return error(503, "backup_unavailable")
                if (device == null) return error(403, "invalid_device")
                if (q.queryParameter("conversation_id") != null) return error(400, "bad_request")
                val bytes = r.body.readByteArray()
                val id = UUID.randomUUID().toString()
                blobs[id] = q.queryParameter("backup_id")!! to bytes
                json(201, """{"blob_id":"$id","size":${bytes.size},"sha256":"${sha(bytes)}","expires_at":"2026-10-09T02:00:12.345Z"}""")
            }
            r.method == "POST" && path == "backups" -> commit(ProtocolJson.parseToJsonElement(r.body.readUtf8()) as JsonObject, device)
            r.method == "GET" && path == "backups" -> json(200, buildJsonObject {
                put("backups", JsonArray(backups))
                put("key", keyRecord != null)
                put("quota", buildJsonObject { put("used", 0); put("limit", 1610612736) })
            })
            r.method == "DELETE" && path == "backups" -> {
                backups.clear(); blobs.clear(); keyRecord = null; backupDevice = null
                MockResponse().setResponseCode(204)
            }
            r.method == "GET" && path.startsWith("blobs/") -> blobs[path.removePrefix("blobs/")]?.let { MockResponse().setResponseCode(200).setBody(Buffer().write(it.second)) } ?: error(404, "not_found")
            else -> error(404, "not_found")
        }
    }

    private fun commit(b: JsonObject, device: String?): MockResponse {
        if (switch != "on") return error(503, "backup_unavailable")
        if (device == null) return error(403, "invalid_device")
        val id = b["backup_id"]!!.jsonPrimitive.content
        backups.firstOrNull { it["backup_id"]!!.jsonPrimitive.content == id }?.let { return json(200, buildJsonObject { put("backup", it) }) }
        val rec = keyRecord ?: return error(409, "no_backup_key")
        if (rec["bk_id"]!!.jsonPrimitive.content != b["bk_id"]!!.jsonPrimitive.content) return error(409, "backup_key_conflict")
        if (backupDevice != null && backupDevice != device && b["replace_device"]!!.jsonPrimitive.content != "true") {
            return error(409, "backup_device_mismatch") { JsonObject(this + ("device_id" to JsonPrimitive(backupDevice)) + ("device_name" to JsonPrimitive(backupDeviceName))) }
        }
        val parts = b["parts"]!!.jsonArray.map { it.jsonObject }
        var total = 0L
        for (p in parts) {
            val (bid, bytes) = blobs[p["blob_id"]!!.jsonPrimitive.content] ?: return error(400, "bad_request")
            if (bid != id || bytes.size.toLong() != p["size"]!!.jsonPrimitive.long || sha(bytes) != p["sha256"]!!.jsonPrimitive.content) return error(400, "bad_request")
            total += bytes.size
        }
        if (total != b["size"]!!.jsonPrimitive.long) return error(400, "bad_request")
        commits++
        backupDevice = device
        val backup = JsonObject(
            b - "replace_device" + mapOf(
                "device_id" to JsonPrimitive(device), "device_name" to JsonPrimitive(backupDeviceName),
                "uploaded_at" to JsonPrimitive("2026-10-08T02:03:41.512Z"), "current" to JsonPrimitive(true), "expires_at" to kotlinx.serialization.json.JsonNull,
            ),
        )
        backups.add(0, backup)
        return json(201, buildJsonObject { put("backup", backup) })
    }

    fun shutdown() = server.shutdown()
}
