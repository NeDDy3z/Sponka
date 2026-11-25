package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.SelectionAction
import com.xnotes.core.tools.SelectionBarLayout
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * The toolbar customizer, rendered inline inside Preferences. Each section is a bordered card whose
 * top strip (the empty space beside the delete X) is the grab area for dragging the whole section;
 * the cards sit side by side in a wrapping [FlowRow], and within a card the tool chips flow like the
 * real bar. Chips tap to show/hide and long-press to drag (within or across cards). The drag state
 * lives in [ToolbarDrag], whose ghost the host draws above its scroll; this body only paints, captures
 * bounds, and forwards gesture events up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolbarCustomizerBody(
    layout: ToolbarLayout,
    dragItem: ToolbarItem?,
    dropTarget: Pair<Int, Int>?,
    dragSection: Int?,
    sectionDropTarget: Int?,
    chipBounds: MutableMap<ToolbarItem, Rect>,
    sectionBounds: MutableMap<Int, Rect>,
    sectionGrabBounds: MutableMap<Int, Rect>,
    onToggle: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
    onDragStart: (ToolbarItem, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onSectionDragStart: (Int, Offset) -> Unit,
    onSectionDrag: (Offset) -> Unit,
    onSectionDrop: () -> Unit,
    onSectionCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    val dragging = dragItem != null
    val draggingSection = dragSection != null
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        layout.sections.forEachIndexed { sec, section ->
            if (draggingSection && sectionDropTarget == sec) SectionCaret()
            SectionCard(
                canDelete = layout.sections.size > 1,
                targeted = dragging && dropTarget?.first == sec,
                dimmed = dragSection == sec,
                onDelete = { onDelete(sec) },
                onBounds = { sectionBounds[sec] = it },
                onGrabBounds = { sectionGrabBounds[sec] = it },
                onGrabDragStart = { onSectionDragStart(sec, it) },
                onGrabDrag = onSectionDrag,
                onGrabDrop = onSectionDrop,
                onGrabCancel = onSectionCancel,
            ) {
                if (section.entries.isEmpty()) {
                    Text(
                        stringResource(R.string.drop_here),
                        color = (if (dragging && dropTarget == sec to 0) palette.accent else palette.textDim).toComposeColor(),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        section.entries.forEachIndexed { idx, entry ->
                            if (dragging && dropTarget == sec to idx) InsertionCaret()
                            ChipCell(
                                key = entry.item,
                                icon = itemIcon(entry.item),
                                label = stringResource(entry.item.labelRes),
                                visible = entry.visible,
                                dragging = dragItem == entry.item,
                                onTap = { onToggle(sec, idx) },
                                onDragStart = { local -> onDragStart(entry.item, local) },
                                onDrag = onDrag,
                                onDrop = onDrop,
                                onCancel = onCancel,
                                onBounds = { chipBounds[entry.item] = it },
                            )
                        }
                        if (dragging && dropTarget == sec to section.entries.size) InsertionCaret()
                    }
                }
            }
        }
        if (draggingSection && sectionDropTarget == layout.sections.size) SectionCaret()
        AddSectionChip(onClick = onAdd)
    }
}

/**
 * Resolve the drop slot (sectionIndex, entryIndex) for a finger position in root coordinates. The
 * section is the card the finger is inside, else the nearest card. Within it, the first chip the
 * finger sits "before" (an earlier row, or left of its centre on the same row) wins; an empty card
 * takes slot 0. Returns null only when no card has been measured yet.
 */
internal fun toolbarDropTarget(
    layout: ToolbarLayout,
    finger: Offset,
    chipBounds: Map<ToolbarItem, Rect>,
    sectionBounds: Map<Int, Rect>,
): Pair<Int, Int>? = chipDropTarget(layout.sections.map { s -> s.entries.map { it.item } }, finger, chipBounds, sectionBounds)

