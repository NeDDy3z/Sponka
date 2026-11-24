package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.BoxStyle
import com.xnotes.core.model.CellStyle
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TableItem
import com.xnotes.core.model.TableLines
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class TableBoxCodecTest {

    private val notes = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

    @Test fun aTablesFrameComesBack() {
        val style = BoxStyle(Rgba(10, 20, 30, 80), Rgba(200, 0, 0), 3.0, 16.0)
        val table = TableItem(Rect(0.0, 0.0, 200.0, 100.0), listOf(0.5, 0.5), listOf(0.5, 0.5), Rgba(0, 0, 0)).also { it.box = style; it.lines = TableLines(dashed = true, dashGap = 5.0, neon = true, neonStrength = 0.9) }
        val doc = Document().also { it.pages += Page(400.0, 400.0).also { p -> p.items += table } }
        val bytes = ByteArrayOutputStream().also { notes.write(doc, it) }.toByteArray()
        val back = notes.read(ByteArrayInputStream(bytes), imageDir = Files.createTempDirectory("table-img").toFile())
        val t = back.pages.single().items.single() as TableItem
        assertEquals(style, t.box)
        assertEquals(TableLines(dashed = true, dashGap = 5.0, neon = true, neonStrength = 0.9), t.lines)
    }

    @Test fun styledCellsComeBackAndFollowInsertedAndRemovedLines() {
        val table = TableItem.create(Rect(0.0, 0.0, 300.0, 300.0), 3, 3, Rgba(0, 0, 0))
        val red = CellStyle(fill = Rgba(255, 0, 0))
        val ringed = CellStyle(border = Rgba(0, 0, 255), borderWidth = 2.5)
        table.setCell(0, 0, red)
        table.setCell(2, 1, ringed)
        val doc = Document().also { it.pages += Page(400.0, 400.0).also { p -> p.items += table } }
        val bytes = ByteArrayOutputStream().also { notes.write(doc, it) }.toByteArray()
        val t = notes.read(ByteArrayInputStream(bytes), imageDir = Files.createTempDirectory("table-img").toFile()).pages.single().items.single() as TableItem
        assertEquals(mapOf((0 to 0) to red, (2 to 1) to ringed), t.cells)
        t.insertColumn(0)
        t.removeRows(1..1)
        assertEquals(mapOf((0 to 1) to red, (1 to 2) to ringed), t.cells)
        t.removeColumns(1..1)
        assertEquals(mapOf((1 to 1) to ringed), t.cells)
    }
}
