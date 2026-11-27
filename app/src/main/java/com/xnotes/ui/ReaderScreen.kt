package com.xnotes.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.ReaderKind
import com.xnotes.platform.ReaderHtml
import com.xnotes.settings.Preferences
import com.xnotes.settings.ReaderMark
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The read-only reader: Markdown, Office files, books and images as HTML in an offline WebView, one
 * long scroll or pages in several layouts, with the notes' side panel for contents, bookmarks and
 * search. Its colours, where the reader got to and its bookmarks are app preferences and never touch the file.
 */
@Composable
internal fun ReaderScreen(
    editor: Editor,
    file: ReaderFile,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    onWikiLink: (String) -> Unit,
) {
    val context = LocalContext.current
    val appPalette = LocalPalette.current
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    val view = readerViewOf(prefs, file)
    val paged = view != "scroll"
    val slides = view == "slides"
    // The page script is the only one the page lets run, by this key.
    val nonce = remember(file.uri) { java.util.UUID.randomUUID().toString().replace("-", "") }
    val (colors, look) = remember(prefs.readerTheme, prefs.readerCustomPaper, prefs.readerCustomText, prefs.readerPaperLook, appPalette) {
        readerTheme(editor, prefs, appPalette)
    }
    BackHandler(onBack = onClose)

    val content by produceState<ReaderContent?>(null, file.uri) {
        value = withContext(Dispatchers.IO) { runCatching { readerHtmlBody(context, editor, file, readerLabels(context)) }.getOrNull() ?: ReaderContent.Failed }
    }

    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    // The islands: a tap shows or hides them, moving the page hides them.
    var awake by remember(file.uri) { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    // The side panel opens and shuts with a note's, and like a note's it opens on its first tab.
    var sideTab by remember { mutableStateOf(FileSideTab.CONTENTS) }
    val side = sideTab.takeIf { editor.sidebarVisible }
    fun toggleSide() {
        if (!editor.sidebarVisible) sideTab = FileSideTab.CONTENTS
        editor.sidebarVisible = !editor.sidebarVisible
    }
    val floating = LocalToolbarLook.current.floating
    // What the side panel covers of the left, its margins too while it floats.
    val sideRoom = when {
        side == null -> 0.dp
        floating -> READER_SIDE_PANEL_WIDTH.dp + ISLAND_MARGIN * 2
        else -> READER_SIDE_PANEL_WIDTH.dp
    }
    var autoscroll by remember(file.uri) { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTo by remember { mutableStateOf<Int?>(null) }
    // Paged, the page in view of [count] in the part shown; else how far down, in thousandths.
    var at by remember(file.uri) { mutableIntStateOf(0) }
    var count by remember(file.uri) { mutableIntStateOf(1) }
    // How far through the whole file the reader is, from 0 to 1.
    var bookAt by remember(file.uri) { mutableFloatStateOf(prefs.readerPositions[file.name] ?: 0f) }
    // A file is shown a part at a time, since laying it all out at once takes the WebView long.
    val chunks = remember(content) { (content as? ReaderContent.Html)?.let { ReaderHtml.pageChunks(it.body) } ?: listOf("") }
    var chunk by remember(file.uri) { mutableIntStateOf(0) }
    // Where the next load opens, as a fraction of what it shows; null keeps the place as it was.
    var enterAt by remember { mutableStateOf<Float?>(null) }
    // A deck counts its slides across all parts: where each part's first slide falls, and how many in all.
    val slideStarts = remember(chunks) { chunks.runningFold(0) { n, c -> n + SLIDE_TAG.findAll(c).count() } }
    val paper = readerPaper(prefs)
    var pageBox by remember { mutableStateOf(IntSize.Zero) }
    // Other pages are counted part by part on a hidden page, then the whole file counts them like a deck its slides.
    var partPages by remember(chunks, view, paper, prefs.readerTextZoom, sideRoom, pageBox) { mutableStateOf(emptyList<Int>()) }
    // Counting waits for the page in view, so the first page is not slowed by the count beside it.
    var shown by remember(file.uri) { mutableStateOf(false) }
    val counted = slides || (paged && partPages.size == chunks.size)
    val starts = remember(slides, slideStarts, partPages) { if (slides) slideStarts else partPages.runningFold(0, Int::plus) }
    // Pages in the whole file as the reader lays it out now; 0 while not known.
    val pages = when { counted -> starts.last(); paged && chunks.size == 1 -> count; else -> 0 }
    /** Go to [f] of the whole file; [live] (while scrubbing) loads another part only once the one shown is ready, so a drag never queues loads. */
    fun showBook(f: Float, live: Boolean = false) {
        val (target, within) = if (counted) {
            val total = starts.last()
            val slide = (f.coerceIn(0f, 1f) * (total - 1).coerceAtLeast(0)).roundToInt()
            val k = starts.dropLast(1).indexOfLast { it <= slide }.coerceAtLeast(0)
            val n = starts[k + 1] - starts[k]
            k to if (n > 1) (slide - starts[k]) / (n - 1f) else 0f
        } else {
            val t = f.coerceIn(0f, 1f) * chunks.size
            val k = t.toInt().coerceAtMost(chunks.lastIndex)
            k to (t - k).coerceAtMost(1f)
        }
        val wv = webView
        when {
            wv == null -> { enterAt = within; chunk = target }
            target == chunk && wv.ready -> wv.showFraction(within)
            // The part is still loading: it lands where the finger is now.
            target == chunk -> { wv.restoreAt = within; if (enterAt != null) enterAt = within }
            !live || wv.ready -> { enterAt = within; chunk = target }
        }
    }
    /** Run [script] in part [part]: at once when it is shown, else once it has loaded. */
    fun inPart(part: Int, script: String) {
        val wv = webView ?: return
        if (part == chunk && wv.ready) wv.evaluateJavascript(script, null)
        else { wv.pendingScript = script; enterAt = 0f; chunk = part }
    }
    // The file opens where it was left, once its parts are known.
    var opened by remember(file.uri) { mutableStateOf(false) }
    if (!opened && content is ReaderContent.Html) {
        opened = true
        prefs.readerPositions[file.name]?.let { showBook(it) }
    }
    var shownView by remember { mutableStateOf(view) }
    if (shownView != view && content is ReaderContent.Html) {
        shownView = view
        enterAt = (bookAt * chunks.size - chunk).coerceIn(0f, 1f)
    }
    // Where the reader got to is kept a moment after it stops moving, and on leaving, so it follows the file to other devices.
    fun savePosition() {
        val now = editor.preferences
        if (content !is ReaderContent.Html || abs((now.readerPositions[file.name] ?: -1f) - bookAt) < 0.0005f) return
        editor.applyHomePreferences(now.copy(readerPositions = (now.readerPositions - file.name + (file.name to bookAt)).trimmed()))
    }
    LaunchedEffect(bookAt) { delay(2500); savePosition() }
    val saveNow = rememberUpdatedState { savePosition() }
    DisposableEffect(file.uri) { onDispose { saveNow.value() } }

    val marks = prefs.readerMarks[file.name].orEmpty()
    fun setMarks(next: List<ReaderMark>) {
        val now = editor.preferences
        editor.applyHomePreferences(now.copy(readerMarks = if (next.isEmpty()) now.readerMarks - file.name else now.readerMarks + (file.name to next)))
    }

    // Autoscroll moves the page on at the chosen speed, on into the next part at the end of one. Whole
    // pages turn instead, each held as long as scrolling a screen's height would take; a slide is held 10 s at the default speed.
    val speed = prefs.autoScrollSpeed
    LaunchedEffect(autoscroll, speed, view) {
        if (!autoscroll) return@LaunchedEffect
        val pxPerMm = context.resources.displayMetrics.ydpi / 25.4f
        val turning = view == "slides" || view == "single" || view == "double" || view == "cover"
        var last = 0L
        var carry = 0f
        var held = 0f
        while (true) {
            withFrameNanos { t ->
                val wv = webView
                if (last != 0L && wv != null && wv.ready && wv.touching) held = 0f
                if (last != 0L && wv != null && wv.ready && !wv.touching) {
                    val px = speed / 10f * pxPerMm * (t - last) / 1e9f
                    if (turning) {
                        held += if (slides) speed * (t - last) / 1e9f else px
                        if (held >= if (slides) SLIDE_HOLD else wv.height.toFloat()) {
                            held = 0f
                            if (at < count - 1 || chunk < chunks.lastIndex) wv.turn(1) else autoscroll = false
                        }
                    } else {
                        carry += px
                        val step = carry.toInt()
                        carry -= step
                        val across = view == "horizontal"
                        if (if (across) wv.canScrollHorizontally(1) else wv.canScrollVertically(1)) {
                            if (across) wv.scrollBy(step, 0) else wv.scrollBy(0, step)
                        } else if (chunk < chunks.lastIndex) {
                            enterAt = 0f; chunk++
                        } else autoscroll = false
                    }
                }
                last = t
            }
        }
    }

    // The side panel lies over the page, which makes room for it itself (R.inset): reflowing and finding its place
    // again happen in one go inside the page, where resizing the WebView shows the old layout shifted for a few frames.
    Box(Modifier.fillMaxSize().background(appPalette.bg.toComposeColor())) {
        Box(Modifier.fillMaxSize()) {
            side?.let { tab ->
                ReaderSidePanel(
                    // Above the page, and touches on it never reach the page.
                    // Below a docked bar; floating, it floats like the bar along the whole left side and the islands move beside it.
                    modifier = Modifier.zIndex(1f).then(if (floating) Modifier.padding(ISLAND_MARGIN).barPill() else Modifier.padding(top = topIslandsRoom())).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                    tab = tab,
                    onTab = { sideTab = it },
                    parts = chunks,
                    at = bookAt,
                    marks = marks,
                    onAddMark = { label -> setMarks(marks + ReaderMark(bookAt, label)) },
                    onRenameMark = { m, label -> setMarks(marks.map { if (it == m) it.copy(label = label) else it }) },
                    onRemoveMark = { m -> setMarks(marks - m) },
                    placeOf = { f -> if (pages > 0) context.getString(R.string.page_n, (f * (pages - 1)).roundToInt() + 1) else "${(f * 100).roundToInt()}%" },
                    onHeading = { h -> inPart(h.part, "window.R&&R.heading(${h.index})") },
                    onMark = { m -> showBook(m.at) },
                    onHit = { h, q -> inPart(h.part, "window.R&&R.find(${JSONObject.quote(q)},${h.nth})") },
                )
            }
            Box(Modifier.fillMaxSize().onSizeChanged { pageBox = it }) {
                when (val c = content) {
                    null -> {
                        // Like a note opening: only a read slow enough to notice shows the spinner.
                        var slow by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { delay(160); slow = true }
                        if (slow) com.xnotes.SpinnerDialog(stringResource(R.string.loading), onCancel = onClose)
                    }
                    ReaderContent.Failed -> ReaderMessage(stringResource(R.string.reader_open_failed), appPalette)
                    is ReaderContent.Html -> {
                        val part = chunk.coerceIn(0, chunks.lastIndex)
                        if (shown && paged && !slides && chunks.size > 1 && partPages.size < chunks.size) {
                            val next = partPages.size
                            val countPage = remember(c, colors, look, view, next, paper) { ReaderHtml.readerPage(chunks[next], colors, view, paper, nonce, look, c.wide, false) }
                            PageCounter(countPage, prefs.readerTextZoom, view, sideRoom.value.roundToInt()) { n -> if (partPages.size == next) partPages = partPages + n }
                        }
                        val smooth = prefs.readerTurnAnimation
                        val html = remember(c, colors, look, view, part, paper, smooth) {
                            ReaderHtml.readerPage(chunks[part], colors, view, paper, nonce, look, c.wide, smooth)
                        }
                        ReaderWebView(
                            html, appPalette.bg, prefs.readerTextZoom, view, prefs.readerTapToTurn, smooth, enterAt, sideRoom.value.roundToInt(),
                            modifier = Modifier.fillMaxSize(),
                            onCreated = { webView = it },
                            onEntered = { enterAt = null },
                            onEdge = { d ->
                                val next = part + d
                                if (next in chunks.indices) { enterAt = if (d > 0) 0f else 1f; chunk = next }
                            },
                            onTap = { awake = !awake },
                            onMove = { awake = false },
                            onPositionChange = { a, n ->
                                shown = true
                                at = a
                                count = n
                                bookAt = when {
                                    counted -> (starts[part] + a) / (starts.last() - 1).coerceAtLeast(1).toFloat()
                                    !paged -> (part + a / n.toFloat()) / chunks.size
                                    // The last page of the last part is the whole way through.
                                    n <= 1 -> (part + if (part == chunks.lastIndex) 1f else 0f) / chunks.size
                                    else -> (part + a / (n - 1f)) / chunks.size
                                }.coerceIn(0f, 1f)
                            },
                        ) { url -> openLink(context, url, onWikiLink) }
                    }
                }
            }
        }
        // The islands are app chrome like the note reader's; only the file under them follows the reader's theme.
        FileReadingIslands(
            file.name,
            awake,
            held = panelOpen || scrubbing,
            start = sideRoom,
            onClose = onClose,
            home = {
                ToolbarIcon(XnotesIcons.sidebar, stringResource(R.string.side_panel), active = side != null, shortcut = Keys.SIDEBAR) { toggleSide() }
            },
            actions = {
                ToolbarIcon(XnotesIcons.search, stringResource(R.string.find_in_page), active = side == FileSideTab.SEARCH) {
                    if (side == FileSideTab.SEARCH) editor.sidebarVisible = false else { sideTab = FileSideTab.SEARCH; editor.sidebarVisible = true }
                }
                FileReaderSettingsButton(editor, file, fullscreen, onToggleFullscreen, autoscroll, { autoscroll = it }) { panelOpen = it }
            },
        ) {
            val scrub = Modifier.weight(1f).padding(horizontal = SCRUBBER_ROOM)
            // The thumb slides freely and, let go, snaps to the nearest slide, or page when the file is one part.
            // A file in several parts snaps once the hidden page has counted all its parts.
            val steps = when {
                counted -> starts.last() - 1
                paged && chunks.size == 1 -> count - 1
                else -> PERMILLE
            }.coerceAtLeast(1)
            val scrubber = @Composable {
                // The scrubber spans the whole file, across its parts, and moves the page as it slides.
                PageScrubber(
                    scrubTo ?: (bookAt * PERMILLE).roundToInt(), PERMILLE, emptyList(), scrubbing,
                    onScrub = { scrubbing = true; scrubTo = it; showBook(it / PERMILLE.toFloat(), live = true) },
                    onLetGo = {
                        scrubbing = false
                        // The thumb snaps where it was let go rather than flicking back until the page reports.
                        scrubTo?.let { bookAt = (it / PERMILLE.toFloat() * steps).roundToInt() / steps.toFloat(); showBook(bookAt) }
                        scrubTo = null
                    },
                    modifier = scrub,
                )
            }
            if (paged) {
                // Slides, and pages once counted, are numbered through the whole file.
                ToolbarIcon(XnotesIcons.prev, stringResource(R.string.previous_page), enabled = at > 0 || chunk > 0) { webView?.turn(-1) }
                // The slide or page under the thumb is known before its part loads.
                val slideNow = (scrubTo?.let { (it / PERMILLE.toFloat() * steps).roundToInt() } ?: (starts.getOrElse(chunk) { 0 } + at)).coerceIn(0, (starts.last() - 1).coerceAtLeast(0))
                // Until its parts are counted, a file in several parts shows how far through it is instead.
                val label = when {
                    counted -> "${slideNow + 1} " + stringResource(R.string.page_of_count, starts.last())
                    chunks.size > 1 -> "${(scrubTo ?: (bookAt * PERMILLE).roundToInt()) / 10}%"
                    else -> "${at + 1} " + stringResource(R.string.page_of_count, count)
                }
                Label(label, Modifier.widthIn(min = 64.dp))
                scrubber()
                ToolbarIcon(XnotesIcons.next, stringResource(R.string.next_page), enabled = at < count - 1 || chunk < chunks.lastIndex) { webView?.turn(1) }
            } else {
                Label("${(scrubTo ?: (bookAt * PERMILLE).roundToInt()) / 10}%", Modifier.padding(start = 12.dp).widthIn(min = 44.dp))
                scrubber()
            }
        }
    }
}

private const val PERMILLE = 1000


/** A slide's hold in autoscroll, in speed x seconds: 200 is 10 s at the default speed, 2 s at the fastest. */
private const val SLIDE_HOLD = 200f

private val SLIDE_TAG = Regex("<section class=\"slide\"")

/** The newest few hundred entries, so per-file maps in the synced settings stay small. */
private fun <V> Map<String, V>.trimmed(): Map<String, V> = if (size <= 300) this else entries.toList().takeLast(300).associate { it.key to it.value }

/** Slide decks: their slides are pages already, so they scroll with each slide counted as a page. */
internal val READER_SLIDE_KINDS = setOf(ReaderKind.PPTX, ReaderKind.PPT, ReaderKind.ODP)

/** Files that bring their own page size and margins. */
internal val READER_DOC_KINDS = setOf(ReaderKind.DOCX, ReaderKind.ODT)

/** How [file] is shown: a deck as slides and a book a page at a time; else its own choice, else documents their pages, else the default for all files. */
internal fun readerViewOf(p: Preferences, file: ReaderFile): String {
    val kind = ReaderKind.ofName(file.name)
    if (kind in READER_SLIDE_KINDS) return "slides"
    if (ReaderKind.isBook(file.name)) return "single"
    return p.readerFileViews[file.name] ?: if (kind in READER_DOC_KINDS) "vertical" else p.readerView
}

/** The paper a flowing file is paged on, in cm as "width height top right bottom left", or "screen". */
private fun readerPaper(p: Preferences): String {
    val (w, h, m) = when (p.readerPageFormat) {
        "a6" -> Triple(10.5, 14.8, 1.2)
        "a5" -> Triple(14.8, 21.0, 1.6)
        "a4" -> Triple(21.0, 29.7, 2.2)
        "letter" -> Triple(21.6, 27.9, 2.2)
        else -> return "screen"
    }
    val (pw, ph) = if (p.readerLandscape) h to w else w to h
    return "$pw $ph $m $m $m $m"
}

/** CSS hex of an ARGB colour. */
private fun hex(argb: Int) = String.format("#%06x", argb and 0xffffff)

/** [a] moved [t] of the way to [b], both ARGB. */
private fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = (((a shr s) and 0xff) * (1 - t) + ((b shr s) and 0xff) * t).roundToInt() shl s
    return ch(16) or ch(8) or ch(0)
}

private fun luma(argb: Int) = (((argb shr 16) and 0xff) * 299 + ((argb shr 8) and 0xff) * 587 + (argb and 0xff) * 114) / 1000

/**
 * The colours a file is shown in for the reader's theme, and how a document's own white pages and a
 * book's paper follow it. What is behind the content always keeps the app's own theme.
 */
private fun readerTheme(editor: Editor, p: Preferences, app: Palette): Pair<ReaderHtml.Colors, ReaderHtml.Look> {
    val accent = Rgba.toHex(app.accent)
    fun preset(paper: Int, text: Int) = ReaderHtml.Colors(hex(paper), hex(text), hex(mix(text, paper, 0.4f)), accent, hex(mix(paper, text, 0.08f)), hex(mix(paper, text, 0.2f)))
    val colors = when (p.readerTheme) {
        "light" -> readerColors(if (app.isDark) editor.readerPalette(false) else app)
        "dark" -> readerColors(if (app.isDark) app else editor.readerPalette(true))
        "sepia" -> preset(0xF4ECD8, 0x5B4636)
        "gray" -> preset(0x56575B, 0xEEEEEE)
        "black" -> preset(0x000000, 0xD4D4D4)
        "custom" -> preset(p.readerCustomPaper, p.readerCustomText)
        else -> readerColors(app)
    }
    val dark = luma(colors.background.removePrefix("#").toInt(16)) < 128
    // The reading themes give a document's pages their own paper and text, as any other file; light and dark only tint the white page.
    val docPaper = p.readerTheme in setOf("sepia", "gray", "black", "custom")
    return colors to ReaderHtml.Look(
        around = Rgba.toHex(app.bg),
        docFilter = if (dark && !docPaper) "invert(.88) hue-rotate(180deg)" else "",
        imageFilter = if (dark && !docPaper) "invert(1) hue-rotate(180deg)" else "",
        paperLook = ReaderHtml.paperLookCss(p.readerPaperLook),
        docPaper = docPaper,
    )
}

@Composable
private fun ReaderMessage(message: String, palette: Palette) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = palette.text.toComposeColor(), fontSize = 15.sp)
    }
}

