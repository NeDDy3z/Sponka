package com.xnotes.platform

import org.xmlpull.v1.XmlPullParser
import java.util.Base64

/**
 * The reader's other text and office kinds as HTML bodies: plain text, XML, RTF, OpenDocument
 * text (ODT) and the old binary PowerPoint (PPT), whose slide text is read out of its record stream.
 */
object OfficeHtml {

    private fun empty(labels: ReaderHtml.Labels) = "<p class=\"missing\">${ReaderHtml.esc(labels.empty)}</p>"

    // --- TXT ---

    fun text(source: String, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String {
        val text = source.removePrefix("\uFEFF")
        if (text.isBlank()) return empty(labels)
        return "<pre class=\"text\">${ReaderHtml.esc(text)}</pre>"
    }

    // --- TeX ---

    private val texToken = Regex("(%[^\\n]*)|(\\\\(?:[a-zA-Z@]+\\*?|.))|(\\$\\$?)")

    /** LaTeX source as written, its commands, maths delimiters and comments set apart. */
    fun tex(source: String, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String {
        val text = source.removePrefix("\uFEFF")
        if (text.isBlank()) return empty(labels)
        val out = StringBuilder("<pre class=\"code tex\">")
        var last = 0
        for (m in texToken.findAll(text)) {
            out.append(ReaderHtml.esc(text.substring(last, m.range.first)))
            val cls = when {
                m.groups[1] != null -> "x-c"
                m.groups[2] != null -> "x-t"
                else -> "x-a"
            }
            out.append("<span class=\"").append(cls).append("\">").append(ReaderHtml.esc(m.value)).append("</span>")
            last = m.range.last + 1
        }
        out.append(ReaderHtml.esc(text.substring(last)))
        return out.append("</pre>").toString()
    }

    // --- XML ---

    /** The file as written, its tags, attributes and comments set apart; a file on one line is laid out a tag a line. */
    fun xml(source: String, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String {
        var text = source.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return empty(labels)
        if (text.count { it == '\n' } < 2 && text.length > 200) text = indent(text)
        val out = StringBuilder("<pre class=\"code xml\">")
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('<', i)
            if (open < 0) { out.append(ReaderHtml.esc(text.substring(i))); break }
            out.append(ReaderHtml.esc(text.substring(i, open)))
            val comment = text.startsWith("<!--", open)
            val close = if (comment) text.indexOf("-->", open).let { if (it < 0) text.length else it + 3 }
            else text.indexOf('>', open).let { if (it < 0) text.length else it + 1 }
            val tag = text.substring(open, close)
            if (comment) out.append("<span class=\"x-c\">").append(ReaderHtml.esc(tag)).append("</span>")
            else out.append(xmlTag(tag))
            i = close
        }
        return out.append("</pre>").toString()
    }

    private val attrPart = Regex("([\\w:.-]+)(\\s*=\\s*)(\"[^\"]*\"|'[^']*')")

    private fun xmlTag(tag: String): String {
        val name = Regex("^</?[?!]?[\\w:.-]*").find(tag)?.value.orEmpty()
        val rest = tag.substring(name.length)
        val attrs = StringBuilder()
        var last = 0
        for (m in attrPart.findAll(rest)) {
            attrs.append(ReaderHtml.esc(rest.substring(last, m.range.first)))
            attrs.append("<span class=\"x-a\">").append(ReaderHtml.esc(m.groupValues[1])).append("</span>")
                .append(ReaderHtml.esc(m.groupValues[2]))
                .append("<span class=\"x-v\">").append(ReaderHtml.esc(m.groupValues[3])).append("</span>")
            last = m.range.last + 1
        }
        attrs.append(ReaderHtml.esc(rest.substring(last)))
        return "<span class=\"x-t\">${ReaderHtml.esc(name)}</span>$attrs"
    }

    /** A tag a line, nested two spaces deeper per level; text stays beside its tags. */
    internal fun indent(xml: String): String {
        val out = StringBuilder()
        var depth = 0
        val parts = Regex("(<[^>]+>)|([^<]+)").findAll(xml).map { it.value }.filter { it.isNotBlank() }.toList()
        var i = 0
        while (i < parts.size) {
            val part = parts[i].trim()
            val closing = part.startsWith("</")
            val selfContained = part.startsWith("<?") || part.startsWith("<!") || part.endsWith("/>")
            if (closing) depth = (depth - 1).coerceAtLeast(0)
            if (out.isNotEmpty()) out.append('\n')
            out.append("  ".repeat(depth)).append(part)
            // <a>text</a> stays on one line.
            if (!closing && !selfContained && part.startsWith("<") && i + 2 < parts.size && !parts[i + 1].startsWith("<") && parts[i + 2].startsWith("</")) {
                out.append(parts[i + 1].trim()).append(parts[i + 2].trim())
                i += 3
                continue
            }
            if (!closing && !selfContained && part.startsWith("<")) depth++
            i++
        }
        return out.toString()
    }

    // --- RTF ---

    private class RtfState(var bold: Boolean = false, var italic: Boolean = false, var underline: Boolean = false, var strike: Boolean = false, var skip: Boolean = false) {
        fun copy() = RtfState(bold, italic, underline, strike, skip)
    }

    /** Groups that hold no text of the document: tables of fonts, colours and styles, pictures, metadata. */
    private val rtfSkipped = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "headerl", "headerr", "headerf", "footer", "footerl",
        "footerr", "footerf", "listtable", "listoverridetable", "rsidtbl", "xmlnstbl", "themedata", "colorschememapping",
        "datastore", "latentstyles", "pgdsctbl", "object", "fldinst", "generator", "filetbl", "revtbl", "mmathPr", "operator",
        "author", "company", "title", "keywords", "comment", "doccomm", "nonshppict", "bkmkstart", "bkmkend",
    )

    /** RTF as paragraphs with their bold, italic, underlined and struck-through runs. */
    fun rtf(source: String, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String {
        if (!source.trimStart().startsWith("{\\rtf")) return text(source, labels)
        val paras = ArrayList<String>()
        val para = StringBuilder()
        val run = StringBuilder()
        var state = RtfState()
        var runState = state.copy()
        val stack = ArrayList<RtfState>()
        var charset = charset("windows-1252")
        var skipChars = 0
        var uc = 1
        val pendingBytes = java.io.ByteArrayOutputStream()

        fun flushBytes() {
            if (pendingBytes.size() == 0) return
            val s = String(pendingBytes.toByteArray(), charset)
            pendingBytes.reset()
            if (!state.skip) run.append(s)
        }
        fun flushRun() {
            flushBytes()
            if (run.isEmpty()) return
            var s = ReaderHtml.esc(run.toString())
            if (runState.bold) s = "<b>$s</b>"
            if (runState.italic) s = "<i>$s</i>"
            if (runState.underline) s = "<u>$s</u>"
            if (runState.strike) s = "<s>$s</s>"
            para.append(s)
            run.setLength(0)
        }
        fun format(change: RtfState.() -> Unit) {
            flushRun()
            state.change()
            runState = state.copy()
        }
        fun endPara() {
            flushRun()
            paras.add(para.toString())
            para.setLength(0)
        }
        fun literal(s: String) {
            if (skipChars > 0) { skipChars--; return }
            flushBytes()
            if (!state.skip) run.append(s)
        }

        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when (c) {
                '{' -> { flushBytes(); stack.add(state.copy()); i++ }
                '}' -> {
                    flushRun()
                    state = stack.removeLastOrNull() ?: state
                    runState = state.copy()
                    i++
                }
                '\\' -> {
                    if (i + 1 >= n) { i++; continue }
                    val next = source[i + 1]
                    when {
                        next == '\'' -> {
                            val hex = source.substring(i + 2, minOf(i + 4, n)).toIntOrNull(16)
                            if (skipChars > 0) skipChars-- else if (hex != null) pendingBytes.write(hex)
                            i += 4
                        }
                        next == '*' -> { state.skip = true; i += 2 }
                        next == '{' || next == '}' || next == '\\' -> { literal(next.toString()); i += 2 }
                        next == '~' -> { literal("\u00A0"); i += 2 }
                        next == '\n' || next == '\r' -> { endPara(); i += 2 }
                        !next.isLetter() -> i += 2
                        else -> {
                            var j = i + 1
                            while (j < n && source[j].isLetter()) j++
                            val word = source.substring(i + 1, j)
                            var k = j
                            if (k < n && (source[k] == '-' || source[k].isDigit())) {
                                k++
                                while (k < n && source[k].isDigit()) k++
                            }
                            val param = source.substring(j, k).toIntOrNull()
                            if (k < n && source[k] == ' ') k++
                            i = k
                            when (word) {
                                in rtfSkipped -> state.skip = true
                                "ansicpg" -> param?.let { cp -> runCatching { charset = charset("windows-$cp") } }
                                "uc" -> uc = param ?: 1
                                "u" -> if (param != null) {
                                    literal(((if (param < 0) param + 65536 else param)).toChar().toString())
                                    skipChars = uc
                                }
                                "par" -> if (!state.skip) endPara()
                                "line" -> if (!state.skip) { flushRun(); para.append("<br>") }
                                "tab" -> literal("\t")
                                "cell" -> literal("\t")
                                "row" -> if (!state.skip) endPara()
                                "emdash" -> literal("\u2014")
                                "endash" -> literal("\u2013")
                                "bullet" -> literal("\u2022")
                                "lquote" -> literal("\u2018")
                                "rquote" -> literal("\u2019")
                                "ldblquote" -> literal("\u201C")
                                "rdblquote" -> literal("\u201D")
                                "plain" -> format { bold = false; italic = false; underline = false; strike = false }
                                "b" -> format { bold = param != 0 }
                                "i" -> format { italic = param != 0 }
                                "ul" -> format { underline = param != 0 }
                                "ulnone" -> format { underline = false }
                                "strike" -> format { strike = param != 0 }
                            }
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> { literal(c.toString()); i++ }
            }
        }
        endPara()
        while (paras.isNotEmpty() && paras.last().isBlank()) paras.removeAt(paras.lastIndex)
        if (paras.all { it.isBlank() }) return empty(labels)
        return paras.joinToString("") { if (it.isBlank()) "<p class=\"gap\"></p>" else "<p style=\"white-space:pre-wrap\">$it</p>" }
    }

    // --- ODT ---

    private class Fmt(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean)

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    private fun attr(p: XmlPullParser, localName: String): String? {
        for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == localName) return p.getAttributeValue(i)
        return null
    }

    /** Each named style's character formatting, from the content's automatic styles and the document's own. */
    private fun odtStyles(bytes: ByteArray?, newParser: () -> XmlPullParser, into: HashMap<String, Fmt>) {
        if (bytes == null) return
        val p = newParser().apply { setInput(bytes.inputStream(), "UTF-8") }
        var name: String? = null
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (local(p.name)) {
                "style" -> name = attr(p, "name")
                "text-properties" -> name?.let { n ->
                    val bold = attr(p, "font-weight").let { it == "bold" || (it?.toIntOrNull() ?: 0) >= 600 }
                    val italic = attr(p, "font-style") == "italic"
                    val underline = attr(p, "text-underline-style").let { it != null && it != "none" }
                    val strike = attr(p, "text-line-through-style").let { it != null && it != "none" }
                    if (bold || italic || underline || strike) into[n] = Fmt(bold, italic, underline, strike)
                }
            } else if (e == XmlPullParser.END_TAG && local(p.name) == "style") name = null
            e = p.next()
        }
    }

    /** An OpenDocument file's look as far as the reader draws it: each style's settings, its fonts and its page. */
    private class OdtLooks {
        val props = HashMap<String, HashMap<String, String>>()
        val parents = HashMap<String, String>()
        val fonts = HashMap<String, String>()
        val layouts = HashMap<String, List<String?>>()
        var master: String? = null

        fun read(bytes: ByteArray?, newParser: () -> XmlPullParser) {
            if (bytes == null) return
            val p = newParser().apply { setInput(bytes.inputStream(), "UTF-8") }
            var style: HashMap<String, String>? = null
            var layout: String? = null
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) when (local(p.name)) {
                    "style" -> {
                        val name = attr(p, "name") ?: ""
                        attr(p, "parent-style-name")?.let { parents[name] = it }
                        style = props.getOrPut(name) { HashMap() }
                    }
                    "default-style" -> style = if (attr(p, "family") == "paragraph") props.getOrPut(DEFAULT) { HashMap() } else null
                    "paragraph-properties", "text-properties" -> style?.let { s -> for (i in 0 until p.attributeCount) s[local(p.getAttributeName(i))] = p.getAttributeValue(i) }
                    "font-face" -> attr(p, "name")?.let { n ->
                        val generic = when (attr(p, "font-family-generic")) { "roman" -> "serif"; "modern" -> "monospace"; else -> "sans-serif" }
                        fonts[n] = "'${(attr(p, "font-family") ?: n).replace("'", "")}',$generic"
                    }
                    "page-layout" -> layout = attr(p, "name")
                    "page-layout-properties" -> layout?.let { l ->
                        layouts[l] = listOf("page-width", "page-height", "margin-top", "margin-right", "margin-bottom", "margin-left").map { attr(p, it) }
                    }
                    "master-page" -> if (master == null || attr(p, "name") == "Standard") master = attr(p, "page-layout-name")
                } else if (e == XmlPullParser.END_TAG && local(p.name).let { it == "style" || it == "default-style" }) style = null
                e = p.next()
            }
        }

        /** [name]'s settings over the styles it builds on, as CSS. */
        fun css(name: String?): String {
            if (name == null) return ""
            val chain = ArrayList<String>()
            var n: String? = name
            while (n != null && chain.size < 10 && n !in chain) { chain.add(n); n = parents[n] }
            val all = HashMap<String, String>()
            for (s in chain.asReversed()) props[s]?.let(all::putAll)
            return cssOf(all)
        }

        fun cssOf(all: Map<String, String>): String {
            val css = StringBuilder()
            fun put(k: String, v: String?) { if (v != null) css.append(k).append(':').append(v).append(';') }
            put("font-size", all["font-size"])
            put("color", all["color"])
            put("font-family", all["font-name"]?.let { fonts[it] ?: "'$it',sans-serif" })
            put("text-align", when (all["text-align"]) { null -> null; "start", "left" -> "left"; "end", "right" -> "right"; "center" -> "center"; "justify" -> "justify"; else -> null })
            for (k in listOf("margin-top", "margin-bottom", "margin-left", "margin-right", "text-indent")) put(k, all[k])
            put("line-height", all["line-height"]?.takeIf { it != "normal" })
            put("background", all["background-color"]?.takeIf { it != "transparent" })
            all["font-weight"]?.let { put("font-weight", if (it == "bold" || (it.toIntOrNull() ?: 0) >= 600) "bold" else "normal") }
            all["font-style"]?.let { put("font-style", if (it == "italic") "italic" else "normal") }
            return css.toString()
        }

        /** The base look every paragraph inherits: the default paragraph style's text settings. */
        fun base(): String = cssOf(props[DEFAULT].orEmpty().filterKeys { !it.startsWith("margin") && it != "text-indent" })

        companion object { const val DEFAULT = "\u0000default" }
    }

