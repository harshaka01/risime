package lk.codegen.risime.ui.pdf

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.data.media.ImageThumb
import lk.codegen.risime.data.notes.RisiNotes
import lk.codegen.risime.data.pdf.PdfContent
import lk.codegen.risime.data.pdf.PdfDoc
import lk.codegen.risime.data.pdf.PdfFonts
import lk.codegen.risime.data.pdf.PdfNames
import lk.codegen.risime.data.pdf.PdfTooLongException
import lk.codegen.risime.data.pdf.PdfWriter
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.ExportPdfErrors
import lk.codegen.risime.net.PdfSources
import lk.codegen.risime.net.RisiNote
import java.io.File
import java.time.ZoneId
import java.util.Locale

/**
 * v1.34 §33.14 phone-only PDF export: the source is always the phone's own data (the stored message, the
 * note as the note screen shows it, the synced Risi Calendar); the PDF is written only to
 * `noBackupFilesDir/pdf/<uuid>.pdf` and deleted when its action completes, after 1 hour, and on the next
 * start. It never reaches the server, the model, the learning log or a push.
 */
object PdfExport {
    const val DIR = "pdf"
    const val TTL_MS = 60 * 60_000L
    const val TOO_LONG = "Too long to export; choose a shorter period"
    const val NOT_ON_PHONE = "This isn't on this phone"
    const val FAILED = "The phone couldn't make the PDF"

    /** Exportable Risi kinds (§33.14). */
    val KINDS = setOf("summary", "report", "answer", "digest", "discussion_summary", "note_card")

    class Made(val file: File, val pages: Int, val title: String, val fileName: String, val sourceType: String) {
        val meta: FileMeta get() = FileMeta(fileName, lk.codegen.risime.data.media.FileEnvelope.MIME_PDF, pages)
    }

    sealed interface Outcome {
        class Ok(val made: Made) : Outcome

        /** [code]: an §33.15 `export_pdf` error code; [text] for the user. */
        class Failed(val code: String, val text: String) : Outcome
    }

    fun cleanup(context: Context, olderThanMs: Long? = null) {
        val now = System.currentTimeMillis()
        File(context.noBackupFilesDir, DIR).listFiles()?.forEach { f -> if (olderThanMs == null || now - f.lastModified() > olderThanMs) f.delete() }
    }

    private suspend fun names(c: AppContainer, me: String, myName: String): (String) -> String {
        val out = HashMap<String, String>()
        runCatching { c.db.groups().observeAllMembers().first() }.getOrDefault(emptyList()).forEach { if (it.displayName.isNotBlank()) out[it.userId.lowercase()] = it.displayName }
        runCatching { c.db.contacts().all().first() }.getOrDefault(emptyList()).forEach { ct -> ct.userId?.let { out[it.lowercase()] = ct.displayName } }
        return { id -> if (id.equals(me, true)) myName else out[id.lowercase()] ?: "Someone" }
    }

