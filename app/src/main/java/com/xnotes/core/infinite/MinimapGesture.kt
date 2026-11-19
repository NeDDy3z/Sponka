package com.xnotes.core.infinite

import android.os.Handler
import android.os.Looper
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect

/**
 * The one minimap gesture, driven identically by the paged note and the infinite canvas.
 *
 * A press on the panel becomes, as the finger moves or a hold timer fires:
 *  - a tap (no move): jump the view to the pressed content point,
 *  - a drag past the tap slop: slide the viewport square (the content point under the finger stays
 *    under it),
 *  - a still hold past [Host.longPressMs]: drag the whole panel to a new corner.
 *
 * All the geometry is [Minimap]'s; this class only holds the state machine. The [Host] maps points,
 * moves the view, persists the panel's clamped offset and asks for a redraw, so the two canvases
 * behave the same while each keeps its own renderer (Canvas2D vs GL).
 */
class MinimapGesture(private val host: Host) {

    interface Host {
        /** How long a still press holds before it starts moving the panel, in ms. */
        val longPressMs: Long

        /** Finger drift (viewport px) past which a press is a square drag rather than a tap. */
        val tapSlopPx: Double

        /** The panel's rectangle for a given offset from its default corner (clamped on screen). */
        fun panelAt(offsetX: Double, offsetY: Double): Rect

        /** The content region the panel maps: everything drawn, unioned with the viewport. */
        fun extent(): Rect

        /** The panel's current offset from its default corner. */
        fun currentOffset(): Pt

        /** Persist [offset] as the panel's new position (already clamped by [panelAt]). */
        fun setOffset(offset: Pt)

        /** Centre the view on content point [p], so a tap-jump and a square drag both land there. */
        fun centerOnContent(p: Pt)

        /** The panel was picked up for a move: highlight it and confirm with a haptic. */
        fun onPickUp()

        /** The gesture ended (tap, square drag, or panel move): let the host persist and redraw. */
        fun onFinish()

        /** Ask for a redraw. */
        fun render()
    }

    enum class State { IDLE, PENDING, PAN, MOVE }

    var state: State = State.IDLE
        private set

    private val handler = Handler(Looper.getMainLooper())

    private var downAt = Pt.ZERO
    private var downOffset = Pt.ZERO
    private var downContent = Pt.ZERO
    private var downExtent = Rect(0.0, 0.0, 1.0, 1.0)
    private var downPanel = Rect(0.0, 0.0, 1.0, 1.0)
    private var longPress: Runnable? = null

    /** True while a gesture is live (pending, sliding the square, or moving the panel). */
    val active: Boolean get() = state != State.IDLE

    /** A press landed on the panel: hold the gesture for a tap-jump, square drag, or panel move. */
    fun onDown(vx: Double, vy: Double) {
        downAt = Pt(vx, vy)
        downOffset = host.currentOffset()
        downPanel = host.panelAt(downOffset.x, downOffset.y)
        downExtent = host.extent()
        downContent = Minimap.toContent(downAt, downExtent, downPanel)
        state = State.PENDING
        val r = Runnable {
            longPress = null
            if (state == State.PENDING) {
                state = State.MOVE
                host.onPickUp()
                host.render()
            }
        }
        longPress = r
        handler.postDelayed(r, host.longPressMs)
    }

    fun onMove(vx: Double, vy: Double) {
        when (state) {
            State.PENDING ->
                // Past the slop the press is a square drag; the still-hold timer would have made it
                // a panel move, so it is cancelled now that the finger has chosen.
                if (Pt(vx, vy).distanceTo(downAt) > host.tapSlopPx) {
                    cancelLongPress()
                    state = State.PAN
                    panTo(vx, vy)
                }
            State.PAN -> panTo(vx, vy)
            State.MOVE -> movePanel(vx, vy)
            State.IDLE -> Unit
        }
    }

    fun onUp() {
        if (state == State.PENDING) {
            // A tap: jump to the pressed point. A drag would have moved the state on already.
            host.centerOnContent(downContent)
            host.render()
        }
        val wasActive = state != State.IDLE
        cancelLongPress()
        state = State.IDLE
        if (wasActive) host.onFinish()
    }

    /** A second finger, or the OS taking the touch back: drop the gesture where it is. */
    fun onCancel() {
        cancelLongPress()
        state = State.IDLE
    }

    /** Drag the viewport square: the content point under the finger at touch-down stays under it. */
    private fun panTo(vx: Double, vy: Double) {
        val scale = Minimap.scaleFor(downExtent, downPanel)
        if (scale <= 0.0) return
        host.centerOnContent(
            Pt(
                downContent.x + (vx - downAt.x) / scale,
                downContent.y + (vy - downAt.y) / scale,
            ),
        )
        host.render()
    }

    private fun movePanel(vx: Double, vy: Double) {
        // Back-solve the clamped offset so the panel sticks to the screen edge rather than winding
        // up far away and taking a long drag back.
        val p = host.panelAt(
            downOffset.x + (vx - downAt.x),
            downOffset.y + (vy - downAt.y),
        )
        val base = host.panelAt(0.0, 0.0)
        host.setOffset(Pt(p.left - base.left, p.top - base.top))
        host.render()
    }

    private fun cancelLongPress() {
        longPress?.let { handler.removeCallbacks(it) }
        longPress = null
    }
}