package com.xnotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.OverlayTessellator
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

/**
 * A canvas that can flash brief action feedback ("Deleted", "Copied", ...). Both the
 * paged [Editor] and the infinite editor implement it so one pill composable serves both.
 * [toastToken] bumps on every message so re-showing the same text still re-arms the pill.
 */
interface ToastHost {
    val toastText: String?
    val toastToken: Int
}

/**
 * A small themed pill at the bottom-centre of the canvas, mirroring the flat surface + 1dp
 * border chrome the app uses everywhere (see ZoomLockHint). Auto-dismisses after a short beat.
 */
@Composable
fun BoxScope.CanvasToastPill(host: ToastHost) {
    val palette = LocalPalette.current
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }
    var armToken by remember { mutableStateOf(0) }
    LaunchedEffect(host.toastToken) {
        if (host.toastToken > 0 && !host.toastText.isNullOrEmpty()) {
            shown = host.toastText
            visible = true
            armToken++
        }
    }
    LaunchedEffect(armToken) {
        if (armToken > 0) {
            delay(1500)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Text(
            text = shown ?: "",
            color = palette.text.toComposeColor(),
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** The angle of a line end or rotation being dragged, [at] the pen in viewport pixels, and the dashed
 *  [guide] the snap lies along while the angle sits on a 45-degree step. */
data class AngleReadout(val at: Pt, val deg: Int, val guide: Pair<Pt, Pt>?)

/** [InfiniteEditor.angleReadout] in the same pill as the other readouts, up and right of the pen. */
@Composable
fun AngleReadoutPill(canvas: InfiniteEditor) {
    val (at, deg, guide) = canvas.angleReadout ?: return
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    if (guide != null) {
        val accent = palette.accent.toComposeColor()
        Canvas(Modifier.fillMaxSize()) {
            val on = OverlayTessellator.DASH_ON_DP.dp.toPx()
            val gap = OverlayTessellator.DASH_GAP_DP.dp.toPx()
            drawLine(
                accent,
                Offset(guide.first.x.toFloat(), guide.first.y.toFloat()),
                Offset(guide.second.x.toFloat(), guide.second.y.toFloat()),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, gap)),
            )
        }
    }
    Text(
        text = "$deg°",
        color = palette.text.toComposeColor(),
        fontSize = 13.sp,
        modifier = Modifier
            .offset { IntOffset((at.x + 24.dp.toPx()).toInt(), (at.y - 56.dp.toPx()).toInt()) }
            .clip(shape)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