internal fun <K> chipDropTarget(
    sections: List<List<K>>,
    finger: Offset,
    chipBounds: Map<K, Rect>,
    sectionBounds: Map<Int, Rect>,
): Pair<Int, Int>? {
    var sec = sections.indices.firstOrNull { sectionBounds[it]?.contains(finger) == true } ?: -1
    if (sec < 0) {
        var best = Float.MAX_VALUE
        for (i in sections.indices) {
            val r = sectionBounds[i] ?: continue
            val dx = maxOf(r.left - finger.x, 0f, finger.x - r.right)
            val dy = maxOf(r.top - finger.y, 0f, finger.y - r.bottom)
            val d = dx * dx + dy * dy
            if (d < best) { best = d; sec = i }
        }
    }
    if (sec < 0) return null
    val entries = sections[sec]
    if (entries.isEmpty()) return sec to 0
    fun before(r: Rect): Boolean = finger.y < r.top || (finger.y <= r.bottom && finger.x < r.center.x)
    for (idx in entries.indices) {
        val r = chipBounds[entries[idx]] ?: continue
        if (before(r)) return sec to idx
    }
    return sec to entries.size
}

/** Resolve the section insertion index (0..size) for a finger position in root coordinates. */
internal fun toolbarSectionDropTarget(
    layout: ToolbarLayout,
    finger: Offset,
    sectionBounds: Map<Int, Rect>,
): Int {
    fun before(r: Rect): Boolean = finger.y < r.top || (finger.y <= r.bottom && finger.x < r.center.x)
    for (sec in layout.sections.indices) {
        val r = sectionBounds[sec] ?: continue
        if (before(r)) return sec
    }
    return layout.sections.size
}

/**
 * A bordered section box: a header strip (grab area for the section drag) with the delete X at its
 * right, then the tool chips. The header is sized to the chips' width by [SectionCardScaffold] so
 * the strip beside the X fills the whole top edge without forcing the card to the full pane width.
 */
@Composable
private fun SectionCard(
    canDelete: Boolean,
    targeted: Boolean,
    dimmed: Boolean,
    onDelete: () -> Unit,
    onBounds: (Rect) -> Unit,
    onGrabBounds: (Rect) -> Unit,
    onGrabDragStart: (Offset) -> Unit,
    onGrabDrag: (Offset) -> Unit,
    onGrabDrop: () -> Unit,
    onGrabCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    SectionCardScaffold(
        modifier = Modifier
            .alpha(if (dimmed) 0.35f else 1f)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, (if (targeted) palette.accent else palette.border).toComposeColor(), MaterialTheme.shapes.small)
            .padding(start = 6.dp, top = 2.dp, end = 2.dp, bottom = 6.dp),
        header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionDragStrip(
                    // Explicit height: the card is measured with an unbounded max height, so
                    // fillMaxHeight would collapse the grab strip to 0px and nothing could be pressed.
                    modifier = Modifier.weight(1f).height(34.dp),
                    onBounds = onGrabBounds,
                    onDragStart = onGrabDragStart,
                    onDrag = onGrabDrag,
                    onDrop = onGrabDrop,
                    onCancel = onGrabCancel,
                )
                Tooltip(stringResource(R.string.delete_section)) {
                    IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(26.dp)) {
                        Icon(
                            XnotesIcons.close, stringResource(R.string.delete_section),
                            tint = (if (canDelete) palette.textDim else palette.border).toComposeColor(),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        },
        body = content,
    )
}

/** Stacks [header] above [body] and sizes the header to the body's measured width. */
@Composable
private fun SectionCardScaffold(
    modifier: Modifier,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    Layout(contents = listOf(body, header), modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bodyPlaceable = measurables[0].first().measure(loose)
        val width = bodyPlaceable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val headerPlaceable = measurables[1].first().measure(loose.copy(minWidth = width, maxWidth = width))
        layout(width, headerPlaceable.height + bodyPlaceable.height) {
            headerPlaceable.place(0, 0)
            bodyPlaceable.place(0, headerPlaceable.height)
        }
    }
}

