package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.DialogProperties
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.R
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.FunctionCurve
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.SelectionAction
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.SelectionBarLayout
import com.xnotes.core.tools.ToolConversions
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * What the selection menu needs from whichever editor is open.
 *
 * The bar is the same bar on either canvas, so it is the same composable rather than a second one
 * that resembles it. Taking the few members it reads through an interface is what makes "identical"
 * a property of the code rather than something to keep checking, exactly as [ToolPopupHost] does
 * for the tool popups.
 */
interface SelectionMenuHost : SwatchHost {
    /** Where the settled selection sits in viewport pixels, or null to hide the bar. */
    val selectionMenuRect: com.xnotes.core.geometry.Rect?

    fun deleteSelection()
    fun cutSelection()
    fun copySelection()
    /** Move the selection up or down the stack of items it sits in, as one undo step. */
    fun arrange(how: com.xnotes.core.model.Arrange)
    fun duplicateSelection()

    /** The text box selected on its own, which the bar offers to open and to frame. */
    fun selectedTextBox(): com.xnotes.core.model.TextItem? = null

    /** Open [selectedTextBox] for typing, arming the Text box tool. */
    fun editSelectedTextBox() {}

    /**
     * Give [selectedTextBox] the frame [style]. A [preview] call skips history, and the next call
     * without it records everything since as one undo step.
     */
    fun setSelectedBoxStyle(style: com.xnotes.core.model.BoxStyle, preview: Boolean = false) {}

    /** The audio pill selected on its own, which the bar offers to play. */
    fun selectedAudio(): com.xnotes.core.model.AudioItem?

    /** Play or pause [selectedAudio]: the way to play it when a finger draws rather than pans. */
    fun playSelectedAudio()

    /** Give [selectedAudio] a new name, undoably. */
    fun renameSelectedAudio(name: String)

    /** Untie the ink written during [selectedAudio] from it, undoably, with Undo on the message. */
    fun unlinkSelectedAudio()
    fun dismissSelectionMenu()

    /** Pin the selection where it is and put it away; a held finger over it offers to release it. */
    fun lockSelection()

    /** What the lone selected function curve plots, or null when the selection is not one. */
    val selectionFunction: FunctionSpec?

    /** Replot the selected function curve; false when [spec] does not parse or has no values. */
    fun setSelectionFunction(spec: FunctionSpec): Boolean

    /** True when the selection is a single text box (shows the Edit action). */
    val selectionIsText: Boolean

    /** Reopen the text editor on the selected text box. */
    fun editSelectionText()

    /** The LaTeX of the lone selected formula box, or null when the selection is not one. */
    val selectionMath: String?

    /** Replace the selected formula's LaTeX; false when it does not set. */
    fun setSelectionMath(latex: String): Boolean

    /** True when exactly one item is selected (it can carry a hyperlink). */
    val selectionCanLink: Boolean

    /** The selected item's hyperlink, or null when none / not a single item. */
    val selectionLink: String?

    /** Set (or clear, when null) the selected item's hyperlink. */
    fun setSelectionLink(url: String?)

    /** True when the lone selected item is an image or shape that can take a caption below it. */
    val selectionCanCaption: Boolean

    /** True when the selection is an image or shape grouped with its caption. */
    val selectionHasCaption: Boolean

    /** Start typing a caption under the selected image or shape. */
    fun captionSelection()

    /** True when the selection contains handwriting (shows the Convert to text action). */
    val selectionHasInk: Boolean

    /** Recognize the selected handwriting and replace it with a text box (async). */
    fun convertSelectionToText()

    /** True when the handwritten-maths add-on is downloaded (shows Convert to math). */
    val canConvertToMath: Boolean

    /** Recognize the selected handwriting as a formula and replace it with a formula box (async). */
    fun convertSelectionToMath()

    /** The colour and width of every selected stroke/shape, for the restyle popup to open on. */
    fun selectionStyles(): List<DrawStyle>

    /**
     * Recolour and/or re-thicken the selection; a null [color] or [width] leaves that half alone.
     * A [preview] call skips history, and the next call without it records everything since as one
     * undo step, so dragging the thickness slider is a single edit rather than one per sample.
     */
    fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean = false)

    /** The fills of the selected shapes that have one, for the restyle popup to open on. */
    fun selectionFills(): List<Rgba>

    /** Which of dashed, neon and filled the selection can take, and whether it already has them. */
    fun selectionEffects(): com.xnotes.core.history.Effects

    /** Turn the non-null of [dashed], [neon] and [filled] on or off across the selection, as one undo step. */
    fun setSelectionEffects(dashed: Boolean? = null, neon: Boolean? = null, filled: Boolean? = null)

    /** Dash, glow and fill details of the selected shapes, and of the pen strokes: read off the first, set on all. */
    fun selectionShapeLook(): com.xnotes.core.history.ShapeLook? = null
    fun restyleSelectionShapes(preview: Boolean = false, change: (com.xnotes.core.history.ShapeLook) -> com.xnotes.core.history.ShapeLook) {}
    fun selectionPenLook(): com.xnotes.core.history.PenLook? = null
    fun restyleSelectionPens(preview: Boolean = false, change: (com.xnotes.core.history.PenLook) -> com.xnotes.core.history.PenLook) {}
    fun selectionTableLook(): com.xnotes.core.model.TableLook? = null
    fun selectionOnlyTables(): Boolean = false
    fun restyleSelectionTables(preview: Boolean = false, change: (com.xnotes.core.model.TableLook) -> com.xnotes.core.model.TableLook) {}
    fun selectionTableCells(): com.xnotes.core.model.CellStyle? = null
    fun restyleSelectionCells(preview: Boolean = false, change: (com.xnotes.core.model.CellStyle) -> com.xnotes.core.model.CellStyle) {}

    /** The first selected tape's pattern, for the restyle popup to open on; null when no tape is selected. */
    fun selectionTapePattern(): com.xnotes.core.model.TapePattern?

    /** Print [pattern] on every selected tape, as one undo step. */
    fun setSelectionTapePattern(pattern: com.xnotes.core.model.TapePattern)

    /**
     * Recolour the selected shapes' fills in [color], each keeping its opacity; null fills each in
     * its outline's colour. A [preview] call skips history, and the next call without it records
     * everything since as one undo step, so a drag through the picker is a single edit.
     */
    fun refillSelection(color: Rgba?, preview: Boolean = false)

    /** A lone selected star's points or polygon's sides and the counts it can take; null otherwise. */
    fun selectionCount(): ShapeCount?

    /** Set that count, as one undo step. */
    fun setSelectionCount(n: Int)

    /** True when the lone selection is a shape that can take a typed label. */
    fun canLabelSelection(): Boolean

    /** True when that shape's label already has words. */
    fun selectionHasLabel(): Boolean

    /** Open the editor on that shape's label. */
    fun labelSelection()

    /** True when the lone selection is a connector whose route can be planned afresh. */
    fun canRerouteSelection(): Boolean

    /** Plan that connector's route afresh round what is in its way, as one undo step. */
    fun rerouteSelection()

    /** True when the selection can be mirrored: it holds no text, which would read backwards. */
    fun canMirrorSelection(): Boolean

    /** Mirror the selection about its own centre, left to right or [upsideDown], as one undo step. */
    fun mirrorSelection(upsideDown: Boolean)

    /** Put the selection, drawn alone over what lies beneath it and cut to its bounds, on the clipboard as a picture. */
    fun copySelectionAsImage()

    /** Keep copies of the selection in the sticker library, to be placed again as editable items. */
    fun saveSelectionAsSticker()

    /** That picture as a PNG ready for the share sheet, or null (with a message) when it could not be made. */
    fun selectionImageForShare(): java.io.File?

    /** True when the selection is two or more items not already one group. */
    fun canGroupSelection(): Boolean

    /** True when the selection holds a group. */
    fun canUngroupSelection(): Boolean

    /** Put the selection in one group, any groups in it merged, as one undo step. */
    fun groupSelection()

    /** Free every item of the groups in the selection, as one undo step. */
    fun ungroupSelection()

    /** True when the selection is a lone picture, which the bar offers to crop. */
    fun canCropSelection(): Boolean

    /** Put that picture in crop mode, the frame standing over it. */
    fun startCrop()

    /** Pixels per inch of the document's units, to show sizes in mm. */
    fun documentDpi(): Int

    /** The selection box's width and height in document px, or null when it cannot be sized. */
    fun selectionSize(): Pair<Double, Double>?

    /** Scale the selection about its centre to [width] by [height] document px, as one undo step. */
    fun resizeSelection(width: Double, height: Double)
}

