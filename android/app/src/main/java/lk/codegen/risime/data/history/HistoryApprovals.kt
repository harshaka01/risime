package lk.codegen.risime.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import lk.codegen.risime.data.mls.MlsEngine
import lk.codegen.risime.net.ProtocolJson
import java.util.Base64

/** One option-A approval (§17.8): a new phone of mine, by device id and its leaf signature key. */
@Serializable
data class HistoryApproval(
    @SerialName("device_id") val deviceId: String,
    @SerialName("user_id") val userId: String,
    /** base64 of the requesting leaf's signature key (from the MLS credential). */
    val key: String,
    val at: Long,
)

/**
 * §17.8 option A: "one approval per new device, then automatic", keyed by (requester device id,
 * its leaf signature key), so a re-registered device with a new key asks again. Kept in the sealed
 * store (`mls_kv`, namespace "app"), listed in Settings → Privacy with Remove, and forgotten when
 * that device leaves my device set.
 */
class HistoryApprovals(private val engine: () -> MlsEngine?) {
    private val _all = MutableStateFlow<List<HistoryApproval>>(emptyList())

    /** For Settings → Privacy ("Phones allowed to get your history"). */
    val flow: StateFlow<List<HistoryApproval>> get() = _all

    private fun load(): List<HistoryApproval> {
        val bytes = engine()?.appStateGet(KEY) ?: return emptyList()
        return runCatching { ProtocolJson.decodeFromString(ListSerializer(HistoryApproval.serializer()), bytes.decodeToString()) }.getOrDefault(emptyList())
    }

    private fun save(list: List<HistoryApproval>) {
        engine()?.appStatePut(KEY, if (list.isEmpty()) null else ProtocolJson.encodeToString(ListSerializer(HistoryApproval.serializer()), list).toByteArray())
        _all.value = list
    }

    fun refresh(): List<HistoryApproval> = load().also { _all.value = it }

    fun approved(deviceId: String, signatureKey: ByteArray): Boolean {
        val k = Base64.getEncoder().encodeToString(signatureKey)
        return load().any { it.deviceId.equals(deviceId, true) && it.key == k }
    }

    fun add(userId: String, deviceId: String, signatureKey: ByteArray, at: Long) {
        val k = Base64.getEncoder().encodeToString(signatureKey)
        save(load().filterNot { it.deviceId.equals(deviceId, true) } + HistoryApproval(deviceId.lowercase(), userId.lowercase(), k, at))
    }

    fun remove(deviceId: String) {
        val before = load()
        val after = before.filterNot { it.deviceId.equals(deviceId, true) }
        if (after.size != before.size) save(after)
    }

    private companion object {
        const val KEY = "history/approvals"
    }
}
