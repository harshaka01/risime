package lk.codegen.risime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private val base = "https://risicloud.ai/app/risime/"
    private val sha = "ab".repeat(32)
    private val pinned = PINNED_CERT_SHA256

    /** Exactly what scripts/publish-release writes today. */
    private val published = """
        {"versionCode": 20002, "versionName": "0.2.0-nightly.2", "date": "2026-10-07T01:30:00Z",
         "notes": "Presence and typing", "url": "https://risicloud.ai/app/risime/risime-0.2.0-nightly.2.apk",
         "sha256": "$sha", "certSha256": "DA:7B:98:24:15:E5:D1:1D:D9:0A:EE:89:37:16:C7:A0:4E:D0:84:1E:21:89:0F:FC:C8:E0:75:F4:16:C9:9E:2E",
         "required": false, "extra": 1}
    """.trimIndent()

    private val info = VersionJson.parse(published)!!

    @Test fun parsesThePublisherFormat() {
        assertEquals(20002L, info.versionCode)
        assertEquals("0.2.0-nightly.2", info.versionName)
        assertEquals(pinned, info.certSha256) // colons/case normalised
        assertFalse(info.required)
        assertEquals("Presence and typing", info.notes)
    }

    @Test fun rejectsMalformedManifests() {
        assertNull(VersionJson.parse("not json"))
        assertNull(VersionJson.parse("""{"versionName":"x"}"""))
        assertNull(VersionJson.parse(published.replace(sha, "abc"))) // short hash
        // `required` defaults to false when absent.
        assertFalse(VersionJson.parse(published.replace("\"required\": false,", ""))!!.required)
    }

    @Test fun urlAllowlist() {
        assertTrue(urlAllowed("https://risicloud.ai/app/risime/risime-1.apk", base))
        assertFalse(urlAllowed("http://risicloud.ai/app/risime/risime-1.apk", base)) // not https
        assertFalse(urlAllowed("https://evil.example/app/risime/risime-1.apk", base))
        assertFalse(urlAllowed("https://risicloud.ai.evil.example/app/risime/x.apk", base))
        assertFalse(urlAllowed("https://risicloud.ai/app/other/x.apk", base))
        assertFalse(urlAllowed("https://risicloud.ai/app/risime/", base)) // the folder itself
        assertFalse(urlAllowed("https://risicloud.ai/app/risime/../other/x.apk", base))
        assertFalse(urlAllowed("https://risicloud.ai:8443/app/risime/x.apk", base))
        assertFalse(urlAllowed("https://user:pw@risicloud.ai/app/risime/x.apk", base))
        assertFalse(urlAllowed("garbage", base))
    }

    @Test fun decisionAndRequiredGating() {
        assertEquals(UpdateDecision.UpToDate, decide(info, 20002, base))
        assertEquals(UpdateDecision.UpToDate, decide(info, 30000, base))
        assertEquals(UpdateDecision.Available(info), decide(info, 20001, base))
        val req = info.copy(required = true)
        assertEquals(UpdateDecision.Required(req), decide(req, 20001, base))
        assertTrue(decide(info.copy(url = "https://evil.example/x.apk"), 1, base) is UpdateDecision.Rejected)
        assertTrue(decide(info.copy(certSha256 = "00".repeat(32)), 1, base) is UpdateDecision.Rejected)
        assertTrue(decide(null, 1, base) is UpdateDecision.Rejected)

        // Any state carrying a required release blocks; optional ones never do.
        assertEquals(req, UpdateState.Available(req).blocking())
        assertEquals(req, UpdateState.Working(req, "Downloading…").blocking())
        assertEquals(req, UpdateState.Failed(req, "x").blocking())
        assertEquals(req, UpdateState.NeedsPermission(req).blocking())
        assertNull(UpdateState.Available(info).blocking())
        assertNull(UpdateState.Idle.blocking())
    }

    private val good = ApkFacts(sha, "lk.codegen.risime", 20002, listOf(pinned))

    @Test fun verificationChecksEverything() {
        assertEquals(VerifyResult.Ok, verifyApk(info, good, "lk.codegen.risime"))
        assertEquals(VerifyResult.Ok, verifyApk(info, good.copy(sha256 = sha.uppercase()), "lk.codegen.risime"))
        fun fails(f: ApkFacts, i: UpdateInfo = info, pkg: String = "lk.codegen.risime") =
            assertTrue(verifyApk(i, f, pkg) is VerifyResult.Failed)
        fails(good.copy(sha256 = "cd".repeat(32)))
        fails(good.copy(certSha256s = emptyList()))
        fails(good.copy(certSha256s = listOf("00".repeat(32))))
        fails(good.copy(certSha256s = listOf(pinned, "00".repeat(32)))) // extra signer
        fails(good.copy(packageName = "lk.codegen.risime.debug"))
        fails(good.copy(packageName = null))
        fails(good.copy(versionCode = 20001))
        fails(good, i = info.copy(certSha256 = "00".repeat(32)))
        fails(good, pkg = "lk.codegen.risime.debug") // a debug build never installs the release
    }

    @Test fun checkInterval() {
        assertTrue(shouldCheck(null, 0))
        assertFalse(shouldCheck(0, 6 * 3_600_000L - 1))
        assertTrue(shouldCheck(0, 6 * 3_600_000L))
    }
}