/**
 * Floating action bar shown above a settled selection (spec-adjacent): delete,
 * cut, copy, arrange, duplicate, and an overflow menu for the rest.
 * Hidden while moving/resizing. Rotation is not here: everything the bar can be
 * shown over turns by its own grip.
 */
@Composable
fun SelectionMenu(
    host: SelectionMenuHost,
    layout: SelectionBarLayout,
    onSaveAudio: ((com.xnotes.core.model.AudioItem) -> Unit)? = null,
    onShareAudio: ((com.xnotes.core.model.AudioItem) -> Unit)? = null,
    onShareImage: ((java.io.File) -> Unit)? = null,
) {
    val rect = host.selectionMenuRect ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var overflowOpen by remember { mutableStateOf(false) }
    var subOpen by remember { mutableStateOf<SelectionAction?>(null) }
    var styleOpen by remember { mutableStateOf(false) }
    var linkDialogOpen by remember { mutableStateOf(false) }
    var functionDialogOpen by remember { mutableStateOf(false) }
    var mathDialogOpen by remember { mutableStateOf(false) }
    var renamingAudio by remember { mutableStateOf<com.xnotes.core.model.AudioItem?>(null) }
    var sizeDialogOpen by remember { mutableStateOf(false) }

    val audio = host.selectedAudio()
    val size = host.selectionSize()
    val textBox = host.selectedTextBox()
    val barHeightPx = with(density) { 48.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val barAbove = rect.top.toFloat() - barHeightPx - gap > 0f
    val yPx = if (barAbove) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }

    fun applies(a: SelectionAction): Boolean = when (a) {
        SelectionAction.PLAY, SelectionAction.AUDIO_RENAME, SelectionAction.AUDIO_UNLINK -> audio != null
        SelectionAction.AUDIO_SAVE -> audio != null && onSaveAudio != null
        SelectionAction.AUDIO_SHARE -> audio != null && onShareAudio != null
        SelectionAction.CROP -> host.canCropSelection()
        SelectionAction.SIZE -> size != null
        SelectionAction.MIRROR -> host.canMirrorSelection()
        // Nothing selected carries ink, a fill or text colour: an image has nothing to restyle.
        SelectionAction.CHANGE_STYLE -> host.selectionStyles().isNotEmpty() || host.selectionFills().isNotEmpty() || textBox != null
        SelectionAction.EDIT_FUNCTION -> host.selectionFunction != null
        SelectionAction.EDIT_TEXT -> host.selectionIsText
        SelectionAction.CONVERT_TEXT -> host.selectionHasInk && com.xnotes.BuildConfig.OCR
        SelectionAction.CONVERT_MATH -> host.selectionHasInk && host.canConvertToMath
        SelectionAction.LINK -> host.selectionCanLink
        SelectionAction.CAPTION -> host.selectionCanCaption
        SelectionAction.LABEL -> host.canLabelSelection()
        SelectionAction.REROUTE -> host.canRerouteSelection()
        SelectionAction.GROUP -> host.canGroupSelection()
        SelectionAction.UNGROUP -> host.canUngroupSelection()
        SelectionAction.SHARE_IMAGE -> onShareImage != null
        else -> true
    }

    val playing = audio != null && AudioPlayback.session?.let { it.item === audio && it.playing } == true
    val label: @Composable (SelectionAction) -> String = { a ->
        when (a) {
            SelectionAction.PLAY -> stringResource(if (playing) R.string.audio_pause else R.string.audio_play)
            SelectionAction.LINK -> stringResource(if (host.selectionLink != null) R.string.canvas_menu_edit_link else R.string.canvas_menu_add_link)
            SelectionAction.CAPTION -> stringResource(if (host.selectionHasCaption) R.string.canvas_menu_edit_caption else R.string.canvas_menu_add_caption)
            SelectionAction.LABEL -> stringResource(if (host.selectionHasLabel()) R.string.shape_label_edit else R.string.shape_label_add)
            else -> stringResource(a.labelRes)
        }
    }

    fun run(a: SelectionAction) {
        when (a) {
            SelectionAction.DELETE -> host.deleteSelection()
            SelectionAction.CUT -> host.cutSelection()
            SelectionAction.COPY -> { host.copySelection(); host.dismissSelectionMenu() }
            SelectionAction.DUPLICATE -> host.duplicateSelection()
            SelectionAction.ARRANGE, SelectionAction.MIRROR -> subOpen = a
            SelectionAction.LOCK -> host.lockSelection()
            SelectionAction.PLAY -> host.playSelectedAudio()
            SelectionAction.CROP -> host.startCrop()
            SelectionAction.SIZE -> sizeDialogOpen = true
            SelectionAction.AUDIO_RENAME -> renamingAudio = audio
            SelectionAction.AUDIO_SAVE -> audio?.let { onSaveAudio?.invoke(it) }
            SelectionAction.AUDIO_SHARE -> audio?.let { onShareAudio?.invoke(it) }
            SelectionAction.AUDIO_UNLINK -> host.unlinkSelectedAudio()
            SelectionAction.CHANGE_STYLE -> styleOpen = true
            SelectionAction.EDIT_FUNCTION -> functionDialogOpen = true
            // A formula is edited as its LaTeX source, not in the text box editor.
            SelectionAction.EDIT_TEXT -> if (host.selectionMath != null) mathDialogOpen = true else host.editSelectedTextBox()
            SelectionAction.CONVERT_TEXT -> host.convertSelectionToText()
            SelectionAction.CONVERT_MATH -> host.convertSelectionToMath()
            SelectionAction.LINK -> linkDialogOpen = true
            SelectionAction.CAPTION -> host.captionSelection()
            SelectionAction.LABEL -> host.labelSelection()
            SelectionAction.REROUTE -> host.rerouteSelection()
            SelectionAction.GROUP -> host.groupSelection()
            SelectionAction.UNGROUP -> host.ungroupSelection()
            SelectionAction.COPY_IMAGE -> host.copySelectionAsImage()
            SelectionAction.SHARE_IMAGE -> host.selectionImageForShare()?.let { onShareImage?.invoke(it) }
            SelectionAction.SAVE_STICKER -> { host.saveSelectionAsSticker(); host.dismissSelectionMenu() }
        }
    }

    /** The rows Arrange and Mirror open: a dropdown on the bar, inline rows in the 3-dot menu. */
    val subRows: @Composable (SelectionAction) -> List<Pair<String, () -> Unit>>? = { a ->
        when (a) {
            SelectionAction.ARRANGE -> ARRANGE_ROWS.map { (how, res) -> stringResource(res) to { host.arrange(how) } }
            SelectionAction.MIRROR -> listOf(
                stringResource(R.string.mirror_left_right) to { host.mirrorSelection(upsideDown = false) },
                stringResource(R.string.mirror_upside_down) to { host.mirrorSelection(upsideDown = true) },
            )
            else -> null
        }
    }

    val styleOnBar = layout.bar.any { it.action == SelectionAction.CHANGE_STYLE && it.visible }
    val barActions = layout.bar.filter { it.visible && applies(it.action) }.map { it.action }
    val moreActions = layout.more.filter { it.visible && applies(it.action) }.map { it.action }

    CompositionLocalProvider(LocalTipSide provides tipSideFor(barAbove)) {
        Row(
            modifier = Modifier
                // Kept inside the pane; on a narrow one the icons scroll.
                .layout { measurable, constraints ->
                    val margin = 8.dp.roundToPx()
                    val bar = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0)))
                    val x = (centerX - bar.width / 2f).roundToInt().coerceIn(margin, (constraints.maxWidth - bar.width - margin).coerceAtLeast(margin))
                    layout(bar.width, bar.height) { bar.place(x, yPx.roundToInt()) }
                }
                .floatingBar()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = BAR_END),
        ) {
            for (a in barActions) Box {
                ActionIcon(
                    if (a == SelectionAction.PLAY && playing) XnotesIcons.pause else a.icon,
                    label(a),
                    shortcut = if (a == SelectionAction.DELETE) Keys.DELETE else null,
                    tint = if (a == SelectionAction.DELETE) palette.danger.toComposeColor() else null,
                ) { run(a) }
                subRows(a)?.let { rows ->
                    DropdownMenu(
                        expanded = subOpen == a,
                        onDismissRequest = { subOpen = null },
                        properties = PopupProperties(focusable = false),
                    ) {
                        for ((text, onClick) in rows) MenuItem(text, onClick = { subOpen = null; onClick() })
                    }
                }
                if (a == SelectionAction.CHANGE_STYLE && styleOpen) {
                    // Closing settles any preview the slider left open.
                    SelectionStylePopup(host) { host.restyleSelection(null, null); styleOpen = false }
                }
            }
            if (moreActions.isNotEmpty()) Box {
                ActionIcon(XnotesIcons.more, stringResource(R.string.more)) { overflowOpen = true }
                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    for (a in moreActions) {
                        val rows = subRows(a)
                        if (rows != null) {
                            for ((text, onClick) in rows) MenuItem(text, onClick = { overflowOpen = false; onClick() })
                        } else {
                            MenuItem(label(a), a.icon, danger = a == SelectionAction.DELETE, onClick = { overflowOpen = false; run(a) })
                        }
                    }
                }
                if (!styleOnBar && styleOpen) {
                    SelectionStylePopup(host) { host.restyleSelection(null, null); styleOpen = false }
                }
            }
        }
        val latex = host.selectionMath
        if (mathDialogOpen && latex != null) {
            LatexDialog(
                initial = latex,
                onConfirm = { if (host.setSelectionMath(it)) mathDialogOpen = false },
                onDismiss = { mathDialogOpen = false },
            )
        }
        val function = host.selectionFunction
        if (functionDialogOpen && function != null) {
            FunctionDialog(
                initial = function,
                onConfirm = { if (host.setSelectionFunction(it)) functionDialogOpen = false },
                onDismiss = { functionDialogOpen = false },
            )
        }
        if (linkDialogOpen) {
            LinkDialog(
                initial = host.selectionLink ?: "",
                onConfirm = { host.setSelectionLink(it); linkDialogOpen = false },
                onRemove = { host.setSelectionLink(null); linkDialogOpen = false },
                onDismiss = { linkDialogOpen = false },
            )
        }
        if (sizeDialogOpen && size != null) {
            SizeDialog(
                initial = size,
                dpi = host.documentDpi(),
                onConfirm = { w, h -> sizeDialogOpen = false; host.resizeSelection(w, h) },
                onDismiss = { sizeDialogOpen = false },
            )
        }
        renamingAudio?.let { item ->
            AudioNameDialog(item.name, onConfirm = { renamingAudio = null; host.renameSelectedAudio(it) }) { renamingAudio = null }
        }
    }
}

