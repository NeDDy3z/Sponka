package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathCorrectionsTest {

    @Test fun pairsAlignTokens() {
        assertEquals(
            listOf("a" to "\\alpha", "^" to "^", "{" to "{", "2" to "2", "}" to "}"),
            MathCorrections.pairs("a^{2}", "\\alpha^{2}"),
        )
    }

    @Test fun rewriteTeachesNothing() {
        assertTrue(MathCorrections.pairs("x+1", "\\int_{0}^{1}f").isEmpty())
    }

    @Test fun swapNeedsTwoSightings() {
        val c = MathCorrections()
        c.learn("a+b", "\\alpha+b")
        assertEquals("a+b", c.apply("a+b"))
        c.learn("a-1", "\\alpha-1")
        assertEquals("\\alpha+b", c.apply("a+b"))
    }

    @Test fun correctReadsOutvoteSwap() {
        val c = MathCorrections()
        repeat(2) { c.learn("a+b", "\\alpha+b") }
        repeat(3) { c.learn("a+b", "a+b") }
        assertEquals("a+b", c.apply("a+b"))
        assertEquals("\\alpha+b", c.variants("a+b").first())
    }

    @Test fun variantsOfferTheModelsOwnReading() {
        val c = MathCorrections()
        repeat(2) { c.learn("a+b", "\\alpha+b") }
        c.learn("a+b", "a+b")
        assertEquals("\\alpha+b", c.apply("a+b"))
        assertEquals("a+b", c.variants("a+b").first())
    }

    @Test fun unlearnUndoesLearn() {
        val c = MathCorrections()
        c.learn("a+b", "\\alpha+b")
        c.unlearn("a+b", "\\alpha+b")
        assertTrue(c.isEmpty)
        assertEquals("{}", c.toJson().toString())
    }

    @Test fun jsonRoundTrip() {
        val c = MathCorrections()
        repeat(2) { c.learn("x+y", "x\\times y") }
        val back = MathCorrections.fromJson(org.json.JSONObject(c.toJson().toString()))
        assertEquals("x\\times y", back.apply("x+y"))
    }

    @Test fun lookalikesGiveVariantsBeforeAnyTraining() {
        assertEquals(listOf("\\times^{2}", "X^{2}", "\\chi^{2}"), MathCorrections().variants("x^{2}"))
    }

    @Test fun suggestionPutsCorrectedFirstAndKeepsRawAsVariant() {
        val c = MathCorrections()
        repeat(2) { c.learn("a+b", "\\alpha+b") }
        val s = c.suggest(listOf("a+b", "a+6"), renders = { it != "a+6" })!!
        assertEquals("a+b", s.raw)
        assertEquals("\\alpha+b", s.best)
        assertEquals("\\alpha+6", s.variants[0])
        assertEquals("a+b", s.variants[1])
        assertTrue(s.variants.size >= 3)
        assertTrue("a+6" !in s.variants)
    }

    @Test fun nothingRecognizedSuggestsNothing() {
        assertEquals(null, MathCorrections().suggest(emptyList(), { true }))
    }
}
