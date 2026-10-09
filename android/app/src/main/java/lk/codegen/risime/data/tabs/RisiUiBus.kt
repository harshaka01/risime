package lk.codegen.risime.data.tabs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import lk.codegen.risime.data.db.ChatTabEntity
import lk.codegen.risime.data.db.ContactEntity
import lk.codegen.risime.data.db.GroupEntity
import lk.codegen.risime.net.dmPeer
import java.util.concurrent.ConcurrentHashMap

/**
 * What Risi's cards ask of the rest of the app: a draft's [Use] (open a chat with its composer filled;
 * nothing is sent) and "Open Risi skills". In memory only.
 */
class RisiUiBus {
    sealed interface Nav {
        data class Chat(val conversationId: String) : Nav

        data class Skills(val skillId: String?) : Nav
    }

    private val drafts = ConcurrentHashMap<String, String>()
    private val _nav = MutableSharedFlow<Nav>(extraBufferCapacity = 8)
    val nav: SharedFlow<Nav> = _nav.asSharedFlow()

    /** [Use]: the target's composer gets [text] when it opens; never sent. */
    fun useDraft(conversationId: String, text: String) {
        drafts[conversationId.lowercase()] = text
        _nav.tryEmit(Nav.Chat(conversationId))
    }

    /** The draft waiting for [conversationId] (taken once). */
    fun takeDraft(conversationId: String): String? = drafts.remove(conversationId.lowercase())

    fun openSkills(skillId: String?) {
        _nav.tryEmit(Nav.Skills(skillId))
    }
}

/**
 * The conversations on this phone and their local names: DMs with a friend ("Kumu"), groups this
 * user is in (their name), and Official conversations (the chat's name). Risi chats are excluded.
 */
object ConversationDirectory {
    fun names(
        me: String,
        groups: List<GroupEntity>,
        contacts: List<ContactEntity>,
        tabRows: Map<String, ChatTabEntity>?,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        val byUser = contacts.filter { it.userId != null }.associate { it.userId!!.lowercase() to it.displayName }
        for (ct in contacts) {
            val uid = ct.userId ?: continue
            out[lk.codegen.risime.net.conversationFor(me, uid).lowercase()] = ct.displayName
        }
        for (g in groups) {
            if (g.readOnly) continue
            val conv = g.conversationId.lowercase()
            val row = tabRows?.get(conv)
            if (row?.risi == true) continue
            val chat = chatIdOf(conv, tabRows).lowercase()
            val name = when {
                !g.name.isNullOrBlank() -> g.name
                chat.startsWith("dm:") -> dmPeer(chat, me)?.let { byUser[it.lowercase()] }
                else -> groups.firstOrNull { it.conversationId.equals(chat, true) }?.name?.takeIf { it.isNotBlank() }
            } ?: "Group"
            out[conv] = name
        }
        return out
    }
}
