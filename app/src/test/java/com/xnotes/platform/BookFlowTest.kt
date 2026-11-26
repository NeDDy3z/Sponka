package com.xnotes.platform

import com.xnotes.core.text.ListKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFlowTest {

    private fun text(p: com.xnotes.core.text.Paragraph) = p.runs.joinToString("") { it.text }

    @Test
    fun paragraphsHeadingsAndEmphasisComeThrough() {
        val r = BookFlow.parse("<section class=\"chapter\"><h1>One</h1><p>Some <em>slanted</em> &amp; <b>bold</b>\n text.</p></section>")
        assertEquals(listOf("One", "Some slanted & bold text."), r.paragraphs.map(::text))
        assertEquals(1, r.paragraphs[0].headingLevel)
        val p = r.paragraphs[1]
        assertTrue(p.runs.first { it.text == "slanted" }.style.italic)
        assertTrue(p.runs.first { it.text == "bold" }.style.bold)
        assertFalse(p.runs.first().style.bold)
    }

    @Test
    fun laterChaptersStartAPage() {
        val r = BookFlow.parse("<section class=\"chapter\"><p>a</p></section><section class=\"chapter\"><p>b</p></section>")
        assertFalse(r.paragraphs[0].breakBefore)
        assertTrue(r.paragraphs[1].breakBefore)
    }

    @Test
    fun imagesStandOnTheirOwnPage() {
        val data = java.util.Base64.getEncoder().encodeToString("PNG".toByteArray())
        val r = BookFlow.parse("<p>before</p><img src=\"data:image/png;base64,$data\"><p>after</p>")
        assertEquals(3, r.paragraphs.size)
        assertArrayEquals("PNG".toByteArray(), r.images[1])
        assertTrue(r.paragraphs[1].breakBefore)
        assertTrue(r.paragraphs[2].breakBefore)
    }

    @Test
    fun listsRulesAndSkippedTags() {
        val r = BookFlow.parse("<style>p{}</style><ol><li>first</li></ol><ul><li>dot</li></ul><hr><p>&#x41;&#66;</p>")
        assertEquals(ListKind.ORDERED, r.paragraphs[0].list)
        assertEquals(ListKind.BULLET, r.paragraphs[1].list)
        assertTrue(r.paragraphs[2].rule)
        assertEquals("AB", text(r.paragraphs[3]))
    }
}