    fun odt(zip: ReaderHtml.ZipRead, newParser: () -> XmlPullParser, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String {
        val content = zip.read("content.xml") ?: throw IllegalArgumentException("not an OpenDocument file")
        val styles = HashMap<String, Fmt>()
        odtStyles(zip.read("styles.xml"), newParser, styles)
        odtStyles(content, newParser, styles)
        val looks = OdtLooks().apply { read(zip.read("styles.xml"), newParser); read(content, newParser) }
        var spent = 0L
        val out = StringBuilder()
        val p = newParser().apply { setInput(content.inputStream(), "UTF-8") }
        val closers = ArrayList<String>() // what each open element closes with, so spans and paragraphs end where they began
        var inBody = false
        var skip = 0
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> {
                    val name = local(p.name)
                    if (skip > 0) { skip++; e = p.next(); continue }
                    if (name == "text" && p.name.startsWith("office:")) inBody = true
                    if (!inBody) { e = p.next(); continue }
                    val fmt = attr(p, "style-name")?.let(styles::get)
                    val look = looks.css(attr(p, "style-name")).let { if (it.isEmpty()) "" else " style=\"${ReaderHtml.esc(it)}\"" }
                    fun wrap(open: String, close: String) {
                        var o = open
                        var c = close
                        if (fmt != null) {
                            if (fmt.bold) { o += "<b>"; c = "</b>$c" }
                            if (fmt.italic) { o += "<i>"; c = "</i>$c" }
                            if (fmt.underline) { o += "<u>"; c = "</u>$c" }
                            if (fmt.strike) { o += "<s>"; c = "</s>$c" }
                        }
                        out.append(o)
                        closers.add(c)
                    }
                    when (name) {
                        "h" -> { val level = (attr(p, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6); wrap("<h$level$look>", "</h$level>") }
                        "p" -> wrap("<p$look>", "</p>")
                        "span" -> if (look.isEmpty()) wrap("", "") else wrap("<span$look>", "</span>")
                        "a" -> wrap("<a href=\"${ReaderHtml.esc(attr(p, "href").orEmpty())}\">", "</a>")
                        "list" -> wrap("<ul>", "</ul>")
                        "list-item", "list-header" -> wrap("<li>", "</li>")
                        "table" -> wrap("<table>", "</table>")
                        "table-row" -> wrap("<tr>", "</tr>")
                        "table-cell" -> {
                            val span = attr(p, "number-columns-spanned")?.toIntOrNull()?.takeIf { it > 1 }
                            wrap(if (span != null) "<td colspan=\"$span\">" else "<td>", "</td>")
                        }
                        "s" -> { out.append("&nbsp;".repeat((attr(p, "c")?.toIntOrNull() ?: 1).coerceIn(1, 200))); closers.add("") }
                        "tab" -> { out.append("&emsp;"); closers.add("") }
                        "line-break" -> { out.append("<br>"); closers.add("") }
                        "image" -> {
                            val href = attr(p, "href")
                            val bytes = href?.takeIf { !it.contains("://") }?.let { zip.read(it.removePrefix("./")) }
                            val mime = bytes?.let(::sniff)
                            if (bytes != null && mime != null && spent + bytes.size <= MAX_IMAGE_TOTAL) {
                                spent += bytes.size
                                out.append("<img src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}\" alt=\"\">")
                            }
                            closers.add("")
                        }
                        "note", "annotation", "tracked-changes", "sequence-decls", "forms" -> skip = 1
                        else -> closers.add("")
                    }
                }
                XmlPullParser.TEXT -> if (inBody && skip == 0) out.append(ReaderHtml.esc(p.text))
                XmlPullParser.END_TAG -> {
                    if (skip > 0) skip--
                    else if (inBody) {
                        if (local(p.name) == "text" && p.name.startsWith("office:")) inBody = false
                        else closers.removeLastOrNull()?.let(out::append)
                    }
                }
            }
            e = p.next()
        }
        if (out.isBlank()) return empty(labels)
        val page = looks.master?.let { looks.layouts[it] }
        fun cm(i: Int, fallback: Double) = ReaderHtml.odfCm(page?.getOrNull(i))?.takeIf { it >= 0 } ?: fallback
        return ReaderHtml.docPage(cm(0, 21.0) to cm(1, 29.7), listOf(cm(2, 2.0), cm(3, 2.0), cm(4, 2.0), cm(5, 2.0)), looks.base(), out.toString())
    }

