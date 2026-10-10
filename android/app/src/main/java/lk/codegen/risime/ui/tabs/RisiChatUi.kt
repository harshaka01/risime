package lk.codegen.risime.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import lk.codegen.risime.data.tabs.RISI_CHAT_SUBTITLE
import lk.codegen.risime.data.tabs.RisiChatOpen
import lk.codegen.risime.ui.theme.Sizes
import lk.codegen.risime.ui.theme.Spacing

/*
 * §25.2 the Risi chat on screen: Official styling (the Official accent), Risi's avatar, no tabs and no
 * Official toggle. Only on `risi_tools` devices.
 */

/** Shown instead of the Risi chat on a device that doesn't (or no longer) advertise `risi_tools`. */
const val RISI_CHAT_UNAVAILABLE = "Your Risi chat isn't available on this phone."

/** Risi's avatar: the Official accent with "R". */
@Composable
fun RisiAvatar(size: Dp = Sizes.avatar) {
    Box(
        Modifier.size(size).clip(CircleShape).background(OfficialAccent).clearAndSetSemantics { }.testTag("risi_avatar"),
        contentAlignment = Alignment.Center,
    ) {
        Text("R", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
    }
}

/** The strip under the Risi chat's header (where other chats have their tab bar). */
@Composable
fun RisiChatStrip() {
    Text(
        RISI_CHAT_SUBTITLE,
        Modifier.fillMaxWidth().background(OfficialAccent.copy(alpha = 0.12f)).padding(horizontal = Spacing.lg, vertical = Spacing.xxs).testTag("risi_chat_strip"),
        style = MaterialTheme.typography.labelMedium, color = OfficialAccent,
    )
}

/**
 * The first open of the chat list's "Risi" entry: `POST /risi/chat` (and epoch 0 through the outbox),
 * then [onReady] with the conversation. A failure says why, with Retry.
 */
@Composable
fun RisiChatOpenScreen(open: suspend () -> RisiChatOpen, onReady: (String) -> Unit, onBack: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        when (val r = open()) {
            is RisiChatOpen.Ready -> onReady(r.conversationId)
            is RisiChatOpen.Failed -> error = r.text
        }
    }
    Box(Modifier.fillMaxSize().padding(Spacing.lg).testTag("risi_chat_open"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            RisiAvatar(Sizes.avatarLarge)
            val e = error
            if (e == null) {
                CircularProgressIndicator()
                Text("Setting up your Risi chat…", textAlign = TextAlign.Center)
            } else {
                Text(e, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.testTag("risi_chat_error"))
                Button(onClick = { attempt++ }) { Text("Retry", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
            TextButton(onClick = onBack) { Text("Back", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
}

/** A Risi chat on a device without `risi_tools` (e.g. a link from a notification): nothing of it is shown. */
@Composable
fun RisiChatUnavailable(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(Spacing.lg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(RISI_CHAT_UNAVAILABLE, textAlign = TextAlign.Center)
            TextButton(onClick = onBack) { Text("Back", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
    }
}
