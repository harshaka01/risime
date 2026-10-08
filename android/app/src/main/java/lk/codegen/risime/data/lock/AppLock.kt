package lk.codegen.risime.data.lock

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.biometric.BiometricManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** "Automatically lock" (decision 064): time since RisiMe went to the background. */
enum class AutoLock(val ms: Long, val label: String) {
    IMMEDIATELY(0L, "Immediately"),
    ONE_MINUTE(60_000L, "After 1 minute"),
    THIRTY_MINUTES(30 * 60_000L, "After 30 minutes"),
    ;

    companion object {
        fun ofMs(ms: Long?): AutoLock = entries.firstOrNull { it.ms == ms } ?: IMMEDIATELY
    }
}

/** Settings → Privacy → Fingerprint lock. Off by default; kept across updates (DataStore). */
data class AppLockSettings(
    val enabled: Boolean = false,
    val autoLock: AutoLock = AutoLock.IMMEDIATELY,
    val showContent: Boolean = true,
)

/**
 * The lock decisions, pure. A UI gate only (decision 064): it never touches tokens, the socket,
 * push, sync or calls, never signs out, never wipes and never needs the network.
 */
object AppLockPolicy {
    /**
     * A new process: locked when the lock is on, unless RisiMe went to the background less than the
     * auto-lock time ago ([backgroundAt], elapsedRealtime; a value in the future means the phone
     * rebooted since). No stamp (crash, first start) → locked.
     */
    fun lockedAtStart(s: AppLockSettings, backgroundAt: Long?, now: Long): Boolean =
        s.enabled && (backgroundAt == null || elapsedEnough(s, backgroundAt, now))

    /** Back to the foreground in the same process: locked when it was away at least the auto-lock time. */
    fun lockedOnReturn(s: AppLockSettings, backgroundAt: Long?, now: Long): Boolean =
        s.enabled && backgroundAt != null && elapsedEnough(s, backgroundAt, now)

    private fun elapsedEnough(s: AppLockSettings, backgroundAt: Long, now: Long): Boolean =
        backgroundAt > now || now - backgroundAt >= s.autoLock.ms

    /** "Show content in notifications" off with the lock on: "New message" with no name or text. */
    fun hideNotificationContent(s: AppLockSettings?): Boolean = s != null && s.enabled && !s.showContent
}

/** `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`, reduced to what the lock decides on. */
enum class BiometricStatus {
    /** BIOMETRIC_SUCCESS: a strong biometric is enrolled and usable now. */
    AVAILABLE,

    /** BIOMETRIC_ERROR_NONE_ENROLLED / BIOMETRIC_ERROR_NO_HARDWARE: the only reasons the lock turns itself off. */
    GONE,

    /** Anything else (HW_UNAVAILABLE, SECURITY_UPDATE_REQUIRED, UNSUPPORTED, a thrown error): the lock stays on; try again. */
    UNAVAILABLE_NOW,
    ;

    companion object {
        fun of(canAuthenticate: Int): BiometricStatus = when (canAuthenticate) {
            BiometricManager.BIOMETRIC_SUCCESS -> AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> GONE
            else -> UNAVAILABLE_NOW
        }
    }
}

interface AppLockStore {
    suspend fun load(): AppLockSettings

    suspend fun save(s: AppLockSettings)
}

/** The background stamp (elapsedRealtime), persisted so a later process (push-started) knows it. */
interface LockStamp {
    fun get(): Long?

    fun set(v: Long?)
}

class DataStoreAppLockStore(private val prefs: DataStore<Preferences>) : AppLockStore {
    override suspend fun load(): AppLockSettings = prefs.data.first().let {
        AppLockSettings(
            enabled = it[ENABLED] ?: false,
            autoLock = AutoLock.ofMs(it[AFTER_MS]),
            showContent = it[SHOW_CONTENT] ?: true,
        )
    }