    private const val MAX_IMAGE_TOTAL = 40L * 1024 * 1024

    private fun sniff(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
        b.size > 12 && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size > 5 && String(b, 0, minOf(b.size, 256), Charsets.ISO_8859_1).contains("<svg") -> "image/svg+xml"
        else -> null
    }

    // --- PPT ---

    private const val SLIDE = 0x03EE
    private const val SLIDE_LIST_WITH_TEXT = 0x0FF0
    private const val SLIDE_PERSIST = 0x03F3
    private const val TEXT_HEADER = 0x0F9F
    private const val TEXT_CHARS = 0x0FA0
    private const val TEXT_BYTES = 0x0FA8

    private class PptText(val title: Boolean, val text: String)

    /** The old binary PowerPoint: each slide's title and body text, in order. Layout and pictures are not read. */
    fun ppt(bytes: ByteArray, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String {
        val stream = Cfb(bytes).stream("PowerPoint Document") ?: throw IllegalArgumentException("not a PowerPoint file")
        // Slide text lives in the slide list, one run per slide; files saved by newer versions keep it in each slide instead.
        val listed = ArrayList<MutableList<PptText>>()
        val drawn = ArrayList<MutableList<PptText>>()
        var textType = -1

        fun walk(from: Int, to: Int, inList: Boolean, slide: MutableList<PptText>?) {
            var at = from
            while (at + 8 <= to) {
                val verInst = u16le(stream, at)
                val type = u16le(stream, at + 2)
                val len = u32le(stream, at + 4)
                val body = at + 8
                val end = (body.toLong() + len).coerceAtMost(to.toLong()).toInt()
                if (len < 0 || end < body) return
                val container = verInst and 0xF == 0xF
                when {
                    type == SLIDE_LIST_WITH_TEXT && container -> if (verInst shr 4 == 0) walk(body, end, true, null)
                    type == SLIDE && container -> drawn.add(ArrayList<PptText>().also { walk(body, end, false, it) })
                    container -> walk(body, end, inList, slide)
                    type == SLIDE_PERSIST && inList -> listed.add(ArrayList())
                    type == TEXT_HEADER -> textType = u32le(stream, body)
                    type == TEXT_CHARS || type == TEXT_BYTES -> {
                        val text = if (type == TEXT_CHARS) String(stream, body, end - body, Charsets.UTF_16LE) else String(stream, body, end - body, Charsets.ISO_8859_1)
                        val target = if (inList) listed.lastOrNull() else slide
                        target?.add(PptText(textType == 0 || textType == 6, text))
                        textType = -1
                    }
                }
                at = end
            }
        }
        walk(0, stream.size, false, null)
        val slides = if (listed.any { it.isNotEmpty() }) listed else drawn.filter { it.isNotEmpty() }
        if (slides.isEmpty()) return empty(labels)
        val out = StringBuilder()
        slides.forEachIndexed { i, texts ->
            out.append("<section class=\"slide\"><div class=\"slide-no\">").append(ReaderHtml.esc(labels.slide(i + 1, slides.size))).append("</div>")
            for (t in texts.sortedBy { !it.title }) {
                val lines = t.text.split('\r').map { ReaderHtml.esc(it).replace("\u000B", "<br>") }.filter { it.isNotBlank() }
                if (lines.isEmpty()) continue
                if (t.title) out.append("<h2>").append(lines.joinToString("<br>")).append("</h2>")
                else for (line in lines) out.append("<p>").append(line).append("</p>")
            }
            out.append("</section>")
        }
        return out.toString()
    }

    /** An OLE compound file (the container of the old Office formats), enough to read one stream by name. */
    internal class Cfb(private val b: ByteArray) {
        private val sectorSize: Int
        private val fat: IntArray

        init {
            val magic = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            if (b.size < 512 || !b.copyOfRange(0, 8).contentEquals(magic)) throw IllegalArgumentException("not a compound file")
            sectorSize = 1 shl u16le(b, 0x1E).coerceIn(7, 16)
            val fatSectors = ArrayList<Int>()
            for (k in 0 until 109) u32le(b, 0x4C + k * 4).takeIf { it >= 0 }?.let(fatSectors::add)
            var difat = u32le(b, 0x44)
            var guard = 0
            while (difat >= 0 && guard++ < 10_000) {
                val at = offsetOf(difat)
                if (at + sectorSize > b.size) break
                for (k in 0 until sectorSize / 4 - 1) u32le(b, at + k * 4).takeIf { it >= 0 }?.let(fatSectors::add)
                difat = u32le(b, at + sectorSize - 4)
            }
            val entries = ArrayList<Int>()
            for (s in fatSectors) {
                val at = offsetOf(s)
                if (at + sectorSize > b.size) continue
                for (k in 0 until sectorSize / 4) entries.add(u32le(b, at + k * 4))
            }
            fat = entries.toIntArray()
        }

        private fun offsetOf(sector: Int) = (sector + 1) * sectorSize

        private fun chain(start: Int, limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s >= 0 && s < fat.size && out.size() < limit && guard++ < fat.size) {
                val at = offsetOf(s)
                if (at >= b.size) break
                out.write(b, at, minOf(sectorSize, b.size - at))
                s = fat[s]
            }
            return out.toByteArray()
        }

        /** The stream called [name], when it is big enough to sit in the main sectors (a slide deck always is). */
        fun stream(name: String): ByteArray? {
            val dir = chain(u32le(b, 0x30), Long.MAX_VALUE)
            val cutoff = u32le(b, 0x38)
            var at = 0
            while (at + 128 <= dir.size) {
                val nameLen = u16le(dir, at + 64)
                val entryName = if (nameLen in 2..64) String(dir, at, nameLen - 2, Charsets.UTF_16LE) else ""
                if (entryName == name && dir[at + 66].toInt() == 2) {
                    val size = u32le(dir, at + 120).toLong() and 0xFFFFFFFFL
                    if (size < cutoff) return null
                    return chain(u32le(dir, at + 116), size).copyOf(size.toInt())
                }
                at += 128
            }
            return null
        }
    }

    private fun u16le(b: ByteArray, at: Int): Int = if (at + 2 > b.size) 0 else (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun u32le(b: ByteArray, at: Int): Int = if (at + 4 > b.size) -1 else u16le(b, at) or (u16le(b, at + 2) shl 16)
}
