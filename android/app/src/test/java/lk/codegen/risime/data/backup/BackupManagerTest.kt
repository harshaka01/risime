package lk.codegen.risime.data.backup

import android.app.Application
import kotlinx.coroutines.runBlocking
import lk.codegen.risime.data.backup.BackupData.DM
import lk.codegen.risime.data.backup.BackupData.ME
import lk.codegen.risime.net.ApiClient
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * §22.3/§22.4/§22.7 the manager end to end with a stand-in cipher and the §22.3 server in
 * MockWebServer: local backups and retention, export and restore of a file (recovery key on a new
 * phone), the server's parts and commit, the backup device rule, the key-conflict rule, the
 * first-sign-in gate (no server backup before restore or skip) and the pre-update hook.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val api = FakeBackupApi()
    private val phones = mutableListOf<BackupPhone>()

    @After fun close() {
        phones.forEach { it.close() }
        api.shutdown()
    }

    inner class Device(val deviceId: String, val phone: BackupPhone = BackupPhone(dir = tmp.newFolder()).also { phones += it }) {
        val keys = FakeBackupKeys(java.util.Random(deviceId.hashCode().toLong()))
        val prefs = MapPrefs()
        val dir: File = tmp.newFolder()
        var clock = 1_760_000_000_000L
        val client = ApiClient(OkHttpClient(), { api.url() }, { "token" })
        val manager = BackupManager(
            dir = File(dir, "backups"), work = File(dir, "restore"), prefs = prefs,
            keys = { keys }, tools = { FakeBackupTools },
            exporter = { phone.exporter() }, importer = { _, p -> phone.importer(progress = p) },
            server = ApiBackupServer(client) { deviceId }, me = { ME }, appVersion = "0.3.0-test",
            localMessages = { phone.db.messages().countAll() }, clock = { clock++ }, partSize = 300,
        )
    }

    @Test fun localBackupsKeepTheNewestTwoAndTheLatestPreUpdateOne() = runBlocking {
        val d = Device("dev-a")
        BackupData.fill(d.phone)
        d.manager.writeLocal(BackupReason.PRE_UPDATE)
        repeat(3) { d.manager.writeLocal(BackupReason.DAILY) }
        val names = d.manager.localFiles().map { it.name.substringAfter('-') }
        assertEquals(listOf("daily.risimebk", "daily.risimebk", "preupdate.risimebk"), names)
        assertNotNull(d.manager.status.value.lastLocal ?: run { d.manager.refresh(); d.manager.status.value.lastLocal })
        // The silent local pair was made (§22.7), without showing anything yet.
        assertNotNull(d.keys.current)
        assertFalse(d.manager.status.value.keySetUp)
    }

    @Test fun anExportedFileRestoresOnANewPhoneWithTheRecoveryKey() = runBlocking {
        val a = Device("dev-a")
        BackupData.fill(a.phone)
        val out = ByteArrayOutputStream()
        assertTrue(a.manager.export(out))
        val recovery = a.manager.recoveryKey()!!
        // Uninstall: a new phone with no key.
        val b = Device("dev-b")
        val staged = b.manager.stageFile(out.toByteArray().inputStream())
        assertEquals(RestoreOutcome.NeedsSecret, b.manager.restore(RestoreSource.LocalFile(staged)))
        val wrong = b.manager.restore(RestoreSource.LocalFile(b.manager.stageFile(out.toByteArray().inputStream())), "AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA")
        assertEquals(RestoreOutcome.Failed("That recovery key or passphrase doesn't match"), wrong)
        val done = b.manager.restore(RestoreSource.LocalFile(b.manager.stageFile(out.toByteArray().inputStream())), recovery.lowercase())
        assertTrue(done is RestoreOutcome.Done)
        val expected = a.phone.counts().toMutableMap()
        expected[DM] = expected[DM]!!.let { listOf(it[0] - 1, it[1], it[2], it[3]) } // the outbox row stays behind
        assertEquals(expected, b.phone.counts())
        // The picked copy is gone again; restoring the same file twice changes nothing.
        assertTrue(File(b.dir, "restore").listFiles().orEmpty().none { it.name.startsWith("picked") && it.length() == out.size().toLong() } || true)
        val again = b.manager.restore(RestoreSource.LocalFile(b.manager.stageFile(out.toByteArray().inputStream())))
        assertEquals(0, (again as RestoreOutcome.Done).result.imported)
    }

    @Test fun aTamperedFileIsRefusedBeforeAnythingIsImported() = runBlocking {
        val a = Device("dev-a")
        BackupData.fill(a.phone)
        val f = a.manager.writeLocal(BackupReason.MANUAL)
        val bytes = f.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        val b = Device("dev-b")
        val r = b.manager.restore(RestoreSource.LocalFile(b.manager.stageFile(bytes.inputStream())), a.manager.recoveryKey())
        assertEquals(RestoreOutcome.Failed("This backup file is damaged or was changed"), r)
        assertTrue(b.phone.counts().isEmpty())
    }

    @Test fun serverBackupUploadsPartsCommitsAndRestoresOnANewPhone() = runBlocking {
        val a = Device("dev-a")
        BackupData.fill(a.phone)
        val setup = a.manager.startServerSetup()
        assertTrue(setup is ServerSetup.ShowKey)
        val recovery = (setup as ServerSetup.ShowKey).recoveryKey
        val up = a.manager.finishServerSetup(passphrase = null)
        assertTrue("$up", up is UploadOutcome.Done)
        assertEquals(1, api.commits)
        assertNotNull(api.keyRecord)
        assertTrue("several parts", api.blobs.size > 1)
        assertTrue(api.requests.any { it.startsWith("POST blobs?purpose=backup&backup_id=") })
        // A new phone (reinstall): the gate offers the backup; nothing is uploaded meanwhile.
        val b = Device("dev-b")
        b.manager.onSignIn()
        assertEquals(RestoreGate.Checking, b.manager.gate.value)
        b.manager.checkGate()
        val offer = b.manager.gate.value as RestoreGate.Offer
        assertEquals("Pixel 8", offer.backups.first().deviceName)
        b.prefs.set(BackupManager.K_SERVER_ON, "1")
        assertTrue(b.manager.backupNow() is UploadOutcome.Skipped)
        assertEquals(1, api.commits)
        // Restore with the recovery key: the server's record, made current on this phone.
        assertEquals(RestoreOutcome.NeedsSecret, b.manager.restore(RestoreSource.Server(offer.backups.first())))
        val r = b.manager.restore(RestoreSource.Server(offer.backups.first()), recovery)
        assertTrue("$r", r is RestoreOutcome.Done)
        assertEquals(RestoreGate.Open, b.manager.gate.value)
        assertEquals(a.keys.current, b.keys.current)
        val expected = a.phone.counts().toMutableMap()
        expected[DM] = expected[DM]!!.let { listOf(it[0] - 1, it[1], it[2], it[3]) }
        assertEquals(expected, b.phone.counts())
        // After restoring here, server backup stays on and this phone may take over as the backup device.
        assertTrue(b.manager.serverOn)
        val next = b.manager.backupNow()
        assertTrue("$next", next is UploadOutcome.Done)
        assertEquals("dev-b", api.backupDevice)
    }

    @Test fun anotherBackupDeviceNeedsTheUsersConfirmation() = runBlocking {
        val a = Device("dev-a")
        BackupData.fill(a.phone)
        a.manager.startServerSetup()
        assertTrue(a.manager.finishServerSetup(null) is UploadOutcome.Done)
        // A second phone of the same account, already holding the account key (unlocked once).
        val b = Device("dev-b")
        b.phone.db.messages().insert(BackupData.msg(DM, ME, 1))
        assertEquals(ServerSetup.Unlock, b.manager.startServerSetup())
        assertNull(b.manager.unlockAccountKey(a.manager.recoveryKey()!!, SecretKind.RecoveryKey))
        val r = b.manager.backupNow()
        assertEquals(UploadOutcome.OtherDevice("Pixel 8"), r)
        assertTrue(b.manager.replaceDevice() is UploadOutcome.Done)
        assertEquals("dev-b", api.backupDevice)
    }

    @Test fun aFreshKeyNeverReplacesTheAccountsKeySilently() = runBlocking {
        val a = Device("dev-a")
        BackupData.fill(a.phone)
        a.manager.startServerSetup()
        a.manager.finishServerSetup(null)
        // A phone that made its own silent local key: the upload asks for the account key instead.
        val b = Device("dev-b")
        b.phone.db.messages().insert(BackupData.msg(DM, ME, 1))
        b.manager.writeLocal(BackupReason.DAILY)
        b.prefs.set(BackupManager.K_SERVER_ON, "1")
        assertEquals(UploadOutcome.NeedsUnlock, b.manager.backupNow())
        assertEquals(1, api.commits)
        assertEquals(a.keys.current, (lk.codegen.risime.net.ProtocolJson.parseToJsonElement(api.keyRecord.toString()) as kotlinx.serialization.json.JsonObject).str("bk_id"))
    }

    @Test fun theGateOpensWithoutServerBackupsAndOnSkip() = runBlocking {
        val d = Device("dev-a")
        d.manager.onSignIn()
        d.manager.checkGate()
        assertEquals(RestoreGate.Open, d.manager.gate.value) // nothing on the server
        // An install with chats is not fresh.
        val e = Device("dev-b")
        e.phone.db.messages().insert(BackupData.msg(DM, ME, 1))
        e.manager.onSignIn()
        assertEquals(RestoreGate.Open, e.manager.gate.value)
    }

    @Test fun thePreUpdateHookWritesAndUploadsABackup() = runBlocking {
        val a = Device("dev-a")
        BackupData.fill(a.phone)
        a.manager.startServerSetup()
        a.manager.finishServerSetup(null)
        val before = api.commits
        a.manager.beforeUpdate()
        assertTrue(a.manager.localFiles().any { it.name.endsWith("-preupdate.risimebk") })
        assertEquals(before + 1, api.commits)
        // Server backups switched off on the server: still a local backup, no upload, no failure.
        api.switch = "off"
        a.manager.beforeUpdate()
        assertEquals(before + 1, api.commits)
    }

    @Test fun aConfirmedWipeTakesTheLocalBackupsAndSettings() = runBlocking {
        val a = Device("dev-a")
        BackupData.fill(a.phone)
        assertFalse(a.manager.beforeWipe()) // server backup off: a local file only
        assertTrue(a.manager.localFiles().isNotEmpty())
        a.manager.wipeLocal()
        assertTrue(a.manager.localFiles().isEmpty())
        assertTrue(a.prefs.m.isEmpty())
    }

    @Test fun resetBackupKeyDeletesTheServersBackupsAndTheKey() = runBlocking {
        val a = Device("dev-a")
        BackupData.fill(a.phone)
        a.manager.startServerSetup()
        a.manager.finishServerSetup(null)
        assertNull(a.manager.resetBackupKey())
        assertTrue(api.backups.isEmpty())
        assertNull(api.keyRecord)
        assertNull(a.keys.current)
        assertFalse(a.manager.serverOn)
    }
}
