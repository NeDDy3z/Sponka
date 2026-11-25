package com.xnotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.shapes.ConnectorPresets
import com.xnotes.core.shapes.ShapePresets
import com.xnotes.core.tools.PencilcaseItem
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolConversions
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** Floating bar of saved tools: tap arms, the arrow slides out save, reorder and remove.
 *  The handle drags it at once, the rest after a long press; near a side edge it docks upright. */
@Composable
fun BoxScope.Pencilcase(
    items: List<PencilcaseItem>,
    canSave: Boolean,
    onSave: () -> Unit,
    onPick: (PencilcaseItem) -> Unit,
    onRemove: (PencilcaseItem) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onRename: (index: Int, name: String?) -> Unit,
    popupHost: ToolPopupHost,
    onEdit: (index: Int, item: PencilcaseItem) -> Unit,
    canPick: (PencilcaseItem) -> Boolean = { true },
    widthMark: String = "number",
) {
    val palette = LocalPalette.current
    var offset by remember { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dock by remember { mutableStateOf(Dock.NONE) }
    var moving by remember { mutableStateOf(false) }
    var vertical by remember { mutableStateOf(false) }
    var trayOpen by remember { mutableStateOf(false) }
    var barGrab by remember { mutableStateOf(Offset.Zero) }
    var barFinger by remember { mutableStateOf(Offset.Zero) }
    var barAt by remember { mutableStateOf(Offset.Zero) }
    var handleBounds by remember { mutableStateOf(Rect.Zero) }
    var mode by remember { mutableStateOf(Mode.USE) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) }
    // Where it was left is kept on this device only, as fractions of the room it moves in.
    val placeStore = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("pencilcase", android.content.Context.MODE_PRIVATE)
    var placed by remember { mutableStateOf(false) }
    val current = when {
        (mode == Mode.REMOVE || mode == Mode.RENAME || mode == Mode.EDIT) && items.isEmpty() -> Mode.USE
        mode == Mode.REORDER && items.size < 2 -> Mode.USE
        else -> mode
    }
    LaunchedEffect(current) { mode = current }

    // Reorder drag, all in root coordinates.
    var grabbed by remember { mutableStateOf<Int?>(null) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val latestItems by rememberUpdatedState(items)
    val latestMove by rememberUpdatedState(onMove)

    fun shown(at: Offset = offset): Offset {
        val maxX = (area.width - size.width).coerceAtLeast(0).toFloat()
        val maxY = (area.height - size.height).coerceAtLeast(0).toFloat()
        val x = when (dock) {
            Dock.LEFT -> 0f
            Dock.RIGHT -> maxX
            Dock.NONE -> at.x.coerceIn(0f, maxX)
        }
        return Offset(x, at.y.coerceIn(0f, maxY))
    }

    fun startMove(at: Offset) {
        moving = true
        offset = shown()
        barGrab = at
        barFinger = offset + at
    }

    // Docking follows the finger, not the bar, so turning upright mid-drag cannot flip it back.
    fun moveBy(drag: Offset, snap: Float) {
        barFinger += drag
        dock = when {
            barFinger.x < snap -> Dock.LEFT
            barFinger.x > area.width - snap -> Dock.RIGHT
            else -> Dock.NONE
        }
        vertical = dock != Dock.NONE
        offset = shown(barFinger - barGrab)
    }

    val reach = with(androidx.compose.ui.platform.LocalDensity.current) { SNAP_REACH.toPx() }
    fun landing(): Offset = snapToEdges(
        shown(),
        0f..(area.width - size.width).coerceAtLeast(0).toFloat(),
        0f..(area.height - size.height).coerceAtLeast(0).toFloat(),
        reach,
    )

    fun settle() {
        moving = false
        offset = landing()
        placeStore.edit().putFloat("x", offset.x / area.width.coerceAtLeast(1)).putFloat("y", offset.y / area.height.coerceAtLeast(1))
            .putString("dock", dock.name).apply()
    }

    LaunchedEffect(area, size) {
        if (placed || area == IntSize.Zero || size == IntSize.Zero) return@LaunchedEffect
        placed = true
        dock = runCatching { Dock.valueOf(placeStore.getString("dock", null) ?: "NONE") }.getOrDefault(Dock.NONE)
        vertical = dock != Dock.NONE
        offset = Offset(placeStore.getFloat("x", 0f) * area.width, placeStore.getFloat("y", 0f) * area.height)
    }

    /** Where the dragged item lands in the list without it. */
    fun target(from: Int): Int = latestItems.indices.count { i ->
        val b = bounds[i] ?: return@count false
        i != from && (if (vertical) b.center.y < finger.y else b.center.x < finger.x)
    }

    val tipSide = when (dock) {
        Dock.LEFT -> TipSide.RIGHT
        Dock.RIGHT -> TipSide.LEFT
        Dock.NONE -> TipSide.BELOW
    }

    val caretAt = grabbed?.let { g -> target(g).let { if (it < g) it else it + 1 } }

    Box(
        Modifier.matchParentSize().padding(LocalToolbarCover.current).padding(12.dp)
            .onSizeChanged { area = it }
            .onGloballyPositioned { origin = it.positionInRoot() },
    ) {
        if (moving) landing().takeIf { it != shown() }?.let { SnapMark(it, size, Alignment.TopStart) }
        Surface(
            Modifier
                .align(Alignment.TopStart)
                .offset { shown().round() }
                // Unseen until its saved place is read, so it never shows at the top left first.
                .alpha(if (placed) 1f else 0f)
                .onSizeChanged { size = it }
                .pointerInput(current == Mode.REORDER) {
                    if (current == Mode.REORDER) return@pointerInput // a long press there picks up an item instead
                    val snap = 48.dp.toPx()
                    detectDragGesturesAfterLongPress(
                        onDragStart = { startMove(it) },
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                    ) { change, drag ->
                        change.consume()
                        moveBy(drag, snap)
                    }
                }
                // The handle's drag lives on the bar: the handle is rebuilt when the bar turns upright, which would end it.
                .onGloballyPositioned { barAt = it.positionInRoot() }
                .pointerInput(Unit) {
                    val snap = 48.dp.toPx()
                    var onHandle = false
                    detectDragGestures(
                        onDragStart = { at ->
                            onHandle = handleBounds.inflate(viewConfiguration.touchSlop).contains(barAt + at)
                            if (onHandle) startMove(at)
                        },
                        onDragEnd = { if (onHandle) settle() },
                        onDragCancel = { if (onHandle) settle() },
                    ) { change, drag ->
                        if (!onHandle) return@detectDragGestures
                        change.consume()
                        moveBy(drag, snap)
                    }
                },
            shape = MaterialTheme.shapes.large,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
            border = BorderStroke(1.dp, palette.border.toComposeColor()),
        ) {
            val handle: @Composable () -> Unit = {
                Icon(
                    XnotesIcons.grip,
                    contentDescription = stringResource(R.string.pencilcase_move),
                    tint = palette.textDim.toComposeColor(),
                    modifier = Modifier
                        .onGloballyPositioned { handleBounds = it.boundsInRoot() }
                        .padding(8.dp)
                        .size(18.dp)
                        .rotate(if (vertical) 90f else 0f),
                )
            }
            val buttons: @Composable () -> Unit = {
                items.forEachIndexed { i, item ->
                    if (caretAt == i) Caret(vertical)
                    val label = item.name ?: stringResource(item.tool.labelRes)
                    Box(
                        Modifier
                            .alpha(if (grabbed == i) 0.15f else 1f)
                            .onGloballyPositioned { bounds[i] = it.boundsInRoot() }
                            .then(
                                if (current != Mode.REORDER) Modifier
                                else Modifier.pointerInput(item, i) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            grabbed = i
                                            grab = at
                                            finger = (bounds[i]?.topLeft ?: Offset.Zero) + at
                                        },
                                        onDragEnd = {
                                            val to = target(i)
                                            grabbed = null
                                            if (to != i) latestMove(i, to)
                                        },
                                        onDragCancel = { grabbed = null },
                                    ) { change, drag ->
                                        change.consume()
                                        finger += drag
                                    }
                                },
                            ),
                    ) {
                        Tooltip(label, side = tipSide) {
                            val dimmed = current == Mode.USE && !canPick(item)
                            ItemButton(
                                item,
                                widthMark,
                                if (current == Mode.REMOVE) stringResource(R.string.pencilcase_remove, label) else label,
                                tint = when {
                                    dimmed || current == Mode.EDIT && item.tool !in EDITABLE -> palette.border
                                    item.tool == Tool.ERASER -> palette.text
                                    else -> item.shownColor
                                }.toComposeColor(),
                                enabled = when (current) {
                                    Mode.REORDER -> false
                                    Mode.USE -> canPick(item)
                                    Mode.EDIT -> item.tool in EDITABLE
                                    else -> true
                                },
                            ) {
                                when (current) {
                                    Mode.REMOVE -> onRemove(item)
                                    Mode.RENAME -> renaming = i
                                    Mode.EDIT -> editing = i
                                    else -> onPick(item)
                                }
                            }
                        }
                        if (current == Mode.REMOVE) {
                            Icon(
                                XnotesIcons.close,
                                contentDescription = null,
                                tint = palette.text.toComposeColor(),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(4.dp, 4.dp)
                                    .size(12.dp)
                                    .background(palette.surface.toComposeColor(), CircleShape)
                                    .border(1.dp, palette.border.toComposeColor(), CircleShape)
                                    .padding(2.dp),
                            )
                        }
                    }
                }
                if (caretAt == items.size) Caret(vertical)
                if (items.isEmpty()) {
                    CaseButton(
                        XnotesIcons.plus,
                        stringResource(R.string.pencilcase_save),
                        tint = (if (canSave) palette.textDim else palette.border).toComposeColor(),
                        enabled = canSave,
                        onClick = onSave,
                    )
                }
            }
            val toggle: @Composable () -> Unit = {
                if (current == Mode.USE) {
                    CaseButton(
                        XnotesIcons.chevronDown,
                        stringResource(R.string.more),
                        palette.textDim.toComposeColor(),
                        rotation = if (vertical) (if (trayOpen) 180f else 0f) else (if (trayOpen) 90f else -90f),
                    ) { trayOpen = !trayOpen }
                } else {
                    CaseButton(XnotesIcons.check, stringResource(R.string.done), palette.accent.toComposeColor()) { mode = Mode.USE }
                }
            }
            val tray: @Composable () -> Unit = {
                fun tint(on: Boolean) = (if (on) palette.textDim else palette.border).toComposeColor()
                CaseButton(XnotesIcons.plus, stringResource(R.string.pencilcase_save), tint(canSave), enabled = canSave) { trayOpen = false; onSave() }
                CaseButton(XnotesIcons.sort, stringResource(R.string.pencilcase_reorder), tint(items.size > 1), enabled = items.size > 1, rotation = 90f) {
                    trayOpen = false; mode = Mode.REORDER
                }
                CaseButton(XnotesIcons.sliders, stringResource(R.string.pencilcase_edit_items), tint(items.any { it.tool in EDITABLE }), enabled = items.any { it.tool in EDITABLE }) {
                    trayOpen = false; mode = Mode.EDIT
                }
                CaseButton(XnotesIcons.rename, stringResource(R.string.pencilcase_rename_items), tint(items.isNotEmpty()), enabled = items.isNotEmpty()) {
                    trayOpen = false; mode = Mode.RENAME
                }
                CaseButton(XnotesIcons.trash, stringResource(R.string.pencilcase_remove_items), tint(items.isNotEmpty()), enabled = items.isNotEmpty()) {
                    trayOpen = false; mode = Mode.REMOVE
                }
            }
            val trayShown = trayOpen && current == Mode.USE && items.isNotEmpty()
            val scroll = rememberScrollState()
            if (vertical) {
                Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    handle()
                    Rule(vertical)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) { buttons() }
                    if (items.isNotEmpty()) {
                        Rule(vertical)
                        toggle()
                        AnimatedVisibility(trayShown, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { tray() }
                        }
                    }
                }
            } else {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    handle()
                    Rule(vertical)
                    Row(Modifier.weight(1f, fill = false).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) { buttons() }
                    if (items.isNotEmpty()) {
                        Rule(vertical)
                        toggle()
                        AnimatedVisibility(trayShown, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                            Row(verticalAlignment = Alignment.CenterVertically) { tray() }
                        }
                    }
                }
            }
        }
        renaming?.let { r ->
            val item = items.getOrNull(r) ?: return@let
            // A blank name, or Reset, goes back to the tool's own name.
            NameDialog(
                title = stringResource(R.string.pencilcase_rename),
                initial = item.name.orEmpty(),
                confirmLabel = stringResource(R.string.rename),
                placeholder = stringResource(item.tool.labelRes),
                allowEmpty = true,
                onConfirm = { onRename(r, it.ifEmpty { null }); renaming = null },
                onDismiss = { renaming = null },
                onRemove = { onRename(r, null); renaming = null },
                removeLabel = stringResource(R.string.reset),
            )
        }
        editing?.let { e ->
            val item = items.getOrNull(e) ?: return@let
            val host = remember(e, popupHost) { ItemPopupHost(popupHost, item) { onEdit(e, it) } }
            val close = { editing = null }
            Box(Modifier.align(Alignment.TopStart).offset { (bounds[e]?.bottomLeft?.minus(origin) ?: Offset.Zero).round() }) {
                when (item.tool) {
                    Tool.TAPE -> TapeConfigPopup(host, close)
                    Tool.ERASER -> EraserConfigPopup(host, close)
                    else -> ToolConfigPopup(host, item.tool, onDismiss = close)
                }
            }
        }
        grabbed?.let { g ->
            val item = items.getOrNull(g) ?: return@let
            Box(Modifier.align(Alignment.TopStart).offset { (finger - grab - origin).round() }) {
                ToolbarDragGhost(pencilcaseIcon(item), item.name ?: stringResource(item.tool.labelRes))
            }
        }
    }
}

