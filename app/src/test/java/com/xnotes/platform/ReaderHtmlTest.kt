package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

class ReaderHtmlTest {
    private val parser = { KXmlParser() }

    private fun zip(vararg entries: Pair<String, String>) = ReaderHtml.ZipRead { path ->
        entries.firstOrNull { it.first == path }?.second?.toByteArray()
    }

    @Test
    fun wikilinksBecomeReaderLinksWithAliasAndHeading() {
        val md = ReaderHtml.obsidianSyntax("See [[My Note|this one]] and [[Other#Part]].")
        assertEquals("See [this one](<nnotes-wiki:My%20Note>) and [Other > Part](<nnotes-wiki:Other>).", md)
    }

    @Test
    fun obsidianSyntaxLeavesCodeAlone() {
        val src = "```\n[[not a link]] ==no==\n```\ninline `[[code]]` and ==mark=="
        val out = ReaderHtml.obsidianSyntax(src)
        assertTrue(out.startsWith("```\n[[not a link]] ==no==\n```\n"))
        assertTrue(out.endsWith("inline `[[code]]` and <mark>mark</mark>"))
    }

    @Test
    fun markdownRendersTablesTasksAndCallouts() {
        val html = ReaderHtml.markdown("| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] done\n\n> [!warning] Careful\n> body")
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("type=\"checkbox\""))
        assertTrue(html.contains("<blockquote class=\"callout callout-warning\"><p class=\"callout-title\">Careful</p><p>body"))
    }

    @Test
    fun frontmatterIsShownAsPropertiesNotAsText() {
        val html = ReaderHtml.markdown("---\ntitle: Hello\ntags:\n  - a\n  - b\n---\n# Body")
        assertTrue(html.contains("<b>title</b>: Hello"))
        assertTrue(html.contains("<b>tags</b>: a, b"))
        assertTrue(html.contains("<h1>Body</h1>"))
        assertFalse(html.contains("---"))
    }

    @Test
    fun mathIsTypesetOutsideCodeAndHtmlPassesThrough() {
        val math = ReaderHtml.MathTypeset { latex, display -> "<span class=\"m\">${if (display) "D" else "I"}:$latex</span>" }
        val html = ReaderHtml.markdown("Area \$\\pi r^2\$ and `\$code\$` <u>under</u>\n\n$$\na_1 + b_2\n$$", math = math)
        assertTrue(html, html.contains("<span class=\"m\">I:\\pi r^2</span>"))
        assertTrue(html, html.contains("<span class=\"m\">D:a_1 + b_2</span>"))
        assertTrue(html, html.contains("<code>\$code\$</code>"))
        assertTrue(html, html.contains("<u>under</u>"))
    }

    @Test
    fun unsyncedImagesFallBackToAltText() {
        val html = ReaderHtml.markdown("![a cat](cat.png)")
        assertTrue(html.contains("[a cat]"))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun csvHandlesQuotesAndDetectsSemicolons() {
        val text = "name;note\n\"Doe; J\";\"said \"\"hi\"\"\nthen left\"\nx;1"
        assertEquals(';', ReaderHtml.detectDelimiter(text))
        val rows = ReaderHtml.parseCsv(text, ';')
        assertEquals(listOf("name", "note"), rows[0])
        assertEquals(listOf("Doe; J", "said \"hi\"\nthen left"), rows[1])
        assertEquals(listOf("x", "1"), rows[2])
        val html = ReaderHtml.csv(text)
        assertTrue(html.contains("<th>name</th>"))
        assertTrue(html.contains("<td class=\"num\">1</td>"))
    }

    @Test
    fun docxKeepsHeadingsListsFormattingAndTables() {
        val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        val doc = """<w:document $w><w:body>
            <w:p><w:pPr><w:pStyle w:val="Nadpis1"/></w:pPr><w:r><w:t>Intro</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r><w:r><w:t xml:space="preserve"> plain</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="3"/></w:numPr></w:pPr><w:r><w:t>one</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="3"/></w:numPr></w:pPr><w:r><w:t>two</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            </w:body></w:document>"""
        val styles = """<w:styles $w><w:style w:styleId="Nadpis1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val numbering = """<w:numbering $w><w:abstractNum w:abstractNumId="7"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/></w:lvl></w:abstractNum>
            <w:num w:numId="3"><w:abstractNumId w:val="7"/></w:num></w:numbering>"""
        val html = ReaderHtml.docx(zip("word/document.xml" to doc, "word/styles.xml" to styles, "word/numbering.xml" to numbering), parser)
        assertTrue(html, html.contains("<h1>Intro</h1>"))
        assertTrue(html, html.contains("<p><b>bold</b> plain</p>"))
        assertTrue(html, html.contains("<ol><li>one</li><li>two</li></ol>"))
        assertTrue(html, html.contains("<table><tr><td><p>cell</p></td></tr></table>"))
    }

    @Test
    fun pptxListsSlidesInOrderWithTitlesFirstAndNotes() {
        val ns = "xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"r\""
        val pres = """<p:presentation $ns><p:sldIdLst><p:sldId id="257" r:id="rId3"/><p:sldId id="256" r:id="rId2"/></p:sldIdLst></p:presentation>"""
        val presRels = """<Relationships><Relationship Id="rId2" Type="t/slide" Target="slides/slide1.xml"/><Relationship Id="rId3" Type="t/slide" Target="slides/slide2.xml"/></Relationships>"""
        fun slide(title: String, body: String) = """<p:sld $ns><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="500"/></a:xfrm></p:spPr>
              <p:txBody><a:p><a:r><a:t>$body</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="900"/></a:xfrm></p:spPr>
              <p:txBody><a:p><a:r><a:t>$title</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>99</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>"""
        val notesRels = """<Relationships><Relationship Id="rId1" Type="x/notesSlide" Target="../notesSlides/notesSlide1.xml"/></Relationships>"""
        val notes = """<p:notes $ns><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr>
            <p:txBody><a:p><a:r><a:t>say this</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:notes>"""
        val html = ReaderHtml.pptx(
            zip(
                "ppt/presentation.xml" to pres, "ppt/_rels/presentation.xml.rels" to presRels,
                "ppt/slides/slide1.xml" to slide("First", "alpha"), "ppt/slides/slide2.xml" to slide("Second", "beta"),
                "ppt/slides/_rels/slide2.xml.rels" to notesRels, "ppt/notesSlides/notesSlide1.xml" to notes,
            ),
            parser,
        )
        assertTrue(html, html.indexOf("Second") < html.indexOf("First"))
        assertTrue(html, html.contains("<h2>Second</h2><ul><li style=\"margin-left:0.0em\">beta</li></ul>"))
        assertTrue(html, html.contains("say this"))
        assertFalse(html, html.contains("99"))
    }

    @Test
    fun pptxWithASizeDrawsShapesWhereTheLayoutPlacesThem() {
        val ns = "xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"r\""
        val pres = """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>"""
        val presRels = """<Relationships><Relationship Id="rId2" Type="t/slide" Target="slides/slide1.xml"/></Relationships>"""
        val slide = """<p:sld $ns><p:cSld><p:bg><p:bgPr><a:solidFill><a:schemeClr val="accent1"><a:lumMod val="50000"/></a:schemeClr></a:solidFill></p:bgPr></p:bg><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:p><a:pPr algn="ctr"/><a:r><a:rPr sz="2000"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></a:rPr><a:t>Hi</a:t></a:r></a:p></p:txBody></p:sp>
            <p:grpSp><p:grpSpPr><a:xfrm><a:off x="1000" y="1000"/><a:ext cx="2000" cy="2000"/><a:chOff x="0" y="0"/><a:chExt cx="1000" cy="1000"/></a:xfrm></p:grpSpPr>
              <p:sp><p:spPr><a:xfrm><a:off x="100" y="100"/><a:ext cx="500" cy="500"/></a:xfrm><a:prstGeom prst="ellipse"/><a:solidFill><a:srgbClr val="00FF00"/></a:solidFill></p:spPr></p:sp></p:grpSp>
            </p:spTree></p:cSld></p:sld>"""
        val slideRels = """<Relationships><Relationship Id="rId1" Type="x/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
        val layout = """<p:sldLayout $ns><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
            <p:spPr><a:xfrm><a:off x="1219200" y="685800"/><a:ext cx="6096000" cy="1371600"/></a:xfrm></p:spPr></p:sp></p:spTree></p:cSld></p:sldLayout>"""
        val layoutRels = """<Relationships><Relationship Id="rId1" Type="x/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
        val master = """<p:sldMaster $ns><p:cSld><p:spTree/></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1"/></p:sldMaster>"""
        val masterRels = """<Relationships><Relationship Id="rId1" Type="x/theme" Target="../theme/theme1.xml"/></Relationships>"""
        val theme = """<a:theme xmlns:a="a"><a:themeElements><a:clrScheme name="x"><a:dk1><a:srgbClr val="111111"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:accent1><a:srgbClr val="4080C0"/></a:accent1></a:clrScheme></a:themeElements></a:theme>"""
        val html = ReaderHtml.pptx(
            zip(
                "ppt/presentation.xml" to pres, "ppt/_rels/presentation.xml.rels" to presRels,
                "ppt/slides/slide1.xml" to slide, "ppt/slides/_rels/slide1.xml.rels" to slideRels,
                "ppt/slideLayouts/slideLayout1.xml" to layout, "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to layoutRels,
                "ppt/slideMasters/slideMaster1.xml" to master, "ppt/slideMasters/_rels/slideMaster1.xml.rels" to masterRels,
                "ppt/theme/theme1.xml" to theme,
            ),
            parser,
        )
        assertTrue(html, html.contains("aspect-ratio:12192000/6858000;background:#204060;color:#111111"))
        assertTrue(html, html.contains("left:10.000%;top:10.000%;width:50.000%;height:20.000%;"))
        assertTrue(html, html.contains("<p style=\"text-align:center;\"><span style=\"font-size:2.083cqw;color:#ff0000;\">Hi</span></p>"))
        assertTrue(html, html.contains("left:0.010%;top:0.017%;width:0.008%;height:0.015%;font-size:1.875cqw;background:#00ff00;border-radius:50%;"))
    }

    @Test
    fun pptxWithAChildExtentOnTheSlideTreeStillDrawsTheSlide() {
        val ns = "xmlns:p=\"p\" xmlns:a=\"a\" xmlns:r=\"r\""
        val pres = """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="1000" cy="1000"/></p:presentation>"""
        val presRels = """<Relationships><Relationship Id="rId2" Type="t/slide" Target="slides/slide1.xml"/></Relationships>"""
        val slide = """<p:sld $ns><p:cSld><p:spTree><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
            <p:sp><p:spPr><a:xfrm><a:off x="100" y="200"/><a:ext cx="500" cy="300"/></a:xfrm></p:spPr><p:txBody><a:p><a:r><a:t>Title</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>"""
        val html = ReaderHtml.pptx(zip("ppt/presentation.xml" to pres, "ppt/_rels/presentation.xml.rels" to presRels, "ppt/slides/slide1.xml" to slide), parser)
        assertTrue(html, html.contains("<div class=\"shape\" style=\"left:10.000%;top:20.000%;width:50.000%;height:30.000%;"))
    }

    @Test
    fun odpDrawsEachPageWithItsShapesInPlaceAndNotesBelow() {
        val styles = """<office:document-styles xmlns:office="o" xmlns:style="s" xmlns:fo="f" xmlns:draw="d"><office:automatic-styles>
            <style:page-layout style:name="PM1"><style:page-layout-properties fo:page-width="20cm" fo:page-height="10cm"/></style:page-layout>
            </office:automatic-styles><office:master-styles><style:master-page style:name="M" style:page-layout-name="PM1"/></office:master-styles></office:document-styles>"""
        val content = """<office:document-content xmlns:office="o" xmlns:style="s" xmlns:fo="f" xmlns:draw="d" xmlns:text="t" xmlns:svg="v" xmlns:presentation="p">
            <office:automatic-styles>
            <style:style style:name="dp1" style:family="drawing-page"><style:drawing-page-properties draw:fill="solid" draw:fill-color="#0b0f19"/></style:style>
            <style:style style:name="gr1" style:family="graphic"><style:graphic-properties draw:fill="none" draw:textarea-vertical-align="middle"/></style:style>
            <style:style style:name="P1" style:family="paragraph"><style:paragraph-properties fo:text-align="center"/></style:style>
            <style:style style:name="T1" style:family="text"><style:text-properties fo:color="#22d3ee" fo:font-size="20pt" fo:font-weight="bold"/></style:style>
            </office:automatic-styles><office:body><office:presentation>
            <draw:page draw:style-name="dp1" draw:master-page-name="M">
              <draw:custom-shape draw:style-name="gr1" svg:x="2cm" svg:y="1cm" svg:width="10cm" svg:height="5cm"><text:p text:style-name="P1"><text:span text:style-name="T1">ITER</text:span></text:p></draw:custom-shape>
              <presentation:notes><draw:frame svg:x="0cm" svg:y="0cm" svg:width="1cm" svg:height="1cm"><draw:text-box><text:p>Said aloud</text:p></draw:text-box></draw:frame></presentation:notes>
            </draw:page></office:presentation></office:body></office:document-content>"""
        val html = ReaderHtml.odp(zip("styles.xml" to styles, "content.xml" to content), parser, ReaderHtml.Labels(slide = { n, total -> "S$n/$total" }, notes = "Notes"))
        assertTrue(html, html.contains("S1/1</div><div class=\"deck-slide\" style=\"aspect-ratio:20.000/10.000;background:#0b0f19;"))
        assertTrue(html, html.contains("left:10.000%;top:10.000%;width:50.000%;height:50.000%;"))
        assertTrue(html, html.contains("justify-content:center;"))
        assertTrue(html, html.contains("<p style=\"text-align:center;\"><span style=\"color:#22d3ee;font-size:3.528cqw;font-weight:bold;\">ITER</span></p>"))
        assertTrue(html, html.contains("<div class=\"notes\"><b>Notes</b><p>Said aloud</p></div>"))
    }

    @Test
    fun xlsxShowsSheetsWithSharedStringsNumbersAndDates() {
        val wb = """<workbook xmlns:r="r"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/><sheet name="Empty" sheetId="2" r:id="rId2"/></sheets></workbook>"""
        val wbRels = """<Relationships><Relationship Id="rId1" Type="x/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="x/worksheet" Target="/xl/worksheets/sheet2.xml"/></Relationships>"""
        val shared = """<sst><si><t>Name</t></si><si><r><t>Bo</t></r><r><t>b</t></r><rPh><t>x</t></rPh></si></sst>"""
        val styles = """<styleSheet><numFmts><numFmt numFmtId="164" formatCode="d/m/yyyy"/></numFmts><cellXfs><xf numFmtId="0"/><xf numFmtId="164"/></cellXfs></styleSheet>"""
        val sheet1 = """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="inlineStr"><is><t>When</t></is></c></row>
            <row r="3"><c r="A3" t="s"><v>1</v></c><c r="B3"><v>0.30000000000000004</v></c><c r="C3" s="1"><v>45292</v></c><c r="D3" t="b"><v>1</v></c></row>
            </sheetData></worksheet>"""
        val sheet2 = """<worksheet><sheetData/></worksheet>"""
        val html = ReaderHtml.xlsx(
            zip(
                "xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to wbRels, "xl/sharedStrings.xml" to shared,
                "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet1, "xl/worksheets/sheet2.xml" to sheet2,
            ),
            parser,
        )
        assertTrue(html, html.contains("<h2 id=\"sheet0\">Data</h2>"))
        assertTrue(html, html.contains("<th></th><th>A</th><th>B</th><th>C</th><th>D</th>"))
        assertTrue(html, html.contains("<tr><th>1</th><td>Name</td><td></td><td>When</td><td></td></tr>"))
        assertTrue(html, html.contains("<tr><th>3</th><td>Bob</td><td class=\"num\">0.3</td><td>2024-01-01</td><td>TRUE</td></tr>"))
        assertTrue(html, html.contains("<h2 id=\"sheet1\">Empty</h2><p class=\"missing\">"))
    }

    @Test
    fun xlsxMergesStylesFormatsAndHidesWhatExcelHides() {
        java.util.Locale.setDefault(java.util.Locale.US) // numbers read the way the device's language writes them
        val wb = """<workbook xmlns:r="r"><sheets><sheet name="S" sheetId="1" r:id="rId1"/><sheet name="H" sheetId="2" state="hidden" r:id="rId1"/></sheets></workbook>"""
        val wbRels = """<Relationships><Relationship Id="rId1" Type="x/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
        val styles = """<styleSheet><numFmts><numFmt numFmtId="164" formatCode="&quot;$&quot;#,##0.00"/></numFmts>
            <fonts count="2"><font><color theme="1"/></font><font><b/><color rgb="FFFF0000"/></font></fonts>
            <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFFF00"/></patternFill></fill></fills>
            <cellXfs><xf numFmtId="0"/><xf numFmtId="164" fontId="1" fillId="2"><alignment horizontal="center"/></xf><xf numFmtId="10"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet><cols><col min="2" max="2" hidden="1"/></cols><sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>Head</t></is></c></row>
            <row r="2"><c r="A2" s="1"><v>1234.5</v></c><c r="C2" s="2"><v>0.125</v></c></row>
            <row r="3" hidden="1"><c r="A3" t="inlineStr"><is><t>gone</t></is></c></row>
            </sheetData><mergeCells><mergeCell ref="A1:C1"/></mergeCells></worksheet>"""
        val html = ReaderHtml.xlsx(zip("xl/workbook.xml" to wb, "xl/_rels/workbook.xml.rels" to wbRels, "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet), parser)
        assertFalse(html, html.contains(">H<"))
        assertTrue(html, html.contains("<th></th><th>A</th><th>C</th></tr>"))
        assertTrue(html, html.contains("<td colspan=\"2\">Head</td>"))
        assertTrue(html, html.contains("style=\"font-weight:600;background:#ffff00;color:#ff0000;text-align:center;\">$1,234.50</td>"))
        assertTrue(html, html.contains(">12.50%</td>"))
        assertFalse(html, html.contains("gone"))
    }

    @Test
    fun spreadsheetColumnsAndDates() {
        assertEquals(0, ReaderHtml.columnIndex("A1"))
        assertEquals(27, ReaderHtml.columnIndex("AB12"))
        assertEquals("AB", ReaderHtml.columnName(27))
        assertEquals("1900-01-01", ReaderHtml.excelDate(2.0))
        assertEquals("2024-01-01 12:00", ReaderHtml.excelDate(45292.5))
    }

    @Test
    fun resolvesRelativePartPaths() {
        assertEquals("ppt/media/image1.png", ReaderHtml.resolvePath("ppt/slides", "../media/image1.png"))
        assertEquals("word/media/a.png", ReaderHtml.resolvePath("word", "media/a.png"))
    }

    @Test
    fun markdownEmbedsLocalImagesAndObsidianEmbeds() {
        val read = ReaderHtml.ZipRead { path -> if (path == "img/a b.png" || path == "pic.jpg") byteArrayOf(1, 2, 3) else null }
        val html = ReaderHtml.markdown("![alt](<img/a b.png>)\n\n![[pic.jpg|300]]\n\n![gone](none.png)\n\n![web](https://x.org/a.png)", image = read)
        assertTrue(html, html.contains("<img src=\"data:image/png;base64,AQID\" alt=\"alt\">"))
        assertTrue(html, html.contains("<img src=\"data:image/jpeg;base64,AQID\""))
        assertTrue(html, html.contains("<span class=\"missing\">[gone]</span>"))
        assertTrue(html, html.contains("<span class=\"missing\">[web]</span>"))
    }

    @Test
    fun pageChunksSplitChaptersThenLongOnesAfterAParagraph() {
        val long = "<section class=\"chapter\" id=\"ch1\">" + "<p>abcdefgh</p>".repeat(10) + "</section>"
        val body = "<h1>T</h1><section class=\"chapter\" id=\"ch0\"><p>a</p></section>$long"
        val chunks = ReaderHtml.pageChunks(body, max = 40)
        assertEquals(body, chunks.joinToString(""))
        assertEquals("<h1>T</h1>", chunks[0])
        assertTrue(chunks[1].startsWith("<section class=\"chapter\" id=\"ch0\""))
        assertTrue(chunks.size > 3)
        assertTrue(chunks.drop(2).dropLast(1).all { it.endsWith("</p>") })
        assertEquals(listOf("<p>x</p>"), ReaderHtml.pageChunks("<p>x</p>"))
        val big = "<section class=\"slide\">" + "<p>x</p>".repeat(20) + "</section>"
        assertEquals(listOf(big), ReaderHtml.pageChunks(big, max = 20))
        val slides = "<section class=\"slide\">1</section><section class=\"slide\">2</section><section class=\"slide\">3</section>"
        assertEquals(listOf("<section class=\"slide\">1</section><section class=\"slide\">2</section>", "<section class=\"slide\">3</section>"), ReaderHtml.pageChunks(slides, max = 70))
    }
}
