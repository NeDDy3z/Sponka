package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.xnotes.R
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/** A pill while formulas are recognized, and the other readings under a freshly converted formula. */
@Composable
fun MathChoicesStrip(editor: Editor) {
    val palette = LocalPalette.current
    if (editor.mathConverting > 0) {
        Box(Modifier.fillMaxSize().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.clip(MaterialTheme.shapes.extraLarge)
                    .background(palette.menuBg.toComposeColor())
                    .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.extraLarge)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.math_converting), color = palette.text.toComposeColor(), fontSize = 13.sp)
            }
        }
    }
    editor.mathError?.let { message ->
        Box(Modifier.fillMaxSize().padding(bottom = 24.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.clip(MaterialTheme.shapes.medium)
                    .background(palette.menuBg.toComposeColor())
                    .border(1.dp, palette.danger.toComposeColor(), MaterialTheme.shapes.medium)
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(message, color = palette.danger.toComposeColor(), fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                TextButton(onClick = editor::dismissMathError) { Text(stringResource(R.string.close), fontSize = 12.sp) }
            }
        }
    }
    val choices = editor.shownMathChoices ?: return
    val rect = editor.selectionMenu ?: return
    val density = LocalDensity.current
    // The selection bar sits above the formula when there is room, else below it; the strip goes under both.
    val barHeight = with(density) { 48.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val barBelow = rect.top.toFloat() - barHeight - gap <= 0f
    val y = rect.bottom.toFloat() + gap + if (barBelow) barHeight + gap else 0f
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    Row(
        Modifier
            .layout { measurable, constraints ->
                val margin = 8.dp.roundToPx()
                val strip = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)))
                val x = (centerX - strip.width / 2f).roundToInt().coerceIn(margin, (constraints.maxWidth - strip.width - margin).coerceAtLeast(margin))
                // A tall strip that would run off the bottom goes above the formula (and its bar) instead.
                val above = rect.top.toFloat() - gap - strip.height - if (barBelow) 0f else barHeight + gap
                val top = if (y + strip.height <= constraints.maxHeight - margin || above < margin) y else above
                layout(strip.width, strip.height) { strip.place(x, top.roundToInt()) }
            }
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val config = LatexConfig(fontSize = 16.sp, theme = latexTheme())
        // Every reading as tall as the tallest one (integral limits, fractions), so none is cut off.
        // Latex can't report intrinsic sizes, so the formulas are measured once on their own first.
        SubcomposeLayout { constraints ->
            val loose = Constraints(maxHeight = constraints.maxHeight)
            val pad = 12.dp.roundToPx()
            val tallest = subcompose("measure") { for (v in choices.variants) Latex(latex = v, config = config) }
                .maxOfOrNull { it.measure(loose).height } ?: 0
            val chipHeight = maxOf(tallest + pad, 44.dp.roundToPx())
            val spacing = 6.dp.roundToPx()
            val chips = subcompose("chips") {
                for (v in choices.variants) {
                    Box(
                        Modifier.height(with(density) { chipHeight.toDp() })
                            .clip(MaterialTheme.shapes.small)
                            .background(palette.surface.toComposeColor())
                            .clickable { editor.chooseMath(v) }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Latex(latex = v, config = config)
                    }
                }
            }.map { it.measure(loose) }
            val width = chips.sumOf { it.width } + spacing * (chips.size - 1).coerceAtLeast(0)
            layout(width, chipHeight) {
                var x = 0
                for (c in chips) {
                    c.place(x, 0)
                    x += c.width + spacing
                }
            }
        }
        TextButton(onClick = { editor.dismissMathChoices() }) { Text(stringResource(R.string.close), fontSize = 12.sp) }
    }
}