private enum class Dock { NONE, LEFT, RIGHT }

private enum class Mode { USE, REMOVE, REORDER, RENAME, EDIT }

/** Saved tools whose setup the usual tool popup can edit in place. */
private val EDITABLE: Set<Tool> = (Tool.PENS + listOf(Tool.HIGHLIGHTER, Tool.ERASER, Tool.TAPE)).toSet()

/** The editor's popup host, with the tool's setup read from and written to one saved item instead. */
private class ItemPopupHost(base: ToolPopupHost, private var item: PencilcaseItem, private val save: (PencilcaseItem) -> Unit) : ToolPopupHost by base {
    override fun toolConfig(tool: Tool): ToolConfig = item.config.copy(colorOverride = item.shownColor)

    // "Default" keeps the saved colour; one saved with its own tool colour keeps it there, so arming it leaves the ink alone.
    override fun updateToolConfig(tool: Tool, config: ToolConfig) {
        val color = config.colorOverride ?: item.shownColor
        item = if (item.config.colorOverride != null) item.copy(config = config.copy(colorOverride = color))
        else item.copy(config = config.copy(colorOverride = null), color = color)
        save(item)
    }
}

@Composable
private fun Rule(vertical: Boolean) {
    Box(
        Modifier
            .padding(4.dp)
            .then(if (vertical) Modifier.height(1.dp).width(20.dp) else Modifier.width(1.dp).height(20.dp))
            .background(LocalPalette.current.border.toComposeColor()),
    )
}

