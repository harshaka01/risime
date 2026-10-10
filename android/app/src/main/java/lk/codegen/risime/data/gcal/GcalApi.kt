package lk.codegen.risime.data.gcal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/*
 * v1.31 §31: the Google Calendar REST calls over the existing OkHttp and kotlinx.serialization (no Google API
 * client library). The base URL is a compiled-in constant; only the debug seam (src/debug) replaces it. No
 * logging interceptor at all: nothing of Google's traffic (Authorization, bodies) is ever logged.
 */

/** Why a Google call failed (mapped to the §31.4 reason codes by [GcalErr.reason]). */
enum class GcalErr(val reason: String) {
    /** `401` that a silent re-authorize did not fix, or the user must consent again. */
    REAUTH("reauth_needed"),
    NETWORK("network"),
    TIMEOUT("timeout"),
    API("api_error"),

    /** `404` / `410`. */
    GONE("api_error"),

    /** `409` (insert of an existing id). */
    CONFLICT("api_error"),

    /** No Play services. */
    UNAVAILABLE("api_error"),
}

sealed interface GcalResult<out T> {
    data class Ok<T>(val value: T) : GcalResult<T>

    data class Fail(val err: GcalErr, val http: Int = 0) : GcalResult<Nothing>
}

inline fun <T> GcalResult<T>.getOr(onFail: (GcalResult.Fail) -> Nothing): T = when (this) {
    is GcalResult.Ok -> value
    is GcalResult.Fail -> onFail(this)
}

/** One entry of `users/me/calendarList` (§31.2 step 2). */
data class GCalendar(
    val id: String,
    val name: String,
    val primary: Boolean,
    val accessRole: String,
    val selected: Boolean,
    val hidden: Boolean,
) {
    val canWrite: Boolean get() = accessRole == "owner" || accessRole == "writer"
}

/** An event with just what busy reads and the reconcile need. Times are epoch ms (all-day: local midnights). */
data class GEvent(
    val id: String,
    val status: String?,
    val transparency: String?,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val selfDeclined: Boolean,
    val private: Map<String, String>,
)