/**
 * A WebView that tells where the reader is: scrolling, how far down it is; in pages, the page in
 * view of how many, which the page script reports through the page title.
 */
internal class ReaderWebView(context: Context) : WebView(context) {
    /** "scroll", "slides", or one of the page views. */
    var view = "scroll"
    var onPosition: (Int, Int) -> Unit = { _, _ -> }

    /** Hears a page turn run past the first or the last page, -1 or 1. */
    var onEdge: (Int) -> Unit = {}

    /** Where to put the reader once a reload lands, as a fraction of the way through; null to leave it. */
    var restoreAt: Float? = null

    /** A script to run once the page has loaded, instead of restoring the place: going to a heading or a match. */
    var pendingScript: String? = null

    /** Whether the page has loaded (and been cut into pages) since the last load began. */
    var ready = false
        private set

    /** How much of the page's left the side panel covers, in dp, which the page leaves free. */
    var inset = 0

    /** Whether a finger is on the page, which holds autoscroll. */
    var touching = false

    private var pages = 0
    private var pageAt = 0

    val paged get() = view != "scroll"

    /** Whether pages only turn whole, by a tap or a swipe, rather than scroll. */
    val turnsWhole get() = view == "single" || view == "double" || view == "cover"

    private val rangeY get() = computeVerticalScrollRange()