@Composable
private fun Caret(vertical: Boolean) {
    Box(
        Modifier
            .then(if (vertical) Modifier.height(2.dp).width(28.dp) else Modifier.width(2.dp).height(28.dp))
            .background(LocalPalette.current.accent.toComposeColor()),
    )
}

@Composable
private fun CaseButton(
    icon: ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(enabled = enabled, onClick = onClick).padding(8.dp).size(18.dp).rotate(rotation),
    )
}

/** A saved tool: its icon in its colour, a pen's width as a dot or a number per [widthMark], and a light backdrop when the colour blends into the bar. */
@Composable
private fun ItemButton(item: PencilcaseItem, widthMark: String, description: String, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val bar = MenuDefaults.containerColor.luminance()
    val lum = tint.luminance()
    val blends = (maxOf(lum, bar) + 0.05f) / (minOf(lum, bar) + 0.05f) < 1.6f
    Box(Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(enabled = enabled, onClick = onClick).padding(8.dp)) {
        Icon(
            pencilcaseIcon(item),
            contentDescription = description,
            tint = tint,
            modifier = Modifier
                .then(if (blends) Modifier.background(palette.text.toComposeColor().copy(alpha = 0.3f), CircleShape) else Modifier)
                .size(18.dp),
        )
        if (item.tool.isStroke && widthMark == "dot") {
            val range = ToolConversions.widthRange(item.tool)
            val f = ((item.config.baseWidth - range.start) / (range.endInclusive - range.start)).coerceIn(0.0, 1.0).toFloat()
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(4.dp, 4.dp)
                    .size(3.dp + 6.dp * f)
                    .then(if (blends) Modifier.border(1.dp, palette.textDim.toComposeColor(), CircleShape) else Modifier)
                    .background(tint, CircleShape),
            )
        }
        if (item.tool.isStroke && widthMark == "number") {
            // The thickness as the tool menu's slider shows it.
            val width = ToolConversions.snapWidth(item.tool, item.config.baseWidth)
            androidx.compose.material3.Text(
                "%.1f".format(width).trimEnd('0').trimEnd('.', ','),
                color = palette.text.toComposeColor(),
                fontSize = 9.sp,
                lineHeight = 9.sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(6.dp, 6.dp)
                    .background(MenuDefaults.containerColor, CircleShape)
                    .padding(horizontal = 2.dp),
            )
        }
    }
}