/** The icon an action shows on the selection bar and in the customizer. */
val SelectionAction.icon: ImageVector
    get() = when (this) {
        SelectionAction.DELETE -> XnotesIcons.trash
        SelectionAction.CUT -> XnotesIcons.cut
        SelectionAction.COPY -> XnotesIcons.copy
        SelectionAction.DUPLICATE -> XnotesIcons.duplicate
        SelectionAction.ARRANGE -> XnotesIcons.front
        SelectionAction.LOCK -> XnotesIcons.lock
        SelectionAction.PLAY -> XnotesIcons.play
        SelectionAction.CROP -> XnotesIcons.crop
        SelectionAction.SIZE -> XnotesIcons.resize
        SelectionAction.MIRROR -> XnotesIcons.mirror
        SelectionAction.AUDIO_RENAME -> XnotesIcons.rename
        SelectionAction.AUDIO_SAVE -> XnotesIcons.download
        SelectionAction.AUDIO_SHARE -> XnotesIcons.share
        SelectionAction.AUDIO_UNLINK -> XnotesIcons.unlink
        SelectionAction.CHANGE_STYLE -> XnotesIcons.palette
        SelectionAction.EDIT_FUNCTION -> XnotesIcons.function
        SelectionAction.EDIT_TEXT -> XnotesIcons.edit
        SelectionAction.CONVERT_TEXT -> XnotesIcons.convertText
        SelectionAction.CONVERT_MATH -> XnotesIcons.sigma
        SelectionAction.LINK -> XnotesIcons.link
        SelectionAction.CAPTION -> XnotesIcons.caption
        SelectionAction.LABEL -> XnotesIcons.tag
        SelectionAction.REROUTE -> XnotesIcons.reroute
        SelectionAction.GROUP -> XnotesIcons.group
        SelectionAction.UNGROUP -> XnotesIcons.ungroup
        SelectionAction.COPY_IMAGE -> XnotesIcons.copyImage
        SelectionAction.SHARE_IMAGE -> XnotesIcons.share
        SelectionAction.SAVE_STICKER -> XnotesIcons.sticker
    }

/** The Arrange menu's rows, top of the stack first. */
private val ARRANGE_ROWS = listOf(
    com.xnotes.core.model.Arrange.FRONT to R.string.bring_to_front,
    com.xnotes.core.model.Arrange.FORWARD to R.string.bring_forward,
    com.xnotes.core.model.Arrange.BACKWARD to R.string.send_backward,
    com.xnotes.core.model.Arrange.BACK to R.string.send_to_back,
)

/**
 * The selection's exact width and height, typed in px (document units) or mm. While the aspect
 * ratio is kept, the other side follows; a flat side (a straight line) is left to itself.
 */
@Composable
private fun SizeDialog(initial: Pair<Double, Double>, dpi: Int, onConfirm: (Double, Double) -> Unit, onDismiss: () -> Unit) {
    val ratio = if (initial.first > 0.0 && initial.second > 0.0) initial.second / initial.first else null
    var mm by remember { mutableStateOf(false) }
    fun toUnit(px: Double) = if (mm) PageSize.pxToMm(px, dpi) else px
    fun toPx(v: Double) = if (mm) PageSize.mmToPx(v, dpi) else v
    fun fmt(v: Double) = "%.1f".format(java.util.Locale.ROOT, v).removeSuffix(".0")
    fun parse(raw: String) = raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
    var w by remember { mutableStateOf(fmt(initial.first)) }
    var h by remember { mutableStateOf(fmt(initial.second)) }
    var keep by remember { mutableStateOf(ratio != null) }
    val wPx = parse(w)?.let(::toPx)
    val hPx = parse(h)?.let(::toPx)
    fun switchUnits(toMm: Boolean) {
        if (toMm == mm) return
        val pw = wPx ?: initial.first
        val ph = hPx ?: initial.second
        mm = toMm
        w = fmt(toUnit(pw))
        h = fmt(toUnit(ph))
    }
    val number = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    val unit = stringResource(if (mm) R.string.units_mm else R.string.units_px)
    SheetDialog(
        title = stringResource(R.string.selection_size),
        onDismiss = onDismiss,
        foot = {
            ConfirmButtons(stringResource(R.string.apply), onDismiss, enabled = wPx != null && hPx != null) {
                if (wPx != null && hPx != null) onConfirm(wPx, hPx)
            }
        },
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip(stringResource(R.string.units_px), !mm) { switchUnits(false) }
                    ModeChip(stringResource(R.string.units_mm), mm) { switchUnits(true) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = w,
                        onValueChange = { w = it; if (keep && ratio != null) parse(it)?.let { v -> h = fmt(v * ratio) } },
                        singleLine = true,
                        label = { Text("${stringResource(R.string.width)} ($unit)") },
                        isError = wPx == null,
                        keyboardOptions = number,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = h,
                        onValueChange = { h = it; if (keep && ratio != null) parse(it)?.let { v -> w = fmt(v / ratio) } },
                        singleLine = true,
                        label = { Text("${stringResource(R.string.height)} ($unit)") },
                        isError = hPx == null,
                        keyboardOptions = number,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (ratio != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { keep = !keep }) {
                        androidx.compose.material3.Checkbox(keep, { keep = it })
                        Text(stringResource(R.string.keep_aspect_ratio))
                    }
                }
            }
    }
}

