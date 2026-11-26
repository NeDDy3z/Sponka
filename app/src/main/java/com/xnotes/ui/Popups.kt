package com.xnotes.ui

import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.core.shapes.ShapePreset
import com.xnotes.core.shapes.ShapePresets
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
import com.xnotes.canvas.PdfColorFilter
import com.xnotes.canvas.ReaderPageMode
import com.xnotes.canvas.ReaderSettings
import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageEdge
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageSizeChoice
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.TapeMode
import com.xnotes.core.model.TapePattern
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TemplateMemory
import com.xnotes.core.model.TemplateSettings
import com.xnotes.core.pal.FontFace
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.MarkupMode
import com.xnotes.core.tools.SelectKind
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolConversions
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.platform.FontCatalog
import com.xnotes.platform.TemplateLibrary
import com.xnotes.settings.Preferences
import com.xnotes.settings.SwatchRole
import com.xnotes.settings.ZOOM_MATCH_TOLERANCE
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A stroke tool's menu (spec 10 §3), in three cards. The stroke: a preview, its colour and WIDTH.
 * How it answers the hand: PRESSURE / SENSITIVITY, the tool's signature control (MULTIPLIER for
 * calligraphy, THINNING / SMOOTHING / TOP SPEED for the speed pen, TIP WIDTH PERCENTAGE for the
 * taper pen) and STABILIZATION. How it sits on the page: OPACITY (hidden under neon), NEON, SCALE,
 * and SNAP TO SHAPES. With [onPick], a pen also gets a row of every pen kind under its title, which
 * re-arms the toolbar pen as that kind.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolConfigPopup(editor: ToolPopupHost, tool: Tool, onPick: ((Tool) -> Unit)? = null, onDismiss: () -> Unit) {
    val base = remember(tool) { editor.toolConfig(tool) }
    var pressure by remember(tool) { mutableStateOf(base.pressureEnabled) }
    var sensitivity by remember(tool) { mutableStateOf(ToolConversions.minFactorToSensitivity(base.pressureMinFactor).toFloat()) }
    var multiplier by remember(tool) { mutableStateOf(ToolConversions.directionStrengthToMultiplier(base.directionStrength).toFloat()) }
    var speed by remember(tool) { mutableStateOf(ToolConversions.strengthToSpeed(base.speedStrength).toFloat()) }
    var smoothing by remember(tool) { mutableStateOf(ToolConversions.blurToSmoothing(base.speedSmoothing).toFloat()) }
    var topSpeed by remember(tool) { mutableStateOf(ToolConversions.dpPerMsToTopSpeed(base.speedTop).toFloat()) }
    var taperTip by remember(tool) { mutableStateOf((base.taperMinFactor * 100).toFloat()) }
    var width by remember(tool) { mutableStateOf(ToolConversions.snapWidth(tool, base.baseWidth).toFloat()) }
    var glow by remember(tool) { mutableStateOf(base.neon) }
    var glowIntensity by remember(tool) { mutableStateOf(ToolConversions.neonStrengthToIntensity(base.neonStrength).toFloat()) }
    var dashLen by remember(tool) { mutableStateOf(base.dashLength.toFloat()) }
    var gapLen by remember(tool) { mutableStateOf(base.dashGap.toFloat()) }
    var straight by remember(tool) { mutableStateOf(base.straightLine) }
    var scale by remember(tool) { mutableStateOf(base.scale) }
    var intensity by remember(tool) { mutableStateOf(ToolConversions.highlighterAlphaToIntensity(base.highlighterAlpha).toFloat()) }
    var inverse by remember(tool) { mutableStateOf(base.highlighterInverse) }
    var faded by remember(tool) { mutableStateOf(base.opacityEnabled) }
    var opacity by remember(tool) { mutableStateOf(ToolConversions.opacityToPercent(base.opacity).toFloat()) }
    var colorOverride by remember(tool) { mutableStateOf(base.colorOverride) }
    var steady by remember(tool) { mutableStateOf(base.stabilize) }
    var steadyStrength by remember(tool) { mutableStateOf(ToolConversions.trailToStrength(base.stabilizeTrail).toFloat()) }
    var crossover by remember(tool) { mutableStateOf(ToolConversions.dpPerSToCrossover(base.stabilizeCrossover).toFloat()) }
    var snapShapes by remember(tool) { mutableStateOf(editor.snapHeldToShapes) }

    fun current(): ToolConfig {
        val m = ToolConversions.sensitivityToMinFactor(sensitivity.toDouble())
        val ds = if (tool == Tool.CALLIGRAPHY) ToolConversions.multiplierToDirectionStrength(multiplier.toDouble()) else 0.0
        val sp = if (tool == Tool.SPEED) ToolConversions.speedToStrength(speed.toDouble()) else 0.0
        val blur = if (tool == Tool.SPEED) ToolConversions.smoothingToBlur(smoothing.toDouble()) else base.speedSmoothing
        val top = if (tool == Tool.SPEED) ToolConversions.topSpeedToDpPerMs(topSpeed.toDouble()) else base.speedTop
        val tmf = if (tool == Tool.TAPER) taperTip.toDouble() / 100.0 else base.taperMinFactor
        val ha = if (tool == Tool.HIGHLIGHTER) ToolConversions.intensityToHighlighterAlpha(intensity.toDouble()) else base.highlighterAlpha
        val op = if (tool != Tool.HIGHLIGHTER) ToolConversions.percentToOpacity(opacity.toDouble()) else base.opacity
        return base.copy(
            baseWidth = ToolConversions.snapWidth(tool, width.toDouble()),
            pressureEnabled = pressure,
            pressureMinFactor = m,
            directionStrength = ds,
            speedStrength = sp,
            speedSmoothing = blur,
            speedTop = top,
            taperMinFactor = tmf,
            neon = glow,
            neonStrength = ToolConversions.intensityToNeonStrength(glowIntensity.toDouble()),
            dashLength = dashLen.toDouble(),
            dashGap = gapLen.toDouble(),
            straightLine = straight,
            scale = scale,
            highlighterAlpha = ha,
            highlighterInverse = inverse,
            colorOverride = colorOverride,
            stabilize = steady,
            stabilizeTrail = ToolConversions.strengthToTrail(steadyStrength.toDouble()),
            stabilizeCrossover = ToolConversions.crossoverToDpPerS(crossover.toDouble()),
            opacityEnabled = faded,
            opacity = op,
        )
    }

    fun emit() = editor.updateToolConfig(tool, current())

    val active = editor.hostToolbarColors.getOrNull(editor.hostActiveColorIndex)
    val ink = colorOverride ?: active ?: LocalPalette.current.text
    val hasPressure = tool == Tool.PEN || tool == Tool.CALLIGRAPHY || tool == Tool.SPEED || tool == Tool.TAPER
    // Glow is offered on every stroke tool except the highlighter (translucent) and the
    // dashed pen (it draws a line, not a fillable ribbon, so a halo has nothing to hug).
    val hasGlow = tool.isStroke && tool != Tool.HIGHLIGHTER && tool != Tool.DASHED
    val range = ToolConversions.widthRange(tool)

    ToolMenu(stringResource(tool.labelRes), onDismiss) {
        // The stroke itself: how it comes out, its colour and its width.
        if (onPick != null && tool in Tool.PENS) {
            PanelCard(padding = PaddingValues(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (pen in Tool.PENS) MenuTile(penIcon(pen), stringResource(pen.labelRes), pen == tool, 44.dp) { if (pen != tool) onPick(pen) }
                }
            }
        }
        MenuCard(buildList<MenuGroup> {
            add {
                StrokePreview(tool, current().copy(rgba = ink), editor, Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp))
                // "Default" follows the toolbar's active ink colour; a colour picked here pins the tool to it.
                MenuRow(stringResource(R.string.caption_colour)) {
                    ColorOrNone(
                        stringResource(R.string.default_choice),
                        colorOverride,
                        suggested = active ?: LocalPalette.current.text,
                        onChange = { colorOverride = it; emit() },
                    ) { d, p ->
                        ColorPickerPopup(initial = colorOverride ?: active, recents = editor.hostRecentColors, onDismiss = d, onPick = p)
                    }
                }
            }
            add {
                MenuSlider(
                    stringResource(R.string.caption_thickness), width,
                    range.start.toFloat()..range.endInclusive.toFloat(), step = ToolConversions.WIDTH_STEP.toFloat(),
                ) { width = it; emit() }
            }
            if (tool == Tool.HIGHLIGHTER) add { MenuSlider(stringResource(R.string.caption_intensity), intensity, 10f..100f) { intensity = it; emit() } }
            if (tool == Tool.DASHED) {
                add { MenuSlider(stringResource(R.string.caption_dash), dashLen, 2f..40f) { dashLen = it; emit() } }
                add { MenuSlider(stringResource(R.string.caption_gap), gapLen, 2f..40f) { gapLen = it; emit() } }
            }
        })
        // How it answers the hand.
        MenuCard(buildList<MenuGroup> {
            if (hasPressure) add {
                MenuSwitch(stringResource(R.string.caption_pressure), pressure) { pressure = it; emit() }
                if (pressure) MenuSlider(stringResource(R.string.caption_sensitivity), sensitivity, 0f..100f, child = true) { sensitivity = it; emit() }
            }
            if (tool == Tool.CALLIGRAPHY) add { MenuSlider(stringResource(R.string.caption_multiplier), multiplier, 1f..5f) { multiplier = it; emit() } }
            if (tool == Tool.SPEED) {
                add { MenuSlider(stringResource(R.string.caption_thinning), speed, 0f..100f) { speed = it; emit() } }
                add { MenuSlider(stringResource(R.string.caption_smoothing), smoothing, 0f..100f) { smoothing = it; emit() } }
                add { MenuSlider(stringResource(R.string.caption_top_speed), topSpeed, 0f..100f) { topSpeed = it; emit() } }
            }
            if (tool == Tool.TAPER) add { MenuSlider(stringResource(R.string.caption_tip_width), taperTip, 0f..100f) { taperTip = it; emit() } }
            // A straight-segment lock, for ruling and underlining.
            if (tool == Tool.HIGHLIGHTER) add { MenuSwitch(stringResource(R.string.caption_straight_line), straight) { straight = it; emit() } }
            // STABILIZATION steadies the path while drawing; a straight line has nothing to steady.
            if (tool.isStroke && !(tool == Tool.HIGHLIGHTER && straight)) add {
                MenuSwitch(stringResource(R.string.caption_stabilization), steady) { steady = it; emit() }
                if (steady) {
                    MenuSlider(stringResource(R.string.caption_strength), steadyStrength, 0f..100f, child = true) { steadyStrength = it; emit() }
                    MenuSlider(stringResource(R.string.caption_crossover), crossover, 0f..100f, child = true) { crossover = it; emit() }
                }
            }
        })
        // How it sits on the page.
        MenuCard(buildList<MenuGroup> {
            // Neon paints at full strength whatever the opacity, so opacity hides under it.
            if (tool.isStroke && tool != Tool.HIGHLIGHTER && !(hasGlow && glow)) add {
                MenuSwitch(stringResource(R.string.caption_opacity), faded) { faded = it; emit() }
                if (faded) MenuSlider(stringResource(R.string.caption_intensity), opacity, 10f..100f, step = 1f, child = true) { opacity = it; emit() }
            }
            if (hasGlow) add {
                MenuSwitch(stringResource(R.string.caption_neon), glow) { glow = it; emit() }
                if (glow) MenuSlider(stringResource(R.string.caption_glow_intensity), glowIntensity, 0f..100f, child = true) { glowIntensity = it; emit() }
            }
            // INVERSE swaps the multiply blend for a screen one, so the marker lightens the
            // page instead of darkening it. A multiply has nothing to darken on a dark page.
            if (tool == Tool.HIGHLIGHTER) add { MenuSwitch(stringResource(R.string.caption_inverse), inverse) { inverse = it; emit() } }
            // SCALE off: ink keeps a constant on-screen thickness whatever zoom you draw at.
            add { MenuSwitch(stringResource(R.string.caption_scale), scale) { scale = it; emit() } }
            // Global dwell shape detection (holding the pen still). Off for the highlighter, which never dwells.
            if (tool.isStroke && tool != Tool.HIGHLIGHTER) add {
                MenuSwitch(stringResource(R.string.caption_snap_to_shapes), snapShapes) { snapShapes = it; editor.snapHeldToShapes = it }
            }
        })
    }
}

