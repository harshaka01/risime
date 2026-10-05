package lk.codegen.risime.ui.common

import lk.codegen.risime.net.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PresenceFormatTest {
    private val zone = ZoneId.of("Asia/Colombo") // +05:30
    private val now = Instant.parse("2026-10-06T10:00:00Z").toEpochMilli() // 15:30 local

    private fun seen(iso: String?) = presenceLabel(Presence("u", false, iso), now, zone)

    @Test fun labels() {
        assertNull(presenceLabel(null, now, zone))
        assertEquals("online", presenceLabel(Presence("u", true), now, zone))
        assertNull(seen(null)) // never connected
        assertEquals("last seen today at 13:40", seen("2026-10-06T08:10:00.000Z"))
        assertEquals("last seen yesterday at 23:00", seen("2026-10-05T17:30:00.000Z"))
        assertEquals("last seen 1 Feb", seen("2026-02-01T00:00:00.000Z"))
        assertEquals("last seen 3 Oct 2025", seen("2025-10-03T06:00:00.000Z"))
        assertNull(seen("garbage"))
    }
}