/** Rename a recording: its name, ready to type over. */
@Composable
private fun AudioNameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val rename = { if (text.text.isBlank()) onDismiss() else onConfirm(text.text) }
    SheetDialog(
        title = stringResource(R.string.rename_recording),
        onDismiss = onDismiss,
        foot = { ConfirmButtons(stringResource(R.string.rename), onDismiss, onConfirm = rename) },
    ) {
        DialogField(text, { text = it }, label = stringResource(R.string.audio_name), autoFocus = true, onDone = rename, onEscape = onDismiss)
    }
}

/**
 * Retype what a function curve plots, e.g. sin(2x) or x^3 - x, and over which x range. The range
 * fields take expressions too, so "2pi" works. Save stays disabled until the curve can be drawn.
 */
@Composable
private fun FunctionDialog(initial: FunctionSpec, onConfirm: (FunctionSpec) -> Unit, onDismiss: () -> Unit) {
    var expr by remember { mutableStateOf(initial.expr) }
    var from by remember { mutableStateOf(formatBound(initial.from)) }
    var to by remember { mutableStateOf(formatBound(initial.to)) }
    val spec = run {
        val a = FunctionCurve.constant(from) ?: return@run null
        val b = FunctionCurve.constant(to) ?: return@run null
        FunctionSpec(expr.trim(), a, b).takeIf { it.normalizedSamples() != null }
    }
    SheetDialog(
        title = stringResource(R.string.canvas_menu_function),
        onDismiss = onDismiss,
        foot = { ConfirmButtons(stringResource(R.string.save), onDismiss, enabled = spec != null) { spec?.let(onConfirm) } },
    ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = expr,
                    onValueChange = { expr = it },
                    singleLine = true,
                    label = { Text("y =") },
                    isError = spec == null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.canvas_menu_x_from)) },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.canvas_menu_x_to)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
    }
}

/** Put [template] at the cursor, wrapping the selection at its `#`, and leave the cursor there. */
private fun insertLatex(value: TextFieldValue, template: String): TextFieldValue {
    val sel = value.selection
    val (inserted, cursor) = latexInsertion(value.text.substring(0, sel.min), value.text.substring(sel.min, sel.max), template)
    val text = value.text.substring(0, sel.min) + inserted + value.text.substring(sel.max)
    return TextFieldValue(text, TextRange(sel.min + cursor))
}

/**
 * Edit a formula box's LaTeX: a live preview over the source, typed on the maths keyboard (numbers
 * and functions, as in flowing text) with the soft keyboard kept down until ABC asks for it. On a
 * tablet the dialog and its keys grow to use the room. Save stays on the dialog when the renderer
 * cannot set the result.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LatexDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= COMPACT_WIDTH_DP
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var mathKeys by remember { mutableStateOf(true) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val scope = rememberCoroutineScope()
    val shape = MaterialTheme.shapes.large
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Read inside the dialog so they act on its window, not the activity's.
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth(if (wide) 0.86f else 1f)
                .widthIn(max = 1100.dp)
                .padding(if (wide) 0.dp else 12.dp)
                .clip(shape)
                .background(palette.menuBg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), shape)
                .padding(if (wide) 24.dp else 16.dp),
        ) {
            Text(stringResource(R.string.canvas_menu_formula), style = MaterialTheme.typography.headlineSmall, color = palette.text.toComposeColor())
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (wide) 120.dp else 72.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(palette.surface.toComposeColor())
                        .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Latex(
                        latex = "\\displaystyle " + value.text,
                        config = LatexConfig(fontSize = if (wide) 30.sp else 22.sp, theme = latexTheme()),
                    )
                }
                // While the maths keys are up the field keeps its caret but asks for no soft keyboard.
                androidx.compose.ui.platform.InterceptPlatformTextInput(
                    interceptor = { request, next -> if (mathKeys) kotlinx.coroutines.awaitCancellation() else next.startInputMethod(request) },
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("LaTeX") },
                        minLines = if (wide) 2 else 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = if (wide) 18.sp else 15.sp,
                            color = palette.text.toComposeColor(),
                        ),
                    )
                }
                if (mathKeys) {
                    MathKeys(
                        MathKeyActions(
                            onKey = { value = insertLatex(value, it) },
                            onAbc = {
                                mathKeys = false
                                // The field restarts its input with the interceptor out of the way.
                                scope.launch {
                                    focusManager.clearFocus()
                                    kotlinx.coroutines.delay(30)
                                    runCatching { focus.requestFocus() }
                                    keyboard?.show()
                                }
                            },
                            onMove = { value = moveLatexCursor(value, it) },
                            onDelete = { value = deleteLatexBack(value) },
                            onEnter = { value = insertLatex(value, "\n") },
                        ),
                    )
                } else {
                    TextButton(onClick = { keyboard?.hide(); mathKeys = true }) { Text(stringResource(R.string.canvas_menu_maths_keys)) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(enabled = value.text.isNotBlank(), onClick = { onConfirm(value.text.trim()) }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

private fun moveLatexCursor(value: TextFieldValue, by: Int): TextFieldValue {
    val at = (if (by < 0) value.selection.min else value.selection.max) + by
    return value.copy(selection = TextRange(at.coerceIn(0, value.text.length)))
}

private fun deleteLatexBack(value: TextFieldValue): TextFieldValue {
    val sel = value.selection
    val from = if (sel.collapsed) (sel.min - 1).coerceAtLeast(0) else sel.min
    if (from == sel.max) return value
    return TextFieldValue(value.text.removeRange(from, sel.max), TextRange(from))
}

/** A range bound as the user would type it: multiples of pi as "2pi", the rest trimmed. */
private fun formatBound(v: Double): String {
    val k = v / Math.PI
    val rounded = Math.round(k * 2) / 2.0
    if (v != 0.0 && kotlin.math.abs(k - rounded) < 1e-9) {
        return when (rounded) {
            1.0 -> "pi"
            -1.0 -> "-pi"
            else -> "${rounded.toString().removeSuffix(".0")}pi"
        }
    }
    return v.toString().removeSuffix(".0")
}

/**
 * Add/edit a hyperlink on the selected item. A blank field with Save clears the link; the Remove
 * button (shown only when one exists) does the same. Only http/https/mailto actually open on tap
 * (the opener rejects the rest), so no scheme validation is forced here.
 */
@Composable
private fun LinkDialog(initial: String, onConfirm: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    SheetDialog(
        title = stringResource(R.string.insert_group_link),
        onDismiss = onDismiss,
        foot = {
            DialogButtons {
                if (initial.isNotEmpty()) DialogButton(stringResource(R.string.remove), ButtonKind.DISCARD, onClick = onRemove)
                else DialogButton(stringResource(R.string.cancel), onClick = onDismiss)
                DialogButton(stringResource(R.string.save), ButtonKind.CONFIRM) { onConfirm(text.trim()) }
            }
        },
    ) {
        DialogField(text, { text = it }, placeholder = "https://example.com", autoFocus = true, onDone = { onConfirm(text.trim()) }, onEscape = onDismiss)
    }
}