    /** Builds the document for [source] from the phone's own data (null: not on this phone). */
    suspend fun document(c: AppContainer, source: JsonObject, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): PdfDoc? {
        val me = c.sessionStore.current()?.user?.id ?: return null
        val myName = c.sessionStore.current()?.user?.displayName ?: "You"
        val nameOf = names(c, me, myName)
        fun str(k: String) = (source[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (PdfSources.type(source)) {
            PdfSources.NOTE -> noteDoc(c, str("note_id") ?: return null, null, me, myName, nameOf, now, zone, locale)
            PdfSources.MESSAGE -> {
                val conv = str("conversation_id") ?: return null
                val row = c.db.messages().inConversation(conv, str("message_id") ?: return null) ?: return null
                val r = RisiMessages.meta(row) ?: return null
                if (r.kind !in KINDS) return null
                if (r.kind == lk.codegen.risime.net.RisiKinds130.NOTE_CARD) {
                    val note = RisiNote.parse(row.systemJson) ?: return null
                    return noteDoc(c, note.noteId, note, me, myName, nameOf, now, zone, locale)
                }
                val risiChat = lk.codegen.risime.data.tabs.isRisiChat(conv, c.chatTabs.rows.value)
                val people = if (risiChat) emptyList() else c.db.groups().members(conv).filter { it.current && it.kind != lk.codegen.risime.net.GroupMember.KIND_AGENT }.map { nameOf(it.userId) }
                val rows = c.db.messages().conversation(conv).first()
                val sources = r.sources.mapNotNull { s ->
                    when {
                        s.type == "message" && s.messageId != null -> rows.firstOrNull { it.messageId.equals(s.messageId, true) }?.let { m ->
                            "${nameOf(m.from)} · ${lk.codegen.risime.ui.chat.infoStamp(lk.codegen.risime.data.messaging.CopyFormat.timeOf(m))}"
                        }
                        s.type == "calendar" && s.start != null -> listOfNotNull(s.title, s.start.take(16).replace('T', ' ')).joinToString(" · ")
                        else -> null
                    }
                }
                PdfContent.message(
                    r, row.body, lk.codegen.risime.data.messaging.CopyFormat.timeOf(row), sources, people,
                    r.madeBy?.let { lk.codegen.risime.data.tabs.MadeByLabels.label(it.model, it.provider) }, now, zone, locale,
                )
            }
            PdfSources.CALENDAR -> {
                val from = str("from")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
                val to = str("to")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
                if (to <= from || to - from > 31L * 86_400_000L) return null
                val events = c.risiCalendar.eventsNow().filter { !it.cancelled }.mapNotNull { e ->
                    val s = lk.codegen.risime.data.calendar.RisiEventRows.ms(e.start) ?: return@mapNotNull null
                    val en = lk.codegen.risime.data.calendar.RisiEventRows.ms(e.end) ?: return@mapNotNull null
                    PdfContent.CalEvent(e.title.ifBlank { "Event" }, s, en, e.allDay, e.participants.filterNot { it.userId.equals(me, true) }.map { nameOf(it.userId) })
                }
                PdfContent.calendar(from, to, events, now, zone, locale)
            }
            else -> null
        }
    }

    private suspend fun noteDoc(
        c: AppContainer, noteId: String, card: RisiNote?, me: String, myName: String, nameOf: (String) -> String,
        now: Long, zone: ZoneId, locale: Locale,
    ): PdfDoc? {
        val conv = c.risiChatConversation()
        val rows = conv?.let { c.db.messages().conversation(it).first() }.orEmpty()
        val local = card ?: RisiNotes.localNotes(rows).firstOrNull { it.noteId.equals(noteId, true) }
        val remote = if (c.risiNotesOn()) (runCatching { c.risiNotesRest.note(noteId) }.getOrNull() as? ApiResult.Ok)?.value?.note else null
        val n = remote ?: local ?: return null
        val items = RisiNotes.liveItems(n.items, n.noteId, rows, if (remote != null) RisiNotes.reflectedIds(rows) else null)
        val title = RisiNotes.title(me, myName, n.withUsers, nameOf, n.topic, n.endedAt, zone, locale)
        return PdfContent.note(
            title, n, items, { RisiNotes.ownerDue(it, me, nameOf, zone) }, { RisiNotes.eventLine(it, zone) },
            n.withUsers.map(nameOf), n.madeBy?.let { lk.codegen.risime.data.tabs.MadeByLabels.label(it.model, it.provider) }, now, zone, locale,
        )
    }

    /** Generates the PDF for [source] (§33.14): fonts embedded, ActualText, Info without Author. */
    suspend fun make(context: Context, c: AppContainer, source: JsonObject): Outcome = withContext(Dispatchers.Default) {
        val doc = runCatching { document(c, source) }.getOrNull() ?: return@withContext Outcome.Failed(ExportPdfErrors.SOURCE_UNAVAILABLE, NOT_ON_PHONE)
        val now = java.time.ZonedDateTime.now()
        val out = try {
            PdfWriter(PdfFonts.load(context.assets)).render(doc, "RisiMe ${lk.codegen.risime.BuildConfig.VERSION_NAME}", now)
        } catch (e: PdfTooLongException) {
            return@withContext Outcome.Failed(ExportPdfErrors.TOO_LARGE, TOO_LONG)
        } catch (e: Exception) {
            return@withContext Outcome.Failed(ExportPdfErrors.PDF_FAILED, FAILED)
        }
        val dir = File(context.noBackupFilesDir, DIR).apply { mkdirs() }
        val f = File(dir, java.util.UUID.randomUUID().toString() + ".pdf")
        runCatching { f.writeBytes(out.bytes) }.onFailure { return@withContext Outcome.Failed(ExportPdfErrors.PDF_FAILED, FAILED) }
        val type = PdfSources.type(source) ?: "unknown"
        runCatching { c.behaviour.pdfExported(out.pages, type) }
        Outcome.Ok(Made(f, out.pages, doc.title, PdfNames.fileName(doc.title, now.toLocalDate()), type))
    }

    /** §33.13 the sender renders a thumbnail only for files it made: page 1 of its own PDF (JPEG ≤ 128 px, ≤ 4096 bytes). */
    fun thumbnail(file: File): ImageThumb? = runCatching {
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                r.openPage(0).use { p ->
                    val scale = 128f / maxOf(p.width, p.height)
                    val w = (p.width * scale).toInt().coerceAtLeast(1)
                    val h = (p.height * scale).toInt().coerceAtLeast(1)
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var q = 60
                    var bytes: ByteArray
                    do {
                        val o = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, o)
                        bytes = o.toByteArray()
                        q -= 10
                    } while (bytes.size > 4096 && q >= 20)
                    bmp.recycle()
                    bytes.takeIf { it.size <= 4096 }?.let { ImageThumb("image/jpeg", w, h, it) }
                }
            }
        }
    }.getOrNull()

    /** Page 1 for the sheet's preview (our own PDF: never a received file). */
    fun preview(file: File, widthPx: Int): android.graphics.Bitmap? = runCatching {
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                r.openPage(0).use { p ->
                    val h = (widthPx.toFloat() * p.height / p.width).toInt()
                    android.graphics.Bitmap.createBitmap(widthPx, h, android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(android.graphics.Color.WHITE)
                        p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    /**
     * §33.14 "Send to chat": one encrypt and upload **per target** (a fresh key each), a `file` outbox row
     * each. Returns the rows (one per target that could take it).
     */
    suspend fun sendTo(c: AppContainer, made: Made, targets: List<String>): List<lk.codegen.risime.data.db.MessageEntity> {
        val thumb = withContext(Dispatchers.Default) { thumbnail(made.file) }
        val rows = targets.mapNotNull { conv -> c.sendFile(conv, made.file, made.meta, thumb) }
        c.scope.launch { c.engine.flushOutbox() }
        return rows
    }

}
