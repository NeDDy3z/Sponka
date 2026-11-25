package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xnotes.R
import com.xnotes.core.util.DocumentKind
import com.xnotes.settings.OpenTab
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

private val STRIP_HEIGHT = 38.dp
private val TAB_MIN = 120.dp
private val TAB_MAX = 220.dp

/** The tabs as the strip lays them out: a split tab's two halves are one slot, any other tab a slot of its own. */
private fun slots(tabs: List<OpenTab>): List<IntRange> = buildList {
    var i = 0
    while (i < tabs.size) {
        val size = if (tabs[i].pair != null && i + 1 < tabs.size) 2 else 1
        add(i until i + size)
        i += size
    }
}

/** Where slot [from], dropped at slot [to] among the others, lands in the tab list without it. */
private fun landing(slots: List<IntRange>, from: Int, to: Int): Int =
    slots.filterIndexed { i, _ -> i != from }.take(to).sumOf { it.count() }

/**
 * The open tabs over the editor, drawn like a browser's: the [shown] tab is cut from the surface just
 * below it (the docked toolbar, or the desk when the bar floats), the rest sit dim on the strip. A
 * split tab's halves are drawn as one tab; [onScreen] are the documents the panes show, matched by [same].
 * A long press picks a tab up to move it along the strip.
 */
@Composable
internal fun TabStrip(
    tabs: List<OpenTab>,
    shown: String?,
    onScreen: List<String>,
    same: (String, String) -> Boolean,
    onSelect: (OpenTab) -> Unit,
    onClose: (OpenTab) -> Unit,
    onMove: (OpenTab, Int) -> Unit,
    onMoveFirst: (OpenTab) -> Unit,
    onSplit: (OpenTab) -> Unit,
    onSwap: (OpenTab) -> Unit,
    onUnsplit: (OpenTab) -> Unit,
    onCloseSplit: (() -> Unit)?,
    onColor: (OpenTab, com.xnotes.core.model.Rgba?) -> Unit,
    onCloseOthers: () -> Unit,
    onOpenNote: () -> Unit,
) {
    val palette = LocalPalette.current
    // The strip is the toolbar's colour; the shown tab stands out as a selection does in a menu.
    val strip = palette.panel.toComposeColor()
    val below = palette.selectionBackground.toComposeColor()
    val slots = slots(tabs)
    val active = if (shown == null) -1 else tabs.indexOfFirst { same(it.uri, shown) }
    val visible = tabs.indices.filter { i -> onScreen.any { same(tabs[i].uri, it) } }.toSet()
    val lead = slots.indexOfFirst { active in it }
    val order = remember { TabDrag(vertical = false) }
    Row(
        Modifier.fillMaxWidth().height(STRIP_HEIGHT).background(strip),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val widths = slots.sumOf { if (it.count() == 2) SPLIT_WIDTH.toDouble() else 1.0 }.coerceAtLeast(1.0)
            val width = (maxWidth / widths.toFloat()).coerceIn(TAB_MIN, TAB_MAX)
            val scroll = rememberScrollState()
            val widthPx = with(LocalDensity.current) { width.roundToPx() }
            LaunchedEffect(lead, slots.size) { if (lead >= 0) scroll.animateScrollTo((lead * widthPx - widthPx).coerceAtLeast(0)) }
            // shortcut: the strip does not scroll while a tab is carried to its edge; add it if long strips need it.
            // As wide as the strip, so a tab carried past the last one is not cut off where they end.
            Row(Modifier.fillMaxSize().fadeEdges(scroll).horizontalScroll(scroll).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                val caret = order.caret(slots.size)
                slots.forEachIndexed { s, range ->
                    if (caret == s) DropLine(vertical = false)
                    ReorderItem(order, s, tabs[range.first].uri, slots.size, strip, { from, to -> onMove(tabs[slots[from].first], landing(slots, from, to)) }) {
                        if (range.count() == 2) {
                            // The halves in the order the panes stand on screen.
                            val swapped = tabs[range.first].swapped
                            val (left, right) = if (swapped) range.last to range.first else range.first to range.last
                            val focused = when (active) { left -> 0; right -> 1; else -> -1 }
                            SplitTab(tabs[left], tabs[right], focused, below, Modifier.width(width * SPLIT_WIDTH), onSelect) {
                                onUnsplit(tabs[range.first])
                            }
                        } else {
                            val tab = tabs[range.first]
                            Tab(tab, range.first == active, below, Modifier.width(width), { onSelect(tab) }, { onClose(tab) })
                        }
                    }
                }
                if (caret == slots.size) DropLine(vertical = false)
            }
        }
        TabCounter(tabs, slots, active, visible, shown != null, onSelect, onClose, onMove, onMoveFirst, onSplit, onSwap, onUnsplit, onCloseSplit, onColor, onCloseOthers, onOpenNote)
    }
}

