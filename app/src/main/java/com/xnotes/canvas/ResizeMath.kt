package com.xnotes.canvas

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.TextItem
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Which resize handle is being dragged (spec 06 §8). T/B are the top/bottom side mid-handles. */
enum class HandleId { TL, TR, BL, BR, L, R, T, B, START, END }

data class ResizeHandle(val id: HandleId, val content: Pt)

/** Pure resize-handle geometry and per-type resize updates (spec 06 §8). */
object ResizeMath {

    /** The square box of a circle dragged out from its centre [centre] to a point [p] on its rim. */
    fun circleFromCentre(centre: Pt, p: Pt): Pair<Pt, Pt> {
        val r = centre.distanceTo(p)
        return Pt(centre.x - r, centre.y - r) to Pt(centre.x + r, centre.y + r)
    }

    const val MIN_SIZE = 24.0

    /** Lowest scale a generic box-handle drag yields, so a selection can't collapse to zero or
     *  flip. A ratio floor (vs the absolute [MIN_SIZE]) keeps thin selections from exploding. */
    const val MIN_SCALE = 0.05

    /** Handle positions in content space for [item] on a page at [pageTopLeft]. */
    fun handles(item: CanvasItem, pageTopLeft: Pt): List<ResizeHandle> = when (item) {
        is ImageItem -> rectCorners(item.rect, pageTopLeft)
        is TextItem -> {
            val b = item.bounds()
            val l = b.left + pageTopLeft.x
            val r = b.right + pageTopLeft.x
            val t = b.top + pageTopLeft.y
            val bot = b.bottom + pageTopLeft.y
            val cy = b.centerY + pageTopLeft.y
            // Six handles: the corners scale it, the two sides set its width. Its text sets its height.
            listOf(
                ResizeHandle(HandleId.TL, Pt(l, t)),
                ResizeHandle(HandleId.TR, Pt(r, t)),
                ResizeHandle(HandleId.R, Pt(r, cy)),
                ResizeHandle(HandleId.BR, Pt(r, bot)),
                ResizeHandle(HandleId.BL, Pt(l, bot)),
                ResizeHandle(HandleId.L, Pt(l, cy)),
            )
        }
        is ShapeItem ->
            if (item.shape.isEndpointShape) {
                listOf(
                    ResizeHandle(HandleId.START, Pt(item.start.x + pageTopLeft.x, item.start.y + pageTopLeft.y)),
                    ResizeHandle(HandleId.END, Pt(item.end.x + pageTopLeft.x, item.end.y + pageTopLeft.y)),
                )
            } else {
                rectCorners(item.box, pageTopLeft)
            }
        else -> emptyList()
    }

    private fun rectCorners(rect: Rect, ptl: Pt) = listOf(
        ResizeHandle(HandleId.TL, Pt(rect.left + ptl.x, rect.top + ptl.y)),
        ResizeHandle(HandleId.TR, Pt(rect.right + ptl.x, rect.top + ptl.y)),
        ResizeHandle(HandleId.BL, Pt(rect.left + ptl.x, rect.bottom + ptl.y)),
        ResizeHandle(HandleId.BR, Pt(rect.right + ptl.x, rect.bottom + ptl.y)),
    )

    fun hitHandle(handles: List<ResizeHandle>, content: Pt, tolerance: Double): HandleId? =
        handles.firstOrNull { it.content.distanceTo(content) <= tolerance }?.id

    /** The eight generic box handles (four corners + four edge midpoints) for [box], in box's
     *  own coordinate space. Used by the unified resize handles for any multi/mixed selection. */
    fun boxHandles(box: Rect): List<ResizeHandle> = listOf(
        ResizeHandle(HandleId.TL, box.topLeft),
        ResizeHandle(HandleId.T, Pt(box.centerX, box.top)),
        ResizeHandle(HandleId.TR, Pt(box.right, box.top)),
        ResizeHandle(HandleId.R, Pt(box.right, box.centerY)),
        ResizeHandle(HandleId.BR, Pt(box.right, box.bottom)),
        ResizeHandle(HandleId.B, Pt(box.centerX, box.bottom)),
        ResizeHandle(HandleId.BL, Pt(box.left, box.bottom)),
        ResizeHandle(HandleId.L, Pt(box.left, box.centerY)),
    )

