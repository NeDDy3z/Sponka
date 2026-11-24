package com.xnotes.canvas

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.ImageData
import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.TextItem
import com.xnotes.core.shapes.ArrowKinds
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.shapes.ShapeParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2

class ResizeMathTest {

    @Test fun imageResizeIsAspectLocked() {
        val r = ResizeMath.resizeImage(Rect(0.0, 0.0, 100.0, 50.0), HandleId.BR, Pt(200.0, 60.0))
        // scale = max(200/100, 60/50) = 2 -> 200x100, anchored at TL
        assertEquals(Rect(0.0, 0.0, 200.0, 100.0), r)
    }

    @Test fun imageResizeAnchorsOppositeCorner() {
        val r = ResizeMath.resizeImage(Rect(10.0, 10.0, 100.0, 50.0), HandleId.TL, Pt(-90.0, -40.0))
        // anchor = BR (110,60); rawW=200,rawH=100 -> scale 2 -> 200x100 from BR
        assertEquals(110.0, r.right, 1e-9)
        assertEquals(60.0, r.bottom, 1e-9)
        assertEquals(200.0, r.w, 1e-9)
    }

    @Test fun imageResizeEnforcesMinSize() {
        val r = ResizeMath.resizeImage(Rect(0.0, 0.0, 100.0, 100.0), HandleId.BR, Pt(5.0, 5.0))
        assertTrue(r.w >= ResizeMath.MIN_SIZE && r.h >= ResizeMath.MIN_SIZE)
    }

    @Test fun closedShapeResizesFreely() {
        val (s, e) = ResizeMath.resizeClosedShape(Pt(0.0, 0.0), Pt(100.0, 50.0), HandleId.BR, Pt(200.0, 60.0))
        assertEquals(Pt(0.0, 0.0), s)
        assertEquals(Pt(200.0, 60.0), e) // not aspect-locked
    }

    @Test fun openShapeDragsGrabbedEndpoint() {
        val (s, e) = ResizeMath.resizeOpenShape(Pt(0.0, 0.0), Pt(100.0, 0.0), HandleId.END, Pt(50.0, 50.0))
        assertEquals(Pt(0.0, 0.0), s)
        assertEquals(Pt(50.0, 50.0), e)
    }

    @Test fun openShapeEndSnapsLevelAndUprightNearAnAxis() {
        assertEquals(Pt(100.0, 0.0), ResizeMath.resizeOpenShape(Pt(0.0, 0.0), Pt(50.0, 50.0), HandleId.END, Pt(100.0, 3.0)).second)
        assertEquals(Pt(50.0, 100.0), ResizeMath.resizeOpenShape(Pt(0.0, 0.0), Pt(50.0, 0.0), HandleId.START, Pt(53.0, 100.0)).first)
    }

    /** Feed [snap] the angles [degs], one 8 ms frame apart from [frame0]; the snapped angles, in degrees. */
    private fun feed(snap: AngleSnap, degs: List<Double>, quarterTurns: Boolean = false, frame0: Int = 0): List<Double> =
        degs.mapIndexed { i, d -> Math.toDegrees(snap.snap(Math.toRadians(d), quarterTurns, (frame0 + i) * 8_000_000L)) }

    private fun ramp(from: Double, step: Double, n: Int) = List(n) { from + it * step }

    @Test fun aQuickTurnHoldsFortyFiveUntilItLeavesTheWindow() {
        val out = feed(AngleSnap(), ramp(0.0, 3.0, 19)) // 375 deg/s up to 54
        assertEquals(36.0, out[12], 1e-9)
        for (k in 13..17) assertEquals(45.0, out[k], 1e-9)
        assertEquals(54.0, out[18], 1e-9)
    }

    @Test fun aCarefulTurnNeverSnaps() {
        val inputs = ramp(40.0, 0.1, 100) // 12.5 deg/s, across 45
        feed(AngleSnap(), inputs).forEachIndexed { k, out -> assertEquals(inputs[k], out, 1e-9) }
    }

    @Test fun aMediumTurnStaysFree() {
        val inputs = ramp(25.0, 0.1, 240) // 12.5 deg/s, across 30 and 45
        feed(AngleSnap(), inputs).forEachIndexed { k, out -> assertEquals(inputs[k], out, 1e-9) }
    }

