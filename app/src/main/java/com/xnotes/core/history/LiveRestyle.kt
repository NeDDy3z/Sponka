package com.xnotes.core.history

import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults

/** A drawn shape's or connector's dashes, glow and fill. */
data class ShapeLook(val dashLength: Double, val dashGap: Double, val neonStrength: Double, val fill: com.xnotes.core.model.Rgba?)

/** A pen stroke's kind of pen and the style it was drawn with. */
data class PenLook(val tool: Tool, val config: ToolConfig) {
    /** This stroke as [pen] would have drawn it: that pen's nib, the stroke's own colour, width and effects. */
    fun asPen(pen: Tool): PenLook {
        if (pen == tool) return this
        val nib = ToolDefaults.configFor(pen)
        return PenLook(
            pen,
            config.copy(
                pressureEnabled = nib.pressureEnabled,
                pressureMinFactor = nib.pressureMinFactor,
                directionStrength = nib.directionStrength,
                speedStrength = nib.speedStrength,
                taperEnabled = nib.taperEnabled,
                taperMinFactor = nib.taperMinFactor,
                neon = config.neon && pen != Tool.DASHED,
            ),
        )
    }
}

/**
 * A restyle the selection menu drags live: a [preview] change skips history, and the next call
 * without it records everything since as one undo step.
 */
class LiveRestyle<S>(private val read: (CanvasItem) -> S?, private val write: (CanvasItem, S) -> Unit) {
    private var baseline: Map<CanvasItem, S>? = null

    fun first(items: List<CanvasItem>): S? = items.firstNotNullOfOrNull { if (it.locked) null else read(it) }

    /** Apply [change] to [items]; returns the step to push once settled, or null. */
    fun apply(items: List<CanvasItem>, preview: Boolean, change: (S) -> S): Command? {
        val start = baseline ?: items.filter { !it.locked }.mapNotNull { i -> read(i)?.let { i to it } }.toMap()
        for (item in start.keys) {
            val now = read(item) ?: continue
            val next = change(now)
            if (next != now) write(item, next)
        }
        if (preview) {
            baseline = start
            return null
        }
        baseline = null
        val steps = start.mapNotNull { (item, before) -> read(item)?.takeIf { it != before }?.let { Triple(item, before, it) } }
        if (steps.isEmpty()) return null
        return object : Command {
            override fun redo() = steps.forEach { (item, _, after) -> write(item, after) }
            override fun undo() = steps.forEach { (item, before, _) -> write(item, before) }
            override fun touched(locate: (CanvasItem) -> com.xnotes.core.model.Page?) =
                steps.mapNotNull { (item, _, _) -> locate(item)?.let { it to item } }
        }
    }

    companion object {
        fun shapes() = LiveRestyle<ShapeLook>(
            read = { item ->
                when (item) {
                    is ShapeItem -> if (item.isFill || item.shape == com.xnotes.core.shapes.ShapeKind.GLYPHS) null
                        else ShapeLook(item.dashLength, item.dashGap, item.neonStrength, item.fillRgba)
                    is ConnectorItem -> ShapeLook(item.dashLength, item.dashGap, item.neonStrength, null)
                    else -> null
                }
            },
            write = { item, look ->
                when (item) {
                    is ShapeItem -> { item.dashLength = look.dashLength; item.dashGap = look.dashGap; item.neonStrength = look.neonStrength; item.fillRgba = look.fill }
                    is ConnectorItem -> { item.dashLength = look.dashLength; item.dashGap = look.dashGap; item.neonStrength = look.neonStrength }
                    else -> {}
                }
            },
        )

        fun tables() = LiveRestyle<com.xnotes.core.model.TableLook>(
            read = { (it as? com.xnotes.core.model.TableItem)?.let(com.xnotes.core.model.TableLook::of) },
            write = { item, look -> look.applyTo(item as com.xnotes.core.model.TableItem) },
        )

        fun pens() = LiveRestyle<PenLook>(
            read = { item -> (item as? Stroke)?.takeIf { it.tool in Tool.PENS }?.let { PenLook(it.tool, it.config) } },
            write = { item, look ->
                (item as Stroke).tool = look.tool
                item.config = look.config
                item.invalidate()
            },
        )
    }
}
