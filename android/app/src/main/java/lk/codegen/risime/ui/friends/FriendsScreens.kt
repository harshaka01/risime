package lk.codegen.risime.ui.friends

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lk.codegen.risime.net.FriendRequest
import lk.codegen.risime.net.Invite
import lk.codegen.risime.ui.common.EmptyState
import lk.codegen.risime.ui.common.ErrorState
import lk.codegen.risime.ui.common.InitialsAvatar
import lk.codegen.risime.ui.common.ListRow
import lk.codegen.risime.ui.common.RisiTopBar
import lk.codegen.risime.ui.common.SectionHeader
import lk.codegen.risime.ui.common.shortStamp
import lk.codegen.risime.ui.theme.Spacing
import java.time.Instant

/** Android share sheet with the server's subject + share_text (§9.1: the server never sends invites itself). */
fun shareInvite(context: Context, invite: Invite) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, invite.subject)
        putExtra(Intent.EXTRA_TEXT, invite.shareText)
    }
    context.startActivity(Intent.createChooser(send, "Invite ${invite.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun isoMs(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Requests tab: incoming Accept / Decline / Block (confirmed), outgoing Cancel. */
@Composable
fun RequestsTab(vm: FriendsViewModel, onAddFriend: () -> Unit) {
    val f by vm.friendsState.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        msg?.let { m -> item { ErrorState(m) } }
        if (f.incoming.isEmpty() && f.outgoing.isEmpty()) {
            item { EmptyState("No requests", actionLabel = "Add a friend", onAction = onAddFriend) }
        }
        if (f.incoming.isNotEmpty()) item { Header("Incoming") }
        items(f.incoming, key = { "in:" + it.id }) { r -> IncomingRow(r, vm) { title, act -> confirm = title to act } }
        if (f.outgoing.isNotEmpty()) item { Header("Sent") }
        items(f.outgoing, key = { "out:" + it.id }) { r ->
            ListRow(
                title = r.phone,
                subtitle = "Waiting for them to accept",
                leading = { InitialsAvatar(r.phone) },
                meta = isoMs(r.insertedAt)?.let(::shortStamp),
            )
            Row(Modifier.padding(start = Spacing.lg)) {
                TextButton(onClick = { confirm = "Cancel the request to ${r.phone}?" to { vm.cancel(r.id) } }) { Text("Cancel request") }
            }
        }
        if (f.blocked.isNotEmpty()) item { Header("Blocked") }
        items(f.blocked, key = { "b:" + it.userId }) { b ->
            ListRow(title = b.displayName, subtitle = b.phone, leading = { InitialsAvatar(b.displayName, enabled = false) })
            Row(Modifier.padding(start = Spacing.lg)) { TextButton(onClick = { vm.unblock(b.userId) }) { Text("Unblock") } }
        }
    }
    confirm?.let { (title, act) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text("They won't be told.") },
            confirmButton = { TextButton(onClick = { act(); confirm = null }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("No") } },
        )
    }
}

@Composable
private fun Header(t: String) = SectionHeader(t, Modifier.padding(start = Spacing.lg, top = Spacing.lg, bottom = Spacing.xs))

@Composable
private fun IncomingRow(r: FriendRequest, vm: FriendsViewModel, ask: (String, () -> Unit) -> Unit) =
    IncomingRowContent(r, onAccept = vm::accept, onDecline = vm::decline, onBlock = vm::block, ask = ask)

/** An incoming request; §21.4 adds "Phone not verified" for a self-asserted phone. */
@Composable
internal fun IncomingRowContent(
    r: FriendRequest,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    onBlock: (String) -> Unit,
    ask: (String, () -> Unit) -> Unit,
) {
    val name = r.displayName ?: r.phone
    ListRow(
        title = name,
        subtitle = listOfNotNull(r.company?.takeIf { it.isNotBlank() }, r.phone).joinToString(" · "),
        leading = { InitialsAvatar(name) },
        meta = isoMs(r.insertedAt)?.let(::shortStamp),
    )
    if (!r.phoneConfirmed) lk.codegen.risime.ui.auth.PhoneNotVerifiedNote(Modifier.padding(start = Spacing.lg))
    Row(Modifier.padding(start = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Button(onClick = { onAccept(r.id) }) { Text("Accept") }
        OutlinedButton(onClick = { ask("Decline $name's request?") { onDecline(r.id) } }) { Text("Decline") }
        r.userId?.let { uid ->
            TextButton(onClick = { ask("Block $name? You won't get messages or requests from them.") { onBlock(uid) } }) {
                Text("Block", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Add friend by phone → the always-same reply → "Not on RisiMe yet? Send an invite" → share sheet. */
@Composable
fun AddFriendScreen(vm: FriendsViewModel, onBack: () -> Unit) {
    val s by vm.add.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.share.collect { shareInvite(context, it) } }
    Scaffold(
        topBar = { RisiTopBar(title = "Add friend", onBack = { vm.resetAdd(); onBack() }) },
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (s.sentTo == null) {
                Text("Enter their mobile number. They'll get a friend request when they're on RisiMe.", textAlign = TextAlign.Center)
                OutlinedTextField(
                    value = s.phone,
                    onValueChange = vm::onPhone,
                    label = { Text("Phone number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
                    modifier = Modifier.fillMaxWidth(),
                )
                s.error?.let { ErrorState(it) }
                Button(onClick = vm::sendRequest, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (s.busy) "Sending…" else "Send friend request")
                }
            } else {
                Text(requestSentText(s.sentTo!!), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                if (!s.inviting) {
                    Text("Not on RisiMe yet?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = vm::startInvite, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Send an invite") }
                    TextButton(onClick = { vm.resetAdd(); onBack() }) { Text("Done") }
                } else {
                    Text("Invite ${s.sentTo}", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = s.inviteName, onValueChange = vm::onInviteName, label = { Text("Their name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.inviteEmail, onValueChange = vm::onInviteEmail, label = { Text("Email they'll sign in with") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "You share the invite yourself (WhatsApp, SMS, email…). RisiMe doesn't message them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    s.error?.let { ErrorState(it) }
                    Button(onClick = vm::sendInvite, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(if (s.busy) "Creating…" else "Create and share invite")
                    }
                }
            }
        }
    }
}

/** My sent invites, newest first; Revoke / Share again for pending ones. */
@Composable
fun InvitesScreen(vm: FriendsViewModel, onBack: () -> Unit) {
    val list by vm.invites.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadInvites() }
    Scaffold(topBar = { RisiTopBar(title = "Invites", onBack = onBack) }, contentWindowInsets = WindowInsets(0)) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            msg?.let { m -> item { ErrorState(m, onRetry = vm::loadInvites) } }
            if (list.isEmpty()) item { EmptyState("You haven't invited anyone") }
            items(list, key = { it.id }) { inv ->
                ListRow(
                    title = inv.name,
                    subtitle = "${inv.phone} · ${inv.email}",
                    leading = { InitialsAvatar(inv.name, enabled = inv.pending) },
                    meta = inv.status,
                    strong = inv.pending,
                )
                if (inv.pending) {
                    Row(Modifier.padding(start = Spacing.lg)) {
                        TextButton(onClick = { shareInvite(context, inv) }) { Text("Share again") }
                        TextButton(onClick = { vm.revoke(inv.id) }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