    @Test fun snappingNeverPullsBackAgainstTheTurn() {
        var a = 0.0
        val inputs = List(400) { i -> a += if ((i / 20) % 2 == 0) 2.5 else 0.2; a } // quick and slow in turns
        val out = feed(AngleSnap(), inputs)
        for (k in 1 until out.size) assertTrue("frame $k went back: ${out[k - 1]} -> ${out[k]}", out[k] >= out[k - 1] - 1e-9)
    }

    @Test fun creepingSlowlyOffAHeldStepLetsGo() {
        val snap = AngleSnap()
        feed(snap, ramp(0.0, 3.0, 16)) // quick, onto 45
        val out = feed(snap, ramp(45.05, 0.05, 100), frame0 = 16)
        assertEquals(45.0, out.first(), 1e-9)
        assertEquals(50.0, out.last(), 1e-9)
    }

    @Test fun quarterTurnSettingCatchesFromFurtherAtAnySpeed() {
        assertEquals(90.0, feed(AngleSnap(), ramp(75.0, 0.1, 61), quarterTurns = true).last(), 1e-9)
        assertEquals(81.0, feed(AngleSnap(), ramp(75.0, 0.1, 61)).last(), 1e-9)
    }

    @Test fun snappedAnglesAndRunsLandExactlyOnTheirSteps() {
        val deg = Math.PI / 180.0
        assertTrue(ResizeMath.onSnapStep(-135.0 * deg))
        assertFalse(ResizeMath.onSnapStep(39.0 * deg))
        assertEquals(Pt(100.0, 3.0), ResizeMath.resizeOpenShape(Pt(0.0, 0.0), Pt(50.0, 50.0), HandleId.END, Pt(100.0, 3.0), step = null).second)
        val thirty = ResizeMath.snapAxis(Pt(0.0, 0.0), Pt(100.0, 60.0), 30.0 * deg)
        assertEquals(30.0 * deg, atan2(thirty.y, thirty.x), 1e-12)
        assertTrue(ResizeMath.onSnapStep(atan2(thirty.y, thirty.x)))
    }

    @Test fun openShapeEndSnapsToDiagonalsAndStaysFreeBetween() {
        val diag = ResizeMath.snapAxis(Pt(0.0, 0.0), Pt(100.0, 95.0))
        assertEquals(97.5, diag.x, 1e-9)
        assertEquals(97.5, diag.y, 1e-9)
        assertEquals(Pt(100.0, 70.0), ResizeMath.snapAxis(Pt(0.0, 0.0), Pt(100.0, 70.0)))
        assertEquals(Pt(0.0, -100.0), ResizeMath.snapAxis(Pt(0.0, 0.0), Pt(-8.0, -100.0)))
    }

    @Test fun angleReadsClockwiseOnScreen() {
        assertEquals(0, ResizeMath.angleDeg(Pt(0.0, 0.0), Pt(10.0, 0.0)))
        assertEquals(90, ResizeMath.angleDeg(Pt(0.0, 0.0), Pt(0.0, 10.0)))
        assertEquals(180, ResizeMath.angleDeg(Pt(0.0, 0.0), Pt(-10.0, 0.0)))
        assertEquals(270, ResizeMath.angleDeg(Pt(0.0, 0.0), Pt(0.0, -10.0)))
        assertEquals(0, ResizeMath.angleDeg(Pt(0.0, 0.0), Pt(1000.0, 1.0)))
    }






    @Test fun imageHandlesAreFourCorners() {
        val img = ImageItem(ImageData(java.io.File("test-image"),10, 10), Rect(0.0, 0.0, 100.0, 50.0))
        val handles = ResizeMath.handles(img, Pt(48.0, 48.0))
        assertEquals(4, handles.size)
        assertTrue(handles.any { it.id == HandleId.TL && it.content == Pt(48.0, 48.0) })
        assertTrue(handles.any { it.id == HandleId.BR && it.content == Pt(148.0, 98.0) })
    }

