package lk.codegen.risime.ui.chat

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.emoji2.emojipicker.RecentEmojiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lk.codegen.risime.ui.theme.Spacing

/** Recently used emoji, most recent first, no duplicates, capped at [MAX] (the picker's "Recent" tab). */
object EmojiRecents {
    const val MAX = 32
    private const val SEP = "\n"

    fun push(list: List<String>, emoji: String): List<String> =
        if (emoji.isBlank()) list else (listOf(emoji) + list.filter { it != emoji }).take(MAX)

    fun encode(list: List<String>): String = list.take(MAX).joinToString(SEP)

    fun decode(s: String?): List<String> = s.orEmpty().split(SEP).filter { it.isNotBlank() }.distinct().take(MAX)
}

/** The panel's height: the last keyboard height when known and sane, else the 280dp default. */
fun emojiPanelHeight(lastKeyboard: Dp?): Dp = lastKeyboard?.takeIf { it.value >= 200f } ?: 280.dp

private val Context.emojiDataStore by preferencesDataStore(name = "risime_emoji")

/** Recents of the emoji2 picker kept in DataStore (one list for the composer panel and the reaction sheet). */
class EmojiRecentStore(private val prefs: DataStore<Preferences>, private val scope: CoroutineScope) : RecentEmojiProvider {
    private val key = stringPreferencesKey("recent")

    override suspend fun getRecentEmojiList(): List<String> = EmojiRecents.decode(prefs.data.first()[key])

    override fun recordSelection(emoji: String) {
        scope.launch { prefs.edit { it[key] = EmojiRecents.encode(EmojiRecents.push(EmojiRecents.decode(it[key]), emoji)) } }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun of(context: Context) = EmojiRecentStore(context.applicationContext.emojiDataStore, scope)
    }
}

/**
 * The inline emoji panel that takes the keyboard's place: category tabs, grid and recents come from
 * the emoji2 picker ([picker]; a fake in tests), plus a bottom row with "back to keyboard" and
 * backspace (deletes a whole grapheme).
 */
@Composable
fun EmojiPanel(
    height: Dp,
    onPick: (String) -> Unit,
    onBackspace: () -> Unit,
    onKeyboard: () -> Unit,
    picker: @Composable (onPick: (String) -> Unit) -> Unit,
) {
    val latest by rememberUpdatedState(onPick)
    val stable = remember { { e: String -> latest(e) } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().height(height)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth()) { picker(stable) }
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onKeyboard) {
                    Text("ABC", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = "Keyboard" })
                }
                IconButton(onClick = onBackspace) {
                    Text("⌫", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = "Delete" })
                }
            }
        }
    }
}
