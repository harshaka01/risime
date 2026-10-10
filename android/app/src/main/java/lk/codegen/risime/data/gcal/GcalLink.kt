package lk.codegen.risime.data.gcal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import lk.codegen.risime.data.db.GcalCalendarEntity
import lk.codegen.risime.data.db.GcalDao
import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.GcalErrors
import lk.codegen.risime.net.GcalLinkReasons
import lk.codegen.risime.net.GcalLinkStates
import lk.codegen.risime.net.GoogleCalendarLinkData
import lk.codegen.risime.net.GoogleLink
import lk.codegen.risime.net.GoogleLinkPut
import lk.codegen.risime.net.GoogleLinkReply

/*
 * v1.31 §31.2/§31.3/§31.8: the phone's side of the Google link. The server knows the state and two counts; this
 * class keeps the real picks (names, Google ids, refs) in Room and the token in the authorizer's memory.
 */

/** `GET`/`PUT`/`DELETE /risi/calendar/google` (the app's [ApiClient]; a fake in tests). */
interface GcalRest {
    suspend fun get(): ApiResult<GoogleLinkReply>
    suspend fun put(body: GoogleLinkPut): ApiResult<GoogleLinkReply>
    suspend fun delete(removeCopies: Boolean): ApiResult<Unit>
}

class ApiGcalRest(private val api: ApiClient) : GcalRest {
    override suspend fun get() = api.googleLink()
    override suspend fun put(body: GoogleLinkPut) = api.putGoogleLink(body)
    override suspend fun delete(removeCopies: Boolean) = api.deleteGoogleLink(removeCopies)
}

/** What the picker sheet returns (§31.2 step 3). */
data class GcalSelection(
    val calendars: List<GCalendar>,
    val readIds: Set<String>,
    val writeId: String?,
    val mirror: Boolean,
)

sealed interface GcalOutcome {
    data object Ok : GcalOutcome
    data class Failed(val code: String) : GcalOutcome
}

/** The sections of §31.2. */
sealed interface GcalSection {
    data object Hidden : GcalSection
    data object NoPlayServices : GcalSection
    data object NotConnected : GcalSection
    data class Connected(val read: Int, val writeName: String?, val account: String?) : GcalSection
    data class OnOther(val deviceName: String?) : GcalSection
    data object NeedsReconnecting : GcalSection
    data object Paused : GcalSection
}

