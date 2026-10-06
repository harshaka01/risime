package lk.codegen.risime.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.groups.GroupStore
import lk.codegen.risime.data.groups.SystemLine
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import lk.codegen.risime.push.mergeReactionNotifications
import lk.codegen.risime.push.planChatNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * §13 (decision 043): a fresh install (new device id, empty database) joins with `since: null` and
 * replays the inbox: both sides of plaintext history, one marker for the e2ee gap, no notifications.
 */
class FreshInstallReplayTest {
    private val me = "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3"
    private val peer = "0b9d7e8a-1c2f-4a3b-8d4e-5f6a7b8c9d0e"
    private val dm = dmConversationId(me, peer)
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val myDev = "d-new"
    private val historyBefore = "2026-10-06T11:00:00.000Z"

    private val messages = FakeMessageDao()
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val reactions = FakeReactionDao()
    private val mls = FakeMlsEngine(me, myDev)
    private val pending = FakeMlsPendingDao()
    private val groupDao = FakeGroupDao()
    private var now = ms("2026-10-06T12:00:00.000Z")
    private var notifiedUpTo = 0L
    private var ids = 0
    private val addedMe = mutableListOf<String>()

    private fun ms(ts: String) = Instant.parse(ts).toEpochMilli()

    private fun TestScope.engine() = ChatEngine(
        messages = messages, sync = sync,
        tx = object : TransactionRunner { override suspend fun <T> run(block: suspend () -> T): T = block() },
        scope = this, realtime = { realtime }, meId = { me }, clock = { now++ }, newClientMsgId = { "c${++ids}" },
        mls = MlsPipeline({ mls }, pending), mlsEngine = { mls },
        reactionsDao = reactions,
        groupsEnabled = { true },
        groups = GroupStore(groupDao, FakeGroupOpDao(), messages, { myDev }, { mls.groupMeta(it) }, { now++ }, onAddedMe = { c, _ -> addedMe += c }),
        onFreshReplayDone = { notifiedUpTo = maxOf(notifiedUpTo, now) },
    )

    private fun ev(json: String) = ProtocolJson.decodeFromString<Event>(json)

    private fun plain(eid: String, from: String, to: String, ts: String, body: String) = ev(
        """{"event_id":"$eid","kind":"message","data":{"message_id":"$eid","client_msg_id":"cm-$eid","conversation_id":"$dm",
        "from":"$from","to":"$to","body":"$body","server_ts":"$ts"}}""",
    )

    private fun status(eid: String, target: String, st: String) = ev(
        """{"event_id":"$eid","kind":"status","data":{"message_id":"$target","client_msg_id":"cm-$target","conversation_id":"$dm",
        "status":"$st","by":"$peer","at":"2026-10-01T09:05:00.000Z"}}""",
    )

    private fun reaction(eid: String, from: String, target: String, ts: String) = ev(
        """{"event_id":"$eid","kind":"reaction","data":{"message_id":"$eid","client_msg_id":"cm-$eid","conversation_id":"$dm",
        "from":"$from","to":"${if (from == me) peer else me}","target":"$target","emoji":"👍","op":"add","server_ts":"$ts"}}""",
    )

    private fun e2ee(eid: String, conv: String, from: String, dev: String, epoch: Long, ts: String, text: String) = ev(
        """{"event_id":"$eid","kind":"message","data":{"message_id":"$eid","client_msg_id":"cm-$eid","conversation_id":"$conv",
        "from":"$from",${if (conv == dm) "\"to\":\"${if (from == me) peer else me}\"," else ""}"from_device":"$dev",
        "ciphertext":"${FakeMlsEngine.ciphertext(1, epoch, from, dev, String(MlsPayload.text(text)))}","generation":1,"epoch":$epoch,"server_ts":"$ts"}}""",
    )

    private fun welcome(eid: String, conv: String, epoch: Long) = ev(
        """{"event_id":"$eid","kind":"mls_welcome","data":{"conversation_id":"$conv","generation":1,"epoch":$epoch,
        "welcome":"${FakeMlsEngine.b64("welcome|$epoch")}","to_devices":["$myDev"]}}""",
    )

