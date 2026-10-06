package lk.codegen.risime.ui.chat

import androidx.compose.runtime.Composable
import lk.codegen.risime.data.db.MessageEntity
import lk.codegen.risime.data.deletes.DeleteRules
import lk.codegen.risime.ui.common.MessageBubble
import lk.codegen.risime.ui.common.timeOf

/** §15.6 the text of a tombstone row (or a row being deleted for everyone: mine, so "You deleted this message"). */
fun tombstoneText(m: MessageEntity, me: String): String =
    DeleteRules.tombstoneText(if (m.deleteState != null) me else m.deletedBy, me, m.deletedByAdmin)

/**
 * §15.6 a tombstone: the same position and side, no content, no ticks, no reactions; long-press
 * offers only "Delete for me" (when the delete UI is on, [onMenu] non-null).
 */
@Composable
fun TombstoneBubble(m: MessageEntity, me: String, sender: String? = null, onMenu: (() -> Unit)? = null, selected: Boolean = false, onTap: (() -> Unit)? = null) {
    MessageBubble(
        body = "🚫 " + tombstoneText(m, me),
        time = timeOf(m.localTs),
        mine = m.outgoing,
        status = null,
        onMenu = { onMenu?.invoke() },
        sender = sender,
        onTap = onTap,
        selected = selected,
        muted = true,
    )
}
