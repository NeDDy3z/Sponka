package com.xnotes.platform

import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * E-books as one HTML body for the reader: EPUB chapters in reading order, FictionBook (FB2) and
 * unprotected Mobipocket / Kindle files (MOBI, PRC, AZW, AZW3). The book's own stylesheets and
 * inline styles are left out so its text takes the reader's colours in either theme.
 */
object BookHtml {

    private const val MAX_IMAGE_BYTES = 6 * 1024 * 1024

    /** The EXTH record holding a Mobipocket book's cover, as an offset into its images. */
    private const val EXTH_COVER = 201
    private const val MAX_IMAGE_TOTAL = 60 * 1024 * 1024

    // --- EPUB ---

    /** [imageUrl], when given, turns an image's path in the book into a URL the reader serves, instead of inlining its data. */
    fun epub(zip: ReaderHtml.ZipRead, labels: ReaderHtml.Labels = ReaderHtml.Labels(), imageUrl: ((String) -> String)? = null): String {
        val container = zip.read("META-INF/container.xml")?.let { String(it) } ?: throw IllegalArgumentException("not an EPUB")
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: throw IllegalArgumentException("no package")
        val opf = zip.read(opfPath)?.let { String(it) } ?: throw IllegalArgumentException("no package")
        val base = opfPath.substringBeforeLast('/', "")
        val manifest = HashMap<String, String>()
        for (m in Regex("<(?:\\w+:)?item\\b[^>]*>").findAll(opf)) {
            val id = attrOf(m.value, "id") ?: continue
            val href = attrOf(m.value, "href") ?: continue
            manifest[id] = ReaderHtml.resolvePath(base, decode(href))
        }
        val spine = Regex("<(?:\\w+:)?itemref\\b[^>]*>").findAll(opf)
            .filter { attrOf(it.value, "linear") != "no" }
            .mapNotNull { attrOf(it.value, "idref")?.let(manifest::get) }
            .toList()
        if (spine.isEmpty()) return "<p class=\"missing\">${ReaderHtml.esc(labels.empty)}</p>"
        val chapters = spine.withIndex().associate { (i, path) -> path to "ch$i" }
        val images = Images(labels)
        fun image(path: String, alt: String = "") = imageUrl?.let { "<img src=\"${it(path)}\" alt=\"${ReaderHtml.esc(alt)}\">" } ?: images.tag(zip, path, alt)
        val out = StringBuilder()
        for ((i, path) in spine.withIndex()) {
            val bytes = zip.read(path) ?: continue
            val dir = path.substringBeforeLast('/', "")
            var body = clean(bodyOf(String(bytes)))
            body = svgImage.replace(body) { m ->
                attrOf(m.value, "href")?.let { image(ReaderHtml.resolvePath(dir, decode(it))) } ?: ""
            }
            body = imgTag.replace(body) { m ->
                val src = attrOf(m.value, "src") ?: return@replace ""
                if (src.startsWith("data:")) return@replace m.value
                image(ReaderHtml.resolvePath(dir, decode(src)), attrOf(m.value, "alt").orEmpty())
            }
            body = hrefAttr.replace(body) { m ->
                val href = m.groupValues[1]
                if (href.startsWith("#") || href.contains("://") || href.startsWith("mailto:")) return@replace m.value
                val target = ReaderHtml.resolvePath(dir, decode(href.substringBefore('#')))
                val anchor = href.substringAfter('#', "").ifEmpty { chapters[target] ?: return@replace " href=\"#\"" }
                " href=\"#$anchor\""
            }
            out.append("<section class=\"chapter\" id=\"ch").append(i).append("\">").append(body).append("</section>")
        }
        return out.toString()
    }