    fun pageCount(): Int = pages.coerceAtLeast(1)

    fun pageNow(): Int = pageAt

    fun showPage(page: Int) = evaluateJavascript("window.R&&R.go(${page.coerceIn(0, pageCount() - 1)})", null)

    /** Turn [by] pages or spreads on from [from] (the page in view by default); past either end the script reports an edge. */
    fun turn(by: Int, from: Int? = null) = evaluateJavascript("window.R&&R.turn($by${from?.let { ",$it" } ?: ""})", null)

    fun showPermille(p: Int) = scrollTo(0, ((rangeY - height).coerceAtLeast(0).toLong() * p / PERMILLE).toInt())

    /** How far through the reader is, from 0 to 1. */
    fun fraction(): Float =
        if (paged) (if (pageCount() > 1) pageAt.toFloat() / (pageCount() - 1) else 0f)
        else (rangeY - height).let { max -> if (max <= 0) 0f else scrollY.toFloat() / max }

    fun showFraction(f: Float) {
        if (paged) showPage((f * (pageCount() - 1)).roundToInt()) else showPermille((f * PERMILLE).roundToInt())
    }

    /** A new page is loading: its pages are not counted yet. */
    fun reset() { pages = 0; pageAt = 0; ready = false }

    /** The page has loaded: go where a pending script or the restore asks. */
    fun landed() {
        if (ready) return
        ready = true
        val script = pendingScript
        pendingScript = null
        val f = restoreAt
        restoreAt = null
        if (script != null) evaluateJavascript(script, null) else f?.let { showFraction(it) }
    }

