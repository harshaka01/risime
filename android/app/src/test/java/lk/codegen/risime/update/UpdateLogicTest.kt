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

    @Test fun summaryIsOptionalAndPreferred() {
        assertNull(info.summary) // today's publisher has no summary
        assertEquals("Presence and typing", info.displayNotes())
        val withSummary = VersionJson.parse(published.replace("\"required\": false,", "\"required\": false, \"summary\": \"Short and plain.\","))!!
        assertEquals("Short and plain.", withSummary.summary)
        assertEquals("Short and plain.", withSummary.displayNotes())
        assertEquals("Short and plain.", updateBanner(UpdateState.Available(withSummary))!!.notes)
        // Blank summary falls back to the notes, Markdown stripped.
        val md = info.copy(summary = " ", notes = "## Fixes\n\n- **Gate** scrolls")
        assertEquals("Fixes\n\n• Gate scrolls", md.displayNotes())
        assertNull(info.copy(summary = null, notes = "#  \n").displayNotes())
    }

    @Test fun failureMessagesAreActionable() {
        assertTrue(downloadFailureMessage(java.net.UnknownHostException("x")).contains("no connection"))
        assertTrue(downloadFailureMessage(java.net.SocketTimeoutException()).contains("no connection"))
        assertTrue(downloadFailureMessage(javax.net.ssl.SSLHandshakeException("bad cert")).contains("certificate"))
        assertEquals("Couldn't download the update (network error: reset).", downloadFailureMessage(java.io.IOException("reset")))
        assertTrue(downloadFailureMessage(IllegalStateException("HTTP 404")).contains("HTTP 404"))
        // The HTTP code, in plain words.
        assertEquals("Couldn't download the update: the update server answered HTTP 503.", downloadFailureMessage(UpdateHttpException(503)))
        assertTrue(downloadFailureMessage(UpdateHttpException(404)).startsWith("Couldn't download the update: the update server answered HTTP 404 ("))
        // sha / certificate mismatch.
        assertEquals("The downloaded update failed its security check (checksum mismatch), so nothing was installed.",
            verifyFailureMessage("checksum mismatch"))
        assertTrue(verifyFailureMessage("APK is signed by another key").contains("signed by another key"))
        // The installer's own message (EXTRA_STATUS_MESSAGE) is shown as is.
        assertEquals("Install cancelled. Tap Retry to install the update.", installFailureMessage(3, null))
        assertEquals("App not installed: the installer reported a failure (status 1): INSTALL_FAILED_X.",
            installFailureMessage(1, "INSTALL_FAILED_X"))
        assertEquals("App not installed: it conflicts with the installed app: INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match.",
            installFailureMessage(5, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match"))
        assertEquals("App not installed: not enough storage.", installFailureMessage(6, " "))
        assertEquals("https://risicloud.ai/app/risime/", DOWNLOAD_PAGE_URL)
        assertEquals("Download from website", DOWNLOAD_FROM_WEBSITE)
        assertEquals("Install over the old app — never uninstall: uninstalling deletes your chats.", NEVER_UNINSTALL_TEXT)
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
        // Test-build mirror (-Prisime.updateBaseUrl): plain http only from a loopback base, same port/path.
        val mirror = "http://127.0.0.1:8480/app/risime/"
        assertTrue(urlAllowed("http://127.0.0.1:8480/app/risime/risime-1.apk", mirror))
        assertFalse(urlAllowed("http://127.0.0.1:9999/app/risime/risime-1.apk", mirror))
        assertFalse(urlAllowed("https://127.0.0.1:8480/app/risime/risime-1.apk", mirror)) // scheme must match the base
        assertFalse(urlAllowed("http://evil.example/app/risime/x.apk", "http://evil.example/app/risime/")) // http only for loopback
        assertFalse(urlAllowed("http://127.0.0.1/app/risime/x.apk", base)) // the release base never takes http
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
        // P0-1 B: every foreground, at most every 15 min (was 6 h per process).
        assertEquals(15 * 60_000L, UPDATE_CHECK_INTERVAL_MS)
        assertTrue(shouldCheck(null, 0))
        assertFalse(shouldCheck(1_000, 1_000 + 15 * 60_000L - 1))
        assertTrue(shouldCheck(1_000, 1_000 + 15 * 60_000L))
        assertTrue(shouldCheck(1_000, 2_000, force = true)) // "Check for updates"
        assertTrue(shouldCheck(50_000, 1_000)) // clock went back (new boot): check
    }

    @Test fun bannerStates() {
        assertNull(updateBanner(UpdateState.Idle))
        val avail = updateBanner(UpdateState.Available(info))!!
        assertEquals("RisiMe 0.2.0-nightly.2 is available", avail.title)
        assertEquals("Presence and typing", avail.notes)
        assertEquals("Update", avail.action) // one tap installs
        assertTrue(avail.canDismiss)
        assertFalse(avail.busy)
        val working = updateBanner(UpdateState.Working(info, "Downloading…"))!!
        assertTrue(working.busy)
        assertNull(working.action)
        assertFalse(working.canDismiss)
        assertEquals("Downloading…", working.status)
        val failed = updateBanner(UpdateState.Failed(info, "Update rejected: checksum mismatch"))!!
        assertEquals("Retry", failed.action)
        assertTrue(failed.error)
        assertEquals("RisiMe 0.2.0-nightly.2 wasn't installed", failed.title)
        val progress = updateBanner(UpdateState.Working(info, downloadStep(42), 42))!!
        assertEquals(42, progress.percent)
        assertEquals("Downloading… 42%", progress.status)
        assertEquals("Update rejected: checksum mismatch", failed.status)
        assertTrue(updateBanner(UpdateState.NeedsPermission(info))!!.status!!.contains("Allow"))
        assertNull(updateBanner(UpdateState.Available(info.copy(notes = "  ")))!!.notes)
        // required → no banner (the blocking screen shows instead)
        assertNull(updateBanner(UpdateState.Available(info.copy(required = true))))
        assertNull(updateBanner(UpdateState.Working(info.copy(required = true), "x")))
    }

    @Test fun silentUpdateTargetTable() {
        assertNull(minTargetSdkForSilentUpdate(30))
        assertFalse(requestSilentUpdate(30, 37)) // Android 11: always the confirm dialog
        assertEquals(29, minTargetSdkForSilentUpdate(31))
        assertEquals(29, minTargetSdkForSilentUpdate(32))
        assertEquals(30, minTargetSdkForSilentUpdate(33))
        assertEquals(31, minTargetSdkForSilentUpdate(34))
        assertEquals(33, minTargetSdkForSilentUpdate(35))
        assertEquals(34, minTargetSdkForSilentUpdate(36))
        assertEquals(35, minTargetSdkForSilentUpdate(37))
        assertTrue(requestSilentUpdate(37, 37)) // our targetSdk 37 qualifies everywhere today
        assertFalse(requestSilentUpdate(36, 33))
    }
}
