package com.xnotes.ui

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.R
import com.xnotes.canvas.StylusButtonLatch
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.platform.MathTraining
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.text.DateFormat
import java.util.Date

/** Simple to hard, so the model sees each kind of symbol and layout this user writes. */
internal val EXERCISES = listOf(
    "x^{2}",
    "a+b=c",
    "\\frac{a}{b}",
    "\\sqrt{x+1}",
    "y=2x-3",
    "a^{2}+b^{2}=c^{2}",
    "x_{1}+x_{2}",
    "\\alpha+\\beta=\\gamma",
    "\\sum_{i=1}^{n}i",
    "\\int_{0}^{1}x^{2}dx",
    "\\lim_{x\\to0}\\frac{\\sin x}{x}=1",
    "x=\\frac{-b\\pm\\sqrt{b^{2}-4ac}}{2a}",
)

private val HOVER_ACTIONS = setOf(MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT)

/** A training run can be finished once this many formulas are written. */
internal const val MIN_TRAINED = 10

private val TIPS = listOf(
    R.string.math_tip_fractions,
    R.string.math_tip_exponents,
    R.string.math_tip_spacing,
    R.string.math_tip_letters,
    R.string.math_tip_size,
)

private enum class Phase { INTRO, WRITING, DONE }

/**
 * Training for math model [modelId]. It starts with instructions and the past trainings (each can
 * be deleted), then a pad for formulas from simple to hard. Next hands each written formula to the
 * model in the background and learns what it read against what was asked; every run adds to the
 * ones before. The writing is kept, so other models can learn from it too.
 */
