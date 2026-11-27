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
        assertTrue(html, html.contains("<p>Plain <span style=\"font-weight:bold;\"><b>strong</b></span>&nbsp;&nbsp;end</p>"))
        assertTrue(html, html.startsWith("<article class=\"doc\" data-page=\"21.000 29.700 2.000 2.000 2.000 2.000\""))
        assertTrue(html.contains("<ul><li><p>one</p></li></ul>"))
        assertTrue(html.contains("<td><p>cell</p></td>"))
        assertTrue(html.contains("<p>after</p>"))
        assertFalse(html.contains("footnote"))
    }

    @Test
    fun odtTakesItsPageSizeAndParagraphLookFromItsStyles() {
        val styles = """<office:document-styles xmlns:office="o" xmlns:style="s" xmlns:fo="f"><office:styles>
            <style:default-style style:family="paragraph"><style:text-properties fo:font-size="12pt"/></style:default-style>
            <style:style style:name="Standard"><style:paragraph-properties fo:margin-bottom="0.2cm"/></style:style></office:styles>
            <office:automatic-styles><style:page-layout style:name="pm1"><style:page-layout-properties fo:page-width="14.8cm" fo:page-height="21cm" fo:margin-top="1cm" fo:margin-right="1.5cm" fo:margin-bottom="1cm" fo:margin-left="1.5cm"/></style:page-layout></office:automatic-styles>
            <office:master-styles><style:master-page style:name="Standard" style:page-layout-name="pm1"/></office:master-styles></office:document-styles>"""
        val content = """<office:document-content xmlns:office="o" xmlns:text="t" xmlns:style="s" xmlns:fo="f">
            <office:automatic-styles><style:style style:name="P1" style:parent-style-name="Standard"><style:paragraph-properties fo:text-align="center"/></style:style></office:automatic-styles>
            <office:body><office:text><text:p text:style-name="P1">Hi</text:p></office:text></office:body></office:document-content>"""
        val zip = ReaderHtml.ZipRead { mapOf("styles.xml" to styles, "content.xml" to content)[it]?.toByteArray() }
        val html = OfficeHtml.odt(zip, parser)
        assertTrue(html, html.contains("data-page=\"14.800 21.000 1.000 1.500 1.000 1.500\""))
        assertTrue(html, html.contains("font-size:12pt;"))
        assertTrue(html, html.contains("<p style=\"text-align:center;margin-bottom:0.2cm;\">Hi</p>"))
    }
}