/** The toolbar's pencilcase button: its menu shows or hides the bar, sets how a pen's thickness is marked and clears every saved tool. */
@Composable
fun PencilcaseButton(editor: Editor) {
    var open by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(Icons.Outlined.DesignServices, stringResource(R.string.toolbar_pencilcase), active = editor.showPencilcase) { open = true }
        if (open) {
            ToolMenu(stringResource(R.string.toolbar_pencilcase), { open = false }) {
                MenuCard(buildList<MenuGroup> {
                    add { MenuSwitch(stringResource(R.string.pencilcase_show), editor.showPencilcase) { editor.setShowPencilcase(it) } }
                    add {
                        MenuRow(stringResource(R.string.pencilcase_width_mark)) {
                            val marks = listOf("none", "dot", "number")
                            ChoiceDropdown(
                                listOf(stringResource(R.string.none), stringResource(R.string.pencilcase_width_dot), stringResource(R.string.pencilcase_width_number)),
                                marks.indexOf(editor.pencilcaseWidthMark).coerceAtLeast(0),
                            ) { editor.setPencilcaseWidthMark(marks[it]) }
                        }
                    }
                })
                if (editor.pencilcase.isNotEmpty()) {
                    MenuAction(stringResource(R.string.pencilcase_clear), XnotesIcons.trash, Modifier.fillMaxWidth()) { open = false; clearing = true }
                }
            }
        }
        if (clearing) {
            SheetDialog(
                title = stringResource(R.string.pencilcase_clear_title),
                onDismiss = { clearing = false },
                text = stringResource(R.string.pencilcase_clear_text),
                foot = {
                    ConfirmButtons(stringResource(R.string.pencilcase_clear), { clearing = false }, kind = ButtonKind.DESTROY) {
                        clearing = false
                        editor.clearPencilcase()
                    }
                },
            )
        }
    }
}