    override suspend fun save(s: AppLockSettings) {
        prefs.edit {
            it[ENABLED] = s.enabled
            it[AFTER_MS] = s.autoLock.ms
            it[SHOW_CONTENT] = s.showContent
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("app_lock_enabled")
        val AFTER_MS = longPreferencesKey("app_lock_after_ms")
        val SHOW_CONTENT = booleanPreferencesKey("app_lock_show_content")
    }
}

/** The app lock's runtime state; one per process ([lk.codegen.risime.AppContainer]). */
class AppLock(
    private val store: AppLockStore,
    private val stamp: LockStamp,
    /** `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` as a [BiometricStatus]. */
    private val biometric: () -> BiometricStatus,
    private val elapsed: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    private val _settings = MutableStateFlow<AppLockSettings?>(null)

    /** Null until loaded. */
    val settings: StateFlow<AppLockSettings?> = _settings.asStateFlow()

    private val _locked = MutableStateFlow<Boolean?>(null)

    /** Null until loaded (the UI waits, so no content flashes before the lock). */
    val locked: StateFlow<Boolean?> = _locked.asStateFlow()

    suspend fun load() = mutex.withLock {
        if (_settings.value != null) return@withLock
        // An unreadable settings file never keeps the user out (the lock is a convenience gate).
        val read = try {
            store.load()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // a timed-out read is "not loaded", never "off"
        } catch (e: Exception) {
            log("RisiMe lock: settings unreadable: ${e.message}")
            AppLockSettings()
        }
        val s = offIfNoBiometrics(read)
        _settings.value = s
        _locked.value = AppLockPolicy.lockedAtStart(s, stamp.get(), elapsed())
    }

    /** ProcessLifecycle onStop: remember when RisiMe left the screen ("Immediately" locks right away: no content on return). */
    fun onBackground() {
        stamp.set(elapsed())
        val s = _settings.value
        if (s != null && s.enabled && s.autoLock == AutoLock.IMMEDIATELY) _locked.value = true
    }

    /** ProcessLifecycle onStart, synchronously (before the first frame): lock if it was away long enough. */
    fun checkOnReturn() {
        val s = _settings.value ?: return
        if (_locked.value != true && AppLockPolicy.lockedOnReturn(s, stamp.get(), elapsed())) _locked.value = true
    }

    /** ProcessLifecycle onStart: lock if it was away long enough; turn the lock off if the fingerprint is gone. */
    suspend fun onForeground() {
        checkOnReturn()
        load()
        mutex.withLock {
            val s = offIfNoBiometrics(_settings.value ?: return@withLock)
            _settings.value = s
            if (_locked.value != true && AppLockPolicy.lockedOnReturn(s, stamp.get(), elapsed())) _locked.value = true
            if (_locked.value != true) stamp.set(null)
        }
    }

    /** The fingerprint matched on the lock screen. */
    fun unlocked() {
        _locked.value = false
        stamp.set(null)
    }

    /** Turned on after one fingerprint confirmation (the caller ran the prompt); off at once. */
    suspend fun setEnabled(on: Boolean) = update { it.copy(enabled = on && biometric() == BiometricStatus.AVAILABLE) }.also {
        if (!on) unlocked()
    }

    suspend fun setAutoLock(a: AutoLock) = update { it.copy(autoLock = a) }

    suspend fun setShowContent(show: Boolean) = update { it.copy(showContent = show) }

    /**
     * Whether a notification posted now must hide the name and text. A process started by a push
     * may post before the async [load] finished: the settings are read first (bounded wait), and
     * when they still can't be read the content is hidden (never shown against the user's choice).
     */
    suspend fun hideNotificationContent(): Boolean {
        if (_settings.value == null) {
            withTimeoutOrNull(SETTINGS_WAIT_MS) { runCatching { load() } }
        }
        val s = _settings.value ?: return true
        return AppLockPolicy.hideNotificationContent(s)
    }

    /** For non-suspend callers (the Notifier): [hideNotificationContent], blocking only until the settings are loaded once. */
    fun hideNotificationContentBlocking(): Boolean =
        _settings.value?.let(AppLockPolicy::hideNotificationContent) ?: runBlocking { hideNotificationContent() }

    private suspend fun update(f: (AppLockSettings) -> AppLockSettings) {
        load()
        mutex.withLock {
            val s = f(_settings.value ?: AppLockSettings())
            store.save(s)
            _settings.value = s
        }
    }

    /** Only a removed fingerprint (none enrolled) or no sensor at all turns the lock off; a sensor busy now keeps it on. */
    private suspend fun offIfNoBiometrics(s: AppLockSettings): AppLockSettings {
        if (!s.enabled) return s
        when (val b = biometric()) {
            BiometricStatus.AVAILABLE -> return s
            BiometricStatus.UNAVAILABLE_NOW -> {
                log("RisiMe lock: fingerprint unavailable now: the lock stays on")
                return s
            }
            BiometricStatus.GONE -> log("RisiMe lock: fingerprint no longer available ($b): the app lock is turned off")
        }
        val off = s.copy(enabled = false)
        runCatching { store.save(off) }
        _locked.value = false
        return off
    }

    private companion object {
        const val SETTINGS_WAIT_MS = 3_000L
    }
}
