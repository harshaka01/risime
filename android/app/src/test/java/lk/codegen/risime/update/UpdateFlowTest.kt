package lk.codegen.risime.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0-1 state machine: checks, installer statuses (late ones too), cold start, Range answers. */
class UpdateFlowTest {
    private fun info(code: Long = 20031, name: String = "0.2.0-nightly.31") = UpdateInfo(
        code, name, null, "notes", "https://risicloud.ai/app/risime/risime-$name.apk", "ab".repeat(32), PINNED_CERT_SHA256, false,
    )

    private val n31 = info()
    private val n32 = info(20032, "0.2.0-nightly.32")

    @Test fun checkKeepsARunningUpdateAndItsFailure() {
        val working = UpdateState.Working(n31, "Downloading… 40%", 40)
        // The same release offered again: the download, the permission wait and the error stay on screen.
        assertEquals(working, stateAfterCheck(working, UpdateDecision.Available(n31)))
        val failed = UpdateState.Failed(n31, "App not installed: x.")
        assertEquals(failed, stateAfterCheck(failed, UpdateDecision.Available(n31)))
        assertEquals(UpdateState.NeedsPermission(n31), stateAfterCheck(UpdateState.NeedsPermission(n31), UpdateDecision.Available(n31)))
        // A newer release replaces an old failure, but never interrupts a running update.
        assertEquals(UpdateState.Available(n32), stateAfterCheck(failed, UpdateDecision.Available(n32)))
        assertSame(working, stateAfterCheck(working, UpdateDecision.Available(n32)))
        assertEquals(UpdateState.Available(n31), stateAfterCheck(UpdateState.Idle, UpdateDecision.Available(n31)))
        assertEquals(UpdateState.Available(n31), stateAfterCheck(UpdateState.Idle, UpdateDecision.Required(n31)))
        // Up to date clears a stale offer, not a running install; a rejected manifest changes nothing.
        assertEquals(UpdateState.Idle, stateAfterCheck(UpdateState.Available(n31), UpdateDecision.UpToDate))
        assertSame(working, stateAfterCheck(working, UpdateDecision.UpToDate))
        assertEquals(failed, stateAfterCheck(failed, UpdateDecision.Rejected("x")))
    }

    @Test fun installFailureWhileWorkingShowsTheInstallersMessage() {
        val out = stateAfterInstallStatus(UpdateState.Working(n31, "Installing…"), n31, 7, 7, 4, "INSTALL_PARSE_FAILED_NOT_APK")
        assertFalse(out.ignored)
        assertEquals(UpdateState.Failed(n31, "App not installed: the downloaded file isn't a valid app: INSTALL_PARSE_FAILED_NOT_APK."), out.state)
    }

    @Test fun lateStatusesAreNoLongerDropped() {
        // P0-1 D: the banner was dismissed (Idle), or the process restarted: the persisted release is used.
        val afterDismiss = stateAfterInstallStatus(UpdateState.Idle, n31, null, 7, 1, "INSTALL_FAILED_INSUFFICIENT_STORAGE")
        assertEquals(UpdateState.Failed(n31, "App not installed: the installer reported a failure (status 1): INSTALL_FAILED_INSUFFICIENT_STORAGE."), afterDismiss.state)
        // A status on an Available banner (e.g. a check ran meanwhile) also lands.
        val onAvailable = stateAfterInstallStatus(UpdateState.Available(n31), n31, 7, 7, 3, null)
        assertEquals(UpdateState.Failed(n31, "Install cancelled. Tap Retry to install the update."), onAvailable.state)
        // Nothing known about the release at all: ignored rather than inventing one.
        assertTrue(stateAfterInstallStatus(UpdateState.Idle, null, null, 7, 1, null).ignored)
    }

    @Test fun staleSessionStatusIsIgnoredWhileANewerOneRuns() {
        val working = UpdateState.Working(n31, "Installing…")
        val stale = stateAfterInstallStatus(working, n31, 9, 7, 3, null)
        assertTrue(stale.ignored)
        assertSame(working, stale.state)
    }