/** The transparent top strip beside the X; long-press and drag it to reorder the whole section. */
@Composable
private fun SectionDragStrip(
    modifier: Modifier,
    onBounds: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    val dStart by rememberUpdatedState(onDragStart)
    val dMove by rememberUpdatedState(onDrag)
    val dEnd by rememberUpdatedState(onDrop)
    val dCancel by rememberUpdatedState(onCancel)
    Box(
        modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dStart(it) },
                    onDrag = { _, delta -> dMove(delta) },
                    onDragEnd = { dEnd() },
                    onDragCancel = { dCancel() },
                )
            },
    )
}

@Composable
private fun ChipCell(
    key: Any,
    icon: ImageVector,
    label: String,
    visible: Boolean,
    dragging: Boolean,
    onTap: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    // The pointerInput blocks key on the stable item, so they capture the first-composition
    // lambdas; rememberUpdatedState keeps the gestures calling the latest callbacks instead.
    val tap by rememberUpdatedState(onTap)
    val dStart by rememberUpdatedState(onDragStart)
    val dMove by rememberUpdatedState(onDrag)
    val dEnd by rememberUpdatedState(onDrop)
    val dCancel by rememberUpdatedState(onCancel)
    ChipFace(
        icon = icon,
        label = label,
        visible = visible,
        modifier = Modifier
            .alpha(if (dragging) 0.15f else 1f)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .pointerInput(key) { detectTapGestures(onTap = { tap() }) }
            .pointerInput(key) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dStart(it) },
                    onDrag = { _, delta -> dMove(delta) },
                    onDragEnd = { dEnd() },
                    onDragCancel = { dCancel() },
                )
            },
    )
}

/** The visual of a tool chip (icon + label, dimmed when hidden); shared by the bar editor and the
 *  section drag ghost so a lifted section looks exactly like its card. */
@Composable
private fun ChipFace(icon: ImageVector, label: String, visible: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .alpha(if (!visible) 0.4f else 1f)
            .clip(MaterialTheme.shapes.small)
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = palette.text.toComposeColor(), fontSize = 12.sp)
    }
}

/** The floating chip that tracks the finger during a chip drag (drawn at the pane root). */
@Composable
fun ToolbarDragGhost(icon: ImageVector, label: String) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .alpha(0.9f)
            .clip(MaterialTheme.shapes.small)
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = palette.accent.toComposeColor(), fontSize = 12.sp)
    }
}

/**
 * A faithful floating copy of a whole section during a section drag, pinned to the dragged card's
 * width ([widthPx], root pixels) so its chips wrap exactly like the card and the shape never changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionCardGhost(section: ToolbarSection, widthPx: Float) {
    val palette = LocalPalette.current
    val width = with(LocalDensity.current) { widthPx.toDp() }
    Column(
        Modifier
            .width(width)
            .alpha(0.95f)
            .clip(MaterialTheme.shapes.small)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small)
            .padding(start = 6.dp, top = 2.dp, end = 2.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.close, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
            }
        }
        if (section.entries.isEmpty()) {
            Text(
                stringResource(R.string.drop_here), color = palette.textDim.toComposeColor(), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                section.entries.forEach { e -> ChipFace(itemIcon(e.item), stringResource(e.item.labelRes), e.visible) }
            }
        }
    }
}

@Composable
private fun AddSectionChip(onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XnotesIcons.plus, stringResource(R.string.add_section), tint = palette.accent.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.add_section), color = palette.accent.toComposeColor(), fontSize = 12.sp)
    }
}

@Composable
private fun InsertionCaret() {
    Box(Modifier.width(2.dp).height(28.dp).background(LocalPalette.current.accent.toComposeColor()))
}

/** Taller caret marking where a dragged section will land between cards. */
@Composable
private fun SectionCaret() {
    Box(Modifier.width(3.dp).height(56.dp).clip(RoundedCornerShape(2.dp)).background(LocalPalette.current.accent.toComposeColor()))
}

