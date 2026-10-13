package ai.risicloud.risime.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.risicloud.risime.ui.common.InitialsAvatar
import ai.risicloud.risime.ui.theme.Spacing

/** v1.44 §39.2/§39.4 one person the picker offers. */
data class AddCandidate(
    val userId: String,
    val name: String,
    val enabled: Boolean,
    /** Why it is greyed out, or what tapping does ("In the group · Ring"). */
    val note: String? = null,
    /** §39.4 (a): a member of the group call's chat who isn't in the room: a re-ring, no add. */
    val ring: Boolean = false,
)

/** A friend as the picker needs it (from the contacts and `GET /friends`). */
data class FriendLite(val userId: String, val name: String, val groupReady: Boolean, val groupCalls: Boolean = true)

object AddPeople {
    const val NEEDS_UPDATE = "needs to update RisiMe"
    const val NO_GROUP_CALLS = "can't join calls yet"
    const val ONLY_ADMINS = "Only admins can add people to this group"
    const val IN_GROUP_RING = "In this group · ring"

    /** §39.2: the most people in a call, those already in it included (§20.9). */
    fun cap(video: Boolean) = if (video) 8 else 32

    /** §39.2 a 1:1 call: my friends except [exclude] (me, the peer), greyed out when they can't join. */
    fun forOneToOne(friends: List<FriendLite>, exclude: Set<String>): List<AddCandidate> =
        friends.filter { it.userId.lowercase() !in exclude }.map { f ->
            AddCandidate(
                f.userId.lowercase(), f.name, enabled = f.groupReady && f.groupCalls,
                note = when {
                    !f.groupReady -> NEEDS_UPDATE
                    !f.groupCalls -> NO_GROUP_CALLS
                    else -> null
                },
            )
        }.sortedWith(compareBy({ !it.enabled }, { it.name.lowercase() }))

    /**
     * §39.4 a group call: (a) the chat's [members] who aren't [present] → ring; (b) for an [admin],
     * friends who aren't members → add to the group and ring (others see why not).
     */
    fun forGroup(members: Set<String>, present: Set<String>, me: String, admin: Boolean, friends: List<FriendLite>, memberName: (String) -> String): List<AddCandidate> {
        val m = members.map(String::lowercase).toSet()
        val p = present.map(String::lowercase).toSet() + me.lowercase()
        val ring = (m - p).map { AddCandidate(it, memberName(it), enabled = true, note = IN_GROUP_RING, ring = true) }.sortedBy { it.name.lowercase() }
        val add = friends.filter { it.userId.lowercase() !in m && !it.userId.equals(me, true) }.map { f ->
            AddCandidate(
                f.userId.lowercase(), f.name, enabled = admin && f.groupReady && f.groupCalls,
                note = when {
                    !admin -> ONLY_ADMINS
                    !f.groupReady -> NEEDS_UPDATE
                    !f.groupCalls -> NO_GROUP_CALLS
                    else -> null
                },
            )
        }.sortedWith(compareBy({ !it.enabled }, { it.name.lowercase() }))
        return ring + add
    }

    /** §39.2 the confirmation line. */
    fun confirmText(names: List<String>, peerName: String?, groupName: String?): String {
        val who = CallMover.joinNames(names)
        return if (peerName != null) {
            "Add $who to this call? A group chat with ${CallMover.joinNames(listOf(peerName) + names)} and you is created; " +
                (if (names.size == 1) "$who hears" else "they hear") + " the call from when they join."
        } else {
            "Add $who to ${groupName ?: "this group"} and ring ${if (names.size == 1) "them" else "them all"}? They'll see new messages in this group."
        }
    }
}

/**
 * v1.44 §39.2/§39.4 the picker over the call screen: search, multi-select up to [room] more people,
 * then a confirmation (nothing is created before it). [peerName] non-null = a 1:1 call.
 */
@Composable
fun AddPeopleDialog(
    candidates: List<AddCandidate>,
    room: Int,
    peerName: String?,
    groupName: String?,
    official: Boolean,
    onAdd: (ring: List<String>, add: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val byId = candidates.associateBy { it.userId }
    if (confirm) {
        val chosen = picked.mapNotNull { byId[it] }
        val adds = chosen.filter { !it.ring }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add to call") },
            text = { Text(if (adds.isEmpty()) "Ring ${CallMover.joinNames(chosen.map { it.name })}?" else AddPeople.confirmText(adds.map { it.name }, peerName, groupName)) },
            confirmButton = { TextButton(onClick = { onAdd(chosen.filter { it.ring }.map { it.userId }, adds.map { it.userId }) }, modifier = Modifier.testTag("add-confirm")) { Text(if (adds.isEmpty()) "Ring" else "Add") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Back") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add participant") },
        text = {
            Column {
                Text(
                    when {
                        peerName == null -> "Ring someone in this group, or add a friend to it."
                        official -> "A new group chat with everyone in the call. Risi will be in it (Official)."
                        else -> "A new group chat with everyone in the call."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = Spacing.xs), singleLine = true, placeholder = { Text("Search") })
                if (room <= 0) Text("This call is full", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    val shown = candidates.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                    items(shown.size, key = { shown[it].userId }) { i ->
                        val c = shown[i]
                        val on = c.userId in picked
                        val can = c.enabled && (on || picked.size < room)
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = can) { picked = if (on) picked - c.userId else picked + c.userId }
                                .padding(vertical = Spacing.xxs).semantics { contentDescription = c.name + (c.note?.let { ", $it" } ?: "") }.testTag("add-candidate"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, onCheckedChange = null, enabled = can)
                            InitialsAvatar(c.name, size = 36.dp, photoKey = c.userId)
                            Spacer(Modifier.width(Spacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (c.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                c.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm = true }, enabled = picked.isNotEmpty(), modifier = Modifier.testTag("add-next")) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
