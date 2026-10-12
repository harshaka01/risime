package ai.risicloud.risime.data.tabs

import ai.risicloud.risime.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PART 4 (Harsha 2026-10-12): addressing Risi from the composer, the Private hint, the empty Risi chat's examples. */
class RisiTalkTest {
    @Test fun theMentionTokenIsAWordAnyCase() {
        assertTrue(RisiTalk.mentionsRisi("@Risi when is it"))
        assertTrue(RisiTalk.mentionsRisi("hi @risi, when is it"))
        assertTrue(RisiTalk.mentionsRisi("ok @RISI"))
        assertFalse(RisiTalk.mentionsRisi("mail risi@example.com"))
        assertFalse(RisiTalk.mentionsRisi("@Risiya is here"))
        assertFalse(RisiTalk.mentionsRisi("Risi, when"))
    }

    @Test fun theRequestIsTheTextWithoutTheToken() {
        assertEquals("when is the review?", RisiTalk.mentionRequest("@Risi when is the review?"))
        assertEquals("when is the review?", RisiTalk.mentionRequest("@Risi, when is the review?"))
        assertEquals("Team, when is the review?", RisiTalk.mentionRequest("Team, @risi when is the review?"))
        assertNull(RisiTalk.mentionRequest("@Risi"))
        assertNull(RisiTalk.mentionRequest("no token here"))
    }

    @Test fun directAddressByName() {
        assertEquals("when is the review?", RisiTalk.directRequest("Risi, when is the review?"))
        assertEquals("add it", RisiTalk.directRequest("  risi: add it"))
        assertEquals("what's next", RisiTalk.directRequest("Hey Risi, what's next"))
        assertNull(RisiTalk.directRequest("Risi,"))
        assertNull(RisiTalk.directRequest("Tell Risi, later"))
        assertNull(RisiTalk.directRequest("Risiya, hi"))
    }

    @Test fun thePickerOffersRisiWhileTheTokenCanStillBecomeRisi() {
        assertEquals(0 until 1, RisiTalk.pickerQuery("@", 1))
        assertEquals(3 until 6, RisiTalk.pickerQuery("hi @Ri", 6))
        assertEquals(0 until 5, RisiTalk.pickerQuery("@Risi", 5))
        assertNull(RisiTalk.pickerQuery("@Risix", 6))
        assertNull(RisiTalk.pickerQuery("@Kamal", 6))
        assertNull(RisiTalk.pickerQuery("mail@r", 6))
        assertNull(RisiTalk.pickerQuery("", 0))
        assertEquals("hi " to 3, RisiTalk.applyPick("hi @Ri", 6))
        assertEquals("ok" to 0, RisiTalk.applyPick("@ok", 1))
    }

    @Test fun theExamplesFillTheComposerAndTheReminderOneStopsAtTheName() {
        assertEquals(
            listOf("Summarise my Official chats today", "Remind me tomorrow at 9 to call …", "Add a meeting to my calendar", "What did I promise this week?"),
            RisiTalk.EXAMPLES.map { it.label },
        )
        val remind = RisiTalk.EXAMPLES[1]
        assertEquals("Remind me tomorrow at 9 to call ", remind.text)
        assertEquals(remind.text.length, remind.cursor)
        RisiTalk.EXAMPLES.forEach { assertTrue(it.cursor in 0..it.text.length) }
    }

    private fun row(kind: String) = MessageEntity(
        clientMsgId = "c-$kind", messageId = null, conversationId = "grp:r", from = "me", to = "grp:r", body = "x", serverTs = null,
        localTs = 1L, status = "SENT", outgoing = true, kind = kind,
    )

    @Test fun anEmptyRisiChatHasOnlySystemLines() {
        assertTrue(RisiTalk.emptyRisiChat(emptyList()))
        assertTrue(RisiTalk.emptyRisiChat(listOf(row(MessageEntity.KIND_SYSTEM))))
        assertFalse(RisiTalk.emptyRisiChat(listOf(row(MessageEntity.KIND_SYSTEM), row(MessageEntity.KIND_RISI_CTL))))
        assertFalse(RisiTalk.emptyRisiChat(listOf(row(MessageEntity.KIND_TEXT))))
    }
}
