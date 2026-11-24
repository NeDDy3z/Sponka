package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** How near an edge a freely dragged bar has to come to be pulled flush to it. */
internal val SNAP_REACH = 48.dp

/** Where a freely dragged bar at [at] settles: flush to each end of [xs] and [ys] it comes within [reach] of. */
internal fun snapToEdges(at: Offset, xs: ClosedFloatingPointRange<Float>, ys: ClosedFloatingPointRange<Float>, reach: Float): Offset {
    fun pull(v: Float, r: ClosedFloatingPointRange<Float>) = when {
        v - r.start < reach -> r.start
        r.endInclusive - v < reach -> r.endInclusive
        else -> v
    }
    return Offset(pull(at.x, xs), pull(at.y, ys))
}

/** The outline of where a carried bar of [size] would land, at [at] from [alignment], as the main toolbar shows it. */
@Composable
internal fun BoxScope.SnapMark(at: Offset, size: IntSize, alignment: Alignment) {
    val accent = LocalPalette.current.accent.toComposeColor()
    val shape = MaterialTheme.shapes.large
    val dp = with(LocalDensity.current) { androidx.compose.ui.unit.DpSize(size.width.toDp(), size.height.toDp()) }
    Box(
        Modifier.align(alignment).offset { at.round() }.size(dp)
            .background(accent.copy(alpha = 0.22f), shape).border(2.dp, accent, shape),
    )
}