    @Test fun textHandlesAreCornersAndSides() {
        val t = TextItem(Pt(0.0, 0.0), width = 100.0, text = "x", measurer = FakeTextMeasurer())
        val handles = ResizeMath.handles(t, Pt(0.0, 0.0))
        // Height follows the text, so there is no top or bottom to drag.
        assertEquals(setOf(HandleId.TL, HandleId.TR, HandleId.R, HandleId.BR, HandleId.BL, HandleId.L), handles.map { it.id }.toSet())
        assertEquals(6, handles.size)
    }

    @Test fun boxHandlesAreEightAroundTheBox() {
        val handles = ResizeMath.boxHandles(Rect(0.0, 0.0, 100.0, 50.0))
        assertEquals(8, handles.size)
        assertEquals(setOf(HandleId.TL, HandleId.T, HandleId.TR, HandleId.R, HandleId.BR, HandleId.B, HandleId.BL, HandleId.L), handles.map { it.id }.toSet())
        assertTrue(handles.any { it.id == HandleId.R && it.content == Pt(100.0, 25.0) })
    }

    @Test fun cornerScaleIsUniformFromOppositeCorner() {
        val sc = ResizeMath.scaleForHandle(Rect(0.0, 0.0, 100.0, 50.0), HandleId.BR, Pt(150.0, 75.0))
        assertEquals(Pt(0.0, 0.0), sc.anchor) // anchored at the opposite (TL) corner
        assertEquals(1.5, sc.sx, 1e-9)
        assertEquals(1.5, sc.sy, 1e-9) // aspect-locked
    }

    @Test fun edgeScaleIsSingleAxis() {
        val sc = ResizeMath.scaleForHandle(Rect(0.0, 0.0, 100.0, 50.0), HandleId.R, Pt(150.0, 999.0))
        assertEquals(0.0, sc.anchor.x, 1e-9) // anchored at the left edge
        assertEquals(1.5, sc.sx, 1e-9)
        assertEquals(1.0, sc.sy, 1e-9) // the other axis is untouched
    }

    @Test fun scaleClampsAwayFromCollapse() {
        val sc = ResizeMath.scaleForHandle(Rect(0.0, 0.0, 100.0, 50.0), HandleId.R, Pt(1.0, 0.0))
        assertEquals(ResizeMath.MIN_SCALE, sc.sx, 1e-9) // floored so the box can't collapse or flip
    }

    @Test fun thinSelectionEdgeScaleDoesNotExplode() {
        // A 4px-tall selection (e.g. a horizontal stroke) must not force a huge minimum scale.
        val sc = ResizeMath.scaleForHandle(Rect(0.0, 0.0, 100.0, 4.0), HandleId.B, Pt(50.0, 6.0))
        assertEquals(1.5, sc.sy, 1e-9)
    }

    @Test fun cornerShrinksWhenDraggedInward() {
        // Diagonal projection lets a corner shrink, not only grow.
        val sc = ResizeMath.scaleForHandle(Rect(0.0, 0.0, 100.0, 50.0), HandleId.BR, Pt(50.0, 25.0))
        assertEquals(0.5, sc.sx, 1e-9)
        assertEquals(0.5, sc.sy, 1e-9)
    }

    @Test fun obbHandlesAreEightAtCorners() {
        val h = ResizeMath.obbHandles(Obb.fromAabb(Rect(0.0, 0.0, 100.0, 50.0)))
        assertEquals(8, h.size)
        assertTrue(h.any { it.id == HandleId.TL && it.content == Pt(0.0, 0.0) })
        assertTrue(h.any { it.id == HandleId.BR && it.content == Pt(100.0, 50.0) })
    }

    @Test fun obbCornerResizeAnchorsOppositeCorner() {
        val res = ResizeMath.obbResize(Obb.fromAabb(Rect(0.0, 0.0, 100.0, 50.0)), HandleId.BR, Pt(150.0, 75.0))
        // BR dragged 1.5x about the TL corner: box spans (0,0)..(150,75).
        assertEquals(75.0, res.obb.halfW, 1e-9)
        assertEquals(37.5, res.obb.halfH, 1e-9)
        assertEquals(Pt(0.0, 0.0), res.obb.corners()[0])
        assertEquals(Pt(150.0, 75.0), res.obb.corners()[2])
    }