@Composable
fun MathPractice(modelId: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val palette = LocalPalette.current
    val context = LocalContext.current
    var phase by remember { mutableStateOf(Phase.INTRO) }
    var session by remember { mutableStateOf(MathTraining.newSession()) }
    var index by remember { mutableIntStateOf(0) }
    var written by remember { mutableIntStateOf(0) }
    var erasing by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<SnapshotStateList<Offset>>() }
    var historyVersion by remember { mutableIntStateOf(0) }
    val accent = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor())

    fun begin() {
        session = MathTraining.newSession()
        index = 0
        written = 0
        lines.clear()
        phase = Phase.WRITING
    }

    /** Hand the pad to the model for [EXERCISES]`[index]`, if anything is written, and move on. */
    fun next() {
        val points = lines.flatten()
        if (points.isNotEmpty()) {
            val strokes = lines.map { line -> Stroke(Tool.PEN, ToolConfig(), line.map { Sample(it.x.toDouble(), it.y.toDouble(), 1.0) }) }
            val left = points.minOf { it.x }.toDouble()
            val top = points.minOf { it.y }.toDouble()
            val bounds = Rect(left, top, points.maxOf { it.x } - left, points.maxOf { it.y } - top)
            MathTraining.trainLater(context, modelId, session, EXERCISES[index], strokes, bounds)
            written++
        }
        lines.clear()
        if (index + 1 < EXERCISES.size) index++ else phase = Phase.DONE
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(palette.bg.toComposeColor()).systemBarsPadding()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.math_practice_title), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.weight(1f))
                    // The last page closes with its own button.
                    if (phase != Phase.DONE) TextButton(onClick = onBack) { Text(stringResource(R.string.close), fontSize = 13.sp) }
                }
                when (phase) {
                    Phase.INTRO -> {
                        Text(stringResource(R.string.math_training_instructions, EXERCISES.size, MIN_TRAINED), color = palette.text.toComposeColor(), fontSize = 14.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (tip in TIPS) Bullet(stringResource(tip))
                        }
                        Button(onClick = ::begin, colors = accent) { Text(stringResource(R.string.math_training_begin)) }
                        TrainingHistory(historyVersion) { historyVersion++ }
                    }
                    Phase.WRITING -> WritingPad(index, written, erasing, { erasing = it }, lines, ::next) { phase = Phase.DONE }
                    Phase.DONE -> {
                        Text(stringResource(R.string.math_training_done, written), color = palette.text.toComposeColor(), fontSize = 14.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = ::begin) { Text(stringResource(R.string.math_training_again)) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = onBack, colors = accent) { Text(stringResource(R.string.close)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    val palette = LocalPalette.current
    Row {
        Text("-", color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Text(text, color = palette.textDim.toComposeColor(), fontSize = 13.sp)
    }
}

/** The past training runs, newest first, each with a delete, plus Delete all. */
@Composable
private fun TrainingHistory(version: Int, onChanged: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val sessions = remember(version) { MathTraining.sessions(context) }
    var confirmAll by remember { mutableStateOf(false) }
    Text(stringResource(R.string.math_training_past), color = palette.text.toComposeColor(), fontWeight = FontWeight.Bold, fontSize = 14.sp)
    if (sessions.isEmpty()) {
        Text(stringResource(R.string.math_training_none), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
        return
    }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    for (s in sessions) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.math_training_session, format.format(Date(s.time)), s.count),
                color = palette.text.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { MathTraining.deleteSession(context, s.id); onChanged() }) { Text(stringResource(R.string.delete), fontSize = 13.sp) }
        }
    }
    TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.math_training_delete_all), fontSize = 13.sp, color = palette.danger.toComposeColor()) }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            text = { Text(stringResource(R.string.math_training_delete_all_confirm)) },
            confirmButton = { TextButton(onClick = { MathTraining.deleteAll(context); confirmAll = false; onChanged() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * One formula to write: the target, a pen/eraser toolbar and the pad. The stylus button or the
 * stylus's eraser end erase too, as on the canvas.
 */
@Composable
private fun WritingPad(
    index: Int,
    written: Int,
    erasing: Boolean,
    onErasing: (Boolean) -> Unit,
    lines: SnapshotStateList<SnapshotStateList<Offset>>,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    val palette = LocalPalette.current
    val target = EXERCISES[index]
    val ink = palette.text.toComposeColor()
    val accent = ButtonDefaults.buttonColors(containerColor = palette.accent.toComposeColor(), contentColor = palette.bg.toComposeColor())
    val outline = LatexConfig(fontSize = 64.sp, theme = LatexTheme.light(ink.copy(alpha = 0.15f), Color.Transparent))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.math_training_formula, index + 1, EXERCISES.size), color = palette.accent.toComposeColor(), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.math_training_written, written, MIN_TRAINED), color = palette.textDim.toComposeColor(), fontSize = 13.sp)
    }
    Latex(latex = target, config = LatexConfig(fontSize = 22.sp, theme = latexTheme()))
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Tapping the pen while it is active switches to the eraser, like the canvas toolbar.
        IconToggleButton(checked = !erasing, onCheckedChange = { onErasing(!erasing) }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_stroke_regular), stringResource(R.string.math_tool_pen), tint = if (erasing) palette.textDim.toComposeColor() else palette.accent.toComposeColor())
        }
        IconToggleButton(checked = erasing, onCheckedChange = { onErasing(it) }) {
            Icon(XnotesIcons.eraser, stringResource(R.string.math_tool_eraser), tint = if (erasing) palette.accent.toComposeColor() else palette.textDim.toComposeColor())
        }
        TextButton(onClick = { lines.clear() }, enabled = lines.isNotEmpty()) { Text(stringResource(R.string.math_practice_clear), fontSize = 13.sp) }
    }
    Box(
        Modifier.fillMaxWidth().height(220.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        // A faint copy of the target to write over; only the user's strokes go to the model.
        Latex(latex = target, config = outline)
        // Raw touch events and the canvas's own button latch, so every pen the canvas knows erases
        // here too (button in the touch stream or sent as a key). The toggle is read fresh per event.
        val latch = remember { StylusButtonLatch() }
        val eraserTool by rememberUpdatedState(erasing)
        val reach = with(LocalDensity.current) { 12.dp.toPx() }
        val drawing = remember { arrayOfNulls<SnapshotStateList<Offset>>(1) }
        // Claimed on down, or the page's scroll takes the stroke over and cancels it after a few pixels.
        val keep = remember { RequestDisallowInterceptTouchEvent() }
        Canvas(
            Modifier.fillMaxSize()
                .pointerInteropFilter(keep) { e ->
                    if (e.isFromSource(android.view.InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked in HOVER_ACTIONS) {
                        // Some pens report the side button only while hovering.
                        latch.onGenericMotion(e)
                        return@pointerInteropFilter false
                    }
                    val rubbing = erasingNow(eraserTool, e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER, latch.heldFor(e))
                    fun at(p: Offset) {
                        if (rubbing) {
                            drawing[0] = null
                            lines.removeAll { line -> line.any { (it - p).getDistance() < reach } }
                        } else {
                            drawing[0]?.add(p) ?: mutableStateListOf(p).also { drawing[0] = it; lines += it }
                        }
                    }
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            keep(true)
                            drawing[0] = null
                            at(Offset(e.x, e.y))
                        }
                        MotionEvent.ACTION_MOVE -> {
                            for (h in 0 until e.historySize) at(Offset(e.getHistoricalX(h), e.getHistoricalY(h)))
                            at(Offset(e.x, e.y))
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drawing[0] = null
                    }
                    true
                },
        ) {
            val width = 3.dp.toPx()
            for (line in lines) {
                if (line.size == 1) {
                    drawCircle(ink, width / 2, line[0])
                    continue
                }
                val path = Path().apply {
                    moveTo(line[0].x, line[0].y)
                    for (i in 1 until line.size) lineTo(line[i].x, line[i].y)
                }
                drawPath(path, ink, style = DrawStroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (written >= MIN_TRAINED) TextButton(onClick = onFinish) { Text(stringResource(R.string.math_training_finish), fontSize = 13.sp) }
        Spacer(Modifier.weight(1f))
        Button(onClick = onNext, colors = accent) { Text(stringResource(R.string.math_practice_next)) }
    }
}

/** The eraser tool, the stylus's eraser end, or its side button held ([buttonHeld] is stylus-only already) rub out lines. */
internal fun erasingNow(eraserTool: Boolean, eraserEnd: Boolean, buttonHeld: Boolean): Boolean =
    eraserTool || eraserEnd || buttonHeld