    /** The page script's "count|page" report, with "|edge|n" when a turn ran past either end. */
    fun pagesReported(title: String?) {
        val parts = title?.split('|')?.map { it.toIntOrNull() ?: return } ?: return
        if (parts.size < 2) return
        pages = parts[0].coerceAtLeast(1); pageAt = parts[1].coerceIn(0, pages - 1)
        onPosition(pageAt, pages)
        if (!ready) landed()
        if (parts.size >= 4) onEdge(parts[2])
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        // Each frame may follow a scroll or a relayout; the callback only changes state when the numbers do.
        if (!paged) {
            val max = rangeY - height
            onPosition(if (max <= 0) 0 else (scrollY.toLong() * PERMILLE / max).toInt().coerceIn(0, PERMILLE), PERMILLE)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebView(
    html: String,
    background: Rgba,
    textZoom: Int,
    view: String,
    tapToTurn: Boolean,
    smooth: Boolean,
    enterAt: Float?,
    inset: Int,
    modifier: Modifier,
    onCreated: (ReaderWebView) -> Unit,
    onEntered: () -> Unit,
    onEdge: (Int) -> Unit,
    onTap: () -> Unit,
    onMove: () -> Unit,
    onPositionChange: (Int, Int) -> Unit,
    onLink: (String) -> Unit,
) {
    val onLinkNow = rememberUpdatedState(onLink)
    val onTapNow = rememberUpdatedState(onTap)
    val onMoveNow = rememberUpdatedState(onMove)
    val onPositionNow = rememberUpdatedState(onPositionChange)
    val onEdgeNow = rememberUpdatedState(onEdge)
    val tapToTurnNow = rememberUpdatedState(tapToTurn)
    val smoothNow = rememberUpdatedState(smooth)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ReaderWebView(ctx).apply {
                // Wrapping its content, a WebView measures 100vh by the content, which breaks the pages.
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                // File access and the network stay off. Scripts run only for the page script: the page's
                // policy blocks any script that has not its key, so whatever a file brings stays inert.
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                this.onPosition = { a, n -> onPositionNow.value(a, n) }
                this.onEdge = { onEdgeNow.value(it) }
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) = pagesReported(title)
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("about:blank#")) return false
                        onLinkNow.value(url)
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // A scroll has no pages to count, so it lands once laid out; the same text after a colour
                        // or layout change keeps about the same place. Pages land when the script reports them.
                        if (this@apply.view == "scroll") view.postDelayed({ landed() }, 80)
                    }
                }
                val slop = ViewConfiguration.get(ctx).scaledTouchSlop
                var downX = 0f
                var downY = 0f
                var downPage = 0
                var swiping = false
                var moved = false
                var atTop = false
                var atBottom = false
                val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // Turning whole pages or slides, a tap near either side turns; anywhere else shows or hides the islands.
                        val left = inset * resources.displayMetrics.density
                        val edge = (width - left) / 4f
                        val turns = (turnsWhole || view == "slides") && tapToTurnNow.value
                        when {
                            turns && e.x < left + edge -> turn(-1)
                            turns && e.x > width - edge -> turn(1)
                            else -> onTapNow.value()
                        }
                        return false
                    }
                })
                @SuppressLint("ClickableViewAccessibility")
                setOnTouchListener { v, e ->
                    taps.onTouchEvent(e)
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            touching = true
                            downX = e.x; downY = e.y; swiping = false; moved = false; downPage = pageNow()
                            atTop = if (view == "horizontal") !canScrollHorizontally(-1) else !canScrollVertically(-1)
                            atBottom = if (view == "horizontal") !canScrollHorizontally(1) else !canScrollVertically(1)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.x - downX
                            if (!moved && (abs(dx) > slop || abs(e.y - downY) > slop)) { moved = true; onMoveNow.value() }
                            if (turnsWhole && !swiping && abs(dx) > slop && abs(dx) > abs(e.y - downY)) {
                                swiping = true
                                // Without the slide, the page under the finger lets go of the press, so no link fires on lifting.
                                if (!smoothNow.value) MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { v.onTouchEvent(it) }.recycle()
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            touching = false
                            if (e.actionMasked == MotionEvent.ACTION_UP) when {
                                swiping -> {
                                    // The page followed the finger; a short drag springs back, a longer one turns from where it began.
                                    val dx = e.x - downX
                                    turn(if (abs(dx) < slop * 3) 0 else if (dx < 0) 1 else -1, downPage)
                                }
                                // Scrolling, a pull on past either end of the part opens the next or the previous one.
                                !turnsWhole && atBottom && (if (view == "horizontal") downX - e.x else downY - e.y) > slop * 4 -> onEdgeNow.value(1)
                                !turnsWhole && atTop && (if (view == "horizontal") e.x - downX else e.y - downY) > slop * 4 -> onEdgeNow.value(-1)
                            }
                        }
                    }
                    when {
                        !turnsWhole -> false
                        // Sliding, the page follows the finger, but lifting it never flings on past the turn.
                        smoothNow.value -> if (swiping && e.actionMasked == MotionEvent.ACTION_UP) {
                            MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { v.onTouchEvent(it) }.recycle()
                            true
                        } else false
                        // Without the slide, the WebView never scrolls itself: pages only turn whole.
                        else -> swiping || e.actionMasked == MotionEvent.ACTION_MOVE
                    }
                }
                onCreated(this)
            }
        },
        update = { wv ->
            wv.setBackgroundColor(background.toComposeColor().toArgb())
            if (wv.settings.textZoom != textZoom) wv.settings.textZoom = textZoom
            wv.settings.setSupportZoom(view == "scroll" || view == "vertical" || view == "slides")
            // Pages are cut for one text size, so a new size cuts them again.
            val tag = if (view == "scroll") "$view|$html" else "$view|$textZoom|$html"
            if (wv.tag != tag) {
                wv.restoreAt = enterAt ?: if (wv.tag != null) wv.fraction() else null
                onEntered()
                wv.tag = tag
                wv.view = view
                wv.reset()
                wv.inset = inset
                wv.loadDataWithBaseURL(null, html.replaceFirst("<body", "<body data-inset=\"$inset\""), "text/html", "utf-8", null)
            } else if (wv.inset != inset) {
                wv.inset = inset
                wv.evaluateJavascript("window.R&&R.inset($inset)", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

/** A hidden page that lays out [html] as the reader would, only to count its pages. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PageCounter(html: String, textZoom: Int, view: String, inset: Int, onCount: (Int) -> Unit) {
    val onCountNow = rememberUpdatedState(onCount)
    AndroidView(
        modifier = Modifier.fillMaxSize().alpha(0f),
        factory = { ctx ->
            ReaderWebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) = pagesReported(title)
                }
            }
        },
        update = { wv ->
            if (wv.settings.textZoom != textZoom) wv.settings.textZoom = textZoom
            val tag = "$view|$textZoom|$inset|$html"
            if (wv.tag != tag) {
                wv.tag = tag
                wv.view = view
                wv.reset()
                var reported = false
                wv.onPosition = { _, n -> if (!reported) { reported = true; onCountNow.value(n) } }
                wv.loadDataWithBaseURL(null, html.replaceFirst("<body", "<body data-inset=\"$inset\""), "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun openLink(context: Context, url: String, onWikiLink: (String) -> Unit) {
    val uri = Uri.parse(url)
    when (uri.scheme?.lowercase()) {
        ReaderHtml.WIKI_SCHEME -> onWikiLink(Uri.decode(uri.schemeSpecificPart.orEmpty()))
        "http", "https", "mailto", "tel" -> runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
