package lk.codegen.risime.data.mls

import lk.codegen.risime.net.ApiClient
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.AuthErrors
import lk.codegen.risime.net.DeviceMls
import lk.codegen.risime.net.DevicePut
import lk.codegen.risime.net.KeyPackagesUpload
import java.util.Base64

sealed interface Registration {
    /** Push-only (v1.5) or nothing to register. */
    data object PushOnly : Registration

    data object Skipped : Registration

    /** MLS-capable: attested, key packages topped up to [keyPackages]. */
    data class Mls(val keyPackages: Int) : Registration

    /** The server has E2EE off (503 mls_unavailable): behave exactly as before. */
    data object MlsUnavailable : Registration

    data class Failed(val code: String) : Registration
}

/**
 * `PUT /me/devices/{id}` with or without MLS (contract §8.1, §10.1): push_token may be null when
 * the MLS key is present (an app without Firebase still joins the census as MLS-capable).
 */
class DeviceRegistrar(
    private val api: ApiClient,
    private val deviceId: suspend () -> String,
    private val appVersion: String,
    private val engine: () -> MlsEngine?,
    private val topUpTo: Int = 50,
    /** §14.1: advertise `images` once this app can receive and render images. */
    private val imagesSupported: () -> Boolean = { false },
    /** §16.1: advertise `calls` once this app can ring, answer and play a call (WebRTC loaded, Telecom registered, notifications allowed). */
    private val callsSupported: () -> Boolean = { false },
    private val lowWater: Int = 20,
    /** §12.1: the signature key (b64) whose key packages were already replaced with 0xFA01 ones. */
    private val groupsReplacedFor: suspend () -> String? = { null },
    private val setGroupsReplacedFor: suspend (String) -> Unit = {},
    /** §17.1: advertise `history_share` only when the app can both provide and receive bundles. */
    private val historySupported: () -> Boolean = { false },
    /** §19.1: advertise `video` only together with `calls` and when the VP8 encoder and decoder load. */
    private val videoSupported: () -> Boolean = { false },
    /** §20.1: advertise `group_calls` only with `groups`, `calls` and `video`, LiveKit loaded and call keys in the core. */
    private val groupCallsSupported: () -> Boolean = { false },
    /** §23.1: advertise `call_switch` with `calls` and `video` (this app switches voice↔video mid-call). */
    private val callSwitchSupported: () -> Boolean = { false },
    /** §23.1: advertise `screen_share` with `call_switch` once the screen can be captured. */
    private val screenShareSupported: () -> Boolean = { false },
    /** The server accepted a `PUT` carrying this push token (null: none was sent). Health screen. */
    private val onPushTokenRegistered: (String?) -> Unit = {},
    /** §24.7: `/auth/config` says `tabs: on` (the app shows both tabs, creates and toggles Official, parses `chat_event`). */
    private val tabsSupported: () -> Boolean = { false },
    /** §25.8: `/auth/config` says `risi_tools: on` and the app handles tool calls, progress, the Risi chat and the v1.25 kinds. */
    private val risiToolsSupported: () -> Boolean = { false },
    /** §26.9: `/auth/config` says `risi_skills: on` (advertised only together with `risi_tools`). */
    private val risiSkillsSupported: () -> Boolean = { false },
    /** §27.10: `/auth/config` says `risi_ledger: on` (advertised only together with `risi_tools`). */
    private val risiLedgerSupported: () -> Boolean = { false },
    /** §29.1: `/auth/config` says `risi_events: on` (advertised only with `risi_tools`, `risi_skills` and `risi_ledger`). */
    private val risiEventsSupported: () -> Boolean = { false },
    /** §30.1: `/auth/config` says `risi_notes: on` (advertised only together with `risi_events`). */
    private val risiNotesSupported: () -> Boolean = { false },
    /** §31.1: `/auth/config` says `google_calendar: on` (advertised only together with `risi_events`). */
    private val googleCalendarSupported: () -> Boolean = { false },
    /** v1.34 §33.1: this build can receive, validate and open `file` (and has the media core). */
    private val filesSupported: () -> Boolean = { false },
    /** v1.34 §33.1: §33.14 and the Risi parts of §33.15 (advertised only with `risi_tools`). */
    private val pdfExportSupported: () -> Boolean = { false },
    /** v1.35 §34 Phase 2: `confirm_update`, the native `clarify` card and Risi's items (advertised only with `risi_tools`). */
    private val risiItemsSupported: () -> Boolean = { false },
    /** The capabilities a successful MLS `PUT` advertised. */
    private val onAdvertised: (List<String>) -> Unit = {},
) {
    private val b64 = Base64.getEncoder()

    /** The capabilities the last successful MLS `PUT` advertised (null before the first one in this process). */
    @Volatile var advertised: List<String>? = null
        private set

    /**
     * §12.1/§14.1/§15.1/§16.1: what this device can advertise right now. `calls` depends on runtime
     * state (notifications allowed, Telecom registered), so it can change after registration.
     */
    fun capabilities(mls: MlsEngine): List<String>? = when {
        mls.groupsSupported -> listOfNotNull(
            DeviceMls.CAP_GROUPS,
            DeviceMls.CAP_IMAGES.takeIf { imagesSupported() },
            DeviceMls.CAP_DELETES.takeIf { mls.deletesSupported },
            DeviceMls.CAP_CALLS.takeIf { mls.deletesSupported && imagesSupported() && callsSupported() },
            DeviceMls.CAP_VIDEO.takeIf { mls.deletesSupported && imagesSupported() && callsSupported() && videoSupported() },
            DeviceMls.CAP_GROUP_CALLS.takeIf {
                mls.deletesSupported && imagesSupported() && callsSupported() && videoSupported() && mls.callKeysSupported && groupCallsSupported()
            },
            DeviceMls.CAP_CALL_SWITCH.takeIf { mls.deletesSupported && imagesSupported() && callsSupported() && videoSupported() && callSwitchSupported() },
            DeviceMls.CAP_SCREEN_SHARE.takeIf { mls.deletesSupported && imagesSupported() && callsSupported() && videoSupported() && callSwitchSupported() && screenShareSupported() },
            // v1.14 §12.4a: only when the bundled core enforces the member rule (never on the app's own say).
            DeviceMls.CAP_MEMBER_DEVICES.takeIf { DeviceMls.CAP_MEMBER_DEVICES in mls.coreCapabilities },
            // v1.15 §17.1: the core's §17.3 functions present and the feature on.
            DeviceMls.CAP_HISTORY_SHARE.takeIf { mls.historySupported && historySupported() },
            // v1.24 §24.7: only while the server switch is on and the bundled core enforces §24.1.
            DeviceMls.CAP_TABS.takeIf { mls.tabsSupported && tabsSupported() },
            // v1.25 §25.8: only together with `tabs`, while the server switch is on and the core does Risi chats.
            DeviceMls.CAP_RISI_TOOLS.takeIf { mls.tabsSupported && tabsSupported() && mls.risiChatSupported && risiToolsSupported() },
            // v1.26 §26.9: only with `risi_tools`, while the server switch is on.
            DeviceMls.CAP_RISI_SKILLS.takeIf { mls.tabsSupported && tabsSupported() && mls.risiChatSupported && risiToolsSupported() && risiSkillsSupported() },
            // v1.27 §27.10: only with `risi_tools`, while the server switch is on.
            DeviceMls.CAP_RISI_LEDGER.takeIf { mls.tabsSupported && tabsSupported() && mls.risiChatSupported && risiToolsSupported() && risiLedgerSupported() },
            // v1.29 §29.1: only with `risi_tools`, `risi_skills` and `risi_ledger`, while the server switch is on.
            DeviceMls.CAP_RISI_EVENTS.takeIf {
                mls.tabsSupported && tabsSupported() && mls.risiChatSupported && risiToolsSupported() && risiSkillsSupported() && risiLedgerSupported() && risiEventsSupported()
            },
            // v1.29 §30.1: only with `risi_events` (so with tools, skills and ledger), while the server switch is on.
            DeviceMls.CAP_RISI_NOTES.takeIf {
                mls.tabsSupported && tabsSupported() && mls.risiChatSupported && risiToolsSupported() && risiSkillsSupported() && risiLedgerSupported() &&
                    risiEventsSupported() && risiNotesSupported()
            },
            // v1.31 §31.1: only with `risi_events`, while the server switch is on (with or without Play services).
            DeviceMls.CAP_GOOGLE_CALENDAR.takeIf {
                mls.tabsSupported && tabsSupported() && mls.risiChatSupported && risiToolsSupported() && risiSkillsSupported() && risiLedgerSupported() &&
                    risiEventsSupported() && googleCalendarSupported()
            },
            // v1.34 §33.1: `files` once the app can receive, validate and open a `file` (the media core is there).
            DeviceMls.CAP_FILES.takeIf { imagesSupported() && filesSupported() },
            // v1.34 §33.1: `pdf_export` only with `risi_tools` (the server drops it otherwise).
            DeviceMls.CAP_PDF_EXPORT.takeIf { mls.tabsSupported && tabsSupported() && mls.risiChatSupported && risiToolsSupported() && imagesSupported() && filesSupported() && pdfExportSupported() },
            // v1.35 §34 Phase 2: `risi_items` only with `risi_tools` (the cards it unlocks live in the Risi chat).
            DeviceMls.CAP_RISI_ITEMS.takeIf { mls.tabsSupported && tabsSupported() && mls.risiChatSupported && risiToolsSupported() && risiItemsSupported() },
        )
        else -> null
    }

    /**
     * Re-registers when the capabilities changed since the last advertisement (e.g. the user allowed
     * notifications after the sign-in registration, so `calls` was left out: nightly.16 finding).
     * Null when nothing changed or nothing was registered yet in this process.
     */
    suspend fun refreshCapabilities(pushToken: String?): Registration? {
        val mls = engine() ?: return null
        val before = advertised ?: return null
        if (capabilities(mls).orEmpty().toSet() == before.toSet()) return null
        return register(pushToken)
    }

    suspend fun register(pushToken: String?): Registration {
        val id = deviceId()
        val mls = engine()
        if (mls == null) {
            if (pushToken.isNullOrBlank()) return Registration.Skipped
            return when (val r = api.putDevice(id, DevicePut(DevicePut.PLATFORM_ANDROID, pushToken, appVersion))) {
                is ApiResult.Ok -> Registration.PushOnly.also { onPushTokenRegistered(pushToken) }
                is ApiResult.Error -> Registration.Failed(r.code)
                is ApiResult.NetworkError -> Registration.Failed("network")
            }
        }
        val sigKey = b64.encodeToString(mls.signatureKey())
        // §12.1: advertise `groups` only with a core that really does groups (0xFA01 key packages).
        // §15.1: `deletes` once the core answers AAD + sender_is_admin and the app applies delete events.
        val caps = capabilities(mls)
        val body = DevicePut(DevicePut.PLATFORM_ANDROID, pushToken?.takeIf { it.isNotBlank() }, appVersion, DeviceMls(sigKey, caps))
        return when (val r = api.putMlsDevice(id, body)) {
            is ApiResult.Ok -> {
                mls.setAttestation(r.value.attestation)
                advertised = caps.orEmpty()
                onAdvertised(caps.orEmpty())
                onPushTokenRegistered(pushToken?.takeIf { it.isNotBlank() })
                if (caps != null && groupsReplacedFor() != sigKey) replaceForGroups(id, mls, sigKey) else topUp(id, mls)
            }
            is ApiResult.Error -> when {
                r.code == AuthErrors.MLS_UNAVAILABLE -> {
                    // E2EE is off on the server: register for push only, as a v1.6 app would.
                    if (!pushToken.isNullOrBlank() && api.putDevice(id, DevicePut(DevicePut.PLATFORM_ANDROID, pushToken, appVersion)) is ApiResult.Ok) {
                        onPushTokenRegistered(pushToken)
                    }
                    Registration.MlsUnavailable
                }
                else -> Registration.Failed(r.code)
            }
            is ApiResult.NetworkError -> Registration.Failed("network")
        }
    }

    /**
     * The first time this device advertises `groups`: replace every stored normal key package with
     * fresh 0xFA01 ones and regenerate the last-resort package (§12.1, crypto README).
     */
    private suspend fun replaceForGroups(id: String, mls: MlsEngine, sigKey: String): Registration {
        val pkgs = mls.createKeyPackages(topUpTo).map(b64::encodeToString)
        val last = b64.encodeToString(mls.lastResortKeyPackage())
        return when (val r = api.uploadKeyPackages(id, KeyPackagesUpload(pkgs, last, replace = true))) {
            is ApiResult.Ok -> {
                setGroupsReplacedFor(sigKey)
                Registration.Mls(topUpTo)
            }
            is ApiResult.Error -> Registration.Failed(r.code)
            is ApiResult.NetworkError -> Registration.Failed("network")
        }
    }

    /** Top up to [topUpTo] when below [lowWater] (on sign-in, join, after a Welcome, on the low signal). */
    suspend fun topUp(id: String? = null, mls: MlsEngine? = engine()): Registration {
        mls ?: return Registration.Skipped
        val dev = id ?: deviceId()
        val count = (api.keyPackageCount(dev) as? ApiResult.Ok)?.value?.count ?: return Registration.Failed("count")
        if (count >= lowWater) return Registration.Mls(count)
        val n = (topUpTo - count).coerceIn(0, 100)
        // The core stores each package's private part before we upload it (crash-safe).
        val pkgs = mls.createKeyPackages(n).map(b64::encodeToString)
        val last = b64.encodeToString(mls.lastResortKeyPackage())
        return when (val r = api.uploadKeyPackages(dev, KeyPackagesUpload(pkgs, last))) {
            is ApiResult.Ok -> Registration.Mls(count + n)
            is ApiResult.Error -> Registration.Failed(r.code)
            is ApiResult.NetworkError -> Registration.Failed("network")
        }
    }
}
