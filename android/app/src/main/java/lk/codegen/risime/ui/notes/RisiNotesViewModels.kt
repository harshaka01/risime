package lk.codegen.risime.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.calendar.CalendarResult
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.notes.NoteNames
import lk.codegen.risime.data.notes.NoteScreenModel
import lk.codegen.risime.data.notes.NoteShareModel
import lk.codegen.risime.data.notes.NotesListModel
import lk.codegen.risime.data.notes.RisiNotes
import lk.codegen.risime.data.tabs.ConversationDirectory
import lk.codegen.risime.data.tabs.RisiUiBus
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.RisiNote

/** What the Notes screens read from the app: names, the Risi chat's rows, the chats to share into. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesEnv(private val c: AppContainer, val me: String, scope: kotlinx.coroutines.CoroutineScope) {
    private val myName = MutableStateFlow("You").also { f ->
        scope.launch { runCatching { c.sessionStore.current()?.user?.displayName }.getOrNull()?.takeIf { it.isNotBlank() }?.let { f.value = it } }
    }

    /** Everyone this phone knows by name (contacts, members of any chat). */
    private val people: StateFlow<Map<String, String>> = combine(c.db.contacts().all(), c.db.groups().observeAllMembers()) { ct, ms ->
        val out = HashMap<String, String>()
        ms.forEach { m -> if (m.displayName.isNotBlank()) out[m.userId.lowercase()] = m.displayName }
        ct.forEach { x -> x.userId?.let { out[it.lowercase()] = x.displayName } }
        out as Map<String, String>
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    fun nameOf(id: String): String = if (id.equals(me, true)) "You" else people.value[id.lowercase()] ?: "Someone"

    val names: NoteNames get() = NoteNames(me, myName.value, ::nameOf)

    /** The Risi chat's rows (empty while there is none). */
    val risiRows: StateFlow<List<MessageEntity>> = c.chatTabs.rows.map { rows -> rows?.values?.firstOrNull { it.risi }?.conversationId }
        .flatMapLatest { conv -> if (conv == null) flowOf(emptyList()) else c.db.messages().conversation(conv) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The chats a note can be shared into, by name (Risi chats excluded). */
    val chats: StateFlow<List<Pair<String, String>>> = combine(c.db.groups().all(), c.db.contacts().all(), c.chatTabs.rows) { g, ct, rows ->
        ConversationDirectory.names(me, g, ct, rows).entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** A Notes device: the REST is used (else the phone's own cards only). */
    val rest get() = if (c.risiNotesOn()) c.risiNotesRest else null

    suspend fun rows(): List<MessageEntity> = risiRows.first()

    /** The note (REST when available, else this phone's card) as share text with its live items. */
    suspend fun shareText(noteId: String): String? {
        val rows = rows()
        val local = RisiNotes.localNotes(rows).firstOrNull { it.noteId.equals(noteId, true) }
        val remote = (runCatching { rest?.note(noteId) }.getOrNull() as? ApiResult.Ok)?.value?.note
        val n = remote ?: local ?: return null
        val items = RisiNotes.liveItems(n.items, n.noteId, rows, if (remote != null) RisiNotes.reflectedIds(rows) else null)
        return RisiNotes.shareText(names.title(n), n, items, me, ::nameOf)
    }

    /** A note's title for My promises: this phone's card, else the server's note (null: unknown). */
    suspend fun noteTitle(noteId: String): String? {
        val local = RisiNotes.localNotes(rows()).firstOrNull { it.noteId.equals(noteId, true) }
        val n = local ?: (runCatching { rest?.note(noteId) }.getOrNull() as? ApiResult.Ok)?.value?.note ?: return null
        return names.title(n)
    }

    fun share(scope: kotlinx.coroutines.CoroutineScope) = NoteShareModel(scope, render = { shareText(it) }, send = { conv, text -> c.shareText(conv, text) })
}

class RisiNotesListViewModel(c: AppContainer, me: String) : ViewModel() {
    val env = NotesEnv(c, me, viewModelScope)
    val model = NotesListModel(
        c.risiNotesRest, viewModelScope,
        local = { val rows = env.rows(); RisiNotes.localNotes(rows).map { it to RisiNotes.summaryOf(it, rows) } },
        nameOf = env::nameOf,
    ).also { it.load() }
    val share = env.share(viewModelScope)
}

class RisiNoteViewModel(private val c: AppContainer, me: String, private val noteId: String) : ViewModel() {
    val env = NotesEnv(c, me, viewModelScope)
    val model = NoteScreenModel(
        noteId, env.rest, viewModelScope,
        act = { id, action, text, due, allDay -> c.sendItemAction(id, action, text, due, allDay) },
        accept = { eventId -> c.risiEventsOn() && c.risiCalendarCards.respond(eventId, "accept", null, null, null) is CalendarResult.Ok },
    )
    val share = env.share(viewModelScope)

    /** Events open in the Calendar tab only on a calendar device. */
    val calendarOn: Boolean get() = c.risiEventsOn()

    /** v1.34 §33.14 ⋮ → "Export PDF" (made on this phone from the note as this screen shows it). */
    fun exportPdf() = c.requestPdf(lk.codegen.risime.net.PdfSources.note(noteId))

    init {
        viewModelScope.launch {
            val rows = env.rows()
            model.load(RisiNotes.localNotes(rows).firstOrNull { it.noteId.equals(noteId, true) }, rows)
        }
    }

    fun openEvent(eventId: String) = c.risiCalendarCards.open(eventId)

    fun openChat(n: RisiNote) {
        val conv = n.conversationId ?: return
        c.risiUi.openChat(conv, n.startedAt?.let { RisiUiBus.Focus(at = it) })
    }
}

/** Route: the Notes list (Risi chat ⋮ → Notes; Settings → Risi Notes). */
@Composable
fun NotesListRoute(vm: RisiNotesListViewModel, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val s by vm.model.state.collectAsStateWithLifecycle()
    val share by vm.share.state.collectAsStateWithLifecycle()
    val chats by vm.env.chats.collectAsStateWithLifecycle()
    // The search goes to the server a moment after typing stops.
    var typed by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    LaunchedEffect(typed) {
        val q = typed ?: return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        vm.model.search(q)
    }
    Box(Modifier.fillMaxSize()) {
        NotesListScreen(
            s.copy(query = typed ?: s.query), titleOf = { vm.env.names.title(it) },
            onQuery = { typed = it }, onOpen = onOpen, onShare = vm.share::start, onDelete = vm.model::delete, onMore = vm.model::loadMore,
            onAskDeleteAll = vm.model::askDeleteAll, onConfirmDeleteAll = vm.model::confirmDeleteAll, onCancelDeleteAll = vm.model::cancelDeleteAll,
            onBack = onBack,
        )
        Box(Modifier.align(Alignment.BottomCenter)) { ShareNotice(share, vm.share::clearNotice) }
    }
    ShareChatPicker(share, chats, vm.share::pick, vm.share::cancel)
}

/** Route: one note. */
@Composable
fun NoteRoute(vm: RisiNoteViewModel, onBack: () -> Unit) {
    val s by vm.model.state.collectAsStateWithLifecycle()
    val rows by vm.env.risiRows.collectAsStateWithLifecycle()
    val share by vm.share.state.collectAsStateWithLifecycle()
    val chats by vm.env.chats.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(10_000); now = System.currentTimeMillis() }
    }
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    val view = remember(s, rows, now) { vm.model.view(rows, vm.env.me, now) }
    val names = vm.env.names
    Box(Modifier.fillMaxSize()) {
        NoteScreen(
            view = view, title = view?.note?.let { names.title(it) }, loading = s.loading, error = s.error, notice = s.notice,
            me = vm.env.me, nameOf = vm.env::nameOf, canDelete = vm.env.rest != null, canAnswerEvents = vm.calendarOn,
            actions = NoteItemActions(tick = vm.model::tick, send = { id, a, t, d, all -> vm.model.send(id, a, t, d, all) }),
            onAcceptEvent = { vm.model.acceptEvent(it, rows) },
            onOpenEvent = if (vm.calendarOn) vm::openEvent else null,
            onOpenChat = vm::openChat,
            onShare = {
                val v = view ?: return@NoteScreen
                vm.share.startWith(RisiNotes.shareText(names.title(v.note), v.note, v.items, vm.env.me, vm.env::nameOf))
            },
            onDelete = vm.model::delete,
            onExportPdf = vm::exportPdf,
            onBack = onBack,
        )
        Box(Modifier.align(Alignment.BottomCenter)) { ShareNotice(share, vm.share::clearNotice) }
    }
    ShareChatPicker(share, chats, vm.share::pick, vm.share::cancel)
}
