package lk.codegen.risime.data.tabs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.RisiCommitmentsReply
import lk.codegen.risime.net.RisiKinds127
import lk.codegen.risime.net.RisiMeta
import lk.codegen.risime.net.RisiTotals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Server items 8–10 (2026-10-09): offer cards (`origin: "offer"`), the `item_clarify` card and the
 * My promises split by `direction` with `totals`. Fixtures under test resources `fixtures/risi_items_8_10/`
 * (shapes from server/lib/risime/agent/offers.ex, writes.ex, rest.ex); older replies from contract/v1/examples.
 */
class RisiItems810Test {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val shenika = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val risiUser = "9e1f0000-0000-4000-8000-000000000001"
    private val conv = "grp:9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
    private val colombo = ZoneId.of("Asia/Colombo")

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/risi_items_8_10/$name")!!.readText()
    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    private var n = 0

    private fun row(json: String): MessageEntity {
        val env = ProtocolJson.parseToJsonElement(json) as JsonObject
        return MessageEntity(
            clientMsgId = "r${n++}", messageId = "m$n", conversationId = conv, from = risiUser, to = conv,
            body = (env["body"] as JsonPrimitive).content, serverTs = "2026-10-09T15:10:00.000Z", localTs = n.toLong(),
            status = "READ", outgoing = false, systemJson = RisiMessages.encode(env["risi"] as JsonObject),
        )
    }

    private fun meta(name: String): RisiMeta = RisiMessages.meta(row(fixture(name)))!!

    private fun ctl(target: String, action: String, from: String = me) = MessageEntity(
        clientMsgId = "ctl${n++}", messageId = "c$n", conversationId = conv, from = from, to = conv, body = "", serverTs = "2026-10-09T15:12:00.000Z",
        localTs = n.toLong(), status = "READ", outgoing = from == me, kind = MessageEntity.KIND_RISI_CTL,
        systemJson = String(RisiControl.encode(RisiControl.action(target, action, "Send Kamal the deck", "2026-10-14T04:30:00.000Z", editAllDay = false))),
    )

    private fun reply(name: String, contract: Boolean = false) =
        ProtocolJson.decodeFromString(RisiCommitmentsReply.serializer(), if (contract) example(name) else fixture(name))

    // ---- item 8: offers ----

    @Test fun anOfferCardParsesWithUnknownFieldsAndAsksAddToCalendar() {
        val r = meta("envelope_risi_confirm_offer_calendar.json")
        assertEquals("confirm", r.kind)
        assertEquals("offer", r.origin)
        assertEquals("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", r.itemId)
        assertNull(r.turnRef)
        assertTrue(RisiOffers.isOffer(r))
        assertEquals("Add to calendar?", RisiOffers.header(r))
        // The P0 action card's event: title, start, end.
        val p = RisiCalendarCards.proposal(r)!!
        assertEquals("Interview with Shenika", p.title)
        assertEquals(Instant.parse("2026-10-12T08:30:00Z"), p.start)
        assertEquals(Instant.parse("2026-10-12T09:30:00Z"), p.end)
        // [Add] and [Cancel] for me while open (the event start is the expiry: days away).
        val state = RisiToolCards.confirmState(r, emptyList(), Instant.parse("2026-10-11T10:00:00Z").toEpochMilli())
        assertEquals(RisiToolCards.ConfirmState.OPEN, state)
        assertEquals(listOf("add", "cancel"), RisiToolCards.confirmButtons(me, r, state, emptySet()))
    }

    @Test fun aReminderOfferAsksRemindMeAndAnOrdinaryConfirmKeepsItsHeader() {
        val r = meta("envelope_risi_confirm_offer_reminder.json")
        assertEquals("Remind me?", RisiOffers.header(r))
        assertEquals(listOf("add", "cancel"), RisiToolCards.confirmButtons(me, r, RisiToolCards.ConfirmState.OPEN, emptySet()))
        val plain = RisiMessages.meta(row(example("envelope_risi_confirm.json")))!!
        assertFalse(RisiOffers.isOffer(plain))
        assertNull(RisiOffers.header(plain))
    }