/**
 * "Change style" on a settled selection: the toolbar's swatches plus the full colour picker, and a
 * thickness slider. Both apply live, so the result can be judged on the page rather than guessed
 * at. The controls open on the styles the selection had when the popup did — a shared colour when
 * every item agrees, the first item's otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionStylePopup(host: SelectionMenuHost, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val opened = remember { host.selectionStyles() }
    val first = opened.firstOrNull()
    val shared = opened.firstOrNull()?.color?.takeIf { c -> opened.all { it.color == c } }
    var color by remember { mutableStateOf(shared ?: first?.color ?: Rgba(0, 0, 0)) }
    val sized = opened.firstOrNull { it.width != DrawStyle.NO_WIDTH }
    var width by remember { mutableStateOf((sized?.width ?: 1.0).toFloat()) }
    var effects by remember { mutableStateOf(host.selectionEffects()) }
    val fills = remember(effects) { host.selectionFills() }
    // Only dropped colours selected: they have a fill and nothing else to restyle.
    val fillsOnly = first == null
    val textBox = remember { host.selectedTextBox() }
    if (fillsOnly && fills.isEmpty() && textBox == null) return
    var boxStyle by remember { mutableStateOf(textBox?.box) }
    var look by remember { mutableStateOf(host.selectionShapeLook()) }
    var pen by remember { mutableStateOf(host.selectionPenLook()) }
    var table by remember { mutableStateOf(host.selectionTableLook()) }
    val onlyTables = remember { host.selectionOnlyTables() }
    val cells = remember { host.selectionTableCells() }
    if (cells != null) return CellStylePopup(host, cells, color, onDismiss)
    fun restyleShapes(preview: Boolean = false, change: (com.xnotes.core.history.ShapeLook) -> com.xnotes.core.history.ShapeLook = { it }) {
        host.restyleSelectionShapes(preview, change)
        look = host.selectionShapeLook()
    }
    fun restylePens(preview: Boolean = false, change: (com.xnotes.core.history.PenLook) -> com.xnotes.core.history.PenLook = { it }) {
        host.restyleSelectionPens(preview, change)
        pen = host.selectionPenLook()
    }
    // Closing settles whatever a box slider left as a preview.
    val close = {
        boxStyle?.let { host.setSelectedBoxStyle(it, preview = false) }
        if (look != null) restyleShapes()
        if (pen != null) restylePens()
        if (table != null) host.restyleSelectionTables { it }
        onDismiss()
    }

    ToolMenu(stringResource(R.string.title_change_style), close, focusable = false) {
        boxStyle?.let { style ->
            BoxStyleCard(style, host.hostRecentColors) { next, preview ->
                boxStyle = next
                host.setSelectedBoxStyle(next, preview)
            }
        }
        pen?.let { p ->
            PanelCard(padding = PaddingValues(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in Tool.PENS) MenuTile(penIcon(t), stringResource(t.labelRes), t == p.tool, 44.dp) { if (t != p.tool) restylePens { it.asPen(t) } }
                }
            }
        }
        val colourGroup: MenuGroup = {
                Column(Modifier.padding(10.dp)) {
                    PanelCaption(stringResource(R.string.caption_colour))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        host.hostToolbarColors.forEach { c ->
                            ColorDot(c.toComposeColor(), selected = color == c) {
                                color = c
                                host.restyleSelection(c, null)
                            }
                        }
                        ColorPickerDot(
                            color,
                            custom = color !in host.hostToolbarColors,
                            onPick = { color = it; host.restyleSelection(it, null) },
                            dismissOnPick = false,
                        ) { dismiss, pick ->
                            ColorPickerPopup(
                                initial = color,
                                recents = host.hostRecentColors,
                                onDismiss = dismiss,
                                onPick = pick,
                            )
                        }
                    }
                }
        }
        // Tables alone get their own cards, the colour among them; beside other items they add theirs after.
        if (onlyTables) table?.let { t ->
            val tableColour: MenuGroup = {
                MenuRow(stringResource(R.string.caption_colour)) {
                    ColorPickerDot(color, custom = true, onPick = { color = it; host.restyleSelection(it, null) }, dismissOnPick = false) { d, p ->
                        ColorPickerPopup(color, host.hostRecentColors, d, p)
                    }
                }
            }
            t.groups(color, host.hostRecentColors, tableColour, emptyList()) { next, preview ->
                table = next
                host.restyleSelectionTables(preview) { next }
            }.forEach { MenuCard(it) }
        }
        if (!onlyTables) MenuCard(buildList<MenuGroup> {
            if (!fillsOnly) add(colourGroup)
            host.selectionTapePattern()?.let { opened ->
                add {
                    var pattern by remember { mutableStateOf(opened) }
                    val patterns = com.xnotes.core.model.TapePattern.entries
                    Column(Modifier.padding(10.dp)) {
                        PanelCaption(stringResource(R.string.caption_pattern))
                        PanelSegmented(patterns.map { stringResource(it.labelRes) }, patterns.indexOf(pattern), { pattern = patterns[it]; host.setSelectionTapePattern(patterns[it]) })
                    }
                }
            }
            pen?.let { p ->
                val c = p.config
                if (p.tool == Tool.DASHED) {
                    add { MenuSlider(stringResource(R.string.caption_dash), c.dashLength.toFloat(), 2f..40f, onChangeFinished = { restylePens() }) { v -> restylePens(true) { it.copy(config = it.config.copy(dashLength = v.toDouble())) } } }
                    add { MenuSlider(stringResource(R.string.caption_gap), c.dashGap.toFloat(), 2f..40f, onChangeFinished = { restylePens() }) { v -> restylePens(true) { it.copy(config = it.config.copy(dashGap = v.toDouble())) } } }
                }
                // Neon paints at full strength whatever the opacity, so opacity hides under it, as on the pen.
                if (!c.neon) add {
                    MenuSwitch(stringResource(R.string.caption_opacity), c.opacityEnabled) { on -> restylePens { it.copy(config = it.config.copy(opacityEnabled = on)) } }
                    if (c.opacityEnabled) {
                        MenuSlider(stringResource(R.string.caption_intensity), ToolConversions.opacityToPercent(c.opacity).toFloat(), 10f..100f, step = 1f, child = true, onChangeFinished = { restylePens() }) { v ->
                            restylePens(true) { it.copy(config = it.config.copy(opacity = ToolConversions.percentToOpacity(v.toDouble()))) }
                        }
                    }
                }
                if (p.tool != Tool.DASHED) add {
                    MenuSwitch(stringResource(R.string.caption_neon), c.neon) { on -> restylePens { it.copy(config = it.config.copy(neon = on)) } }
                    if (c.neon) {
                        MenuSlider(stringResource(R.string.caption_glow_intensity), ToolConversions.neonStrengthToIntensity(c.neonStrength).toFloat(), 0f..100f, child = true, onChangeFinished = { restylePens() }) { v ->
                            restylePens(true) { it.copy(config = it.config.copy(neonStrength = ToolConversions.intensityToNeonStrength(v.toDouble()))) }
                        }
                    }
                }
            }
            effects.dashed?.let { on ->
                add {
                    MenuSwitch(stringResource(R.string.caption_dashed), on) { host.setSelectionEffects(dashed = it); effects = host.selectionEffects() }
                    val l = look
                    if (on && l != null) {
                        MenuSlider(stringResource(R.string.caption_dash), l.dashLength.toFloat(), 2f..40f, child = true, onChangeFinished = { restyleShapes() }) { v -> restyleShapes(true) { it.copy(dashLength = v.toDouble()) } }
                        MenuSlider(stringResource(R.string.caption_gap), l.dashGap.toFloat(), 2f..40f, child = true, onChangeFinished = { restyleShapes() }) { v -> restyleShapes(true) { it.copy(dashGap = v.toDouble()) } }
                    }
                }
            }
            effects.neon?.let { on ->
                add {
                    MenuSwitch(stringResource(R.string.caption_neon), on) { host.setSelectionEffects(neon = it); effects = host.selectionEffects() }
                    val l = look
                    if (on && l != null) {
                        MenuSlider(stringResource(R.string.caption_glow_intensity), ToolConversions.neonStrengthToIntensity(l.neonStrength).toFloat(), 0f..100f, child = true, onChangeFinished = { restyleShapes() }) { v ->
                            restyleShapes(true) { it.copy(neonStrength = ToolConversions.intensityToNeonStrength(v.toDouble())) }
                        }
                    }
                }
            }
            effects.filled?.let { on ->
                add {
                    MenuSwitch(stringResource(R.string.caption_fill), on) { host.setSelectionEffects(filled = it); effects = host.selectionEffects(); look = host.selectionShapeLook() }
                    val alpha = look?.fill?.a
                    if (on && alpha != null) {
                        val minPct = (com.xnotes.core.tools.ShapeConfig.FILL_ALPHA_MIN * 100).toFloat()
                        MenuSlider(stringResource(R.string.caption_opacity), alpha / 2.55f, minPct..100f, step = 1f, child = true, onChangeFinished = { restyleShapes() }) { v ->
                            restyleShapes(true) { it.copy(fill = it.fill?.copy(a = (v * 2.55f).roundToInt().coerceIn(0, 255))) }
                        }
                    }
                }
            }
            if (fills.isNotEmpty()) add {
                var fill by remember { mutableStateOf<Rgba?>(fills.first().copy(a = 255)) }
                var editingFill by remember { mutableStateOf<Int?>(null) }
                val outline = color
                Column(Modifier.padding(10.dp)) {
                    PanelCaption(stringResource(R.string.caption_fill_colour))
                    // The toolbar's own swatches; tapping the chosen one again opens the picker on it, as the toolbar does.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!fillsOnly) {
                            MatchDot(outline, selected = fill == null || fill == outline) {
                                fill = null
                                host.refillSelection(null)
                            }
                        }
                        host.shownSwatches.forEachIndexed { i, c ->
                            val on = fill == c
                            Box {
                                ColorDot(c.toComposeColor(), selected = on) {
                                    if (on) {
                                        editingFill = i
                                    } else {
                                        fill = c
                                        host.refillSelection(c)
                                    }
                                }
                                if (editingFill == i) {
                                    // However the picker closes, the drag through it settles as one undo step.
                                    val latest by rememberUpdatedState(fill)
                                    DisposableEffect(Unit) { onDispose { host.refillSelection(latest) } }
                                    ColorPickerPopup(
                                        initial = c,
                                        recents = host.hostRecentColors,
                                        onDismiss = {
                                            host.rememberSwatchColor(i)
                                            editingFill = null
                                        },
                                        onPick = { picked ->
                                            host.recolorSwatch(i, picked)
                                            fill = picked
                                            host.refillSelection(picked, preview = true)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            host.selectionCount()?.let { sc ->
                add {
                    MenuRow(stringResource(countLabel(sc.kind))) {
                        MenuStepper(
                            "${sc.n}", stringResource(R.string.count_fewer), stringResource(R.string.count_more), Modifier.width(116.dp),
                            canLess = sc.n > sc.range.first, canMore = sc.n < sc.range.last,
                            onLess = { host.setSelectionCount(sc.n - 1) }, onMore = { host.setSelectionCount(sc.n + 1) },
                        )
                    }
                }
            }
            // The drag previews live and commits on release, so it is one undo step, not fifty.
            if (!fillsOnly && sized != null) add {
                MenuSlider(
                    stringResource(R.string.caption_thickness),
                    width,
                    DrawStyle.MIN_WIDTH.toFloat()..DrawStyle.MAX_WIDTH.toFloat(),
                    step = ToolConversions.WIDTH_STEP.toFloat(),
                    onChangeFinished = { host.restyleSelection(null, null) },
                ) { w ->
                    width = w
                    host.restyleSelection(null, w.toDouble(), preview = true)
                }
            }
        })
        if (!onlyTables) table?.let { t ->
            t.groups(color, host.hostRecentColors, null, emptyList()) { next, preview ->
                table = next
                host.restyleSelectionTables(preview) { next }
            }.forEach { MenuCard(it) }
        }
        if (!fillsOnly) {
            Text(
                stringResource(R.string.change_style_hint),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 2.dp),
            )
        }
    }
}

/** A star's [n] points, a polygon's [n] sides and so on ([kind] says which), and the counts it can take. */
class ShapeCount(val n: Int, val range: IntRange, val kind: com.xnotes.core.shapes.CountKind) {
    companion object {
        fun of(shape: com.xnotes.core.model.ShapeItem?): ShapeCount? {
            val s = shape ?: return null
            val range = s.shape.nRange ?: return null
            return ShapeCount(s.n.coerceIn(range.first, range.last), range, s.shape.countKind)
        }
    }
}

