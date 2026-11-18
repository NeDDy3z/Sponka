package com.xnotes.canvas

import com.xnotes.core.FakeMathTypesetter
import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.history.History
import com.xnotes.core.model.Document
import com.xnotes.core.model.MathOrigin
import com.xnotes.core.model.Page
import com.xnotes.core.model.TextItem
import com.xnotes.ui.theme.Palette
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RecognizedMathEditTest {

    private val calls = ArrayList<Triple<String, String, String>>()

    @After fun reset() {
        TextItem.mathTypesetter = null
    }

    private fun edit(origin: MathOrigin?): History {
        TextItem.mathTypesetter = FakeMathTypesetter()
        val box = TextItem(Pt(10.0, 10.0), 100.0, 0.0, "a+b", measurer = FakeTextMeasurer(), math = true).also { it.ocr = origin }
        val st = CanvasState(Document(mutableListOf(Page(400.0, 400.0, mutableListOf(box)))), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            viewportW = 800
            viewportH = 1000
            relayout()
        }
        val history = History()
        val ctrl = InteractionController(st, history, FakeTextMeasurer(), requestRender = {})
        ctrl.onMathRelearn = { o, from, to -> calls += Triple(o.model, from, to) }
        ctrl.selectAll()
        ctrl.setSelectedMath("\\alpha+b")
        return history
    }

    @Test fun editOfRecognizedFormulaRelearnsAndUndoTakesItBack() {
        val history = edit(MathOrigin("pix2text-mfr", "a+b"))
        history.undo()
        history.redo()
        assertEquals(
            listOf(
                Triple("pix2text-mfr", "a+b", "\\alpha+b"),
                Triple("pix2text-mfr", "\\alpha+b", "a+b"),
                Triple("pix2text-mfr", "a+b", "\\alpha+b"),
            ),
            calls,
        )
    }

    @Test fun editOfTypedFormulaTeachesNothing() {
        edit(null)
        assertEquals(emptyList<Triple<String, String, String>>(), calls)
    }
}
