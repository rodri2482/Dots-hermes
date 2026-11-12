package app.dotshermes.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Markdown des réponses de l'agent, en deux temps : [parseMarkdown] découpe le texte en blocs
 * (titres, listes, citations, code, tableaux…), puis [inlineMarkdown] met en forme l'intérieur
 * de chaque bloc (gras, italique, barré, code, liens). Pur Kotlin, donc testable sans Android.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val depth: Int, val marker: String, val text: String, val checked: Boolean? = null)
    data class Bullets(val items: List<ListItem>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val HeadingLine = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val FenceLine = Regex("""^\s*(```|~~~)\s*([\w+#.-]*)\s*$""")
private val ListLine = Regex("""^(\s*)([-*+•]|\d{1,3}[.)])\s+(.*)$""")
private val TaskPrefix = Regex("""^\[([ xX])]\s+(.*)$""")
private val RuleLine = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val TableSeparator = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

/**
 * Le texte coupé autour de ses tableaux : (true, tableau) ou (false, le reste). Les tableaux dans un bloc de code
 * restent du code. Sert à dessiner les tableaux à part (cellules sur plusieurs lignes au lieu de « … »).
 */
fun splitTables(text: String): List<Pair<Boolean, String>> {
    val lines = text.lines()
    val out = ArrayList<Pair<Boolean, String>>()
    val plain = ArrayList<String>()
    fun flush() { if (plain.isNotEmpty()) { out += false to plain.joinToString("\n"); plain.clear() } }
    var fenced = false
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) fenced = !fenced
        if (!fenced && line.contains('|') && i + 1 < lines.size && TableSeparator.matches(lines[i + 1])) {
            flush()
            val table = arrayListOf(line, lines[i + 1])
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) table += lines[i++]
            out += true to table.joinToString("\n")
            continue
        }
        plain += line
        i++
    }
    flush()
    return out
}

fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").lines()
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    var i = 0

    fun flushParagraph() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        val fence = FenceLine.find(line)
        when {
            line.isBlank() -> flushParagraph()
            fence != null -> {
                flushParagraph()
                val marker = fence.groupValues[1]
                val code = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(marker)) code += lines[i++]
                blocks += MdBlock.Code(fence.groupValues[2], code.joinToString("\n").trimEnd())
            }
            HeadingLine.matches(line) -> {
                flushParagraph()
                val (hashes, title) = HeadingLine.find(line)!!.destructured
                blocks += MdBlock.Heading(hashes.length, title)
            }
            RuleLine.matches(line) && paragraph.isEmpty() -> blocks += MdBlock.Rule
            line.trimStart().startsWith(">") -> {
                flushParagraph()
                val quote = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) quote += lines[i++].trimStart().removePrefix(">").removePrefix(" ")
                blocks += MdBlock.Quote(quote.joinToString("\n"))
                continue
            }
            line.contains('|') && i + 1 < lines.size && TableSeparator.matches(lines[i + 1]) -> {
                flushParagraph()
                val header = cells(line)
                val rows = ArrayList<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += cells(lines[i++]).let { row ->
                    List(header.size) { c -> row.getOrElse(c) { "" } }
                }
                blocks += MdBlock.Table(header, rows)
                continue
            }
            ListLine.matches(line) -> {
                flushParagraph()
                val items = ArrayList<MdBlock.ListItem>()
                while (i < lines.size) {
                    val match = ListLine.find(lines[i])
                    if (match == null) {
                        // Ligne de continuation indentée : elle prolonge l'élément précédent.
                        if (lines[i].startsWith("  ") && lines[i].isNotBlank() && items.isNotEmpty()) {
                            val last = items.removeAt(items.lastIndex)
                            items += last.copy(text = last.text + " " + lines[i].trim())
                            i++
                            continue
                        }
                        break
                    }
                    val (indent, marker, body) = match.destructured
                    val task = TaskPrefix.find(body)
                    items += MdBlock.ListItem(
                        depth = indent.replace("\t", "    ").length / 2,
                        marker = if (marker.first().isDigit()) marker.trimEnd(')').trimEnd('.') + "." else "•",
                        text = task?.groupValues?.get(2) ?: body,
                        checked = task?.groupValues?.get(1)?.let { it.isNotBlank() },
                    )
                    i++
                }
                blocks += MdBlock.Bullets(items)
                continue
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trimEnd())
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