    private val dmInbox = listOf(
        plain("p1", peer, me, "2026-10-01T09:00:00.000Z", "from Kamal"),
        plain("p2", me, peer, "2026-10-01T09:01:00.000Z", "my plaintext"), // §13.1 sender copy
        status("s1", "p2", "delivered"),
        status("s2", "p2", "read"),
        reaction("r1", peer, "p2", "2026-10-01T09:02:00.000Z"),
        reaction("r2", me, "p1", "2026-10-01T09:03:00.000Z"),
        e2ee("x1", dm, peer, "d-kamal", 0, "2026-10-02T09:00:00.000Z", "secret from Kamal"),
        e2ee("x2", dm, me, "d-old", 1, "2026-10-02T09:05:00.000Z", "secret from my old install"),
        welcome("w1", dm, 2),
        e2ee("x3", dm, peer, "d-kamal", 2, "2026-10-06T11:30:00.000Z", "readable"),
    )

    /** What the realtime client does on a fresh join: cursor (null), history_before, pages, live. */
    private suspend fun ChatEngine.join(events: List<Event>) {
        assertNull(cursor())
        onHistoryBefore(historyBefore)
        onEvents(events)
        onLive()
    }

    private fun visible(conv: String) = messages.rows.values.filter { it.conversationId == conv }.sortedBy { it.localTs }

    @Test fun freshInstallReplaysTheDmInbox() = runTest {
        val e = engine()
        e.join(dmInbox)
        advanceUntilIdle()

        val rows = visible(dm)
        assertEquals(listOf("from Kamal", "my plaintext", SystemLine.HISTORY_GAP_TEXT, "readable"), rows.map { it.body })
        // Both sides of plaintext history, at their server time (not under "Today").
        val copy = messages.rows["cm-p2"]!!
        assertTrue(copy.outgoing)
        assertEquals("READ", copy.status) // ticks from the replayed status events
        assertEquals(ms("2026-10-01T09:01:00.000Z"), copy.localTs)
        assertEquals(ms("2026-10-01T09:00:00.000Z"), messages.rows["cm-p1"]!!.localTs)
        assertEquals(ms("2026-10-06T11:30:00.000Z"), messages.rows["cm-x3"]!!.localTs)
        // One marker, just after the latest pre-install message.
        val marker = messages.rows[HistoryMarkers.historyId(dm)]!!
        assertEquals(ms("2026-10-02T09:05:00.000Z") + 1, marker.localTs)
        assertEquals(1, rows.count { it.system })
        assertTrue(pending.rows.isEmpty())
        // Acks: only the readable incoming message (pre-install received history is stored read, the copy never).
        assertEquals(listOf(listOf("x3") to "delivered"), realtime.acks)
        assertFalse(realtime.acks.flatMap { it.first }.contains("p2"))
        // Reactions restored on both targets.
        assertEquals(2, reactions.rows.size)

        // No notification burst: everything replayed is already "notified".
        assertTrue(notifiedUpTo >= ms("2026-10-06T12:00:00.000Z"))
        val plan = planChatNotifications(messages.unreadIncoming(), emptyList(), notifiedUpTo)
        val merged = mergeReactionNotifications(plan, reactions.addsSince(notifiedUpTo), { t -> messages.rows.values.firstOrNull { it.messageId == t } }, { "Kamal" }, me)
        assertTrue(merged.isEmpty())
        assertFalse(e.replayingFresh)

        // Replaying the same list (duplicate delivery) and a restart over the same store: still one marker.
        e.onEvents(dmInbox)
        val restarted = engine()
        restarted.onHistoryBefore(historyBefore)
        restarted.onEvents(dmInbox)
        assertEquals(1, visible(dm).count { it.system })
        assertEquals(4, visible(dm).size)
    }

    @Test fun laterJoinsAreNotFresh() = runTest {
        val e = engine()
        e.join(dmInbox.take(1))
        notifiedUpTo = 0
        sync.last = "p1"
        assertEquals("p1", e.cursor())
        e.onLive()
        assertEquals(0L, notifiedUpTo) // only a since:null join moves notifiedUpTo
    }