    /** A scale about [anchor] by ([sx], [sy]) — the transform a box-handle drag maps to. */
    data class ScaleSpec(val anchor: Pt, val sx: Double, val sy: Double)

    /** The eight handles of an oriented box, in world space. */
    fun obbHandles(obb: Obb): List<ResizeHandle> = listOf(
        ResizeHandle(HandleId.TL, obb.localToWorld(Pt(-obb.halfW, -obb.halfH))),
        ResizeHandle(HandleId.T, obb.localToWorld(Pt(0.0, -obb.halfH))),
        ResizeHandle(HandleId.TR, obb.localToWorld(Pt(obb.halfW, -obb.halfH))),
        ResizeHandle(HandleId.R, obb.localToWorld(Pt(obb.halfW, 0.0))),
        ResizeHandle(HandleId.BR, obb.localToWorld(Pt(obb.halfW, obb.halfH))),
        ResizeHandle(HandleId.B, obb.localToWorld(Pt(0.0, obb.halfH))),
        ResizeHandle(HandleId.BL, obb.localToWorld(Pt(-obb.halfW, obb.halfH))),
        ResizeHandle(HandleId.L, obb.localToWorld(Pt(-obb.halfW, 0.0))),
    )

    /** Top-edge midpoint of an oriented box in world space (the rotate stem's base). */
    fun obbTopMid(obb: Obb): Pt = obb.localToWorld(Pt(0.0, -obb.halfH))

    /** The rotate-grip centre, [arm] (content px) out past the box's top edge along its local up. */
    fun obbRotateGrip(obb: Obb, arm: Double): Pt = obb.localToWorld(Pt(0.0, -obb.halfH - arm))

    /** On-screen size (device px) below which a selection gets a move grip, as its handles crowd out its body. */
    const val MOVE_GRIP_BELOW_PX = 120.0

    /** True when [obb] seen at [zoom] is below [MOVE_GRIP_BELOW_PX], so it gets a move grip and no box handles. */
    fun obbCrowded(obb: Obb, zoom: Double): Boolean = kotlin.math.max(obb.halfW, obb.halfH) * 2.0 * zoom < MOVE_GRIP_BELOW_PX

    /** The move-grip centre, [arm] content px past the bottom edge of [obb] seen at [zoom], or null when the box is big enough to grab. */
    fun obbMoveGrip(obb: Obb, zoom: Double, arm: Double): Pt? =
        if (!obbCrowded(obb, zoom)) null
        else obb.localToWorld(Pt(0.0, obb.halfH + arm))

    /** The pan tool's four-way arrows as polylines centred on [c], reaching [r] content px out. */
    fun moveGlyph(c: Pt, r: Double): List<List<Pt>> {
        fun p(x: Double, y: Double) = Pt(c.x + (x - 12.0) / 10.0 * r, c.y + (y - 12.0) / 10.0 * r)
        return listOf(
            listOf(p(5.0, 9.0), p(2.0, 12.0), p(5.0, 15.0)),
            listOf(p(9.0, 5.0), p(12.0, 2.0), p(15.0, 5.0)),
            listOf(p(15.0, 19.0), p(12.0, 22.0), p(9.0, 19.0)),
            listOf(p(19.0, 9.0), p(22.0, 12.0), p(19.0, 15.0)),
            listOf(p(2.0, 12.0), p(22.0, 12.0)),
            listOf(p(12.0, 2.0), p(12.0, 22.0)),
        )
    }

    /** The affine to bake into the items, plus the resulting oriented box. */
    data class ObbResize(val transform: Affine, val obb: Obb)

    /**
     * Drag a box handle of an oriented box [obb] to world [pointer]: scale in the box's own frame
     * (corner uniform, edge single-axis) about the opposite handle, returning the world-space affine
     * to bake into the items and the new oriented box. The two stay consistent — applying the affine
     * to [obb]'s corners reproduces the returned box's corners.
     */
    fun obbResize(obb: Obb, handle: HandleId, pointer: Pt): ObbResize {
        val local = obb.worldToLocal(pointer)
        val localBox = Rect(-obb.halfW, -obb.halfH, obb.halfW * 2.0, obb.halfH * 2.0)
        val sc = scaleForHandle(localBox, handle, local)
        val transform = Affine.scaleAlongAxes(obb.localToWorld(sc.anchor), obb.angle, sc.sx, sc.sy)
        // The local box was centred at the origin; scaling about sc.anchor moves that centre.
        val newCenterLocal = Pt(sc.anchor.x * (1.0 - sc.sx), sc.anchor.y * (1.0 - sc.sy))
        val newObb = Obb(obb.localToWorld(newCenterLocal), obb.halfW * sc.sx, obb.halfH * sc.sy, obb.angle)
        return ObbResize(transform, newObb)
    }