    @Test fun anOfferIsNeverAProgressBubble() {
        // No risi_progress names the offer's request: nothing to show, and the card itself ends nothing.
        val card = row(fixture("envelope_risi_confirm_offer_calendar.json"))
        assertTrue(RisiProgressStore.visible(emptyList(), listOf(card), conv).isEmpty())
    }

    // ---- item 10: item_clarify ----

    @Test fun theClarifyCardParsesAndOffersNewDateToTheOwnerOnly() {
        val r = meta("envelope_risi_item_clarify.json")
        assertEquals(RisiKinds127.ITEM_CLARIFY, r.kind)
        assertEquals("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6f", r.itemId)
        assertEquals("sometime next week", r.dueText)
        assertTrue(r.question!!.startsWith("When is \"Send Kamal the deck\" due?"))
        assertTrue(RisiClarify.canPickDate(me, r, emptyList(), emptySet()))
        assertFalse(RisiClarify.canPickDate(shenika, r, emptyList(), emptySet()))
        assertFalse(RisiClarify.canPickDate(me, r, emptyList(), setOf(r.itemId!!)))
    }

    @Test fun aV124ItemShowsTheQuestionOnly() {
        val r = meta("envelope_risi_item_clarify_no_buttons.json")
        assertNull(r.summaryId)
        assertNull(r.dueText)
        assertTrue(r.buttons.isEmpty())
        assertFalse(RisiClarify.canPickDate(me, r, emptyList(), emptySet()))
    }

    @Test fun myItemEditAnswersTheCard() {
        val r = meta("envelope_risi_item_clarify.json")
        val others = listOf(ctl(r.itemId!!, "item_edit", from = shenika), ctl("another-item", "item_edit"))
        assertFalse(RisiClarify.answered(me, r, others))
        val mine = others + ctl(r.itemId, "item_edit")
        assertTrue(RisiClarify.answered(me, r, mine))
        assertFalse(RisiClarify.canPickDate(me, r, mine, emptySet()))
    }

    @Test fun theNewDateIsATimedOrAllDayDue() {
        assertEquals("2026-10-14T04:30:00.000Z" to false, RisiClarify.due(LocalDate.of(2026, 10, 14), LocalTime.of(10, 0), colombo))
        // All day: the day's end, local (as the server stores an all-day due).
        assertEquals("2026-10-14T18:29:59.999Z" to true, RisiClarify.due(LocalDate.of(2026, 10, 14), null, colombo))
    }

    @Test fun theItemEditGoesOutAsSection27Point5() {
        val o = RisiControl.action("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6f", RisiLedger.EDIT, "Send Kamal the deck", "2026-10-14T18:29:59.999Z", editAllDay = true)
        assertEquals("item_edit", (o["action"] as JsonPrimitive).content)
        assertEquals("""{"text":"Send Kamal the deck","due":"2026-10-14T18:29:59.999Z","all_day":true}""", o["edit"].toString())
    }

    // ---- item 9: My promises ----

    @Test fun theItem9ReplyParsesDirectionOwnerStatusSourceAndTotals() {
        val r = reply("risi_commitments_reply_item9.json")
        assertEquals(RisiTotals(2, 1, 1), r.totals)
        val vague = r.commitments[1]
        assertEquals("i_promised", vague.direction)
        assertEquals("You", vague.ownerName)
        assertEquals("needs_clarification", vague.status)
        assertEquals(true, vague.needsClarification)
        assertNull(vague.sourceMessageId)
        val owed = r.commitments[2]
        assertEquals("Shenika", owed.ownerName)
        assertEquals("c1a2b3f1-a4f0-11f1-8000-0242ac120009", owed.sourceMessageId)
        assertEquals(listOf("c1a2b3f1-a4f0-11f1-8000-0242ac120009"), owed.sourceMessageIds)
    }

    @Test fun sectionsAreIPromisedPromisedToMeOthersWithTheServersCounts() {
        val r = reply("risi_commitments_reply_item9.json")
        val s = RisiPromises.sections(r.commitments, r.totals)
        assertEquals(listOf("I promised", "Promised to me", "Others"), s.map { it.title })
        assertEquals(listOf(2, 1, 1), s.map { it.count })
        assertEquals(listOf("Send the revised quote", "Send Kamal the deck"), s[0].items.map { it.text })
        assertEquals(listOf("Book the site visit transport"), s[1].items.map { it.text })
        assertEquals(listOf("Kamal sends the minutes"), s[2].items.map { it.text })
        assertEquals("I promised 2 · Promised to me 1 · Others 1", RisiPromises.countsLine(r.totals, r.commitments))
        // The totals are the counts even when fewer items are listed.
        assertEquals(listOf(5, 1, 1), RisiPromises.sections(r.commitments, RisiTotals(5, 1, 1)).map { it.count })
    }