    @Test fun obbResizeTransformReproducesNewBox() {
        // The baked transform applied to the old corners must reproduce the new box's corners,
        // even when the box is tilted (so the chrome and the items never disagree).
        val obb = Obb(Pt(50.0, 30.0), 50.0, 30.0, 0.4)
        val pointer = obb.localToWorld(Pt(80.0, 50.0)) // drag the BR corner outward in the local frame
        val res = ResizeMath.obbResize(obb, HandleId.BR, pointer)
        obb.corners().zip(res.obb.corners()).forEach { (oldC, newC) ->
            val t = res.transform.apply(oldC)
            assertEquals(newC.x, t.x, 1e-6)
            assertEquals(newC.y, t.y, 1e-6)
        }
    }

    private fun drawing(kind: ShapeKind, n: Int = kind.defaultN) =
        ShapeItem(kind, Pt(10.0, 20.0), Pt(10.0, 20.0), Rgba(0, 0, 0), n = n)

    @Test fun perfectSquaresABox() {
        for (k in listOf(ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.ROUNDED_RECT, ShapeKind.HEART)) {
            val s = drawing(k)
            assertEquals("$k free", Pt(110.0, 60.0), ResizeMath.drawnEnd(s, Pt(110.0, 60.0), perfect = false))
            assertEquals("$k square, reaching the pen", Pt(110.0, 120.0), ResizeMath.drawnEnd(s, Pt(110.0, 60.0), perfect = true))
            assertEquals("$k up and left of its start", Pt(-70.0, -60.0), ResizeMath.drawnEnd(s, Pt(-30.0, -60.0), perfect = true))
        }
        assertEquals("a circle is round without one", Pt(110.0, 120.0), ResizeMath.drawnEnd(drawing(ShapeKind.CIRCLE), Pt(110.0, 60.0), perfect = false))
    }

    @Test fun perfectKeepsALineToFifteenDegreeSteps() {
        for (k in listOf(ShapeKind.LINE, ShapeKind.ARROW)) {
            val s = drawing(k)
            val end = ResizeMath.drawnEnd(s, Pt(110.0, 50.0), perfect = true)
            assertEquals("$k at 17 degrees goes to 15", 15.0, Math.toDegrees(atan2(end.y - 20.0, end.x - 10.0)), 1e-9)
            assertEquals("$k flat stays exactly flat", 20.0, ResizeMath.drawnEnd(s, Pt(110.0, 26.0), perfect = true).y, 0.0)
            assertEquals("$k upright stays exactly upright", 10.0, ResizeMath.drawnEnd(s, Pt(16.0, -80.0), perfect = true).x, 0.0)
            assertEquals("$k free", Pt(110.0, 50.0), ResizeMath.drawnEnd(s, Pt(110.0, 50.0), perfect = false))
        }
    }

    @Test fun perfectMakesPolygonsStarsAndTrianglesRegular() {
        fun sides(k: ShapeKind, n: Int): List<Double> {
            val s = drawing(k, n)
            s.end = ResizeMath.drawnEnd(s, Pt(130.0, 70.0), perfect = true)
            val c = k.build(s.box, ShapeParams(k.defaultAdj, n)).corners
            return c.indices.map { c[it].distanceTo(c[(it + 1) % c.size]) }
        }
        for (n in 3..12) {
            val polygon = sides(ShapeKind.REGULAR_POLYGON, n)
            for (d in polygon) assertEquals("$n sides", polygon[0], d, 1e-9)
            val star = sides(ShapeKind.STAR, n)
            for (d in star) assertEquals("$n points", star[0], d, 1e-9)
        }
        val triangle = sides(ShapeKind.TRIANGLE, 0)
        for (d in triangle) assertEquals("equilateral", triangle[0], d, 1e-9)
    }

    @Test fun perfectKeepsPillsAndDoubleArrowsLong() {
        for (k in listOf(ShapeKind.CAPSULE, ArrowKinds.LEFT_RIGHT)) {
            assertEquals("$k twice as wide as tall", Pt(210.0, 120.0), ResizeMath.drawnEnd(drawing(k), Pt(110.0, 120.0), perfect = true))
        }
        assertEquals("twice as tall as wide", Pt(110.0, 220.0), ResizeMath.drawnEnd(drawing(ArrowKinds.UP_DOWN), Pt(110.0, 120.0), perfect = true))
    }
}