/** Fades the strip's ends where more tabs scroll past them, so they are not cut off sharply. */
private fun Modifier.fadeEdges(scroll: ScrollState): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val fade = TAB_FADE.toPx()
        if (scroll.value > 0) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = fade), size = Size(fade, size.height), blendMode = BlendMode.DstIn)
        }
        if (scroll.value < scroll.maxValue) {
            val start = size.width - fade
            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = start), Offset(start, 0f), Size(fade, size.height), blendMode = BlendMode.DstIn)
        }
    }

private val TAB_FADE = 16.dp

/** A long-press drag that moves one of a row's or column's items, all in root coordinates. */
private class TabDrag(val vertical: Boolean) {
    var grabbed by mutableStateOf<Int?>(null)

    /** How far the grabbed item has been carried from its place. */
    var shift by mutableFloatStateOf(0f)

    /** Each item's place at rest. */
    val bounds = mutableStateMapOf<Int, Rect>()

    fun along(o: Offset) = if (vertical) o.y else o.x

    /** Where the grabbed item [from] lands among the other [count] - 1. */
    fun target(from: Int, count: Int): Int {
        val at = bounds[from]?.let { along(it.center) + shift } ?: return from
        return (0 until count).count { i -> i != from && bounds[i]?.let { along(it.center) < at } == true }
    }

    /** The gap the drop line marks, or null while the item would stay where it is. */
    fun caret(count: Int): Int? {
        val from = grabbed ?: return null
        val to = target(from, count)
        return if (to == from) null else if (to < from) to else to + 1
    }
}

@Composable
private fun ReorderItem(
    order: TabDrag,
    index: Int,
    key: String,
    count: Int,
    lifted: Color,
    onDrop: (from: Int, to: Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val drop by rememberUpdatedState(onDrop)
    val size by rememberUpdatedState(count)
    val carried = order.grabbed == index
    val palette = LocalPalette.current
    Box(
        Modifier
            .onGloballyPositioned { if (order.grabbed == null) order.bounds[index] = it.boundsInRoot() }
            .zIndex(if (carried) 1f else 0f)
            .graphicsLayer {
                if (carried) {
                    if (order.vertical) translationY = order.shift else translationX = order.shift
                }
            }
            .then(
                if (!carried) Modifier
                else Modifier.clip(MaterialTheme.shapes.small).background(lifted).border(1.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small),
            )
            .pointerInput(key, index) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { order.shift = 0f; order.grabbed = index },
                    onDragEnd = {
                        val to = order.target(index, size)
                        order.grabbed = null
                        if (to != index) drop(index, to)
                    },
                    onDragCancel = { order.grabbed = null },
                ) { change, drag ->
                    change.consume()
                    order.shift += order.along(drag)
                }
            },
    ) { content() }
}

/** Where a carried tab would land. */
@Composable
private fun DropLine(vertical: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .then(if (vertical) Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp) else Modifier.width(2.dp).height(22.dp))
            .background(LocalPalette.current.accent.toComposeColor()),
    )
}

@Composable
private fun Tab(
    tab: OpenTab,
    shown: Boolean,
    below: Color,
    modifier: Modifier,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalPalette.current
    Box(modifier.height(STRIP_HEIGHT - 6.dp)) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = if (shown) 0.dp else 3.dp, vertical = 3.dp)
                .tabOutline()
                .clip(MaterialTheme.shapes.small)
                .background(if (shown) below else Color.Transparent)
                .clickable(role = Role.Tab, onClick = onSelect)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dim = (if (shown) palette.selectionForeground else palette.textDim).toComposeColor()
            Icon(
                kindIcon(tab.name),
                contentDescription = null,
                tint = tab.color?.toComposeColor() ?: dim,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                DocumentKind.stripSuffix(tab.name),
                color = dim,
                fontSize = 13.sp,
                fontWeight = if (shown) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(24.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(XnotesIcons.close, contentDescription = stringResource(R.string.close_tab), tint = dim, modifier = Modifier.size(14.dp))
            }
        }
        tab.color?.let { c -> ColorEdge(c, Modifier.align(Alignment.TopCenter), if (shown) 10.dp else 13.dp, 3.dp) }
    }
}

/** A thin outline that tells the tabs apart. */
@Composable
private fun Modifier.tabOutline(): Modifier = border(1.dp, LocalPalette.current.border.toComposeColor(), MaterialTheme.shapes.small)

