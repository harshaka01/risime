package lk.codegen.risime.data.lock

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import lk.codegen.risime.data.auth.SessionVault
import lk.codegen.risime.data.auth.VaultKey
import lk.codegen.risime.net.ProtocolJson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * "Lock chat" (WhatsApp style): which conversations sit in the Locked chats folder, and the optional
 * secret code that hides the folder's pull-down entry. Per device, never sent to the server, never
 * touches a message. The protection is the UI gate (a fingerprint or the screen lock when the folder
 * is opened), so the file is sealed with a Keystore key that needs no user authentication.
 */
@Serializable
data class LockedChatsData(
    val ids: List<String> = emptyList(),
    /** PBKDF2 parameters + hash of the secret code (base64); never the code itself. */
    val codeSalt: String? = null,
    val codeHash: String? = null,
    val codeIterations: Int = 0,
) {
    val hasCode: Boolean get() = codeSalt != null && codeHash != null
}

/** Salted PBKDF2-HMAC-SHA256 for the secret code. */
object SecretCode {
    const val MIN_LENGTH = 4
    const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    fun valid(code: String): Boolean = code.length >= MIN_LENGTH

    fun hash(code: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, iterations, KEY_BITS)).encoded

    fun create(code: String, iterations: Int = ITERATIONS): Triple<String, String, Int> {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val b64 = Base64.getEncoder()
        return Triple(b64.encodeToString(salt), b64.encodeToString(hash(code, salt, iterations)), iterations)
    }

    fun matches(d: LockedChatsData, code: String): Boolean {
        if (!d.hasCode) return false
        val b64 = Base64.getDecoder()
        val got = hash(code, b64.decode(d.codeSalt), d.codeIterations)
        return MessageDigest.isEqual(got, b64.decode(d.codeHash))
    }
}

interface LockedChatsStore {
    sealed interface Load {
        data class Ok(val data: LockedChatsData) : Load

        /** Nothing stored yet. */
        data object Empty : Load

        /** Can never be opened again (key gone, corrupt): treated as empty, rewritten on the next change. */
        data class Unreadable(val reason: String) : Load

        /** Keystore or disk busy now: nothing is shown or posted from the unknown state; read again. */
        data class Transient(val reason: String) : Load
    }

    fun load(): Load

    /** True once durable. */
    fun save(d: LockedChatsData): Boolean

    fun wipe()
}

/** The sealed file in `noBackupFilesDir` (never in a cloud backup), AES-GCM under [key]. */
class FileLockedChatsStore(private val file: File, private val key: VaultKey) : LockedChatsStore {
    override fun load(): LockedChatsStore.Load {
        if (!file.isFile) return LockedChatsStore.Load.Empty
        val k = try {
            key.get()
        } catch (e: Exception) {
            return LockedChatsStore.Load.Transient("key: ${e.javaClass.simpleName}")
        } ?: return LockedChatsStore.Load.Unreadable("key missing")
        val bytes = try {
            file.readBytes()
        } catch (e: java.io.IOException) {
            return LockedChatsStore.Load.Transient("read: ${e.javaClass.simpleName}")
        }
        return try {
            val plain = SessionVault.open(bytes, k, key.alias)
            LockedChatsStore.Load.Ok(ProtocolJson.decodeFromString(LockedChatsData.serializer(), plain.decodeToString()))
        } catch (e: Exception) {
            when (SessionVault.classify(e)) {
                is SessionVault.Load.Unreadable -> LockedChatsStore.Load.Unreadable(e.javaClass.simpleName)
                else -> LockedChatsStore.Load.Transient(e.javaClass.simpleName)
            }
        }
    }

