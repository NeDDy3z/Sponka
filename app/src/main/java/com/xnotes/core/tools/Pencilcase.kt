package com.xnotes.core.tools

import com.xnotes.core.model.Rgba
import com.xnotes.canvas.TableToolDefaults

/** A tool saved in the pencilcase: what it is, how it is set up and the ink it draws in. */
data class PencilcaseItem(
    val tool: Tool,
    val config: ToolConfig = ToolDefaults.configFor(tool),
    val shape: ShapeConfig = ShapeConfig(),
    val color: Rgba = InkPalette.DEFAULT,
    /** The table tool's size and line width; its line colour is [color]. */
    val table: TableToolDefaults = DEFAULT_TABLE,
    /** What the user called it, shown on hover instead of the tool's name. */
    val name: String? = null,
) {
    /** What the item draws in: the tool's own colour when it has one, else the saved ink. */
    val shownColor: Rgba get() = config.colorOverride ?: color

    companion object {
        /** Tools that can go in the pencilcase. */
        val TOOLS: Set<Tool> = (Tool.PENS + listOf(Tool.HIGHLIGHTER, Tool.ERASER, Tool.TAPE, Tool.SHAPE, Tool.TABLE)).toSet()

        /** Keeps only what [tool] uses and settings store, so the same tool saved twice compares equal. */
        val DEFAULT_TABLE = TableToolDefaults(cols = 3, rows = 3, look = com.xnotes.core.model.TableLook(2.0, com.xnotes.core.model.BoxStyle.NONE, com.xnotes.core.model.TableLines()))

        /** Keeps only what [tool] uses and settings store, so the same tool saved twice compares equal.
         *  A table's colour is fixed to what it draws in now, its own or the ink it follows. */
        fun of(tool: Tool, config: ToolConfig, shape: ShapeConfig, color: Rgba, table: TableToolDefaults = DEFAULT_TABLE) = PencilcaseItem(
            tool,
            config = if (tool == Tool.SHAPE || tool == Tool.TABLE) ToolDefaults.configFor(tool) else config.copy(rgba = InkPalette.DEFAULT),
            shape = if (tool == Tool.SHAPE) shape else ShapeConfig(),
            color = when (tool) {
                Tool.ERASER -> InkPalette.DEFAULT
                Tool.TABLE -> table.color ?: color
                else -> color
            },
            table = if (tool == Tool.TABLE) table.copy(color = null) else DEFAULT_TABLE,
        )
    }
}
