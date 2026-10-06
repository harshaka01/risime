package lk.codegen.risime.data.deletes

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.BlobFetch
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeReactionDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.ChatStateEntity
import lk.codegen.risime.data.db.DeleteOutboxEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.db.ReactionEntity
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A version-1 TimeUUID at [ms] (+[n] ticks), for ordering tests. */
fun tu(ms: Long, n: Int = 0): String {
    val ticks = ms * 10_000 + 0x01B21DD213814000L + n
    val low = ticks and 0xffffffffL
    val mid = (ticks ushr 32) and 0xffffL
    val hi = (ticks ushr 48) and 0x0fffL
    val msb = (low shl 32) or (mid shl 16) or 0x1000L or hi
    return java.util.UUID(msb, -0x7ffffffffffffffeL).toString()
}

fun iso(ms: Long): String = ChatEngine.isoMillis(ms)

/** §15.4–§15.6 the receive side through ChatEngine + MlsPipeline with the fake core. */
class DeleteReceiveTest {
    private val me = "u-me"
    private val bob = "u-bob"
    private val kamal = "u-kamal"
    private val dm = dmConversationId(me, bob)
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val messages = FakeMessageDao()
    private val reactions = FakeReactionDao()
    private val dao = FakeDeleteDao(messages, reactions)
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "dev-me")
    private val pending = FakeMlsPendingDao()
    private val logs = mutableListOf<String>()
    private var applied = 0
    private val base = 1_791_000_000_000L
    private var now = base
    private val images = RecordingImages()
    private var inTx = false

    private fun TestScope.engine() = ChatEngine(
        messages = messages, sync = sync,
        tx = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T { inTx = true; try { return block() } finally { inTx = false } }
        },
        scope = this, realtime = { realtime }, meId = { me }, clock = { now },
        mls = MlsPipeline({ mls }, pending, log = { logs += it }),
        mlsEngine = { mls }, reactionsDao = reactions, groupsEnabled = { true },
        groups = GroupStore(FakeGroupDao(), FakeGroupOpDao(), messages, { "dev-me" }, { mls.groupMeta(it) }, { now }),
        blobs = { BlobFetch.Gone },
        images = images,
        deletes = dao, onDeletesApplied = { applied++ }, log = { logs += it },
    )

    inner class RecordingImages : lk.codegen.risime.data.media.ImageHooks {
        val purgedInTx = mutableListOf<Pair<String, Boolean>>()
        val unlinkedInTx = mutableListOf<Pair<String, Boolean>>()
        override suspend fun stored(row: MessageEntity, envelope: lk.codegen.risime.data.media.ImageEnvelope) = Unit
        override fun received() = Unit
        override suspend fun envelope(clientMsgId: String): ByteArray? = null
        override suspend fun deleted(clientMsgId: String) = Unit
        override suspend fun purgeRow(clientMsgId: String): List<java.io.File> { purgedInTx += clientMsgId to inTx; return emptyList() }
        override fun afterPurge(clientMsgId: String, files: List<java.io.File>) { unlinkedInTx += clientMsgId to inTx }
    }

    private fun ev(json: String) = ProtocolJson.decodeFromString<Event>(json)

    private fun dmMessage(mid: String, from: String, dev: String, text: String, ts: Long) = ev(
        """{"event_id":"$mid","kind":"message","data":{"message_id":"$mid","client_msg_id":"cm-$mid","conversation_id":"$dm","from":"$from",
        "to":"${if (from == me) bob else me}","from_device":"$dev","ciphertext":"${FakeMlsEngine.ciphertext(1, 1, from, dev, String(MlsPayload.text(text)))}",
        "generation":1,"epoch":1,"server_ts":"${iso(ts)}"}}""",
    )

    private fun grpMessage(mid: String, from: String, dev: String, text: String, ts: Long, epoch: Long = 1) = ev(
        """{"event_id":"$mid","kind":"message","data":{"message_id":"$mid","client_msg_id":"cm-$mid","conversation_id":"$grp","from":"$from",
        "from_device":"$dev","ciphertext":"${FakeMlsEngine.ciphertext(1, epoch, from, dev, String(MlsPayload.text(text)))}",
        "generation":1,"epoch":$epoch,"server_ts":"${iso(ts)}"}}""",
    )

    /** An e2ee delete event; [envTargets]/[aadTargets] default to the event's targets (to test the binding). */
    private fun deleteEvent(
        conv: String, did: String, from: String, dev: String, targets: List<Triple<String, String?, Long?>>, ts: Long,
        envTargets: List<String> = targets.map { it.first }, aadTargets: List<String> = targets.map { it.first }, epoch: Long = 1,
    ): Event {
        val ct = FakeMlsEngine.deleteCiphertext(1, epoch, from, dev, MlsPayload.delete(envTargets), DeleteAad.encode(aadTargets))
        val tj = targets.joinToString(",") { (id, f, t) -> """{"message_id":"$id","from":${f?.let { "\"$it\"" }},"server_ts":${t?.let { "\"${iso(it)}\"" }}}""" }
        val to = if (conv == dm) ""","to":"${if (from == me) bob else me}"""" else ""
        return ev(
            """{"event_id":"$did","kind":"delete","data":{"message_id":"$did","client_msg_id":"cd-$did","conversation_id":"$conv","from":"$from"$to,
            "from_device":"$dev","targets":[$tj],"ciphertext":"$ct","generation":1,"epoch":$epoch,"server_ts":"${iso(ts)}"}}""",
        )
    }

    private fun plainDelete(did: String, from: String, targets: List<Triple<String, String?, Long?>>, ts: Long, cid: String = "cd-$did"): Event {
        val tj = targets.joinToString(",") { (id, f, t) -> """{"message_id":"$id","from":${f?.let { "\"$it\"" }},"server_ts":${t?.let { "\"${iso(it)}\"" }}}""" }
        return ev(
            """{"event_id":"$did","kind":"delete","data":{"message_id":"$did","client_msg_id":"$cid","conversation_id":"$dm","from":"$from",
            "to":"${if (from == me) bob else me}","targets":[$tj],"server_ts":"${iso(ts)}"}}""",
        )
    }

    private fun joinDm() { mls.groups[dm] = GroupRef(dm, 1, 1) }

    private fun joinGroup(admins: List<String> = listOf(kamal)) {
        mls.groups[grp] = GroupRef(grp, 1, 1)
        mls.metas[grp] = GroupMeta(name = "Pilot", admins = admins)
    }

    private fun row(mid: String) = messages.byMessageIdSync(mid)

    private fun FakeMessageDao.byMessageIdSync(mid: String) = rows.values.firstOrNull { it.messageId == mid }

    @Test fun senderDeletesInADmTheRowBecomesATombstone() = runTest {
        val e = engine()
        joinDm()
        val m = tu(base)
        e.onEvents(listOf(dmMessage(m, bob, "dev-b", "secret", base)))
        assertEquals(1, messages.unreadIncoming().size)
        reactions.rows[listOf(dm, m, me, "👍")] = ReactionEntity(dm, m, me, "👍", "add", "add", iso(base), tu(base, 1), false, null, base)
        e.onEvents(listOf(deleteEvent(dm, tu(base + 60_000), bob, "dev-b2", listOf(Triple(m, bob, base)), base + 60_000)))
        val r = row(m)!!
        assertEquals(MessageEntity.KIND_DELETED, r.kind)
        assertEquals("", r.body)
        assertEquals(bob, r.deletedBy)
        assertFalse(r.deletedByAdmin)
        assertEquals("This message was deleted", lk.codegen.risime.ui.chat.tombstoneText(r, me))
        assertTrue("never unread", messages.unreadIncoming().isEmpty())
        assertTrue("reactions purged", reactions.rows.isEmpty())
        assertEquals(1, applied)
        // A later reaction to the tombstone is dropped.
        e.onEvents(listOf(ev("""{"event_id":"${tu(base + 70_000)}","kind":"message","data":{"message_id":"${tu(base + 70_000)}","client_msg_id":"r1","conversation_id":"$dm","from":"$bob","to":"$me","from_device":"dev-b","ciphertext":"${FakeMlsEngine.ciphertext(1, 1, bob, "dev-b", String(MlsPayload.reaction(m, "❤️", "add")))}","generation":1,"epoch":1,"server_ts":"${iso(base + 70_000)}"}}""")))
        assertTrue(reactions.rows.isEmpty())
        // No §13.3 line from any of this.
        assertTrue(messages.rows.values.none { it.system })
    }

    @Test fun myOtherDeviceDeletesMyMessageYouDeleted() = runTest {
        val e = engine()
        joinDm()
        val m = tu(base)
        e.onEvents(listOf(dmMessage(m, me, "dev-phone", "from my phone", base)))
        e.onEvents(listOf(deleteEvent(dm, tu(base + 1000), me, "dev-laptop", listOf(Triple(m, me, base)), base + 1000)))
        assertEquals("You deleted this message", lk.codegen.risime.ui.chat.tombstoneText(row(m)!!, me))
    }

    @Test fun theOtherUserCannotDeleteMyDmMessageAndTheWindowIs48hPlusGrace() = runTest {
        val e = engine()
        joinDm()
        val mine = tu(base)
        val old = tu(base, 5)
        e.onEvents(listOf(dmMessage(mine, me, "dev-me2", "mine", base), dmMessage(old, bob, "dev-b", "old", base)))
        e.onEvents(listOf(deleteEvent(dm, tu(base + 1000), bob, "dev-b", listOf(Triple(mine, me, base)), base + 1000)))
        assertEquals("mine", row(mine)!!.body)
        assertTrue(logs.any { "delete_unauthorised" in it })
        val late = base + DeleteRules.WINDOW_MS + DeleteRules.RECEIVER_GRACE_MS + 1
        e.onEvents(listOf(deleteEvent(dm, tu(late), bob, "dev-b", listOf(Triple(old, bob, base)), late)))
        assertEquals("old", row(old)!!.body)
        val inside = base + DeleteRules.WINDOW_MS + DeleteRules.RECEIVER_GRACE_MS
        e.onEvents(listOf(deleteEvent(dm, tu(inside, 1), bob, "dev-b", listOf(Triple(old, bob, base)), inside)))
        assertTrue(row(old)!!.deleted)
    }

    @Test fun adminAtTheControlsEpochDeletesAnotherMembersMessage() = runTest {
        val e = engine()
        joinGroup(admins = listOf(kamal))
        val a = tu(base)
        val b = tu(base, 1)
        e.onEvents(listOf(grpMessage(a, bob, "dev-b", "one", base), grpMessage(b, bob, "dev-b", "two", base)))
        // Not an admin at that epoch (demoted later or never): ignored.
        mls.adminsAt = { _, epoch -> if (epoch == 1L) listOf(me) else listOf(kamal) }
        e.onEvents(listOf(deleteEvent(grp, tu(base + 1000), kamal, "dev-k", listOf(Triple(a, bob, base)), base + 1000)))
        assertEquals("one", row(a)!!.body)
        // Admin at the control's epoch: any age.
        mls.adminsAt = { _, _ -> listOf(kamal) }
        val muchLater = base + 10 * DeleteRules.WINDOW_MS
        e.onEvents(listOf(deleteEvent(grp, tu(muchLater), kamal, "dev-k", listOf(Triple(a, bob, base), Triple(b, bob, base)), muchLater)))
        assertTrue(row(a)!!.deleted && row(a)!!.deletedByAdmin)
        assertEquals("This message was deleted by an admin", lk.codegen.risime.ui.chat.tombstoneText(row(b)!!, me))
    }

    @Test fun anAadOrEnvelopeMismatchDropsTheWholeControlWithoutALine() = runTest {
        val e = engine()
        joinDm()
        val a = tu(base)
        val b = tu(base, 1)
        e.onEvents(listOf(dmMessage(a, bob, "dev-b", "one", base), dmMessage(b, bob, "dev-b", "two", base)))
        // The server named a and b; the authenticated AAD names only a.
        e.onEvents(listOf(deleteEvent(dm, tu(base + 1000), bob, "dev-b", listOf(Triple(a, bob, base), Triple(b, bob, base)), base + 1000, aadTargets = listOf(a))))
        // Envelope differs from the AAD.
        e.onEvents(listOf(deleteEvent(dm, tu(base + 2000), bob, "dev-b", listOf(Triple(a, bob, base)), base + 2000, envTargets = listOf(b), aadTargets = listOf(a))))
        assertEquals("one", row(a)!!.body)
        assertEquals("two", row(b)!!.body)
        assertTrue(logs.any { "binding mismatch" in it })
        assertTrue(messages.rows.values.none { it.system })
        assertEquals(0, applied)
    }

    @Test fun aDeleteBeforeItsMessageIsReCheckedWhenTheMessageArrives() = runTest {
        val e = engine()
        joinGroup()
        val m1 = tu(base)
        val m2 = tu(base, 1)
        // Null metadata: hidden tombstones only.
        e.onEvents(listOf(deleteEvent(grp, tu(base + 1000), bob, "dev-b", listOf(Triple(m1, null, null), Triple(m2, null, null)), base + 1000)))
        assertTrue(messages.rows.isEmpty())
        assertEquals(setOf(m1, m2), dao.deletedIds.keys)
        // m1 really is bob's: authorised → a tombstone, not notified or unread.
        e.onEvents(listOf(grpMessage(m1, bob, "dev-b", "bob's", base)))
        assertTrue(row(m1)!!.deleted)
        assertTrue(messages.unreadIncoming().isEmpty())
        // m2 is kamal's: bob (not admin) can't delete it → shown normally, the hidden tombstone goes (never blind hiding).
        e.onEvents(listOf(grpMessage(m2, kamal, "dev-k", "kamal's", base)))
        assertEquals("kamal's", row(m2)!!.body)
        assertNull(dao.deletedIds[m2])
    }

    @Test fun aTargetNeverStoredIsPlacedFromMetadataAndReJudgedIfTheMessageComes() = runTest {
        val e = engine()
        joinGroup()
        val m = tu(base)
        e.onEvents(listOf(deleteEvent(grp, tu(base + 1000), bob, "dev-b", listOf(Triple(m, bob, base)), base + 1000)))
        val placed = messages.rows[MessageEntity.placeholderId(m)]!!
        assertTrue(placed.deleted)
        assertEquals(base, placed.localTs)
        assertEquals("READ", placed.status)
        // The server lied about the sender: the real message (kamal's) replaces the placed tombstone.
        e.onEvents(listOf(grpMessage(m, kamal, "dev-k", "real", base)))
        assertNull(messages.rows[MessageEntity.placeholderId(m)])
        assertEquals("real", row(m)!!.body)
    }

    @Test fun aMalformedControlLeavesANoteOnTheMessage() = runTest {
        val e = engine()
        joinGroup()
        val m = tu(base)
        e.onEvents(listOf(grpMessage(m, bob, "dev-b", "hi", base)))
        mls.adminsAt = { _, _ -> null } // no admin record for that epoch (> 3 epochs back)
        e.onEvents(listOf(deleteEvent(grp, tu(base + 1000), bob, "dev-b", listOf(Triple(m, bob, base)), base + 1000)))
        val r = row(m)!!
        assertEquals("hi", r.body)
        assertTrue(r.deleteUnverified)
        assertTrue(messages.rows.values.none { it.system })
    }

    @Test fun preInstallAndUndecryptableDeletesCreateNoMarker() = runTest {
        val e = engine()
        e.onHistoryBefore(iso(base + 10_000))
        val m = tu(base)
        // No group yet and before history_before: dropped (no marker, no tombstone).
        e.onEvents(listOf(deleteEvent(dm, tu(base + 1000), bob, "dev-b", listOf(Triple(m, bob, base)), base + 1000)))
        joinDm()
        // Garbage ciphertext: dropped quietly.
        e.onEvents(listOf(ev("""{"event_id":"${tu(base + 2000)}","kind":"delete","data":{"message_id":"${tu(base + 2000)}","client_msg_id":"x","conversation_id":"$dm","from":"$bob","to":"$me","from_device":"dev-b","targets":[{"message_id":"$m","from":null,"server_ts":null}],"ciphertext":"${FakeMlsEngine.b64("garbage")}","generation":1,"epoch":1,"server_ts":"${iso(base + 2000)}"}}""")))
        assertTrue(messages.rows.isEmpty())
        assertTrue(dao.deletedIds.isEmpty())
        assertEquals(tu(base + 2000), sync.last)
    }

    @Test fun imageFilesGoAfterTheCommit() = runTest {
        val e = engine()
        joinDm()
        val m = tu(base)
        messages.insert(MessageEntity("img1", m, dm, bob, me, "", iso(base), base, "READ", false, ackedStatus = "READ", kind = MessageEntity.KIND_IMAGE, blobId = "blob-1"))
        e.onEvents(listOf(deleteEvent(dm, tu(base + 1000), bob, "dev-b", listOf(Triple(m, bob, base)), base + 1000)))
        assertEquals(listOf("img1" to true), images.purgedInTx)
        assertEquals(listOf("img1" to false), images.unlinkedInTx)
        assertNull(row(m)!!.blobId)
    }

    @Test fun plaintextDmDeletesApplyButNeverInAnE2eeChat() = runTest {
        val e = engine()
        val m = tu(base)
        messages.insert(MessageEntity("c1", m, dm, bob, me, "plain", iso(base), base, "DELIVERED", false))
        e.onEvents(listOf(plainDelete(tu(base + 1000), bob, listOf(Triple(m, bob, base)), base + 1000)))
        assertTrue(row(m)!!.deleted)
        val m2 = tu(base, 1)
        messages.insert(MessageEntity("c2", m2, dm, bob, me, "plain2", iso(base), base, "DELIVERED", false))
        joinDm()
        e.onEvents(listOf(plainDelete(tu(base + 2000), bob, listOf(Triple(m2, bob, base)), base + 2000)))
        assertEquals("plain2", row(m2)!!.body)
    }

    @Test fun myOwnEchoCompletesTheOutbox() = runTest {
        val e = engine()
        joinGroup(admins = listOf(me))
        val m = tu(base)
        e.onEvents(listOf(grpMessage(m, kamal, "dev-k", "x", base)))
        dao.queue(DeleteOutboxEntity("cd-own", grp, "everyone", DeleteJson.encode(listOf(m)), "[]", DeleteOutboxEntity.QUEUED, createdAt = 0))
        val d = deleteEvent(grp, tu(base + 1000), me, "dev-me", listOf(Triple(m, kamal, base)), base + 1000)
        e.onEvents(listOf(ev(ProtocolJson.encodeToString(Event.serializer(), d).replace("cd-${tu(base + 1000)}", "cd-own"))))
        assertTrue(row(m)!!.deleted)
        assertEquals(me, row(m)!!.deletedBy)
        assertNull(dao.outbox["cd-own"])
    }

    @Test fun theClearWatermarkKeepsOldMessagesReactionsAndMarkersAway() = runTest {
        val e = engine()
        joinDm()
        val old = tu(base)
        val fresh = tu(base + 5_000)
        dao.putChatState(ChatStateEntity(dm, TimeUuid.ticks(tu(base + 1000)), hidden = true))
        e.onEvents(listOf(dmMessage(old, bob, "dev-b", "old", base)))
        assertNull(row(old))
        // A delete for a cleared message places nothing.
        e.onEvents(listOf(deleteEvent(dm, tu(base + 2000), bob, "dev-b", listOf(Triple(old, bob, base)), base + 2000)))
        assertTrue(messages.rows.isEmpty())
        e.onEvents(listOf(dmMessage(fresh, bob, "dev-b", "new", base + 5_000)))
        assertEquals("new", row(fresh)!!.body)
        assertFalse("Delete chat: a new message brings it back", dao.chatStates.value[dm]!!.hidden)
        assertNotNull(dao.chatStates.value[dm]!!.clearedUpto)
    }
}
