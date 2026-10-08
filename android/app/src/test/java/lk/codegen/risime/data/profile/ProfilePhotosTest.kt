package lk.codegen.risime.data.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import lk.codegen.risime.data.ChatEngine
import lk.codegen.risime.data.FakeMessageDao
import lk.codegen.risime.data.FakeRealtime
import lk.codegen.risime.data.FakeSyncDao
import lk.codegen.risime.data.TransactionRunner
import lk.codegen.risime.data.db.ProfilePhotoConvEntity
import lk.codegen.risime.data.db.ProfilePhotoDao
import lk.codegen.risime.data.db.ProfilePhotoEntity
import lk.codegen.risime.data.media.AwtBitmapOps
import lk.codegen.risime.data.media.FakeMediaCrypto
import lk.codegen.risime.data.media.ImageBytes
import lk.codegen.risime.data.media.ImageRejected
import lk.codegen.risime.data.mls.DeviceRef
import lk.codegen.risime.data.mls.FakeMlsEngine
import lk.codegen.risime.data.mls.FakeMlsPendingDao
import lk.codegen.risime.data.mls.GroupRef
import lk.codegen.risime.data.mls.KvSealer
import lk.codegen.risime.data.mls.MlsPayload
import lk.codegen.risime.data.mls.MlsPipeline
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.BlobUploadReply
import lk.codegen.risime.net.Event
import lk.codegen.risime.net.ProtocolJson
import lk.codegen.risime.net.dmConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class FakeProfilePhotoDao : ProfilePhotoDao {
    val rows = MutableStateFlow<Map<String, ProfilePhotoEntity>>(emptyMap())
    val convRows = linkedMapOf<String, ProfilePhotoConvEntity>()

    override suspend fun get(id: String) = rows.value[id]
    override fun observeAll() = rows.map { it.values.toList() }
    override suspend fun unfetched() = rows.value.values.filter { it.blobId != null && it.fetched == ProfilePhotoEntity.FETCH_NONE }
    override suspend fun goneBefore(before: Long) = rows.value.values.filter { it.blobId != null && it.fetched == ProfilePhotoEntity.FETCH_GONE && it.checkedAt < before }
    override suspend fun put(p: ProfilePhotoEntity) { rows.value = rows.value + (p.userId to p) }
    override suspend fun setFetched(id: String, blobId: String, fetched: Int, at: Long): Int {
        val r = rows.value[id]?.takeIf { it.blobId == blobId } ?: return 0
        put(r.copy(fetched = fetched, checkedAt = at))
        return 1
    }
    override suspend fun delete(id: String) { rows.value = rows.value - id }
    override suspend fun conv(conv: String) = convRows[conv]
    override suspend fun convs() = convRows.values.toList()
    override suspend fun pendingSends() = convRows.values.filter { it.pendingVer != null }.sortedBy { it.dueAt }
    override suspend fun putConv(c: ProfilePhotoConvEntity) { convRows[c.conversationId] = c }
    override suspend fun clearSent(conv: String, ver: Long): Int {
        val r = convRows[conv] ?: return 0
        if (r.pendingVer != null && r.pendingVer > ver) return 0
        convRows[conv] = r.copy(pendingVer = null, dirty = false)
        return 1
    }
    override suspend fun deleteConv(conv: String) { convRows.remove(conv) }
}

class FakeAvatarApi(private val crypto: FakeMediaCrypto) : AvatarApi {
    val log = mutableListOf<String>()
    /** blob id → served bytes (what a download returns). */
    val served = mutableMapOf<String, ByteArray>()
    var uploadReply: ((File) -> ApiResult<BlobUploadReply>)? = null
    private var n = 0

    override suspend fun uploadAvatar(clientBlobId: String, file: File): ApiResult<BlobUploadReply> {
        log += "upload avatar"
        uploadReply?.let { return it(file) }
        val id = "avatar-${++n}"
        served[id] = file.readBytes()
        return ApiResult.Ok(BlobUploadReply(id, file.length(), lk.codegen.risime.data.media.sha256B64(file), null))
    }