/** One side of a custom page in millimetres; only a value inside the settable range reaches [onChange]. */
@Composable
internal fun SideField(label: String, mm: Double, modifier: Modifier, onChange: (Double) -> Unit) {
    Column(modifier) {
        StyleCaption(label)
        Spacer(Modifier.size(4.dp))
        FieldFrame(Modifier.fillMaxWidth()) {
            NativeField(
                value = formatMm(mm),
                onText = { raw -> parseMm(raw)?.let(onChange) },
                modifier = Modifier.weight(1f),
                decimal = true,
                maxLen = 7,
                endAlign = true,
            )
        }
    }
}

/**
 * The cards that set [t] on a page-style level: its colours (the paper when [withPaper], the
 * template's ink and accent when [withInk], then its colour parameters), then its lengths and
 * numbers. Each falls back to [lower] (the level below, when its values apply) and then the template.
 */
@Composable
internal fun TemplateCards(
    t: com.xnotes.core.template.Template?,
    style: PageStyle,
    lower: PageStyle?,
    look: TemplateLook,
    dpi: Int,
    store: SwatchStore,
    withPaper: Boolean,
    withInk: Boolean,
    apply: (PageStyle) -> Unit,
) {
    MenuCard(buildList<MenuGroup> {
        if (withPaper) add {
            ColourRow(stringResource(R.string.caption_paper), style.pageColor, look.paper, store, SwatchRole.PAPER) { apply(style.copy(pageColor = it)) }
        }
        if (t != null && withInk && t.usesInk) add {
            // The colour holds the hue at full strength; Opacity sets the alpha, so a pick keeps it.
            ColourRow(stringResource(R.string.caption_pattern_colour), style.patternColor?.copy(a = 255), look.ink.copy(a = 255), store, SwatchRole.LINES) {
                apply(style.copy(patternColor = it?.copy(a = look.ink.a)))
            }
            val pct = look.ink.a * 100f / 255f
            ParamSlider(stringResource(R.string.caption_opacity), "${pct.roundToInt()}%", pct, 0f..100f, own = style.patternColor != null, child = true) { v ->
                apply(style.copy(patternColor = look.ink.copy(a = (v / 100f * 255f).roundToInt().coerceIn(0, 255))))
            }
        }
        if (t != null && withInk && t.usesAccent) add {
            ColourRow(stringResource(R.string.caption_accent_colour), style.accentColor?.copy(a = 255), look.accent.copy(a = 255), store, SwatchRole.ACCENT) {
                apply(style.copy(accentColor = it?.copy(a = look.accent.a)))
            }
        }
        for (p in t?.params.orEmpty()) {
            if (p.type != com.xnotes.core.template.ParamType.COLOR) continue
            add {
                val suggested = lower?.colors?.get(p.name) ?: p.defaultColor ?: look.ink.copy(a = 255)
                ColourRow(p.label ?: p.name.replaceFirstChar { it.uppercase() }, style.colors?.get(p.name), suggested, store, SwatchRole.TEMPLATE) { c ->
                    val rest = style.colors?.minus(p.name)
                    apply(style.copy(colors = if (c == null) rest?.takeIf { it.isNotEmpty() } else (rest ?: emptyMap()) + (p.name to c)))
                }
            }
        }
    })
    if (t != null) MenuCard(templateValueGroups(t, style, lower, dpi, apply))
}