/** "Match outline" for a fill: the outline's colour with a link over it. */
@Composable
private fun MatchDot(outline: Rgba, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(stringResource(R.string.fill_match_outline)) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(outline.toComposeColor())
                .border(if (selected) 2.dp else 1.dp, (if (selected) palette.accent else palette.border).toComposeColor(), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (outline.luminance() > 0.55) Rgba(0, 0, 0) else Rgba(255, 255, 255)
            Icon(XnotesIcons.link, contentDescription = null, tint = tint.toComposeColor(), modifier = Modifier.size(14.dp))
        }
    }
}

/**
 * The screenshot tool's floating action, shown above the frozen capture rectangle: a single
 * "Copy as image" button that renders the region and puts it on the system clipboard.
 */
@Composable
fun ScreenshotMenu(editor: Editor, onShare: (java.io.File) -> Unit) {
    val rect = editor.screenshotMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val barHeightPx = with(density) { 44.dp.toPx() }
    val barWidthPx = with(density) { 224.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }
    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .floatingBar()
            .padding(horizontal = BAR_END),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clickable { editor.copyScreenshotAsImage() }.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                XnotesIcons.copy,
                contentDescription = stringResource(R.string.copy_as_image),
                tint = palette.text.toComposeColor(),
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.copy_as_image),
                color = palette.text.toComposeColor(),
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(Modifier.width(1.dp).height(24.dp).background(palette.border.toComposeColor()))
        ActionIcon(XnotesIcons.share, stringResource(R.string.share)) { editor.screenshotImageForShare()?.let(onShare) }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, enabled: Boolean = true, active: Boolean = false, shortcut: String? = null, tint: Color? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val color = when {
        !enabled -> palette.text.toComposeColor().copy(alpha = 0.35f)
        active -> palette.accent.toComposeColor()
        else -> tint ?: palette.text.toComposeColor()
    }
    Tooltip(desc, shortcut = shortcut) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(46.dp)) {
            Icon(icon, contentDescription = desc, tint = color, modifier = Modifier.size(22.dp))
        }
    }
}

/** A bar placed beside what it acts on opens its tips away from it, on the same side as itself. */
private fun tipSideFor(barAbove: Boolean) = if (barAbove) TipSide.ABOVE else TipSide.BELOW

/**
 * What the long-press menu needs from whichever editor is open. Same reasoning as
 * [SelectionMenuHost]: one menu, taken through an interface, rather than two that resemble each
 * other and drift.
 */
interface LongPressMenuHost {
    /** Where the press landed, or null when no menu is open. */
    val contextMenu: ContextMenuTarget?

