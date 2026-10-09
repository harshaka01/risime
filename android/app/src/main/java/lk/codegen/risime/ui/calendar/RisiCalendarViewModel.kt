package lk.codegen.risime.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.calendar.CalendarResult
import lk.codegen.risime.data.calendar.RisiCalendar
import lk.codegen.risime.data.calendar.RisiEventRows
import lk.codegen.risime.net.RisiCalendarBodies
import lk.codegen.risime.net.RisiCalendarSettings
import lk.codegen.risime.net.RisiEvent

/** What the Calendar tab asks of the app (the view model; a fake in tests). */
interface RisiCalendarActions {
    /** The tab opened: sync now (the cursor feed, or a list) and read the settings. */
    fun opened() {}

    /** A view moved outside the listed window: list that range too. */
    fun loadRange(fromMs: Long, toMs: Long) {}

    fun create(title: String, startMs: Long, endMs: Long, allDay: Boolean) {}

    /** The owner's edit: the [edit] object of the editor (title, start, end, all_day). */
    fun edit(event: RisiEvent, edit: JsonObject) {}

    fun delete(event: RisiEvent) {}

    fun respond(event: RisiEvent, response: String) {}

    fun setReminder(event: RisiEvent, minutes: Int?) {}

    fun setShowDeclined(on: Boolean) {}

    fun saveSettings(s: RisiCalendarSettings) {}

    fun openChat(conversationId: String) {}
}

object RisiCalendarEdits {
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** The editor's object as a versioned PATCH of only what changed (owner fields). Null: nothing changed. */
    fun patchOf(e: RisiEvent, edit: JsonObject): JsonObject? {
        val title = str(edit, "title")?.trim()?.takeIf { it.isNotEmpty() && it != e.title }
        val start = str(edit, "start")
        val end = str(edit, "end")
        val allDay = (edit["all_day"] as? JsonPrimitive)?.booleanOrNull
        val timeChanged = (start != null && !RisiCalendar.sameInstant(start, e.start)) || (end != null && !RisiCalendar.sameInstant(end, e.end)) || (allDay != null && allDay != e.allDay)
        if (title == null && !timeChanged) return null
        return RisiCalendarBodies.patch(
            e.version, title = title,
            start = start.takeIf { timeChanged }, end = end.takeIf { timeChanged }, allDay = allDay.takeIf { timeChanged },
            tz = if (timeChanged) java.time.ZoneId.systemDefault().id else null,
        )
    }

    fun startMs(edit: JsonObject): Long? = RisiEventRows.ms(str(edit, "start"))

    fun endMs(edit: JsonObject): Long? = RisiEventRows.ms(str(edit, "end"))

    fun title(edit: JsonObject): String? = str(edit, "title")

    fun allDay(edit: JsonObject): Boolean = (edit["all_day"] as? JsonPrimitive)?.booleanOrNull ?: false
}

/** §29 the Calendar tab next to Chats / Calls / Requests (only on a `risi_events` device). */
class RisiCalendarViewModel(private val c: AppContainer, val meId: String) : ViewModel(), RisiCalendarActions {
    val on: StateFlow<Boolean> = c.risiEventsActive

    val events: StateFlow<List<RisiEvent>> = c.risiCalendar.events.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val showDeclined: StateFlow<Boolean> = c.risiCalendar.showDeclined

    val settings: StateFlow<RisiCalendarSettings> = c.risiCalendar.settings

    val focus: StateFlow<String?> = c.risiCalendar.focus

    fun takeFocus(): String? = c.risiCalendar.takeFocus()

    /** A short notice after a write (error text); null when fine. */
    val notice = MutableStateFlow<String?>(null)

    /** Names from the phone's own data (friends, members of any chat). */
    val names: StateFlow<Map<String, String>> = combine(c.db.contacts().all(), c.db.groups().observeAllMembers()) { ct, ms ->
        val out = HashMap<String, String>()
        ms.forEach { m -> if (m.displayName.isNotBlank()) out[m.userId.lowercase()] = m.displayName }
        ct.forEach { x -> x.userId?.let { out[it.lowercase()] = x.displayName } }
        out as Map<String, String>
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val conversations: StateFlow<Map<String, String>> = combine(c.db.groups().all(), c.db.contacts().all(), c.chatTabs.rows) { g, ct, rows ->
        lk.codegen.risime.data.tabs.ConversationDirectory.names(meId, g, ct, rows)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private fun after(r: CalendarResult) {
        notice.value = when (r) {
            is CalendarResult.Ok -> null
            is CalendarResult.Conflict -> RisiCalendar.errorText(if (r.current == null) "not_found" else "version_conflict")
            is CalendarResult.Failed -> RisiCalendar.errorText(r.code)
        }
    }

    private fun write(block: suspend () -> CalendarResult) {
        viewModelScope.launch { after(runCatching { block() }.getOrElse { CalendarResult.Failed("network") }) }
    }

    override fun opened() {
        c.syncRisiCalendar()
        viewModelScope.launch { runCatching { c.risiCalendar.loadSettings() } }
    }

    override fun loadRange(fromMs: Long, toMs: Long) {
        viewModelScope.launch { runCatching { c.risiCalendar.loadRange(fromMs, toMs) } }
    }

    override fun create(title: String, startMs: Long, endMs: Long, allDay: Boolean) = write { c.risiCalendar.create(title, startMs, endMs, allDay) }

    override fun edit(event: RisiEvent, edit: JsonObject) {
        val body = RisiCalendarEdits.patchOf(event, edit) ?: return
        write { c.risiCalendar.patch(event.eventId, body) }
    }

    override fun delete(event: RisiEvent) = write { c.risiCalendar.delete(event) }

    override fun respond(event: RisiEvent, response: String) = write { c.risiCalendar.respond(event.eventId, response, event.version, event.start, event.end) }

    override fun setReminder(event: RisiEvent, minutes: Int?) = write { c.risiCalendar.setReminder(event, minutes) }

    override fun setShowDeclined(on: Boolean) = c.risiCalendar.setShowDeclined(on)

    override fun saveSettings(s: RisiCalendarSettings) {
        viewModelScope.launch { if (!c.risiCalendar.saveSettings(s)) notice.value = "Couldn't save the settings." }
    }

    override fun openChat(conversationId: String) = c.risiUi.openChat(conversationId, null)
}
