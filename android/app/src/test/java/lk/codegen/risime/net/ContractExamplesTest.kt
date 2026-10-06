package lk.codegen.risime.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses every file in contract/v1/examples (copied into test resources by copyContractExamples). */
class ContractExamplesTest {

    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("contract/v1/examples/$name")?.readText()
            ?: error("missing contract example $name")

    private val all: List<String> by lazy { read("index.txt").lines().filter { it.isNotBlank() } }

    /** Every example file must map to a model; a new file without a decoder fails this test. */
    private val decoders: Map<String, (String) -> Any> = mapOf(
        "auth_verify_reply.json" to { s -> ProtocolJson.decodeFromString<AuthVerifyReply>(s) },
        "contacts_reply.json" to { s -> ProtocolJson.decodeFromString<ContactsReply>(s) },
        "event_message.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.messageData()) } },
        "event_status.json" to { s -> ProtocolJson.decodeFromString<Event>(s).also { requireNotNull(it.statusData()) } },
        "join_reply.json" to { s -> ProtocolJson.decodeFromString<EventsPage>(s) },
        "msg_send.json" to { s -> ProtocolJson.decodeFromString<MsgSend>(s) },
        "msg_send_reply.json" to { s -> ProtocolJson.decodeFromString<MsgSendReply>(s) },
        "presence_watch.json" to { s -> ProtocolJson.decodeFromString<PresenceWatch>(s) },
        "presence_watch_reply.json" to { s -> ProtocolJson.decodeFromString<PresenceWatchReply>(s) },
        "signal_presence.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.presence()) } },
        "signal_typing.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.typing()) } },
        "typing.json" to { s -> ProtocolJson.decodeFromString<TypingPush>(s) },
        "auth_config.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s) },
        "error_not_allowlisted.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_invalid_token.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_identity_conflict.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "auth_refresh.json" to { s -> ProtocolJson.decodeFromString<AuthRefresh>(s) },
        "auth_refresh_reply.json" to { s -> ProtocolJson.decodeFromString<AuthRefreshReply>(s) },
        "auth_refresh_error.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s) },
        "auth_config_v14.json" to { s -> ProtocolJson.decodeFromString<AuthConfig>(s) },
        "me_reply_unverified.json" to { s -> ProtocolJson.decodeFromString<MeReply>(s) },
        "phone_verify_request_reply.json" to { s -> ProtocolJson.decodeFromString<PhoneVerifyRequestReply>(s) },
        "phone_verify_confirm.json" to { s -> ProtocolJson.decodeFromString<PhoneVerifyConfirm>(s) },
        "error_phone_unverified.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_invalid_code_attempts.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_already_verified.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "error_sms_unavailable.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "device_put.json" to { s -> ProtocolJson.decodeFromString<DevicePut>(s) },
        "push_inbox.json" to { s -> ProtocolJson.decodeFromString<PushPayload>(s) },
        "error_invalid_device.json" to { s -> ProtocolJson.decodeFromString<ApiErrorEnvelope>(s) },
        "invite_create.json" to { s -> ProtocolJson.decodeFromString<InviteCreate>(s) },
        "invite_reply.json" to { s -> ProtocolJson.decodeFromString<InviteReply>(s) },
        "invites_reply.json" to { s -> ProtocolJson.decodeFromString<InvitesReply>(s) },
        "friend_request.json" to { s -> ProtocolJson.decodeFromString<FriendRequestCreate>(s) },
        "friend_request_reply.json" to { s -> ProtocolJson.decodeFromString<FriendRequestReply>(s) },
        "friends_reply.json" to { s -> ProtocolJson.decodeFromString<FriendsReply>(s) },
        "friend_accept_reply.json" to { s -> ProtocolJson.decodeFromString<FriendAcceptReply>(s) },
        "block.json" to { s -> ProtocolJson.decodeFromString<BlockCreate>(s) },
        "signal_friend.json" to { s -> ProtocolJson.decodeFromString<Signal>(s).also { requireNotNull(it.friend()) } },
        "error_not_friends.json" to { s -> ProtocolJson.decodeFromString<ErrorReason>(s) },
        "user_vouched.json" to { s -> ProtocolJson.decodeFromString<MeReply>(s) },
    )

