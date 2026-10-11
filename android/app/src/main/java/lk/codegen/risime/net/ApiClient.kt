package lk.codegen.risime.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** The server answered with an error envelope (§0) or a bare status. */
    data class Error(
        val httpStatus: Int,
        val code: String,
        val message: String,
        /** §7.1: `Retry-After` seconds on a 429 (null when absent/unparseable). */
        val retryAfterSec: Long? = null,
        val attemptsLeft: Int? = null,
        /** §10.2: 409 epoch_conflict's current epoch; 409 not_ready's missing devices. */
        val epoch: Long? = null,
        val missing: List<MlsMissing>? = null,
        /** §13.4/§14.5 413 quota_exceeded numbers. */
        val used: Long? = null,
        val limit: Long? = null,
        /** The whole error body (e.g. §22.3 `device_name`, `bk_id`). */
        val detail: ApiErrorBody? = null,
    ) : ApiResult<Nothing>
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>
}

/** REST client for PROTOCOL.md §1. The server base URL and token are read on every call. */
class ApiClient(
    private val http: OkHttpClient,
    private val serverUrl: suspend () -> String,
    private val token: suspend () -> String?,
    /** A 401 on an authenticated call: try to refresh; true = retry the call once (§6.1). */
    private val onUnauthorized: suspend () -> Boolean = { false },
    /** This install's device id: sent as X-Device-Id on group reads (§24.7: Official groups are visible only to a `tabs` device). */
    private val deviceId: suspend () -> String? = { null },
) {
    private suspend fun deviceHeaders(): Map<String, String> = deviceId()?.let { mapOf(DEVICE_HEADER to it) }.orEmpty()

    suspend fun requestCode(phone: String, email: String): ApiResult<AuthRequestReply> =
        call("POST", "auth/request", AuthRequest(phone, email), auth = false)

    suspend fun verify(phone: String, code: String, deviceName: String): ApiResult<AuthVerifyReply> =
        call("POST", "auth/verify", AuthVerify(phone, code, deviceName), auth = false)

    /** §6.1: which sign-in modes the server offers. A pre-v1.3 server answers 404. */
    suspend fun authConfig(): ApiResult<AuthConfig> = call<Unit, AuthConfig>("GET", "auth/config", null, auth = false)

    /** §21.3 (v1.20): create an account for the signed-in Keycloak identity (bearer token, not mapped first). */
    suspend fun signup(req: SignupRequest): ApiResult<MeReply> = call("POST", "auth/signup", req)

    /** §7.1: SMS a code to the user's allowlisted phone. */
    suspend fun requestPhoneCode(): ApiResult<PhoneVerifyRequestReply> =
        call("POST", "me/phone/verify/request", JsonObject(emptyMap()))

    suspend fun confirmPhoneCode(code: String): ApiResult<MeReply> =
        call("POST", "me/phone/verify/confirm", PhoneVerifyConfirm(code))

    /** §8.1: register/refresh this install's push token (idempotent). 204 without MLS. */
    suspend fun putDevice(deviceId: String, body: DevicePut): ApiResult<Unit> =
        call<DevicePut, Unit>("PUT", "me/devices/$deviceId", body)

    /** §10.1: with `mls`, the reply is 200 {"attestation"}; 503 mls_unavailable when E2EE is off. */
    suspend fun putMlsDevice(deviceId: String, body: DevicePut): ApiResult<DevicePutReply> =
        call("PUT", "me/devices/$deviceId", body)

    suspend fun attestationKeys(): ApiResult<AttestationKeys> = call<Unit, AttestationKeys>("GET", "mls/attestation_keys", null, auth = false)

    suspend fun uploadKeyPackages(deviceId: String, body: KeyPackagesUpload): ApiResult<Unit> =
        call("POST", "me/devices/$deviceId/key_packages", body)

    suspend fun keyPackageCount(deviceId: String): ApiResult<KeyPackageCount> =
        call<Unit, KeyPackageCount>("GET", "me/devices/$deviceId/key_packages/count", null)

    /** §10.2: [deviceId] (X-Device-Id) excludes the calling device from my own devices. */
    suspend fun claimKeyPackages(userIds: List<String>, deviceId: String? = null, conversationId: String? = null): ApiResult<KeyPackagesClaimReply> =
        call("POST", "mls/key_packages/claim", KeyPackagesClaim(userIds, conversationId), headers = deviceId?.let { mapOf(DEVICE_HEADER to it) }.orEmpty())

    suspend fun mlsGroup(conversationId: String): ApiResult<MlsGroup> = call<Unit, MlsGroup>("GET", "mls/groups/$conversationId", null, headers = deviceHeaders())

    /** §10.2: X-Device-Id is required here (it names from_device). */
    suspend fun mlsCommit(conversationId: String, body: MlsCommitRequest, deviceId: String): ApiResult<MlsCommitReply> =
        call("POST", "mls/groups/$conversationId/commit", body, headers = mapOf(DEVICE_HEADER to deviceId))

    /** v1.16: ask for this device to be (re-)added to an e2ee DM group (X-Device-Id required). */
    suspend fun mlsRejoin(conversationId: String, deviceId: String): ApiResult<DmRejoinReply> =
        call<Unit, DmRejoinReply>("POST", "mls/groups/$conversationId/rejoin", null, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun mlsCommits(conversationId: String, sinceEpoch: Long, limit: Int? = null): ApiResult<MlsCommitsReply> =
        call<Unit, MlsCommitsReply>("GET", "mls/groups/$conversationId/commits?since_epoch=$sinceEpoch" + (limit?.let { "&limit=$it" } ?: ""), null, headers = deviceHeaders())

    // ---- §12 groups (mutating calls carry X-Device-Id) ----
    /** §16.7 TURN REST credentials (503 calls_unavailable while the server has none: STUN only). Never persisted. */
    suspend fun callsTurn(): ApiResult<CallsTurnReply> = call<Unit, CallsTurnReply>("GET", "calls/turn", null)

    /** §20.2 `start`/`join` a group call's LiveKit room (X-Device-Id required). The token is never logged. */
    suspend fun callsRoom(body: CallsRoomRequest, deviceId: String): ApiResult<CallsRoomReply> =
        call("POST", "calls/rooms", body, headers = mapOf(DEVICE_HEADER to deviceId))

    /** §20.2 `status`: is the group call's room still there (the chat line's Join). */
    suspend fun callsRoomStatus(conversationId: String, callId: String, media: String, deviceId: String): ApiResult<CallsRoomStatusReply> =
        call("POST", "calls/rooms", CallsRoomRequest(conversationId, callId, media, CallsRoomRequest.STATUS), headers = mapOf(DEVICE_HEADER to deviceId))

    // ---- §24 (v1.24) chats and their tabs (tabs devices only) ----

    suspend fun chats(deviceId: String): ApiResult<ChatsReply> = call<Unit, ChatsReply>("GET", "chats", null, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun chat(chatId: String, deviceId: String): ApiResult<ChatReply> = call<Unit, ChatReply>("GET", "chats/$chatId", null, headers = mapOf(DEVICE_HEADER to deviceId))

    /** `201` (created, `state: creating`) or `200` (it already exists): both carry the Official group. */
    suspend fun createOfficial(chatId: String, deviceId: String): ApiResult<GroupReply> =
        call("POST", "chats/$chatId/official", JsonObject(emptyMap()), headers = mapOf(DEVICE_HEADER to deviceId))

    /** `official`: "on" | "off". */
    suspend fun patchChat(chatId: String, official: String, deviceId: String): ApiResult<ChatReply> =
        call("PATCH", "chats/$chatId", ChatPatch(official), headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun groups(): ApiResult<GroupsReply> = call<Unit, GroupsReply>("GET", "groups", null, headers = deviceHeaders())

    suspend fun group(id: String): ApiResult<GroupReply> = call<Unit, GroupReply>("GET", "groups/$id", null, headers = deviceHeaders())

    suspend fun createGroup(body: GroupCreate, deviceId: String): ApiResult<GroupReply> =
        call("POST", "groups", body, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun addGroupMembers(id: String, userIds: List<String>, deviceId: String): ApiResult<GroupReply> =
        call("POST", "groups/$id/members", GroupMembersAdd(userIds), headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun removeGroupMember(id: String, userId: String, deviceId: String): ApiResult<Unit> =
        call<Unit, Unit>("DELETE", "groups/$id/members/$userId", null, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun leaveGroup(id: String, deviceId: String): ApiResult<Unit> =
        call<Unit, Unit>("POST", "groups/$id/leave", null, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun setGroupRole(id: String, userId: String, role: String, deviceId: String): ApiResult<GroupReply> =
        call("PATCH", "groups/$id/members/$userId", GroupRolePatch(role), headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun rejoinGroup(id: String, deviceId: String): ApiResult<GroupRejoinReply> =
        call<Unit, GroupRejoinReply>("POST", "groups/$id/rejoin", null, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun resetGroup(id: String, generation: Long, deviceId: String): ApiResult<GroupResetReply> =
        call("POST", "mls/groups/$id/reset", GroupReset(generation), headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun groupCommit(id: String, body: GroupCommitRequest, deviceId: String): ApiResult<MlsCommitReply> =
        call("POST", "mls/groups/$id/commit", body, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun groupReceipts(id: String, messageId: String): ApiResult<GroupReceiptsReply> =
        call<Unit, GroupReceiptsReply>("GET", "groups/$id/messages/$messageId/receipts", null)

    /** §12.6: opaque bytes (an MLS commit or Welcome over 64 KiB). */
    suspend fun uploadBlob(conversationId: String, bytes: ByteArray): ApiResult<BlobUploadReply> =
        execute("POST", "blobs?purpose=mls&conversation_id=$conversationId", bytes.toRequestBody(OCTET), true, serializer<BlobUploadReply>())

    suspend fun downloadBlob(blobId: String): ApiResult<ByteArray> =
        execute("GET", "blobs/$blobId", null, true, BYTES)

    // ---- §14.2 media blobs (v1.11) ----

    /**
     * Streams [file] as the body (OkHttp sends its Content-Length; never buffered in memory).
     * Idempotent by [clientBlobId]: a repeat after a completed upload answers 200 with the same blob.
     */
    suspend fun uploadMediaBlob(
        conversationId: String,
        clientBlobId: String,
        file: java.io.File,
        purpose: String = "media",
        /** Bytes written to the socket so far and the total (restarts at 0 if the request is retried). */
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): ApiResult<BlobUploadReply> =
        execute(
            "POST", "blobs?purpose=$purpose&conversation_id=$conversationId&client_blob_id=$clientBlobId",
            if (onProgress == null) file.asRequestBody(OCTET) else ProgressFileBody(file, OCTET, onProgress), true, serializer<BlobUploadReply>(),
        )

    /**
     * §17.9 a `history` blob (one sealed bundle part), only by the accepted provider device
     * ([deviceId] as X-Device-Id); idempotent by [clientBlobId].
     */
    suspend fun uploadHistoryBlob(conversationId: String, requestId: String, clientBlobId: String, file: java.io.File, deviceId: String): ApiResult<BlobUploadReply> =
        execute(
            "POST", "blobs?purpose=history&conversation_id=$conversationId&request_id=$requestId&client_blob_id=$clientBlobId",
            file.asRequestBody(OCTET), true, serializer<BlobUploadReply>(), mapOf(DEVICE_HEADER to deviceId),
        )

    /**
     * §18.3 an `avatar` blob (no conversation), idempotent by [clientBlobId]; the reply's
     * `expires_at` is null (the current avatar).
     */
    suspend fun uploadAvatarBlob(clientBlobId: String, file: java.io.File): ApiResult<BlobUploadReply> =
        execute("POST", "blobs?purpose=avatar&client_blob_id=$clientBlobId", file.asRequestBody(OCTET), true, serializer<BlobUploadReply>())

    // ---- §22 encrypted backups (v1.22): writing calls carry X-Device-Id ----

    /**
     * §22.3 one part of a backup file: bytes [offset, offset+length) of [file], streamed with its
     * Content-Length; idempotent by [clientBlobId] (a retry of the same part reuses it).
     */
    suspend fun uploadBackupPart(backupId: String, clientBlobId: String, file: java.io.File, offset: Long, length: Long, deviceId: String): ApiResult<BlobUploadReply> =
        execute(
            "POST", "blobs?purpose=backup&backup_id=$backupId&client_blob_id=$clientBlobId",
            FileRangeBody(file, offset, length, OCTET), true, serializer<BlobUploadReply>(), mapOf(DEVICE_HEADER to deviceId),
        )

    suspend fun createBackup(body: BackupCreateRequest, deviceId: String): ApiResult<BackupCreateReply> =
        call("POST", "backups", body, headers = mapOf(DEVICE_HEADER to deviceId))

    suspend fun backups(): ApiResult<BackupsReply> = call<Unit, BackupsReply>("GET", "backups", null)

    /** Deletes every backup and the key record (idempotent 204). */
    suspend fun deleteBackups(deviceId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "backups", null, headers = mapOf(DEVICE_HEADER to deviceId))

    /** [recordJson] is the core's `BackupKey` JSON as is. */
    suspend fun putBackupKey(recordJson: String, deviceId: String): ApiResult<BackupKeyReply> =
        execute("PUT", "backup_key", recordJson.toRequestBody(JSON), true, serializer<BackupKeyReply>(), mapOf(DEVICE_HEADER to deviceId))

    /** 404 no_backup_key without a record. */
    suspend fun backupKey(): ApiResult<BackupKeyReply> = call<Unit, BackupKeyReply>("GET", "backup_key", null)

    /** Owner only, idempotent 204 (a cancelled send after the upload). */
    suspend fun deleteBlob(blobId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "blobs/$blobId", null)

    suspend fun blobUsage(): ApiResult<BlobUsageReply> = call<Unit, BlobUsageReply>("GET", "blobs/usage", null)

    /**
     * §14.2 streamed, resumable download into [part]: from byte [from] with `Range` and `If-Range`
     * ([etag], the strong ETag = SHA-256 hex); a full `200` (e.g. If-Range mismatch) rewrites the
     * file. Returns the file's length afterwards. Never holds the body in memory.
     */
    suspend fun downloadBlobTo(blobId: String, part: java.io.File, from: Long, etag: String?): ApiResult<Long> {
        suspend fun once(): ApiResult<Long> {
            val url = serverUrl().trimEnd('/').toHttpUrl().newBuilder().addPathSegments("api/v1/blobs/$blobId").build()
            val b = Request.Builder().url(url).get()
            token()?.let { b.header("Authorization", "Bearer $it") }
            if (from > 0) {
                b.header("Range", "bytes=$from-")
                etag?.let { b.header("If-Range", "\"$it\"") }
            }
            return withContext(Dispatchers.IO) {
                try {
                    http.newCall(b.build()).execute().use { res ->
                        when {
                            res.code == 206 && from > 0 -> {
                                val start = res.header("Content-Range")?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
                                if (start != from) return@use ApiResult.Error(206, "bad_range", "Content-Range ${res.header("Content-Range")}")
                                java.io.FileOutputStream(part, true).use { out -> res.body.byteStream().copyTo(out) }
                                ApiResult.Ok(part.length())
                            }
                            res.code == 200 -> {
                                java.io.FileOutputStream(part, false).use { out -> res.body.byteStream().copyTo(out) }
                                ApiResult.Ok(part.length())
                            }
                            res.code == 416 -> ApiResult.Ok(part.length())
                            else -> {
                                val text = runCatching { res.body.string() }.getOrDefault("")
                                val err = runCatching { ProtocolJson.decodeFromString<ApiErrorEnvelope>(text).error }.getOrNull()
                                ApiResult.Error(res.code, err?.code ?: "http_${res.code}", err?.message ?: "", retryAfterSec = parseRetryAfter(res.header("Retry-After"), System.currentTimeMillis()))
                            }
                        }
                    }
                } catch (e: IOException) {
                    ApiResult.NetworkError(e)
                }
            }
        }
        val first = once()
        if (first is ApiResult.Error && first.httpStatus == 401 && onUnauthorized()) return once()
        return first
    }

    /** §8.1: at logout (idempotent). */
    suspend fun deleteDevice(deviceId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "me/devices/$deviceId", null)

    /** Decision 050: plain logout unregisters push only; the device stays in its groups. */
    suspend fun unregisterPush(deviceId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "me/devices/$deviceId/push_token", null)

    // ---- §9 invites and friends ----
    suspend fun createInvite(body: InviteCreate): ApiResult<InviteReply> = call("POST", "invites", body)

    suspend fun invites(): ApiResult<InvitesReply> = call<Unit, InvitesReply>("GET", "invites", null)

    suspend fun revokeInvite(id: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "invites/$id", null)

    suspend fun requestFriend(phone: String): ApiResult<FriendRequestReply> = call("POST", "friends/requests", FriendRequestCreate(phone))

    suspend fun friends(): ApiResult<FriendsReply> = call<Unit, FriendsReply>("GET", "friends", null)

    suspend fun acceptRequest(id: String): ApiResult<FriendAcceptReply> = call<Unit, FriendAcceptReply>("POST", "friends/requests/$id/accept", null)

    suspend fun declineRequest(id: String): ApiResult<Unit> = call<Unit, Unit>("POST", "friends/requests/$id/decline", null)

    suspend fun cancelRequest(id: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "friends/requests/$id", null)

    suspend fun unfriend(userId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "friends/$userId", null)

    suspend fun block(userId: String): ApiResult<Unit> = call("POST", "blocks", BlockCreate(userId))

    suspend fun unblock(userId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "blocks/$userId", null)

    suspend fun me(): ApiResult<MeReply> = call<Unit, MeReply>("GET", "me", null)

    suspend fun updateDisplayName(name: String): ApiResult<MeReply> = call("PATCH", "me", PatchMe(name))

    /** §24.11 `PATCH /me {"tz"}`: the phone's IANA zone (Risi's reminders and digest run in it). */
    suspend fun patchTimezone(tz: String): ApiResult<MeReply> = call("PATCH", "me", PatchTz(tz))

    // ---- §24.11 Risi (private REST) ----
    suspend fun risiFeedback(body: RisiFeedback): ApiResult<Unit> = call("POST", "risi/feedback", body)

    /** §25.8: with `X-Device-Id` (a `risi_tools` device sees notes as `note`, others as `preference`). */
    suspend fun risiFacts(): ApiResult<RisiFactsReply> = call<Unit, RisiFactsReply>("GET", "risi/facts", null, headers = deviceHeaders())

    // ---- §25 (v1.25) Risi with tools (risi_tools devices only) ----

    /** §25.2 `201` (created, `state: creating`) or `200` (the existing one); both carry the chat and its group. */
    suspend fun createRisiChat(deviceId: String): ApiResult<RisiChatReply> =
        call("POST", "risi/chat", JsonObject(emptyMap()), headers = mapOf(DEVICE_HEADER to deviceId))

    /** §25.3 a client tool's result (`204`); never logged. */
    suspend fun postRisiToolResult(toolCallId: String, body: RisiToolResult, deviceId: String): ApiResult<Unit> =
        call("POST", "risi/tool_calls/$toolCallId/result", body, headers = mapOf(DEVICE_HEADER to deviceId))

    // ---- §26 (v1.26) Risi skills ----
    /** `GET /risi/skills` (with `X-Device-Id`: `client` is this device's reported permission). */
    suspend fun risiSkills(): ApiResult<RisiSkillsReply> = call<Unit, RisiSkillsReply>("GET", "risi/skills", null, headers = deviceHeaders())

    /** `PATCH /risi/skills` (a `risi_skills` device only, else `403 invalid_device`). */
    suspend fun patchRisiSkills(body: RisiSkillsPatch): ApiResult<RisiSkillsReply> = call("PATCH", "risi/skills", body, headers = deviceHeaders())

    suspend fun risiSkillActivity(skillId: String, before: String? = null): ApiResult<RisiActivityReply> =
        call<Unit, RisiActivityReply>("GET", "risi/skills/$skillId/activity" + (before?.let { "?before=$it" } ?: ""), null, headers = deviceHeaders())

    suspend fun clearRisiSkillActivity(skillId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "risi/skills/$skillId/activity", null, headers = deviceHeaders())

    suspend fun undoRisiSkillEntry(skillId: String, entryId: String, token: String): ApiResult<RisiUndoReply> =
        call("POST", "risi/skills/$skillId/activity/$entryId/undo", RisiUndoRequest(token), headers = deviceHeaders())

    suspend fun deleteRisiFact(factId: String): ApiResult<Unit> = call<Unit, Unit>("DELETE", "risi/facts/$factId", null)

    suspend fun deleteRisiFacts(): ApiResult<Unit> = call<Unit, Unit>("DELETE", "risi/facts", null)

    suspend fun risiCommitments(state: String): ApiResult<RisiCommitmentsReply> = call<Unit, RisiCommitmentsReply>("GET", "risi/commitments?state=$state", null, headers = deviceHeaders()) // §30.5: `note_id` only for a risi_notes device

    // ---- §29.3 Risi Calendar (a `risi_events` device; else 403 invalid_device) ----
    private fun q(v: String) = java.net.URLEncoder.encode(v, "UTF-8")

    suspend fun risiCalendarEvents(from: String, to: String): ApiResult<RisiCalendarEventsReply> =
        call<Unit, RisiCalendarEventsReply>("GET", "risi/calendar/events?from=${q(from)}&to=${q(to)}", null, headers = deviceHeaders())

    // ---- v1.35 §34.4 Risi's items (X-Device-Id of the caller's device) ----
    suspend fun risiItems(from: String? = null, to: String? = null, kinds: List<String>? = null): ApiResult<RisiItemsReply> {
        val qs = listOfNotNull(from?.let { "from=${q(it)}" }, to?.let { "to=${q(it)}" }, kinds?.takeIf { it.isNotEmpty() }?.let { "kinds=${q(it.joinToString(","))}" })
        return call<Unit, RisiItemsReply>("GET", "risi/items" + (if (qs.isEmpty()) "" else "?" + qs.joinToString("&")), null, headers = deviceHeaders())
    }

    suspend fun patchRisiItem(id: String, body: RisiItemPatch): ApiResult<RisiItemReply> =
        call("PATCH", "risi/items/${q(id)}", body, headers = deviceHeaders())

    suspend fun deleteRisiItem(id: String): ApiResult<Unit> =
        call<Unit, Unit>("DELETE", "risi/items/${q(id)}", null, headers = deviceHeaders())

    suspend fun risiCalendarChanges(since: String, limit: Int): ApiResult<RisiCalendarChangesReply> =
        call<Unit, RisiCalendarChangesReply>("GET", "risi/calendar/changes?since=${q(since)}&limit=$limit", null, headers = deviceHeaders())

    suspend fun risiCalendarEvent(id: String): ApiResult<RisiEventReply> =
        call<Unit, RisiEventReply>("GET", "risi/calendar/events/$id", null, headers = deviceHeaders())

    suspend fun createRisiCalendarEvent(body: kotlinx.serialization.json.JsonObject): ApiResult<RisiEventReply> =
        call("POST", "risi/calendar/events", body, headers = deviceHeaders())

    suspend fun patchRisiCalendarEvent(id: String, body: kotlinx.serialization.json.JsonObject): ApiResult<RisiEventReply> =
        call("PATCH", "risi/calendar/events/$id", body, headers = deviceHeaders())

    suspend fun deleteRisiCalendarEvent(id: String): ApiResult<Unit> =
        call<Unit, Unit>("DELETE", "risi/calendar/events/$id", null, headers = deviceHeaders())

    suspend fun respondRisiCalendarEvent(id: String, body: kotlinx.serialization.json.JsonObject): ApiResult<RisiEventReply> =
        call("POST", "risi/calendar/events/$id/respond", body, headers = deviceHeaders())

    suspend fun resolveRisiCalendarSuggestion(suggestionId: String, body: kotlinx.serialization.json.JsonObject): ApiResult<RisiEventReply> =
        call("POST", "risi/calendar/suggestions/$suggestionId/resolve", body, headers = deviceHeaders())

    suspend fun risiCalendarSettings(): ApiResult<RisiCalendarSettingsReply> =
        call<Unit, RisiCalendarSettingsReply>("GET", "risi/calendar/settings", null, headers = deviceHeaders())

    suspend fun patchRisiCalendarSettings(body: kotlinx.serialization.json.JsonObject): ApiResult<RisiCalendarSettingsReply> =
        call("PATCH", "risi/calendar/settings", body, headers = deviceHeaders())

    suspend fun deleteRisiCalendar(): ApiResult<Unit> = call<Unit, Unit>("DELETE", "risi/calendar", null, headers = deviceHeaders())

    // ---- §31.3 Google Calendar link (a `google_calendar` device; else 403 invalid_device) ----

    suspend fun googleLink(): ApiResult<GoogleLinkReply> = call<Unit, GoogleLinkReply>("GET", "risi/calendar/google", null, headers = deviceHeaders())

    suspend fun putGoogleLink(body: GoogleLinkPut): ApiResult<GoogleLinkReply> = call("PUT", "risi/calendar/google", body, headers = deviceHeaders())

    suspend fun deleteGoogleLink(removeCopies: Boolean): ApiResult<Unit> =
        call<Unit, Unit>("DELETE", "risi/calendar/google?remove_copies=$removeCopies", null, headers = deviceHeaders())

    // ---- §30.6 Risi Notes (a `risi_notes` device; else 403 invalid_device) ----

    suspend fun risiNotes(query: String?, before: String?, limit: Int): ApiResult<RisiNotesReply> {
        val qs = buildList {
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { add("q=" + q(it.take(100))) }
            before?.let { add("before=" + q(it)) }
            add("limit=" + limit.coerceIn(1, 50))
        }.joinToString("&")
        return call<Unit, RisiNotesReply>("GET", "risi/notes?$qs", null, headers = deviceHeaders())
    }

    suspend fun risiNote(id: String): ApiResult<RisiNoteReply> =
        call<Unit, RisiNoteReply>("GET", "risi/notes/${q(id)}", null, headers = deviceHeaders())

    suspend fun deleteRisiNote(id: String): ApiResult<Unit> =
        call<Unit, Unit>("DELETE", "risi/notes/${q(id)}", null, headers = deviceHeaders())

    suspend fun deleteAllRisiNotes(): ApiResult<Unit> = call<Unit, Unit>("DELETE", "risi/notes", null, headers = deviceHeaders())

    suspend fun contacts(): ApiResult<ContactsReply> = call<Unit, ContactsReply>("GET", "contacts", null)

    suspend fun logout(): ApiResult<Unit> = call<Unit, Unit>("POST", "auth/logout", null)

    private suspend inline fun <reified B, reified R> call(
        method: String,
        path: String,
        body: B?,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): ApiResult<R> = execute(method, path, body?.let { encode(serializer<B>(), it) }, auth, serializer<R>(), headers)

    private fun <B> encode(s: KSerializer<B>, body: B): RequestBody =
        ProtocolJson.encodeToString(s, body).toRequestBody(JSON)

    private suspend fun <R> execute(
        method: String,
        path: String,
        body: RequestBody?,
        auth: Boolean,
        resSerializer: KSerializer<R>,
        headers: Map<String, String> = emptyMap(),
    ): ApiResult<R> {
        val first = executeOnce(method, path, body, auth, resSerializer, headers)
        if (auth && first is ApiResult.Error && first.httpStatus == 401 && onUnauthorized()) {
            return executeOnce(method, path, body, auth, resSerializer, headers)
        }
        return first
    }

    private suspend fun <R> executeOnce(
        method: String,
        path: String,
        body: RequestBody?,
        auth: Boolean,
        resSerializer: KSerializer<R>,
        headers: Map<String, String>,
    ): ApiResult<R> {
        val url = serverUrl().trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("api/v1/${path.substringBefore('?')}")
            .apply { if ('?' in path) encodedQuery(path.substringAfter('?')) }
            .build()
        val builder = Request.Builder().url(url)
            .method(method, body ?: if (method == "GET") null else ByteArray(0).toRequestBody(JSON))
        if (auth) token()?.let { builder.header("Authorization", "Bearer $it") }
        // Every authenticated request names this install's device (one place, so no endpoint can forget it).
        if (auth) deviceId()?.let { builder.header(DEVICE_HEADER, it) }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(builder.build()).execute().use { res ->
                    if (res.isSuccessful && resSerializer === BYTES) {
                        @Suppress("UNCHECKED_CAST")
                        ApiResult.Ok(res.body.bytes() as R)
                    } else if (res.isSuccessful) {
                        val text = res.body.string()
                        @Suppress("UNCHECKED_CAST")
                        val value = if (res.code == 204 || text.isBlank()) Unit as R else ProtocolJson.decodeFromString(resSerializer, text)
                        ApiResult.Ok(value)
                    } else {
                        val text = res.body.string()
                        val err = runCatching { ProtocolJson.decodeFromString<ApiErrorEnvelope>(text).error }.getOrNull()
                        ApiResult.Error(
                            res.code, err?.code ?: "http_${res.code}", err?.message ?: "",
                            retryAfterSec = parseRetryAfter(res.header("Retry-After"), System.currentTimeMillis()),
                            attemptsLeft = err?.attemptsLeft,
                            epoch = err?.epoch,
                            missing = err?.missing,
                            used = err?.used,
                            limit = err?.limit,
                            detail = err,
                        )
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()

        /** Marker serializer: the raw response body (blobs). */
        private val BYTES: KSerializer<ByteArray> = serializer<ByteArray>()

        /** §10.2: the calling device on MLS REST calls. */
        const val DEVICE_HEADER = "X-Device-Id"
    }
}

/** `Retry-After`: delta-seconds or an HTTP-date (RFC 9110); null if absent or unparseable. */
fun parseRetryAfter(value: String?, nowMs: Long): Long? {
    val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    v.toLongOrNull()?.let { return it.coerceAtLeast(0) }
    return runCatching {
        val at = java.time.ZonedDateTime.parse(v, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        ((at - nowMs + 999) / 1000).coerceAtLeast(0)
    }.getOrNull()
}

/** A byte range of a file as a request body (a §22.3 backup part); never buffered in memory. */
internal class FileRangeBody(
    private val file: java.io.File,
    private val offset: Long,
    private val length: Long,
    private val type: okhttp3.MediaType,
) : RequestBody() {
    override fun contentType() = type

    override fun contentLength() = length

    override fun writeTo(sink: okio.BufferedSink) {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("backup file shorter than expected")
                sink.write(buf, 0, n)
                left -= n
            }
        }
    }
}

/** A file body that reports the bytes written (the upload's determinate progress); Content-Length stays the file's size. */
internal class ProgressFileBody(
    private val file: java.io.File,
    private val type: okhttp3.MediaType,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType() = type

    override fun contentLength() = file.length()

    override fun writeTo(sink: okio.BufferedSink) {
        val total = contentLength()
        var sent = 0L
        onProgress(0, total)
        file.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                sent += n
                onProgress(sent, total)
            }
        }
    }
}