    @Test fun successAndPendingUserAction() {
        assertEquals(UpdateState.Idle, stateAfterInstallStatus(UpdateState.Working(n31, "Installing…"), n31, 7, 7, INSTALL_SUCCESS, null).state)
        assertEquals(
            UpdateState.Working(n31, "Confirm the update…"),
            stateAfterInstallStatus(UpdateState.Working(n31, "Installing…"), n31, 7, 7, INSTALL_PENDING_USER_ACTION, null).state,
        )
    }

    @Test fun coldStartRestoresAFailure() {
        val failed = UpdateAttempt(20031, "0.2.0-nightly.31", UpdateStep.FAILED, status = 1, statusMessage = "INSTALL_FAILED_X", error = "App not installed: x.")
        assertEquals(UpdateState.Failed(n31, "App not installed: x."), stateOnStart(failed, n31, installedVersionCode = 20030))
        // Installed meanwhile (e.g. from the website): nothing to show.
        assertEquals(UpdateState.Idle, stateOnStart(failed, n31, installedVersionCode = 20031))
        // Cut off mid-way: the worker shows it when it resumes.
        assertEquals(UpdateState.Idle, stateOnStart(failed.copy(step = UpdateStep.DOWNLOAD), n31, 20030))
        assertEquals(UpdateState.Idle, stateOnStart(null, n31, 20030))
        assertEquals(UpdateState.Idle, stateOnStart(failed, n32, 20030)) // a different release is pending now
    }

    @Test fun attemptRoundTrips() {
        val a = UpdateAttempt(20031, "0.2.0-nightly.31", UpdateStep.INSTALL, sessionId = 12, atMs = 5, wasForeground = true)
        assertEquals(a, UpdateAttempt.decode(a.encode()))
        assertNull(UpdateAttempt.decode("{broken"))
        assertNull(UpdateAttempt.decode(null))
    }

    @Test fun rangeAnswers() {
        assertEquals(RangeOutcome.Restart(1000L), rangeOutcome(0, 200, null, 1000))
        assertEquals(RangeOutcome.Restart(null), rangeOutcome(0, 200, null, -1))
        // Resume: 206 for exactly our bytes appends, with the total from Content-Range.
        assertEquals(RangeOutcome.Append(400, 1000L), rangeOutcome(400, 206, "bytes 400-999/1000", 600))
        assertEquals(RangeOutcome.Append(400, 1000L), rangeOutcome(400, 206, "bytes 400-999/*", 600))
        // The server ignored Range: start over with the full body.
        assertEquals(RangeOutcome.Restart(1000L), rangeOutcome(400, 200, null, 1000))
        // Other bytes than asked for, or no Content-Range: drop the partial file.
        assertEquals(RangeOutcome.Mismatch, rangeOutcome(400, 206, "bytes 0-999/1000", 1000))
        assertEquals(RangeOutcome.Mismatch, rangeOutcome(400, 206, null, 600))
        // 416: the partial file already has everything (the hash decides).
        assertEquals(RangeOutcome.Complete, rangeOutcome(1000, 416, "bytes */1000", 0))
        assertEquals(RangeOutcome.Fail(416), rangeOutcome(0, 416, null, 0))
        assertEquals(RangeOutcome.Fail(404), rangeOutcome(400, 404, null, 10))
        assertEquals(RangeOutcome.Fail(206), rangeOutcome(0, 206, "bytes 0-9/10", 10))
        assertNull(parseContentRange("garbage"))
    }

    @Test fun progressText() {
        assertNull(downloadPercent(10, null))
        assertNull(downloadPercent(10, 0))
        assertEquals(0, downloadPercent(0, 1000))
        assertEquals(42, downloadPercent(420, 1000))
        assertEquals(100, downloadPercent(2000, 1000))
        assertEquals("Downloading…", downloadStep(null))
        assertEquals("Downloading… 42%", downloadStep(42))
    }
}