/** [t]'s lengths and numbers as sliders, the spacing one first and in mm like every other length. */
private fun templateValueGroups(
    t: com.xnotes.core.template.Template,
    style: PageStyle,
    lower: PageStyle?,
    dpi: Int,
    apply: (PageStyle) -> Unit,
): List<MenuGroup> = buildList<MenuGroup> {
    val k = dpi / 25.4
    t.spacingParam?.let { sp ->
        add {
            val lo = ((sp.min ?: (sp.default / 4)) * k).toFloat()
            val hi = ((sp.max ?: (sp.default * 4)) * k).toFloat().coerceAtLeast(lo + 1f)
            val spacing = style.spacing ?: lower?.spacing ?: (sp.default * k)
            ParamSlider(
                sp.label ?: stringResource(R.string.caption_spacing), "%.1f mm".format(spacing / k), spacing.toFloat(), lo..hi,
                own = style.spacing != null, onReset = { apply(style.copy(spacing = null)) },
            ) { apply(style.copy(spacing = it.toDouble())) }
        }
    }
    for (p in t.params) {
        if (p === t.spacingParam || p.type == com.xnotes.core.template.ParamType.COLOR) continue
        add {
            val own = style.params?.get(p.name)
            val value = p.clamp(own ?: lower?.params?.get(p.name) ?: p.default)
            val lo = p.min ?: if (p.default > 0) p.default / 4 else p.default - 10
            val hi = (p.max ?: if (p.default > 0) p.default * 4 else p.default + 10).coerceAtLeast(lo + 1e-6)
            val shown = when (p.type) {
                com.xnotes.core.template.ParamType.LENGTH -> "%.1f mm".format(value)
                com.xnotes.core.template.ParamType.INTEGER -> value.roundToInt().toString()
                else -> "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
            }
            val integer = p.type == com.xnotes.core.template.ParamType.INTEGER
            ParamSlider(
                p.label ?: p.name.replaceFirstChar { it.uppercase() }, shown, value.toFloat(), lo.toFloat()..hi.toFloat(),
                own = own != null,
                steps = if (integer) ((hi - lo).roundToInt() - 1).coerceIn(0, 200) else 0,
                onReset = { apply(style.copy(params = style.params?.minus(p.name)?.takeIf { it.isNotEmpty() })) },
            ) { v -> apply(style.copy(params = (style.params ?: emptyMap()) + (p.name to p.clamp(v.toDouble())))) }
        }
    }
}

