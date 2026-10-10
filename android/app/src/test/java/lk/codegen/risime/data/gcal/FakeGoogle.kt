package lk.codegen.risime.data.gcal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.GcalCalendarEntity
import lk.codegen.risime.data.db.GcalCopyEntity
import lk.codegen.risime.data.db.GcalDao
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/** In-memory [GcalDao]. */
class MemGcalDao : GcalDao {
    val cals = MutableStateFlow<List<GcalCalendarEntity>>(emptyList())
    val copyRows = MutableStateFlow<Map<String, GcalCopyEntity>>(emptyMap())
    override suspend fun calendars() = cals.value.sortedBy { it.name.lowercase() }
    override fun observeCalendars(): Flow<List<GcalCalendarEntity>> = cals
    override suspend fun upsertCalendars(rows: List<GcalCalendarEntity>) { cals.value = (cals.value.associateBy { it.calendarId } + rows.associateBy { it.calendarId }).values.toList() }
    override suspend fun clearCalendars() { cals.value = emptyList() }
    override suspend fun copies() = copyRows.value.values.toList()
    override fun observeCopies(): Flow<List<GcalCopyEntity>> = copyRows.map { it.values.toList() }
    override suspend fun copy(eventId: String) = copyRows.value[eventId]
    override suspend fun upsertCopy(row: GcalCopyEntity) { copyRows.value = copyRows.value + (row.eventId to row) }
    override suspend fun deleteCopy(eventId: String) { copyRows.value = copyRows.value - eventId }
    override suspend fun clearCopies() { copyRows.value = emptyMap() }
}

/** A tiny Google Calendar server (the shape of scripts/fake-gcal): events per calendar, calendarList, bearer check. */
class FakeGoogle(val token: String = "T1") {
    val server = MockWebServer()
    val calendars = linkedMapOf<String, JsonObject>() // id -> calendarList entry
    val events = linkedMapOf<Pair<String, String>, JsonObject>() // (calendar, event id) -> event
    val requests = mutableListOf<Triple<String, String, String>>() // method, path, body
    val queries = mutableListOf<okhttp3.HttpUrl>()
    @Volatile var mode = "ok"
    var pageSize = 2

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this@FakeGoogle) { handle(request) }
        }
        server.start()
    }

    fun baseUrl() = server.url("/calendar/v3/").toString()

    fun api(auth: GcalAuthorizer = FakeAuthorizer(mutableListOf(token))) = GcalApi(OkHttpClient(), auth, baseUrl = { baseUrl() })

    private fun json(code: Int, o: JsonObject = JsonObject(emptyMap())) = MockResponse().setResponseCode(code).setBody(o.toString())

    fun seed(cal: String, id: String, start: String, end: String, extra: JsonObject = JsonObject(emptyMap())) {
        events[cal to id] = JsonObject(buildJsonObject {
            put("id", id); put("status", "confirmed")
            put("start", buildJsonObject { put("dateTime", start) }); put("end", buildJsonObject { put("dateTime", end) })
        } + extra)
    }

    private fun handle(r: RecordedRequest): MockResponse {
        val url = r.requestUrl!!
        queries += url
        val body = r.body.readUtf8()
        requests += Triple(r.method!!, url.encodedPath.removePrefix("/calendar/v3"), body)
        if (r.getHeader("Authorization") != "Bearer $token" || mode == "revoked") return json(401)
        if (mode == "error500") return json(500)
        val seg = url.pathSegments.drop(2) // after calendar, v3
        if (seg.take(2) == listOf("users", "me") && seg.getOrNull(2) == "calendarList") {
            val all = calendars.values.toList()
            val from = url.queryParameter("pageToken")?.toInt() ?: 0
            val page = all.drop(from).take(pageSize)
            return json(200, buildJsonObject {
                put("items", buildJsonArray { page.forEach { add(it) } })
                if (from + pageSize < all.size) put("nextPageToken", (from + pageSize).toString())
            })
        }
        if (seg.firstOrNull() != "calendars") return json(404)
        val cal = seg[1]
        if (seg.size == 3 && seg[2] == "events") {
            when (r.method) {
                "GET" -> {
                    val tag = url.queryParameter("privateExtendedProperty")
                    val all = events.filter { it.key.first == cal && it.value["status"]?.jsonPrimitive?.content != "cancelled" }.values.filter {
                        tag == null || (it["extendedProperties"]?.jsonObject?.get("private")?.jsonObject?.get(tag.substringBefore("="))?.jsonPrimitive?.content == tag.substringAfter("="))
                    }
                    val from = url.queryParameter("pageToken")?.toInt() ?: 0
                    return json(200, buildJsonObject {
                        put("items", buildJsonArray { all.drop(from).take(pageSize).forEach { add(it) } })
                        if (from + pageSize < all.size) put("nextPageToken", (from + pageSize).toString())
                    })
                }
                "POST" -> {
                    val ev = Json.parseToJsonElement(body).jsonObject
                    val id = ev["id"]!!.jsonPrimitive.content
                    if ((cal to id) in events) return json(409)
                    events[cal to id] = JsonObject(ev + ("status" to JsonPrimitive("confirmed")))
                    return json(200, events[cal to id]!!)
                }
            }
        }
        if (seg.size >= 4 && seg[2] == "events") {
            val id = seg[3]
            val cur = events[cal to id]
            if (seg.size == 5 && seg[4] == "move") {
                cur ?: return json(404)
                events.remove(cal to id)
                events[url.queryParameter("destination")!! to id] = cur
                return json(200, cur)
            }
            when (r.method) {
                "GET" -> return if (cur == null) json(404) else json(200, cur)
                "PUT" -> {
                    cur ?: return json(404)
                    events[cal to id] = JsonObject(Json.parseToJsonElement(body).jsonObject + ("status" to (Json.parseToJsonElement(body).jsonObject["status"] ?: JsonPrimitive("confirmed"))))
                    return json(200, events[cal to id]!!)
                }
                "PATCH" -> {
                    cur ?: return json(404)
                    events[cal to id] = JsonObject(cur + Json.parseToJsonElement(body).jsonObject)
                    return json(200, events[cal to id]!!)
                }
                "DELETE" -> {
                    if (cur == null) return json(410)
                    events[cal to id] = JsonObject(cur + ("status" to JsonPrimitive("cancelled")))
                    return MockResponse().setResponseCode(204)
                }
            }
        }
        return json(404)
    }

    /** Events (not cancelled) in [cal]. */
    fun live(cal: String) = events.filter { it.key.first == cal && it.value["status"]?.jsonPrimitive?.content != "cancelled" }.values.toList()

    fun shutdown() = server.shutdown()
}
