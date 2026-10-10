package lk.codegen.risime.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/*
 * P0 2026-10-10 (Harsha: raw "**" in Risi's answers): a small Markdown subset for Risi bubbles, no library.
 * Blocks: paragraphs, "- "/"* "/"• " bullets (2-space nesting), "1. " numbered items, "# " headings (bold).
 * Inline: **bold** / __bold__, *italic* / _italic_, `code`, [label](https://…) links. Anything else is plain
 * text, and a marker that doesn't pair ("**" left over) is dropped, so no raw "**" is ever shown.
 */
object RisiMarkdown {
    sealed interface Block {
        data class Para(val text: String, val heading: Boolean = false) : Block

        data class Bullet(val text: String, val level: Int) : Block

        data class Numbered(val number: String, val text: String, val level: Int) : Block
    }

    private val BULLET = Regex("^( *)[-*•+] +(.*)$")
    private val NUMBERED = Regex("^( *)(\\d{1,3})[.)] +(.*)$")
    private val HEADING = Regex("^#{1,6} +(.*)$")

    fun blocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += Block.Para(para.toString().trim())
            para.clear()
        }
        for (raw in text.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            val b = BULLET.matchEntire(line)
            val n = NUMBERED.matchEntire(line)
            val h = HEADING.matchEntire(line.trimStart())
            when {
                line.isBlank() -> flush()
                // "**Bold**" alone on a line is not a bullet ("*" followed by "*").
                b != null && !line.trimStart().startsWith("**") -> { flush(); out += Block.Bullet(b.groupValues[2], b.groupValues[1].length / 2) }
                n != null -> { flush(); out += Block.Numbered(n.groupValues[2], n.groupValues[3], n.groupValues[1].length / 2) }
                h != null -> { flush(); out += Block.Para(h.groupValues[1], heading = true) }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            }
        }
        flush()
        return out
    }

    /** The inline spans of one block. [linkColor] colours links; only http(s) links are links. */
    fun inline(text: String, linkColor: Color = Color.Unspecified, codeBackground: Color = Color.Unspecified): AnnotatedString = buildAnnotatedString {
        var i = 0
        val s = text
        fun closing(marker: String, from: Int): Int {
            var j = s.indexOf(marker, from)
            while (j >= 0) {
                if (j > from && !s[j - 1].isWhitespace()) return j
                j = s.indexOf(marker, j + 1)
            }
            return -1
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "*_`[]\\" -> { append(s[i + 1]); i += 2 }
                c == '`' -> {
                    val j = s.indexOf('`', i + 1)
                    if (j > i + 1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(s.substring(i + 1, j)) }
                        i = j + 1
                    } else { append(c); i++ }
                }
                (s.startsWith("**", i) || s.startsWith("__", i)) -> {
                    val m = s.substring(i, i + 2)
                    val j = if (i + 2 < s.length && !s[i + 2].isWhitespace()) closing(m, i + 2) else -1
                    if (j > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(s.substring(i + 2, j), linkColor, codeBackground)) }
                        i = j + 2
                    } else {
                        i += 2 // an unpaired "**"/"__" is dropped, never shown
                    }
                }
                (c == '*' || c == '_') -> {
                    val wordStart = i == 0 || !s[i - 1].isLetterOrDigit()
                    val j = if (wordStart && i + 1 < s.length && !s[i + 1].isWhitespace()) closing(c.toString(), i + 1) else -1
                    val wordEnd = j > 0 && (j + 1 >= s.length || !s[j + 1].isLetterOrDigit())
                    if (j > 0 && wordEnd) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(s.substring(i + 1, j), linkColor, codeBackground)) }
                        i = j + 1
                    } else { append(c); i++ }
                }
                c == '[' -> {
                    val close = s.indexOf("](", i + 1)
                    val end = if (close > i) s.indexOf(')', close + 2) else -1
                    val url = if (end > close && close > i) s.substring(close + 2, end).trim() else ""
                    if (end > 0 && (url.startsWith("https://") || url.startsWith("http://"))) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                            append(inline(s.substring(i + 1, close), linkColor, codeBackground))
                        }
                        i = end + 1
                    } else { append(c); i++ }
                }
                else -> { append(c); i++ }
            }
        }
    }

    /** The plain text of [text] as the renderer shows it (tests; accessibility). */
    fun plain(text: String): String = blocks(text).joinToString("\n") { b ->
        when (b) {
            is Block.Para -> inline(b.text).text
            is Block.Bullet -> "• " + inline(b.text).text
            is Block.Numbered -> "${b.number}. " + inline(b.text).text
        }
    }
}

/** A Risi bubble's text with the [RisiMarkdown] subset. */
@Composable
fun RisiMarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = remember(text) { RisiMarkdown.blocks(text) }
    val link = MaterialTheme.colorScheme.primary
    val code = MaterialTheme.colorScheme.surfaceVariant
    // Consecutive paragraphs stay one Text ("\n\n" between them), so a plain answer renders exactly as before.
    val groups = remember(blocks) {
        val g = ArrayList<Any>()
        blocks.forEach { b ->
            val last = g.lastOrNull()
            if (b is RisiMarkdown.Block.Para && last is MutableList<*>) @Suppress("UNCHECKED_CAST") (last as MutableList<RisiMarkdown.Block.Para>).add(b)
            else if (b is RisiMarkdown.Block.Para) g.add(mutableListOf(b)) else g.add(b)
        }
        g
    }
    Column(modifier.testTag("risi_markdown"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { item ->
            @Suppress("UNCHECKED_CAST")
            when (val b = item) {
                is MutableList<*> -> Text(
                    buildAnnotatedString {
                        (b as List<RisiMarkdown.Block.Para>).forEachIndexed { i, p ->
                            if (i > 0) append("\n\n")
                            if (p.heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(RisiMarkdown.inline(p.text, link, code)) } else append(RisiMarkdown.inline(p.text, link, code))
                        }
                    },
                    style = style,
                )
                is RisiMarkdown.Block.Para -> Text(RisiMarkdown.inline(b.text, link, code), style = style)
                is RisiMarkdown.Block.Bullet -> Row(Modifier.padding(start = (b.level * 16).dp)) {
                    Text("•", style = style, modifier = Modifier.padding(end = 8.dp))
                    Text(RisiMarkdown.inline(b.text, link, code), style = style)
                }
                is RisiMarkdown.Block.Numbered -> Row(Modifier.padding(start = (b.level * 16).dp)) {
                    Text("${b.number}.", style = style, modifier = Modifier.padding(end = 8.dp))
                    Text(RisiMarkdown.inline(b.text, link, code), style = style)
                }
            }
        }
    }
}
