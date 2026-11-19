package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SelectionBarLayoutTest {

    private fun SelectionBarLayout.toRaw() = sections.map { s -> s.map { it.action.id to it.visible } }

    @Test fun roundTripsThroughRaw() {
        val edited = SelectionBarLayout.DEFAULT
            .moveItem(SelectionBarLayout.MORE, 0, SelectionBarLayout.BAR, 1)
            .toggleVisible(SelectionBarLayout.BAR, 0)
        assertEquals(edited, SelectionBarLayout.fromRaw(edited.toRaw()))
        assertEquals(SelectionAction.AUDIO_RENAME, edited.bar[1].action)
        assertFalse(edited.bar[0].visible)
    }

    @Test fun moveWithinListAccountsForRemoval() {
        val moved = SelectionBarLayout.DEFAULT.moveItem(SelectionBarLayout.BAR, 0, SelectionBarLayout.BAR, 2)
        assertEquals(listOf(SelectionAction.CUT, SelectionAction.DELETE, SelectionAction.COPY), moved.bar.take(3).map { it.action })
    }

    @Test fun fromRawDropsUnknownAndDuplicatesAndAddsMissing() {
        val layout = SelectionBarLayout.fromRaw(listOf(listOf("group" to true, "nope" to true), listOf("group" to false, "delete" to false)))
        assertEquals(SelectionAction.GROUP, layout.bar[0].action)
        assertEquals(SelectionEntry(SelectionAction.DELETE, false), layout.more[0])
        assertEquals(SelectionAction.entries.toSet(), (layout.bar + layout.more).map { it.action }.toSet())
        assertEquals(SelectionAction.entries.size, layout.bar.size + layout.more.size)
    }

    @Test fun malformedFallsBackToDefault() {
        assertEquals(SelectionBarLayout.DEFAULT, SelectionBarLayout.fromRaw(emptyList()))
    }
}