    /** An EPUB's cover: the package's cover-image item, the image its cover meta names, or an image called cover; null when none. */
    fun epubCover(zip: ReaderHtml.ZipRead): ByteArray? {
        val container = zip.read("META-INF/container.xml")?.let { String(it) } ?: return null
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return null
        val opf = zip.read(opfPath)?.let { String(it) } ?: return null
        val items = Regex("<(?:\\w+:)?item\\b[^>]*>").findAll(opf).map { it.value }.filter { attrOf(it, "media-type").orEmpty().startsWith("image/") }.toList()
        val named = Regex("<(?:\\w+:)?meta\\b[^>]*>").findAll(opf).map { it.value }.firstOrNull { attrOf(it, "name") == "cover" }?.let { attrOf(it, "content") }
        val item = items.firstOrNull { attrOf(it, "properties").orEmpty().split(' ').contains("cover-image") }
            ?: named?.let { id -> items.firstOrNull { attrOf(it, "id") == id } }
            ?: items.firstOrNull { (attrOf(it, "id").orEmpty() + attrOf(it, "href").orEmpty()).contains("cover", ignoreCase = true) }
            ?: return null
        val href = attrOf(item, "href") ?: return null
        return zip.read(ReaderHtml.resolvePath(opfPath.substringBeforeLast('/', ""), decode(href)))
    }

    // --- FB2 ---

    /** A FictionBook's cover: the binary its coverpage image points at; null when none. */
    fun fb2Cover(bytes: ByteArray): ByteArray? {
        // Tags and base64 are ASCII, so any of FictionBook's encodings reads the same here.
        val text = String(bytes, Charsets.ISO_8859_1)
        val cover = Regex("<(?:\\w+:)?coverpage\\b[\\s\\S]*?</(?:\\w+:)?coverpage>").find(text)?.value ?: return null
        val id = Regex("href\\s*=\\s*[\"']#([^\"']+)").find(cover)?.groupValues?.get(1) ?: return null
        val binary = Regex("<(?:\\w+:)?binary\\b[^>]*>").findAll(text).firstOrNull { attrOf(it.value, "id") == id } ?: return null
        val end = text.indexOf('<', binary.range.last + 1).takeIf { it >= 0 } ?: return null
        return runCatching { Base64.getMimeDecoder().decode(text.substring(binary.range.last + 1, end).trim()) }.getOrNull()
    }

