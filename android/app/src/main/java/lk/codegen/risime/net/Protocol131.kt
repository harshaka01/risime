package lk.codegen.risime.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * v1.31 §31 Google Calendar link (decision 074). The Google token stays on this phone (in memory only);
 * the server learns only the link's state, the Google device and two counts. Additive only: an app
 * without `google_calendar` sees v1.30 exactly. Every field optional where the contract allows it;
 * unknown fields and kinds are ignored.
 */

/** Capability a v1.31 client advertises (only with `risi_events`) while `/auth/config` says `google_calendar: on`. */
const val CAPABILITY_GOOGLE_CALENDAR = "google_calendar"

/** §31.3 the stored event (cursor-ordered; delivered to the user's `google_calendar` devices only). */
const val KIND_GOOGLE_CALENDAR_LINK = "google_calendar_link"

/** §31.3 `Link.state`. `paused` is computed by the server (a link exists but the Calendar skill is off). */
object GcalLinkStates {
    const val NOT_CONNECTED = "not_connected"
    const val CONNECTED = "connected"
    const val REAUTH_NEEDED = "reauth_needed"
    const val PAUSED = "paused"
}

/** §31.3 `google_calendar_link.data.reason`. */
object GcalLinkReasons {
    const val CONNECTED = "connected"
    const val UPDATED = "updated"
    const val DISCONNECTED = "disconnected"
    const val REPLACED = "replaced"
}

/** §31.4 `google_api` source reasons (the wire words; §31.5 gives their English). */
object GcalReadReasons {
    const val NOT_CONNECTED = "not_connected"
    const val REAUTH_NEEDED = "reauth_needed"
    const val NO_ANSWER = "no_answer"
    const val PAUSED = "paused"
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val API_ERROR = "api_error"
    const val NO_CALENDARS = "no_calendars"

    /** §31.5 the words after "Not checked: Google Calendar (…)". */
    fun words(reason: String?): String = when (reason) {
        NOT_CONNECTED -> "not connected"
        REAUTH_NEEDED -> "needs reconnecting"
        NO_ANSWER -> "phone didn't answer"
        PAUSED -> "Calendar skill is off"
        NETWORK -> "no network on the phone"
        TIMEOUT -> "Google didn't answer in time"
        API_ERROR -> "Google didn't answer"
        NO_CALENDARS -> "no calendars picked"
        else -> "not checked"
    }
}

/** §31 `risi.kind`. */
object RisiKinds131 {
    /** In the user's Risi chat, at most one per 24 h, when the link went to `reauth_needed`. */
    const val GOOGLE_RECONNECT = "google_reconnect"
    const val REASON_REAUTH_NEEDED = "reauth_needed"
    const val BUTTON_RECONNECT = "reconnect"
    val ALL = setOf(GOOGLE_RECONNECT)
}

object GcalErrors {
    /** `409`: `connect:false` from a device that does not hold the link (or there is no link). */
    const val NOT_GOOGLE_DEVICE = "not_google_device"
}

/** §31.3 `Link`. */
@Serializable
data class GoogleLink(
    val state: String = GcalLinkStates.NOT_CONNECTED,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("read_calendars") val readCalendars: Int = 0,
    @SerialName("write_calendar") val writeCalendar: Boolean = false,
    val mirror: Boolean = false,
    @SerialName("connected_at") val connectedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val linked: Boolean get() = state != GcalLinkStates.NOT_CONNECTED

    companion object {
        val NONE = GoogleLink()
    }
}

/** `GET`/`PUT /risi/calendar/google` -> `{"google": Link}`. */
@Serializable
data class GoogleLinkReply(val google: GoogleLink)

/** `PUT /risi/calendar/google` body (§31.3). `state` is `connected` or `reauth_needed`. */
@Serializable
data class GoogleLinkPut(
    val connect: Boolean,
    val state: String,
    @SerialName("read_calendars") val readCalendars: Int,
    @SerialName("write_calendar") val writeCalendar: Boolean,
    val mirror: Boolean,
)

/** §31.3 the `google_calendar_link` event's data. */
@Serializable
data class GoogleCalendarLinkData(
    val state: String = GcalLinkStates.NOT_CONNECTED,
    @SerialName("device_id") val deviceId: String? = null,
    val reason: String = GcalLinkReasons.UPDATED,
    @SerialName("remove_copies") val removeCopies: Boolean = false,
    @SerialName("server_ts") val serverTs: String? = null,
)

fun Event.googleCalendarLink(): GoogleCalendarLinkData? =
    if (kind == KIND_GOOGLE_CALENDAR_LINK) runCatching { ProtocolJson.decodeFromJsonElement(GoogleCalendarLinkData.serializer(), data) }.getOrNull() else null

/** §31.4 the `google_api` result entry's calendar: a random local `ref` and a count, never a name or id. */
@Serializable
data class GoogleSourceCalendar(val ref: String, val events: Int)

/** §31.4 `{"source": "google_api", "calendars": [{ref, events}], "read_ok", "reason"}`. */
@Serializable
data class GoogleSourceReport(
    val source: String = "google_api",
    val calendars: List<GoogleSourceCalendar> = emptyList(),
    @SerialName("read_ok") val readOk: Boolean,
    val reason: String? = null,
)

/** The `calendar_check` result when `args.sources` is present (§31.4): exactly the sources asked for. */
@Serializable
data class CalendarCheckSourcesResult(
    val blocks: List<CalendarBlock>,
    val sources: List<JsonObject>,
    @SerialName("connected_sources") val connectedSources: List<String>,
)
