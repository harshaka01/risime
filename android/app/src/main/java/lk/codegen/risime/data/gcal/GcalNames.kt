package lk.codegen.risime.data.gcal

import lk.codegen.risime.net.RisiSource

/**
 * v1.31 §31.5 names on the Google phone only. The server's "Checked:" paragraph (the last paragraph of the answer,
 * after a blank line) says "Google Calendar (2 calendars)". A phone that holds a local name for EVERY ref the answer
 * lists for its Google source shows "Google Calendar (Work, Personal)" instead. Only the rendering changes: the stored
 * message never does. Other devices, and a phone missing any ref, show the server's counts.
 */
object GcalNames {
    private val COUNT = Regex("Google Calendar \\((\\d+) calendars?\\)")
    private const val CHECKED = "Checked:"

    fun render(answer: String, sources: List<RisiSource>, names: Map<String, String>): String {
        if (names.isEmpty()) return answer
        val g = sources.firstOrNull { it.type == "calendar_source" && it.source == "google_api" && it.readOk == true } ?: return answer
        if (g.refs.isEmpty()) return answer
        val local = g.refs.map { names[it] ?: return answer }
        val cut = answer.lastIndexOf("\n\n")
        val head = if (cut >= 0) answer.substring(0, cut + 2) else ""
        val last = if (cut >= 0) answer.substring(cut + 2) else answer
        if (!last.startsWith(CHECKED)) return answer
        val m = COUNT.find(last) ?: return answer
        return head + last.replaceRange(m.range, "Google Calendar (${local.joinToString(", ")})")
    }
}
