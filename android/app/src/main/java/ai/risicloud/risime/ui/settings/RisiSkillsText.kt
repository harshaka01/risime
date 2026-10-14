package ai.risicloud.risime.ui.settings

import ai.risicloud.risime.net.ApiResult
import ai.risicloud.risime.net.ClientPermission
import ai.risicloud.risime.net.RisiSkill
import ai.risicloud.risime.net.RisiSkillIds
import ai.risicloud.risime.net.RisiSkillStates
import ai.risicloud.risime.net.RisiSkillsErrors
import ai.risicloud.risime.net.RisiUndo
import ai.risicloud.risime.net.RisiUndoReply

/** §26.4 the notice after an [Undo] tap. */
fun undoResultText(r: ApiResult<RisiUndoReply>): String = when (r) {
    is ApiResult.Ok -> when (r.value.entry.undo.state) {
        RisiUndo.DONE -> "Undone"
        RisiUndo.PENDING -> "Undoing on your phone…"
        else -> "Undo requested"
    }
    is ApiResult.Error -> when (r.code) {
        RisiSkillsErrors.UNDO_UNAVAILABLE -> "This can't be undone any more."
        "not_found" -> "That's no longer in your activity."
        else -> "Couldn't undo. Try again."
    }
    is ApiResult.NetworkError -> "No connection. Try again."
}

/*
 * Proposal 2026-10-14-send-for-me §42 (Harsha's P1, item 4): Settings → Risi skills in plain words. Never an
 * Android permission string ("android.permission…", "com.android…") and never "Ask me each time". The app has
 * no Sinhala or Tamil resources (no values-si / values-ta), so these are English like the rest of the app.
 */
object SkillPlainText {
    const val ON = "On"
    const val OFF = "Off"
    const val COMING_LATER = "Coming later"

    /** The two choices of a skill that allows them (§26.3 `ask` / `allowed`). */
    const val SHOW_ME_FIRST = "Show me first"
    const val WITHOUT_ASKING = "Do it without asking"
    const val SHOW_ME_FIRST_HELP = "Risi shows you a card and waits for your tap."
    const val WITHOUT_ASKING_HELP = "Risi does it for you, without a card, when it only affects you."
    const val ALWAYS_SHOWS = "Risi always shows you the message first, because it goes to other people."

    const val ALLOWED_HERE = "Allowed on this phone"
    const val NOT_ALLOWED_HERE = "Not allowed on this phone"
    const val NOT_ASKED_HERE = "Not asked on this phone yet"
    const val NOT_SUPPORTED_HERE = "This phone can't do it"
    const val NOTHING_NEEDED = "Nothing to allow on this phone"
    const val UNKNOWN_HERE = "Not checked on this phone yet"

    /** The state line under the title: "On" / "On · Do it without asking" / "Off" / "Coming later". */
    fun stateLine(s: RisiSkill): String = when {
        !s.available -> COMING_LATER
        s.state == RisiSkillStates.ALLOWED -> "$ON · $WITHOUT_ASKING"
        s.state == RisiSkillStates.ASK -> ON
        else -> OFF
    }

    /** One plain sentence per skill (the server's description for an id this build doesn't know). */
    fun sentence(s: RisiSkill): String = when (s.id) {
        RisiSkillIds.ALARM -> "Risi can set an alarm in your phone's Clock app when you ask. Only on this phone; you remove it in Clock."
        RisiSkillIds.REMINDERS -> "Risi can remind you at the time you choose, with a notification and a message in the chat."
        RisiSkillIds.CALENDAR -> "Risi can see when you're busy and add events you agree to. It never reads event titles or who is invited."
        RisiSkillIds.SCHEDULED_MESSAGES -> "Risi can send a message you approved at the time you choose. Your phone sends it, encrypted. Risi always shows you the message first."
        "email" -> "Risi will be able to read your email when you ask. Coming later."
        else -> s.description
    }

    /** What this phone allows, in plain words; [optional]: the skill works without it (just less exactly). */
    fun permissionLine(p: String, optional: Boolean = false): String = when (p) {
        ClientPermission.GRANTED -> ALLOWED_HERE
        ClientPermission.DENIED -> if (optional) "$NOT_ALLOWED_HERE · it may be a few minutes late" else "$NOT_ALLOWED_HERE · Open settings"
        ClientPermission.NOT_ASKED -> NOT_ASKED_HERE
        ClientPermission.NOT_NEEDED -> NOTHING_NEEDED
        ClientPermission.UNSUPPORTED -> NOT_SUPPORTED_HERE
        else -> UNKNOWN_HERE
    }

    /** A permission's label only (the exact Android string is never shown). */
    fun permissionLabel(label: String): String = label.ifBlank { "Access on this phone" }

    /** Text that must never reach the screen. */
    fun leaksRawNames(text: String): Boolean =
        text.contains("android.permission") || text.contains("com.android.") || text.contains("Ask me each time")
}
