package lk.codegen.risime.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Settings → About shows [PROTOCOL_VERSION]; keep it equal to the contract header. */
class ProtocolVersionTest {
    @Test fun matchesContractHeader() {
        // Unit tests run with the app module as working directory.
        val protocol = File("../../contract/v1/PROTOCOL.md")
        assertTrue("PROTOCOL.md not found at ${protocol.absolutePath}", protocol.isFile)
        val header = protocol.useLines { it.first() }
        assertTrue("header '$header' is not v$PROTOCOL_VERSION", header.contains(" v$PROTOCOL_VERSION "))
    }
}