@Composable
internal fun StyleCaption(text: String) {
    Text(
        text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontSize = 12.sp,
    )
}

/** Eraser configuration popup: a STROKE/AREA mode picker and a SIZE slider (the eraser radius). */

/** Lock and save chips for the current zoom, the zoom bar switch, then the saved zooms. */
/**
 * The zooms starred in [owner]'s settings: a star saves the zoom in view (or drops it), a chip
 * jumps back to one with [onPick], and the optional zoom bar shows them on the page.
 */
@Composable
internal fun SavedZooms(owner: Editor, zoomPercent: Int, locked: Boolean, onPick: (Float) -> Unit) {
    val current = zoomPercent / 100f
    val favorites = owner.favoriteZooms
    val starred = favorites.any { isSameZoom(it, current) }
    Spacer(Modifier.height(8.dp))
    PanelCaption(stringResource(R.string.caption_saved_zooms)) {
        PanelChip(stringResource(R.string.zoom_save, zoomPercent), starred, XnotesIcons.bookmark) { owner.toggleFavoriteZoom(current) }
    }
    if (favorites.isNotEmpty()) SavedZoomChips(favorites, current, locked, onPick, owner::toggleFavoriteZoom)
    PanelToggle(stringResource(R.string.zoom_show_bar), owner.showZoomBar, owner::setShowZoomBar)
}

internal fun isSameZoom(a: Float, b: Float) = abs(a - b) <= ZOOM_MATCH_TOLERANCE

/** The saved zooms as chips: tap one to jump to it, its x to remove it. */
@Composable
internal fun SavedZoomChips(favorites: List<Float>, current: Float, locked: Boolean, onPick: (Float) -> Unit, onRemove: (Float) -> Unit) {
    val palette = LocalPalette.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        favorites.forEach { zoom ->
            val pct = (zoom * 100).roundToInt()
            val selected = isSameZoom(zoom, current)
            val fg = (if (selected) palette.selectionForeground else palette.text).toComposeColor()
            Row(
                Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background((if (selected) palette.selectionBackground else palette.surface).toComposeColor())
                    .border(1.dp, (if (selected) palette.accent else palette.border).toComposeColor(), MaterialTheme.shapes.extraSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$pct%",
                    color = fg,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(enabled = !locked) { onPick(zoom) }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                )
                Icon(
                    XnotesIcons.close,
                    contentDescription = stringResource(R.string.zoom_remove_saved, pct),
                    tint = fg,
                    modifier = Modifier.clickable { onRemove(zoom) }.padding(6.dp).size(14.dp),
                )
            }
        }
    }
}


/** Pan configuration popup: which axes a finger pan moves along, how fast, and whether a flick glides on. */
@Composable
fun PanConfigPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    var cfg by remember { mutableStateOf(editor.toolConfig(Tool.PAN)) }
    fun emit(next: ToolConfig) { cfg = next; editor.updateToolConfig(Tool.PAN, next) }

    fun reset() {
        val d = ToolDefaults.configFor(Tool.PAN)
        emit(cfg.copy(lockPanX = d.lockPanX, lockPanY = d.lockPanY, panSensitivity = d.panSensitivity, panGlide = d.panGlide))
    }

    ToolMenu(stringResource(R.string.tool_pan), onDismiss, action = { PanelChip(stringResource(R.string.reset), onClick = ::reset) }) {
        PanelCard(padding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp)) {
            PanelCaption(stringResource(R.string.caption_pan_axes))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.pan_axis_x), selected = !cfg.lockPanX) { emit(cfg.copy(lockPanX = !cfg.lockPanX)) }
                ModeChip(stringResource(R.string.pan_axis_y), selected = !cfg.lockPanY) { emit(cfg.copy(lockPanY = !cfg.lockPanY)) }
                ModeChip(stringResource(R.string.pan_axis_zoom), selected = !editor.hostZoomLocked) { editor.toggleHostZoomLock() }
            }
        }
        MenuCard(buildList<MenuGroup> {
            add { MenuSlider(stringResource(R.string.caption_pan_sensitivity), cfg.panSensitivity.toFloat(), 1f..10f, step = 1f) { emit(cfg.copy(panSensitivity = it.roundToInt())) } }
            add { MenuSwitch(stringResource(R.string.caption_pan_glide), cfg.panGlide) { emit(cfg.copy(panGlide = it)) } }
        })
    }
}
@Composable
fun EraserConfigPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.ERASER) }
    var mode by remember { mutableStateOf(base.eraseMode) }
    var size by remember { mutableStateOf(base.baseWidth.toFloat()) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterErase) }
    var scale by remember { mutableStateOf(base.scale) }
    var markups by remember { mutableStateOf(base.eraseMarkups) }
    var scribble by remember { mutableStateOf(base.scribbleErase) }
    var kinds by remember { mutableStateOf(base.erasableKinds) }

    fun emit() = editor.updateToolConfig(
        Tool.ERASER,
        base.copy(
            baseWidth = size.toDouble(),
            eraseMode = mode,
            switchBackAfterErase = switchBack,
            scale = scale,
            eraseMarkups = markups,
            scribbleErase = scribble,
            erasableKinds = kinds,
        ),
    )

    ToolMenu(stringResource(R.string.title_eraser), onDismiss) {
        MenuCard(buildList<MenuGroup> {
            add {
                val modes = listOf(EraseMode.STROKE, EraseMode.AREA, EraseMode.LASSO)
                MenuSegmented(
                    listOf(stringResource(R.string.eraser_stroke), stringResource(R.string.eraser_area), stringResource(R.string.tool_lasso)),
                    modes.indexOf(mode).coerceAtLeast(0),
                ) { mode = modes[it]; emit() }
                val r = ToolConversions.widthRange(Tool.ERASER)
                MenuSlider(stringResource(R.string.caption_size), size, r.start.toFloat()..r.endInclusive.toFloat()) { size = it; emit() }
            }
        })
        PanelCard(padding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp)) {
            PanelCaption(stringResource(R.string.caption_erasable_kinds))
            KindChips(
                kinds,
                extra = if (editor.hostHasPdf) {
                    {
                        KindChip(XnotesIcons.markup, stringResource(R.string.caption_erase_markups), markups) {
                            markups = !markups; emit()
                        }
                    }
                } else null,
            ) { kinds = it; emit() }
        }
        MenuCard(buildList<MenuGroup> {
            // SCALE off: the eraser holds a constant on-screen size whatever zoom you are at.
            add { MenuSwitch(stringResource(R.string.caption_scale), scale) { scale = it; emit() } }
            // Re-arm the previous pen/highlighter once an erase lifts, so a quick fix doesn't strand
            // you in the eraser.
            add { MenuSwitch(stringResource(R.string.caption_switch_back), switchBack) { switchBack = it; emit() } }
            // A pen scribbled over ink erases it without picking up the eraser.
            add { MenuSwitch(stringResource(R.string.caption_scribble_erase), scribble) { scribble = it; emit() } }
        })
    }
}