    /**
     * As [obbResize] for a corner, but free: each axis scales on its own about the opposite corner,
     * so a corner sets width and height apart. A rectangle of tape resizes this way.
     */
    fun obbResizeFree(obb: Obb, handle: HandleId, pointer: Pt): ObbResize {
        val local = obb.worldToLocal(pointer)
        val box = Rect(-obb.halfW, -obb.halfH, obb.halfW * 2.0, obb.halfH * 2.0)
        val anchor = cornerAnchor(box, handle)
        val grab = grabbedCorner(box, handle)
        val sx = axisScale(local.x, anchor.x, grab.x)
        val sy = axisScale(local.y, anchor.y, grab.y)
        val transform = Affine.scaleAlongAxes(obb.localToWorld(anchor), obb.angle, sx, sy)
        val newCenterLocal = Pt(anchor.x * (1.0 - sx), anchor.y * (1.0 - sy))
        return ObbResize(transform, Obb(obb.localToWorld(newCenterLocal), obb.halfW * sx, obb.halfH * sy, obb.angle))
    }

    /**
     * As [obbResize], for handles drawn [margin] outside the box that scales: a lone shape's frame,
     * whose chrome sits past its outline. The pointer is read as if pulled back by the margin, so
     * the box edge stays exactly the margin behind the finger rather than scaling the gap.
     */
    fun obbResize(obb: Obb, handle: HandleId, pointer: Pt, margin: Double): ObbResize {
        if (margin <= 0.0) return obbResize(obb, handle, pointer)
        val local = obb.worldToLocal(pointer)
        val dir = handleDirection(handle)
        return obbResize(obb, handle, obb.localToWorld(Pt(local.x - dir.x * margin, local.y - dir.y * margin)))
    }

    /** Which way a handle sits from the box centre, one unit per axis it moves along. */
    fun handleDirection(handle: HandleId): Pt = when (handle) {
        HandleId.TL -> Pt(-1.0, -1.0)
        HandleId.TR -> Pt(1.0, -1.0)
        HandleId.BL -> Pt(-1.0, 1.0)
        HandleId.BR -> Pt(1.0, 1.0)
        HandleId.L -> Pt(-1.0, 0.0)
        HandleId.R -> Pt(1.0, 0.0)
        HandleId.T -> Pt(0.0, -1.0)
        HandleId.B -> Pt(0.0, 1.0)
        else -> Pt.ZERO
    }

    /**
     * The scale a box-handle drag produces: a corner scales both axes by one factor (aspect-locked,
     * anchored at the opposite corner); an edge scales a single axis (anchored at the opposite
     * edge). Each axis is floored positive so the box can't shrink past [MIN_SIZE] or flip.
     */
    fun scaleForHandle(box: Rect, handle: HandleId, pointer: Pt): ScaleSpec = when (handle) {
        HandleId.L, HandleId.R -> {
            val anchorX = if (handle == HandleId.L) box.right else box.left
            val grabX = if (handle == HandleId.L) box.left else box.right
            ScaleSpec(Pt(anchorX, box.top), axisScale(pointer.x, anchorX, grabX), 1.0)
        }
        HandleId.T, HandleId.B -> {
            val anchorY = if (handle == HandleId.T) box.bottom else box.top
            val grabY = if (handle == HandleId.T) box.top else box.bottom
            ScaleSpec(Pt(box.left, anchorY), 1.0, axisScale(pointer.y, anchorY, grabY))
        }
        else -> {
            // A corner is aspect-locked: project the pointer onto the box diagonal from the opposite
            // (anchored) corner, so the one factor follows the drag and shrinks as well as grows.
            val anchor = cornerAnchor(box, handle)
            val s = diagonalScale(anchor, grabbedCorner(box, handle), pointer)
            ScaleSpec(anchor, s, s)
        }
    }

    private fun grabbedCorner(box: Rect, handle: HandleId): Pt = when (handle) {
        HandleId.TL -> box.topLeft
        HandleId.TR -> Pt(box.right, box.top)
        HandleId.BL -> Pt(box.left, box.bottom)
        else -> Pt(box.right, box.bottom) // BR
    }