    override suspend fun uploadIcon(conversationId: String, clientBlobId: String, file: File): ApiResult<BlobUploadReply> {
        log += "upload icon $conversationId"
        val id = "icon-${++n}"
        served[id] = file.readBytes()
        return ApiResult.Ok(BlobUploadReply(id, file.length(), lk.codegen.risime.data.media.sha256B64(file), null))
    }

    override suspend fun download(blobId: String, into: File, etagHex: String): ApiResult<Long> {
        log += "download $blobId"
        val b = served[blobId] ?: return ApiResult.Error(404, "not_found", "")
        into.writeBytes(b)
        return ApiResult.Ok(b.size.toLong())
    }

    override suspend fun delete(blobId: String): ApiResult<Unit> {
        log += "delete $blobId"
        served.remove(blobId)
        return ApiResult.Ok(Unit)
    }
}

/** Contract v1.17 §18 on the device (§18.9 android JVM coverage). */
class ProfilePhotosTest {
    @get:Rule val tmp = TemporaryFolder()
    private val me = "u-me"
    private val kamal = "u-kamal"
    private val dm = dmConversationId(me, kamal)
    private val grp = "grp:5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    private val messages = FakeMessageDao()
    private val sync = FakeSyncDao()
    private val realtime = FakeRealtime()
    private val mls = FakeMlsEngine(me, "dev-me")
    private val pending = FakeMlsPendingDao()
    private val dao = FakeProfilePhotoDao()
    private val crypto = FakeMediaCrypto()
    private val api = FakeAvatarApi(crypto)
    private val dbKey = KvSealer(ByteArray(32) { 3 })
    private var now = 1_791_450_000_000L
    private var inTx = false
    private val kvMap = mutableMapOf<String, String>()
    private val sleeps = mutableListOf<Long>()
    private var blockedIds = setOf<String>()
    private var randomPick: (IntRange) -> Int = { it.first + 7_000 }
    private var ids = 0
    private val tx = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T {
            inTx = true
            try { return block() } finally { inTx = false }
        }
    }

    private lateinit var engine: ChatEngine

    private fun TestScope.photos(): ProfilePhotos = ProfilePhotos(
        dao, tx, { dbKey }, AvatarFiles(tmp.root.resolve("avatars")), { crypto }, api,
        sendSilent = { conv, pt -> engine.sendSilent(conv, pt) }, mls = { mls },
        conversations = { listOf(dm, grp) }, me = { me }, serverNow = { now }, scope = this,
        kv = object : PhotoKv {
            override fun get(key: String) = kvMap[key]
            override fun set(key: String, value: String?) { if (value == null) kvMap.remove(key) else kvMap[key] = value }
        },
        blocked = { it in blockedIds }, clock = { now }, random = { randomPick(it) }, sleep = { sleeps += it; now += it },
        newId = { "id-${++ids}" },
    )

    private fun TestScope.engine(p: ProfilePhotos, historyBefore: String? = null) = ChatEngine(
        messages = messages, sync = sync, tx = tx, scope = this, realtime = { realtime }, meId = { me }, clock = { now },
        newClientMsgId = { "c${++ids}" }, mls = MlsPipeline({ mls }, pending), mlsEngine = { mls }, groupsEnabled = { true },
        profilePhotos = p,
    ).also { engine = it }

    private val example = res("profile_photo_payload.json")
    private val removed = res("profile_photo_payload_removed.json")

    private fun res(n: String) = javaClass.classLoader!!.getResource("contract/v1/examples/$n")!!.readText()

    private fun event(eid: String, conv: String, from: String, dev: String, payload: String, silent: Boolean = true, gen: Long = 1, epoch: Long = 1, ts: String = "2026-10-08T09:12:05.211Z") =
        ProtocolJson.decodeFromString<Event>(
            """{"event_id":"$eid","kind":"message","data":{"message_id":"m-$eid","client_msg_id":"cm-$eid","conversation_id":"$conv",
            "from":"$from","from_device":"$dev","ciphertext":"${FakeMlsEngine.ciphertext(gen, epoch, from, dev, payload)}",
            "generation":$gen,"epoch":$epoch,"server_ts":"$ts","silent":$silent}}""",
        )

    private fun withVer(payload: String, ver: Long, sha: String? = null): String {
        val o = ProtocolJson.parseToJsonElement(payload) as JsonObject
        var out = JsonObject(o + ("ver" to JsonPrimitive(ver)))
        if (sha != null) {
            val p = out["photo"] as JsonObject
            val blob = p["blob"] as JsonObject
            out = JsonObject(out + ("photo" to JsonObject(p + ("blob" to JsonObject(blob + ("sha256" to JsonPrimitive(sha)))))))
        }
        return out.toString()
    }

    // ---- the envelope (§18.1) ----

    @Test fun strictValidationDropsEveryMalformedShape() {
        fun v(mut: (MutableMap<String, Any?>) -> Unit): ProfilePhotoEnvelope? {
            val o = ProtocolJson.parseToJsonElement(example) as JsonObject
            val photo = (o["photo"] as JsonObject).toMutableMap<String, kotlinx.serialization.json.JsonElement>()
            val m = mutableMapOf<String, Any?>()
            mut(m)
            m.forEach { (k, value) -> if (value == null) photo.remove(k) else photo[k] = value as kotlinx.serialization.json.JsonElement }
            return ProfilePhotoEnvelope.validate(JsonObject(o + ("photo" to JsonObject(photo))))
        }
        assertNotNull(v { })
        assertNull(v { it["mime"] = JsonPrimitive("image/png") })
        assertNull(v { it["w"] = JsonPrimitive(256); it["h"] = JsonPrimitive(255) })
        assertNull(v { it["w"] = JsonPrimitive(32); it["h"] = JsonPrimitive(32) })
        assertNull(v { it["w"] = JsonPrimitive(1024); it["h"] = JsonPrimitive(1024) })
        val enc = (ProtocolJson.parseToJsonElement(example) as JsonObject)["photo"]!!.let { (it as JsonObject)["enc"] as JsonObject }
        assertNull(v { it["enc"] = JsonObject(enc + ("key" to JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(31))))) })
        assertNull(v { it["enc"] = JsonObject(enc + ("plain_size" to JsonPrimitive(70000))) }) // cipher_size != blob.size
        assertNull(v { it["enc"] = JsonObject(enc + ("alg" to JsonPrimitive("A128GCM"))) })
        // Over the 512 KiB avatar cap.
        val big = 600_000L
        val blob = (ProtocolJson.parseToJsonElement(example) as JsonObject)["photo"]!!.let { (it as JsonObject)["blob"] as JsonObject }
        assertNull(v { it["enc"] = JsonObject(enc + ("plain_size" to JsonPrimitive(big))); it["blob"] = JsonObject(blob + ("size" to JsonPrimitive(lk.codegen.risime.data.media.MediaFormat.cipherSize(big)!!))) })
        // `photo` must be present (null or an object); `ver` an integer ≥ 1.
        val o = ProtocolJson.parseToJsonElement(example) as JsonObject
        assertNull(ProfilePhotoEnvelope.validate(JsonObject(o - "photo")))
        assertNull(ProfilePhotoEnvelope.validate(JsonObject(o + ("ver" to JsonPrimitive(0)))))
        assertNull(ProfilePhotoEnvelope.validate(JsonObject(o + ("ver" to JsonPrimitive("1791450724000")))))
        assertNull(ProfilePhotoEnvelope.validate(JsonObject(o + ("ver" to JsonPrimitive(1.5)))))
    }

    @Test fun verWindowIsNowPlus24h() {
        assertTrue(ProfilePhotoEnvelope.inWindow(now + 24 * 3_600_000L, now))
        assertFalse(ProfilePhotoEnvelope.inWindow(now + 24 * 3_600_000L + 1, now))
        assertFalse(ProfilePhotoEnvelope.inWindow(0, now))
    }

    @Test fun winnerRuleIncludingTies() {
        assertTrue(ProfilePhotoEnvelope.wins(5, "b", null, null))
        assertTrue(ProfilePhotoEnvelope.wins(6, "a", 5, "z"))
        assertFalse(ProfilePhotoEnvelope.wins(4, "z", 5, "a"))
        // Equal ver: a null photo wins; then the greater sha256 as base64 strings.
        assertTrue(ProfilePhotoEnvelope.wins(5, null, 5, "a"))
        assertFalse(ProfilePhotoEnvelope.wins(5, "zzz", 5, null))
        assertTrue(ProfilePhotoEnvelope.wins(5, "b", 5, "a"))
        assertFalse(ProfilePhotoEnvelope.wins(5, "a", 5, "b"))
        assertFalse(ProfilePhotoEnvelope.wins(5, "a", 5, "a")) // a re-send changes nothing
        assertFalse(ProfilePhotoEnvelope.wins(5, null, 5, null))
    }

    // ---- receiving (§18.2) ----

    @Test fun receivedPhotoIsNotAMessageAndIsStoredSealedForTheMlsSender() = runTest {
        val p = photos()
        val e = engine(p)
        mls.groups[dm] = GroupRef(dm, 1, 1)
        now = 1_791_450_724_000L
        // A `user_id` field never changes the subject (crypto K3).
        val withUser = JsonObject((ProtocolJson.parseToJsonElement(example) as JsonObject) + ("user_id" to JsonPrimitive(me))).toString()
        e.onEvents(listOf(event("e1", dm, kamal, "dev-k", withUser)))
        advanceUntilIdle()
        assertTrue("no row, no unread, no marker", messages.rows.isEmpty())
        assertTrue("no ack", realtime.acks.isEmpty())
        assertEquals("e1", sync.last)
        assertNull(dao.get(me))
        val row = dao.get(kamal)!!
        assertEquals(1_791_450_724_000L, row.ver)
        assertEquals("8d2e4f6a-1b3c-4d5e-9f70-a1b2c3d4e5f6", row.blobId)
        val key = Base64.getDecoder().decode("e9kavbSz+JwYqNHiqbkWDhG+EoZJTlUVcMw7EfeUw9c=")
        assertFalse(row.keySealed!!.toList().windowed(32).any { it.toByteArray().contentEquals(key) })
        assertTrue(dbKey.open(ProfilePhotos.NS, "$kamal|${row.blobId}".toByteArray(), row.keySealed!!).contentEquals(key))
        // The download ran after the transaction (404 here: initials, retried daily).
        assertEquals(listOf("download 8d2e4f6a-1b3c-4d5e-9f70-a1b2c3d4e5f6"), api.log)
        assertEquals(ProfilePhotoEntity.FETCH_GONE, dao.get(kamal)!!.fetched)
    }

    @Test fun verTooFarInTheFutureIsDropped() = runTest {
        val e = engine(photos())
        mls.groups[dm] = GroupRef(dm, 1, 1)
        now = 1_791_450_724_000L - 25 * 3_600_000L
        e.onEvents(listOf(event("e1", dm, kamal, "dev-k", example)))
        assertNull(dao.get(kamal))
        assertEquals("e1", sync.last)
    }

    @Test fun preInstallSilentEventAddsNoMarkerAndNoGap() = runTest {
        val e = engine(photos())
        mls.groups[dm] = GroupRef(dm, 2, 1) // the event's generation 1 is lost (a reset): normally a marker
        e.onEvents(listOf(event("e1", dm, kamal, "dev-k", example, silent = true)))
        assertTrue(messages.rows.isEmpty())
        // The same shape without `silent` does leave the visible line (control).
        e.onEvents(listOf(event("e2", dm, kamal, "dev-k", "{\"v\":1,\"type\":\"text\",\"body\":\"hi\"}", silent = false)))
        assertEquals(1, messages.rows.values.count { it.system })
    }

    @Test fun newerPhotoReplacesKeyAndCacheOlderOneIsIgnored() = runTest {
        val p = photos()
        val e = engine(p)
        mls.groups[dm] = GroupRef(dm, 1, 1)
        now = 1_791_460_000_000L
        val f = p.files.file(kamal, "8d2e4f6a-1b3c-4d5e-9f70-a1b2c3d4e5f6")
        dao.put(ProfilePhotoEntity(kamal, 1_791_450_000_000L, "8d2e4f6a-1b3c-4d5e-9f70-a1b2c3d4e5f6", 61456, "x", ByteArray(40), 61000, 512, 512, ProfilePhotoEntity.FETCH_CACHED))
        f.writeBytes(ByteArray(10))
        e.onEvents(listOf(event("e1", dm, kamal, "dev-k", removed)))
        advanceUntilIdle()
        val row = dao.get(kamal)!!
        assertNull(row.blobId)
        assertNull(row.keySealed)
        assertFalse("cached ciphertext of the old photo is gone", f.exists())
        // An older envelope loses: nothing changes, nothing is fetched.
        e.onEvents(listOf(event("e2", dm, kamal, "dev-k", example)))
        advanceUntilIdle()
        assertNull(dao.get(kamal)!!.blobId)
        assertTrue(api.log.isEmpty())
    }

    @Test fun downloadIsVerifiedThenDecryptedOnDisplayAndBlockedUsersShowInitials() = runTest {
        val p = photos()
        val e = engine(p)
        mls.groups[dm] = GroupRef(dm, 1, 1)
        // A real (fake-core) blob: encrypt, serve it, send its envelope.
        val plain = tmp.newFile("p.jpg").apply { writeBytes(ByteArray(61000) { 1 }) }
        val sealed = crypto.encryptFile(plain, tmp.root.resolve("c.enc"))
        api.served["b1"] = tmp.root.resolve("c.enc").readBytes()
        val ref = PhotoRef(lk.codegen.risime.net.BlobRef("b1", sealed.cipherSize, Base64.getEncoder().encodeToString(sealed.sha256)), lk.codegen.risime.data.media.ImageEnc(sealed.alg, sealed.key, sealed.plainSize), "image/jpeg", 512, 512)
        now = 1_791_450_724_000L
        e.onEvents(listOf(event("e1", dm, kamal, "dev-k", String(ProfilePhotoEnvelope(now, ref).encode()))))
        advanceUntilIdle()
        assertEquals(ProfilePhotoEntity.FETCH_CACHED, dao.get(kamal)!!.fetched)
        assertEquals(61000, p.plaintext(kamal)!!.bytes.size)
        blockedIds = setOf(kamal)
        assertNull(p.plaintext(kamal))
        blockedIds = emptySet()
        // A tampered served blob fails the digest: initials, never retried as-is.
        api.served["b2"] = ByteArray(sealed.cipherSize.toInt())
        val bad = PhotoRef(lk.codegen.risime.net.BlobRef("b2", sealed.cipherSize, Base64.getEncoder().encodeToString(sealed.sha256)), ref.enc, "image/jpeg", 512, 512)
        e.onEvents(listOf(event("e2", dm, kamal, "dev-k", String(ProfilePhotoEnvelope(now + 1, bad).encode()))))
        advanceUntilIdle()
        assertEquals(ProfilePhotoEntity.FETCH_FAILED, dao.get(kamal)!!.fetched)
        assertNull(p.plaintext(kamal))
    }

    // ---- sending (§18.5) ----

    private suspend fun TestScope.setPhoto(p: ProfilePhotos): PhotoChange = p.setOwnPhoto(ByteArray(40_000) { 2 }, 512)

    @Test fun aChangeGoesToEveryE2eeConversationPacedAndSilent() = runTest {
        val p = photos()
        engine(p)
        mls.groups[dm] = GroupRef(dm, 1, 1)
        mls.groups[grp] = GroupRef(grp, 1, 3)
        assertEquals(PhotoChange.Ok, setPhoto(p))
        advanceUntilIdle()
        assertEquals(listOf("upload avatar"), api.log)
        val own = dao.get(me)!!
        assertEquals(ProfilePhotoEntity.FETCH_CACHED, own.fetched) // never downloaded back
        assertEquals(1, realtime.sentEncrypted.size)
        assertEquals(1, realtime.sentGroup.size)
        assertEquals(true, realtime.sentEncrypted.single().silent)
        assertEquals(true, realtime.sentGroup.single().silent)
        // The envelope: own ver, the uploaded blob, a JPEG 512 square.
        val sent = MlsPayload.decode(Base64.getDecoder().decode(realtime.sentEncrypted.single().ciphertext).decodeToString().split('|', limit = 4)[3].toByteArray())
        val env = (sent as MlsPayload.Decoded.ProfilePhoto).env
        assertEquals(own.ver, env.ver)
        assertEquals(own.blobId, env.photo!!.blob.blobId)
        // At most one per second in all.
        assertTrue(sleeps.count { it == ProfilePhotos.PACE_MS } >= 1)
        assertTrue(dao.pendingSends().isEmpty())
        assertTrue(messages.rows.isEmpty())
    }

    @Test fun threeChangesPerHourThenTryAgainLater() = runTest {
        val p = photos()
        engine(p)
        repeat(3) { assertEquals(PhotoChange.Ok, setPhoto(p)) }
        assertEquals(PhotoChange.Refused(ProfilePhotos.TRY_LATER), setPhoto(p))
        now += ProfilePhotos.HOUR_MS
        assertEquals(PhotoChange.Ok, setPhoto(p))
    }

    @Test fun verIsStrictlyIncreasingForTheOwnUser() = runTest {
        val p = photos()
        engine(p)
        setPhoto(p)
        val v1 = dao.get(me)!!.ver
        p.removeOwnPhoto()
        assertTrue(dao.get(me)!!.ver > v1)
    }

    @Test fun removeSendsNullThenDeletesTheBlobOnceSent() = runTest {
        val p = photos()
        engine(p)
        mls.groups[dm] = GroupRef(dm, 1, 1)
        setPhoto(p)
        advanceUntilIdle()
        val blob = dao.get(me)!!.blobId!!
        assertEquals(PhotoChange.Ok, p.removeOwnPhoto())
        advanceUntilIdle()
        val last = realtime.sentEncrypted.last()
        val env = (MlsPayload.decode(Base64.getDecoder().decode(last.ciphertext).decodeToString().split('|', limit = 4)[3].toByteArray()) as MlsPayload.Decoded.ProfilePhoto).env
        assertNull(env.photo)
        assertEquals(listOf("upload avatar", "delete $blob"), api.log)
        assertFalse(p.hasPhoto(me))
    }

    @Test fun triggersOnlyWithAPhotoDmWaitsGroupGoesDirty() = runTest {
        val p = photos()
        engine(p)
        mls.groups[dm] = GroupRef(dm, 1, 1)
        mls.memberLists[dm] = mutableListOf(DeviceRef(me, "dev-me"), DeviceRef(kamal, "dev-k"))
        // Without a photo: rows are recorded, nothing is owed.
        p.reconcile()
        advanceUntilIdle()
        assertNull(dao.conv(dm)!!.pendingVer)
        setPhoto(p)
        advanceUntilIdle()
        val sentBefore = realtime.sentEncrypted.size
        // Trigger 3: Kamal's new tablet.
        mls.memberLists[dm]!! += DeviceRef(kamal, "dev-k2")
        randomPick = { it.last } // 30 s
        p.reconcile()
        val row = dao.conv(dm)!!
        assertEquals(dao.get(me)!!.ver, row.pendingVer)
        assertEquals(now + 30_000, row.dueAt)
        // Trigger 2 in a group: dirty, nothing sent until the next own message or the chat opens.
        mls.groups[grp] = GroupRef(grp, 1, 1)
        mls.memberLists[grp] = mutableListOf(DeviceRef(me, "dev-me"))
        p.reconcile()
        assertTrue(dao.conv(grp)!!.dirty)
        assertNull(dao.conv(grp)!!.pendingVer)
        advanceUntilIdle() // the DM's wait passes (the fake sleep advances the clock)
        assertEquals(sentBefore + 1, realtime.sentEncrypted.size)
        assertTrue(realtime.sentGroup.isEmpty())
        p.onChatOpened(grp)
        assertNotNull(dao.conv(grp)!!.pendingVer)
        advanceUntilIdle()
        assertEquals(1, realtime.sentGroup.size)
        assertFalse(dao.conv(grp)!!.dirty)
    }

    @Test fun aSiblingsSendSettlesTheWait() = runTest {
        val p = photos()
        val e = engine(p)
        mls.groups[dm] = GroupRef(dm, 1, 1)
        setPhoto(p)
        advanceUntilIdle()
        val own = dao.get(me)!!
        dao.putConv(dao.conv(dm)!!.copy(pendingVer = own.ver, dueAt = now + 20_000))
        // My tablet sent my photo (with the same ver) into that DM first: arrives through the sender copy.
        val env = String(ProfilePhotoEnvelope(own.ver, PhotoRef.validate((ProtocolJson.parseToJsonElement(example) as JsonObject)["photo"] as JsonObject, true)).encode())
        e.onEvents(listOf(event("e1", dm, me, "dev-tablet", env)))
        assertNull(dao.conv(dm)!!.pendingVer)
        // A lower ver from a stale sibling doesn't settle it.
        dao.putConv(dao.conv(dm)!!.copy(pendingVer = own.ver, dueAt = now + 20_000))
        e.onEvents(listOf(event("e2", dm, me, "dev-tablet", String(ProfilePhotoEnvelope(own.ver - 5, null).encode()))))
        assertEquals(own.ver, dao.conv(dm)!!.pendingVer)
    }

    @Test fun ownPhotoArrivesFromASiblingAfterAFreshInstall() = runTest {
        val p = photos()
        val e = engine(p)
        mls.groups[dm] = GroupRef(dm, 1, 1)
        now = 1_791_450_724_000L
        e.onEvents(listOf(event("e1", dm, me, "dev-tablet", example)))
        assertEquals("8d2e4f6a-1b3c-4d5e-9f70-a1b2c3d4e5f6", dao.get(me)!!.blobId)
        assertTrue(messages.rows.isEmpty())
    }

    @Test fun dirtyGroupSendsBeforeTheNextOwnMessage() = runTest {
        val p = photos()
        val e = engine(p)
        mls.groups[grp] = GroupRef(grp, 1, 1)
        setPhoto(p)
        advanceUntilIdle()
        realtime.sentGroup.clear()
        dao.putConv(dao.conv(grp)!!.copy(dirty = true))
        e.sendText(grp, "hello")
        advanceUntilIdle()
        assertEquals(2, realtime.sentGroup.size)
        assertEquals(true, realtime.sentGroup[0].silent) // the photo first
        assertNull(realtime.sentGroup[1].silent)
        assertFalse(dao.conv(grp)!!.dirty)
    }

    // ---- group icons (§18.7) ----

    @Test fun groupIconFollowsGroupMeta() = runTest {
        val p = photos()
        engine(p)
        mls.groups[grp] = GroupRef(grp, 1, 1)
        val icon = (ProtocolJson.parseToJsonElement(res("group_meta_icon.json")) as JsonObject)["icon"]
        mls.metas[grp] = lk.codegen.risime.net.GroupMeta(name = "Pilot team", icon = icon, admins = listOf(me))
        p.reconcile()
        assertEquals("5f6a7b8c-9d0e-4f1a-8b2c-3d4e5f6a7b8c", dao.get(grp)!!.blobId)
        mls.metas[grp] = lk.codegen.risime.net.GroupMeta(name = "Pilot team", icon = null, admins = listOf(me))
        p.reconcile()
        assertNull(dao.get(grp))
    }

    // ---- the crop and re-encode (§18.6) ----

    private fun jpeg(w: Int, h: Int): ByteArray {
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = java.awt.Color.ORANGE; g.fillRect(0, 0, w, h); g.dispose()
        return AwtBitmapOps().encodeJpeg(img, 90) // hostile: carries EXIF, XMP, ICC
    }

    @Test fun cropIsSquareAt512WithoutMetadata() {
        val src = jpeg(1600, 1200)
        assertTrue(ImageBytes.findMetadata(src).isNotEmpty())
        val out = AvatarEncoder(AwtBitmapOps()).encode(src, CropSquare.centred(1600, 1200))
        assertEquals(512, out.side)
        assertTrue(ImageBytes.findMetadata(out.bytes).isEmpty())
        assertEquals(512 to 512, ImageBytes.dimensions(out.bytes))
    }

    @Test fun smallCropKeepsItsSideAndTinyOnesAreRefused() {
        val out = AvatarEncoder(AwtBitmapOps()).encode(jpeg(300, 200), CropSquare.centred(300, 200))
        assertEquals(200, out.side)
        val e = runCatching { AvatarEncoder(AwtBitmapOps()).encode(jpeg(300, 200), CropSquare(0f, 0f, 0.25f)) }.exceptionOrNull()
        assertTrue(e is ImageRejected && e.message == AvatarEncoder.TOO_SMALL)
    }
}