    @Test
    fun everyExampleParses() {
        assertTrue("no contract examples found", all.isNotEmpty())
        for (name in all) {
            val decode = decoders[name] ?: throw AssertionError("no decoder for contract example $name")
            assertNotNull(name, decode(read(name)))
        }
    }

    /** Re-encoding a model yields the same JSON object as the example (field names match exactly). */
    @Test
    fun clientSentPayloadsRoundTrip() {
        val original = ProtocolJson.parseToJsonElement(read("msg_send.json")) as JsonObject
        val model = ProtocolJson.decodeFromJsonElement<MsgSend>(original)
        assertEquals(original, ProtocolJson.encodeToJsonElement(model))
    }

    @Test
    fun clientSentV12PayloadsRoundTrip() {
        for (name in listOf("presence_watch.json", "typing.json")) {
            val original = ProtocolJson.parseToJsonElement(read(name)) as JsonObject
            val encoded = when (name) {
                "typing.json" -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<TypingPush>(original))
                else -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<PresenceWatch>(original))
            }
            assertEquals(name, original, encoded)
        }
    }

    @Test
    fun presenceExamples() {
        val reply = ProtocolJson.decodeFromString<PresenceWatchReply>(read("presence_watch_reply.json"))
        val p = reply.presences.single()
        assertFalse(p.online)
        assertNotNull(p.lastSeen)
        val watch = ProtocolJson.decodeFromString<PresenceWatch>(read("presence_watch.json"))
        assertEquals(watch.userIds, listOf(p.userId))

        val sig = ProtocolJson.decodeFromString<Signal>(read("signal_presence.json"))
        assertEquals(Signal.KIND_PRESENCE, sig.kind)
        val live = sig.presence()!!
        assertTrue(live.online)
        assertEquals(null, live.lastSeen)
        assertEquals(null, sig.typing())
    }

    @Test
    fun typingExamples() {
        val t = ProtocolJson.decodeFromString<Signal>(read("signal_typing.json")).typing()!!
        assertTrue(t.typing)
        val push = ProtocolJson.decodeFromString<TypingPush>(read("typing.json"))
        assertEquals(dmConversationId(t.from, push.to), t.conversationId)
        assertTrue(push.typing)
    }

    @Test
    fun unknownSignalKindIsIgnored() {
        val s = ProtocolJson.decodeFromString<Signal>("""{"kind":"mood","data":{"x":1}}""")
        assertEquals(null, s.presence())
        assertEquals(null, s.typing())
    }

    @Test
    fun authV13Examples() {
        val cfg = ProtocolJson.decodeFromString<AuthConfig>(read("auth_config.json"))
        assertEquals(listOf(AuthConfig.MODE_OIDC, AuthConfig.MODE_DEV), cfg.modes)
        assertEquals("https://risicloud.ai/realms/aoa", cfg.issuer)
        assertEquals("risime", cfg.clientId)
        mapOf(
            "error_not_allowlisted.json" to AuthErrors.NOT_ALLOWLISTED,
            "error_invalid_token.json" to AuthErrors.INVALID_TOKEN,
            "error_identity_conflict.json" to AuthErrors.IDENTITY_CONFLICT,
        ).forEach { (file, code) ->
            val e = ProtocolJson.decodeFromString<ApiErrorEnvelope>(read(file)).error
            assertEquals(code, e.code)
            assertTrue(e.message.isNotBlank())
        }
        val refresh = ProtocolJson.parseToJsonElement(read("auth_refresh.json")) as JsonObject
        assertEquals(refresh, ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<AuthRefresh>(refresh)))
        assertTrue(ProtocolJson.decodeFromString<AuthRefreshReply>(read("auth_refresh_reply.json")).expiresAt.endsWith("Z"))
        assertEquals(AuthErrors.IDENTITY_MISMATCH, ProtocolJson.decodeFromString<ErrorReason>(read("auth_refresh_error.json")).reason)
        // A config with only "dev" has no issuer/client_id.
        val dev = ProtocolJson.decodeFromString<AuthConfig>("""{"modes":["dev"]}""")
        assertEquals(null, dev.issuer)
    }

    @Test
    fun phoneVerificationV14Examples() {
        val cfg = ProtocolJson.decodeFromString<AuthConfig>(read("auth_config_v14.json"))
        assertTrue(cfg.phoneVerificationRequired)
        assertFalse(ProtocolJson.decodeFromString<AuthConfig>(read("auth_config.json")).phoneVerificationRequired) // absent = off

        val unverified = ProtocolJson.decodeFromString<MeReply>(read("me_reply_unverified.json")).user
        assertFalse(unverified.phoneVerified)
        // Absent phone_verified means true (pre-v1.4 servers).
        assertTrue(ProtocolJson.decodeFromString<AuthVerifyReply>(read("auth_verify_reply.json")).user.phoneVerified)

        val sent = ProtocolJson.decodeFromString<PhoneVerifyRequestReply>(read("phone_verify_request_reply.json"))
        assertEquals("sent", sent.status)
        assertEquals(300, sent.expiresIn)
        assertTrue(sent.to.contains('\u2022'))
        assertTrue(sent.to.endsWith("01"))

        val confirm = ProtocolJson.parseToJsonElement(read("phone_verify_confirm.json")) as JsonObject
        assertEquals(confirm, ProtocolJson.encodeToJsonElement(PhoneVerifyConfirm("123456")))

        mapOf(
            "error_phone_unverified.json" to AuthErrors.PHONE_UNVERIFIED,
            "error_already_verified.json" to AuthErrors.ALREADY_VERIFIED,
            "error_sms_unavailable.json" to AuthErrors.SMS_UNAVAILABLE,
            "error_invalid_code_attempts.json" to AuthErrors.INVALID_CODE,
        ).forEach { (file, code) ->
            val e = ProtocolJson.decodeFromString<ApiErrorEnvelope>(read(file)).error
            assertEquals(file, code, e.code)
            assertTrue(e.message.isNotBlank())
        }
        assertEquals(3, ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_invalid_code_attempts.json")).error.attemptsLeft)
        assertEquals(null, ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_sms_unavailable.json")).error.attemptsLeft)
    }

    @Test
    fun retryAfterParsing() {
        assertEquals(120L, parseRetryAfter("120", 0))
        assertEquals(0L, parseRetryAfter("-5", 0))
        assertEquals(null, parseRetryAfter(null, 0))
        assertEquals(null, parseRetryAfter("soon", 0))
        val now = java.time.Instant.parse("2026-10-06T08:00:00Z").toEpochMilli()
        assertEquals(90L, parseRetryAfter("Tue, 06 Oct 2026 08:01:30 GMT", now))
        assertEquals(0L, parseRetryAfter("Tue, 06 Oct 2026 07:00:00 GMT", now))
    }

    @Test
    fun pushV15Examples() {
        val put = ProtocolJson.parseToJsonElement(read("device_put.json")) as JsonObject
        val model = ProtocolJson.decodeFromJsonElement<DevicePut>(put)
        assertEquals(DevicePut.PLATFORM_ANDROID, model.platform)
        assertEquals(put, ProtocolJson.encodeToJsonElement(model))
        assertFalse(model.toString().contains(model.pushToken))

        val push = ProtocolJson.decodeFromString<PushPayload>(read("push_inbox.json"))
        assertTrue(push.isInbox)
        assertEquals("1", push.v)
        // FCM delivers data as a string map; no content fields exist in the payload.
        assertEquals(push, PushPayload.fromData(mapOf("type" to "inbox", "v" to "1")))
        assertFalse(PushPayload.fromData(mapOf("type" to "other"))!!.isInbox)
        assertEquals(null, PushPayload.fromData(emptyMap()))

        assertEquals(AuthErrors.INVALID_DEVICE, ProtocolJson.decodeFromString<ApiErrorEnvelope>(read("error_invalid_device.json")).error.code)
    }

    @Test
    fun friendsV16Examples() {
        // Client-sent payloads round-trip exactly.
        for ((file, enc) in listOf<Pair<String, (JsonObject) -> kotlinx.serialization.json.JsonElement>>(
            "invite_create.json" to { o -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<InviteCreate>(o)) },
            "friend_request.json" to { o -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<FriendRequestCreate>(o)) },
            "block.json" to { o -> ProtocolJson.encodeToJsonElement(ProtocolJson.decodeFromJsonElement<BlockCreate>(o)) },
        )) {
            val o = ProtocolJson.parseToJsonElement(read(file)) as JsonObject
            assertEquals(file, o, enc(o))
        }
        val inv = ProtocolJson.decodeFromString<InviteReply>(read("invite_reply.json")).invite
        assertTrue(inv.pending)
        assertEquals("Join me on RisiMe", inv.subject)
        assertTrue(inv.shareText.contains(inv.link) && inv.shareText.contains(inv.email))
        assertTrue(inv.shareText.length <= 300)
        assertEquals(inv, ProtocolJson.decodeFromString<InvitesReply>(read("invites_reply.json")).invites.single())
        assertEquals("requested", ProtocolJson.decodeFromString<FriendRequestReply>(read("friend_request_reply.json")).status)

        val f = ProtocolJson.decodeFromString<FriendsReply>(read("friends_reply.json"))
        assertEquals(1, f.friends.size)
        assertEquals(null, f.friends.single().vouchedBy)
        val incoming = f.incoming.single()
        assertNotNull(incoming.userId)
        assertNotNull(incoming.displayName)
        val outgoing = f.outgoing.single()
        assertEquals(null, outgoing.userId) // outgoing never reveals registration
        assertEquals(null, outgoing.displayName)
        assertEquals("Test User F", f.blocked.single().displayName)
        assertEquals(f.friends.single(), ProtocolJson.decodeFromString<FriendAcceptReply>(read("friend_accept_reply.json")).friend)

        val sig = ProtocolJson.decodeFromString<Signal>(read("signal_friend.json"))
        val fs = sig.friend()!!
        assertEquals(FriendSignal.REQUEST_RECEIVED, fs.action)
        assertEquals(incoming.id, fs.requestId)
        assertEquals(incoming.userId, fs.user.userId)
        assertEquals(null, sig.presence())

        assertEquals(AuthErrors.NOT_FRIENDS, ProtocolJson.decodeFromString<ErrorReason>(read("error_not_friends.json")).reason)
        val vouched = ProtocolJson.decodeFromString<MeReply>(read("user_vouched.json")).user
        assertEquals("Test User B", vouched.vouchedBy!!.displayName)
        assertEquals(null, ProtocolJson.decodeFromString<AuthVerifyReply>(read("auth_verify_reply.json")).user.vouchedBy)
    }

    @Test
    fun messageEventFields() {
        val e = ProtocolJson.decodeFromString<Event>(read("event_message.json"))
        val m = e.messageData()!!
        assertEquals(Event.KIND_MESSAGE, e.kind)
        assertEquals(dmConversationId(m.from, m.to), m.conversationId)
        assertEquals(null, e.statusData())
    }

    @Test
    fun statusEventFields() {
        val s = ProtocolJson.decodeFromString<Event>(read("event_status.json")).statusData()!!
        assertEquals("delivered", s.status)
    }

    @Test
    fun contactsUnregisteredHasNullUserId() {
        val c = ProtocolJson.decodeFromString<ContactsReply>(read("contacts_reply.json")).contacts
        assertTrue(c.any { !it.registered && it.userId == null })
        assertFalse(c.isEmpty())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val s = """{"events":[],"has_more":false,"server_time":"2026-10-06T08:15:29.000Z","extra":1}"""
        assertFalse(ProtocolJson.decodeFromString<EventsPage>(s).hasMore)
    }
}