    /** Single-axis scale from [anchor] toward [grab], floored at [MIN_SCALE] so the box can't
     *  collapse or flip. A ratio floor (not an absolute one) keeps thin selections from exploding. */
    private fun axisScale(pointer: Double, anchor: Double, grab: Double): Double {
        val denom = grab - anchor
        if (abs(denom) < 1e-9) return 1.0
        return max((pointer - anchor) / denom, MIN_SCALE)
    }

    /** Uniform scale from projecting [pointer] onto the [anchor]->[grab] diagonal, floored at
     *  [MIN_SCALE]. */
    private fun diagonalScale(anchor: Pt, grab: Pt, pointer: Pt): Double {
        val dx = grab.x - anchor.x
        val dy = grab.y - anchor.y
        val denom = dx * dx + dy * dy
        if (denom < 1e-9) return 1.0
        return max(((pointer.x - anchor.x) * dx + (pointer.y - anchor.y) * dy) / denom, MIN_SCALE)
    }

    private fun cornerAnchor(box: Rect, handle: HandleId): Pt = when (handle) {
        HandleId.TL -> Pt(box.right, box.bottom)
        HandleId.TR -> Pt(box.left, box.bottom)
        HandleId.BL -> Pt(box.right, box.top)
        else -> Pt(box.left, box.top) // BR
    }

    /** Aspect-locked image resize (page-local pointer). */
    fun resizeImage(old: Rect, handle: HandleId, pointer: Pt): Rect {
        val anchor = cornerAnchor(old, handle)
        val rawW = abs(pointer.x - anchor.x)
        val rawH = abs(pointer.y - anchor.y)
        var scale = max(rawW / old.w, rawH / old.h)
        scale = max(scale, max(MIN_SIZE / old.w, MIN_SIZE / old.h))
        val newW = old.w * scale
        val newH = old.h * scale
        val left = if (handle == HandleId.TL || handle == HandleId.BL) anchor.x - newW else anchor.x
        val top = if (handle == HandleId.TL || handle == HandleId.TR) anchor.y - newH else anchor.y
        return Rect(left, top, newW, newH)
    }

    /** Free (per-axis) closed-shape resize; returns new (start, end). */
    fun resizeClosedShape(start: Pt, end: Pt, handle: HandleId, pointer: Pt): Pair<Pt, Pt> {
        val anchor = cornerAnchor(Rect.fromPoints(start, end), handle)
        var px = pointer.x
        var py = pointer.y
        if (abs(px - anchor.x) < MIN_SIZE) px = anchor.x + if (px >= anchor.x) MIN_SIZE else -MIN_SIZE
        if (abs(py - anchor.y) < MIN_SIZE) py = anchor.y + if (py >= anchor.y) MIN_SIZE else -MIN_SIZE
        return anchor to Pt(px, py)
    }

    /** Open-shape resize: drag the grabbed endpoint, snapped onto a run every [step] (rad, null for none) from the other, which stays fixed. */
    fun resizeOpenShape(start: Pt, end: Pt, handle: HandleId, pointer: Pt, step: Double? = Math.PI / 4.0): Pair<Pt, Pt> = when {
        step == null -> if (handle == HandleId.START) pointer to end else start to pointer
        handle == HandleId.START -> snapAxis(end, pointer, step) to end
        else -> start to snapAxis(start, pointer, step)
    }

    /** Square-locked closed-shape resize (the perfect circle): the box stays square, sized by the
     *  larger pointer delta from the anchored corner. Returns new (start, end). */
    fun resizeSquareShape(start: Pt, end: Pt, handle: HandleId, pointer: Pt): Pair<Pt, Pt> {
        val anchor = cornerAnchor(Rect.fromPoints(start, end), handle)
        val side = max(max(abs(pointer.x - anchor.x), abs(pointer.y - anchor.y)), MIN_SIZE)
        val sx = if (pointer.x >= anchor.x) 1.0 else -1.0
        val sy = if (pointer.y >= anchor.y) 1.0 else -1.0
        return anchor to Pt(anchor.x + sx * side, anchor.y + sy * side)
    }