    override fun save(d: LockedChatsData): Boolean = try {
        val k = key.getOrCreate()
        if (k == null) {
            false
        } else {
            val blob = SessionVault.seal(ProtocolJson.encodeToString(LockedChatsData.serializer(), d).toByteArray(), k, key.alias)
            val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use {
                it.write(blob)
                it.flush()
                it.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        }
    } catch (e: Exception) {
        Log.w("RisiMe", "RisiMe lock: locked chats not saved: ${e.javaClass.simpleName}")
        false
    }

    override fun wipe() {
        file.delete()
        key.delete()
    }
}

/**
 * The runtime state, one per process. [ids] is null until read (the UI shows no chat list and
 * notifications are redacted meanwhile: never show a locked chat because the file was busy).
 * [folderOpen] is true only between a successful fingerprint/screen-lock confirmation and leaving
 * the folder or the app going to the background.
 */
class LockedChats(private val store: LockedChatsStore, private val log: (String) -> Unit = {}) {
    private val mutex = Mutex()
    private var data = LockedChatsData()

    private val _ids = MutableStateFlow<Set<String>?>(null)
    val ids: StateFlow<Set<String>?> = _ids.asStateFlow()

    private val _hasCode = MutableStateFlow(false)
    val hasCode: StateFlow<Boolean> = _hasCode.asStateFlow()

    private val _folderOpen = MutableStateFlow(false)
    val folderOpen: StateFlow<Boolean> = _folderOpen.asStateFlow()

    /** Reads the file once (retried by the caller while [ids] stays null). */
    suspend fun load(): Boolean = mutex.withLock {
        if (_ids.value != null) return@withLock true
        when (val r = store.load()) {
            is LockedChatsStore.Load.Ok -> apply(r.data)
            LockedChatsStore.Load.Empty -> apply(LockedChatsData())
            is LockedChatsStore.Load.Unreadable -> {
                log("RisiMe lock: locked-chats file unreadable (${r.reason}): starting empty")
                apply(LockedChatsData())
            }
            is LockedChatsStore.Load.Transient -> {
                log("RisiMe lock: locked-chats file not readable now (${r.reason})")
                return@withLock false
            }
        }
        true
    }

    private fun apply(d: LockedChatsData) {
        data = d
        _hasCode.value = d.hasCode
        _ids.value = d.ids.map { it.lowercase() }.toSet()
    }

    /**
     * For non-suspend callers (the Notifier): a locked chat, or any chat while the list is unknown
     * (unreadable now): true means "redact".
     */
    fun redactBlocking(conversationId: String): Boolean {
        if (_ids.value == null) runBlocking { load() }
        return _ids.value?.contains(conversationId.lowercase()) ?: true
    }

    suspend fun lock(conversationId: String): Boolean =
        change { it.copy(ids = it.ids.filterNot { x -> x.equals(conversationId, true) } + conversationId) }

    suspend fun unlock(conversationId: String): Boolean =
        change { it.copy(ids = it.ids.filterNot { x -> x.equals(conversationId, true) }) }

    suspend fun setSecretCode(code: String): Boolean {
        if (!SecretCode.valid(code)) return false
        val (salt, hash, iter) = SecretCode.create(code)
        return change { it.copy(codeSalt = salt, codeHash = hash, codeIterations = iter) }
    }

    /** Settings "Unlock all": every chat back in the list, the secret code removed. Messages untouched. */
    suspend fun resetAll(): Boolean = change { LockedChatsData() }

    suspend fun clearSecretCode(): Boolean = change { it.copy(codeSalt = null, codeHash = null, codeIterations = 0) }

    /** Exactly the secret code (hash compare); false when none is set. */
    fun codeMatches(code: String): Boolean = SecretCode.matches(data, code)

    fun openFolder() {
        _folderOpen.value = true
    }

    fun closeFolder() {
        _folderOpen.value = false
    }

    /** "Log out and delete chats": the locked list goes with the chats. */
    suspend fun wipe() = mutex.withLock {
        store.wipe()
        apply(LockedChatsData())
        _folderOpen.value = false
    }

    private suspend fun change(f: (LockedChatsData) -> LockedChatsData): Boolean {
        load()
        return mutex.withLock {
            if (_ids.value == null) return@withLock false // unreadable now: never overwrite what we couldn't read
            val next = f(data)
            if (!store.save(next)) return@withLock false
            apply(next)
            true
        }
    }
}
