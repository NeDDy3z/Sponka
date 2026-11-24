package com.xnotes.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingSnapTest {

    @Test fun pullsFlushOnlyToTheEdgesItIsNear() {
        assertEquals(Offset(0f, 300f), snapToEdges(Offset(20f, 300f), 0f..500f, 0f..800f, 48f))
        assertEquals(Offset(500f, 800f), snapToEdges(Offset(470f, 790f), 0f..500f, 0f..800f, 48f))
        assertEquals(Offset(200f, 300f), snapToEdges(Offset(200f, 300f), 0f..500f, 0f..800f, 48f))
    }
}
