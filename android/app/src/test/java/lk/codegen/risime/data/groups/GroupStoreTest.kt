package lk.codegen.risime.data.groups

import kotlinx.coroutines.test.runTest
import lk.codegen.risime.data.FakeGroupDao
import lk.codegen.risime.data.FakeGroupOpDao
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.Group
import lk.codegen.risime.net.GroupEvent
import lk.codegen.risime.net.GroupMember
import lk.codegen.risime.net.GroupMeta
import lk.codegen.risime.net.GroupReceiptEvent
import lk.codegen.risime.net.MlsDeviceRef
import lk.codegen.risime.net.PendingOp
import lk.codegen.risime.net.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupStoreTest {
    private val me = "u-me"
    private val kamal = "u-kamal"
    private val nimal = "u-nimal"
    private val conv = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val dao = FakeGroupDao()
    private val ops = FakeGroupOpDao()
    private val messages = FakeMessageDao()
    private var meta: GroupMeta? = GroupMeta(name = "Pilot team", admins = listOf(kamal))
    private val refreshed = mutableListOf<String>()
    private val addedMe = mutableListOf<Pair<String, String>>()
    private val resets = mutableListOf<Pair<String, Long>>()
    private var now = 100L
    private val store = GroupStore(
        dao, ops, messages, { "dev-me" }, { meta }, { now++ },
        needsRefresh = { refreshed += it },
        onAddedMe = { c, a -> addedMe += c to a },
        onReset = { c, g -> resets += c to g },
    )

    private fun member(id: String, name: String, role: String = GroupMember.ROLE_MEMBER) = GroupMember(id, name, null, role, "user", "active", "2026-10-06T08:00:00.000Z")

    private fun ev(action: String, actor: String, targets: List<String> = emptyList(), role: String? = null, members: List<GroupMember>? = null, gen: Long = 1, epoch: Long? = 1) =
        GroupEvent(conv, gen, epoch, action, actor, targets, role, members)

    private fun systemLines() = messages.rows.values.filter { it.system }.sortedBy { it.localTs }

    @Test fun createdByAnotherMakesTheGroupWithMembersRolesAndAnAddedNotification() = runTest {
        store.applyEvent("e1", ev(GroupEvent.CREATED, kamal, listOf(me), members = listOf(member(kamal, "Kamal", "admin"), member(me, "Me"))), me)
        val g = dao.groups[conv]!!
        assertEquals("Pilot team", g.name)
        assertEquals(GroupEntity.STATE_ACTIVE, g.state)
        assertEquals("member", g.myRole)
        assertEquals(2, dao.members.size)
        assertEquals(listOf(conv to kamal), addedMe)
        val line = systemLines().single()
        assertEquals("Kamal created the group “Pilot team”", line.body)
        assertEquals("READ", line.status) // never unread, never acked
        assertNull(line.messageId)
    }

    @Test fun addRemoveLeaveRoleAndRenameWriteSystemLinesAndState() = runTest {
        store.applyEvent("e1", ev(GroupEvent.CREATED, me, listOf(kamal), members = listOf(member(me, "Me", "admin"), member(kamal, "Kamal"))), me)
        store.applyEvent("e2", ev(GroupEvent.ADDED, me, listOf(nimal), epoch = 2), me)
        assertEquals(listOf(conv), refreshed) // names of the added come from GET /groups/{id}
        dao.upsertMembers(listOf(dao.members[conv to nimal]!!.copy(displayName = "Nimal")))
        store.applyEvent("e3", ev(GroupEvent.ROLE_CHANGED, me, listOf(kamal), role = "admin", epoch = 3), me)
        assertEquals("admin", dao.members[conv to kamal]!!.role)
        meta = meta!!.copy(name = "Rise core")
        store.applyEvent("e4", ev(GroupEvent.METADATA_CHANGED, kamal, epoch = 4), me)
        assertEquals("Rise core", dao.groups[conv]!!.name)
        store.applyEvent("e5", ev(GroupEvent.LEFT, nimal, listOf(nimal), epoch = 5), me)
        assertEquals("left", dao.members[conv to nimal]!!.state)
        store.applyEvent("e6", ev(GroupEvent.REMOVED, kamal, listOf(me), epoch = 6), me)
        assertEquals(GroupEntity.STATE_REMOVED, dao.groups[conv]!!.state)
        assertTrue(dao.groups[conv]!!.readOnly)
        assertEquals(
            listOf(
                "You created the group “Pilot team”", "You added Member", "You made Kamal an admin",
                "Kamal changed the group name to “Rise core”", "Nimal left", "Kamal removed you",
            ),
            systemLines().map { it.body },
        )
        assertEquals(6L, dao.groups[conv]!!.epochSeen)
    }

    @Test fun systemTextNamesManyTargets() {
        val names = mapOf(kamal to "Kamal", nimal to "Nimal", "u3" to "Sunil")
        val t = { l: SystemLine -> systemText(l, me) { names[it] ?: "?" } }
        assertEquals("Kamal added Nimal and 2 others", t(SystemLine(GroupEvent.ADDED, kamal, listOf(nimal, "u3", me))))
        assertEquals("Kamal added Nimal and you", t(SystemLine(GroupEvent.ADDED, kamal, listOf(nimal, me))))
        assertEquals("Kamal dismissed you as admin", t(SystemLine(GroupEvent.ROLE_CHANGED, kamal, listOf(me), role = "member")))
        assertEquals("You left", t(SystemLine(GroupEvent.LEFT, me, listOf(me))))
        assertEquals("Encryption was reset; some messages may be missing", t(SystemLine(GroupEvent.RESET, kamal)))
    }

    @Test fun addedMeIntoAGroupIDidntKnowNotifiesOnce() = runTest {
        store.applyEvent("e1", ev(GroupEvent.ADDED, kamal, listOf(me), epoch = 5), me)
        assertEquals(listOf(conv to kamal), addedMe)
        assertEquals(GroupEntity.STATE_ACTIVE, dao.groups[conv]!!.state)
        store.applyEvent("e2", ev(GroupEvent.ADDED, kamal, listOf(nimal), epoch = 6), me)
        assertEquals(1, addedMe.size)
    }

    @Test fun addExpiredDropsOnlyPendingAddsAndResetReplacesMembersAndGeneration() = runTest {
        store.applyServerGroup(
            Group(conv, "active", me, null, 1, 4, "admin", listOf(member(me, "Me", "admin"), member(kamal, "Kamal"), member(nimal, "Nimal").copy(state = "pending_add"))),
            me,
        )
        store.applyEvent("e1", ev(GroupEvent.ADD_EXPIRED, me, listOf(nimal), epoch = 4), me)
        assertEquals("removed", dao.members[conv to nimal]!!.state)
        assertEquals("Couldn't add Nimal", systemLines().single().body)

        store.applyEvent("e2", ev(GroupEvent.RESET, kamal, members = listOf(member(me, "Me"), member(kamal, "Kamal", "admin")), gen = 2, epoch = null), me)
        assertEquals(listOf(conv to 2L), resets)
        assertEquals(2L, dao.groups[conv]!!.generation)
        assertEquals("member", dao.groups[conv]!!.myRole) // the server's roles win (§12.8)
        assertEquals("Encryption was reset; some messages may be missing", systemLines().last().body)
    }

    @Test fun onlyTheNamedDeviceQueuesAnOpAndRepeatsAreDeduped() = runTest {
        val op = PendingOp("op1", PendingOp.ADD, me, listOf(nimal), committer = MlsDeviceRef(me, "dev-me"))
        assertTrue(store.applyOp(lk.codegen.risime.net.GroupOpEvent(conv, 1, op), me))
        assertFalse(store.applyOp(lk.codegen.risime.net.GroupOpEvent(conv, 1, op), me)) // renamed again: same op
        assertFalse(store.applyOp(lk.codegen.risime.net.GroupOpEvent(conv, 1, op.copy(opId = "op2", committer = MlsDeviceRef(me, "dev-other"))), me))
        assertFalse(store.applyOp(lk.codegen.risime.net.GroupOpEvent(conv, 1, op.copy(opId = "op3", committer = MlsDeviceRef(kamal, "dev-me"))), me))
        assertEquals(listOf("op1"), ops.rows.values.map { it.opId })
        assertEquals(GroupOpType.COMMIT, ops.rows.values.single().type)
    }

    @Test fun serverGroupQueuesOwedOpsAndMarksUnlistedMembersGone() = runTest {
        dao.upsertMembers(listOf(lk.codegen.risime.data.db.GroupMemberEntity(conv, "u-old", "Old", null, "member", "user", "active", null)))
        val g = ProtocolJson.decodeFromString(lk.codegen.risime.net.GroupReply.serializer(), example("group_reply.json")).group
        store.applyServerGroup(g, "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3")
        assertEquals("removed", dao.members[conv to "u-old"]!!.state)
        assertEquals("pending_add", dao.members[conv to "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f"]!!.state)
        // The example's op names device …0001, not ours ("dev-me"): nothing owed here.
        assertTrue(ops.rows.isEmpty())
        val mine = GroupStore(dao, ops, messages, { "c0a80101-0000-4000-8000-000000000001" })
        mine.applyServerGroup(g, "7e3f1a2b-9c8d-4e5f-a6b7-c8d9e0f1a2b3")
        assertEquals(listOf("9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a"), ops.rows.values.map { it.opId })
    }

    @Test fun receiptsMoveTicksForwardOnly() = runTest {
        messages.insert(MessageEntity("c1", "m1", conv, me, conv, "hi", "t", 1, "SENT", true))
        store.applyReceipt(GroupReceiptEvent(conv, "m1", "c1", 2, 0, 2, allDelivered = true, allRead = false))
        assertEquals("DELIVERED", messages.rows["c1"]!!.status)
        store.applyReceipt(GroupReceiptEvent(conv, "m1", "c1", 2, 2, 2, allDelivered = true, allRead = true))
        assertEquals("READ", messages.rows["c1"]!!.status)
        store.applyReceipt(GroupReceiptEvent(conv, "m1", "c1", 2, 1, 3, allDelivered = false, allRead = false))
        assertEquals("READ", messages.rows["c1"]!!.status) // never backwards
        assertEquals(3, messages.rows["c1"]!!.receiptOf)
    }

    @Test fun goneGroupStaysReadOnlyAndAnUnfinishedCreationDisappears() = runTest {
        dao.upsert(GroupEntity(conv, "x", "member", "active", null, null, 1, null, null, null, 1))
        store.markGone(conv)
        assertEquals(GroupEntity.STATE_REMOVED, dao.groups[conv]!!.state)
        dao.upsert(GroupEntity("grp:b", null, "admin", "creating", null, null, 1, null, null, null, 1))
        store.markGone("grp:b")
        assertNull(dao.groups["grp:b"])
    }

    @Test fun welcomeBeforeAnyEventCreatesTheRowWithTheMetaName() = runTest {
        store.onGroupStateChanged(conv, removedSelf = false)
        assertEquals("Pilot team", dao.groups[conv]!!.name)
        assertEquals(listOf(conv), refreshed)
        assertEquals("New group", groupDisplayName(null))
    }

    private fun example(name: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$name")!!.readText()

    @Suppress("unused")
    private fun eventOf(s: String) = ProtocolJson.decodeFromString<Event>(s)
}