    /** A FictionBook, its title and cover first; its images come from the base64 binaries at the end of the file. */
    fun fb2(bytes: ByteArray, newParser: () -> XmlPullParser, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String {
        val binaries = HashMap<String, Pair<String, String>>()
        run {
            val p = newParser().apply { setInput(bytes.inputStream(), null) }
            var id: String? = null
            var type = ""
            val data = StringBuilder()
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> if (local(p.name) == "binary") {
                        id = attr(p, "id"); type = attr(p, "content-type").orEmpty(); data.setLength(0)
                    }
                    XmlPullParser.TEXT -> if (id != null) data.append(p.text)
                    XmlPullParser.END_TAG -> if (local(p.name) == "binary") {
                        id?.let { binaries[it] = type to data.toString().filterNot(Char::isWhitespace) }
                        id = null
                    }
                }
                e = p.next()
            }
        }
        var spent = 0L
        fun image(href: String?): String {
            val (type, data) = href?.removePrefix("#")?.let(binaries::get) ?: return ""
            val size = data.length * 3L / 4
            if (size > MAX_IMAGE_BYTES || spent + size > MAX_IMAGE_TOTAL) return "<span class=\"missing\">[${ReaderHtml.esc(labels.image)}]</span>"
            spent += size
            return "<img src=\"data:${type.ifEmpty { "image/jpeg" }};base64,$data\" alt=\"\">"
        }
        val out = StringBuilder()
        val p = newParser().apply { setInput(bytes.inputStream(), null) }
        var inDescription = false
        var inBookTitle = false
        var inCover = false
        var inBinary = false
        var sections = 0
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            val name = if (e == XmlPullParser.START_TAG || e == XmlPullParser.END_TAG) local(p.name) else ""
            when (e) {
                XmlPullParser.START_TAG -> when {
                    name == "description" -> inDescription = true
                    name == "binary" -> inBinary = true
                    inDescription -> when (name) {
                        "book-title" -> { inBookTitle = true; out.append("<h1>") }
                        "coverpage" -> inCover = true
                        "image" -> if (inCover) out.append(image(attr(p, "href")))
                    }
                    else -> when (name) {
                        "body" -> if (attr(p, "name") == "notes") out.append("<hr>")
                        "section" -> {
                            sections++
                            // A section's id is what its notes link to.
                            val id = attr(p, "id")
                            out.append(if (id == null) "<section>" else "<section id=\"${ReaderHtml.esc(id)}\">")
                        }
                        "title" -> out.append("<h").append((sections + 1).coerceIn(2, 6)).append(">")
                        "subtitle" -> out.append("<h4>")
                        "p", "stanza" -> out.append("<p>")
                        "text-author" -> out.append("<p class=\"subtitle\">")
                        "epigraph", "cite" -> out.append("<blockquote>")
                        "poem" -> out.append("<div class=\"poem\">")
                        "emphasis" -> out.append("<i>")
                        "strong" -> out.append("<b>")
                        "strikethrough" -> out.append("<s>")
                        "sub", "sup", "code", "table", "tr", "td", "th" -> out.append("<").append(name).append(">")
                        "empty-line" -> out.append("<p class=\"gap\"></p>")
                        "image" -> out.append(image(attr(p, "href")))
                        "a" -> {
                            val href = attr(p, "href").orEmpty()
                            out.append("<a href=\"").append(ReaderHtml.esc(href)).append("\">")
                        }
                    }
                }
                XmlPullParser.TEXT -> if (!inBinary && (!inDescription || inBookTitle)) out.append(ReaderHtml.esc(p.text))
                XmlPullParser.END_TAG -> when {
                    name == "description" -> inDescription = false
                    name == "binary" -> inBinary = false
                    inDescription -> when (name) {
                        "book-title" -> { inBookTitle = false; out.append("</h1>") }
                        "coverpage" -> inCover = false
                    }
                    else -> when (name) {
                        "section" -> { sections--; out.append("</section>") }
                        "title" -> out.append("</h").append((sections + 1).coerceIn(2, 6)).append(">")
                        "subtitle" -> out.append("</h4>")
                        "p", "stanza", "text-author" -> out.append("</p>")
                        "v" -> out.append("<br>")
                        "epigraph", "cite" -> out.append("</blockquote>")
                        "poem" -> out.append("</div>")
                        "emphasis" -> out.append("</i>")
                        "strong" -> out.append("</b>")
                        "strikethrough" -> out.append("</s>")
                        "sub", "sup", "code", "table", "tr", "td", "th", "a" -> out.append("</").append(name).append(">")
                    }
                }
            }
            e = p.next()
        }
        return out.toString().ifBlank { "<p class=\"missing\">${ReaderHtml.esc(labels.empty)}</p>" }
    }

    // --- MOBI / AZW / AZW3 ---

    class Unsupported(message: String) : IllegalArgumentException(message)

    /** A Mobipocket or Kindle book with no DRM, PalmDOC compressed or not. */
    fun mobi(bytes: ByteArray, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String {
        val book = MobiBook(bytes)
        val images = Images(labels)
        var html = clean(bodyOf(book.text()))
        html = html.replace(Regex("<mbp:pagebreak[^>]*>", RegexOption.IGNORE_CASE), "<hr>")
        html = imgTag.replace(html) { m ->
            val index = attrOf(m.value, "recindex")?.toIntOrNull()
                ?: attrOf(m.value, "src")?.let { kindleEmbed.find(it) }?.groupValues?.get(1)?.toIntOrNull(32)
            index?.let { book.image(it) }?.let { images.tag(it) } ?: ""
        }
        return html.ifBlank { "<p class=\"missing\">${ReaderHtml.esc(labels.empty)}</p>" }
    }

    /** A Mobipocket book's cover: the image its EXTH cover offset names; null when none or unreadable. */
    fun mobiCover(bytes: ByteArray): ByteArray? = runCatching { MobiBook(bytes).cover() }.getOrNull()

    /** The PalmDB records of a Mobipocket file and what its first record says about them. */
    internal class MobiBook(private val bytes: ByteArray) {
        private val offsets: IntArray
        private val compression: Int
        private val textRecords: Int
        private val utf8: Boolean
        private val extraFlags: Int
        private val firstImage: Int

        init {
            if (bytes.size < 78 + 8) throw IllegalArgumentException("not a Mobipocket file")
            val n = u16(bytes, 76)
            if (n == 0 || bytes.size < 78 + 8 * n) throw IllegalArgumentException("not a Mobipocket file")
            offsets = IntArray(n) { u32(bytes, 78 + it * 8) }
            val r0 = record(0)
            compression = u16(r0, 0)
            textRecords = u16(r0, 8)
            if (u16(r0, 12) != 0) throw Unsupported("encrypted")
            if (compression != 1 && compression != 2) throw Unsupported("compression $compression")
            val mobi = r0.size >= 0x20 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
            val headerLength = if (mobi) u32(r0, 20) else 0
            utf8 = !mobi || u32(r0, 28) == 65001
            firstImage = if (mobi && r0.size >= 0x70) u32(r0, 0x6C) else -1
            extraFlags = if (mobi && headerLength >= 0xE4 && r0.size >= 0xF4) u16(r0, 0xF2) else 0
        }

        fun record(i: Int): ByteArray {
            val start = offsets[i].coerceIn(0, bytes.size)
            val end = (if (i + 1 < offsets.size) offsets[i + 1] else bytes.size).coerceIn(start, bytes.size)
            return bytes.copyOfRange(start, end)
        }

        fun text(): String {
            val out = ByteArrayOutputStream()
            for (i in 1..textRecords.coerceAtMost(offsets.size - 1)) {
                val rec = record(i)
                val body = rec.copyOf((rec.size - trailing(rec)).coerceAtLeast(0))
                out.write(if (compression == 2) palmDoc(body) else body)
            }
            return String(out.toByteArray(), if (utf8) Charsets.UTF_8 else charset("windows-1252"))
        }

        /** The image record EXTH entry 201 points at, counted from the first image; null without one. */
        fun cover(): ByteArray? {
            val r0 = record(0)
            if (r0.size < 0x84 || String(r0, 16, 4, Charsets.ISO_8859_1) != "MOBI" || u32(r0, 0x80) and 0x40 == 0) return null
            val exth = 16 + u32(r0, 20)
            if (exth + 12 > r0.size || String(r0, exth, 4, Charsets.ISO_8859_1) != "EXTH") return null
            var at = exth + 12
            for (n in 0 until u32(r0, exth + 8)) {
                if (at + 8 > r0.size) break
                val length = u32(r0, at + 4)
                if (u32(r0, at) == EXTH_COVER && length >= 12) return image(u32(r0, at + 8) + 1)
                if (length < 8) break
                at += length
            }
            return null
        }

        /** The [index]th image (from 1), as an image tag counts them from the first image record. */
        fun image(index: Int): ByteArray? {
            val i = firstImage + index - 1
            return if (firstImage < 0 || index < 1 || i >= offsets.size) null else record(i)
        }

        /** How many bytes at the end of a text record are not text: the entries [extraFlags] says follow it. */
        private fun trailing(rec: ByteArray): Int {
            var size = 0
            var flags = extraFlags shr 1
            while (flags != 0) {
                if (flags and 1 != 0) size += entrySize(rec, rec.size - size)
                flags = flags shr 1
            }
            if (extraFlags and 1 != 0 && rec.size - size - 1 >= 0) size += (rec[rec.size - size - 1].toInt() and 3) + 1
            return size
        }

        /** A trailing entry's size, written backwards from [end] seven bits a byte. */
        private fun entrySize(rec: ByteArray, end: Int): Int {
            var result = 0
            var shift = 0
            var i = end
            while (i > 0) {
                val v = rec[--i].toInt() and 0xff
                result = result or ((v and 0x7f) shl shift)
                shift += 7
                if (v and 0x80 != 0 || shift >= 28) break
            }
            return result
        }
    }

    /** PalmDOC's LZ77: literals, runs copied from back in the output, and a space folded into the next byte. */
    internal fun palmDoc(input: ByteArray): ByteArray {
        var buf = ByteArray(input.size * 2 + 16)
        var n = 0
        fun put(b: Int) {
            if (n == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[n++] = b.toByte()
        }
        var i = 0
        while (i < input.size) {
            val c = input[i++].toInt() and 0xff
            when {
                c == 0 || c in 0x09..0x7f -> put(c)
                c in 0x01..0x08 -> repeat(c) { if (i < input.size) put(input[i++].toInt() and 0xff) }
                c in 0x80..0xbf -> {
                    if (i >= input.size) break
                    val pair = (c shl 8) or (input[i++].toInt() and 0xff)
                    val distance = (pair shr 3) and 0x7ff
                    val length = (pair and 7) + 3
                    if (distance == 0 || distance > n) continue
                    repeat(length) { put(buf[n - distance].toInt() and 0xff) }
                }
                else -> { put(0x20); put(c xor 0x80) }
            }
        }
        return buf.copyOf(n)
    }

    // --- shared ---

    private val imgTag = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val svgImage = Regex("<svg\\b[^>]*>.*?</svg>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val hrefAttr = Regex("\\shref=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val kindleEmbed = Regex("kindle:embed:([0-9A-Va-v]+)")
    private val voidTags = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")

    /** A web page's text in the reader's look: its body, without scripts, styles or handlers. */
    fun html(source: String, labels: ReaderHtml.Labels = ReaderHtml.Labels()): String =
        clean(bodyOf(source)).ifBlank { "<p class=\"missing\">${ReaderHtml.esc(labels.empty)}</p>" }

    /** What sits between the body tags, or the whole text when it has none. */
    private fun bodyOf(html: String): String {
        val open = Regex("<body\\b[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return html
        val close = html.lastIndexOf("</body", ignoreCase = true).takeIf { it > open.range.last } ?: html.length
        return html.substring(open.range.last + 1, close)
    }

    /**
     * The book's markup made safe to show as HTML in the reader's colours: no scripts, styles or
     * handlers, and XHTML's self-closed elements (`<a id="x"/>`) closed as HTML reads them.
     */
    internal fun clean(html: String): String = html
        .replace(Regex("<(script|style|head|title)\\b.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        .replace(Regex("<(link|meta)\\b[^>]*>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s(?:on\\w+|style|bgcolor|color)=(\"[^\"]*\"|'[^']*')", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<([a-zA-Z][\\w:-]*)([^<>]*?)/>")) { m ->
            val tag = m.groupValues[1]
            if (tag.lowercase() in voidTags) m.value else "<$tag${m.groupValues[2]}></$tag>"
        }

    private fun attrOf(tag: String, name: String): String? =
        Regex("\\s(?:[\\w-]+:)?$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)
            ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    private fun decode(href: String): String =
        runCatching { java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)

    private fun local(name: String?): String = name?.substringAfterLast(':') ?: ""

    private fun attr(p: XmlPullParser, localName: String): String? {
        for (i in 0 until p.attributeCount) if (local(p.getAttributeName(i)) == localName) return p.getAttributeValue(i)
        return null
    }

    private fun u16(b: ByteArray, at: Int): Int = if (at + 2 > b.size) 0 else ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, at: Int): Int = if (at + 4 > b.size) 0 else (u16(b, at) shl 16) or u16(b, at + 2)

    /** Embeds images as data URIs within an overall budget; the type is read from the bytes. */
    private class Images(private val labels: ReaderHtml.Labels) {
        private var spent = 0L

        fun tag(zip: ReaderHtml.ZipRead, path: String, alt: String = ""): String = zip.read(path)?.let { tag(it, alt) } ?: missing(alt)

        fun tag(bytes: ByteArray, alt: String = ""): String {
            val mime = sniff(bytes) ?: return ""
            if (bytes.size > MAX_IMAGE_BYTES || spent + bytes.size > MAX_IMAGE_TOTAL) return missing(alt)
            spent += bytes.size
            return "<img src=\"data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}\" alt=\"${ReaderHtml.esc(alt)}\">"
        }

        private fun missing(alt: String) = "<span class=\"missing\">[${ReaderHtml.esc(alt.ifEmpty { labels.image })}]</span>"

        private fun sniff(b: ByteArray): String? = when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
            b.size > 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
            b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            b.size > 2 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "image/bmp"
            b.size > 5 && String(b, 0, minOf(b.size, 256), Charsets.ISO_8859_1).let { it.contains("<svg") } -> "image/svg+xml"
            else -> null
        }
    }
}