class GcalLinkManager(
    private val rest: GcalRest,
    private val authorizer: GcalAuthorizer,
    private val api: GcalApi,
    private val dao: GcalDao,
    private val copies: GcalCopies,
    private val myDevice: suspend () -> String?,
    /** The phone's own record of the Calendar skill: off pauses the link. */
    private val skillOff: () -> Boolean,
    /** §31.2 step 5: turn the Calendar skill on (`ask`, `client_permission: granted`) when it is off. */
    private val enableSkill: suspend () -> Boolean,
    /** Runs the `gcal-copy` job (`full`: the reconcile too). */
    private val scheduleCopy: (full: Boolean) -> Unit,
    private val newRef: () -> String = ::randomRef,
    private val log: (String) -> Unit = {},
) {
    companion object {
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val rnd = java.security.SecureRandom()

        /** §31.2 `g` + 7 random characters `[a-z0-9]`. */
        fun randomRef(): String = "g" + (1..7).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")

        const val MAX_READ = 10

        /** §31.2 step 3 defaults: the primary calendar plus those Google shows (selected, not hidden), at most 10. */
        fun defaultRead(all: List<GCalendar>): Set<String> =
            all.filter { it.primary || (it.selected && !it.hidden) }.sortedByDescending { it.primary }.take(MAX_READ).map { it.id }.toSet()

        /** The primary calendar when it is writable, else the first writable one. */
        fun defaultWrite(all: List<GCalendar>): String? = (all.firstOrNull { it.primary && it.canWrite } ?: all.firstOrNull { it.canWrite })?.id
    }

    private val _link = MutableStateFlow(GoogleLink.NONE)

    /** The server's view of the link (last `GET`/`PUT`/event). */
    val link: StateFlow<GoogleLink> = _link.asStateFlow()

    /** This phone found its grant unusable (a `401` after a silent retry, or a consent is needed). Reset by a reconnect. */
    private val _localReauth = MutableStateFlow(false)
    val localReauth: StateFlow<Boolean> = _localReauth.asStateFlow()

    fun isMine(l: GoogleLink, me: String?): Boolean = l.linked && me != null && l.deviceId.equals(me, true)

    /** `GET /risi/calendar/google`. A 503 means the switch is off on the server. */
    suspend fun refresh(reconcile: Boolean = true): GcalOutcome = when (val r = rest.get()) {
        is ApiResult.Ok -> { _link.value = r.value.google; if (reconcile) reconcileLocal(r.value.google); GcalOutcome.Ok }
        is ApiResult.Error -> GcalOutcome.Failed(r.code)
        is ApiResult.NetworkError -> GcalOutcome.Failed("network")
    }

    /**
     * The server says there is no link, or another device holds it, while this phone still has picks (it was
     * offline when that happened): the local data goes. A disconnected link is also revoked (the server has
     * stopped using this device); a link moved to another device is not (that would end the new grant).
     */
    private suspend fun reconcileLocal(l: GoogleLink) {
        if (dao.calendars().isEmpty()) return
        val me = myDevice()
        if (isMine(l, me)) return
        if (!l.linked) revoke()
        dao.clearAll()
        log("gcal: local data removed (the link is ${if (l.linked) "on another device" else "gone"})")
    }

    private suspend fun revoke(): Boolean {
        val account = dao.calendars().firstNotNullOfOrNull { it.account } ?: authorizer.accountName()
        return runCatching { authorizer.revoke(account, null) }.getOrDefault(false)
    }

    // ---- the section ----

    fun section(l: GoogleLink, me: String?, localReauth: Boolean, skillIsOff: Boolean, hasLocal: Boolean, switchOn: Boolean, writeName: String?, readCount: Int, account: String?): GcalSection {
        if (!switchOn) return GcalSection.Hidden
        if (!authorizer.available() && !hasLocal && !l.linked) return GcalSection.NoPlayServices
        return when {
            !l.linked -> GcalSection.NotConnected
            !isMine(l, me) -> GcalSection.OnOther(l.deviceName)
            l.state == GcalLinkStates.REAUTH_NEEDED || localReauth -> GcalSection.NeedsReconnecting
            l.state == GcalLinkStates.PAUSED || skillIsOff -> GcalSection.Paused
            else -> GcalSection.Connected(readCount, writeName, account)
        }
    }

    // ---- connect (the consent already happened on the Settings screen) ----

    suspend fun listCalendars(): GcalResult<List<GCalendar>> = api.calendarList()

    private fun rows(sel: GcalSelection, account: String?, refs: Map<String, String>): List<GcalCalendarEntity> =
        sel.calendars.filter { it.id in sel.readIds || it.id == sel.writeId }.map {
            GcalCalendarEntity(it.id, refs[it.id] ?: newRef(), it.name, it.accessRole, read = it.id in sel.readIds, write = it.id == sel.writeId, account = account)
        }

    private fun put(connect: Boolean, state: String, read: Int, write: Boolean, mirror: Boolean) = GoogleLinkPut(connect, state, read, write, mirror)

    /** §31.2 steps 4-6: store the picks (new refs), `PUT connect: true`, turn the skill on if it is off, start the first copy run. */
    suspend fun connect(sel: GcalSelection, account: String?): GcalOutcome {
        if (sel.readIds.size > MAX_READ) return GcalOutcome.Failed("too_many")
        val before = dao.calendars()
        dao.clearCalendars()
        dao.upsertCalendars(rows(sel, account, emptyMap()))
        val r = rest.put(put(true, GcalLinkStates.CONNECTED, sel.readIds.size, sel.writeId != null, sel.mirror && sel.writeId != null))
        return when (r) {
            is ApiResult.Ok -> {
                _link.value = r.value.google
                _localReauth.value = false
                if (skillOff()) runCatching { enableSkill() }
                scheduleCopy(true)
                log("gcal: connected (${sel.readIds.size} to read, write ${sel.writeId != null})")
                GcalOutcome.Ok
            }
            is ApiResult.Error -> { restore(before); GcalOutcome.Failed(r.code) }
            is ApiResult.NetworkError -> { restore(before); GcalOutcome.Failed("network") }
        }
    }

    private suspend fun restore(before: List<GcalCalendarEntity>) {
        dao.clearCalendars()
        if (before.isNotEmpty()) dao.upsertCalendars(before)
    }

    /** [Change calendars]: refs of calendars that stay are kept; the write calendar may move (the copy job moves the copies). */
    suspend fun change(sel: GcalSelection, account: String?): GcalOutcome {
        if (sel.readIds.size > MAX_READ) return GcalOutcome.Failed("too_many")
        val old = dao.calendars()
        val keep = old.associate { it.calendarId to it.ref }
        dao.clearCalendars()
        dao.upsertCalendars(rows(sel, account ?: old.firstNotNullOfOrNull { it.account }, keep))
        val st = if (_link.value.state == GcalLinkStates.REAUTH_NEEDED) GcalLinkStates.REAUTH_NEEDED else GcalLinkStates.CONNECTED
        return when (val r = rest.put(put(false, st, sel.readIds.size, sel.writeId != null, sel.mirror && sel.writeId != null))) {
            is ApiResult.Ok -> { _link.value = r.value.google; scheduleCopy(true); GcalOutcome.Ok }
            is ApiResult.Error -> { restore(old); GcalOutcome.Failed(r.code) }
            is ApiResult.NetworkError -> { restore(old); GcalOutcome.Failed("network") }
        }
    }

    /** Reconnect (§31.8): the consent is done; the picks are kept. Steps 1 and 4 only. */
    suspend fun reconnected(): GcalOutcome {
        val cals = dao.calendars()
        val l = _link.value
        val body = put(false, GcalLinkStates.CONNECTED, cals.count { it.read }, cals.any { it.write }, l.mirror)
        var r = rest.put(body)
        if (r is ApiResult.Error && r.code == GcalErrors.NOT_GOOGLE_DEVICE) r = rest.put(body.copy(connect = true))
        return when (r) {
            is ApiResult.Ok -> {
                _link.value = r.value.google
                _localReauth.value = false
                scheduleCopy(true)
                GcalOutcome.Ok
            }
            is ApiResult.Error -> GcalOutcome.Failed(r.code)
            is ApiResult.NetworkError -> GcalOutcome.Failed("network")
        }
    }

    /** §31.8: a read or a copy run found the grant unusable: stop, and tell the server (it posts one reconnect card per 24 h). */
    suspend fun markReauth() {
        _localReauth.value = true
        authorizer.invalidate()
        val cals = dao.calendars()
        if (cals.isEmpty()) return
        val l = _link.value
        val r = rest.put(put(false, GcalLinkStates.REAUTH_NEEDED, cals.count { it.read }, cals.any { it.write }, l.mirror))
        if (r is ApiResult.Ok) _link.value = r.value.google
        log("gcal: needs reconnecting")
    }

    /** The mirror switch of the picker / section. */
    suspend fun setMirror(on: Boolean): GcalOutcome {
        val cals = dao.calendars()
        val l = _link.value
        val r = rest.put(put(false, if (l.state == GcalLinkStates.REAUTH_NEEDED) GcalLinkStates.REAUTH_NEEDED else GcalLinkStates.CONNECTED, cals.count { it.read }, cals.any { it.write }, on && cals.any { it.write }))
        return when (r) {
            is ApiResult.Ok -> { _link.value = r.value.google; if (on) scheduleCopy(false); GcalOutcome.Ok }
            is ApiResult.Error -> GcalOutcome.Failed(r.code)
            is ApiResult.NetworkError -> GcalOutcome.Failed("network")
        }
    }

    // ---- disconnect / replace (§31.8) ----

    /**
     * Disconnect from any device. On the Google device: remove the copies if chosen, revoke, delete `gcal_*`,
     * then `DELETE`. From another device: the `DELETE` only (the Google device does the rest when it hears).
     */
    suspend fun disconnect(removeCopies: Boolean): GcalOutcome {
        val me = myDevice()
        val mine = dao.calendars().isNotEmpty() && isMine(_link.value, me)
        if (mine) localTeardown(removeCopies, revoke = true)
        return when (val r = rest.delete(removeCopies)) {
            is ApiResult.Ok -> { _link.value = GoogleLink.NONE; GcalOutcome.Ok }
            is ApiResult.Error -> if (r.httpStatus == 404 || r.httpStatus == 204) GcalOutcome.Ok else GcalOutcome.Failed(r.code)
            is ApiResult.NetworkError -> GcalOutcome.Failed("network")
        }
    }

    /** §31.8 steps 1-3. */
    private suspend fun localTeardown(removeCopies: Boolean, revoke: Boolean) {
        if (removeCopies) runCatching { copies.removeAll(dao.calendars().filter { it.write }.map { it.calendarId }) }
        if (revoke) revoke()
        authorizer.invalidate()
        dao.clearAll()
        _localReauth.value = false
    }

    /** The user's confirmed logout or account switch: local data is deleted and a revoke is attempted. */
    suspend fun onLogout() {
        if (dao.calendars().isNotEmpty()) runCatching { revoke() }
        authorizer.invalidate()
        dao.clearAll()
        _link.value = GoogleLink.NONE
    }

    /** `google_calendar_link` (§31.3): refresh the section, and act when this was the Google device. */
    suspend fun onLinkEvent(d: GoogleCalendarLinkData) {
        val hadLocal = dao.calendars().isNotEmpty()
        val me = myDevice()
        // `disconnected`: device_id is the device that HELD the link; it is not for another device's local data.
        if (d.reason == GcalLinkReasons.DISCONNECTED && d.deviceId != null && !d.deviceId.equals(me, true)) { refresh(reconcile = false); return }
        refresh(reconcile = false)
        if (!hadLocal) return
        val l = _link.value
        if (isMine(l, me)) return // still (or again) mine: nothing to undo
        when (d.reason) {
            GcalLinkReasons.REPLACED -> if (dao.calendars().isNotEmpty()) localTeardown(removeCopies = false, revoke = false) // never revoke: that would end the new device's grant
            GcalLinkReasons.DISCONNECTED -> if (dao.calendars().isNotEmpty()) localTeardown(d.removeCopies, revoke = true)
            else -> {}
        }
    }

    /** What the copy job may use (null: do nothing). */
    suspend fun copyConfig(switchOn: Boolean): GcalCopyConfig? {
        if (!switchOn || skillOff() || _localReauth.value) return null
        val l = _link.value
        if (l.state != GcalLinkStates.CONNECTED || !isMine(l, myDevice())) return null
        val w = dao.calendars().firstOrNull { it.write } ?: return null
        return GcalCopyConfig(w.calendarId, l.mirror)
    }
}
