package com.xnotes.canvas

import com.xnotes.core.FakeSurfaceFactory
import com.xnotes.core.model.Document
import com.xnotes.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reader mode's horizontal scrolling: pages in one strip that scrolls freely, not page by page. */
class HorizontalStripTest {

    private fun state(pages: Int): CanvasState =
        CanvasState(Document.blank(pages), FakeSurfaceFactory(), Palette.DEFAULT).apply {
            verticalScroll = true
            horizontalStrip = true
            viewportW = 1000
            viewportH = 1400
            relayout()
            zoom = 1.0
        }

    @Test fun pagesRunLeftToRight() {
        val st = state(3)
        assertEquals(st.pageRects[0].top, st.pageRects[2].top, 1e-9)
        assertTrue(st.pageRects[1].left > st.pageRects[0].right)
        assertTrue(st.contentW > 3 * st.pageRects[0].w)
    }

    @Test fun scrollRunsAcrossEveryPage() {
        val st = state(3)
        st.scrollX = 1e9
        st.clampScroll()
        assertEquals(st.maxScrollX(), st.scrollX, 1e-6)
        assertEquals(2, st.currentPageIndex())
        assertEquals(0..2, st.drawablePageRange())
    }

    @Test fun goToPageLandsOnThatPage() {
        val st = state(4)
        st.goToPage(2)
        assertEquals(2, st.currentPageIndex())
        st.goToPage(0)
        assertEquals(0, st.currentPageIndex())
    }
}