@Composable
internal fun penIcon(pen: Tool): ImageVector = ImageVector.vectorResource(
    when (pen) {
        Tool.DASHED -> R.drawable.ic_stroke_dashed
        Tool.CALLIGRAPHY -> R.drawable.ic_stroke_calligraphy
        Tool.SPEED -> R.drawable.ic_stroke_speed
        Tool.TAPER -> R.drawable.ic_stroke_taper
        else -> R.drawable.ic_stroke_regular
    },
)

/** One chip per item kind; the last chip that is on stays on, since a filter of nothing looks broken.
 *  [extra] appends a trailing chip of the host's own (the eraser's PDF-text-markup toggle). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(kinds: Set<SelectKind>, modifier: Modifier = Modifier, extra: (@Composable () -> Unit)? = null, onChange: (Set<SelectKind>) -> Unit) {
    val context = LocalContext.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in SelectKind.entries) {
            val on = k in kinds
            KindChip(selectKindIcon(k), stringResource(k.labelRes), on) {
                if (on && kinds.size == 1) Toast.makeText(context, R.string.toast_filter_needs_one, Toast.LENGTH_SHORT).show()
                else onChange(if (on) kinds - k else kinds + k)
            }
        }
        extra?.invoke()
    }
}

@Composable
private fun selectKindIcon(k: SelectKind): ImageVector = when (k) {
    SelectKind.INK -> ImageVector.vectorResource(R.drawable.ic_stroke_regular)
    SelectKind.HIGHLIGHTER -> ImageVector.vectorResource(R.drawable.ic_stroke_highlighter)
    SelectKind.SHAPES -> XnotesIcons.shape
    SelectKind.TEXT -> XnotesIcons.textBox
    SelectKind.IMAGES -> XnotesIcons.image
    SelectKind.TABLES -> XnotesIcons.table
    SelectKind.TAPE -> XnotesIcons.tape
    SelectKind.AUDIO -> XnotesIcons.mic
}

/**
 * Select/lasso configuration popup: rectangle or lasso ([onPick] re-arms the toolbar select as the
 * other), which kinds of item a selection picks up (shared by both), whether it needs items fully
 * inside it, plus the select tool's SWITCH BACK.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectConfigPopup(editor: ToolPopupHost, tool: Tool = Tool.SELECT, onPick: (Tool) -> Unit = {}, onDismiss: () -> Unit) {
    val base = remember(tool) { editor.toolConfig(tool) }
    var switchBack by remember(tool) { mutableStateOf(base.switchBackAfterSelect) }
    var whole by remember(tool) { mutableStateOf(base.selectWholeItems) }
    var snap by remember(tool) { mutableStateOf(base.snapToMargins) }
    var kinds by remember { mutableStateOf(editor.toolConfig(Tool.SELECT).selectKinds) }

    fun emit() {
        editor.updateToolConfig(tool, base.copy(switchBackAfterSelect = switchBack, selectWholeItems = whole, snapToMargins = snap))
        editor.updateToolConfig(Tool.SELECT, editor.toolConfig(Tool.SELECT).copy(selectKinds = kinds))
    }

    ToolMenu(stringResource(if (tool == Tool.LASSO) R.string.tool_lasso else R.string.title_select), onDismiss) {
        PanelCard(padding = PaddingValues(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuTile(XnotesIcons.select, stringResource(R.string.shape_rectangle), tool == Tool.SELECT, 44.dp) { if (tool != Tool.SELECT) onPick(Tool.SELECT) }
                MenuTile(XnotesIcons.lasso, stringResource(R.string.tool_lasso), tool == Tool.LASSO, 44.dp) { if (tool != Tool.LASSO) onPick(Tool.LASSO) }
            }
        }
        PanelCard(padding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp)) {
            PanelCaption(stringResource(R.string.caption_select_kinds))
            KindChips(kinds) { kinds = it; emit() }
        }
        MenuCard(buildList<MenuGroup> {
            add { MenuSwitch(stringResource(R.string.caption_select_whole_items), whole) { whole = it; emit() } }
            // Re-arm the previous pen/highlighter once a selection action (move, resize, delete,
            // cut, copy, duplicate) finishes, so a quick edit doesn't strand you in select.
            if (tool == Tool.SELECT) {
                add { MenuSwitch(stringResource(R.string.caption_switch_back), switchBack) { switchBack = it; emit() } }
                if (editor.hostIsPaged) add { MenuSwitch(stringResource(R.string.caption_snap_to_margins), snap) { snap = it; emit() } }
            }
        })
    }
}

/**
 * Table-tool configuration popup: everything the next table starts with. The table itself is drawn
 * on the page by dragging, like the shape tool; an existing one is restyled through Change style.
 */
