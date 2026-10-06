package lk.codegen.risime.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/** The attach sheet's state: what's prepared and the caption being typed. */
data class AttachUi(val state: AttachState, val caption: String = "")

/**
 * The image side of a chat screen (DM or group): media rows, downloads, the viewer, save, the
 * attach flow (pick → re-encode + encrypt in-process → caption → commit) and `images_ready`.
 */
class ImageActions(private val c: AppContainer, private val scope: CoroutineScope, private val meId: String, val conversationId: String) {
    val media: StateFlow<Map<String, MediaEntity>> = c.db.media().forConversation(conversationId)
        .map { rows -> rows.associateBy { it.clientMsgId } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val loader: ImageLoader? get() = if (c.mediaCrypto != null) c.imageLoader else null

    /** The photo shown full screen (client_msg_id). */
    val viewer = MutableStateFlow<String?>(null)

    val attach = MutableStateFlow<AttachUi?>(null)
    private var prepared: PreparedSend? = null

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

    // ---- attach ----

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        attach.value = AttachUi(AttachState.Preparing)
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
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
                    Result.success(c.images.prepare(bytes, ImagePipeline(AndroidBitmapOps)))
                } catch (e: ImageRejected) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(ImageRejected("Couldn't prepare this photo"))
                }
            }
            if (attach.value == null) { // cancelled while preparing
                result.getOrNull()?.let(c.images::discard)
                return@launch
            }
            result.fold(
                onSuccess = { p ->
                    prepared = p
                    val thumb = p.thumb?.data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    attach.value = AttachUi(AttachState.Ready(thumb, p.w, p.h), attach.value?.caption.orEmpty())
                },
                onFailure = { e -> attach.value = AttachUi(AttachState.Error(e.message ?: "Couldn't prepare this photo")) },
            )
        }
    }

    fun onCaption(text: String) {
        attach.value = attach.value?.copy(caption = text)
    }

    fun sendAttach() {
        val p = prepared ?: return
        val caption = attach.value?.caption.orEmpty()
        prepared = null
        attach.value = null
        c.scope.launch {
            val to = dmPeer(conversationId, meId) ?: conversationId
            c.images.commit(p, conversationId, meId, to, caption)
            runCatching { c.behaviour.imageSent(to, p.sealed.plainSize, p.w, p.h) }
        }
    }

    fun cancelAttach() {
        prepared?.let(c.images::discard)
        prepared = null
        attach.value = null
    }
}