    /** A shape being drawn out puts its far end here for [pen]; [perfect] (the shape tool's Perfect
     *  switch) steps a line or arrow's angle and gives anything else its kind's perfect proportions. */
    fun drawnEnd(shape: ShapeItem, pen: Pt, perfect: Boolean): Pt {
        val kind = shape.shape
        return when {
            kind.isEndpointShape && perfect -> stepAngle(shape.start, pen, InteractionController.SHAPE_PERFECT_STEP_DEG)
            kind.isEndpointShape -> snapAxis(shape.start, pen)
            perfect -> aspectCorner(shape.start, pen, kind.perfectAspect(shape.n))
            kind.keepsAspect -> aspectCorner(shape.start, pen, 1.0)
            else -> pen
        }
    }

    /** [p] pinned onto the nearest run every [step] (rad) from [anchor] when it lands within
     *  [snapWindow] of one, as far along it as [p] reaches. */
    fun snapAxis(anchor: Pt, p: Pt, step: Double = Math.PI / 4.0): Pt {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        if (dx == 0.0 && dy == 0.0) return p
        val angle = atan2(dy, dx)
        val snapped = stickToStep(angle, step, snapWindow(step))
        return if (snapped == angle) p else pointAlong(anchor, p, snapped)
    }

    /** [p] dropped onto the run from [anchor] at [angle] (rad), as far along it as [p] reaches. */
    fun pointAlong(anchor: Pt, p: Pt, angle: Double): Pt {
        // Drop the rounding dust so a level or upright run comes out exact.
        val ux = cos(angle).let { if (abs(it) < 1e-12) 0.0 else it }
        val uy = sin(angle).let { if (abs(it) < 1e-12) 0.0 else it }
        val along = (p.x - anchor.x) * ux + (p.y - anchor.y) * uy
        return Pt(anchor.x + ux * along, anchor.y + uy * along)
    }

    /** A line end dragged to [pointer], its angle about the other end snapped by [snap]. Returns new (start, end). */
    fun snapOpenEnd(start: Pt, end: Pt, handle: HandleId, pointer: Pt, snap: AngleSnap): Pair<Pt, Pt> {
        val held = if (handle == HandleId.START) end else start
        val angle = atan2(pointer.y - held.y, pointer.x - held.x)
        val snapped = snap.snap(angle)
        val pen = if (snapped == angle) pointer else pointAlong(held, pointer, snapped)
        return resizeOpenShape(start, end, handle, pen, step = null)
    }

    /** How close (rad) an angle must come to a [step] to be pulled onto it: wide for big steps, a third of a small one. */
    fun snapWindow(step: Double): Double = minOf(InteractionController.ANGLE_SNAP_RAD, step / 3.0)

    /** Whether [angle] (rad) sits exactly on a 15-degree step, as every snap leaves it. */
    fun onSnapStep(angle: Double): Boolean {
        val k = angle / Math.toRadians(15.0)
        return abs(k - Math.round(k)) < 1e-9
    }

    /** The run from [a] to [b], carried on [overhang] past each end, or null when they coincide. */
    fun guideThrough(a: Pt, b: Pt, overhang: Double): Pair<Pt, Pt>? {
        val len = a.distanceTo(b)
        if (len == 0.0) return null
        val u = Pt((b.x - a.x) / len, (b.y - a.y) / len)
        return Pt(a.x - u.x * overhang, a.y - u.y * overhang) to Pt(b.x + u.x * overhang, b.y + u.y * overhang)
    }

    /** [angle] on the nearest whole multiple of [step] when within [tolerance] of it, otherwise left as it is. */
    fun stickToStep(angle: Double, step: Double, tolerance: Double): Double {
        val nearest = Math.round(angle / step) * step
        return if (abs(angle - nearest) <= tolerance) nearest else angle
    }

    /** The direction from [from] to [to] on screen (y down), in whole degrees clockwise from +x, 0..359. */
    fun angleDeg(from: Pt, to: Pt): Int = Math.round(Math.toDegrees(atan2(to.y - from.y, to.x - from.x))).toInt().mod(360)

    /** [p] turned about [anchor] onto the nearest whole step of [stepDeg], as far along it as [p] reaches. */
    fun stepAngle(anchor: Pt, p: Pt, stepDeg: Double): Pt {
        val dx = p.x - anchor.x
        val dy = p.y - anchor.y
        if (dx == 0.0 && dy == 0.0) return p
        val a = Math.toRadians(Math.round(Math.toDegrees(atan2(dy, dx)) / stepDeg) * stepDeg)
        // Rounded, so a quarter turn comes out exact and a flat line stays exactly flat.
        val ux = cos(a).let { if (abs(it) < 1e-12) 0.0 else it }
        val uy = sin(a).let { if (abs(it) < 1e-12) 0.0 else it }
        val along = dx * ux + dy * uy
        return Pt(anchor.x + ux * along, anchor.y + uy * along)
    }

