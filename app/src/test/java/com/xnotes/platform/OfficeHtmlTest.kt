package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class OfficeHtmlTest {

    private val parser = { KXmlParser() }

    @Test
    fun rtfKeepsTextAndFormattingButNotItsTables() {
        val rtf = "{\\rtf1\\ansi\\ansicpg1252{\\fonttbl{\\f0 Times New Roman;}}{\\colortbl;\\red0\\green0\\blue0;}" +
            "{\\*\\generator Test;}\\pard Hello \\b bold\\b0  and caf\\'e9\\par \\u8364? done\\par}"
        val html = OfficeHtml.rtf(rtf)
        assertFalse(html.contains("Times"))
        assertFalse(html.contains("Test;"))
        assertTrue(html.contains("Hello <b>bold</b> and café"))
        assertTrue(html.contains("€ done"))
    }

    @Test
    fun texSetsApartCommandsMathAndComments() {
        val html = OfficeHtml.tex("\\section{A} 50\\% \$x\$ % note")
        assertTrue(html, html.contains("<span class=\"x-t\">\\section</span>{A} 50<span class=\"x-t\">\\%</span> <span class=\"x-a\">$</span>x<span class=\"x-a\">$</span> <span class=\"x-c\">% note</span>"))
    }

    @Test
    fun xmlOnOneLineIsLaidOutATagALine() {
        assertEquals("<a>\n  <b>x</b>\n  <c/>\n</a>", OfficeHtml.indent("<a><b>x</b><c/></a>"))
    }

    @Test
    fun odtReadsHeadingsStyledSpansListsAndTables() {
        val content = """<office:document-content xmlns:office="o" xmlns:text="t" xmlns:style="s" xmlns:fo="f" xmlns:table="tb">
            <office:automatic-styles><style:style style:name="T1"><style:text-properties fo:font-weight="bold"/></style:style></office:automatic-styles>
            <office:body><office:text><text:h text:outline-level="2">Title</text:h>
            <text:p>Plain <text:span text:style-name="T1">strong</text:span><text:s text:c="2"/>end</text:p>
            <text:list><text:list-item><text:p>one</text:p></text:list-item></text:list>
            <table:table><table:table-row><table:table-cell><text:p>cell</text:p></table:table-cell></table:table-row></table:table>
            <text:p>after<text:note><text:note-body><text:p>footnote</text:p></text:note-body></text:note></text:p>
            </office:text></office:body></office:document-content>"""
        val zip = ReaderHtml.ZipRead { if (it == "content.xml") content.toByteArray() else null }
        val html = OfficeHtml.odt(zip, parser)
        assertTrue(html.contains("<h2>Title</h2>"))
        assertTrue(html.contains("<p>Plain <b>strong</b>&nbsp;&nbsp;end</p>"))
        assertTrue(html.contains("<ul><li><p>one</p></li></ul>"))
        assertTrue(html.contains("<td><p>cell</p></td>"))
        assertTrue(html.contains("<p>after</p>"))
        assertFalse(html.contains("footnote"))
    }

    @Test
    fun odpReadsEachPageAsASlide() {
        val content = """<office:document-content xmlns:office="o" xmlns:text="t" xmlns:draw="d">
            <office:body><office:presentation>
            <draw:page draw:name="p1"><draw:frame><draw:text-box><text:p>First</text:p></draw:text-box></draw:frame></draw:page>
            <draw:page draw:name="p2"><draw:frame><draw:text-box><text:p>Second</text:p></draw:text-box></draw:frame></draw:page>
            </office:presentation></office:body></office:document-content>"""
        val zip = ReaderHtml.ZipRead { if (it == "content.xml") content.toByteArray() else null }
        val html = OfficeHtml.odt(zip, parser, ReaderHtml.Labels(slide = { n, total -> "S$n/$total" }))
        assertTrue(html, html.contains("<div class=\"slide\"><div class=\"slide-no\">S1/2</div><p>First</p></div>"))
        assertTrue(html, html.contains("S2/2</div><p>Second</p></div>"))
    }
}