/** A tab's colour code, run along its top edge as a tab group's is. */
@Composable
private fun ColorEdge(color: com.xnotes.core.model.Rgba, modifier: Modifier, inset: Dp, top: Dp) {
    Box(
        modifier.padding(horizontal = inset).padding(top = top).fillMaxWidth().height(3.dp)
            .clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)).background(color.toComposeColor()),
    )
}

/** How much wider than a tab the split tab is: room for two names, not two whole tabs. */
private const val SPLIT_WIDTH = 1.7f

/**
 * The two tabs of a split screen as one tab with a half for each pane. [focused] is the half whose
 * pane has the focus, 0 or 1, or -1 while the split is not on screen. A half opens the split with its
 * pane focused; the x splits the tab back in two.
 */
@Composable
private fun SplitTab(
    first: OpenTab,
    second: OpenTab,
    focused: Int,
    below: Color,
    modifier: Modifier,
    onSelect: (OpenTab) -> Unit,
    onUnsplit: () -> Unit,
) {
    val palette = LocalPalette.current
    val shown = focused >= 0
    val dim = if (shown) palette.selectionForeground.toComposeColor().copy(alpha = 0.7f) else palette.textDim.toComposeColor()
    Box(modifier.height(STRIP_HEIGHT - 6.dp)) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = if (shown) 0.dp else 3.dp, vertical = 3.dp)
                .tabOutline()
                .clip(MaterialTheme.shapes.small)
                .background(if (shown) below else Color.Transparent)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Composable
            fun half(tab: OpenTab, on: Boolean) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(tab) }) {
                    Row(Modifier.fillMaxHeight().padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(kindIcon(tab.name), contentDescription = null, tint = tab.color?.toComposeColor() ?: dim, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            DocumentKind.stripSuffix(tab.name),
                            color = if (on) palette.selectionForeground.toComposeColor() else dim,
                            fontSize = 13.sp,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    tab.color?.let { c -> ColorEdge(c, Modifier.align(Alignment.TopCenter), 10.dp, 0.dp) }
                }
            }
            half(first, focused == 0)
            Box(Modifier.width(1.dp).height(16.dp).background(palette.border.toComposeColor()))
            half(second, focused == 1)
            Box(
                Modifier.size(24.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onUnsplit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(XnotesIcons.close, contentDescription = stringResource(R.string.close_split), tint = dim, modifier = Modifier.size(14.dp))
            }
        }
    }
}

private fun splitName(first: OpenTab, second: OpenTab) =
    DocumentKind.stripSuffix(first.name) + " | " + DocumentKind.stripSuffix(second.name)

private fun kindIcon(name: String): ImageVector = when (DocumentKind.ofName(name)) {
    DocumentKind.CANVAS -> XnotesIcons.canvas
    DocumentKind.PDF -> XnotesIcons.pdfFile
    else -> XnotesIcons.note
}