    /** The far corner from [anchor] toward [p] of the smallest box [aspect] times as wide as tall that reaches [p]. */
    fun aspectCorner(anchor: Pt, p: Pt, aspect: Double): Pt {
        val h = max(abs(p.x - anchor.x) / aspect, abs(p.y - anchor.y))
        val sx = if (p.x >= anchor.x) 1.0 else -1.0
        val sy = if (p.y >= anchor.y) 1.0 else -1.0
        return Pt(anchor.x + sx * h * aspect, anchor.y + sy * h)
    }
}

/**
 * Snapping for one rotate or line-end drag. How fast it turns decides it: a quick turn snaps to
 * 45 degrees, a slower one not at all. A step once caught is held until the
 * drag leaves its window, so a change of speed past it never pulls the angle back, and a step just
 * left cannot catch again until the drag is clear of it. Turning slowly off a held step lets go
 * early, so a snapped angle can still be fine-tuned by hand.
 */
class AngleSnap {
    private var lastIn = Double.NaN
    private var lastOut = 0.0
    private var lastNanos = 0L
    private var radPerSec = 0.0
    private var held: Double? = null
    private var heldWindow = 0.0
    private var heldAtAnySpeed = false
    private var left: Double? = null

    fun reset() {
        lastIn = Double.NaN
        radPerSec = 0.0
        held = null
        left = null
    }

    /** The drag's [angle] (rad) at [nanos], snapped; with [quarterTurns] quarter turns catch from further away at any speed. */
    fun snap(angle: Double, quarterTurns: Boolean = false, nanos: Long = System.nanoTime()): Double {
        // The same angle again (a preview and then its commit) gets the same answer.
        if (angle == lastIn) return lastOut
        val prev = lastIn
        if (!prev.isNaN() && nanos > lastNanos) {
            val dt = (nanos - lastNanos) / 1e9
            radPerSec += (abs(wrap(angle - prev)) / dt - radPerSec) * (1.0 - exp(-dt / SMOOTH_SEC))
        }
        lastIn = angle
        lastNanos = nanos
        lastOut = pick(angle, prev, quarterTurns)
        return lastOut
    }

    private fun pick(angle: Double, prev: Double, quarterTurns: Boolean): Double {
        val step = TIERS.firstOrNull { radPerSec >= it.first }?.second
        held?.let { h ->
            val off = abs(wrap(angle - h))
            if (off <= heldWindow && (step != null || heldAtAnySpeed || off <= SLOW_RELEASE_RAD)) return angle + wrap(h - angle)
            left = h
            held = null
        }
        left?.let { if (abs(wrap(angle - it)) > InteractionController.ROTATE_SNAP_STICK_RAD) left = null }
        val quarter = Math.PI / 2.0
        val tries = listOfNotNull(
            if (quarterTurns) quarter to InteractionController.ROTATE_SNAP_STICK_RAD else null,
            step?.let { it to ResizeMath.snapWindow(it) },
        )
        for ((s, window) in tries) {
            val nearest = Math.round(angle / s) * s
            val justLeft = left?.let { abs(wrap(nearest - it)) < 1e-9 } ?: false
            // Only a step at or ahead of where the drag came from, so a snap never pulls back against the turn.
            val behind = !prev.isNaN() && wrap(nearest - prev) * wrap(angle - prev) < 0.0
            if (abs(angle - nearest) <= window && !justLeft && !behind) {
                held = nearest
                heldWindow = window
                heldAtAnySpeed = s == quarter && quarterTurns
                return nearest
            }
        }
        return angle
    }

    private fun wrap(a: Double): Double = atan2(sin(a), cos(a))

    companion object {
        /** (minimum turning speed, snap step), fastest first; below the last a drag is careful placing and stays free. */
        val TIERS = listOf(
            Math.toRadians(30.0) to Math.toRadians(45.0),
        )
        const val SMOOTH_SEC = 0.12

        /** How far a slow drag may creep off a held step before it lets go. */
        val SLOW_RELEASE_RAD = Math.toRadians(2.0)
    }
}