@Composable
private fun itemIcon(item: ToolbarItem): ImageVector = when (item) {
    ToolbarItem.HOME -> XnotesIcons.home
    ToolbarItem.TITLE -> XnotesIcons.edit
    ToolbarItem.SIDEBAR -> XnotesIcons.sidebar
    ToolbarItem.PEN -> ImageVector.vectorResource(R.drawable.ic_stroke_regular)
    ToolbarItem.HIGHLIGHTER -> ImageVector.vectorResource(R.drawable.ic_stroke_highlighter)
    ToolbarItem.ERASER -> XnotesIcons.eraser
    ToolbarItem.PAN -> XnotesIcons.pan
    ToolbarItem.READER -> Icons.Outlined.AutoStories
    ToolbarItem.SELECT -> XnotesIcons.select
    ToolbarItem.INSERT_SPACE -> XnotesIcons.insertSpace
    ToolbarItem.SCREENSHOT -> XnotesIcons.scissors
    ToolbarItem.WAND -> XnotesIcons.magicWand
    ToolbarItem.TAPE -> XnotesIcons.tape
    ToolbarItem.SHAPE -> XnotesIcons.shape
    ToolbarItem.RULER -> XnotesIcons.ruler
    ToolbarItem.TEXT -> XnotesIcons.text
    ToolbarItem.TEXT_BOX -> XnotesIcons.textBox
    ToolbarItem.MARKUP -> XnotesIcons.markup
    ToolbarItem.IMAGE -> XnotesIcons.paperclip
    ToolbarItem.TABLE -> XnotesIcons.table
    ToolbarItem.UNDO -> XnotesIcons.undo
    ToolbarItem.REDO -> XnotesIcons.redo
    ToolbarItem.SEARCH -> XnotesIcons.search
    ToolbarItem.ADD_PAGE -> XnotesIcons.addPage
    ToolbarItem.MARGINS -> XnotesIcons.margins
    ToolbarItem.VIEW -> XnotesIcons.view
    ToolbarItem.COLORS -> XnotesIcons.more
    ToolbarItem.WIDTH -> XnotesIcons.filter
    ToolbarItem.PENCILCASE -> Icons.Outlined.DesignServices
}

/**
 * Drag state of the toolbar customizer, shared by every screen that shows it. The host keeps one with
 * `remember`, draws [Ghost] at its root (above the scroll, so it is never clipped) and runs the edge
 * auto-scroll with [edgeScrollDelta] while [active].
 */
class ToolbarDrag(private val editor: Editor) {
    /** Which bar is being arranged; the two are separate layouts. */
    var canvasTab by mutableStateOf(false)
        private set
    var item by mutableStateOf<ToolbarItem?>(null)
        private set
    var section by mutableStateOf<Int?>(null)
        private set
    var finger by mutableStateOf(Offset.Zero)
        private set
    private var grab by mutableStateOf(Offset.Zero)
    private var dropTarget by mutableStateOf<Pair<Int, Int>?>(null)
    private var sectionDropTarget by mutableStateOf<Int?>(null)
    // Plain maps, read only at drag time.
    private val chipBounds = mutableMapOf<ToolbarItem, Rect>()
    private val sectionBounds = mutableMapOf<Int, Rect>()
    private val sectionGrabBounds = mutableMapOf<Int, Rect>()

    /** The shape and connector lists under the bar, reordered by key. */
    internal val shapes = KeyReorder()

    /** The selection bar's action being dragged, between the bar and its 3-dot menu. */
    private var action by mutableStateOf<SelectionAction?>(null)
    private var actionTarget by mutableStateOf<Pair<Int, Int>?>(null)
    private val actionBounds = mutableMapOf<SelectionAction, Rect>()
    private val actionListBounds = mutableMapOf<Int, Rect>()

