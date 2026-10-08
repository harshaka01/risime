package lk.codegen.risime.crypto

import lk.codegen.risime.data.backup.BackupException
import lk.codegen.risime.data.backup.DeflatingSink
import lk.codegen.risime.data.backup.PassphraseFloor
import lk.codegen.risime.data.backup.PiecesInputStream
import lk.codegen.risime.data.backup.SecretKind
import lk.codegen.risime.data.backup.bundleLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * §22 through the real core (host build via JNA): every `backup_vectors.json` case (44), and the
 * app's streams (Deflater(nowrap) in, inflate out) through a real writer and reader: a file made on
 * one phone opens on a reinstalled one with the recovery key only, after the verify pass.
 */
class BackupCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun host() = RealMls.assumeHostLibrary()

    private val user = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"

    @Test fun everyVectorCaseRunsInTheCore() {
        val json = File(System.getProperty("risime.contract"), "backup_vectors.json").readText()
        assertEquals(44, UniffiBackupTools().vectorsCheck(json))
    }

    @Test fun recoveryKeyInputAndThePassphraseFloor() {
        val tools = UniffiBackupTools()
        val dev = RealMls.device(user, "c0a80101-0000-4000-8000-000000000001")
        val rk = dev.engine.backupKeys!!.setup(user).recoveryKey
        assertEquals(rk, tools.normalizeRecoveryKey(rk.lowercase().replace("-", " ")))
        val typo = rk.toCharArray().also { it[0] = if (it[0] == 'A') 'B' else 'A' }.concatToString()
        try {
            tools.normalizeRecoveryKey(typo)
            fail("typo accepted")
        } catch (e: BackupException) {
            assertEquals(BackupException.Kind.Typo, e.kind)
        }
        try {
            tools.normalizeRecoveryKey(rk.dropLast(1))
            fail("27 characters accepted")
        } catch (e: BackupException) {
            assertEquals(BackupException.Kind.Malformed, e.kind)
        }
        assertEquals(PassphraseFloor.TooShort, tools.passphraseFloor("short one", null))
        assertEquals(PassphraseFloor.Ok, tools.passphraseFloor("correct horse battery staple", null))
        assertEquals(PassphraseFloor.PhoneNumber, tools.passphraseFloor("my number is 771234567 ok", "+94771234567"))
        dev.close()
    }

    @Test fun aFileFromOnePhoneOpensOnAReinstalledOneWithTheRecoveryKey() {
        val a = RealMls.device(user, "c0a80101-0000-4000-8000-000000000001")
        val keys = a.engine.backupKeys!!
        val setup = keys.setup(user)
        val lines = listOf("""{"v":1,"type":"backup"}""") + (1..3000).map { """{"type":"message","n":$it,"body":"hello ${"x".repeat(it % 50)}"}""" }
        val backupId = UUID.randomUUID().toString()
        val out = File(tmp.root, "b.risimebk")
        val w = keys.writer(user, backupId, "2026-10-08T02:00:00.000Z", "test", null, out)
        val sink = DeflatingSink { w.write(it) }
        lines.forEach { sink.write((it + "\n").toByteArray()) }
        sink.finish()
        val written = w.finish()
        assertEquals(backupId, written.backupId)
        assertEquals(out.length(), written.size)

        // Same phone: the local BK opens it.
        val same = keys.reader(user, out, null, null)
        same.verify()
        assertEquals(lines, bundleLines(PiecesInputStream { same.read() }).asSequence().toList())

        // Reinstall: a new phone has no key until the recovery key unlocks the file's record.
        val b = RealMls.device(user, "c0a80101-0000-4000-8000-000000000002")
        val bk = b.engine.backupKeys!!
        try {
            bk.reader(user, out, null, null)
            fail("opened without the key")
        } catch (e: BackupException) {
            assertEquals(BackupException.Kind.NoKey, e.kind)
        }
        val record = UniffiBackupTools().fileInfo(out).keyRecord!!
        try {
            bk.unlock(user, record, "AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA", SecretKind.RecoveryKey, true)
            fail("a wrong key unlocked")
        } catch (e: BackupException) {
            assertTrue(e.kind == BackupException.Kind.WrongKey || e.kind == BackupException.Kind.Typo)
        }
        assertEquals(written.bkId, bk.unlock(user, record, setup.recoveryKey, SecretKind.RecoveryKey, true))
        val r = bk.reader(user, out, backupId, written.bkId)
        r.verify()
        assertEquals(lines, bundleLines(PiecesInputStream { r.read() }).asSequence().toList())
        assertEquals(setup.recoveryKey, bk.recoveryKey())

        // Another account's file is refused before anything is read.
        val other = RealMls.device("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f", "c0a80101-0000-4000-8000-000000000003")
        try {
            other.engine.backupKeys!!.reader("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f", out, null, null)
            fail("another account's backup opened")
        } catch (e: BackupException) {
            assertEquals(BackupException.Kind.WrongAccount, e.kind)
        }

        // A changed byte fails the verify pass; nothing is released before it.
        val bytes = out.readBytes()
        bytes[bytes.size - 100] = (bytes[bytes.size - 100].toInt() xor 1).toByte()
        val bad = File(tmp.root, "bad.risimebk").also { it.writeBytes(bytes) }
        val br = bk.reader(user, bad, null, null)
        try {
            br.read()
            fail("read before verify")
        } catch (e: BackupException) {
            assertEquals(BackupException.Kind.Malformed, e.kind)
        }
        try {
            br.verify()
            fail("tampered file verified")
        } catch (e: BackupException) {
            assertEquals(BackupException.Kind.Integrity, e.kind)
        }

        // Change recovery key: same BK, a new R; forget removes every key.
        val rotated = keys.rotateRecoveryKey(user)
        assertNotEquals(setup.recoveryKey, rotated.recoveryKey)
        keys.forget()
        assertNull(keys.keyIds().current)
        listOf(a, b, other).forEach { it.close() }
    }
}
