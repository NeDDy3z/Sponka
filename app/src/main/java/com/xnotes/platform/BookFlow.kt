package com.xnotes.platform

import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import java.util.Base64

/**
 * A book's reader HTML ([BookHtml]) turned into a note's flowing text, so the note reader lays it
 * out itself: paragraphs, headings, emphasis, links, lists and rules. A chapter starts a page. An
 * image stands on a page of its own, held by an empty paragraph whose index [Result.images] keys.
 */
object BookFlow {

    class Result(val paragraphs: List<Paragraph>, val images: Map<Int, ByteArray>)

    private val token = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|<!--[\\s\\S]*?-->|([^<]+)")
    private val blocks = setOf("p", "div", "li", "blockquote", "pre", "tr", "dt", "dd", "figure", "figcaption", "table", "ul", "ol", "dl", "body")
    private val skipped = setOf("script", "style", "head", "title", "svg")

    fun parse(html: String): Result {
        val out = mutableListOf<Paragraph>()
        val images = HashMap<Int, ByteArray>()
        var runs = mutableListOf<Run>()
        var heading = 0
        var list = ListKind.NONE
        var bold = 0
        var italic = 0
        var underline = 0
        var link: String? = null
        var skipping = 0
        var pendingBreak = false
        var chapters = 0
        // An ordered list's kind, by depth, so an item knows what marker it takes.
        val lists = ArrayDeque<ListKind>()

        fun style() = CharStyle(bold = bold > 0, italic = italic > 0, underline = underline > 0 || link != null, link = link)

        fun end() {
            val text = runs.joinToString("") { it.text }
            if (text.isBlank()) { runs = mutableListOf(); return }
            // Runs come in with the spaces of the source around them; the first and last are trimmed.
            runs.first().text = runs.first().text.trimStart()
            runs.last().text = runs.last().text.trimEnd()
            out += Paragraph(runs.filter { it.text.isNotEmpty() }.toMutableList(), list = list).apply {
                headingLevel = heading
                breakBefore = pendingBreak
            }
            pendingBreak = false
            runs = mutableListOf()
        }

        fun add(text: String) {
            val s = style()
            val last = runs.lastOrNull()
            if (last != null && last.style == s) last.text += text else runs += Run(text, s)
        }

        for (m in token.findAll(html)) {
            val text = m.groups[4]?.value
            if (text != null) {
                if (skipping > 0) continue
                val t = decode(text.replace(Regex("\\s+"), " "))
                if (t.isNotEmpty() && (runs.isNotEmpty() || t.isNotBlank())) add(t)
                continue
            }
            if (m.value.startsWith("<!--")) continue
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            if (tag in skipped) {
                if (!attrs.trimEnd().endsWith("/")) skipping = (skipping + if (closing) -1 else 1).coerceAtLeast(0)
                continue
            }
            if (skipping > 0) continue
            when (tag) {
                "section" -> if (!closing) {
                    end()
                    // Every chapter after the first starts a page.
                    if (attr(attrs, "class")?.contains("chapter") == true && chapters++ > 0) pendingBreak = true
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> { end(); heading = if (closing) 0 else tag[1].digitToInt() }
                "br" -> end()
                "hr" -> { end(); out += Paragraph().apply { rule = true } }
                "b", "strong" -> bold += if (closing) -1 else 1
                "i", "em", "cite" -> italic += if (closing) -1 else 1
                "u" -> underline += if (closing) -1 else 1
                "a" -> link = if (closing) null else attr(attrs, "href")?.takeIf { it.startsWith("http") || it.startsWith("mailto:") }
                "ul", "ol" -> {
                    end()
                    if (closing) lists.removeLastOrNull() else lists.addLast(if (tag == "ol") ListKind.ORDERED else ListKind.BULLET)
                    list = ListKind.NONE
                }
                "li" -> { end(); list = if (closing) ListKind.NONE else lists.lastOrNull() ?: ListKind.BULLET }
                "img" -> if (!closing) {
                    val bytes = attr(attrs, "src")?.let(::dataBytes)
                    if (bytes != null) {
                        end()
                        // The image's page: an empty paragraph opening it, and the text after it on the next.
                        images[out.size] = bytes
                        out += Paragraph(mutableListOf(Run(" "))).apply { breakBefore = true }
                        pendingBreak = true
                    }
                }
                else -> if (tag in blocks) end()
            }
            bold = bold.coerceAtLeast(0)
            italic = italic.coerceAtLeast(0)
            underline = underline.coerceAtLeast(0)
        }
        end()
        return Result(out, images)
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("\\s$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(attrs)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    private fun dataBytes(src: String): ByteArray? {
        if (!src.startsWith("data:")) return null
        val comma = src.indexOf(',').takeIf { it > 0 } ?: return null
        return runCatching { Base64.getMimeDecoder().decode(src.substring(comma + 1)) }.getOrNull()
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp|shy|mdash|ndash|hellip|laquo|raquo|lsquo|rsquo|ldquo|rdquo);")

    internal fun decode(s: String): String = if ('&' !in s) s else entity.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> "\u00A0"
            "shy" -> ""
            "mdash" -> "\u2014"
            "ndash" -> "\u2013"
            "hellip" -> "\u2026"
            "laquo" -> "\u00AB"
            "raquo" -> "\u00BB"
            "lsquo" -> "\u2018"
            "rsquo" -> "\u2019"
            "ldquo" -> "\u201C"
            "rdquo" -> "\u201D"
            else -> runCatching {
                val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                String(Character.toChars(code))
            }.getOrDefault(m.value)
        }
    }
}
