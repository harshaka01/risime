package ai.risicloud.risime.ui.friends

import ai.risicloud.risime.data.contactEntities
import ai.risicloud.risime.data.friendEntities
import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.Contact
import ai.risicloud.risime.net.Friend
import ai.risicloud.risime.net.FriendsReply
import ai.risicloud.risime.net.VouchedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FriendsLogicTest {
    @Test fun friendsBecomeContactRows() {
        val rows = friendEntities(
            FriendsReply(friends = listOf(Friend("u1", "+941", "Kamal", "Rise", VouchedBy("u0", "Harsha"), "2026-10-06T08:00:00.000Z"))),
        )
        val r = rows.single()
        assertTrue(r.friend && r.registered)
        assertEquals("Harsha", r.vouchedByName)
        // Pre-v1.6 fallback: registered contacts count as friends, others don't.
        val legacy = contactEntities(listOf(Contact("+1", "A", "C", "a", true), Contact("+2", "B", "C", null, false)))
        assertEquals(listOf(true, false), legacy.map { it.friend })
    }

    @Test fun errorsAndTexts() {
        assertNull(friendsError(ApiResult.Ok(Unit)))
        assertEquals("That phone number isn't valid", friendsError(ApiResult.Error(422, "invalid_phone", "")))
        assertEquals("That email address isn't valid", friendsError(ApiResult.Error(422, "invalid_email", "")))
        assertEquals("Enter a name (1–64 characters)", friendsError(ApiResult.Error(422, "invalid_name", "")))
        assertEquals("Too many requests. Try again in 2 min.", friendsError(ApiResult.Error(429, "rate_limited", "", retryAfterSec = 90)))
        assertEquals("That request is no longer there", friendsError(ApiResult.Error(404, "not_found", "")))
        assertTrue(friendsError(ApiResult.NetworkError(IOException()))!!.startsWith("Can't reach"))
        // The reply never says whether the number is on RisiMe.
        assertFalse(requestSentText("+94771234567").contains("not on", ignoreCase = true))
    }

    @Test fun inviteForm() {
        assertNull(inviteFormError("+94771234567", "kamal@example.com", "Kamal"))
        assertEquals("Enter a valid phone number", inviteFormError(null, "k@e.com", "K"))
        assertEquals("Enter the email they'll sign in with", inviteFormError("+941", "nope", "K"))
        assertEquals("Enter their name (1–64 characters)", inviteFormError("+941", "k@e.com", "  "))
        assertEquals("Enter their name (1–64 characters)", inviteFormError("+941", "k@e.com", "x".repeat(65)))
    }
}
