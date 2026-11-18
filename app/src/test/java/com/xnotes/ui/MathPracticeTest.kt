package com.xnotes.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathPracticeTest {
    @Test fun eraserToolEraserEndAndStylusButtonErase() {
        assertTrue(erasingNow(eraserTool = true, eraserEnd = false, buttonHeld = false))
        assertTrue(erasingNow(eraserTool = false, eraserEnd = true, buttonHeld = false))
        assertTrue(erasingNow(eraserTool = false, eraserEnd = false, buttonHeld = true))
        assertFalse(erasingNow(eraserTool = false, eraserEnd = false, buttonHeld = false))
    }

    @Test fun trainingAsksForEnoughFormulas() {
        assertTrue(EXERCISES.size >= MIN_TRAINED)
        assertTrue(MIN_TRAINED >= 10)
    }
}
