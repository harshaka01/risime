package lk.codegen.risime.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import lk.codegen.risime.ui.theme.Spacing

const val NEW_MESSAGES_LABEL = "New messages ↓"
const val CHAT_LIST_TAG = "chat_list"

/** At (or one item from) the end of the list: new messages may follow automatically. */
fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return true
    return !canScrollForward || last >= info.totalItemsCount - 1
}

/**
 * The message list for DM and group chats (P0 hotfix):
 * - opens at the newest message;
 * - a new newest message scrolls into view when the user is at the bottom (or sent it);
 * - when the user has scrolled up, the list stays put and "New messages ↓" jumps to the bottom.
 *
 * [count] is the number of items [content] emits (index [count]-1 is the newest), [lastKey] the
 * newest message's key, [lastOutgoing] whether it is the user's own.
 */
@Composable
fun ChatMessageList(
    count: Int,
    lastKey: Any?,
    lastOutgoing: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    spacing: Dp,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Saved across rotation: the first open jumps once; afterwards the saved position stays.
    var opened by rememberSaveable { mutableStateOf(false) }
    var stick by rememberSaveable { mutableStateOf(true) }
    var unseen by rememberSaveable { mutableStateOf(false) }

    // Only the user's own scrolling decides whether new messages follow.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.distinctUntilChanged().filter { !it }.collect {
            stick = state.isAtBottom()
            if (stick) unseen = false
        }
    }
    LaunchedEffect(lastKey) {
        if (count == 0 || lastKey == null) return@LaunchedEffect
        if (!opened) {
            state.scrollToItem(count - 1)
            opened = true
            stick = true
            return@LaunchedEffect
        }
        if (stick || lastOutgoing) {
            state.animateScrollToItem(count - 1)
            stick = true
            unseen = false
        } else {
            unseen = true
        }
    }
    Box(modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth().testTag(CHAT_LIST_TAG),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        if (unseen && !stick) {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem((count - 1).coerceAtLeast(0))
                        stick = true
                        unseen = false
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.md),
            ) { Text(NEW_MESSAGES_LABEL) }
        }
    }
}