    val active: Boolean get() = item != null || section != null || action != null || shapes.dragging != null
    /** Where the finger is in whichever drag is running. */
    val pointer: Offset get() = if (shapes.dragging != null) shapes.finger else finger
    val layout: ToolbarLayout get() = if (canvasTab) editor.canvasToolbarLayout else editor.toolbarLayout

    fun apply(next: ToolbarLayout) {
        if (canvasTab) editor.applyCanvasToolbarLayout(next) else editor.applyToolbarLayout(next)
    }

    fun reset() = apply(if (canvasTab) ToolbarLayout.CANVAS_DEFAULT else ToolbarLayout.DEFAULT)

    fun retarget() {
        if (shapes.dragging != null) shapes.retarget()
        else if (item != null) dropTarget = toolbarDropTarget(layout, finger, chipBounds, sectionBounds)
        else if (section != null) sectionDropTarget = toolbarSectionDropTarget(layout, finger, sectionBounds)
        else if (action != null) actionTarget = chipDropTarget(editor.selectionBarLayout.sections.map { s -> s.map { it.action } }, finger, actionBounds, actionListBounds)
    }

    fun showTab(canvas: Boolean) {
        if (canvasTab == canvas) return
        canvasTab = canvas
        // The bounds maps are keyed by item and the two bars share most items, so stale entries
        // from the other tab would aim a drop at a chip that is no longer there.
        chipBounds.clear()
        sectionBounds.clear()
        sectionGrabBounds.clear()
        item = null
        section = null
        dropTarget = null
        sectionDropTarget = null
    }

    @Composable
    fun Body() {
        ToolbarCustomizerBody(
            layout = layout,
            dragItem = item,
            dropTarget = dropTarget,
            dragSection = section,
            sectionDropTarget = sectionDropTarget,
            chipBounds = chipBounds,
            sectionBounds = sectionBounds,
            sectionGrabBounds = sectionGrabBounds,
            onToggle = { s, i -> apply(layout.toggleVisible(s, i)) },
            onDelete = { s -> apply(layout.removeSection(s)) },
            onAdd = { apply(layout.addSection()) },
            onDragStart = { dragged, local ->
                item = dragged
                grab = local
                finger = (chipBounds[dragged]?.topLeft ?: Offset.Zero) + local
                retarget()
            },
            onDrag = { delta -> finger += delta; retarget() },
            onDrop = {
                val target = dropTarget
                val moving = item
                if (target != null && moving != null) {
                    val cur = layout
                    val fSec = cur.sections.indexOfFirst { s -> s.entries.any { it.item == moving } }
                    val fIdx = if (fSec >= 0) cur.sections[fSec].entries.indexOfFirst { it.item == moving } else -1
                    if (fSec >= 0 && fIdx >= 0) apply(cur.moveItem(fSec, fIdx, target.first, target.second))
                }
                item = null
                dropTarget = null
            },
            onCancel = {
                item = null
                dropTarget = null
            },
            onSectionDragStart = { sec, local ->
                section = sec
                val grabAt = sectionGrabBounds[sec]?.topLeft ?: sectionBounds[sec]?.topLeft ?: Offset.Zero
                val card = sectionBounds[sec]?.topLeft ?: Offset.Zero
                finger = grabAt + local
                grab = finger - card
                retarget()
            },
            onSectionDrag = { delta -> finger += delta; retarget() },
            onSectionDrop = {
                val from = section
                val insertAt = sectionDropTarget
                if (from != null && insertAt != null) {
                    val cur = layout
                    val to = (if (insertAt > from) insertAt - 1 else insertAt).coerceIn(0, cur.sections.lastIndex)
                    if (to != from) apply(cur.moveSection(from, to))
                }
                section = null
                sectionDropTarget = null
            },
            onSectionCancel = {
                section = null
                sectionDropTarget = null
            },
        )
    }

