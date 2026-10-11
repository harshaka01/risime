package lk.codegen.risime.data.messaging

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.FileMeta
import lk.codegen.risime.net.ProtocolJson
import java.time.ZoneId
import java.util.Locale

/**
 * v1.34 §33.3 Copy (client only). One message: its content alone. Two or more: one line per message in
 * chat order, `[<date>, <time>] <name>: <content>`, joined with "\n" (no trailing newline). The fixture
 * is `contract/v1/copy_format_cases.json` (CopyFormatTest).
 */
object CopyFormat {
    /** The clipboard label. */
    const val CLIP_LABEL = "RisiMe"
    const val RISI_NAME = "Risi"
    const val UNKNOWN_NAME = "Unknown"

    /** The date/time pattern source: android.icu on the phone, ICU4J in JVM tests. */
    interface Dates {
        fun bestPattern(locale: Locale, skeleton: String): String

        /** Formats [ms] with [pattern] in [locale] with Latin digits (`-u-nu-latn`). */
        fun format(pattern: String, locale: Locale, ms: Long, zone: ZoneId): String
    }

    object IcuDates : Dates {
        override fun bestPattern(locale: Locale, skeleton: String): String =
            android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)

        override fun format(pattern: String, locale: Locale, ms: Long, zone: ZoneId): String {
            val f = android.icu.text.SimpleDateFormat(pattern, android.icu.util.ULocale.forLocale(latn(locale)))
            f.timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
            return f.format(java.util.Date(ms))
        }
    }

    /** The locale with Latin digits (`-u-nu-latn`). */
    fun latn(locale: Locale): Locale = Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "latn").build()

    /** U+202F / U+00A0 → U+0020; the bidi marks U+200E, U+200F and U+061C removed. */
    fun clean(s: String): String = s.replace(' ', ' ').replace(' ', ' ').filterNot { it == '‎' || it == '‏' || it == '؜' }

    /** `[10/10/26, 07:26]` (en-GB, 24 h) or `[10/05/26, 07:26 AM]` (en-US, 12 h). */
    fun stamp(ms: Long, locale: Locale, hour24: Boolean, zone: ZoneId, dates: Dates): String {
        val date = dates.format(dates.bestPattern(locale, "ddMMyy"), locale, ms, zone)
        val time = dates.format(dates.bestPattern(locale, if (hour24) "HHmm" else "hhmma"), locale, ms, zone)
        return clean("[$date, $time]")
    }

    /** Line breaks normalised to "\n". */
    fun normalise(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')

    /** The time a line shows: `server_ts`, else (an unsent own row) its local creation time. */
    fun timeOf(m: MessageEntity): Long =
        m.serverTs?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: m.localTs

    /**
     * §33.3 the content of one row, or null when it isn't copyable (tombstones, system and call lines,
     * view-once, a photo or file without a caption when [single]). [noteText] gives a `note_card`'s
     * §30.6 share text (null: its body).
     */
    fun content(m: MessageEntity, single: Boolean, noteText: (MessageEntity) -> String? = { null }): String? {
        if (!copyable(m)) return null
        return when {
            m.image -> {
                val cap = m.body.takeIf { it.isNotBlank() }?.let(::normalise)
                if (single) cap else if (cap == null) "<Photo>" else "<Photo> $cap"
            }
            m.file -> {
                val cap = m.body.takeIf { it.isNotBlank() }?.let(::normalise)
                val name = FileMeta.decode(m.systemJson)?.let { lk.codegen.risime.data.media.FileEnvelope.displayName(it.name) } ?: "file"
                if (single) cap else if (cap == null) "<File: $name>" else "<File: $name> $cap"
            }
            m.risiCtl -> requestText(m)?.let(::normalise)
            else -> {
                val note = if (isNoteCard(m)) noteText(m) else null
                normalise(note ?: m.body)
            }
        }
    }

    /** Selectable for Copy (§33.2): not a tombstone, system/call line, view-once, or a control other than an own `risi_request`. */
    fun copyable(m: MessageEntity): Boolean = when {
        m.showsAsDeleted || m.system || m.call || m.isViewOnce -> false
        m.risiCtl -> m.outgoing && requestText(m) != null
        m.file -> FileMeta.decode(m.systemJson) != null
        else -> true
    }

    /** An own `risi_request` row's `text` (§25.2: shown as the user's bubble in the Risi chat). */
    fun requestText(m: MessageEntity): String? {
        val o = m.systemJson?.let { runCatching { ProtocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        if ((o["type"] as? JsonPrimitive)?.content != lk.codegen.risime.data.tabs.RisiControl.TYPE_REQUEST) return null
        return (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    fun isNoteCard(m: MessageEntity): Boolean = lk.codegen.risime.data.tabs.RisiMessages.meta(m)?.kind == lk.codegen.risime.net.RisiKinds130.NOTE_CARD

    /**
     * The clipboard text for [selected] (already in chat order). [nameOf] = the name the chat shows for
     * a sender (null: unknown); [ownName] = the user's own display name; Risi messages read "Risi".
     */
    fun format(
        selected: List<MessageEntity>,
        me: String,
        ownName: String,
        nameOf: (String) -> String?,
        locale: Locale,
        hour24: Boolean,
        zone: ZoneId,
        dates: Dates,
        noteText: (MessageEntity) -> String? = { null },
    ): String? {
        val rows = selected.filter(::copyable)
        if (rows.isEmpty()) return null
        if (rows.size == 1) return content(rows.single(), single = true, noteText)
        return rows.mapNotNull { m ->
            val c = content(m, single = false, noteText) ?: return@mapNotNull null
            val name = when {
                m.from.equals(me, true) -> ownName
                lk.codegen.risime.data.tabs.RisiMessages.meta(m) != null -> RISI_NAME
                else -> nameOf(m.from)?.takeIf { it.isNotBlank() && !it.startsWith("+") } ?: UNKNOWN_NAME
            }
            "${stamp(timeOf(m), locale, hour24, zone, dates)} $name: $c"
        }.joinToString("\n")
    }
}