    @Test fun groupVariantMarkerKeyedByGroupIdLinesIntactAndOwnCopyTicks() = runTest {
        mls.metas[grp] = lk.codegen.risime.net.GroupMeta(name = "Pilot team", admins = listOf(peer))
        val e = engine()
        e.join(
            listOf(
                ev(
                    """{"event_id":"g1","kind":"group_event","data":{"group_id":"$grp","generation":1,"epoch":0,"action":"created","actor":"$peer",
                    "targets":["$me"],"members":[{"user_id":"$peer","display_name":"Kamal","phone":"+94770000001","role":"admin","kind":"user","state":"active","joined_at":"2026-10-01T08:00:00.000Z"},{"user_id":"$me","display_name":"Me","phone":"+94770000002","role":"member","kind":"user","state":"active","joined_at":"2026-10-01T08:00:00.000Z"}],"server_ts":"2026-10-01T08:00:00.000Z"}}""",
                ),
                e2ee("y1", grp, peer, "d-kamal", 1, "2026-10-02T09:00:00.000Z", "old group message"),
                welcome("w2", grp, 3),
                e2ee("y2", grp, me, "d-phone", 3, "2026-10-06T11:40:00.000Z", "from my other phone"),
                ev(
                    """{"event_id":"r9","kind":"group_receipt","data":{"conversation_id":"$grp","message_id":"y2","client_msg_id":"cm-y2",
                    "delivered":1,"read":1,"of":1,"all_delivered":true,"all_read":true,"at":"2026-10-06T11:41:00.000Z"}}""",
                ),
            ),
        )
        advanceUntilIdle()
        val rows = visible(grp)
        assertEquals(rows.map { it.clientMsgId + "/" + it.body }.toString(), 3, rows.size)
        assertTrue(rows[0].system && rows[0].clientMsgId == "sys:g1") // the created line, at its server time
        assertEquals(ms("2026-10-01T08:00:00.000Z"), rows[0].localTs)
        assertEquals(HistoryMarkers.historyId(grp), rows[1].clientMsgId)
        val mine = rows[2]
        assertTrue(mine.outgoing)
        assertEquals("READ", mine.status)
        assertTrue(realtime.acks.isEmpty())
    }

    @Test fun aNewGroupMemberGetsNoMarker() = runTest {
        val e = engine()
        e.join(
            listOf(
                ev(
                    """{"event_id":"g2","kind":"group_event","data":{"group_id":"$grp","generation":1,"epoch":4,"action":"added","actor":"$peer",
                    "targets":["$me"],"server_ts":"2026-10-06T11:10:00.000Z"}}""",
                ),
                welcome("w3", grp, 5),
                e2ee("y3", grp, peer, "d-kamal", 5, "2026-10-06T11:20:00.000Z", "welcome aboard"),
            ),
        )
        assertTrue(visible(grp).none { it.clientMsgId.startsWith("sys:history:") })
        assertEquals("welcome aboard", visible(grp).last().body)
    }

    @Test fun aLiveCopyBeforeTheSendReplyFillsTheOutboxRow() = runTest {
        val e = engine()
        sync.last = "e0"
        realtime.connected = false
        val id = e.sendText(peer, "hello")!!
        advanceUntilIdle()
        assertEquals("PENDING", messages.rows[id]!!.status)
        e.onEvents(
            listOf(
                ev(
                    """{"event_id":"m77","kind":"message","data":{"message_id":"m77","client_msg_id":"$id","conversation_id":"$dm",
                    "from":"$me","to":"$peer","body":"hello","server_ts":"2026-10-06T12:00:00.000Z"}}""",
                ),
            ),
        )
        assertEquals(1, messages.rows.size)
        assertEquals("SENT", messages.rows[id]!!.status)
        assertEquals("m77", messages.rows[id]!!.messageId)
        assertTrue(realtime.acks.isEmpty())
    }

    @Test fun aReplayOfAlreadySeenEventsStillStoresTheCursor() = runTest {
        val e = engine()
        // Applied once, then the cursor reset (the one-time replay): every event is already seen.
        e.onEvents(dmInbox)
        sync.last = null
        assertNull(e.cursor())
        e.onEvents(dmInbox)
        assertEquals(dmInbox.last().eventId, sync.last)
        // The next join resumes from it (`since: <id>`), no longer a fresh `since: null` replay.
        assertEquals(dmInbox.last().eventId, e.cursor())
        advanceUntilIdle()
    }
}
