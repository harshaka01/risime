package lk.codegen.risime.update

/**
 * Release notes are published as Markdown; the updater shows plain text (no Markdown library).
 * Headings, emphasis, code, quotes and rules lose their markers; list items become "• item";
 * links and images become their text. Unknown syntax passes through unchanged.
 */
fun markdownToPlainText(markdown: String): String {
    val out = mutableListOf<String>()
    for (raw in markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        var line = raw.trimEnd()
        if (FENCE.matches(line)) continue
        if (RULE.matches(line)) { out += ""; continue }
        if (SETEXT.matches(line) && out.lastOrNull()?.isNotBlank() == true) continue
        line = QUOTE.replace(line, "")
        HEADING.matchEntire(line)?.let { line = it.groupValues[1] }
        BULLET.matchEntire(line)?.let { line = it.groupValues[1] + "• " + it.groupValues[2] }
        TASK.matchEntire(line)?.let { line = it.groupValues[1] + it.groupValues[2] }
        out += inline(line)
    }
    return out.joinToString("\n").replace(BLANKS, "\n\n").trim()
}

private fun inline(s: String): String {
    // Escaped punctuation is parked in the private-use area so no rule below sees it.
    var t = ESCAPE.replace(s) { (0xE000 + it.groupValues[1][0].code).toChar().toString() }
    t = IMAGE.replace(t) { it.groupValues[1] }
    t = LINK.replace(t) { it.groupValues[1] }
    t = REF_LINK.replace(t) { it.groupValues[1] }
    t = AUTOLINK.replace(t) { it.groupValues[1] }
    t = CODE.replace(t) { it.groupValues[1] }
    t = BOLD_STAR.replace(t) { it.groupValues[1] }
    t = BOLD_UNDER.replace(t) { it.groupValues[1] }
    t = STRIKE.replace(t) { it.groupValues[1] }
    t = ITALIC_STAR.replace(t) { it.groupValues[1] }
    t = ITALIC_UNDER.replace(t) { it.groupValues[1] + it.groupValues[2] }
    t = HTML_TAG.replace(t, "")
    return String(CharArray(t.length) { i -> t[i].let { if (it.code in 0xE000..0xE07F) (it.code - 0xE000).toChar() else it } })
}

private val FENCE = Regex("""^\s*(```|~~~).*$""")
private val RULE = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val SETEXT = Regex("""^\s{0,3}(=+|-+)\s*$""")
private val QUOTE = Regex("""^\s{0,3}(>\s?)+""")
private val HEADING = Regex("""^\s{0,3}#{1,6}(?:\s+(.*?)(?:\s+#+)?)?\s*$""")
private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val TASK = Regex("""^(\s*• )\[[ xX]\]\s+(.*)$""")
private val IMAGE = Regex("""!\[([^\]]*)\]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]+)\]\([^)]*\)""")
private val REF_LINK = Regex("""\[([^\]]+)\]\[[^\]]*\]""")
private val AUTOLINK = Regex("""<((?:https?|mailto):[^>\s]+)>""")
private val CODE = Regex("""`+([^`]+)`+""")
private val BOLD_STAR = Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*""")
private val BOLD_UNDER = Regex("""(?<![\w])__(?=\S)(.+?)(?<=\S)__(?![\w])""")
private val STRIKE = Regex("""~~(?=\S)(.+?)(?<=\S)~~""")
private val ITALIC_STAR = Regex("""\*(?=\S)([^*]+?)(?<=\S)\*""")
private val ITALIC_UNDER = Regex("""(^|[^\w])_(?=\S)([^_]+?)(?<=\S)_(?![\w])""")
private val HTML_TAG = Regex("""</?[A-Za-z][A-Za-z0-9-]*(\s[^<>]*)?/?>""")
private val ESCAPE = Regex("""\\([\\`*_{}\[\]()#+\-.!>~|])""")
private val BLANKS = Regex("""\n{3,}""")