private fun cells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/** Couleurs du texte enrichi : encre du bloc, liens, fond du code en ligne. */
data class MdColors(val ink: Color, val link: Color, val codeBackground: Color)

fun inlineMarkdown(text: String, colors: MdColors): AnnotatedString = buildAnnotatedString { appendInline(text, colors) }

private fun AnnotatedString.Builder.appendInline(text: String, colors: MdColors) {
    var i = 0
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) append(plain.toString())
        plain.clear()
    }
    while (i < text.length) {
        val c = text[i]
        // Échappement : \* \_ \` … s'affichent tels quels.
        if (c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!~|>") {
            plain.append(text[i + 1]); i += 2; continue
        }
        if (c == '`') {
            val run = text.runLength(i, '`')
            val close = text.indexOf("`".repeat(run), i + run)
            if (close > 0) {
                flush()
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) {
                    append(text.substring(i + run, close).trim())
                }
                i = close + run; continue
            }
        }
        if (c == '[') {
            val link = parseLink(text, i)
            if (link != null) {
                flush()
                withLink(LinkAnnotation.Url(link.url, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)))) {
                    appendInline(link.label, colors)
                }
                i = link.end; continue
            }
        }
        if ((c == 'h' || c == 'w') && (i == 0 || !text[i - 1].isLetterOrDigit())) {
            val url = Regex("""^(https?://|www\.)[^\s<>]+""").find(text.substring(i))?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', '\'', '"')
            if (url != null && url.length > 7) {
                flush()
                val target = if (url.startsWith("www.")) "https://$url" else url
                withLink(LinkAnnotation.Url(target, TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)))) { append(url) }
                i += url.length; continue
            }
        }
        if (c == '~' && text.startsWith("~~", i)) {
            val close = text.indexOf("~~", i + 2)
            if (close > i + 2) {
                flush()
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(text.substring(i + 2, close), colors) }
                i = close + 2; continue
            }
        }
        if (c == '*' || c == '_') {
            val run = minOf(text.runLength(i, c), 3)
            val opensWord = i + run < text.length && !text[i + run].isWhitespace()
            // « snake_case » n'est pas de l'italique : _ doit ouvrir en début de mot.
            val boundary = c == '*' || i == 0 || !text[i - 1].isLetterOrDigit()
            val close = if (opensWord && boundary) findClosing(text, i + run, c, run) else -1
            if (close > 0) {
                flush()
                val style = when (run) {
                    1 -> SpanStyle(fontStyle = FontStyle.Italic)
                    2 -> SpanStyle(fontWeight = FontWeight.SemiBold)
                    else -> SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
                }
                withStyle(style) { appendInline(text.substring(i + run, close), colors) }
                i = close + run; continue
            }
        }
        plain.append(c)
        i++
    }
    flush()
}

private fun String.runLength(from: Int, ch: Char): Int {
    var n = 0
    while (from + n < length && this[from + n] == ch) n++
    return n
}

private fun findClosing(text: String, from: Int, ch: Char, run: Int): Int {
    val marker = ch.toString().repeat(run)
    var j = text.indexOf(marker, from)
    while (j > 0) {
        val beforeOk = !text[j - 1].isWhitespace()
        val afterOk = j + run >= text.length || text[j + run] != ch && (ch == '*' || !text[j + run].isLetterOrDigit())
        if (beforeOk && afterOk) return j
        j = text.indexOf(marker, j + 1)
    }
    return -1
}

private class ParsedLink(val label: String, val url: String, val end: Int)

private fun parseLink(text: String, start: Int): ParsedLink? {
    var depth = 0
    var j = start
    while (j < text.length) {
        when (text[j]) {
            '[' -> depth++
            ']' -> if (--depth == 0) break
        }
        j++
    }
    if (j >= text.length || j + 1 >= text.length || text[j + 1] != '(') return null
    val close = text.indexOf(')', j + 2)
    if (close < 0) return null
    val url = text.substring(j + 2, close).trim().substringBefore(' ')
    if (url.isEmpty()) return null
    return ParsedLink(text.substring(start + 1, j), url, close + 1)
}