/** The number of tabs in a box, opening the list of them all with what can be done to each. */
@Composable
private fun TabCounter(
    tabs: List<OpenTab>,
    slots: List<IntRange>,
    shown: Int,
    onScreen: Set<Int>,
    canSplit: Boolean,
    onSelect: (OpenTab) -> Unit,
    onClose: (OpenTab) -> Unit,
    onMove: (OpenTab, Int) -> Unit,
    onMoveFirst: (OpenTab) -> Unit,
    onSplit: (OpenTab) -> Unit,
    onSwap: (OpenTab) -> Unit,
    onUnsplit: (OpenTab) -> Unit,
    onCloseSplit: (() -> Unit)?,
    onColor: (OpenTab, com.xnotes.core.model.Rgba?) -> Unit,
    onCloseOthers: () -> Unit,
    onOpenNote: () -> Unit,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    // The tab whose own actions the list shows in place of the tabs, and whether that is its colours.
    var acting by remember { mutableStateOf<String?>(null) }
    var colours by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (!open) { acting = null; colours = false } }
    val order = remember { TabDrag(vertical = true) }
    val text = palette.text.toComposeColor()
    Box(Modifier.fillMaxHeight().padding(end = 6.dp), contentAlignment = Alignment.Center) {
        Tooltip(stringResource(R.string.all_tabs)) {
            Row(
                Modifier.height(30.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { open = true }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // A whole-pixel line, or the antialiasing draws some sides thicker than others.
                val line = with(LocalDensity.current) { 1.5.dp.roundToPx().coerceAtLeast(1).toDp() }
                Box(
                    Modifier.widthIn(min = 20.dp).height(20.dp).border(line, text, MaterialTheme.shapes.extraSmall).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // The font's own padding and line gap would sit the digits low in the box.
                    Text(
                        if (tabs.size > 99) "\u221E" else tabs.size.toString(),
                        color = text,
                        fontSize = if (tabs.size > 99) 14.sp else 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = androidx.compose.ui.text.TextStyle(
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                            ),
                        ),
                    )
                }
                Icon(XnotesIcons.chevronDown, contentDescription = null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large, containerColor = panelColors().sheet) {
            val tab = acting?.let { uri -> tabs.firstOrNull { it.uri == uri } }
            // A split tab is acted on as one: its colour goes to both halves.
            val partner = tab?.let { t -> slots.firstOrNull { it.count() == 2 && it.first == tabs.indexOf(t) }?.let { tabs[it.last] } }
            when {
                tab != null && colours -> ColorCodeMenuContent({ colours = false }) { c ->
                    open = false
                    onColor(tab, c)
                    partner?.let { onColor(it, c) }
                }
                tab != null && partner != null -> {
                    MenuBack(splitName(tab, partner)) { acting = null }
                    MenuItem(stringResource(R.string.move_to_first), XnotesIcons.arrowUp, enabled = tabs.firstOrNull() != tab) { open = false; onMoveFirst(tab) }
                    MenuItem(stringResource(R.string.swap_sides), XnotesIcons.flipHorizontal) { onSwap(tab) }
                    MenuItem(stringResource(R.string.colour_code), XnotesIcons.palette, more = true) { colours = true }
                    MenuItem(stringResource(R.string.unsplit_tab), XnotesIcons.split) { open = false; onUnsplit(tab) }
                }
                tab != null -> {
                    MenuBack(DocumentKind.stripSuffix(tab.name)) { acting = null }
                    MenuItem(stringResource(R.string.move_to_first), XnotesIcons.arrowUp, enabled = slots.firstOrNull()?.contains(tabs.indexOf(tab)) != true) { open = false; onMoveFirst(tab) }
                    MenuItem(stringResource(R.string.open_in_split), XnotesIcons.split, enabled = canSplit && tabs.indexOf(tab) !in onScreen) { open = false; onSplit(tab) }
                    MenuItem(stringResource(R.string.colour_code), XnotesIcons.palette, more = true) { colours = true }
                    MenuItem(stringResource(R.string.close_tab), XnotesIcons.close, danger = true) { open = false; onClose(tab) }
                }
                else -> Column(Modifier.width(LIST_WIDTH).padding(horizontal = 8.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ListRow(XnotesIcons.folder, stringResource(R.string.open_note)) { open = false; onOpenNote() }
                    ListRow(XnotesIcons.close, stringResource(R.string.close_all_tabs), danger = true) { open = false; onCloseOthers() }
                    if (onCloseSplit != null) ListRow(XnotesIcons.split, stringResource(R.string.close_split)) { open = false; onCloseSplit() }
                    Spacer(Modifier.height(4.dp))
                    val caret = order.caret(slots.size)
                    val card = panelColors().card
                    slots.forEachIndexed { s, range ->
                        if (caret == s) DropLine(vertical = true)
                        ReorderItem(order, s, tabs[range.first].uri, slots.size, card, { from, to -> onMove(tabs[slots[from].first], landing(slots, from, to)) }) {
                            // A split tab is one row under the split screen's icon, its names in the panes' order.
                            val t = tabs[range.first]
                            val label = when {
                                range.count() == 1 -> DocumentKind.stripSuffix(t.name)
                                t.swapped -> splitName(tabs[range.last], t)
                                else -> splitName(t, tabs[range.last])
                            }
                            ListRow(
                                if (range.count() == 2) XnotesIcons.split else kindIcon(t.name),
                                label,
                                tint = t.color?.toComposeColor(),
                                selected = range.any { it == shown || it in onScreen },
                                onMore = { acting = t.uri },
                            ) { open = false; onSelect(t) }
                        }
                    }
                    if (caret == slots.size) DropLine(vertical = true)
                }
            }
        }
    }
}

private val LIST_WIDTH = 280.dp

/** One row of the tabs list, on a card of its own as the tool menus' rows are. */
@Composable
private fun ListRow(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    onMore: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val fg = when {
        selected -> palette.selectionForeground
        danger -> palette.danger
        else -> palette.text
    }.toComposeColor()
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) palette.selectionBackground.toComposeColor() else panelColors().card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 12.dp, end = if (onMore != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: if (selected || danger) fg else palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onMore != null) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(XnotesIcons.more, contentDescription = stringResource(R.string.tab_options), tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
            }
        }
    }
}