/** A box showing the chosen one of [options]; a tap lists them all. */
@Composable
private fun ChoiceDropdown(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .width(120.dp)
                .height(36.dp)
                .clip(shape)
                .background(palette.bg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), shape)
                .clickable(role = androidx.compose.ui.semantics.Role.DropdownList) { open = true }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Text(
                options[selected],
                color = palette.text.toComposeColor(),
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, label ->
                ChoiceMenuItem(selected = i == selected, onClick = { open = false; onSelect(i) }) { fg -> androidx.compose.material3.Text(label, color = fg) }
            }
        }
    }
}

@Composable
private fun pencilcaseIcon(item: PencilcaseItem): ImageVector = when (val tool = item.tool) {
    Tool.ERASER -> XnotesIcons.eraser
    Tool.TAPE -> XnotesIcons.tape
    Tool.TABLE -> XnotesIcons.table
    Tool.SHAPE -> ShapePresets.byKey(item.shape.preset)?.let(ShapeIcons::of)
        ?: ConnectorPresets.byKey(item.shape.preset)?.let(ShapeIcons::connector)
        ?: XnotesIcons.shape
    else -> ImageVector.vectorResource(
        when (tool) {
            Tool.DASHED -> R.drawable.ic_stroke_dashed
            Tool.CALLIGRAPHY -> R.drawable.ic_stroke_calligraphy
            Tool.SPEED -> R.drawable.ic_stroke_speed
            Tool.TAPER -> R.drawable.ic_stroke_taper
            Tool.HIGHLIGHTER -> R.drawable.ic_stroke_highlighter
            else -> R.drawable.ic_stroke_regular
        },
    )
}