    val hasClipboardItems: Boolean
    val clipboardHasImage: Boolean

    fun pasteItemsAt(content: com.xnotes.core.geometry.Pt)
    fun pasteClipboardImageAt(content: com.xnotes.core.geometry.Pt)
    fun dismissContextMenu()

    /** Release [item], so it can be selected again. */
    fun unlockItem(item: com.xnotes.core.model.CanvasItem)
}

/**
 * Long-press menu: paste copied items or an image from the system clipboard at the press point, or
 * insert an image there. A press that landed on a locked item offers only to release it, since
 * nothing else can be done with one and there is no other way back.
 */
@Composable
fun LongPressMenu(host: LongPressMenuHost, onInsertImageAt: (com.xnotes.core.geometry.Pt) -> Unit) {
    val target = host.contextMenu ?: return
    val density = LocalDensity.current
    val xDp = with(density) { target.viewportX.toFloat().toDp() }
    val yDp = with(density) { target.viewportY.toFloat().toDp() }

    Box(modifier = Modifier.offset(xDp, yDp).size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = { host.dismissContextMenu() }) {
            val locked = target.locked
            if (locked != null) {
                MenuItem(stringResource(R.string.unlock), XnotesIcons.unlock) { host.unlockItem(locked); host.dismissContextMenu() }
                return@DropdownMenu
            }
            if (host.hasClipboardItems) {
                MenuItem(stringResource(R.string.paste_here), XnotesIcons.paste) { host.pasteItemsAt(target.content); host.dismissContextMenu() }
            }
            if (host.clipboardHasImage) {
                MenuItem(stringResource(R.string.paste_image), XnotesIcons.image) { host.pasteClipboardImageAt(target.content); host.dismissContextMenu() }
            }
            MenuItem(stringResource(R.string.insert_image_ellipsis), XnotesIcons.plus) { onInsertImageAt(target.content); host.dismissContextMenu() }
        }
    }
}

/**
 * The flow-editing action bar (long press with the text tool, after the word
 * selection lands): a thin icon row like [SelectionMenu], anchored above the
 * selection so the drag handles stay visible. Not a popup: it never steals
 * focus (the keyboard stays up) and any canvas touch quietly retires it. The
 * paste icon expands the explicit paste modes (no auto-detection anywhere).
 */
@Composable
fun FlowEditMenu(editor: Editor) {
    val rect = editor.flowContextMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val hasClip = editor.clipboardHasText()
    val hasSelection = editor.flowHasSelection
    var pasteOpen by remember { mutableStateOf(false) }

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (4 * 46 + 8).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    // When pushed below the selection, also clear the teardrop handles hanging there.
    val handleClearance = with(density) { (2 * com.xnotes.canvas.TextHandles.RADIUS_DP).dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val barAbove = rect.top.toFloat() - barHeightPx - gap > 0f
    val yPx = if (barAbove) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + handleClearance + gap
    }

    CompositionLocalProvider(LocalTipSide provides tipSideFor(barAbove)) {
        Row(
            modifier = Modifier
                .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
                .floatingBar()
                .padding(horizontal = BAR_END),
        ) {
            ActionIcon(XnotesIcons.cut, stringResource(R.string.cut), enabled = hasSelection, shortcut = Keys.CUT) {
                editor.flowCut(); editor.dismissFlowContextMenu()
            }
            ActionIcon(XnotesIcons.copy, stringResource(R.string.copy), enabled = hasSelection, shortcut = Keys.COPY) {
                editor.flowCopy(); editor.dismissFlowContextMenu()
            }
            Box {
                ActionIcon(XnotesIcons.paste, stringResource(R.string.paste), enabled = hasClip) { pasteOpen = true }
                DropdownMenu(
                    expanded = pasteOpen,
                    onDismissRequest = { pasteOpen = false },
                    properties = PopupProperties(focusable = false),
                ) {
                    MenuItem(stringResource(R.string.paste), onClick = {
                        pasteOpen = false; editor.pasteAtCaret(); editor.dismissFlowContextMenu()
                    })
                    MenuItem(stringResource(R.string.paste_plain), onClick = {
                        pasteOpen = false; editor.pastePlainAtCaret(); editor.dismissFlowContextMenu()
                    })
                    MenuItem(stringResource(R.string.paste_markdown), onClick = {
                        pasteOpen = false; editor.pasteMarkdownAtCaret(); editor.dismissFlowContextMenu()
                    })
                    MenuItem(stringResource(R.string.paste_code), onClick = {
                        pasteOpen = false; editor.pasteAsCodeAtCaret(); editor.dismissFlowContextMenu()
                    })
                }
            }
            ActionIcon(XnotesIcons.trash, stringResource(R.string.delete), enabled = hasSelection, shortcut = Keys.DELETE) {
                editor.flowDeleteSelection(); editor.dismissFlowContextMenu()
            }
        }
    }
}

/**
 * The PDF text selection's bar: Copy, the four marks (made in the active ink colour), and behind
 * the overflow the apps that act on selected text ("Translate", "Search" and the like). Never takes
 * focus; a canvas touch hides it until the selection, or the view under it, settles again.
 */
@Composable
fun PdfSelectionMenu(editor: Editor) {
    val rect = editor.pdfTextMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val actions = remember { editor.pdfTextActions() }
    var overflowOpen by remember { mutableStateOf(false) }
    // A read-only note's text is copied or handed on, never marked.
    val marks = if (editor.readOnly) emptyList() else PDF_MARKS

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { ((1 + marks.size + if (actions.isEmpty()) 0 else 1) * 46 + 8).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    // When pushed below the selection, also clear the teardrop handles hanging there.
    val handleClearance = with(density) { (2 * com.xnotes.canvas.TextHandles.RADIUS_DP).dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(margin)
    val above = rect.top.toFloat() - barHeightPx - gap
    val below = rect.bottom.toFloat() + handleClearance + gap
    // A selection taller than the view leaves no room either side: keep the bar on screen.
    val maxY = (editor.state.viewportH - barHeightPx - margin).coerceAtLeast(margin)
    val yPx = (if (above > 0f) above else below).coerceIn(margin, maxY)
    val barAbove = above > 0f

    CompositionLocalProvider(LocalTipSide provides tipSideFor(barAbove)) {
        Row(
            modifier = Modifier
                .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
                .floatingBar()
                .padding(horizontal = BAR_END),
        ) {
            ActionIcon(XnotesIcons.copy, stringResource(R.string.copy), shortcut = Keys.COPY) { editor.copyPdfText() }
            for ((type, icon, label) in marks) {
                ActionIcon(icon, stringResource(label)) { editor.markPdfSelection(type) }
            }
            if (actions.isNotEmpty()) {
                Box {
                    ActionIcon(XnotesIcons.more, stringResource(R.string.more)) { overflowOpen = true }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                        properties = PopupProperties(focusable = false),
                    ) {
                        for ((label, app) in actions) {
                            MenuItem(label, onClick = {
                                overflowOpen = false
                                editor.processPdfText(app)
                            })
                        }
                    }
                }
            }
        }
    }
}

/**
 * A markup's tap menu: its colour and kind (each a small list), its note, Copy (the text it marks),
 * Delete, and Open link when the tap was on a link. Placed like the selection bar, by the markup.
 * On a read-only note it keeps only Copy and Open link.
 */
@Composable
fun MarkupMenu(editor: Editor) {
    val menu = editor.markupMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var colorsOpen by remember { mutableStateOf(false) }
    var kindsOpen by remember { mutableStateOf(false) }
    val reading = editor.readOnly
    val icons = (if (reading) 1 else 5) + if (menu.openLink != null) 1 else 0
    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (icons * 46 + 8).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val rect = menu.anchor
    val xPx = (((rect.left + rect.right) / 2.0).toFloat() - barWidthPx / 2f).coerceAtLeast(margin)
    val above = rect.top.toFloat() - barHeightPx - gap
    val maxY = (editor.state.viewportH - barHeightPx - margin).coerceAtLeast(margin)
    val yPx = (if (above > 0f) above else rect.bottom.toFloat() + gap).coerceIn(margin, maxY)
    val barAbove = above > 0f
    val m = menu.markup

    CompositionLocalProvider(LocalTipSide provides tipSideFor(barAbove)) {
        Row(
            modifier = Modifier
                .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
                .floatingBar()
                .padding(horizontal = BAR_END),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!reading) Box {
                val colourLabel = stringResource(R.string.markup_colour)
                Tooltip(colourLabel) {
                    IconButton(onClick = { colorsOpen = true }, modifier = Modifier.size(46.dp).semantics { contentDescription = colourLabel }) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(m.color.toComposeColor()))
                    }
                }
                DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }, properties = PopupProperties(focusable = false)) {
                    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        editor.toolbarColors.take(editor.toolbarColorCount).forEach { c ->
                            MarkupDot(c.toComposeColor(), active = c.r == m.color.r && c.g == m.color.g && c.b == m.color.b) {
                                colorsOpen = false
                                editor.recolorMarkup(c)
                            }
                        }
                    }
                }
            }
            if (!reading) Box {
                ActionIcon(markIcon(m.type), stringResource(R.string.markup_kind)) { kindsOpen = true }
                DropdownMenu(expanded = kindsOpen, onDismissRequest = { kindsOpen = false }, properties = PopupProperties(focusable = false)) {
                    for ((type, icon, label) in PDF_MARKS) {
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            selected = type == m.type,
                            onClick = {
                                kindsOpen = false
                                if (type != m.type) editor.retypeMarkup(type)
                            },
                        )
                    }
                }
            }
            if (!reading) ActionIcon(XnotesIcons.note, stringResource(R.string.markup_note)) { editor.editMarkupNote() }
            ActionIcon(XnotesIcons.copy, stringResource(R.string.copy)) { editor.copyMarkupText() }
            if (!reading) ActionIcon(XnotesIcons.trash, stringResource(R.string.delete)) { editor.deleteMarkup() }
            if (menu.openLink != null) ActionIcon(XnotesIcons.link, stringResource(R.string.open_link)) { editor.openMarkupLink() }
        }
    }
}

