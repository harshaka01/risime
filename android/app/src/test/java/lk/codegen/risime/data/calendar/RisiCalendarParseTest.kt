package lk.codegen.risime.data.calendar

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.tabs.RisiControl
import lk.codegen.risime.data.tabs.RisiMessages
import lk.codegen.risime.net.ApiErrorEnvelope
import lk.codegen.risime.net.AuthConfig
import lk.codegen.risime.net.CAPABILITY_RISI_EVENTS
import lk.codegen.risime.net.DevicePut
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiCalendarBodies
import lk.codegen.risime.net.RisiCalendarCard
import lk.codegen.risime.net.RisiCalendarChangesReply
import lk.codegen.risime.net.RisiCalendarEventsReply
import lk.codegen.risime.net.RisiCalendarSettings
import lk.codegen.risime.net.RisiCalendarSettingsReply
import lk.codegen.risime.net.RisiEventReply
import lk.codegen.risime.net.RisiKinds129
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.risiCalendarChanged
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.29 §29 every Risi Calendar fixture decodes (unknown fields and kinds tolerated), the request bodies
 * match the contract shapes key for key, and the cards' participant objects don't break [RisiMeta].
 * Fixtures: test resources `fixtures/risi_calendar/` (shapes from proposal 2026-10-09-risi-calendar-notes).
 */
class RisiCalendarParseTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val shenika = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val ev = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b"

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_calendar/$name")!!.readText()

    private fun obj(name: String) = ProtocolJson.parseToJsonElement(fixture(name)).jsonObject

    private fun row(name: String): MessageEntity {
        val env = obj(name)
        return MessageEntity(
            clientMsgId = name, messageId = "m-$name", conversationId = "grp:x", from = "risi", to = "grp:x",
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-09T15:10:00.000Z", localTs = 1,
            status = "READ", outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
    }

    private fun meta(name: String): RisiMeta = RisiMessages.meta(row(name))!!

    private fun card(name: String): RisiCalendarCard = RisiCalendarCard.parse(row(name).systemJson)!!

    @Test fun authConfigAndDeviceCapability() {
        val cfg = ProtocolJson.decodeFromString(AuthConfig.serializer(), fixture("auth_config_v129.json"))
        assertTrue(cfg.risiEventsOn && cfg.risiLedgerOn && cfg.risiSkillsOn && cfg.risiToolsOn)
        // Absent = off (a v1.28 server).
        assertFalse(ProtocolJson.decodeFromString(AuthConfig.serializer(), """{"modes":["dev"],"risi_ledger":"on"}""").risiEventsOn)
        val put = ProtocolJson.decodeFromString(DevicePut.serializer(), fixture("device_put_risi_events.json"))
        assertTrue(CAPABILITY_RISI_EVENTS in put.mls!!.capabilities!!)
    }

    @Test fun restRepliesDecode() {
        val list = ProtocolJson.decodeFromString(RisiCalendarEventsReply.serializer(), fixture("risi_calendar_events_reply.json"))
        assertEquals(3, list.events.size)
        assertEquals("c1.000000000042", list.cursor)
        val iv = list.events[0]
        assertEquals("Interview", iv.title)
        assertEquals(listOf(shenika, me), iv.participants.map { it.userId })
        assertEquals("proposed", iv.myStatus)
        assertEquals(30, iv.myReminderMin)
        assertEquals("grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d", iv.source!!.conversationId)
        assertTrue(iv.isOwner(shenika) && !iv.isOwner(me))

        val ch = ProtocolJson.decodeFromString(RisiCalendarChangesReply.serializer(), fixture("risi_calendar_changes_reply.json"))
        assertEquals(2, ch.changes.size)
        assertEquals(2, ch.changes[0].event!!.version)
        assertTrue(ch.changes[1].removed && ch.changes[1].event == null)
        assertEquals("7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d", ch.changes[1].id)
        assertFalse(ch.hasMore)

        assertEquals("Standup", ProtocolJson.decodeFromString(RisiEventReply.serializer(), fixture("risi_calendar_event_create_reply.json")).event.title)
        val st = ProtocolJson.decodeFromString(RisiCalendarSettingsReply.serializer(), fixture("risi_calendar_settings.json")).settings
        assertEquals(RisiCalendarSettings(30, 60, true), st)
        // The default reminder is 30 minutes.
        assertEquals(30, RisiCalendarSettings().defaultReminderMin)
    }

    @Test fun inboxEventIsContentFree() {
        val e = ProtocolJson.decodeFromString(Event.serializer(), fixture("event_risi_calendar_changed.json"))
        assertEquals("c1.000000000045", e.risiCalendarChanged()!!.cursor)
        assertNull(ProtocolJson.decodeFromString(Event.serializer(), """{"event_id":"x","kind":"message","data":{}}""").risiCalendarChanged())
    }

    @Test fun errorsDecode() {
        for ((f, code) in listOf("error_version_conflict.json" to "version_conflict", "error_cursor_expired.json" to "cursor_expired", "error_not_invitable.json" to "not_invitable")) {
            assertEquals(code, ProtocolJson.decodeFromString(ApiErrorEnvelope.serializer(), fixture(f)).error.code)
        }
        assertEquals("Only the person who made this event can change it.", RisiCalendar.errorText("not_owner"))
    }

    @Test fun requestBodiesMatchTheShapes() {
        val create = RisiCalendarBodies.create("8b9c0d1e-2f3a-4b4c-9d5e-6f7a8b9c0d1e", "Standup", "2026-10-12T09:00:00.000Z", "2026-10-12T10:00:00.000Z", false, "Asia/Colombo", emptyList(), 30, sendReminder = true)
        assertEquals(obj("risi_calendar_event_create.json"), create)
        assertEquals(obj("risi_calendar_event_patch.json"), RisiCalendarBodies.patch(1, title = "Interview (room 2)", reminderMin = 10, sendReminder = true))
        // A PATCH carries only what changes: no reminder key unless asked; `null` clears it.
        assertFalse("reminder_min" in RisiCalendarBodies.patch(4, title = "x"))
        assertEquals("null", RisiCalendarBodies.patch(4, sendReminder = true)["reminder_min"].toString())
        assertEquals(obj("risi_calendar_respond_accept.json"), RisiCalendarBodies.respond("accept", 1, null, 30, sendReminder = true))
        assertEquals(obj("risi_calendar_respond_suggest.json"), RisiCalendarBodies.respond("suggest", 1, Triple("2026-10-13T09:30:00.000Z", "2026-10-13T10:30:00.000Z", false)))
        assertEquals(obj("risi_calendar_suggestion_resolve.json"), RisiCalendarBodies.resolve("use"))
        assertEquals(obj("risi_calendar_settings.json")["settings"], RisiCalendarBodies.settings(RisiCalendarSettings()))
    }

    @Test fun everyCardIsAnHonouredRisiRowWithItsCalendarFields() {
        for (f in listOf(
            "envelope_risi_event_card_added.json", "envelope_risi_event_card_official.json", "envelope_risi_calendar_invite.json",
            "envelope_risi_event_update.json", "envelope_risi_calendar_suggestion.json", "envelope_risi_calendar_reminder.json",
        )) {
            val m = meta(f)
            assertTrue(f, m.kind in RisiKinds129.ALL)
            assertNotNull(f, RisiEventCards.card(row(f)))
        }
        // Participant objects read as ids in RisiMeta, with their statuses in RisiCalendarCard.
        assertEquals(listOf(shenika, me), meta("envelope_risi_calendar_invite.json").participants)
        val inv = card("envelope_risi_calendar_invite.json")
        assertEquals(ev, inv.eventId)
        assertEquals(listOf("proposed", "proposed"), inv.participants.map { it.status })
        assertEquals("new", inv.reason)
        assertNull(inv.from)
        assertEquals("official", meta("envelope_risi_event_card_official.json").mode)
        assertEquals("added", card("envelope_risi_event_card_added.json").mode)
        assertEquals("status", card("envelope_risi_event_update.json").change)
        assertEquals("9c0d1e2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f", card("envelope_risi_calendar_suggestion.json").suggestionId)
        assertEquals(30, card("envelope_risi_calendar_reminder.json").reminderMin)
    }

    @Test fun theConfirmCardIsTheServerSideAddTool() {
        val m = meta("envelope_risi_confirm_risi_calendar_add.json")
        assertEquals(RisiKinds129.TOOL_RISI_CALENDAR_ADD, m.tool)
        assertEquals(listOf("add", "edit", "cancel"), m.buttons)
        assertTrue(lk.codegen.risime.data.tabs.RisiSkillCards.knownConfirmTool(m))
        // Never mistaken for the phone calendar's card (no device permission involved).
        assertFalse(lk.codegen.risime.data.tabs.RisiCalendarCards.isCalendarAdd(m))
    }

    @Test fun remindersAndInvitesNotifyTheirUserOnly() {
        assertTrue(RisiMessages.notifies(row("envelope_risi_calendar_reminder.json"), me))
        assertFalse(RisiMessages.notifies(row("envelope_risi_calendar_reminder.json"), shenika))
        assertTrue(RisiMessages.notifies(row("envelope_risi_calendar_invite.json"), me))
        // event_update is silent.
        assertFalse(RisiMessages.notifies(row("envelope_risi_event_update.json"), me))
    }

    @Test fun answersWithCalendarSourcesAndTheDigestStillParse() {
        val a = meta("envelope_risi_answer_calendar_sources_risi.json")
        assertEquals(listOf("calendar_source", "calendar_source"), a.sources.map { it.type })
        assertEquals("personal", meta("envelope_risi_digest_personal_v129.json").scope)
    }

    @Test fun unknownKindsAndStatusesAreTolerated() {
        val m = meta("envelope_risi_future_kind.json")
        assertEquals("calendar_hologram", m.kind)
        assertEquals(listOf(me), m.participants)
        assertNull(RisiEventCards.card(row("envelope_risi_future_kind.json")))
        assertEquals("Maybe", RisiCalendarViews.statusLabel("maybe"))
    }

    @Test fun theNewRisiActionsAreValidControls() {
        for (f in listOf("envelope_risi_action_event_accept.json", "envelope_risi_action_event_suggest.json", "envelope_risi_action_confirm_write_edit_risi_calendar.json")) {
            val o = obj(f)
            assertTrue(f, RisiControl.valid(o))
            assertNotNull(f, RisiControl.line(o.toString(), "Shenika"))
        }
        assertEquals("Shenika accepted", RisiControl.line(fixture("envelope_risi_action_event_accept.json"), "Shenika"))
    }
}
