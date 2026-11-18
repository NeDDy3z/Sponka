package com.xnotes.core.text

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTrainingDataTest {

    private fun sample(id: String, session: String, latex: String, time: Long = 1) =
        MathTrainingData.Sample(id, session, time, latex, listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0)), doubleArrayOf(0.0, 0.0, 1.0, 1.0))

    private fun trained(): MathTrainingData = MathTrainingData().apply {
        add(sample("1", "s1", "\\alpha+b", 10))
        add(sample("2", "s1", "\\alpha-1", 10))
        add(sample("3", "s2", "x^{2}", 20))
        learned("m", "1", "a+b")
        learned("m", "2", "a-1")
        learned("m", "3", "x^{2}")
    }

    @Test fun everyTrainingCounts() {
        val d = trained()
        assertEquals("\\alpha+c", d.corrections("m").apply("a+c"))
        assertEquals(3, d.trainedCount("m"))
        assertEquals(listOf("s2" to 1, "s1" to 2), d.sessions().map { it.id to it.count })
    }

    @Test fun deletingASessionUnteachesIt() {
        val d = trained()
        d.deleteSession("s1")
        assertEquals("a+c", d.corrections("m").apply("a+c"))
        assertEquals(1, d.trainedCount("m"))
        d.deleteAll()
        assertTrue(d.samples().isEmpty())
    }

    @Test fun anotherModelLearnsFromKeptWriting() {
        val d = trained()
        assertEquals(3, d.unlearned("other").size)
        assertEquals(0, d.unlearned("m").size)
    }

    @Test fun mergeIsAUnionAndDeletionsWin() {
        val a = trained()
        val b = MathTrainingData.fromJson(JSONObject(a.toJson().toString()))
        b.deleteSession("s2")
        b.add(sample("4", "s3", "y"))
        a.usage("m").learn("u", "\\mu")
        b.usage("m").learn("u", "\\mu")
        a.merge(b)
        assertEquals(setOf("1", "2", "4"), a.samples().map { it.id }.toSet())
        // Both devices learned the same pick once; merging must not make it twice.
        assertEquals("u", a.corrections("m").apply("u"))
        a.usage("m").learn("u", "\\mu")
        assertEquals("\\mu", a.corrections("m").apply("u"))
        a.merge(MathTrainingData.fromJson(JSONObject(a.toJson().toString())))
        assertEquals(3, a.samples().size)
    }

    @Test fun mergedUsageKeepsHigherCount() {
        val a = MathCorrections().apply { learn("u", "\\mu") }
        val b = MathCorrections().apply { repeat(2) { learn("u", "\\mu") } }
        assertEquals("\\mu", MathCorrections.max(a, b).apply("u"))
        assertEquals("u", MathCorrections.max(a, a).apply("u"))
    }
}