@Composable
fun TableConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    var cols by remember { mutableStateOf(editor.tableToolCols) }
    var rows by remember { mutableStateOf(editor.tableToolRows) }
    var look by remember { mutableStateOf(editor.tableToolLook) }
    var color by remember { mutableStateOf(editor.tableToolColor) }
    val active = editor.hostToolbarColors.getOrNull(editor.hostActiveColorIndex) ?: LocalPalette.current.text
    val colour: MenuGroup = {
        // "Default" follows the toolbar's ink colour; a colour picked here pins the tool to it.
        MenuRow(stringResource(R.string.caption_colour)) {
            ColorOrNone(stringResource(R.string.default_choice), color, suggested = active, onChange = { color = it; editor.tableToolColor = it }) { d, p ->
                ColorPickerPopup(initial = color ?: active, recents = editor.hostRecentColors, onDismiss = d, onPick = p)
            }
        }
    }
    val counts = listOf(
        tableCount(R.string.caption_columns, cols) { cols = it; editor.tableToolCols = it },
        tableCount(R.string.caption_rows, rows) { rows = it; editor.tableToolRows = it },
    )
    ToolMenu(stringResource(R.string.title_table), onDismiss) {
        look.groups(color ?: active, editor.hostRecentColors, colour, counts) { next, _ ->
            look = next
            editor.tableToolLook = next
        }.forEach { MenuCard(it) }
    }
}

/**
 * Tape popup: what a drag lays (a straight strip, a rectangle or a freehand band), its colour and
 * pattern, the width of a strip or band, and Reveal all / Cover all for every tape in the note or
 * canvas. Like the pens, tape follows the toolbar's ink colour unless a colour is picked here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TapeConfigPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.TAPE) }
    var mode by remember { mutableStateOf(base.tapeMode) }
    var pattern by remember { mutableStateOf(base.tapePattern) }
    var width by remember { mutableStateOf(ToolConversions.snapWidth(Tool.TAPE, base.baseWidth).toFloat()) }
    var scale by remember { mutableStateOf(base.scale) }
    var colorOverride by remember { mutableStateOf(base.colorOverride) }

    fun emit() = editor.updateToolConfig(
        Tool.TAPE,
        base.copy(
            tapeMode = mode, tapePattern = pattern, baseWidth = ToolConversions.snapWidth(Tool.TAPE, width.toDouble()),
            scale = scale, colorOverride = colorOverride,
        ),
    )

    ToolMenu(stringResource(R.string.tool_tape), onDismiss) {
        MenuCard(buildList<MenuGroup> {
            add {
                val active = editor.hostToolbarColors.getOrNull(editor.hostActiveColorIndex)
                val preview = base.copy(
                    tapeMode = mode, tapePattern = pattern, baseWidth = ToolConversions.snapWidth(Tool.TAPE, width.toDouble()),
                    scale = scale, colorOverride = colorOverride, rgba = active ?: LocalPalette.current.text,
                )
                StrokePreview(Tool.TAPE, preview, editor, Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp))
                val modes = TapeMode.entries
                MenuSegmented(modes.map { stringResource(it.labelRes) }, modes.indexOf(mode)) { mode = modes[it]; emit() }
                MenuRow(stringResource(R.string.caption_colour)) {
                    ColorOrNone(
                        stringResource(R.string.default_choice),
                        colorOverride,
                        suggested = active ?: LocalPalette.current.text,
                        onChange = { colorOverride = it; emit() },
                    ) { d, p ->
                        ColorPickerPopup(initial = colorOverride ?: active, recents = editor.hostRecentColors, onDismiss = d, onPick = p)
                    }
                }
            }
            add {
                Column(Modifier.padding(10.dp)) {
                    PanelCaption(stringResource(R.string.caption_pattern))
                    val patterns = TapePattern.entries
                    PanelSegmented(patterns.map { stringResource(it.labelRes) }, patterns.indexOf(pattern), { pattern = patterns[it]; emit() })
                }
            }
            // A rectangle is as tall as it is dragged, so only a strip and a band take a width.
            if (mode != TapeMode.RECTANGLE) {
                add {
                    val range = ToolConversions.widthRange(Tool.TAPE)
                    MenuSlider(
                        stringResource(R.string.caption_thickness), width,
                        range.start.toFloat()..range.endInclusive.toFloat(), step = ToolConversions.WIDTH_STEP.toFloat(),
                    ) { width = it; emit() }
                }
                add { MenuSwitch(stringResource(R.string.caption_scale), scale) { scale = it; emit() } }
            }
        })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuAction(stringResource(R.string.tape_reveal_all), XnotesIcons.eye, Modifier.weight(1f)) { editor.setAllTapePeeled(true) }
            MenuAction(stringResource(R.string.tape_cover_all), XnotesIcons.eyeOff, Modifier.weight(1f)) { editor.setAllTapePeeled(false) }
        }
    }
}

/** Insert space popup: the colour new room takes. Default leaves it the page's own colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsertSpaceConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.INSERT_SPACE) }
    var color by remember { mutableStateOf(base.colorOverride) }

    fun pick(next: Rgba?) {
        color = next
        editor.updateToolConfig(Tool.INSERT_SPACE, base.copy(colorOverride = next))
    }

    ToolMenu(stringResource(R.string.tool_insert_space), onDismiss) {
        PanelCard(padding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 14.dp)) {
            PanelCaption(stringResource(R.string.caption_colour))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                PanelChip(stringResource(R.string.default_choice), color == null) { pick(null) }
                SwatchCircles(editor, SwatchRole.PAPER, color) { pick(it) }
            }
        }
    }
}

/**
 * Text markup tool popup: what a drag over PDF text does (select it, or one of the four marks) and,
 * in Highlight mode, how deep it goes. A mark takes the toolbar's active ink colour.
 */
