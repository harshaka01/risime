package lk.codegen.risime.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationIdTest {
    private val a = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val b = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"

    @Test
    fun sortedLexicographically() {
        assertEquals("dm:${b}_$a", dmConversationId(a, b))
        assertEquals("dm:${b}_$a", dmConversationId(b, a))
    }

    @Test
    fun lowercased() {
        assertEquals("dm:${b}_$a", dmConversationId(a.uppercase(), b.uppercase()))
    }
}
