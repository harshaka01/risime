package lk.codegen.risime.calls

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** §23.5: notifications already in the shade are redacted when a share starts (and restored after). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenSharingTest {
    @After
    fun reset() = ScreenSharing.set(false)

    @Test
    fun aShareThatStartsRefreshesThePostedNotifications() = runTest {
        ScreenSharing.set(false)
        val seen = mutableListOf<Boolean>()
        ScreenSharing.watch(backgroundScope) { seen += it }
        runCurrent()
        assertEquals("nothing at subscription", emptyList<Boolean>(), seen)
        ScreenSharing.set(true)
        runCurrent()
        ScreenSharing.set(true)
        runCurrent()
        ScreenSharing.set(false)
        runCurrent()
        assertEquals(listOf(true, false), seen)
    }
}
