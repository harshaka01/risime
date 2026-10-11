package lk.codegen.risime.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses every file in contract/v1/examples (copied into test resources by copyContractExamples). */
class ContractExamplesTest {

    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText()
            ?: error("missing contract example $name")

    /** v1.29 §29 Risi Calendar (net/Protocol129.kt): ready for the examples the build adds (proposal 2026-10-09-risi-calendar-notes). */
    private val calendarV129: Map<String, (String) -> Any> by lazy {
        fun card(s: String, kind: String) = ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == kind) }.let { m ->
            if (kind in RisiKinds129.ALL) RisiCalendarCard.parse(ProtocolJson.parseToJsonElement(s).jsonObject["risi"].toString())!!.also { require(it.kind == kind) } else m
        }
        fun action(s: String, a: String) = ProtocolJson.parseToJsonElement(s).jsonObject.also { require(lk.codegen.risime.data.tabs.RisiControl.valid(it) && it["action"]!!.jsonPrimitive.content == a) }
        mapOf(
            "auth_config_v129.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s).also { require(it.risiEventsOn) } },
            "device_put_risi_events.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s).also { require(CAPABILITY_RISI_EVENTS in it.mls!!.capabilities!!) } },
            "risi_calendar_events_reply.json" to { s -> ProtocolJson.decodeFromString<RisiCalendarEventsReply>(s).also { require(it.events.isNotEmpty() && it.cursor != null) } },
            "risi_calendar_changes_reply.json" to { s -> ProtocolJson.decodeFromString<RisiCalendarChangesReply>(s).also { require(it.changes.any { c -> c.removed } && it.changes.any { c -> c.event != null }) } },
            "risi_calendar_event_create.json" to { s -> ProtocolJson.parseToJsonElement(s).jsonObject.also { require("client_event_id" in it && "start" in it && "with" in it) } },
            "risi_calendar_event_create_reply.json" to { s -> ProtocolJson.decodeFromString<RisiEventReply>(s) },
            "risi_calendar_event_patch.json" to { s -> ProtocolJson.parseToJsonElement(s).jsonObject.also { require("version" in it) } },
            "risi_calendar_respond_accept.json" to { s -> ProtocolJson.parseToJsonElement(s).jsonObject.also { require(it["response"]!!.jsonPrimitive.content == "accept") } },
            "risi_calendar_respond_suggest.json" to { s -> ProtocolJson.parseToJsonElement(s).jsonObject.also { require(it["response"]!!.jsonPrimitive.content == "suggest" && it["suggest"] is JsonObject) } },
            "risi_calendar_suggestion_resolve.json" to { s -> ProtocolJson.parseToJsonElement(s).jsonObject.also { require(it["action"]!!.jsonPrimitive.content in setOf("use", "keep")) } },
            "risi_calendar_settings.json" to { s ->
                val o = ProtocolJson.parseToJsonElement(s).jsonObject
                if ("settings" in o) ProtocolJson.decodeFromString<RisiCalendarSettingsReply>(s) else ProtocolJson.decodeFromString<RisiCalendarSettings>(s)
            },
            "event_risi_calendar_changed.json" to { s -> ProtocolJson.decodeFromString<Event>(s).risiCalendarChanged()!!.also { require(it.cursor != null) } },
            "envelope_risi_confirm_risi_calendar_add.json" to { s -> card(s, "confirm").also { require((it as RisiMeta).tool == RisiKinds129.TOOL_RISI_CALENDAR_ADD) } },
            "envelope_risi_action_confirm_write_edit_risi_calendar.json" to { s -> action(s, "confirm_write") },
            "envelope_risi_event_card_added.json" to { s -> card(s, RisiKinds129.EVENT_CARD) },
            "envelope_risi_event_card_official.json" to { s -> card(s, RisiKinds129.EVENT_CARD) },
            "envelope_risi_calendar_invite.json" to { s -> card(s, RisiKinds129.CALENDAR_INVITE) },
            "envelope_risi_event_update.json" to { s -> card(s, RisiKinds129.EVENT_UPDATE) },
            "envelope_risi_calendar_suggestion.json" to { s -> card(s, RisiKinds129.CALENDAR_SUGGESTION) },
            "envelope_risi_calendar_reminder.json" to { s -> card(s, RisiKinds129.CALENDAR_REMINDER) },
            "envelope_risi_action_event_accept.json" to { s -> action(s, RisiActions129.EVENT_ACCEPT) },
            "envelope_risi_action_event_suggest.json" to { s -> action(s, RisiActions129.EVENT_SUGGEST) },
            "envelope_risi_answer_calendar_sources_risi.json" to { s -> card(s, "answer") },
            "envelope_risi_digest_personal_v129.json" to { s -> card(s, "digest") },
            "error_version_conflict.json" to { s -> apiError(s, "version_conflict") },
            "error_cursor_expired.json" to { s -> apiError(s, "cursor_expired") },
            "error_not_invitable.json" to { s -> apiError(s, "not_invitable") },
        )
    }

    /** v1.30 §30 Risi Notes (net/Protocol130.kt): the server's real examples (PROTOCOL.md v1.30 §30). */
    private val notesV130: Map<String, (String) -> Any> by lazy {
        fun risi(s: String) = ProtocolJson.parseToJsonElement(s).jsonObject["risi"].toString()
        mapOf(
            "device_put_risi_notes.json" to { s ->
                ProtocolJson.decodeFromString<DevicePut>(s).also { require(CAPABILITY_RISI_NOTES in it.mls!!.capabilities!! && CAPABILITY_RISI_EVENTS in it.mls!!.capabilities!!) }
            },
            "envelope_risi_note_card.json" to { s ->
                val m = ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!
                require(m.kind == RisiKinds130.NOTE_CARD && m.summaryKey != null)
                RisiNote.parse(risi(s))!!.also { require(it.noteId == m.noteId && it.topic.isNotEmpty() && it.keyPoints.isNotEmpty() && it.withUsers.isNotEmpty()) }
            },
            "envelope_risi_notes_saved.json" to { s ->
                ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds130.NOTES_SAVED && it.noteId != null && it.itemsCount != null && it.notify.isEmpty()) }
            },
            "risi_notes_reply.json" to { s -> ProtocolJson.decodeFromString<RisiNotesReply>(s).also { require(it.notes.isNotEmpty()) } },
            "risi_note_reply.json" to { s -> ProtocolJson.decodeFromString<RisiNoteReply>(s).also { require(it.note.noteId.isNotEmpty()) } },
            "envelope_risi_action_item_reopen.json" to { s ->
                ProtocolJson.parseToJsonElement(s).jsonObject.also { require(lk.codegen.risime.data.tabs.RisiControl.valid(it) && it["action"]!!.jsonPrimitive.content == RisiActions130.ITEM_REOPEN) }
            },
            "risi_commitments_reply_v130.json" to { s -> ProtocolJson.decodeFromString<RisiCommitmentsReply>(s).also { require(it.commitments.any { c -> c.noteId != null }) } },
        )
    }

    /** v1.31 §31 Google Calendar link (net/Protocol131.kt): the server's real examples (PROTOCOL.md §31.12). */
    private val google131: Map<String, (String) -> Any> by lazy {
        fun env(s: String) = ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!
        fun toolResult(s: String) = ProtocolJson.decodeFromString<RisiToolResult>(s)
        mapOf(
            "auth_config_v131.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s).also { require(it.googleCalendarOn && it.risiEventsOn) } },
            "device_put_google_calendar.json" to { s ->
                ProtocolJson.decodeFromString<DevicePut>(s).also { require(CAPABILITY_GOOGLE_CALENDAR in it.mls!!.capabilities!! && CAPABILITY_RISI_EVENTS in it.mls!!.capabilities!!) }
            },
            "risi_google_link_put.json" to { s ->
                ProtocolJson.decodeFromString<GoogleLinkPut>(s).also { require(it.connect && it.state == GcalLinkStates.CONNECTED && it.readCalendars == 2 && it.writeCalendar && it.mirror) }
            },
            "risi_google_link_reply.json" to { s ->
                ProtocolJson.decodeFromString<GoogleLinkReply>(s).google.also { require(it.state == GcalLinkStates.CONNECTED && it.deviceName == "Pixel 8" && it.readCalendars == 2 && it.deviceId != null) }
            },
            "event_google_calendar_link.json" to { s ->
                ProtocolJson.decodeFromString<Event>(s).googleCalendarLink()!!.also { require(it.reason == GcalLinkReasons.DISCONNECTED && it.state == GcalLinkStates.NOT_CONNECTED && !it.removeCopies) }
            },
            "error_not_google_device.json" to { s -> apiError(s, GcalErrors.NOT_GOOGLE_DEVICE) },
            "event_risi_tool_call_calendar_check_google.json" to { s ->
                ProtocolJson.decodeFromString<Event>(s).risiToolCall()!!.also {
                    require(it.tool == RisiToolCall.TOOL_CALENDAR_CHECK && it.calendarCheckArgs()!!.sources == listOf("google_api") && it.toDevices.size == 1)
                }
            },
            "risi_tool_result_calendar_check_sync_off.json" to { s ->
                val r = toolResult(s)
                val c = ProtocolJson.decodeFromJsonElement(CalendarCheckResult.serializer(), r.result!!)
                val p = c.sources!!.single { it.source == "phone_provider" }
                require(r.status == "ok" && !p.readOk && p.reason == "sync_off" && p.calendars.size == 1 && c.connectedSources == listOf("phone_provider"))
                r
            },
            "risi_tool_result_calendar_check_google.json" to { s ->
                val r = toolResult(s)
                val rep = ProtocolJson.decodeFromJsonElement(GoogleSourceReport.serializer(), r.result!!["sources"]!!.jsonArray.single())
                require(r.status == "ok" && rep.readOk && rep.calendars.map { it.ref } == listOf("g4k2m7qa", "g9t3b8rc") && rep.reason == null)
                require("\"name\"" !in s && "account_type" !in s)
                r
            },
            "risi_tool_result_calendar_check_google_reauth.json" to { s ->
                val r = toolResult(s)
                val rep = ProtocolJson.decodeFromJsonElement(GoogleSourceReport.serializer(), r.result!!["sources"]!!.jsonArray.single())
                require(!rep.readOk && rep.reason == GcalReadReasons.REAUTH_NEEDED && rep.calendars.isEmpty())
                r
            },
            "envelope_risi_answer_calendar_sources_google.json" to { s ->
                env(s).also { m ->
                    val g = m.sources.single { it.source == "google_api" }
                    require(m.kind == "answer" && g.type == "calendar_source" && g.count == 2 && g.refs.size == 2 && g.names.isEmpty() && g.readOk == true)
                }
            },
            "envelope_risi_answer_calendar_google_no_answer.json" to { s ->
                env(s).also { m ->
                    val g = m.sources.single { it.source == "google_api" }
                    require(g.readOk == false && g.reason == GcalReadReasons.NO_ANSWER && g.refs.isEmpty() && g.count == 2 && m.nextSteps == listOf("Retry"))
                }
            },
            "envelope_risi_google_reconnect.json" to { s ->
                env(s).also { require(it.kind == RisiKinds131.GOOGLE_RECONNECT && it.reason == "reauth_needed" && it.buttons == listOf("reconnect") && it.deviceId != null) }
            },
            "risi_skills_reply_v131.json" to { s ->
                ProtocolJson.decodeFromString<RisiSkillsReply>(s).also { r ->
                    val cal = r.skills.single { it.id == "calendar" }
                    require(cal.permissions.count { it.scope == "oauth" } == 2)
                }
            },
        )
    }

    private val all: List<String> by lazy { read("index.txt").lines().filter { it.isNotBlank() } }

    /** Every example file must map to a model; a new file without a decoder fails this test. */
    /** v1.34 §33 basic messaging (net/Protocol134.kt, data/media/FileEnvelope.kt). */
    private val basicMessaging134: Map<String, (String) -> Any> by lazy {
        fun decode(s: String) = lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray())
        fun text(s: String) = decode(s) as lk.codegen.risime.data.mls.MlsPayload.Decoded.Text
        fun file(s: String) = (decode(s) as lk.codegen.risime.data.mls.MlsPayload.Decoded.File).envelope
        mapOf(
            "envelope_text_forwarded.json" to { s -> text(s).also { require(it.extras.forwardHops == 1 && !it.extras.forwardedMany && it.body.isNotEmpty()) } },
            "envelope_text_forwarded_many.json" to { s -> text(s).also { require(it.extras.forwardHops == 7 && it.extras.forwardedMany) } },
            // hops 0: shown without a label, never dropped.
            "envelope_text_forwarded_bad.json" to { s -> text(s).also { require(it.extras.forwardHops == null && it.body == "Site visit moved to 3 PM") } },
            "envelope_text_reply.json" to { s ->
                text(s).also { require(it.extras.replyTo == ReplyRef("c1a2b3e1-a0b1-11f0-8000-0242ac120002", "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e") && it.extras.forwardHops == null) }
            },
            "envelope_text_view_once_reserved.json" to { s -> text(s).also { require(it.extras.viewOnce) } },
            "image_payload_forwarded.json" to { s -> imageEnvelope(s).also { require(it.extras.forwardHops == 2 && it.caption == "Site visit, level 3") } },
            "file_payload.json" to { s ->
                file(s).also {
                    require(!it.partsOnly && it.blob!!.size == 196_656L && it.enc!!.plainSize == 196_608L && it.pages == 3 && it.thumb != null && it.caption == "Notes from Friday")
                    require(it.mime == lk.codegen.risime.data.media.FileEnvelope.MIME_PDF && !it.isApk && it.displayName == "Interview planning – 2026-10-09.pdf")
                    // Re-encoding gives the example back exactly.
                    require(ProtocolJson.parseToJsonElement(it.encode().decodeToString()) == ProtocolJson.parseToJsonElement(s))
                }
            },
            // A name with `/` (path traversal): dropped as malformed.
            "file_payload_bad_name.json" to { s -> (decode(s) as lk.codegen.risime.data.mls.MlsPayload.Decoded.Ignored).also { require(it.type.startsWith("file")) } },
            // `parts` (reserved for A) without `blob`: a placeholder bubble, never dropped.
            "file_payload_parts.json" to { s -> file(s).also { require(it.partsOnly && it.name == "Site survey.zip") } },
            "device_put_files.json" to { s ->
                ProtocolJson.decodeFromString<DevicePut>(s).also { require(CAPABILITY_FILES in it.mls!!.capabilities!! && CAPABILITY_PDF_EXPORT in it.mls!!.capabilities!! && CAPABILITY_RISI_TOOLS in it.mls!!.capabilities!!) }
            },
            "mls_group_files_ready.json" to { s -> ProtocolJson.decodeFromString<MlsGroup>(s).also { require(!it.filesReady && it.missingFiles.size == 1 && !it.imagesReady) } },
            "envelope_risi_answer_next_action_pdf.json" to { s ->
                ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                    val a = it.nextActions!!.single()
                    require(it.kind == "answer" && a.action == RisiTools134.ACTION_PDF && a.label == "PDF" && PdfSources.valid(a.source) && PdfSources.type(a.source) == PdfSources.NOTE)
                }
            },
            "envelope_risi_confirm_export_pdf.json" to { s ->
                ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                    require(it.kind == "confirm" && it.tool == RisiTools134.TOOL_EXPORT_PDF && it.buttons == listOf("send", "cancel") && (it.whenRaw == null || it.whenRaw is kotlinx.serialization.json.JsonNull))
                    require(PdfSources.valid(it.export!!.source) && it.export!!.conversationId.startsWith("dm:") && it.writeId != null)
                }
            },
            "event_risi_tool_call_export_pdf.json" to { s ->
                ProtocolJson.decodeFromString<Event>(s).risiToolCall()!!.also {
                    val a = it.exportPdfArgs()!!
                    require(it.tool == RisiTools134.TOOL_EXPORT_PDF && a.writeId == it.writeId && PdfSources.valid(a.source) && a.conversationId.startsWith("dm:"))
                }
            },
            "risi_tool_result_export_pdf.json" to { s ->
                ProtocolJson.decodeFromString<RisiToolResult>(s).also {
                    val r = ProtocolJson.decodeFromJsonElement(ExportPdfResult.serializer(), it.result!!)
                    require(it.status == RisiToolResult.OK && r.state == ExportPdfResult.SENT && r.pages == 3)
                    require(ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(RisiToolResult.serializer(), RisiToolResult(RisiToolResult.OK, ProtocolJson.encodeToJsonElement(ExportPdfResult.serializer(), r) as JsonObject))) == ProtocolJson.parseToJsonElement(s))
                }
            },
            "risi_tool_result_export_pdf_error.json" to { s ->
                ProtocolJson.decodeFromString<RisiToolResult>(s).also {
                    require(it.status == RisiToolResult.ERROR && it.result!!["code"]!!.jsonPrimitive.content == ExportPdfErrors.FILES_NOT_READY)
                    require(ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(RisiToolResult.serializer(), RisiToolResult.error(ExportPdfErrors.FILES_NOT_READY))) == ProtocolJson.parseToJsonElement(s))
                }
            },
            "backup_entry_message_v134.json" to { s ->
                ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupMessageLine>(s).also {
                    require(it.starredAt == "2026-10-11T08:20:00.000Z")
                    val f = (lk.codegen.risime.data.mls.MlsPayload.decode(it.payload.toString().toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.File).envelope
                    require(f.extras.forwardHops == 1 && f.thumb == null)
                    require(ProtocolJson.encodeToJsonElement(lk.codegen.risime.data.backup.BackupMessageLine.serializer(), it) == ProtocolJson.parseToJsonElement(s))
                }
            },
        )
    }

    /** v1.35 §34 Risi P0 (net/Protocol135.kt). */
    private val risiP0V135: Map<String, (String) -> Any> by lazy {
        fun env(s: String) = ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!
        val ask = "Check my calendar for Monday, October 12th"
        mapOf(
            "envelope_risi_answer_schedule_read.json" to { s ->
                env(s).also { m ->
                    require(m.kind == "answer" && m.localEvents != null && m.steps.single().tool == "risi_calendar_check")
                    require(m.sources.any { it.type == "calendar_source" && it.source == RisiKinds135.CALENDAR_SOURCE_RISI_ITEMS && it.readOk == true })
                    require(m.sources.single { it.type == RisiKinds135.SOURCE_RISI_ITEM }.let { it.itemId == "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f" && it.kind == RisiItem.PHONE_EVENT_ADDED })
                }
            },
            "envelope_risi_error_superseded.json" to { s ->
                env(s).also { require(it.kind == "error" && it.code == RisiKinds135.ERROR_SUPERSEDED && it.writeId == "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e" && it.nextActions == null) }
            },
            "envelope_risi_confirm_update_superseded.json" to { s ->
                val e = ProtocolJson.decodeFromString<RisiTextEnvelope>(s)
                e.risi!!.also {
                    require(it.kind == RisiKinds135.CONFIRM_UPDATE && it.state == RisiKinds135.STATE_SUPERSEDED && it.writeId == "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e")
                    require(it.byRequestId == "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d" && it.notify.isEmpty() && e.body == "Replaced by your newer request.")
                }
            },
            "envelope_risi_error_ask_again.json" to { s ->
                env(s).also { require(it.kind == "error" && it.code == "model_unavailable" && it.nextActions!!.single().let { a -> a.action == RisiNextAction.ASK && a.label == "Ask me again" && a.text == ask }) }
            },
            "envelope_risi_answer_ask_again.json" to { s ->
                env(s).also { m ->
                    require(m.kind == "answer" && m.nextActions!!.size == 2 && m.nextActions!![0].text == ask && m.nextActions!![1].target == RisiNextAction.SETTINGS_CALENDAR)
                }
            },
            "risi_items_reply.json" to { s ->
                ProtocolJson.decodeFromString<RisiItemsReply>(s).also { r ->
                    require(r.items.map { it.kind } == listOf("scheduled_message", "risi_calendar_event", "phone_event_added", "reminder", "promise", "follow_up"))
                    val sm = r.items[0]
                    require(sm.title == null && sm.scheduleId != null && sm.repeat == "daily" && sm.deviceId == "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d")
                    val pe = r.items[2]
                    require(pe.eventId == "4711" && pe.calendar == RisiCalendarRef("Work", "Google") && pe.writeId != null && pe.can(RisiItem.DELETE))
                    require(r.items[3].reminderId != null && r.items[4].itemId == r.items[4].id && !r.items[4].can(RisiItem.DELETE) && r.items[5].start == null && r.items[5].actions == listOf("open"))
                }
            },
            "risi_items_patch_reminder.json" to { s ->
                ProtocolJson.decodeFromString<RisiItemPatch>(s).also {
                    require(it.at == "2026-10-13T04:30:00.000Z" && it.text == "Pay the electricity bill")
                    require(ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(RisiItemPatch.serializer(), it)) == ProtocolJson.parseToJsonElement(s))
                }
            },
            "envelope_risi_answer_risi_items.json" to { s ->
                env(s).also { m -> require(m.kind == "answer" && m.sources.size == 6 && m.sources.all { it.type == RisiKinds135.SOURCE_RISI_ITEM && it.itemId != null && it.kind in RisiItem.KINDS } && m.sources.none { it.showable }) }
            },
            "envelope_risi_answer_name_clarify.json" to { s ->
                env(s).also { m ->
                    val c = m.clarify!!
                    require(c.about == RisiNameClarify.ABOUT_NAME && c.said == "Shutazi" && c.keep && c.options.single().name == "Shirazi" && c.writeId == "5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e")
                    require(m.nextActions!!.map { it.text } == listOf("Use Shirazi", "Keep Shutazi"))
                }
            },
            "device_put_risi_items.json" to { s ->
                ProtocolJson.decodeFromString<DevicePut>(s).also { require(CAPABILITY_RISI_ITEMS in it.mls!!.capabilities!! && CAPABILITY_RISI_TOOLS in it.mls!!.capabilities!!) }
            },
        )
    }

    private val decoders: Map<String, (String) -> Any> = calendarV129 + notesV130 + google131 + basicMessaging134 + risiP0V135 + mapOf(
        // v1.32 §32: an ops alert is a text envelope whose risi kind this build does not render specially; it must still decode.
        "envelope_risi_ops_alert.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.risi!!.kind == "ops_alert" && it.body.isNotEmpty()) } },
        // v1.32 §25.3 calendar_add: verified, and the top-level error code + detail.
        "risi_tool_result_calendar_add_v132.json" to { s ->
            ProtocolJson.decodeFromString<RisiToolResult>(s).also {
                val r = ProtocolJson.decodeFromJsonElement(CalendarAddResult.serializer(), it.result!!)
                require(it.status == RisiToolResult.OK && r.eventId == "4711" && r.verified == true)
                require(ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(RisiToolResult.serializer(), RisiToolResult(RisiToolResult.OK, ProtocolJson.encodeToJsonElement(CalendarAddResult.serializer(), CalendarAddResult("4711", verified = true)) as kotlinx.serialization.json.JsonObject))) == ProtocolJson.parseToJsonElement(s))
            }
        },
        "risi_tool_result_calendar_add_error_v132.json" to { s ->
            ProtocolJson.decodeFromString<RisiToolResult>(s).also {
                require(it.status == RisiToolResult.ERROR && it.code == CalendarAddErrors.VERIFY_FAILED && it.detail == "read-back: no row for id 4711" && it.result == null)
                require(ProtocolJson.parseToJsonElement(ProtocolJson.encodeToString(RisiToolResult.serializer(), RisiToolResult.addError("verify_failed", "read-back: no row for id 4711"))) == ProtocolJson.parseToJsonElement(s))
            }
        },
        // v1.32 §29.7 answer.local_events: the checked range the asker's phone lists its own events for.
        "envelope_risi_answer_local_events.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "answer" && it.localEvents!!.from == "2026-10-11T18:30:00.000Z" && it.localEvents!!.to == "2026-10-18T18:30:00.000Z" && it.notify.size == 1)
                require(lk.codegen.risime.data.tabs.LocalEvents.range(it.localEvents) != null)
            }
        },
        // v1.27 (§27 made_by, the Ledger follow-ups, call transcription): net/Protocol127.kt and RisiMeta's optional fields.
        "auth_config_v127.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s).also { require(it.risiLedgerOn && it.risiTranscribeOn && it.risiSkillsOn && it.risiToolsOn) } },
        "device_put_risi_ledger.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s).also { require(CAPABILITY_RISI_LEDGER in it.mls!!.capabilities!! && CAPABILITY_RISI_TOOLS in it.mls!!.capabilities!!) } },
        "envelope_risi_answer_made_by.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == "answer" && it.madeBy!!.model == "risi-l1" && it.madeBy!!.provider == "risime" && it.madeBy!!.at != null && it.madeBy!!.also.isEmpty()) }
        },
        "envelope_risi_discussion_summary_chat.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == RisiKinds127.DISCUSSION_SUMMARY && it.summaryId != null && it.conversationId!!.startsWith("grp:") && it.chatId!!.startsWith("dm:"))
                require(it.forUsers.single() == it.items[0].owner && it.withUsers.size == 1 && it.source == "chat" && it.callId == null && it.durationS == null && it.keyPoints.size == 2)
                require(it.items.size == 2 && it.items[0].id == it.items[0].itemId && !it.items[0].allDay && it.items[1].allDay && it.items.all { i -> i.state == RisiItemStates.PROPOSED } && it.expiresAt != null)
            }
        },
        "envelope_risi_discussion_summary_call.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == RisiKinds127.DISCUSSION_SUMMARY && it.source == "call" && it.media == "video" && it.durationS == 1925L && it.callId != null)
                require(it.madeBy!!.also.single().task == RisiMadeByAlso.TASK_TRANSCRIBE && it.items[1].counterpart.single() == it.items[0].owner)
            }
        },
        "envelope_risi_discussion_card.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds127.DISCUSSION_CARD && it.itemsCount == 2 && it.summary!!.isNotEmpty() && it.withUsers.size == 2 && it.notify.isEmpty() && it.items.isEmpty()) }
        },
        "envelope_risi_item_update.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds127.ITEM_UPDATE && it.itemId != null && it.state == RisiItemStates.CONFIRMED && it.by != null && it.allDay == true && it.madeBy!!.model == null && it.callRef == null) }
        },
        "envelope_risi_item_due.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds127.ITEM_DUE && it.moment == RisiItemDue.BEFORE && it.role == RisiItemDue.ROLE_OWNER && it.buttons == listOf("done", "new_date") && it.allDay == false) }
        },
        "envelope_risi_item_due_counterpart.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds127.ITEM_DUE && it.moment == RisiItemDue.TODAY && it.role == RisiItemDue.ROLE_COUNTERPART && it.buttons.isEmpty()) }
        },
        "envelope_risi_item_overdue.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds127.ITEM_OVERDUE && it.overdueBy == 7200L && it.buttons == listOf("done", "new_date") && it.owner == null) }
        },
        "envelope_risi_item_nudge.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds127.ITEM_NUDGE && it.overdueBy == 93600L && it.owner != null && it.buttons.isEmpty()) }
        },
        "envelope_risi_digest_personal.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == "digest" && it.scope == "personal" && it.items.size == 2 && it.items[0].id == it.items[0].commitmentId) }
        },
        "envelope_risi_action_item_confirm.json" to { s -> ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also { require(it.action == RisiActions127.ITEM_CONFIRM && it.edit == null && ProtocolJson.encodeToJsonElement(it) == ProtocolJson.parseToJsonElement(s)) } },
        "envelope_risi_action_item_decline.json" to { s -> ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also { require(it.action == RisiActions127.ITEM_DECLINE && it.edit == null && ProtocolJson.encodeToJsonElement(it) == ProtocolJson.parseToJsonElement(s)) } },
        "envelope_risi_action_item_edit.json" to { s ->
            ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also {
                require(it.action == RisiActions127.ITEM_EDIT && it.edit == RisiActions127.editObject("Send the revised quote with transport", "2026-10-10T06:30:00.000Z", false))
                require(ProtocolJson.encodeToJsonElement(it) == ProtocolJson.parseToJsonElement(s))
            }
        },
        "envelope_risi_call_listen.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds127.CALL_LISTEN && it.state == CallRisiState.LISTENING && it.since != null && it.by == null && it.callId != null) }
        },
        "envelope_risi_call_listen_stopped.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == RisiKinds127.CALL_LISTEN && it.state == CallRisiState.STOPPED && it.reason == "stopped" && it.by != null) }
        },
        "call_offer_sfu_risi_payload.json" to { s ->
            (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.SfuOffer).also {
                require(it.risi == CALL_RISI_LISTEN && it.media == "video")
                require(lk.codegen.risime.calls.CallEnvelope.toJson(it) == ProtocolJson.parseToJsonElement(s)) // re-encodes exactly
            }
        },
        "group_call_started_risi_payload.json" to { s -> groupCall(s).also { require(it.state == "started" && it.risi == CALL_RISI_LISTEN) } },
        "calls_room_request_risi.json" to { s ->
            ProtocolJson.decodeFromString<CallsRoomRequest>(s).also { require(it.action == CallsRoomRequest.START && it.risiListen == true && ProtocolJson.encodeToJsonElement(it) == ProtocolJson.parseToJsonElement(s)) }
        },
        "calls_room_reply_risi.json" to { s -> ProtocolJson.decodeFromString<CallsRoomReply>(s).also { require(it.risi!!.state == CallRisiState.REQUESTED && it.risi!!.reason == null && !it.toString().contains(it.token)) } },
        "calls_room_risi_stop.json" to { s ->
            ProtocolJson.decodeFromString<CallsRoomRequest>(s).also { require(it.action == CallsRoomRequest.RISI_STOP && it.risiListen == null && ProtocolJson.encodeToJsonElement(it) == ProtocolJson.parseToJsonElement(s)) }
        },
        "calls_room_risi_stop_reply.json" to { s -> ProtocolJson.decodeFromString<CallsRoomRisiStopReply>(s).also { require(it.risi.state == CallRisiState.STOPPED && it.risi.reason == "stopped") } },
        "calls_room_status_reply_v127.json" to { s -> ProtocolJson.decodeFromString<CallsRoomStatusReply>(s).also { require(it.active && it.risi!!.state == CallRisiState.LISTENING) } },
        "signal_call_risi.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).callRisi()!!.also { require(it.state == CallRisiState.STOPPED && it.by != null && it.conversationId.startsWith("grp:")) } },
        // Server-side claims for Risi's internal token: never sent to the app; checked for the listen-only grants.
        "livekit_token_claims_risi.json" to { s ->
            (ProtocolJson.parseToJsonElement(s) as JsonObject).also { o ->
                val v = o["video"]!!.jsonObject
                require(!v["canPublish"]!!.jsonPrimitive.boolean && v["canSubscribe"]!!.jsonPrimitive.boolean && !v["hidden"]!!.jsonPrimitive.boolean && v["canPublishSources"]!!.jsonArray.isEmpty())
            }
        },
        "risi_commitments_reply_v127.json" to { s ->
            ProtocolJson.decodeFromString<RisiCommitmentsReply>(s).also { require(it.commitments.map { c -> c.role } == listOf("owner", "counterpart") && it.commitments[1].allDay == true && it.commitments[0].summaryId != null && it.commitments[0].source == "call") }
        },
        // v1.28 (§28 the Risi action loop, proactive offers, My promises; §27.13 30-day summaries). Fields the
        // app does not model yet (origin, totals, direction, ...) are checked on the raw JSON (clients ignore them).
        "envelope_risi_confirm_calendar_add.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "confirm" && it.tool == RisiToolCall.TOOL_CALENDAR_ADD && it.skillId == RisiSkillIds.CALENDAR && it.args!!["title"]!!.jsonPrimitive.content == "Interview with Shenika")
                require(it.calendarHint() == RisiCalendarRef("Google Calendar", null) && it.buttons == listOf("add", "cancel") && it.turnRef != null)
            }
        },
        "envelope_risi_confirm_offer.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "confirm" && it.tool == RisiToolCall.TOOL_CALENDAR_ADD && it.calendarHint() == null && it.turnRef == null && it.itemId != null && it.madeBy!!.model == null)
                require((ProtocolJson.parseToJsonElement(s).jsonObject["risi"]!!.jsonObject["origin"]!!.jsonPrimitive.content) == "offer")
            }
        },
        "envelope_risi_action_confirm_write_edit.json" to { s ->
            ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also { require(it.action == RisiActions.CONFIRM_WRITE && it.edit!!["title"]!!.jsonPrimitive.content == "Interview with Shenika (HR)" && ProtocolJson.encodeToJsonElement(it) == ProtocolJson.parseToJsonElement(s)) }
        },
        "risi_tool_result_calendar_add_v128.json" to { s ->
            ProtocolJson.decodeFromString<RisiToolResult>(s).also { require(ProtocolJson.decodeFromJsonElement(CalendarAddResult.serializer(), it.result!!).calendar == RisiCalendarRef("Google Calendar", null)) }
        },
        "risi_skills_patch_calendar.json" to { s ->
            ProtocolJson.decodeFromString<RisiSkillsPatch>(s).also { require(it.changes.single().id == RisiSkillIds.CALENDAR && it.changes.single().calendar!!.name == "Google Calendar" && it.changes.single().state == null) }
        },
        "envelope_risi_item_clarify.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { e -> e.risi!!.also { require(it.kind == "item_clarify" && it.itemId != null && it.question == e.body && it.dueText == "soon" && it.buttons == listOf("new_date")) } }
        },
        "risi_commitments_reply_v128.json" to { s ->
            ProtocolJson.decodeFromString<RisiCommitmentsReply>(s).also {
                require(it.commitments.size == 3 && it.commitments[2].summaryId == null && it.commitments[2].allDay == false && it.commitments[2].due == null)
                val raw = ProtocolJson.parseToJsonElement(s).jsonObject
                require(raw["totals"]!!.jsonObject["promised_to_me"]!!.jsonPrimitive.content == "2")
                require(raw["commitments"]!!.jsonArray.map { c -> c.jsonObject["direction"]!!.jsonPrimitive.content } == listOf("i_promised", "promised_to_me", "promised_to_me"))
            }
        },
        "envelope_risi_digest_personal_v128.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == "digest" && it.scope == "personal" && it.items.size == 3 && it.items[2].due == null) }
        },
        "envelope_risi_request_period.json" to { s ->
            ProtocolJson.decodeFromString<RisiRequestEnvelope>(s).also { require(it.action == "summarise" && it.scope!!["period"]!!.jsonPrimitive.content == "7d") }
        },
        "envelope_risi_summary_period.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == "summary" && it.period!!.scope == "7d" && it.days.size == 3 && it.days.all { d -> d.scope == "day" }) }
        },
        "risi_facts_reply_summaries.json" to { s ->
            ProtocolJson.decodeFromString<RisiFactsReply>(s).also { require(it.facts.count { f -> f.kind == "summary" } == 2 && it.facts.last().scope == "week" && it.facts.last().period!!.from == "2026-09-28") }
        },
        // v1.26 (§26 Risi skills): typed models in net/Protocol126.kt (and RisiMeta's / RisiToolCall's optional fields).
        "auth_config_v126.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s).also { require(it.risiSkillsOn && it.risiToolsOn && it.tabsOn) } },
        "device_put_risi_skills.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s).also { require(CAPABILITY_RISI_SKILLS in it.mls!!.capabilities!! && CAPABILITY_RISI_TOOLS in it.mls!!.capabilities!!) } },
        "envelope_risi_action_calendar_accept.json" to { s -> ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also { require(it.action == RisiActions126.CALENDAR_ACCEPT && it.edit == null && it.options!!["reminder"]!!.jsonPrimitive.boolean) } },
        "envelope_risi_action_calendar_decline.json" to { s -> ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also { require(it.action == RisiActions126.CALENDAR_DECLINE && it.options == null) } },
        "envelope_risi_calendar_offer.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "calendar_offer" && it.offerId != null && it.title == "Board meeting" && it.start != null && it.end != null && it.allDay == false)
                require(it.forUsers.size == 2 && it.reminderBeforeMin == 15 && it.buttons == listOf("add", "decline") && it.expiresAt == it.start)
            }
        },
        "envelope_risi_confirm_schedule_message.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "confirm" && it.tool == RisiToolCall.TOOL_SCHEDULE_MESSAGE && it.skillId == RisiSkillIds.SCHEDULED_MESSAGES)
                require(it.args!!["text"]!!.jsonPrimitive.content == "Good morning" && it.args!!["repeat"]!!.jsonPrimitive.content == "daily" && "write_id" !in it.args!!)
            }
        },
        "envelope_risi_confirm_set_alarm.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "confirm" && it.tool == RisiToolCall.TOOL_SET_ALARM && it.skillId == RisiSkillIds.ALARM && it.args!!["time"]!!.jsonPrimitive.content == "05:30")
            }
        },
        "envelope_risi_skill_done.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "skill_done" && it.skillId == RisiSkillIds.ALARM && it.entryId != null && it.action == "alarm_set" && it.via == "allowed")
                require(it.undo!!.kind == RisiUndo.UNDO_MANUAL && it.undo!!.hint == "Open Clock to remove it" && it.undoToken == null)
            }
        },
        "envelope_risi_skill_needed.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == "skill_needed" && it.skillId == RisiSkillIds.ALARM && it.reason == "off" && it.wasOn == true && it.buttons == listOf("open_skills")) } },
        "error_skill_unavailable.json" to { s -> apiError(s, RisiSkillsErrors.SKILL_UNAVAILABLE) },
        "error_undo_unavailable.json" to { s -> apiError(s, RisiSkillsErrors.UNDO_UNAVAILABLE) },
        "event_risi_tool_call_calendar_remove.json" to { s -> ProtocolJson.decodeFromString<Event>(s).risiToolCall()!!.also { require(it.tool == RisiToolCall.TOOL_CALENDAR_REMOVE && it.calendarRemoveArgs()!!.targetWriteId.isNotEmpty() && it.undoEntryId != null && it.conversationId == null && it.writeId == null) } },
        "event_risi_tool_call_cancel_scheduled.json" to { s -> ProtocolJson.decodeFromString<Event>(s).risiToolCall()!!.also { require(it.tool == RisiToolCall.TOOL_CANCEL_SCHEDULED && it.cancelScheduledArgs()!!.writeId == null && it.undoEntryId != null && it.requestId == null) } },
        "event_risi_tool_call_schedule_message.json" to { s -> ProtocolJson.decodeFromString<Event>(s).risiToolCall()!!.also { val a = it.scheduleMessageArgs()!!; require(it.tool == RisiToolCall.TOOL_SCHEDULE_MESSAGE && a.text == "Good morning" && a.repeat == "daily" && a.conversationId.startsWith("dm:") && it.writeId == a.writeId && it.undoEntryId == null) } },
        "event_risi_tool_call_set_alarm.json" to { s -> ProtocolJson.decodeFromString<Event>(s).risiToolCall()!!.also { val a = it.setAlarmArgs()!!; require(it.tool == RisiToolCall.TOOL_SET_ALARM && a.time == "05:30" && a.label == "Wake up" && a.days == null) } },
        "risi_skill_activity_reply.json" to { s -> ProtocolJson.decodeFromString<RisiActivityReply>(s).also { require(it.entries.size == 2 && it.entries[0].undo.kind == RisiUndo.UNDO_CLIENT && it.entries[0].undoToken != null && !it.hasMore) } },
        "risi_skill_activity_scheduled_reply.json" to { s -> ProtocolJson.decodeFromString<RisiActivityReply>(s).also { require(it.entries[0].targetConversationId!!.startsWith("dm:") && it.entries[0].undo.until == null && it.entries[1].undo.kind == RisiUndo.UNDO_MANUAL) } },
        "risi_skill_undo.json" to { s -> ProtocolJson.decodeFromString<RisiUndoRequest>(s).also { require(it.undoToken.startsWith("u1.")) } },
        "risi_skill_undo_reply.json" to { s -> ProtocolJson.decodeFromString<RisiUndoReply>(s).also { require(it.entry.undo.state == RisiUndo.PENDING && it.entry.undoToken == null) } },
        "risi_skills_patch.json" to { s -> ProtocolJson.decodeFromString<RisiSkillsPatch>(s).also { require(it.changes.size == 2 && it.changes[1].state == null && it.changes[1].clientPermission == ClientPermission.DENIED && !it.cancelPending) } },
        "risi_skills_patch_reply.json" to { s -> ProtocolJson.decodeFromString<RisiSkillsReply>(s).also { require(it.skills.map { k -> k.id } == listOf("calendar", "scheduled_messages") && it.skills[0].state == RisiSkillStates.ASK) } },
        "risi_skills_reply.json" to { s ->
            ProtocolJson.decodeFromString<RisiSkillsReply>(s).also {
                require(it.skills.map { k -> k.id } == listOf("alarm", "reminders", "calendar", "scheduled_messages", "email"))
                require(it.skills[0].state == RisiSkillStates.ALLOWED && it.skills[0].client!!.permission == ClientPermission.NOT_NEEDED && !it.skills[4].available && it.skills[4].client == null)
                require(it.skills[3].modes == listOf("ask") && it.skills[2].permissions.all { p -> p.runtime })
            }
        },
        "risi_tool_result_calendar_remove.json" to { s -> ProtocolJson.decodeFromString<RisiToolResult>(s).also { require(ProtocolJson.decodeFromJsonElement(CalendarRemoveResult.serializer(), it.result!!).removed) } },
        "risi_tool_result_cancel_scheduled.json" to { s -> ProtocolJson.decodeFromString<RisiToolResult>(s).also { require(ProtocolJson.decodeFromJsonElement(CancelScheduledResult.serializer(), it.result!!).cancelled) } },
        "risi_tool_result_schedule_message.json" to { s -> ProtocolJson.decodeFromString<RisiToolResult>(s).also { require(ProtocolJson.decodeFromJsonElement(ScheduleMessageResult.serializer(), it.result!!).scheduleId.isNotEmpty()) } },
        "risi_tool_result_set_alarm.json" to { s -> ProtocolJson.decodeFromString<RisiToolResult>(s).also { require(ProtocolJson.decodeFromJsonElement(SetAlarmResult.serializer(), it.result!!).alarmSet) } },
        // v1.25 (§25 Risi with tools): typed models in net/Protocol125.kt (and RisiMeta's optional fields).
        "auth_config_v125.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s).also { require(it.risiToolsOn && it.tabsOn) } },
        "chat_reply_risi.json" to { s -> ProtocolJson.decodeFromString<ChatReply>(s).also { require(it.chat.kind == CHAT_KIND_RISI && it.chat.privateSide == null && !it.chat.canToggle && it.chat.official.conversationId == it.chat.chatId) } },
        "device_put_risi_tools.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s).also { require(CAPABILITY_RISI_TOOLS in it.mls!!.capabilities!! && CAPABILITY_TABS in it.mls!!.capabilities!!) } },
        "envelope_risi_action_confirm_write.json" to { s -> ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also { require(it.action == RisiActions.CONFIRM_WRITE && it.edit == null) } },
        "envelope_risi_action_me_too.json" to { s -> ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also { require(it.action == RisiActions.ME_TOO) } },
        "envelope_risi_answer_v2.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "answer" && it.steps.size == 2 && it.steps[0] == RisiStep("calendar_check", RisiStepStatus.OK))
                require(it.sources.map { x -> x.type } == listOf("calendar", "message", "note", "link") && it.sources.all { x -> x.showable })
                require(it.nextSteps.size == 1 && it.localSearch!!.text == "budget" && it.turnRef != null)
            }
        },
        "envelope_risi_confirm.json" to { s ->
            ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also {
                require(it.kind == "confirm" && it.writeId != null && it.tool == "calendar_add" && it.forUsers.size == 1 && it.expiresAt != null)
                require(it.confirmWhen()!!.end != null && it.buttons == listOf("add", "cancel") && it.text == "Dentist")
            }
        },
        "envelope_risi_draft.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == "draft" && it.language == "ta" && it.targetConversationId!!.startsWith("grp:")) } },
        "envelope_risi_reminder_set.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).risi!!.also { require(it.kind == "reminder_set" && it.reminderId != null && it.reminderWhen() != null && it.meToo && it.participants.size == 1) } },
        "error_tool_call_expired.json" to { s -> apiError(s, RisiToolsErrors.TOOL_CALL_EXPIRED) },
        "event_risi_tool_call_calendar_add.json" to { s -> ProtocolJson.decodeFromString<Event>(s).risiToolCall()!!.also { require(it.tool == RisiToolCall.TOOL_CALENDAR_ADD && it.calendarAddArgs()!!.title == "Dentist" && it.toDevices == listOf(it.deviceId)) } },
        "event_risi_tool_call_calendar_check.json" to { s -> ProtocolJson.decodeFromString<Event>(s).risiToolCall()!!.also { require(it.tool == RisiToolCall.TOOL_CALENDAR_CHECK && it.calendarCheckArgs() != null) } },
        "risi_chat_create_reply.json" to { s -> ProtocolJson.decodeFromString<RisiChatReply>(s).also { require(it.chat.official.state == "none" && it.group.state == Group.STATE_CREATING && it.group.chatKind == CHAT_KIND_RISI && it.group.chatId == it.group.id) } },
        "risi_facts_reply_v125.json" to { s -> ProtocolJson.decodeFromString<RisiFactsReply>(s).also { require(it.facts.any { f -> f.kind == "note" }) } },
        "risi_tool_result_calendar_add.json" to { s -> ProtocolJson.decodeFromString<RisiToolResult>(s).also { require(it.status == RisiToolResult.OK && ProtocolJson.decodeFromJsonElement(CalendarAddResult.serializer(), it.result!!).eventId == "4711") } },
        "risi_tool_result_calendar_check.json" to { s -> ProtocolJson.decodeFromString<RisiToolResult>(s).also { require(ProtocolJson.decodeFromJsonElement(CalendarCheckResult.serializer(), it.result!!).blocks.size == 2) } },
        "signal_risi_progress.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).risiProgress()!!.also { require(it.state == RisiProgress.STEP && it.step!!.n == 1 && it.seq == 3) } },
        // v1.24 (§24 two tabs and Risi stage 1): typed models in net/Protocol124.kt.
        "auth_config_v124.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s).also { require(it.tabsOn && it.backupOn) } },
        "backup_bundle_header_v124.json" to { s -> ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupBundleHeader>(s).also { require(it.schema == 2) } },
        "backup_entry_conversation_v124.json" to { s -> ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupConversationLine>(s).also { require(it.kind == "group") } },
        "chat_official_create.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "chat_official_create_reply.json" to { s -> ProtocolJson.decodeFromString<GroupReply>(s).also { require(it.group.tab == "official" && it.group.chatKind == "dm" && it.group.chatId != null) } },
        "chat_patch_official.json" to { s -> ProtocolJson.decodeFromString<ChatPatch>(s).also { require(it.official == "off") } },
        "chat_reply.json" to { s -> ProtocolJson.decodeFromString<ChatReply>(s).also { require(it.chat.official.on && it.chat.officialReady) } },
        "chats_reply.json" to { s -> ProtocolJson.decodeFromString<ChatsReply>(s).also { require(it.chats.size >= 2 && it.chats.any { c -> !c.official.on }) } },
        "device_put_tabs.json" to { s -> ProtocolJson.parseToJsonElement(s).jsonObject.also { require(CAPABILITY_TABS in it["mls"]!!.jsonObject["capabilities"]!!.jsonArray.map { c -> c.jsonPrimitive.content }) } },
        "envelope_risi_action.json" to { s -> ProtocolJson.decodeFromString<RisiActionEnvelope>(s).also { require(it.type == "risi_action" && it.edit != null) } },
        "envelope_risi_answer.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "answer") } },
        "envelope_risi_commitment.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "commitment") } },
        "envelope_risi_commitment_update.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "commitment_update") } },
        "envelope_risi_digest.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "digest") } },
        "envelope_risi_error.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "error") } },
        "envelope_risi_escalation.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "escalation") } },
        "envelope_risi_offer.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "offer") } },
        "envelope_risi_reminder.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "reminder") } },
        "envelope_risi_report.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "report") } },
        "envelope_risi_summary.json" to { s -> ProtocolJson.decodeFromString<RisiTextEnvelope>(s).also { require(it.type == "text" && it.risi!!.kind == "summary") } },
        "envelope_risi_request.json" to { s -> ProtocolJson.decodeFromString<RisiRequestEnvelope>(s).also { require(it.type == "risi_request") } },
        "error_official_off.json" to { s -> ProtocolJson.decodeFromString<ReasonBody>(s).also { require(it.reason == "official_off") } },
        "error_private_tab.json" to { s -> apiError(s, TabsErrors.PRIVATE_TAB) },
        "event_chat_official_created.json" to { s -> ProtocolJson.decodeFromString<ChatEvent>(s).also { require(it.kind == "chat_event" && it.data.action == "official_created" && ProtocolJson.decodeFromString<Event>(s).chatEvent()!!.officialConversationId != null) } },
        "event_chat_official_off.json" to { s -> ProtocolJson.decodeFromString<ChatEvent>(s).also { require(it.kind == "chat_event" && it.data.action == "official_off") } },
        "event_chat_official_on.json" to { s -> ProtocolJson.decodeFromString<ChatEvent>(s).also { require(it.kind == "chat_event" && it.data.action == "official_on") } },
        "event_group_agent_added.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { require(it.kind == "group_event") } },
        "event_group_agent_removed.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { require(it.kind == "group_event") } },
        "event_group_created_official.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { require(it.kind == "group_event" && it.groupEvent()!!.tab == "official" && it.groupEvent()!!.chatId != null && it.groupEvent()!!.members!!.any { m -> m.kind == "agent" }) } },
        "group_meta_official.json" to { s -> GroupMeta.decode(s.toByteArray())!!.also { require(it.official && it.chatId!!.startsWith("grp:") && it.agents!!.size == 1 && it.admins.none { a -> a in it.agents!! }) } },
        "group_reply_v124.json" to { s -> ProtocolJson.decodeFromString<GroupReply>(s).also { require(it.group.tab == "official" && it.group.agents.size == 1 && it.group.members.any { m -> m.kind == "agent" }) } },
        "risi_commitments_reply.json" to { s -> ProtocolJson.decodeFromString<RisiCommitmentsReply>(s).also { require(it.commitments.single().state == "confirmed") } },
        "risi_facts_reply.json" to { s -> ProtocolJson.decodeFromString<RisiFactsReply>(s).also { require(it.facts.size == 2) } },
        "risi_feedback.json" to { s -> ProtocolJson.decodeFromString<RisiFeedback>(s).also { require(it.rating == "down") } },
        "auth_verify_reply.json" to { s -> ProtocolJson.decodeFromString<AuthVerifyReply>(s) },
        // v1.23 (§23 voice/video switching and screen sharing): parse-only placeholders until the app implements it.
        "call_answer_features_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_answer_renegotiate_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_media_group_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_media_screen_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_offer_features_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_offer_renegotiate_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_offer_renegotiate_payload_bad.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_switch_accept_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_switch_group_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_switch_request_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "call_switch_voice_payload.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "calls_room_status_reply_v123.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "calls_room_upgrade_reply.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "calls_room_upgrade_request.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "device_put_call_switch.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "error_not_in_call.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "error_too_many_for_video.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "livekit_token_claims_v123.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        "livekit_update_participant.json" to { s -> ProtocolJson.parseToJsonElement(s) as JsonObject },
        // v1.22 (§22 encrypted backups): typed models; client-sent bodies re-encode exactly (backupExamplesReEncode).
        "auth_config_v122.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s).also { require(it.backupOn) } },
        "backup_bundle_header.json" to { s ->
            ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupBundleHeader>(s).also { require(it.type == "backup" && it.origin == "backup" && it.schema == 1 && it.counts.messages > 0) }
        },
        "backup_create_reply.json" to { s -> ProtocolJson.decodeFromString<BackupCreateReply>(s).also { require(it.backup.current && it.backup.parts.size == 2 && it.backup.expiresAt == null) } },
        "backup_create_request.json" to { s ->
            ProtocolJson.decodeFromString<BackupCreateRequest>(s).also { require(it.parts.sumOf { p -> p.size } == it.size && !it.replaceDevice) }
        },
        "backup_entry_contact.json" to { s -> ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupContactLine>(s).also { require(it.phone!!.startsWith("+")) } },
        "backup_entry_conversation.json" to { s ->
            ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupConversationLine>(s).also { require(it.kind == "group" && it.group!!.admins.isNotEmpty() && it.chat.pinned) }
        },
        "backup_entry_group_event.json" to { s -> ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupGroupEventLine>(s).also { require(it.action == "added" && it.targets.size == 1) } },
        "backup_entry_message.json" to { s ->
            ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupMessageLine>(s).also {
                require(it.status == "read" && it.origin == null)
                require(lk.codegen.risime.data.mls.MlsPayload.decode(it.payload.toString().toByteArray()) == lk.codegen.risime.data.mls.MlsPayload.Decoded.Text("See you at 10"))
            }
        },
        "backup_entry_tombstone.json" to { s -> ProtocolJson.decodeFromString<lk.codegen.risime.data.backup.BackupTombstoneLine>(s).also { require(it.scope == "everyone" && !it.hidden) } },
        "backup_file_header.json" to { s -> ProtocolJson.decodeFromString<BackupFileHeader>(s).also { require(it.v == 1 && it.key!!["bk_id"]!!.jsonPrimitive.content == it.bkId) } },
        "backup_key_put.json" to { s -> ProtocolJson.decodeFromString<JsonObject>(s).also { require(it["wraps"]!!.jsonArray.size == 2 && it["updated_at"] == null) } },
        "backup_key_reply.json" to { s -> ProtocolJson.decodeFromString<BackupKeyReply>(s).also { require(it.bkId == "en20cm7Cc5c=") } },
        "backups_reply.json" to { s ->
            ProtocolJson.decodeFromString<BackupsReply>(s).also {
                require(it.key && it.backups.size == 3 && it.newest()!!.backupId == it.backups.first().backupId && !it.backups.last().current)
            }
        },
        "blob_upload_backup_reply.json" to { s -> ProtocolJson.decodeFromString<BlobUploadReply>(s).also { require(it.size == 33_562_624L && it.expiresAt != null) } },
        "blob_usage_reply_backup.json" to { s -> ProtocolJson.decodeFromString<BlobUsageReply>(s).also { require(it.backup!!.limit == 1_610_612_736L) } },
        "error_backup_device_mismatch.json" to { s -> apiError(s, BackupErrors.DEVICE_MISMATCH).also { require(it.error.deviceName == "Galaxy A54" && it.error.deviceId != null) } },
        "error_backup_key_conflict.json" to { s -> apiError(s, BackupErrors.KEY_CONFLICT).also { require(it.error.bkId == "en20cm7Cc5c=") } },
        "error_backup_unavailable.json" to { s -> apiError(s, BackupErrors.UNAVAILABLE) },
        "error_no_backup_key.json" to { s -> apiError(s, BackupErrors.NO_KEY) },
        // v1.21 (§12.12 reinstalls without reset) and the v1.16 DM-op examples (§10.6): typed models and the decisions they drive.
        "error_rejoin_pending.json" to { s ->
            apiError(s, AuthErrors.REJOIN_PENDING).also { require(it.error.opId != null && it.error.candidates == 2) }
        },
        "event_group_op_cleanup.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).groupOp()!!.also { e ->
                // A cleanup op: removes only its own user's old leaf, adds nothing, named to that user's live device.
                require(e.op.type == PendingOp.DEVICES && e.op.added.isEmpty() && e.op.removed.single().userId == e.op.actor)
                require(e.op.committer!!.userId == e.op.actor && e.op.committer!!.deviceId != e.op.removed.single().deviceId)
            }
        },
        "event_mls_dm_op.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).mlsDmOp()!!.also { e ->
                require(e.conversationId.startsWith("dm:") && e.op.type == PendingOp.DEVICES && e.op.committer != null && e.op.added.size == 1 && e.op.removed.size == 1)
            }
        },
        "group_rejoin_reply_v121.json" to { s ->
            ProtocolJson.decodeFromString<GroupRejoinReply>(s).also { r ->
                require(r.candidates == 2 && !r.exhausted && r.op!!.opId == r.group.pending.single().opId)
                // The only admin's new device waits for a member's re-add: no reset.
                val now = lk.codegen.risime.data.mls.RejoinRules.epochMs(r.op!!.createdAt)!! + 25L * 3600_000
                require(r.group.myRole == GroupMember.ROLE_ADMIN && r.group.members.count { it.admin } == 1)
                require(lk.codegen.risime.data.mls.RejoinRules.group(true, r.candidates, r.exhausted, lk.codegen.risime.data.mls.RejoinRules.epochMs(r.op!!.createdAt), now) == lk.codegen.risime.data.mls.RejoinDecision.WAIT)
            }
        },
        "mls_commit_request_dm_op.json" to { s ->
            ProtocolJson.decodeFromString<MlsCommitRequest>(s).also { r ->
                require(r.opId != null && r.welcome != null && r.added.size == 1 && r.removed.isEmpty())
                require(ProtocolJson.encodeToJsonElement(r) == ProtocolJson.parseToJsonElement(s)) // re-encodes exactly
            }
        },
        "mls_dm_rejoin_reply.json" to { s ->
            // v1.16 shape: no `exhausted` (= false).
            ProtocolJson.decodeFromString<DmRejoinReply>(s).also { r ->
                require(r.candidates == 1 && !r.exhausted && r.op!!.committer == null)
                require(lk.codegen.risime.data.mls.RejoinRules.dm(r.candidates, r.exhausted) == lk.codegen.risime.data.mls.RejoinDecision.WAIT)
            }
        },
        "mls_dm_rejoin_reply_v121.json" to { s ->
            ProtocolJson.decodeFromString<DmRejoinReply>(s).also { r ->
                require(r.candidates == 1 && !r.exhausted && r.op!!.opId == "5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e")
                require(lk.codegen.risime.data.mls.RejoinRules.dm(r.candidates, r.exhausted) == lk.codegen.risime.data.mls.RejoinDecision.WAIT)
            }
        },
        // v1.20 (§21 open sign-up).
        "auth_config_v120.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s).also { require(it.signupOpen) } },
        "signup_request.json" to { s -> ProtocolJson.decodeFromString<SignupRequest>(s) },
        "signup_reply.json" to { s -> ProtocolJson.decodeFromString<MeReply>(s).also { require(!it.user.phoneConfirmed && it.user.phoneVerified) } },
        "error_signup_required.json" to { s -> apiError(s, AuthErrors.SIGNUP_REQUIRED) },
        "error_signup_closed.json" to { s -> apiError(s, AuthErrors.SIGNUP_CLOSED) },
        "error_phone_taken.json" to { s -> apiError(s, AuthErrors.PHONE_TAKEN) },
        "error_signup_rate_limited.json" to { s -> apiError(s, AuthErrors.RATE_LIMITED) },
        "error_bad_request.json" to { s -> apiError(s, AuthErrors.BAD_REQUEST) },
        "friends_reply_v120.json" to { s ->
            ProtocolJson.decodeFromString<FriendsReply>(s).also { require(it.friends.single().phoneConfirmed && !it.incoming.single().phoneConfirmed) }
        },
        "signal_friend_v120.json" to { s ->
            ProtocolJson.decodeFromString<Signal>(s).also { require(it.kind == Signal.KIND_FRIEND) }
        },
        // v1.19 (§20 group calls with LiveKit): typed models, the envelopes through the strict validators.
        "call_member_payload.json" to { s -> (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Member).also { require(it.state == "joined") } },
        "call_offer_sfu_payload.json" to { s ->
            (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.SfuOffer).also {
                require(it.media == "audio" && lk.codegen.risime.calls.CallEnvelope.ringFor(it))
                require(lk.codegen.risime.calls.CallEnvelope.toJson(it) == ProtocolJson.parseToJsonElement(s)) // re-encodes exactly
            }
        },
        "call_signal_event_group.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).callSignal()!!.also { require(it.to == null && it.conversationId.startsWith("grp:") && it.ring && it.media == "audio") }
        },
        "call_signal_push_group.json" to { s -> ProtocolJson.decodeFromString<CallSignalPush>(s).also { require(it.to == null && it.conversationId!!.startsWith("grp:") && it.media == "audio") } },
        "calls_room_reply.json" to { s ->
            ProtocolJson.decodeFromString<CallsRoomReply>(s).also { require(it.maxParticipants == 32 && it.identity.contains('/') && !it.toString().contains(it.token)) }
        },
        "calls_room_request.json" to { s -> ProtocolJson.decodeFromString<CallsRoomRequest>(s).also { require(it.action == CallsRoomRequest.START && it.media == "audio") } },
        "calls_room_status_reply.json" to { s -> ProtocolJson.decodeFromString<CallsRoomStatusReply>(s).also { require(it.active && it.participants == 3) } },
        "device_put_group_calls.json" to { s ->
            ProtocolJson.decodeFromString<DevicePut>(s).also { d ->
                val caps = d.mls!!.capabilities!!
                require(DeviceMls.CAP_GROUP_CALLS in caps && listOf(DeviceMls.CAP_GROUPS, DeviceMls.CAP_CALLS, DeviceMls.CAP_VIDEO).all { it in caps })
            }
        },
        "error_call_ended.json" to { s -> apiError(s, CallErrors.CALL_ENDED) },
        "error_call_full.json" to { s -> apiError(s, CallErrors.CALL_FULL) },
        "group_call_ended_payload.json" to { s ->
            groupCall(s).also { require(it.state == "ended" && it.reason == "hangup" && it.durationS == 723L && it.connectedAt != null) }
        },
        "group_call_started_payload.json" to { s -> groupCall(s).also { require(it.state == "started" && it.reason == null) } },
        // Server-side claims (the app never decodes a LiveKit token): checked for the grants the app relies on.
        "livekit_token_claims.json" to { s ->
            (ProtocolJson.parseToJsonElement(s) as JsonObject).also { o ->
                val v = o["video"]!!.jsonObject
                require(v["canPublishData"]!!.jsonPrimitive.boolean.not() && v["canSubscribe"]!!.jsonPrimitive.boolean)
                require(o["exp"]!!.jsonPrimitive.int - o["nbf"]!!.jsonPrimitive.int == 600)
            }
        },
        "mls_group_group_calls_ready.json" to { s -> ProtocolJson.decodeFromString<MlsGroup>(s).also { require(it.groupCallsReady && it.missingGroupCalls.single().deviceId != null) } },
        // v1.33 §20.1: no LiveKit on the server → not ready, and why.
        "mls_group_group_calls_unavailable.json" to { s -> ProtocolJson.decodeFromString<MlsGroup>(s).also { require(!it.groupCallsReady && it.groupCallsUnavailable == "server") } },
        // v1.18 (§19 1:1 video calls): typed models, the envelopes through the strict validators (§19.4 SDP rules).
        "call_end_video_payload.json" to { s -> (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.End).also { require(it.media == "video" && it.durationS == 312L) } },
        "call_media_payload.json" to { s -> (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Media).also { require(!it.camera && it.toDevice.isNotEmpty()) } },
        "call_offer_video_payload.json" to { s ->
            (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Offer).also { require(it.media == "video" && !it.restart && lk.codegen.risime.calls.SdpRules.mLineCount(it.sdp) == 2) }
        },
        "call_offer_video_payload_bad.json" to { s -> require(lk.codegen.risime.calls.CallEnvelope.decode(s.toByteArray()) == null) { "the simulcast offer must be dropped" }; s },
        "call_signal_event_video.json" to { s -> ProtocolJson.decodeFromString<Event>(s).callSignal()!!.also { require(it.ring && it.media == "video") } },
        "call_signal_push_video.json" to { s -> ProtocolJson.decodeFromString<CallSignalPush>(s).also { require(it.ring && it.media == "video") } },
        "device_put_video.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s).also { require(DeviceMls.CAP_VIDEO in it.mls!!.capabilities!! && DeviceMls.CAP_CALLS in it.mls!!.capabilities!!) } },
        "error_video_not_ready.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s).also { require(it.reason == CallErrors.VIDEO_NOT_READY) } },
        "mls_group_video_ready.json" to { s -> ProtocolJson.decodeFromString<MlsGroup>(s).also { require(it.videoReady && it.callsReady && it.missingVideo.single().deviceId != null) } },
        // v1.17 (§18 profile photos): typed models, the envelopes through the strict validators.
        "blob_upload_avatar_reply.json" to { s -> ProtocolJson.decodeFromString<BlobUploadReply>(s).also { require(it.expiresAt == null && it.size == 61456L) } },
        "event_message_silent.json" to { s -> ProtocolJson.decodeFromString<Event>(s).messageData()!!.also { require(it.silent && it.encrypted) } },
        "msg_send_silent.json" to { s ->
            ProtocolJson.decodeFromString<MsgSendE2ee>(s).also {
                require(it.silent == true)
                // Round trip: `silent` is sent exactly as the example has it, and omitted when unset.
                require(ProtocolJson.encodeToJsonElement(it) == ProtocolJson.parseToJsonElement(s))
                require("silent" !in ProtocolJson.encodeToJsonElement(it.copy(silent = null)).jsonObject)
            }
        },
        "profile_photo_payload.json" to { s ->
            (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.ProfilePhoto).env.also {
                require(it.ver == 1791450724000L && it.photo!!.w == 512 && it.photo!!.enc.key.size == 32 && it.photo!!.blob.size == 61456L)
                // Re-encoding gives the same envelope (what a device re-sends).
                require(lk.codegen.risime.data.profile.ProfilePhotoEnvelope.validate(ProtocolJson.parseToJsonElement(String(it.encode())) as JsonObject) == it)
            }
        },
        "profile_photo_payload_bad.json" to { s ->
            (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.Ignored).also { require(it.type.startsWith("profile_photo")) }
        },
        "profile_photo_payload_removed.json" to { s ->
            (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.ProfilePhoto).env.also { require(it.photo == null && it.ver == 1791454324000L) }
        },
        // v1.15 (§17 history sharing): typed models, the envelopes through the strict validators.
        "blob_upload_history_reply.json" to { s -> ProtocolJson.decodeFromString<BlobUploadReply>(s).also { requireNotNull(it.expiresAt) } },
        "blob_usage_reply_history.json" to { s -> ProtocolJson.decodeFromString<BlobUsageReply>(s).also { require(it.history!!.used < it.history!!.limit && it.history!!.hourlyLimit == 40) } },
        "device_put_history_share.json" to { s ->
            ProtocolJson.decodeFromString<DevicePut>(s).also { require(DeviceMls.CAP_HISTORY_SHARE in it.mls!!.capabilities!!) }
        },
        "error_request_open.json" to { s -> ProtocolJson.decodeFromString<HistoryRequestOpenError>(s).also { require(it.reason == "request_open" && it.requestId != null) } },
        "event_history_request.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).historyRequest()!!.also {
                require(it.consent == HistoryRequestEvent.CONSENT_OWN && it.intervals.size == 1 && it.toDevices.size == 1 && it.gapCount == 412 && it.expiresAt != null)
            }
        },
        "event_history_request_closed.json" to { s -> ProtocolJson.decodeFromString<Event>(s).historyRequestClosed()!!.also { require(it.reason == "accepted_elsewhere") } },
        "event_history_share.json" to { s -> ProtocolJson.decodeFromString<Event>(s).historyShare()!!.also { require(it.part == 1 && it.parts == 1 && it.toDevices.size == 1) } },
        "event_history_status.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).historyStatus()!!.also { require(it.state == HistoryState.ACCEPTED && it.provider != null) }
        },
        "event_history_status_refresh.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).historyStatus()!!.also { require(it.state == HistoryState.REFRESH && it.provider == null) }
        },
        "history_ack.json" to { s -> ProtocolJson.decodeFromString<HistoryAck>(s).also { require(it.result == HistoryAck.IMPORTED) } },
        "history_bundle_entry.json" to { s ->
            ProtocolJson.decodeFromString<HistoryBundleEntry>(s).also {
                require(it.fromDevice == null && lk.codegen.risime.data.mls.MlsPayload.decode(it.payload.toString().toByteArray()) is lk.codegen.risime.data.mls.MlsPayload.Decoded.Text)
            }
        },
        "history_bundle_header.json" to { s -> ProtocolJson.decodeFromString<HistoryBundleHeader>(s).also { require(it.type == HistoryBundleHeader.TYPE && it.count == 398) } },
        "history_cancel.json" to { s -> ProtocolJson.decodeFromString<HistoryRequestRef>(s) },
        "history_deliver.json" to { s -> ProtocolJson.decodeFromString<HistoryDeliver>(s).also { require(it.part == 1 && it.parts == 1) } },
        "history_escalate.json" to { s -> ProtocolJson.decodeFromString<HistoryRequestRef>(s) },
        "history_refresh.json" to { s -> ProtocolJson.decodeFromString<HistoryRefresh>(s).also { require(it.epoch == 23L) } },
        "history_request_payload.json" to { s ->
            (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.HistoryRequest).env.also { require(it.rpk.size == 32 && it.gapCount == 412) }
        },
        "history_request_push.json" to { s -> ProtocolJson.decodeFromString<HistoryRequestPush>(s).also { require(it.sources == HistoryRequestPush.SOURCES_ANY) } },
        "history_request_reply.json" to { s -> ProtocolJson.decodeFromString<HistoryRequestReply>(s).also { require(it.state == HistoryState.SEARCHING && it.ownDevices.single().deviceName == "Pixel 8") } },
        "history_respond.json" to { s -> ProtocolJson.decodeFromString<HistoryRespond>(s).also { require(it.decision == HistoryRespond.ACCEPT && it.reason == null) } },
        "history_respond_stale.json" to { s -> ProtocolJson.decodeFromString<HistoryRespond>(s).also { require(it.decision == HistoryRespond.UNABLE && it.reason == HistoryRespond.REASON_STALE) } },
        "history_share_payload.json" to { s ->
            (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.HistoryShare).env.also {
                require(it.hpkeEnc.size == 32 && it.sealedKey.size == 48 && it.count == 398 && it.blob.size == 1_048_832L && it.plainSize == 1_040_000L)
            }
        },
        "contacts_reply.json" to { s -> ProtocolJson.decodeFromString<ContactsReply>(s) },
        "event_message.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.messageData()) } },
        "event_status.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.statusData()) } },
        "join_reply.json" to { s -> ProtocolJson.decodeFromString<EventsPage>(s) },
        "msg_send.json" to { s -> ProtocolJson.decodeFromString<MsgSend>(s) },
        "msg_send_reply.json" to { s -> ProtocolJson.decodeFromString<MsgSendReply>(s) },
        "presence_watch.json" to { s -> ProtocolJson.decodeFromString<PresenceWatch>(s) },
        "presence_watch_reply.json" to { s -> ProtocolJson.decodeFromString<PresenceWatchReply>(s) },
        "signal_presence.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.presence()) } },
        "signal_typing.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.typing()) } },
        "typing.json" to { s -> ProtocolJson.decodeFromString<TypingPush>(s) },
        "auth_config.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s) },
        "error_not_allowlisted.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_invalid_token.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_identity_conflict.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "auth_refresh.json" to { s -> ProtocolJson.decodeFromString<AuthRefresh>(s) },
        "auth_refresh_reply.json" to { s -> ProtocolJson.decodeFromString<AuthRefreshReply>(s) },
        "auth_refresh_error.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s) },
        "auth_config_v14.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s) },
        "me_reply_unverified.json" to { s -> ProtocolJson.decodeFromString<MeReply>(s) },
        "phone_verify_request_reply.json" to { s -> ProtocolJson.decodeFromString<PhoneVerifyRequestReply>(s) },
        "phone_verify_confirm.json" to { s -> ProtocolJson.decodeFromString<PhoneVerifyConfirm>(s) },
        "error_phone_unverified.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_invalid_code_attempts.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_already_verified.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_sms_unavailable.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "device_put.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s) },
        "push_inbox.json" to { s -> ProtocolJson.decodeFromString<PushPayload>(s) },
        "error_invalid_device.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "invite_create.json" to { s -> ProtocolJson.decodeFromString<InviteCreate>(s) },
        "invite_reply.json" to { s -> ProtocolJson.decodeFromString<InviteReply>(s) },
        "invites_reply.json" to { s -> ProtocolJson.decodeFromString<InvitesReply>(s) },
        "friend_request.json" to { s -> ProtocolJson.decodeFromString<FriendRequestCreate>(s) },
        "friend_request_reply.json" to { s -> ProtocolJson.decodeFromString<FriendRequestReply>(s) },
        "friends_reply.json" to { s -> ProtocolJson.decodeFromString<FriendsReply>(s) },
        "friend_accept_reply.json" to { s -> ProtocolJson.decodeFromString<FriendAcceptReply>(s) },
        "block.json" to { s -> ProtocolJson.decodeFromString<BlockCreate>(s) },
        "signal_friend.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.friend()) } },
        "error_not_friends.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s) },
        "user_vouched.json" to { s -> ProtocolJson.decodeFromString<MeReply>(s) },
        "device_put_mls.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s).also { requireNotNull(it.mls) } },
        "device_put_mls_reply.json" to { s -> ProtocolJson.decodeFromString<DevicePutReply>(s) },
        "attestation_keys.json" to { s -> ProtocolJson.decodeFromString<AttestationKeys>(s) },
        "key_packages_upload.json" to { s -> ProtocolJson.decodeFromString<KeyPackagesUpload>(s) },
        "key_packages_count.json" to { s -> ProtocolJson.decodeFromString<KeyPackageCount>(s) },
        "key_packages_claim.json" to { s -> ProtocolJson.decodeFromString<KeyPackagesClaim>(s) },
        "key_packages_claim_reply.json" to { s -> ProtocolJson.decodeFromString<KeyPackagesClaimReply>(s) },
        "mls_group.json" to { s -> ProtocolJson.decodeFromString<MlsGroup>(s) },
        "mls_commit_request.json" to { s -> ProtocolJson.decodeFromString<MlsCommitRequest>(s) },
        "mls_commit_reply.json" to { s -> ProtocolJson.decodeFromString<MlsCommitReply>(s) },
        "mls_commits_reply.json" to { s -> ProtocolJson.decodeFromString<MlsCommitsReply>(s) },
        "error_epoch_conflict.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_not_ready.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "msg_send_e2ee.json" to { s -> ProtocolJson.decodeFromString<MsgSendE2ee>(s) },
        "event_message_e2ee.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.messageData()?.ciphertext) } },
        "event_mls_commit.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.mlsCommit()) } },
        "event_mls_welcome.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.mlsWelcome()) } },
        "event_mls_membership.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.mlsMembership()) } },
        "signal_mls_key_packages_low.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.keyPackagesLow()) } },
        "error_e2ee_required.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s) },
        "reaction_payload.json" to { s ->
            (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.Reaction)
        },
        "msg_send_reaction.json" to { s -> ProtocolJson.decodeFromString<MsgSendReaction>(s) },
        "msg_send_reaction_and_body.json" to { s -> ProtocolJson.parseToJsonElement(s).jsonObject.also { require("body" in it && "reaction" in it) } },
        "event_reaction.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.reaction()) } },
        "error_unknown_target.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s) },
        "error_invalid_emoji.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s) },
        "limits_graphemes.json" to { s -> ProtocolJson.parseToJsonElement(s).jsonObject.also { require("cases" in it) } },
        // v1.9 (MLS groups, §12): typed decoders into the app's models.
        "group_create.json" to { s -> ProtocolJson.decodeFromString<GroupCreate>(s) },
        "group_reply.json" to { s -> ProtocolJson.decodeFromString<GroupReply>(s).also { g -> require(g.group.pending.single().committer != null && g.group.members.any { it.state == GroupMember.STATE_PENDING_ADD }) } },
        "groups_reply.json" to { s -> ProtocolJson.decodeFromString<GroupsReply>(s).also { require(it.groups.isNotEmpty()) } },
        "group_members_add.json" to { s -> ProtocolJson.decodeFromString<GroupMembersAdd>(s) },
        "group_role_patch.json" to { s -> ProtocolJson.decodeFromString<GroupRolePatch>(s) },
        "group_reset.json" to { s -> ProtocolJson.decodeFromString<GroupReset>(s) },
        "group_reset_reply.json" to { s -> ProtocolJson.decodeFromString<GroupResetReply>(s) },
        "group_meta.json" to { s -> requireNotNull(GroupMeta.decode(s.toByteArray())).also { require(it.admins.isNotEmpty()) } },
        "group_receipts_reply.json" to { s -> ProtocolJson.decodeFromString<GroupReceiptsReply>(s) },
        "blob_upload_reply.json" to { s -> ProtocolJson.decodeFromString<BlobUploadReply>(s) },
        "device_put_groups.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s).also { require(it.mls?.capabilities == listOf(DeviceMls.CAP_GROUPS)) } },
        "key_packages_upload_replace.json" to { s -> ProtocolJson.decodeFromString<KeyPackagesUpload>(s).also { require(it.replace == true) } },
        "key_packages_claim_group.json" to { s -> ProtocolJson.decodeFromString<KeyPackagesClaim>(s).also { requireNotNull(it.conversationId) } },
        "mls_commit_request_group.json" to { s -> ProtocolJson.decodeFromString<GroupCommitRequest>(s).also { requireNotNull(it.welcomeRef) } },
        "mls_commits_reply_paged.json" to { s -> ProtocolJson.decodeFromString<MlsCommitsReply>(s).also { require(it.hasMore && it.commits[1].commitRef != null) } },
        "friends_reply_v19.json" to { s -> ProtocolJson.decodeFromString<FriendsReply>(s) },
        "msg_send_group.json" to { s -> ProtocolJson.decodeFromString<MsgSendGroup>(s) },
        "msg_send_group_reply.json" to { s -> ProtocolJson.decodeFromString<MsgSendReply>(s) },
        "typing_group.json" to { s -> ProtocolJson.decodeFromString<TypingGroupPush>(s) },
        "signal_typing_group.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { require(isGroupConversation(it.typing()!!.conversationId)) } },
        "event_message_group.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { require(it.messageData()!!.to == null) } },
        "event_mls_commit_group_ref.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.mlsCommit()!!.commitRef) } },
        "event_mls_welcome_ref.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.mlsWelcome()!!.welcomeRef) } },
        "event_group_created.json" to { s -> groupEvent(s, GroupEvent.CREATED).also { requireNotNull(it.members) } },
        "event_group_added.json" to { s -> groupEvent(s, GroupEvent.ADDED) },
        "event_group_removed.json" to { s -> groupEvent(s, GroupEvent.REMOVED) },
        "event_group_left.json" to { s -> groupEvent(s, GroupEvent.LEFT) },
        "event_group_role_changed.json" to { s -> groupEvent(s, GroupEvent.ROLE_CHANGED).also { requireNotNull(it.role) } },
        "event_group_metadata_changed.json" to { s -> groupEvent(s, GroupEvent.METADATA_CHANGED) },
        "event_group_add_expired.json" to { s -> groupEvent(s, GroupEvent.ADD_EXPIRED) },
        "event_group_reset.json" to { s -> groupEvent(s, GroupEvent.RESET).also { requireNotNull(it.rebuilder); require(it.epoch == null) } },
        "event_group_op.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.groupOp()!!.op.committer) } },
        "event_group_receipt.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.groupReceipt()) } },
        "error_not_member.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s).also { require(it.reason == AuthErrors.NOT_MEMBER) } },
        "error_not_admin.json" to { s -> apiError(s, AuthErrors.NOT_ADMIN) },
        "error_too_many_members.json" to { s -> apiError(s, AuthErrors.TOO_MANY_MEMBERS) },
        "error_too_many_devices.json" to { s -> apiError(s, AuthErrors.TOO_MANY_DEVICES) },
        "error_last_admin.json" to { s -> apiError(s, AuthErrors.LAST_ADMIN) },
        "error_log_expired.json" to { s -> apiError(s, AuthErrors.LOG_EXPIRED) },
        "error_generation_conflict.json" to { s -> apiError(s, AuthErrors.GENERATION_CONFLICT).also { requireNotNull(it.error.generation) } },
        "error_not_ready_groups.json" to { s -> apiError(s, AuthErrors.NOT_READY).also { require(it.error.missing!!.single().reason == MlsMissing.LEGACY_APP) } },
        // v1.10 (history, §13)
        "inbox_join_reply_v110.json" to { s -> ProtocolJson.decodeFromString<EventsPage>(s).also { requireNotNull(it.historyBefore) } },
        "event_message_sender_copy.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).also { e -> val m = e.messageData()!!; require(m.messageId == e.eventId && m.body != null && !m.encrypted) }
        },
        "error_quota_exceeded.json" to { s -> apiError(s, "quota_exceeded").also { require(it.error.used!! < it.error.limit!!) } },
        // v1.11 (encrypted images, §14)
        "image_payload.json" to { s -> imageEnvelope(s).also { require(it.thumb != null && it.caption != null && it.mime == "image/jpeg") } },
        "image_payload_no_thumb.json" to { s -> imageEnvelope(s).also { require(it.thumb == null && it.caption == null) } },
        "image_payload_png.json" to { s -> imageEnvelope(s).also { require(it.mime == "image/png") } },
        // Malformed (31-byte key): must be dropped, never stored.
        "image_payload_bad_key.json" to { s ->
            require(lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) is lk.codegen.risime.data.mls.MlsPayload.Decoded.Ignored)
        },
        "group_meta_icon.json" to { s -> GroupMeta.decode(s.toByteArray())!!.also { require(it.icon is JsonObject && it.name.isNotEmpty() && lk.codegen.risime.data.profile.PhotoRef.groupIcon(it.icon)!!.w == 512) } },
        "blob_upload_media_reply.json" to { s -> ProtocolJson.decodeFromString<BlobUploadReply>(s).also { requireNotNull(it.expiresAt) } },
        "blob_usage_reply.json" to { s -> ProtocolJson.decodeFromString<BlobUsageReply>(s).also { require(it.media.used < it.media.limit && it.mls != null) } },
        "device_put_images.json" to { s ->
            ProtocolJson.decodeFromString<DevicePut>(s).also { require(it.mls?.capabilities == listOf(DeviceMls.CAP_GROUPS, DeviceMls.CAP_IMAGES)) }
        },
        "mls_group_images_ready.json" to { s -> ProtocolJson.decodeFromString<MlsGroup>(s).also { require(!it.imagesReady && it.missingImages.size == 1) } },
        "error_not_e2ee.json" to { s -> apiError(s, AuthErrors.NOT_E2EE) },
        "error_storage_full.json" to { s -> apiError(s, AuthErrors.STORAGE_FULL) },
        "error_bad_media_type.json" to { s -> apiError(s, AuthErrors.BAD_MEDIA_TYPE) },
        // v1.12 (deleting messages and chats, §15)
        "delete_payload.json" to { s ->
            (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.Delete).also { require(it.targets.size == 2) }
        },
        // 101 targets: must be dropped as malformed (§15.3).
        "delete_payload_bad.json" to { s ->
            require(lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) is lk.codegen.risime.data.mls.MlsPayload.Decoded.Ignored)
        },
        "msg_delete_everyone_group.json" to { s ->
            ProtocolJson.decodeFromString<MsgDelete>(s).also { require(it.scope == MsgDelete.SCOPE_EVERYONE && it.ciphertext != null && it.blobIds!!.size == 1 && it.epoch == 4L) }
        },
        "msg_delete_everyone_dm.json" to { s ->
            ProtocolJson.decodeFromString<MsgDelete>(s).also { require(it.ciphertext == null && it.blobIds == null && it.clientTs != null) }
        },
        "msg_delete_me.json" to { s -> ProtocolJson.decodeFromString<MsgDelete>(s).also { require(it.scope == MsgDelete.SCOPE_ME && it.clientTs == null) } },
        "msg_delete_reply.json" to { s -> ProtocolJson.decodeFromString<MsgDeleteReply>(s).also { require(it.messageId != null && it.deleted.size == 1 && it.gone.size == 1) } },
        "msg_delete_reply_gone.json" to { s -> ProtocolJson.decodeFromString<MsgDeleteReply>(s).also { require(it.messageId == null && it.serverTs == null && it.deleted.isEmpty()) } },
        "event_delete_group.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).deleteData()!!.also { d ->
                require(d.encrypted && d.to == null && d.fromDevice != null && d.targets.size == 2 && d.targets[1].from == null && d.targets[1].serverTs == null)
            }
        },
        "event_delete_dm.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).deleteData()!!.also { d -> require(!d.encrypted && d.to != null && d.fromDevice == null && d.targets.single().from != null) }
        },
        "event_delete_dm_e2ee.json" to { s ->
            ProtocolJson.decodeFromString<Event>(s).deleteData()!!.also { d -> require(d.encrypted && d.to != null && d.fromDevice != null && d.generation == 1L && d.epoch == 1L) }
        },
        "error_delete_too_old.json" to { s ->
            ProtocolJson.decodeFromString<DeleteError>(s).also { require(it.reason == AuthErrors.TOO_OLD && it.failures.map { f -> f.reason } == listOf(AuthErrors.TOO_OLD, AuthErrors.NOT_ADMIN)) }
        },
        "error_not_sender.json" to { s -> ProtocolJson.decodeFromString<DeleteError>(s).also { require(it.reason == AuthErrors.NOT_SENDER && it.failures.size == 1) } },
        "chat_clear.json" to { s -> ProtocolJson.decodeFromString<ChatClear>(s).also { require(it.conversationId.startsWith("dm:")) } },
        "device_put_deletes.json" to { s ->
            ProtocolJson.decodeFromString<DevicePut>(s).also { require(it.mls?.capabilities == listOf(DeviceMls.CAP_GROUPS, DeviceMls.CAP_IMAGES, DeviceMls.CAP_DELETES)) }
        },
        "mls_group_deletes_ready.json" to { s ->
            ProtocolJson.decodeFromString<MlsGroup>(s).also { require(!it.deletesReady && it.missingDeletes.single().deviceId != null && it.imagesReady) }
        },
        // v1.13 (1:1 voice calls, §16): the MLS envelopes through the strict decoder, the rest typed.
        "call_offer_payload.json" to { s -> callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Offer },
        "call_offer_payload_bad.json" to { s -> require(lk.codegen.risime.calls.CallEnvelope.decode(s.toByteArray()) == null) { "the bad offer must be dropped" }; s },
        "call_ringing_payload.json" to { s -> callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Ringing },
        "call_answer_payload.json" to { s -> callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Answer },
        "call_accepted_payload.json" to { s -> callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Accepted },
        "call_ice_payload.json" to { s -> (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Ice).also { require(it.candidates.size == 2 && it.toDevice == null) } },
        "call_busy_payload.json" to { s -> callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Busy },
        "call_cancel_payload.json" to { s -> (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.Cancel).also { require(it.reason == "glare") } },
        "call_end_payload.json" to { s -> (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.End).also { require(it.durationS == 192L && it.connectedAt != null) } },
        "call_end_missed_payload.json" to { s -> (callEnv(s) as lk.codegen.risime.calls.CallEnvelope.End).also { require(it.reason == "timeout" && it.durationS == null) } },
        "call_signal_push.json" to { s -> ProtocolJson.decodeFromString<CallSignalPush>(s).also { require(it.ring) } },
        "call_signal_reply.json" to { s -> ProtocolJson.decodeFromString<CallSignalReply>(s) },
        "call_signal_event.json" to { s -> ProtocolJson.decodeFromString<Event>(s).callSignal()!!.also { require(it.ring && it.fromDevice.isNotEmpty()) } },
        "calls_turn_reply.json" to { s -> ProtocolJson.decodeFromString<CallsTurnReply>(s).also { require(it.ttl == 18_000L && it.iceServers.size == 2 && it.iceServers[1].credential != null) } },
        "push_call.json" to { s -> ProtocolJson.decodeFromString<PushPayload>(s).also { require(it.isCall && !it.isInbox) } },
        "device_put_calls.json" to { s ->
            ProtocolJson.decodeFromString<DevicePut>(s).also { require(it.mls?.capabilities == listOf(DeviceMls.CAP_GROUPS, DeviceMls.CAP_IMAGES, DeviceMls.CAP_DELETES, DeviceMls.CAP_CALLS)) }
        },
        "mls_group_calls_ready.json" to { s -> ProtocolJson.decodeFromString<MlsGroup>(s).also { require(it.callsReady && it.missingCalls.single().deviceId != null) } },
        "error_calls_unavailable.json" to { s -> apiError(s, CallErrors.CALLS_UNAVAILABLE) },
        "error_calls_not_ready.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s).also { require(it.reason == CallErrors.CALLS_NOT_READY) } },
        // v1.14 (§12.1, §12.4a): what this app sends with a core that reports `member_devices`.
        "device_put_member_devices.json" to { s ->
            ProtocolJson.decodeFromString<DevicePut>(s).also {
                require(it.mls?.capabilities == listOf(DeviceMls.CAP_GROUPS, DeviceMls.CAP_IMAGES, DeviceMls.CAP_DELETES, DeviceMls.CAP_CALLS, DeviceMls.CAP_MEMBER_DEVICES))
            }
        },
    )

    /** §20.4 a `group_call` through the app's front door ([MlsPayload.decode]), re-encoded to exactly the example. */
    private fun groupCall(s: String): lk.codegen.risime.calls.GroupCallEnvelope =
        (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.GroupCall).env.also {
            require(it.toJson() == ProtocolJson.parseToJsonElement(s)) { "re-encode" }
        }

    private fun callEnv(s: String): lk.codegen.risime.calls.CallEnvelope.Env =
        requireNotNull(lk.codegen.risime.calls.CallEnvelope.decode(s.toByteArray())) { "call envelope dropped" }

    private fun groupEvent(s: String, action: String): GroupEvent =
        ProtocolJson.decodeFromString<Event>(s).groupEvent()!!.also { require(it.action == action) { "action ${it.action} != $action" } }

    private fun imageEnvelope(s: String): lk.codegen.risime.data.media.ImageEnvelope =
        (lk.codegen.risime.data.mls.MlsPayload.decode(s.toByteArray()) as lk.codegen.risime.data.mls.MlsPayload.Decoded.Image).envelope

    private fun apiError(s: String, code: String): ApiErrorEnvelope =
        ProtocolJson.decodeFromString<ApiErrorEnvelope>(s).also { require(it.error.code == code) }

    /** v1.9 client-sent payloads re-encode to exactly the example JSON. */
    @Test
    fun groupClientPayloadsRoundTrip() {
        fun <T> check(name: String, ser: kotlinx.serialization.KSerializer<T>) {
            val original = ProtocolJson.parseToJsonElement(read(name))
            assertEquals(name, original, ProtocolJson.encodeToJsonElement(ser, ProtocolJson.decodeFromJsonElement(ser, original)))
        }
        check("group_create.json", GroupCreate.serializer())
        check("group_members_add.json", GroupMembersAdd.serializer())
        check("group_role_patch.json", GroupRolePatch.serializer())
        check("group_reset.json", GroupReset.serializer())
        check("msg_send_group.json", MsgSendGroup.serializer())
        check("typing_group.json", TypingGroupPush.serializer())
        check("mls_commit_request_group.json", GroupCommitRequest.serializer())
        check("device_put_groups.json", DevicePut.serializer())
        check("device_put_images.json", DevicePut.serializer())
        // v1.12 client-sent payloads (§15.2, §15.9, §15.1).
        check("msg_delete_everyone_group.json", MsgDelete.serializer())
        check("msg_delete_everyone_dm.json", MsgDelete.serializer())
        check("msg_delete_me.json", MsgDelete.serializer())
        check("chat_clear.json", ChatClear.serializer())
        check("device_put_deletes.json", DevicePut.serializer())
        // v1.13 client-sent payloads (§16.1, §16.3).
        check("device_put_calls.json", DevicePut.serializer())
        // v1.14 (§12.1).
        check("device_put_member_devices.json", DevicePut.serializer())
        check("call_signal_push.json", CallSignalPush.serializer())
        // v1.18 (§19.2): `media` on every signal of a video call; the voice shape above stays without it.
        check("call_signal_push_video.json", CallSignalPush.serializer())
        // v1.19 (§20.2, §20.3): the group signal (`conversation_id`, no `to`), the room request, `group_calls`.
        check("call_signal_push_group.json", CallSignalPush.serializer())
        check("calls_room_request.json", CallsRoomRequest.serializer())
        check("device_put_group_calls.json", DevicePut.serializer())
        check("device_put_video.json", DevicePut.serializer())
        check("key_packages_upload_replace.json", KeyPackagesUpload.serializer())
        check("key_packages_claim_group.json", KeyPackagesClaim.serializer())
        check("group_meta.json", GroupMeta.serializer())
        // v1.24 (§24.1): the Official meta the app writes at epoch 0 (and a pre-v1.24 meta stays without the new keys).
        check("group_meta_official.json", GroupMeta.serializer())
        // v1.22 (§22): the commit body and every bundle line the app writes.
        check("backup_create_request.json", BackupCreateRequest.serializer())
        check("backup_bundle_header.json", lk.codegen.risime.data.backup.BackupBundleHeader.serializer())
        check("backup_entry_conversation.json", lk.codegen.risime.data.backup.BackupConversationLine.serializer())
        check("backup_entry_message.json", lk.codegen.risime.data.backup.BackupMessageLine.serializer())
        check("backup_entry_tombstone.json", lk.codegen.risime.data.backup.BackupTombstoneLine.serializer())
        check("backup_entry_group_event.json", lk.codegen.risime.data.backup.BackupGroupEventLine.serializer())
        check("backup_entry_contact.json", lk.codegen.risime.data.backup.BackupContactLine.serializer())
        // The v1.7 shapes stay exactly as they were (no new fields when unused).
        check("device_put_mls.json", DevicePut.serializer())
        check("key_packages_upload.json", KeyPackagesUpload.serializer())
        check("key_packages_claim.json", KeyPackagesClaim.serializer())
    }

    /** The v1.29 decoders hold for the Android fixtures of the same names (until the contract examples land). */
    @Test
    fun calendarV129DecodersAcceptTheFixtures() {
        var n = 0
        for ((name, decode) in calendarV129) {
            val s = javaClass.classLoader!!.getResource("fixtures/risi_calendar/$name")?.readText() ?: continue
            assertNotNull(name, decode(s))
            n++
        }
        assertEquals(calendarV129.size, n)
    }

    /** The §30 decoders hold for the Android fixtures of the same names (until the contract examples land). */
    @Test
    fun notesV130DecodersAcceptTheFixtures() {
        for ((name, decode) in notesV130) {
            val s = javaClass.classLoader!!.getResource("fixtures/risi_notes/$name")?.readText() ?: throw AssertionError("missing fixture $name")
            assertNotNull(name, decode(s))
        }
    }

    @Test
    fun everyExampleParses() {
        assertTrue("no contract examples found", all.isNotEmpty())
        for (name in all) {
            val decode = decoders[name] ?: throw AssertionError("no decoder for contract example $name")
            assertNotNull(name, decode(read(name)))
        }
    }

    /** §17: every client-sent history payload and both envelopes re-encode to the example exactly. */
    @Test
    fun historyPayloadsRoundTrip() {
        fun <T> check(name: String, ser: kotlinx.serialization.KSerializer<T>) {
            val original = ProtocolJson.parseToJsonElement(read(name))
            assertEquals(name, original, ProtocolJson.encodeToJsonElement(ser, ProtocolJson.decodeFromJsonElement(ser, original)))
        }
        check("history_request_push.json", HistoryRequestPush.serializer())
        check("history_refresh.json", HistoryRefresh.serializer())
        check("history_respond.json", HistoryRespond.serializer())
        check("history_respond_stale.json", HistoryRespond.serializer())
        check("history_deliver.json", HistoryDeliver.serializer())
        check("history_ack.json", HistoryAck.serializer())
        check("history_escalate.json", HistoryRequestRef.serializer())
        check("history_cancel.json", HistoryRequestRef.serializer())
        check("history_bundle_header.json", HistoryBundleHeader.serializer())
        check("history_bundle_entry.json", HistoryBundleEntry.serializer())
        for (name in listOf("history_request_payload.json", "history_share_payload.json")) {
            val bytes = read(name).toByteArray()
            val encoded = when (val d = lk.codegen.risime.data.mls.MlsPayload.decode(bytes)) {
                is lk.codegen.risime.data.mls.MlsPayload.Decoded.HistoryRequest -> d.env.encode()
                is lk.codegen.risime.data.mls.MlsPayload.Decoded.HistoryShare -> d.env.encode()
                else -> throw AssertionError("$name: $d")
            }
            assertEquals(name, ProtocolJson.parseToJsonElement(read(name)), ProtocolJson.parseToJsonElement(encoded.decodeToString()))
        }
    }

    /** Re-encoding a model yields the same JSON object as the example (field names match exactly). */
    @Test
    fun clientSentPayloadsRoundTrip() {
        val original = ProtocolJson.parseToJsonElement(read("msg_send.json")) as JsonObject
        val model = ProtocolJson.decodeFromJsonElement<MsgSend>(original)
        assertEquals(original, ProtocolJson.encodeToJsonElement(model))
    }

    @Test
    fun clientSentV12PayloadsRoundTrip() {
        for (name in listOf("presence_watch.json", "typing.json")) {
            val original = ProtocolJson.parseToJsonElement(read(name)) as JsonObject
            val encoded = when (name) {
                "typing.json" -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<TypingPush>(original))
                else -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<PresenceWatch>(original))
            }
            assertEquals(name, original, encoded)
        }
    }

    @Test
    fun presenceExamples() {
        val reply = ProtocolJson.decodeFromString<PresenceWatchReply>(read("presence_watch_reply.json"))
        val p = reply.presences.single()
        assertFalse(p.online)
        assertNotNull(p.lastSeen)
        val watch = ProtocolJson.decodeFromString<PresenceWatch>(read("presence_watch.json"))
        assertEquals(watch.userIds, listOf(p.userId))

        val sig = ProtocolJson.decodeFromString<Signal>(read("signal_presence.json"))
        assertEquals(Signal.KIND_PRESENCE, sig.kind)
        val live = sig.presence()!!
        assertTrue(live.online)
        assertEquals(null, live.lastSeen)
        assertEquals(null, sig.typing())
    }

    @Test
    fun typingExamples() {
        val t = ProtocolJson.decodeFromString<Signal>(read("signal_typing.json")).typing()!!
        assertTrue(t.typing)
        val push = ProtocolJson.decodeFromString<TypingPush>(read("typing.json"))
        assertEquals(dmConversationId(t.from, push.to), t.conversationId)
        assertTrue(push.typing)
    }

    @Test
    fun unknownSignalKindIsIgnored() {
        val s = ProtocolJson.decodeFromString<Signal>("""{"kind":"mood","data":{"x":1}}""")
        assertEquals(null, s.presence())
        assertEquals(null, s.typing())
    }

    @Test
    fun authV13Examples() {
        val cfg = ProtocolJson.decodeFromString<AuthConfig>(read("auth_config.json"))
        assertEquals(listOf(AuthConfig.MODE_OIDC, AuthConfig.MODE_DEV), cfg.modes)
        assertEquals("https://risicloud.ai/realms/aoa", cfg.issuer)
        assertEquals("risime", cfg.clientId)
        mapOf(
            "error_not_allowlisted.json" to AuthErrors.NOT_ALLOWLISTED,
            "error_invalid_token.json" to AuthErrors.INVALID_TOKEN,
            "error_identity_conflict.json" to AuthErrors.IDENTITY_CONFLICT,
        ).forEach { (file, code) ->
            val e = ProtocolJson.decodeFromString<ApiErrorEnvelope>(read(file)).error
            assertEquals(code, e.code)
            assertTrue(e.message.isNotBlank())
        }
        val refresh = ProtocolJson.parseToJsonElement(read("auth_refresh.json")) as JsonObject
        assertEquals(refresh, ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<AuthRefresh>(refresh)))
        assertTrue(ProtocolJson.decodeFromString<AuthRefreshReply>(read("auth_refresh_reply.json")).expiresAt.endsWith("Z"))
        assertEquals(AuthErrors.IDENTITY_MISMATCH, ProtocolJson.decodeFromString<ErrorReason>(read("auth_refresh_error.json")).reason)
        // A config with only "dev" has no issuer/client_id.
        val dev = ProtocolJson.decodeFromString<AuthConfig>("""{"modes":["dev"]}""")
        assertEquals(null, dev.issuer)
    }

    @Test
    fun openSignupV120Examples() {
        assertTrue(ProtocolJson.decodeFromString<AuthConfig>(read("auth_config_v120.json")).signupOpen)
        assertFalse(ProtocolJson.decodeFromString<AuthConfig>(read("auth_config.json")).signupOpen) // absent = invite
        val req = ProtocolJson.parseToJsonElement(read("signup_request.json")) as JsonObject
        assertEquals(req, ProtocolJson.encodeToJsonElement(SignupRequest("+94770000009", "Test User N")))
        val user = ProtocolJson.decodeFromString<MeReply>(read("signup_reply.json")).user
        assertFalse(user.phoneConfirmed)
        assertEquals("", user.company)
        // Absent phone_confirmed means true.
        assertTrue(ProtocolJson.decodeFromString<AuthVerifyReply>(read("auth_verify_reply.json")).user.phoneConfirmed)
        assertTrue(ProtocolJson.decodeFromString<FriendsReply>(read("friends_reply_v19.json")).friends.single().phoneConfirmed)
        val sig = ProtocolJson.decodeFromString<Signal>(read("signal_friend_v120.json"))
        val friend = ProtocolJson.decodeFromJsonElement<FriendSignal>(sig.data)
        assertFalse(friend.user.phoneConfirmed)
        assertTrue(ProtocolJson.decodeFromJsonElement<FriendSignal>(ProtocolJson.decodeFromString<Signal>(read("signal_friend.json")).data).user.phoneConfirmed)
        val r = ApiResult.Error(403, AuthErrors.SIGNUP_REQUIRED, ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_signup_required.json")).error.message)
        val o = lk.codegen.risime.data.auth.meOutcome(r) as lk.codegen.risime.data.auth.MeOutcome.Refused
        assertEquals(lk.codegen.risime.data.auth.BlockKind.SIGNUP_REQUIRED, o.blocked.kind)
    }

    @Test
    fun phoneVerificationV14Examples() {
        val cfg = ProtocolJson.decodeFromString<AuthConfig>(read("auth_config_v14.json"))
        assertTrue(cfg.phoneVerificationRequired)
        assertFalse(ProtocolJson.decodeFromString<AuthConfig>(read("auth_config.json")).phoneVerificationRequired) // absent = off

        val unverified = ProtocolJson.decodeFromString<MeReply>(read("me_reply_unverified.json")).user
        assertFalse(unverified.phoneVerified)
        // Absent phone_verified means true (pre-v1.4 servers).
        assertTrue(ProtocolJson.decodeFromString<AuthVerifyReply>(read("auth_verify_reply.json")).user.phoneVerified)

        val sent = ProtocolJson.decodeFromString<PhoneVerifyRequestReply>(read("phone_verify_request_reply.json"))
        assertEquals("sent", sent.status)
        assertEquals(300, sent.expiresIn)
        assertTrue(sent.to.contains('\u2022'))
        assertTrue(sent.to.endsWith("01"))

        val confirm = ProtocolJson.parseToJsonElement(read("phone_verify_confirm.json")) as JsonObject
        assertEquals(confirm, ProtocolJson.encodeToJsonElement(PhoneVerifyConfirm("123456")))

        mapOf(
            "error_phone_unverified.json" to AuthErrors.PHONE_UNVERIFIED,
            "error_already_verified.json" to AuthErrors.ALREADY_VERIFIED,
            "error_sms_unavailable.json" to AuthErrors.SMS_UNAVAILABLE,
            "error_invalid_code_attempts.json" to AuthErrors.INVALID_CODE,
        ).forEach { (file, code) ->
            val e = ProtocolJson.decodeFromString<ApiErrorEnvelope>(read(file)).error
            assertEquals(file, code, e.code)
            assertTrue(e.message.isNotBlank())
        }
        assertEquals(3, ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_invalid_code_attempts.json")).error.attemptsLeft)
        assertEquals(null, ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_sms_unavailable.json")).error.attemptsLeft)
    }

    @Test
    fun retryAfterParsing() {
        assertEquals(120L, parseRetryAfter("120", 0))
        assertEquals(0L, parseRetryAfter("-5", 0))
        assertEquals(null, parseRetryAfter(null, 0))
        assertEquals(null, parseRetryAfter("soon", 0))
        val now = java.time.Instant.parse("2026-10-06T08:00:00Z").toEpochMilli()
        assertEquals(90L, parseRetryAfter("Tue, 06 Oct 2026 08:01:30 GMT", now))
        assertEquals(0L, parseRetryAfter("Tue, 06 Oct 2026 07:00:00 GMT", now))
    }

    @Test
    fun pushV15Examples() {
        val put = ProtocolJson.parseToJsonElement(read("device_put.json")) as JsonObject
        val model = ProtocolJson.decodeFromJsonElement<DevicePut>(put)
        assertEquals(DevicePut.PLATFORM_ANDROID, model.platform)
        assertEquals(put, ProtocolJson.encodeToJsonElement(model))
        assertFalse(model.toString().contains(model.pushToken!!))

        val push = ProtocolJson.decodeFromString<PushPayload>(read("push_inbox.json"))
        assertTrue(push.isInbox)
        assertEquals("1", push.v)
        // FCM delivers data as a string map; no content fields exist in the payload.
        assertEquals(push, PushPayload.fromData(mapOf("type" to "inbox", "v" to "1")))
        assertFalse(PushPayload.fromData(mapOf("type" to "other"))!!.isInbox)
        assertEquals(null, PushPayload.fromData(emptyMap()))

        assertEquals(AuthErrors.INVALID_DEVICE, ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_invalid_device.json")).error.code)
    }

    @Test
    fun friendsV16Examples() {
        // Client-sent payloads round-trip exactly.
        for ((file, enc) in listOf<Pair<String, (JsonObject) -> kotlinx.serialization.json.JsonElement>>(
            "invite_create.json" to { o -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<InviteCreate>(o)) },
            "friend_request.json" to { o -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<FriendRequestCreate>(o)) },
            "block.json" to { o -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<BlockCreate>(o)) },
        )) {
            val o = ProtocolJson.parseToJsonElement(read(file)) as JsonObject
            assertEquals(file, o, enc(o))
        }
        val inv = ProtocolJson.decodeFromString<InviteReply>(read("invite_reply.json")).invite
        assertTrue(inv.pending)
        assertEquals("Join me on RisiMe", inv.subject)
        assertTrue(inv.shareText.contains(inv.link) && inv.shareText.contains(inv.email))
        assertTrue(inv.shareText.length <= 300)
        assertEquals(inv, ProtocolJson.decodeFromString<InvitesReply>(read("invites_reply.json")).invites.single())
        assertEquals("requested", ProtocolJson.decodeFromString<FriendRequestReply>(read("friend_request_reply.json")).status)

        val f = ProtocolJson.decodeFromString<FriendsReply>(read("friends_reply.json"))
        assertEquals(1, f.friends.size)
        assertEquals(null, f.friends.single().vouchedBy)
        val incoming = f.incoming.single()
        assertNotNull(incoming.userId)
        assertNotNull(incoming.displayName)
        val outgoing = f.outgoing.single()
        assertEquals(null, outgoing.userId) // outgoing never reveals registration
        assertEquals(null, outgoing.displayName)
        assertEquals("Test User F", f.blocked.single().displayName)
        assertEquals(f.friends.single(), ProtocolJson.decodeFromString<FriendAcceptReply>(read("friend_accept_reply.json")).friend)

        val sig = ProtocolJson.decodeFromString<Signal>(read("signal_friend.json"))
        val fs = sig.friend()!!
        assertEquals(FriendSignal.REQUEST_RECEIVED, fs.action)
        assertEquals(incoming.id, fs.requestId)
        assertEquals(incoming.userId, fs.user.userId)
        assertEquals(null, sig.presence())

        assertEquals(AuthErrors.NOT_FRIENDS, ProtocolJson.decodeFromString<ErrorReason>(read("error_not_friends.json")).reason)
        val vouched = ProtocolJson.decodeFromString<MeReply>(read("user_vouched.json")).user
        assertEquals("Test User B", vouched.vouchedBy!!.displayName)
        assertEquals(null, ProtocolJson.decodeFromString<AuthVerifyReply>(read("auth_verify_reply.json")).user.vouchedBy)
    }

    @Test
    fun e2eeV17Examples() {
        fun roundTrip(file: String, enc: (JsonObject) -> kotlinx.serialization.json.JsonElement) {
            val o = ProtocolJson.parseToJsonElement(read(file)) as JsonObject
            assertEquals(file, o, enc(o))
        }
        roundTrip("device_put_mls.json") { ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<DevicePut>(it)) }
        roundTrip("key_packages_upload.json") { ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<KeyPackagesUpload>(it)) }
        roundTrip("key_packages_claim.json") { ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<KeyPackagesClaim>(it)) }
        roundTrip("mls_commit_request.json") { ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<MlsCommitRequest>(it)) }
        roundTrip("msg_send_e2ee.json") { ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<MsgSendE2ee>(it)) }

        val put = ProtocolJson.decodeFromString<DevicePut>(read("device_put_mls.json"))
        assertEquals(null, put.pushToken) // no Firebase: still registers for MLS
        assertEquals(32, java.util.Base64.getDecoder().decode(put.mls!!.signatureKey).size)
        assertTrue(ProtocolJson.decodeFromString<DevicePutReply>(read("device_put_mls_reply.json")).attestation.count { it == '.' } == 2)
        assertEquals("OKP", ProtocolJson.decodeFromString<AttestationKeys>(read("attestation_keys.json")).keys.single()["kty"].toString().trim('"'))
        assertEquals(42, ProtocolJson.decodeFromString<KeyPackageCount>(read("key_packages_count.json")).count)

        val claim = ProtocolJson.decodeFromString<KeyPackagesClaimReply>(read("key_packages_claim_reply.json")).devices
        assertTrue(claim[0].mls && claim[0].keyPackage != null && claim[0].attestation != null)
        assertTrue(!claim[1].mls && claim[1].deviceId == null) // a legacy app blocks the upgrade

        val g = ProtocolJson.decodeFromString<MlsGroup>(read("mls_group.json"))
        assertTrue(!g.e2ee && !g.ready && g.epoch == null && g.generation == 1L)
        assertEquals(MlsMissing.LEGACY_APP, g.missing.single().reason)
        assertEquals(1L, ProtocolJson.decodeFromString<MlsCommitReply>(read("mls_commit_reply.json")).epoch)
        assertEquals(1L, ProtocolJson.decodeFromString<MlsCommitsReply>(read("mls_commits_reply.json")).commits.single().epoch)

        val conflict = ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_epoch_conflict.json")).error
        assertEquals(AuthErrors.EPOCH_CONFLICT, conflict.code)
        assertEquals(3L, conflict.epoch)
        val notReady = ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_not_ready.json")).error
        assertEquals(AuthErrors.NOT_READY, notReady.code)
        assertEquals(MlsMissing.LEGACY_APP, notReady.missing!!.single().reason)

        val msg = ProtocolJson.decodeFromString<Event>(read("event_message_e2ee.json")).messageData()!!
        assertTrue(msg.encrypted)
        assertEquals(null, msg.body) // the server never has plaintext
        assertEquals(1L, msg.generation)
        assertNotNull(msg.fromDevice)

        val commit = ProtocolJson.decodeFromString<Event>(read("event_mls_commit.json")).mlsCommit()!!
        assertEquals(1L, commit.epoch)
        val welcome = ProtocolJson.decodeFromString<Event>(read("event_mls_welcome.json")).mlsWelcome()!!
        assertEquals(1, welcome.toDevices.size)
        assertEquals("added", ProtocolJson.decodeFromString<Event>(read("event_mls_membership.json")).mlsMembership()!!.change)
        assertEquals(12, ProtocolJson.decodeFromString<Signal>(read("signal_mls_key_packages_low.json")).keyPackagesLow()!!.count)
        assertEquals(AuthErrors.E2EE_REQUIRED, ProtocolJson.decodeFromString<ErrorReason>(read("error_e2ee_required.json")).reason)
        // A plaintext event still has its body and no ciphertext.
        assertTrue(!ProtocolJson.decodeFromString<Event>(read("event_message.json")).messageData()!!.encrypted)
    }

    @Test
    fun reactionsV18Examples() {
        val env = lk.codegen.risime.data.mls.MlsPayload.decode(read("reaction_payload.json").toByteArray())
        assertEquals(lk.codegen.risime.data.mls.MlsPayload.Decoded.Reaction("c1a2b3c4-a0b1-11f0-8000-0242ac120002", "👍", ReactionBody.ADD), env)
        // Our envelope encoder produces exactly the contract's JSON.
        assertEquals(
            ProtocolJson.parseToJsonElement(read("reaction_payload.json")),
            ProtocolJson.parseToJsonElement(lk.codegen.risime.data.mls.MlsPayload.reaction("c1a2b3c4-a0b1-11f0-8000-0242ac120002", "👍", "add").decodeToString()),
        )
        val send = ProtocolJson.parseToJsonElement(read("msg_send_reaction.json")) as JsonObject
        val model = ProtocolJson.decodeFromJsonElement<MsgSendReaction>(send)
        assertEquals(send, ProtocolJson.encodeToJsonElement(model)) // exactly one content field, no "body"
        assertFalse("body" in ProtocolJson.encodeToJsonElement(model).jsonObject)
        assertEquals("❤️", model.reaction.emoji)
        val both = ProtocolJson.parseToJsonElement(read("msg_send_reaction_and_body.json")).jsonObject
        assertTrue("body" in both && "reaction" in both) // the bad_request case: the client never sends it
        val ev = ProtocolJson.decodeFromString<Event>(read("event_reaction.json"))
        val r = ev.reaction()!!
        assertEquals(ev.eventId, r.messageId)
        assertEquals(model.reaction.target, r.target)
        assertEquals(null, ev.messageData()) // not a message
        assertEquals(AuthErrors.UNKNOWN_TARGET, ProtocolJson.decodeFromString<ErrorReason>(read("error_unknown_target.json")).reason)
        assertEquals(AuthErrors.INVALID_EMOJI, ProtocolJson.decodeFromString<ErrorReason>(read("error_invalid_emoji.json")).reason)
    }

    /** §11.1: the shared fixture through ICU4J (= android.icu on devices). */
    @Test
    fun graphemeLimitsMatchTheFixture() {
        val icu = lk.codegen.risime.data.GraphemeCounter { t ->
            val it = com.ibm.icu.text.BreakIterator.getCharacterInstance()
            it.setText(t)
            var n = 0
            while (it.next() != com.ibm.icu.text.BreakIterator.DONE) n++
            n
        }
        val fx = ProtocolJson.parseToJsonElement(read("limits_graphemes.json")).jsonObject
        val cases = fx["cases"]!!.jsonArray
        assertTrue(cases.size >= 6)
        for (c in cases) {
            val o = c.jsonObject
            assertEquals(o["name"].toString(), o["graphemes"]!!.jsonPrimitive.int, icu.count(o["text"]!!.jsonPrimitive.content))
        }
        assertEquals(lk.codegen.risime.data.BodyLimits.MAX_GRAPHEMES, fx["max_graphemes"]!!.jsonPrimitive.int)
        assertEquals(lk.codegen.risime.data.BodyLimits.MAX_BYTES, fx["max_bytes"]!!.jsonPrimitive.int)
        for (g in fx["generated"]!!.jsonArray) {
            val o = g.jsonObject
            val text = o["repeat"]!!.jsonPrimitive.content.repeat(o["count"]!!.jsonPrimitive.int)
            val ok = o["ok"]!!.jsonPrimitive.boolean
            assertEquals(o["name"].toString(), ok, !lk.codegen.risime.data.BodyLimits.of(text, icu).tooLong)
        }
        // The same rule with 4096 ZWJ families (25 bytes each → over the 16 KiB byte cap first).
        val fam = "👨‍👩‍👧‍👦"
        val l = lk.codegen.risime.data.BodyLimits.of(fam.repeat(4096), icu)
        assertEquals(4096, l.graphemes)
        assertTrue(l.tooLong && l.bytes > lk.codegen.risime.data.BodyLimits.MAX_BYTES)
        assertTrue(lk.codegen.risime.data.BodyLimits.of("a".repeat(3900), icu).showCounter)
        assertFalse(lk.codegen.risime.data.BodyLimits.of("a".repeat(3899), icu).showCounter)
        // Reaction emoji rules.
        for (e in listOf("👍", "❤️", "👍🏽", "🇱🇰", fam, "🏴󠁧󠁢󠁥󠁮󠁧󠁿")) assertTrue(e, lk.codegen.risime.data.isValidReactionEmoji(e, icu))
        for (e in listOf("", "👍👍", "a b", " ", "\u0007", "x".repeat(33))) assertFalse(e, lk.codegen.risime.data.isValidReactionEmoji(e, icu))
    }

    @Test
    fun messageEventFields() {
        val e = ProtocolJson.decodeFromString<Event>(read("event_message.json"))
        val m = e.messageData()!!
        assertEquals(Event.KIND_MESSAGE, e.kind)
        assertEquals(dmConversationId(m.from, m.to!!), m.conversationId)
        assertEquals(null, e.statusData())
    }

    @Test
    fun statusEventFields() {
        val s = ProtocolJson.decodeFromString<Event>(read("event_status.json")).statusData()!!
        assertEquals("delivered", s.status)
    }

    @Test
    fun contactsUnregisteredHasNullUserId() {
        val c = ProtocolJson.decodeFromString<ContactsReply>(read("contacts_reply.json")).contacts
        assertTrue(c.any { !it.registered && it.userId == null })
        assertFalse(c.isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val s = """{"events":[],"has_more":false,"server_time":"2026-10-06T08:15:29.000Z","extra":1}"""
        assertFalse(ProtocolJson.decodeFromString<EventsPage>(s).hasMore)
    }
}
