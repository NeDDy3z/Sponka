package com.xnotes.core.history

import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRestyleTest {

    @Test fun previewsSettleAsOneStepThatUndoesToTheStart() {
        val stroke = Stroke(Tool.PEN, ToolConfig(neon = true, neonStrength = 0.2))
        val pens = LiveRestyle.pens()
        assertNull(pens.apply(listOf(stroke), preview = true) { it.copy(config = it.config.copy(neonStrength = 0.5)) })
        assertNull(pens.apply(listOf(stroke), preview = true) { it.copy(config = it.config.copy(neonStrength = 0.9)) })
        val step = pens.apply(listOf(stroke), preview = false) { it }
        assertNotNull(step)
        assertEquals(0.9, stroke.config.neonStrength, 0.0)
        step!!.undo()
        assertEquals(0.2, stroke.config.neonStrength, 0.0)
    }

    @Test fun switchingToTheDashedPenKeepsColourAndDropsGlow() {
        val stroke = Stroke(Tool.CALLIGRAPHY, ToolConfig(neon = true, directionStrength = 0.6, baseWidth = 7.0))
        val step = LiveRestyle.pens().apply(listOf(stroke), preview = false) { it.asPen(Tool.DASHED) }
        assertEquals(Tool.DASHED, stroke.tool)
        assertEquals(7.0, stroke.config.baseWidth, 0.0)
        assertEquals(0.0, stroke.config.directionStrength, 0.0)
        assertFalse(stroke.config.neon)
        step!!.undo()
        assertEquals(Tool.CALLIGRAPHY, stroke.tool)
    }
}