@Composable
fun MarkupToolPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.MARKUP) }
    var mode by remember { mutableStateOf(base.markupMode) }
    var intensity by remember { mutableStateOf((base.markupIntensity * 100).toFloat()) }

    fun emit() = editor.updateToolConfig(Tool.MARKUP, base.copy(markupMode = mode, markupIntensity = intensity / 100.0))

    ToolMenu(stringResource(R.string.tool_markup), onDismiss) {
        val modes = MarkupMode.entries
        val shaded = mode == MarkupMode.HIGHLIGHT
        MenuCard(buildList<MenuGroup> {
            add {
                Box(Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = if (shaded) 0.dp else 10.dp)) {
                    IconSegmented(modes.map { markupIcon(it) }, modes.map { stringResource(it.labelRes) }, modes.indexOf(mode), height = 38.dp) {
                        mode = modes[it]
                        emit()
                    }
                }
                if (shaded) {
                    val min = (ToolConfig.MARKUP_INTENSITY_MIN * 100).toFloat()
                    MenuSlider(stringResource(R.string.caption_intensity), intensity, min..100f) { intensity = it; emit() }
                }
            }
        })
    }
}

private fun markupIcon(mode: MarkupMode): ImageVector = when (mode) {
    MarkupMode.SELECT -> XnotesIcons.textSelect
    MarkupMode.HIGHLIGHT -> XnotesIcons.highlight
    MarkupMode.UNDERLINE -> XnotesIcons.underline
    MarkupMode.STRIKEOUT -> XnotesIcons.strikethrough
    MarkupMode.SQUIGGLY -> XnotesIcons.squiggly
}

/** Colour switcher (spec 10 §4): the toolbar swatch picker — opens the shared [ColorPickerPopup]
 *  and writes the chosen colour back to swatch [index]. Picks apply live; the final colour is
 *  remembered into recents when the popup closes. */
@Composable
fun ColorSwitcherPopup(host: ToolPopupHost, index: Int, onDismiss: () -> Unit) {
    ColorPickerPopup(
        initial = host.hostToolbarColors.getOrNull(index),
        recents = host.hostRecentColors,
        onDismiss = { host.rememberSwatchColor(index); onDismiss() },
        onPick = { host.setSwatchColor(index, it) },
    )
}

@Composable
internal fun PopupTitle(text: String) {
    Text(
        text,
        color = LocalPalette.current.accent.toComposeColor(),
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The checkbox's 48dp touch frame insets the drawn box; pull the row back to align it.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 220.dp).fillMaxWidth().offset(x = (-14).dp)) {
        val palette = LocalPalette.current
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label, color = (if (enabled) palette.text else palette.textDim).toComposeColor(), fontSize = 12.sp)
    }
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 0f,
    suffix: String = "",
    enabled: Boolean = true,
    onChangeFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    // A stepped row lands on its stops, so its label is the value given, not a rounding of it.
    val shown = if (step > 0f) snapTo(value, range, step) else value
    val number = if (step % 1f != 0f) "%.1f".format(shown).trimEnd('0').trimEnd('.', ',') else "%.0f".format(shown)
    Column {
        Text(
            "$label  $number$suffix",
            color = (if (enabled) LocalPalette.current.text else LocalPalette.current.textDim).toComposeColor(),
            fontSize = 12.sp,
        )
        Slider(
            value = shown,
            onValueChange = { if (step > 0f) onChange(snapTo(it, range, step)) else onChange(it) },
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            steps = if (step > 0f) {
                (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
            } else {
                0
            },
            enabled = enabled,
            // Stops this fine would draw as a speckled track; the label already names each one.
            colors = if (step > 0f) NoTicks else SliderDefaults.colors(),
        )
    }
}

internal val NoTicks
    @Composable get() = SliderDefaults.colors(
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledActiveTickColor = Color.Transparent,
        disabledInactiveTickColor = Color.Transparent,
    )

/** [value] moved to the nearest [step] from the start of [range], held at its ends. */
internal fun snapTo(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    return (range.start + ((value - range.start) / step).roundToInt() * step)
        .coerceIn(range.start, range.endInclusive)
}

/** A chip's LaTeX: what a function chip plots, null for every other chip. */
internal fun chipLatex(p: ShapePreset): String? =
    if (!p.key.startsWith(ShapePresets.FUNCTION_PREFIX)) null
    else when (val expr = p.key.removePrefix(ShapePresets.FUNCTION_PREFIX)) {
        "x^(1/2)" -> "\\sqrt{x}"
        "ln(x)", "sin(x)", "cos(x)", "tan(x)", "cot(x)", "arcsin(x)", "arccos(x)", "arctan(x)" -> "\\" + expr.removeSuffix("(x)") + " x"
        else -> expr
    }

/** How a function chip names what it plots. */
internal fun functionLabel(expr: String): String = when (expr) {
    "x^2" -> "x\u00B2"
    "x^3" -> "x\u00B3"
    "x^(1/2)" -> "\u221Ax"
    "ln(x)" -> "ln x"
    "e^x" -> "e\u02E3"
    "sin(x)" -> "sin x"
    "cos(x)" -> "cos x"
    "tan(x)" -> "tan x"
    "cot(x)" -> "cot x"
    "arcsin(x)" -> "arcsin x"
    "arccos(x)" -> "arccos x"
    "arctan(x)" -> "arctan x"
    else -> expr
}


@Composable
internal fun KindChip(icon: ImageVector, label: String, selected: Boolean, latex: String? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val tint = if (selected) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor()
    Tooltip(label) {
        Box(
            Modifier
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
                .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                // A LaTeX chip trades side padding for width so it stays as wide as an icon chip.
                .padding(horizontal = if (latex == null) 8.dp else 1.dp, vertical = 8.dp),
        ) {
            if (latex == null) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            } else {
                Box(Modifier.size(34.dp, 20.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                    Latex(latex, Modifier.scaleDownToFit(), LatexConfig(fontSize = 13.sp, theme = LatexTheme.light(tint, Color.Transparent)))
                }
            }
        }
    }
}

