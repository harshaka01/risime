package lk.codegen.risime.ui.chat

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lk.codegen.risime.AppContainer
import lk.codegen.risime.data.db.MediaEntity
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.media.AndroidBitmapOps
import lk.codegen.risime.data.media.ImagePipeline
import lk.codegen.risime.data.media.ImageRejected
import lk.codegen.risime.data.media.PreparedSend
import lk.codegen.risime.net.ApiResult
import lk.codegen.risime.net.dmPeer

/**
 * The image side of a chat screen (DM or group): media rows, downloads, the viewer, save, the
 * attach flow (pick one or more → preview with captions → re-encode + encrypt in-process → commit)
 * and `images_ready`.
 */
class ImageActions(private val c: AppContainer, private val scope: CoroutineScope, private val meId: String, val conversationId: String) {
    val media: StateFlow<Map<String, MediaEntity>> = c.db.media().forConversation(conversationId)
        .map { rows -> rows.associateBy { it.clientMsgId } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The determinate upload progress of this phone's photos going out (client_msg_id → 0..1). */
    val uploads: StateFlow<Map<String, Float>> get() = c.imageUploader.progress.flow

    val loader: ImageLoader? get() = if (c.mediaCrypto != null) c.imageLoader else null

    /** The photo shown full screen (client_msg_id). */
    val viewer = MutableStateFlow<String?>(null)

    /** §14.1 from `GET /mls/groups/{id}`: null until fetched. */
    val imagesReady = MutableStateFlow<Boolean?>(null)
    val missingIsMe = MutableStateFlow(false)

    /** One-line feedback (save result, refusals). */
    val toast = MutableStateFlow<String?>(null)

    val available: Boolean get() = c.mediaCrypto != null && c.mlsEngine != null

    fun refreshImagesReady() {
        if (!available) return
        scope.launch {
            val r = c.api.mlsGroup(conversationId) as? ApiResult.Ok ?: return@launch
            imagesReady.value = r.value.imagesReady
            missingIsMe.value = r.value.missingImages.isNotEmpty() && r.value.missingImages.all { it.userId.equals(meId, true) }
        }
    }

    fun onVisible(id: String) = c.onImageVisible(id)

    fun tap(m: MessageEntity) {
        val row = media.value[m.clientMsgId]
        when (imageTap(row)) {
            ImageTap.OPEN -> viewer.value = m.clientMsgId
            ImageTap.DOWNLOAD -> c.scope.launch { c.downloadImage(m.clientMsgId) }
            ImageTap.NONE -> Unit
        }
    }

    fun closeViewer() {
        viewer.value = null
    }

    /** Save to gallery: the decrypted pixels re-encoded (no metadata), never the bytes as received. */
    suspend fun bytesForSave(id: String): ByteArray? = withContext(Dispatchers.Default) {
        val plain = loader?.decrypted(id) ?: return@withContext null
        try {
            runCatching { ImagePipeline(AndroidBitmapOps).reencodeForSave(plain) }.getOrNull()
        } finally {
            plain.fill(0)
        }
    }

    fun retry(clientMsgId: String) {
        c.scope.launch { if (c.images.retry(clientMsgId)) c.engine.flushOutbox() }
    }

    fun delete(clientMsgId: String) {
        c.scope.launch {
            loader?.forget(clientMsgId)
            c.images.deleteUnsent(clientMsgId)
        }
    }

    // ---- attach (the "+" sheet: gallery multi-select or camera → preview with captions → send) ----

    /** The photos chosen for sending (null: no preview open). */
    val selection = MutableStateFlow<List<PickedPhoto>?>(null)

    /** Gallery (Photo Picker, up to [MAX_PHOTOS]) or camera result → the preview. */
    fun onPicked(uris: List<Uri>, temp: Boolean = false) {
        if (uris.isEmpty()) return
        selection.value = uris.take(MAX_PHOTOS).map { PickedPhoto(java.util.UUID.randomUUID().toString(), it, temp = temp) }
    }

    fun onCaption(id: String, text: String) {
        selection.update { list -> list?.map { if (it.id == id) it.copy(caption = text) else it } }
    }

    /** Removes one photo from the selection; removing the last one closes the preview. */
    fun remove(id: String) {
        val list = selection.value ?: return
        list.firstOrNull { it.id == id }?.let(::dropTemp)
        selection.value = list.filterNot { it.id == id }.ifEmpty { null }
    }

    fun cancelSelection() {
        selection.value?.forEach(::dropTemp)
        selection.value = null
    }

    /**
     * Each photo becomes its own encrypted image message (§14) with its caption, in order: read →
     * re-encode without metadata + encrypt in-process → commit (the upload, progress and retry are
     * the normal per-photo pipeline). Runs in the app scope, so leaving the chat doesn't stop it.
     */
    fun sendSelection() {
        val list = selection.value ?: return
        selection.value = null
        c.scope.launch {
            val to = dmPeer(conversationId, meId) ?: conversationId
            var failed = 0
            var reason: String? = null
            list.forEachIndexed { i, p ->
                if (list.size > 1) toast.value = "Encrypting photo ${i + 1} of ${list.size}…"
                val prepared = try {
                    withContext(Dispatchers.Default) { prepare(p.uri) }
                } catch (e: ImageRejected) {
                    reason = e.message; null
                } catch (e: Exception) {
                    reason = "Couldn't prepare this photo"; null
                } finally {
                    dropTemp(p)
                }
                if (prepared == null) {
                    failed++
                } else {
                    c.images.commit(prepared, conversationId, meId, to, p.caption)
                    runCatching { c.behaviour.imageSent(to, prepared.sealed.plainSize, prepared.w, prepared.h) }
                }
            }
            toast.value = when {
                failed == 0 -> null
                list.size == 1 -> reason ?: "Couldn't prepare this photo"
                else -> "Couldn't send $failed of ${list.size} photos: ${reason ?: "couldn't prepare them"}"
            }
        }
    }

    private suspend fun prepare(uri: Uri): PreparedSend {
        val bytes = c.appContentResolver().openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > ImagePipeline.MAX_SOURCE_BYTES) throw ImageRejected("This photo is too large")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw ImageRejected("Couldn't open this photo")
        return c.images.prepare(bytes, ImagePipeline(AndroidBitmapOps)) { }
    }

    /** A camera capture lives in our cache only until it's prepared (or dropped). */
    private fun dropTemp(p: PickedPhoto) {
        if (p.temp) runCatching { c.appContentResolver().delete(p.uri, null, null) }
    }

    companion object {
        /** The most photos one send takes (WhatsApp-like; the picker enforces it too). */
        const val MAX_PHOTOS = 10
    }
}

/** One photo chosen for sending, with its caption. [temp]: a camera capture in our cache (deleted after). */
data class PickedPhoto(val id: String, val uri: Uri, val caption: String = "", val temp: Boolean = false)