    @Test fun rowsShowOwnerDueAndStatus() {
        val r = reply("risi_commitments_reply_item9.json")
        val (quote, vague, owed, other) = r.commitments
        assertEquals("You", RisiPromises.owner(quote, me) { "Someone" })
        assertEquals("Shenika", RisiPromises.owner(owed, me) { "Someone" })
        assertEquals("Kamal", RisiPromises.owner(other, me) { "Someone" })
        assertEquals("Needs a date", RisiPromises.status(vague))
        assertEquals("Confirmed", RisiPromises.status(quote))
        // all_day respected: a timed due with its time, an all-day due by its day; no due: the said phrase.
        assertEquals("due Fri 9 Oct, 17:00", RisiPromises.due(quote, colombo))
        assertEquals("by Mon 12 Oct", RisiPromises.due(owed, colombo))
        assertEquals("sometime next week", RisiPromises.due(vague, colombo))
    }

    @Test fun aTapOpensTheSourceConversationAtTheSourceMessage() {
        val r = reply("risi_commitments_reply_item9.json")
        assertEquals("grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b" to "c1a2b3f1-a4f0-11f1-8000-0242ac120001", RisiPromises.target(r.commitments[0]))
        // No source message: the chat opens at its end.
        assertEquals("grp:4e5f6a7b-8c9d-4e0f-9a1b-2c3d4e5f6a7b" to null, RisiPromises.target(r.commitments[1]))
        assertNull(RisiPromises.target(r.commitments[0].copy(sourceConversationId = null, officialConversationId = null)))
    }

    @Test fun anOlderServersReplySplitsByRoleAndCountsTheList() {
        val v127 = reply("risi_commitments_reply_v127.json", contract = true)
        assertNull(v127.totals)
        val s = RisiPromises.sections(v127.commitments, v127.totals)
        assertEquals(listOf("I promised" to 1, "Promised to me" to 1), s.map { it.title to it.count })
        assertEquals("Confirmed", RisiPromises.status(v127.commitments[0]))
        // A legacy commitment (no role, no direction) stays where v1.27 showed it.
        val legacy = reply("risi_commitments_reply.json", contract = true)
        assertEquals(listOf("I promised"), RisiPromises.sections(legacy.commitments, null).map { it.title })
        // An unknown direction from a newer server is "Others", never a crash.
        assertEquals("others", RisiPromises.direction(legacy.commitments[0].copy(direction = "shared_with_team")))
    }

    @Test fun theDigestGroupsByDirectionWithTheSameTotals() {
        val r = RisiMessages.meta(row(fixture("envelope_risi_digest_personal_item9.json")))!!
        assertEquals(RisiTotals(2, 1, 0), r.totals)
        val s = RisiPromises.digestSections(r, me)
        assertEquals(listOf(Triple("i_promised", 2, 1), Triple("promised_to_me", 1, 1)), s.map { Triple(it.first, it.second, it.third.size) })
        // An older digest (no direction, no totals): by owner, counted from its items.
        val old = RisiMessages.meta(row(example("envelope_risi_digest_personal.json")))!!
        assertEquals(listOf(Triple("i_promised", 1, 1), Triple("promised_to_me", 1, 1)), RisiPromises.digestSections(old, me).map { Triple(it.first, it.second, it.third.size) })
    }

    @Test fun focusOnASourceMessageScrollsOnlyWhenItIsHere() {
        val here = row(example("envelope_risi_confirm.json")).copy(messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac120001")
        assertEquals(here.messageId, RisiLedger.focusTarget(listOf(here), RisiUiBus.Focus(messageId = "C1A2B3F1-A4F0-11F1-8000-0242AC120001")))
        assertNull(RisiLedger.focusTarget(listOf(here), RisiUiBus.Focus(messageId = "c1a2b3f1-a4f0-11f1-8000-0242ac12ffff")))
    }
}