/**
 * A markup's note, shown by a first tap on it: placed as its menu would be, then moving with the
 * page, one size at any zoom. It takes no focus and leaves the page working around it; on itself it
 * takes touches, so a long note scrolls. It goes once scrolled out of view.
 */
@Composable
fun MarkupNotePeek(editor: Editor) {
    val peek = editor.notePeek ?: return
    val note = peek.markup.note ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val scroll = remember(peek) { ScrollState(0) }
    val maxHeight = with(density) { (editor.state.viewportH * NOTE_PEEK_MAX_HEIGHT).toDp() }.coerceAtMost(240.dp)
    Box(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                editor.notePeekTick
                editor.notePeekLaidOut(p.width, p.height)
                val at = editor.notePeekRect(p.width, p.height)
                layout(p.width, p.height) {
                    if (at != null) p.place(at.left.roundToInt(), at.top.roundToInt())
                }
            }
            .widthIn(max = 300.dp)
            .heightIn(max = maxHeight)
            .floatingBar()
            .verticalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(note, style = MaterialTheme.typography.bodyMedium, color = palette.text.toComposeColor())
    }
}

/** The most of the view's height a markup's note window takes before it scrolls. */
private const val NOTE_PEEK_MAX_HEIGHT = 0.4f

/** A markup's note to read or write: plain text, kept on Save, dropped when emptied or deleted. */
@Composable
fun MarkupNoteDialog(editor: Editor) {
    val target = editor.markupNote ?: return
    var text by remember(target) { mutableStateOf(target.markup.note.orEmpty()) }
    SheetDialog(
        title = stringResource(R.string.markup_note),
        onDismiss = { editor.dismissMarkupNote() },
        foot = {
            DialogButtons {
                if (target.markup.note != null) DialogButton(stringResource(R.string.delete), ButtonKind.DISCARD) { editor.saveMarkupNote("") }
                DialogButton(stringResource(R.string.cancel)) { editor.dismissMarkupNote() }
                DialogButton(stringResource(R.string.save), ButtonKind.CONFIRM) { editor.saveMarkupNote(text) }
            }
        },
    ) {
        DialogField(text, { text = it }, placeholder = stringResource(R.string.markup_note_hint), lines = 3)
    }
}

@Composable
private fun MarkupDot(color: Color, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .then(if (active) Modifier.border(2.dp, color, CircleShape) else Modifier)
            .padding(5.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

private fun markIcon(type: com.xnotes.core.model.MarkupType): ImageVector = PDF_MARKS.first { it.first == type }.second

/** The selection bar's marks, in the order the markup tool's popup lists them. */
private val PDF_MARKS = listOf(
    Triple(com.xnotes.core.model.MarkupType.HIGHLIGHT, XnotesIcons.highlight, R.string.markup_highlight),
    Triple(com.xnotes.core.model.MarkupType.UNDERLINE, XnotesIcons.underline, R.string.underline),
    Triple(com.xnotes.core.model.MarkupType.STRIKEOUT, XnotesIcons.strikethrough, R.string.strikethrough),
    Triple(com.xnotes.core.model.MarkupType.SQUIGGLY, XnotesIcons.squiggly, R.string.markup_squiggly),
)

private const val TABLE_BAR_ICONS = 4

/**
 * Where the table bar sits in viewport px: centred over the top of the table's
 * first slice and kept on screen (it overlaps the table rather than leave the
 * top of the view). Null when the table is not laid out.
 */
private fun tableBarRect(editor: Editor, table: com.xnotes.core.text.FlowTable, density: androidx.compose.ui.unit.Density): androidx.compose.ui.geometry.Rect? {
    editor.tableChromeTick
    val (pi, frag) = editor.tableFrags(table).firstOrNull() ?: return null
    val t = editor.pageRectToViewport(pi, frag.rect) ?: return null
    val w = with(density) { (TABLE_BAR_ICONS * 46 + 8).dp.toPx() }
    val h = with(density) { 48.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val x = (((t.left + t.right) / 2.0).toFloat() - w / 2f).coerceAtLeast(margin)
    val y = (t.top.toFloat() - h - gap).coerceAtLeast(margin)
    return androidx.compose.ui.geometry.Rect(x, y, x + w, y + h)
}

/**
 * The table's action bar: edit its structure, fit its columns (the magic wand),
 * restyle it, delete it. Opens the moment a long press holds the table itself
 * (a rule, padding, empty cell space), above the table whatever part was
 * pressed. Like the text bar it never takes focus; the next canvas touch
 * retires it.
 */
@Composable
fun FlowTableMenu(editor: Editor) {
    val table = editor.tableMenu ?: return
    if (editor.editingTable != null) return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val bar = tableBarRect(editor, table, density) ?: return
    // The bar sits over the table's top edge, so its tips open upward, off the table.
    CompositionLocalProvider(LocalTipSide provides TipSide.ABOVE) {
        Row(
            modifier = Modifier
                .offset(with(density) { bar.left.toDp() }, with(density) { bar.top.toDp() })
                .floatingBar()
                .padding(horizontal = BAR_END),
        ) {
            ActionIcon(XnotesIcons.tableEdit, stringResource(R.string.edit_table)) { editor.startTableEdit(table) }
            ActionIcon(XnotesIcons.magicWand, stringResource(R.string.fit_columns)) { editor.tableAutoFit(table) }
            ActionIcon(Icons.Outlined.TableChart, stringResource(R.string.table_style)) { editor.openTableStyle(table) }
            ActionIcon(Icons.Outlined.GridOff, stringResource(R.string.delete_table)) { editor.tableDelete(table) }
        }
    }
}
