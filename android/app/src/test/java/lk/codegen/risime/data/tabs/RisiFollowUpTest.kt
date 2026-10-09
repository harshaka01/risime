package lk.codegen.risime.data.tabs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.ui.tabs.RisiHost
import lk.codegen.risime.ui.tabs.sendFromComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Follow-ups: my next message within 3 min of Risi's answer to my request continues with Risi. */
class RisiFollowUpTest {
    private val me = "me-user"
    private val kamal = "kamal"
    private val risi = "risi"
    private val conv = "grp:o"
    private val t0 = 1_000_000L

    private fun ask(id: String, at: Long, from: String = me) = MessageEntity(
        clientMsgId = "ask-$id", messageId = null, conversationId = conv, from = from, to = conv, body = "Asked Risi", serverTs = null,
        localTs = at, status = "SENT", outgoing = from == me, kind = MessageEntity.KIND_RISI_CTL,
        systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), RisiControl.request(id, "ask", "q", null)),
    )

    private fun answer(requestId: String, at: Long, kind: String = "answer") = MessageEntity(
        clientMsgId = "ans-$requestId-$at", messageId = "m-$at", conversationId = conv, from = risi, to = conv, body = "a", serverTs = null,
        localTs = at, status = "DELIVERED", outgoing = false,
        systemJson = ProtocolJson.encodeToString(JsonObject.serializer(), buildJsonObject { put("v", 1); put("kind", kind); put("request_id", requestId); put("answer", "a") }),
    )

    private fun text(from: String, at: Long) = MessageEntity(
        clientMsgId = "t-$from-$at", messageId = null, conversationId = conv, from = from, to = conv, body = "hi", serverTs = null,
        localTs = at, status = "SENT", outgoing = from == me,
    )

    @Test fun anAnswerToMyRequestOpensAFollowUpForThreeMinutes() {
        val ms = listOf(ask("r1", t0), answer("r1", t0 + 5_000))
        assertEquals("ans-r1-${t0 + 5_000}", RisiFollowUp.active(ms, me, t0 + 60_000))
        assertNull(RisiFollowUp.active(ms, me, t0 + 5_000 + RisiFollowUp.WINDOW_MS + 1))
        // Others' messages don't end it.
        assertEquals("ans-r1-${t0 + 5_000}", RisiFollowUp.active(ms + text(kamal, t0 + 6_000), me, t0 + 60_000))
    }

    @Test fun notForSomeoneElsesRequestOrAfterISentSomething() {
        assertNull(RisiFollowUp.active(listOf(ask("r2", t0, from = kamal), answer("r2", t0 + 1)), me, t0 + 10))
        assertNull(RisiFollowUp.active(listOf(ask("r1", t0), answer("r1", t0 + 1), text(me, t0 + 2)), me, t0 + 10))
        // My follow-up ask is waiting for its answer: no chip; its answer opens a new one.
        val chain = listOf(ask("r1", t0), answer("r1", t0 + 1), ask("r3", t0 + 2))
        assertNull(RisiFollowUp.active(chain, me, t0 + 10))
        assertEquals("ans-r3-${t0 + 3}", RisiFollowUp.active(chain + answer("r3", t0 + 3), me, t0 + 10))
        assertNull(RisiFollowUp.active(listOf(answer("r9", t0)), me, t0))
        assertNull(RisiFollowUp.active(listOf(ask("r1", t0), answer("r1", t0 + 1)), null, t0))
    }

    private class Host : RisiHost {
        val asks = mutableListOf<String>()
        override val me = "me"
        override fun ask(text: String) { asks += text }
        override fun summarise() = Unit
        override fun report() = Unit
        override fun act(target: String, action: String, editText: String?, editDue: String?) = Unit
        override fun feedback(callRef: String, rating: String, reason: String?) = Unit
    }

    @Test fun continuingSendsAnAskAndDismissedSendsPlain() {
        val h = Host()
        val plain = mutableListOf<String>()
        sendFromComposer(h, chip = true /* continuing */, text = "and on Friday?", plain = { plain += it })
        sendFromComposer(h, chip = false /* × tapped */, text = "thanks Kamal", plain = { plain += it })
        assertEquals(listOf("and on Friday?"), h.asks)
        assertEquals(listOf("thanks Kamal"), plain)
    }
}
