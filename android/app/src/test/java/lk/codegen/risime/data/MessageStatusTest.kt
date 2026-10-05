package lk.codegen.risime.data

import lk.codegen.risime.data.MessageStatus.DELIVERED
import lk.codegen.risime.data.MessageStatus.FAILED
import lk.codegen.risime.data.MessageStatus.PENDING
import lk.codegen.risime.data.MessageStatus.READ
import lk.codegen.risime.data.MessageStatus.SENT
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageStatusTest {
    @Test
    fun movesForward() {
        assertEquals(SENT, PENDING.advance(SENT))
        assertEquals(DELIVERED, SENT.advance(DELIVERED))
        assertEquals(READ, DELIVERED.advance(READ))
        assertEquals(READ, PENDING.advance(READ)) // status event can overtake a lost send reply
    }

    @Test
    fun neverMovesBackward() {
        assertEquals(READ, READ.advance(DELIVERED))
        assertEquals(READ, READ.advance(SENT))
        assertEquals(DELIVERED, DELIVERED.advance(SENT))
        assertEquals(SENT, SENT.advance(PENDING))
    }

    @Test
    fun failedOnlyFromPending() {
        assertEquals(FAILED, PENDING.advance(FAILED))
        assertEquals(SENT, SENT.advance(FAILED))
        assertEquals(DELIVERED, FAILED.advance(DELIVERED))
    }

    @Test
    fun wireNames() {
        assertEquals("delivered", DELIVERED.wire)
        assertEquals(READ, MessageStatus.fromWire("read"))
        assertEquals(null, MessageStatus.fromWire("bogus"))
    }

    @Test
    fun onlyFailedCanBeRetried() {
        org.junit.Assert.assertEquals(MessageStatus.PENDING, MessageStatus.FAILED.retry())
        for (st in MessageStatus.entries.filter { it != MessageStatus.FAILED }) {
            org.junit.Assert.assertNull(st.retry())
        }
    }
}