class GcalApi(
    http: OkHttpClient,
    private val authorizer: GcalAuthorizer,
    /** Replaced only by the debug seam. */
    private val baseUrl: () -> String = { BASE_URL },
    /** The zone an all-day `date` is read in. */
    private val zone: () -> java.time.ZoneId = java.time.ZoneId::systemDefault,
) {
    companion object {
        /** §31.11 compiled in. */
        const val BASE_URL = "https://www.googleapis.com/calendar/v3/"
        const val CALENDAR_TIMEOUT_MS = 8_000L
        const val TOTAL_TIMEOUT_MS = 12_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val parser = Json { ignoreUnknownKeys = true }

        const val FIELDS_LIST = "items(id,summary,summaryOverride,primary,accessRole,selected,hidden,deleted),nextPageToken"
        const val FIELDS_BUSY = "items(id,status,transparency,start,end,attendees(self,responseStatus),extendedProperties/private),nextPageToken"
        const val FIELDS_TAGGED = "items(id,status,start,end,extendedProperties/private),nextPageToken"
    }

    private val client: OkHttpClient = http.newBuilder().callTimeout(CALENDAR_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()

    private fun url(vararg segments: String, build: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        baseUrl().toHttpUrl().newBuilder().apply { segments.forEach { addPathSegment(it) } }.apply(build).build()

    /** `users/me/calendarList`, every page (§31.2 step 2). Hidden and deleted entries are kept (the picker decides); `deleted` ones are dropped. */
    suspend fun calendarList(): GcalResult<List<GCalendar>> {
        val out = ArrayList<GCalendar>()
        var page: String? = null
        do {
            val u = url("users", "me", "calendarList") {
                addQueryParameter("minAccessRole", "freeBusyReader")
                addQueryParameter("fields", FIELDS_LIST)
                page?.let { addQueryParameter("pageToken", it) }
            }
            val body = when (val r = send("GET", u, null)) {
                is GcalResult.Ok -> r.value
                is GcalResult.Fail -> return r
            }
            for (e in body["items"]?.jsonArray.orEmpty()) {
                val o = e.jsonObject
                if (o["deleted"]?.jsonPrimitive?.booleanOrNull == true) continue
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
                val name = o["summaryOverride"]?.jsonPrimitive?.contentOrNull ?: o["summary"]?.jsonPrimitive?.contentOrNull ?: id
                out += GCalendar(
                    id, name, o["primary"]?.jsonPrimitive?.booleanOrNull == true, o["accessRole"]?.jsonPrimitive?.contentOrNull ?: "reader",
                    o["selected"]?.jsonPrimitive?.booleanOrNull == true, o["hidden"]?.jsonPrimitive?.booleanOrNull == true,
                )
            }
            page = body["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null)
        return GcalResult.Ok(out)
    }

    /** §31.4 `events.list` for busy times, every page. */
    suspend fun busyEvents(calendarId: String, fromMs: Long, toMs: Long): GcalResult<List<GEvent>> =
        events(calendarId, fromMs, toMs, FIELDS_BUSY, null)

    /** §31.6 the tagged copies (`privateExtendedProperty=risime=1`), every page. */
    suspend fun taggedEvents(calendarId: String, fromMs: Long, toMs: Long): GcalResult<List<GEvent>> =
        events(calendarId, fromMs, toMs, FIELDS_TAGGED, "risime=1")

    private suspend fun events(calendarId: String, fromMs: Long, toMs: Long, fields: String, tag: String?): GcalResult<List<GEvent>> {
        val out = ArrayList<GEvent>()
        var page: String? = null
        do {
            val u = url("calendars", calendarId, "events") {
                addQueryParameter("timeMin", java.time.Instant.ofEpochMilli(fromMs).toString())
                addQueryParameter("timeMax", java.time.Instant.ofEpochMilli(toMs).toString())
                addQueryParameter("singleEvents", "true")
                addQueryParameter("maxResults", "250")
                addQueryParameter("fields", fields)
                tag?.let { addQueryParameter("privateExtendedProperty", it) }
                page?.let { addQueryParameter("pageToken", it) }
            }
            val body = when (val r = send("GET", u, null)) {
                is GcalResult.Ok -> r.value
                is GcalResult.Fail -> return r
            }
            for (e in body["items"]?.jsonArray.orEmpty()) parseEvent(e.jsonObject)?.let { out += it }
            page = body["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null)
        return GcalResult.Ok(out)
    }

    fun parseEvent(o: JsonObject): GEvent? {
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val s = o["start"]?.jsonObject ?: return null
        val e = o["end"]?.jsonObject ?: return null
        fun ms(x: JsonObject): Pair<Long, Boolean>? {
            x["dateTime"]?.jsonPrimitive?.contentOrNull?.let { return java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() to false }
            x["date"]?.jsonPrimitive?.contentOrNull?.let { return java.time.LocalDate.parse(it).atStartOfDay(zone()).toInstant().toEpochMilli() to true }
            return null
        }
        val (a, allDay) = runCatching { ms(s) }.getOrNull() ?: return null
        val b = runCatching { ms(e) }.getOrNull()?.first ?: return null
        val declined = o["attendees"]?.jsonArray.orEmpty().any {
            val at = it.jsonObject
            at["self"]?.jsonPrimitive?.booleanOrNull == true && at["responseStatus"]?.jsonPrimitive?.contentOrNull == "declined"
        }
        val priv = o["extendedProperties"]?.jsonObject?.get("private")?.jsonObject?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        return GEvent(id, o["status"]?.jsonPrimitive?.contentOrNull, o["transparency"]?.jsonPrimitive?.contentOrNull, a, b, allDay, declined, priv)
    }

    // ---- writes (every one with sendUpdates=none, §31.6) ----

    suspend fun insert(calendarId: String, event: JsonObject): GcalResult<JsonObject> =
        send("POST", url("calendars", calendarId, "events") { addQueryParameter("sendUpdates", "none") }, event)

    suspend fun get(calendarId: String, eventId: String): GcalResult<JsonObject> =
        send("GET", url("calendars", calendarId, "events", eventId), null)

    suspend fun put(calendarId: String, eventId: String, event: JsonObject): GcalResult<JsonObject> =
        send("PUT", url("calendars", calendarId, "events", eventId) { addQueryParameter("sendUpdates", "none") }, event)

    suspend fun patch(calendarId: String, eventId: String, event: JsonObject): GcalResult<JsonObject> =
        send("PATCH", url("calendars", calendarId, "events", eventId) { addQueryParameter("sendUpdates", "none") }, event)

    /** `404`/`410` count as done (the caller treats [GcalErr.GONE] as success). */
    suspend fun delete(calendarId: String, eventId: String): GcalResult<Unit> =
        when (val r = send("DELETE", url("calendars", calendarId, "events", eventId) { addQueryParameter("sendUpdates", "none") }, null)) {
            is GcalResult.Ok -> GcalResult.Ok(Unit)
            is GcalResult.Fail -> r
        }

    suspend fun move(calendarId: String, eventId: String, destination: String): GcalResult<JsonObject> =
        send("POST", url("calendars", calendarId, "events", eventId, "move") {
            addQueryParameter("destination", destination)
            addQueryParameter("sendUpdates", "none")
        }, null)

    // ---- transport ----

    /** One call with the bearer; a `401` re-authorizes silently once; a second `401` (or a needed consent) is [GcalErr.REAUTH]. */
    private suspend fun send(method: String, u: HttpUrl, body: JsonObject?): GcalResult<JsonObject> {
        var token = when (val a = authorizer.authorize()) {
            is AuthResult.Token -> a.value
            is AuthResult.NeedsResolution, AuthResult.Cancelled -> return GcalResult.Fail(GcalErr.REAUTH)
            is AuthResult.Unavailable -> return GcalResult.Fail(GcalErr.UNAVAILABLE)
        }
        repeat(2) { attempt ->
            val r = once(method, u, body, token)
            if (r is GcalResult.Fail && r.http == 401) {
                authorizer.invalidate()
                if (attempt == 1) return GcalResult.Fail(GcalErr.REAUTH, 401)
                token = when (val a = authorizer.authorize()) {
                    is AuthResult.Token -> a.value
                    else -> return GcalResult.Fail(GcalErr.REAUTH, 401)
                }
            } else {
                return r
            }
        }
        return GcalResult.Fail(GcalErr.REAUTH, 401)
    }

    private suspend fun once(method: String, u: HttpUrl, body: JsonObject?, token: String): GcalResult<JsonObject> = withContext(Dispatchers.IO) {
        val rb = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" || method == "PUT" || method == "PATCH" -> "".toRequestBody(JSON)
            else -> null
        }
        val req = Request.Builder().url(u).method(method, rb).header("Authorization", "Bearer $token").header("Accept", "application/json").build()
        try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> GcalResult.Ok(if (text.isBlank()) JsonObject(emptyMap()) else runCatching { parser.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) })
                    resp.code == 401 -> GcalResult.Fail(GcalErr.REAUTH, 401)
                    resp.code == 404 || resp.code == 410 -> GcalResult.Fail(GcalErr.GONE, resp.code)
                    resp.code == 409 -> GcalResult.Fail(GcalErr.CONFLICT, 409)
                    else -> GcalResult.Fail(GcalErr.API, resp.code)
                }
            }
        } catch (e: InterruptedIOException) {
            GcalResult.Fail(GcalErr.TIMEOUT)
        } catch (e: IOException) {
            GcalResult.Fail(GcalErr.NETWORK)
        }
    }
}