    /** The selection bar and its 3-dot menu as two cards; chips tap to hide and long-press to drag between them. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun SelectionBody() {
        val palette = LocalPalette.current
        val layout = editor.selectionBarLayout
        val dragging = action != null
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((sec, title) in listOf(SelectionBarLayout.BAR to R.string.selection_bar_main, SelectionBarLayout.MORE to R.string.selection_bar_more)) {
                val entries = layout.sections[sec]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { actionListBounds[sec] = it.boundsInRoot() }
                        .clip(MaterialTheme.shapes.small)
                        .border(1.dp, (if (dragging && actionTarget?.first == sec) palette.accent else palette.border).toComposeColor(), MaterialTheme.shapes.small)
                        .padding(8.dp),
                ) {
                    Text(stringResource(title), color = palette.textDim.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                    if (entries.isEmpty()) Text(stringResource(R.string.drop_here), color = palette.textDim.toComposeColor(), fontSize = 11.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        entries.forEachIndexed { idx, e ->
                            if (dragging && actionTarget == sec to idx) InsertionCaret()
                            ChipCell(
                                key = e.action,
                                icon = e.action.icon,
                                label = stringResource(e.action.labelRes),
                                visible = e.visible,
                                dragging = action == e.action,
                                onTap = { editor.applySelectionBarLayout(editor.selectionBarLayout.toggleVisible(sec, idx)) },
                                onDragStart = { local ->
                                    action = e.action
                                    grab = local
                                    finger = (actionBounds[e.action]?.topLeft ?: Offset.Zero) + local
                                    retarget()
                                },
                                onDrag = { delta -> finger += delta; retarget() },
                                onDrop = {
                                    val target = actionTarget
                                    val moving = action
                                    val cur = editor.selectionBarLayout
                                    val fSec = cur.sections.indexOfFirst { s -> s.any { it.action == moving } }
                                    if (target != null && fSec >= 0) {
                                        val fIdx = cur.sections[fSec].indexOfFirst { it.action == moving }
                                        editor.applySelectionBarLayout(cur.moveItem(fSec, fIdx, target.first, target.second))
                                    }
                                    action = null
                                    actionTarget = null
                                },
                                onCancel = { action = null; actionTarget = null },
                                onBounds = { actionBounds[e.action] = it },
                            )
                        }
                        if (dragging && actionTarget == sec to entries.size) InsertionCaret()
                    }
                }
            }
        }
    }

    /** The dragged chip or section's floating copy, placed relative to the host root at [rootTopLeft]. */
    @Composable
    fun Ghost(rootTopLeft: Offset) {
        val x = (finger.x - grab.x - rootTopLeft.x).toInt()
        val y = (finger.y - grab.y - rootTopLeft.y).toInt()
        val chip = item
        val sec = section
        if (shapes.dragging != null) {
            val f = shapes.finger - shapes.grab - rootTopLeft
            Box(Modifier.offset { IntOffset(f.x.toInt(), f.y.toInt()) }) { shapes.ghost() }
        } else if (action != null) {
            val a = action ?: return
            Box(Modifier.offset { IntOffset(x, y) }) { ToolbarDragGhost(a.icon, stringResource(a.labelRes)) }
        } else if (chip != null) {
            Box(Modifier.offset { IntOffset(x, y) }) { ToolbarDragGhost(itemIcon(chip), stringResource(chip.labelRes)) }
        } else if (sec != null) {
            val card = layout.sections.getOrNull(sec)
            val width = sectionBounds[sec]?.width
            if (card != null && width != null) Box(Modifier.offset { IntOffset(x, y) }) { SectionCardGhost(card, width) }
        }
    }
}

/** How far to scroll this frame while a finger at [y] drags near the top or bottom edge of [viewport]. */
internal fun edgeScrollDelta(y: Float, viewport: Rect): Float {
    val band = 90f
    return when {
        y < viewport.top + band -> -((viewport.top + band - y) / band) * 14f
        y > viewport.bottom - band -> ((y - (viewport.bottom - band)) / band) * 14f
        else -> 0f
    }
}
