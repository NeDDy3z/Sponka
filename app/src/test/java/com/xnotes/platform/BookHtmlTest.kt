package com.xnotes.platform

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class BookHtmlTest {

    private fun zip(vararg entries: Pair<String, String>) = ReaderHtml.ZipRead { path ->
        entries.firstOrNull { it.first == path }?.second?.toByteArray()
    }

    @Test
    fun epubFollowsTheSpineAndLinksChapters() {
        val book = zip(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
            "OEBPS/content.opf" to """<package><manifest>
                <item id="b" href="text/b.xhtml" media-type="application/xhtml+xml"/>
                <item id="a" href="text/a.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OEBPS/text/a.xhtml" to "<html><head><style>p{color:red}</style></head><body><p style=\"color:red\">First<a id=\"x\"/></p><a href=\"b.xhtml\">next</a></body></html>",
            "OEBPS/text/b.xhtml" to "<html><body><p onclick=\"x()\">Second</p><script>bad()</script></body></html>",
        )
        val html = BookHtml.epub(book)
        assertTrue(html.indexOf("First") < html.indexOf("Second"))
        assertTrue(html.contains("<a id=\"x\"></a>"))
        assertTrue(html.contains("href=\"#ch1\""))
        assertFalse(html.contains("style"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("bad()"))
    }

    @Test
    fun fb2KeepsTitleSectionsAndEmphasis() {
        val fb2 = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><book-title>Book</book-title><annotation><p>skip me</p></annotation></title-info></description>
            <body><section id="c1"><title><p>Chapter</p></title><p>Some <emphasis>text</emphasis>.</p><empty-line/></section></body></FictionBook>"""
        val html = BookHtml.fb2(fb2.toByteArray(), { KXmlParser() })
        assertTrue(html.contains("<h1>Book</h1>"))
        assertTrue(html.contains("<section id=\"c1\">"))
        assertTrue(html.contains("Some <i>text</i>."))
        assertFalse(html.contains("skip me"))
    }

    @Test
    fun palmDocExpandsLiteralsRunsAndSpaces() {
        // "ab" literal run, a space folded into 'c', then a copy of 3 bytes from 4 back.
        val input = byteArrayOf(0x02, 'a'.code.toByte(), 'b'.code.toByte(), ('c'.code xor 0x80).toByte(), 0x80.toByte(), (4 shl 3).toByte())
        assertArrayEquals("ab cab ".toByteArray(), BookHtml.palmDoc(input))
    }

    @Test
    fun mobiReadsUncompressedTextRecords() {
        val text = "<html><body><p>Hello</p></body></html>".toByteArray()
        val record0 = ByteArray(16).also { it[1] = 1; it[9] = 1 } // no compression, one text record
        val header = ByteArray(78 + 2 * 8 + 2)
        header[77] = 2
        val r0 = header.size
        val r1 = r0 + record0.size
        fun put(at: Int, v: Int) { for (k in 0..3) header[at + k] = (v shr (24 - 8 * k)).toByte() }
        put(78, r0)
        put(86, r1)
        val html = BookHtml.mobi(header + record0 + text)
        assertEquals("<p>Hello</p>", html)
    }

    @Test
    fun epubCoverPrefersTheCoverImageItemThenTheCoverMeta() {
        val container = "META-INF/container.xml" to "<container><rootfile full-path=\"OPS/book.opf\"/></container>"
        val byProperty = zip(
            container,
            "OPS/book.opf" to """<package><metadata><meta name="cover" content="other"/></metadata><manifest>
                <item id="other" href="img/other.jpg" media-type="image/jpeg"/>
                <item id="c" href="img/front.png" media-type="image/png" properties="cover-image"/>
                </manifest></package>""",
            "OPS/img/front.png" to "FRONT",
            "OPS/img/other.jpg" to "OTHER",
        )
        assertEquals("FRONT", BookHtml.epubCover(byProperty)?.let { String(it) })
        val byMeta = zip(
            container,
            "OPS/book.opf" to """<package><metadata><meta name="cover" content="pic"/></metadata><manifest>
                <item id="pic" href="pic.jpg" media-type="image/jpeg"/></manifest></package>""",
            "OPS/pic.jpg" to "PIC",
        )
        assertEquals("PIC", BookHtml.epubCover(byMeta)?.let { String(it) })
        val none = zip(container, "OPS/book.opf" to "<package><manifest><item id=\"t\" href=\"t.xhtml\" media-type=\"application/xhtml+xml\"/></manifest></package>")
        assertEquals(null, BookHtml.epubCover(none))
    }

    @Test
    fun fb2CoverDecodesTheBinaryItsCoverpageNames() {
        val data = java.util.Base64.getEncoder().encodeToString("COVER".toByteArray())
        val fb2 = """<FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info>
            <coverpage><image l:href="#c.jpg"/></coverpage></title-info></description><body/>
            <binary id="x.jpg" content-type="image/jpeg">AAAA</binary>
            <binary id="c.jpg" content-type="image/jpeg">
            $data
            </binary></FictionBook>"""
        assertEquals("COVER", BookHtml.fb2Cover(fb2.toByteArray())?.let { String(it) })
        assertEquals(null, BookHtml.fb2Cover("<FictionBook><body/></FictionBook>".toByteArray()))
    }

    @Test
    fun mobiCoverFollowsTheExthCoverOffset() {
        val r0 = ByteArray(0x110)
        fun u16(at: Int, v: Int) { r0[at] = (v shr 8).toByte(); r0[at + 1] = v.toByte() }
        fun u32(at: Int, v: Int) { u16(at, v ushr 16); u16(at + 2, v and 0xffff) }
        u16(0, 1)
        "MOBI".toByteArray().copyInto(r0, 16)
        u32(20, 0xE8)
        u32(28, 65001)
        u32(0x6C, 1)
        u32(0x80, 0x40)
        "EXTH".toByteArray().copyInto(r0, 0xF8)
        u32(0xF8 + 4, 24)
        u32(0xF8 + 8, 1)
        u32(0xF8 + 12, 201)
        u32(0xF8 + 16, 12)
        u32(0xF8 + 20, 1)
        val images = listOf("FIRST".toByteArray(), "COVER".toByteArray())
        val start = 78 + 8 * 3
        val header = ByteArray(start)
        header[77] = 3
        var at = start
        for ((i, size) in listOf(r0.size, images[0].size, images[1].size).withIndex()) {
            val p = 78 + i * 8
            header[p] = (at ushr 24).toByte(); header[p + 1] = (at ushr 16).toByte(); header[p + 2] = (at ushr 8).toByte(); header[p + 3] = at.toByte()
            at += size
        }
        val book = header + r0 + images[0] + images[1]
        assertEquals("COVER", BookHtml.mobiCover(book)?.let { String(it) })
    }
}
