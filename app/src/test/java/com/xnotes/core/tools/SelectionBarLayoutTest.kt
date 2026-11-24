package com.xnotes.core.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionBarLayoutTest {

    @Test fun aStoredBoxStyleBecomesChangeStyleInItsPlace() {
        val layout = SelectionBarLayout.fromRaw(listOf(listOf("delete" to true, "box_style" to true), listOf("change_style" to true)))
        assertEquals(listOf(SelectionAction.DELETE, SelectionAction.CHANGE_STYLE), layout.bar.take(2).map { it.action })
        assertEquals(1, (layout.bar + layout.more).count { it.action == SelectionAction.CHANGE_STYLE })
    }
}