/** Measures the content at its natural size and shrinks it, centred, until it fits the space given. */
internal fun Modifier.scaleDownToFit() = layout { measurable, constraints ->
    val p = measurable.measure(Constraints())
    val s = minOf(1f, constraints.maxWidth.toFloat() / p.width.coerceAtLeast(1), constraints.maxHeight.toFloat() / p.height.coerceAtLeast(1))
    layout(constraints.maxWidth, constraints.maxHeight) {
        p.placeWithLayer((constraints.maxWidth - p.width) / 2, (constraints.maxHeight - p.height) / 2) { scaleX = s; scaleY = s }
    }
}

/**
 * The app's dropdown menu: material's, pinned to the palette menu surface and given a
 * hairline border so it reads against same-tone surfaces (the backstage, OLED black)
 * where a shadow alone vanishes. Shadows material3's composable for every same-package
 * caller that doesn't import material's directly.
 */
@Composable
internal fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = LocalMenuOffset.current,
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = LocalPalette.current.menuBg.toComposeColor(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val opened = remember(expanded) { Eyedropper.ended }
    val ended = expanded && Eyedropper.ended != opened
    LaunchedEffect(ended) { if (ended) onDismissRequest() }
    androidx.compose.material3.DropdownMenu(
        expanded = expanded && Eyedropper.target == null && !ended,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        properties = properties,
        shape = shape,
        containerColor = containerColor,
        border = BorderStroke(1.dp, palette.border.toComposeColor()),
    ) {
        // A menu or tip opened from inside this one hangs off its own row, not beside the rail.
        CompositionLocalProvider(LocalMenuOffset provides DpOffset.Zero, LocalTipSide provides TipSide.BELOW) { content() }
    }
}

/** A bar's icons sit this far in from its rounded ends. */
internal val BAR_END = 4.dp

/** The frame of a bar or card floating by what it acts on: the menus' surface and corners, lifted off the page. */
@Composable
internal fun Modifier.floatingBar(shape: Shape = MaterialTheme.shapes.large): Modifier {
    val palette = LocalPalette.current
    return shadow(6.dp, shape)
        .background(palette.menuBg.toComposeColor(), shape)
        .border(1.dp, palette.border.toComposeColor(), shape)
        .clip(shape)
}

/** A menu's rows sit this far in from its edges, so a press or the current choice is a pill. */
private val MENU_ROW_INSET = 8.dp

/**
 * The app's menu row: rounded and inset from the menu's edges, its icons dim beside its label.
 * [selected] fills it with the selection container and [danger] colours a destructive action.
 * Shadows material3's composable like [DropdownMenu] does.
 */
@Composable
internal fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
) {
    val palette = LocalPalette.current
    val fg = when {
        !enabled -> palette.disabled
        selected -> palette.selectionForeground
        danger -> palette.danger
        else -> palette.text
    }.toComposeColor()
    val iconTint = if (enabled && !selected && !danger) palette.textDim.toComposeColor() else fg
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = MENU_ROW_INSET, vertical = 1.dp)
            .sizeIn(minWidth = 112.dp, maxWidth = 280.dp, minHeight = 42.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            CompositionLocalProvider(LocalContentColor provides iconTint) {
                Box(Modifier.padding(end = 12.dp).widthIn(min = 20.dp), contentAlignment = Alignment.Center) { leadingIcon() }
            }
        }
        CompositionLocalProvider(LocalContentColor provides fg) {
            ProvideTextStyle(TextStyle(fontSize = 14.sp, lineHeight = 20.sp)) {
                Box(Modifier.weight(1f).padding(vertical = 6.dp)) { text() }
            }
        }
        if (trailingIcon != null) {
            CompositionLocalProvider(LocalContentColor provides iconTint) {
                Box(Modifier.padding(start = 12.dp)) { trailingIcon() }
            }
        }
    }
}

/** A menu action: its [icon] and [label]; [more] marks one that opens further rows. */
@Composable
internal fun MenuItem(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    more: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(18.dp)) } },
        trailingIcon = if (more) ({ Icon(XnotesIcons.next, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
        enabled = enabled,
        danger = danger,
    )
}

/** The first row of a menu's sub-page: its [label], and the way back to the rows it came from. */
@Composable
internal fun MenuBack(label: String, onBack: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, fontWeight = FontWeight.Medium) },
        onClick = onBack,
        leadingIcon = { Icon(XnotesIcons.prev, contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp)) },
    )
}

/** The hairline between a menu's groups. */
@Composable
internal fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(1.dp).background(LocalPalette.current.border.toComposeColor()))
}

/** A small heading over a group of a menu's rows. */
@Composable
internal fun MenuCaption(text: String) {
    Text(
        text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** A menu row for one of several choices; the current one wears the selection container. */
@Composable
internal fun ChoiceMenuItem(
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: (@Composable (Color) -> Unit)? = null,
    text: @Composable (Color) -> Unit,
) {
    val palette = LocalPalette.current
    val fg = (if (selected) palette.selectionForeground else palette.text).toComposeColor()
    DropdownMenuItem(
        text = { text(fg) },
        onClick = onClick,
        trailingIcon = trailingIcon?.let { icon -> { icon(fg) } },
        selected = selected,
    )
}

@Composable
internal fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor(),
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        )
    }
}

/**
 * The items of a font dropdown, shared by the flow format bar, the text tool
 * config popup and the text box style bar. Each entry previews in its own
 * family; [monoOnly] restricts the list to monospace families. A null pick
 * (offered when [withDefault]) means "inherit the document default".
 */
@Composable
fun FontMenuItems(
    current: FontFace?,
    monoOnly: Boolean = false,
    withDefault: Boolean = false,
    onPick: (FontFace?) -> Unit,
) {
    val palette = LocalPalette.current
    if (withDefault) {
        ChoiceMenuItem(current == null, onClick = { onPick(null) }) { fg ->
            Text(stringResource(R.string.default_choice), color = fg, fontSize = 14.sp)
        }
    }
    for (choice in FontCatalog.choices()) {
        if (monoOnly && !choice.mono) continue
        ChoiceMenuItem(choice.face == current, onClick = { onPick(choice.face) }) { fg ->
            Text(fontLabel(choice.face), color = fg, style = TextStyle(fontFamily = choice.face.toComposeFamily(), fontSize = 14.sp))
        }
    }
}
