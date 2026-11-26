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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The read-only reader: Markdown, Office files, books and images as HTML in a script-less, offline
 * WebView, one long scroll or one page at a time. The light/dark choice is an app preference and never touches the file.
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
    val dark = prefs.readerDark ?: appPalette.isDark
    // The app theme itself, or its other light/dark variant when the reader is switched.
    val palette = remember(appPalette, dark) { if (dark == appPalette.isDark) appPalette else editor.readerPalette(dark) }
    val view = readerViewOf(prefs, file)
    val paged = view != "scroll"
    // The page script is the only one the page lets run, by this key.
    val nonce = remember(file.uri) { java.util.UUID.randomUUID().toString().replace("-", "") }
    BackHandler(onBack = onClose)

    val content by produceState<ReaderContent?>(null, file.uri) {
        value = withContext(Dispatchers.IO) { runCatching { readerHtmlBody(context, editor, file, readerLabels(context)) }.getOrNull() ?: ReaderContent.Failed }
    }

    // Find in page: the WebView finds in HTML itself.
    var finding by remember(file.uri) { mutableStateOf(false) }
    var query by remember(file.uri) { mutableStateOf("") }
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var webHits by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(query, finding, content, webView) {
        val q = if (finding) query.trim() else ""
        webView?.let { wv -> if (q.isEmpty()) { wv.clearMatches(); webHits = 0 to 0 } else wv.findAllAsync(q) }
    }
    val findStatus = if (query.isBlank()) "" else "${webHits.first} / ${webHits.second}"
    // The islands: a tap shows or hides them, moving the page hides them.
    var awake by remember(file.uri) { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTo by remember { mutableStateOf<Int?>(null) }
    // Paged, the page in view of [count] in the part shown; else how far down, in thousandths.
    var at by remember(file.uri) { mutableIntStateOf(0) }
    var count by remember(file.uri) { mutableIntStateOf(1) }
    // How far through the whole file the reader is, from 0 to 1.
    var bookAt by remember(file.uri) { mutableFloatStateOf(0f) }
    // A file is shown a part at a time, since laying it all out at once takes the WebView long.
    val chunks = remember(content) { (content as? ReaderContent.Html)?.let { ReaderHtml.pageChunks(it.body) } ?: listOf("") }
    var chunk by remember(file.uri) { mutableIntStateOf(0) }
    // Where the next load opens, as a fraction of what it shows; null keeps the place as it was.
    var enterAt by remember { mutableStateOf<Float?>(null) }
    // A deck counts its slides across all parts: where each part's first slide falls, and how many in all.
    val slideStarts = remember(chunks) { chunks.runningFold(0) { n, c -> n + SLIDE_TAG.findAll(c).count() } }
    val slides = view == "slides"
    /** Go to [f] of the whole file; [live] (while scrubbing) only moves within the part shown, never loading another. */
    fun showBook(f: Float, live: Boolean = false) {
        val (target, within) = if (slides) {
            val total = slideStarts.last()
            val slide = (f.coerceIn(0f, 1f) * (total - 1).coerceAtLeast(0)).roundToInt()
            val k = slideStarts.dropLast(1).indexOfLast { it <= slide }.coerceAtLeast(0)
            val n = slideStarts[k + 1] - slideStarts[k]
            k to if (n > 1) (slide - slideStarts[k]) / (n - 1f) else 0f
        } else {
            val t = f.coerceIn(0f, 1f) * chunks.size
            val k = t.toInt().coerceAtMost(chunks.lastIndex)
            k to (t - k).coerceAtMost(1f)
        }
        if (target == chunk) webView?.showFraction(within) else if (!live) { enterAt = within; chunk = target }
    }
    var shownView by remember { mutableStateOf(view) }
    if (shownView != view && content is ReaderContent.Html) {
        shownView = view
        enterAt = (bookAt * chunks.size - chunk).coerceIn(0f, 1f)
    }
    // Behind the content keeps the app's theme; only the file itself follows the reader's light/dark.
    Column(Modifier.fillMaxSize().background(appPalette.bg.toComposeColor())) {
        if (finding) FindBar(query, { query = it }, findStatus, { webView?.findNext(false) }, { webView?.findNext(true) }, { finding = false })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalPalette provides palette) {
                when (val c = content) {
                    null -> {
                        // Like a note opening: only a read slow enough to notice shows the spinner.
                        var slow by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { delay(160); slow = true }
                        if (slow) com.xnotes.SpinnerDialog(stringResource(R.string.loading), onCancel = onClose)
                    }
                    ReaderContent.Failed -> ReaderMessage(stringResource(R.string.reader_open_failed), palette.text)
                    is ReaderContent.Html -> {
                        val part = chunk.coerceIn(0, chunks.lastIndex)
                        val paper = readerPaper(prefs)
                        val around = Rgba.toHex(appPalette.bg)
                        val html = remember(c, palette, view, part, paper, around) {
                            if (paged) ReaderHtml.pagedPage(chunks[part], readerColors(palette), view, paper, nonce, around, dark, c.wide)
                            else ReaderHtml.page(chunks[part], readerColors(palette), c.wide, around, dark)
                        }
                        Box(Modifier.fillMaxSize()) {
                            ReaderWebView(
                                html, appPalette.bg, prefs.readerTextZoom, view, enterAt,
                                modifier = Modifier.fillMaxSize(),
                                onCreated = { wv ->
                                    webView = wv
                                    wv.setFindListener { active, n, _ -> webHits = (if (n == 0) 0 else active + 1) to n }
                                },
                                onEntered = { enterAt = null },
                                onEdge = { d ->
                                    val next = part + d
                                    if (next in chunks.indices) { enterAt = if (d > 0) 0f else 1f; chunk = next }
                                },
                                onTap = { awake = !awake },
                                onMove = { awake = false },
                                onPositionChange = { a, n ->
                                    if (!scrubbing) at = a
                                    count = n
                                    bookAt = when {
                                        slides -> (slideStarts[part] + a) / (slideStarts.last() - 1).coerceAtLeast(1).toFloat()
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
            // The islands are app chrome like the note reader's; only the page under them follows the reader's light/dark.
            FileReadingIslands(
                file.name,
                awake,
                held = panelOpen || scrubbing,
                onClose = onClose,
                actions = {
                    ToolbarIcon(XnotesIcons.search, stringResource(R.string.find_in_page), active = finding) { finding = !finding }
                    FileReaderSettingsButton(editor, file, fullscreen, onToggleFullscreen) { panelOpen = it }
                },
            ) {
                val scrub = Modifier.weight(1f).padding(horizontal = SCRUBBER_ROOM)
                val scrubber = @Composable {
                    // The scrubber spans the whole file, across its parts.
                    PageScrubber(
                        scrubTo ?: (bookAt * PERMILLE).roundToInt(), PERMILLE, emptyList(), scrubbing,
                        onScrub = { scrubbing = true; scrubTo = it; showBook(it / PERMILLE.toFloat(), live = true) },
                        onLetGo = {
                            scrubbing = false
                            scrubTo?.let { showBook(it / PERMILLE.toFloat()) }
                            scrubTo = null
                        },
                        modifier = scrub,
                    )
                }
                if (paged) {
                    // A deck counts its slides through the whole file; other pages are counted in the part shown.
                    ToolbarIcon(XnotesIcons.prev, stringResource(R.string.previous_page), enabled = at > 0 || chunk > 0) { webView?.turn(-1) }
                    val label = if (slides) "${slideStarts[chunk.coerceIn(0, chunks.lastIndex)] + at + 1} " + stringResource(R.string.page_of_count, slideStarts.last())
                        else "${at + 1} " + stringResource(R.string.page_of_count, count)
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
}

private const val PERMILLE = 1000

private val SLIDE_TAG = Regex("<section class=\"slide\"")

/** Slide decks: their slides are pages already, so they scroll with each slide counted as a page. */
internal val READER_SLIDE_KINDS = setOf(ReaderKind.PPTX, ReaderKind.PPT, ReaderKind.ODP)

/** Files that bring their own page size and margins. */
internal val READER_DOC_KINDS = setOf(ReaderKind.DOCX, ReaderKind.ODT)

/** How [file] is shown: its own choice, else the default for all files. */
internal fun readerViewOf(p: Preferences, file: ReaderFile): String =
    if (ReaderKind.ofName(file.name) in READER_SLIDE_KINDS) "slides" else p.readerFileViews[file.uri] ?: p.readerView

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

@Composable
private fun ReaderMessage(message: String, text: Rgba) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = text.toComposeColor(), fontSize = 15.sp)
    }
}

/**
 * A WebView that tells where the reader is: scrolling, how far down it is; in pages, the page in
 * view of how many, which the page script reports through the page title.
 */
internal class ReaderWebView(context: Context) : WebView(context) {
    /** "scroll", or one of the page views. */
    var view = "scroll"
    var onPosition: (Int, Int) -> Unit = { _, _ -> }

    /** Hears a page turn run past the first or the last page, -1 or 1. */
    var onEdge: (Int) -> Unit = {}

    /** Where to put the reader once a reload lands, as a fraction of the way through; null to leave it. */
    var restoreAt: Float? = null

    private var pages = 0
    private var pageAt = 0

    val paged get() = view != "scroll"

    /** Whether pages only turn whole, by a tap or a swipe, rather than scroll. */
    val turnsWhole get() = view == "single" || view == "double"

    private val rangeY get() = computeVerticalScrollRange()

    fun pageCount(): Int = pages.coerceAtLeast(1)

    fun showPage(page: Int) = evaluateJavascript("window.R&&R.go(${page.coerceIn(0, pageCount() - 1)})", null)

    /** Turn [by] pages (by spreads, two side by side), on into the next or the previous part past either end. */
    fun turn(by: Int) {
        val page = pageAt + by * (if (view == "double") 2 else 1)
        if (page < 0 || page >= pageCount()) onEdge(by) else showPage(page)
    }

    fun showPermille(p: Int) = scrollTo(0, ((rangeY - height).coerceAtLeast(0).toLong() * p / PERMILLE).toInt())

    /** How far through the reader is, from 0 to 1. */
    fun fraction(): Float =
        if (paged) (if (pageCount() > 1) pageAt.toFloat() / (pageCount() - 1) else 0f)
        else (rangeY - height).let { max -> if (max <= 0) 0f else scrollY.toFloat() / max }

    fun showFraction(f: Float) {
        if (paged) showPage((f * (pageCount() - 1)).roundToInt()) else showPermille((f * PERMILLE).roundToInt())
    }

    /** A new page is loading: its pages are not counted yet. */
    fun reset() { pages = 0; pageAt = 0 }

    /** The page script's "count|page" report. */
    fun pagesReported(title: String?) {
        val (n, i) = title?.split('|')?.takeIf { it.size == 2 }?.map { it.toIntOrNull() ?: return } ?: return
        val first = pages == 0
        pages = n.coerceAtLeast(1); pageAt = i.coerceIn(0, pages - 1)
        onPosition(pageAt, pages)
        if (first) restoreAt?.let { restoreAt = null; showFraction(it) }
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
    enterAt: Float?,
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
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            ReaderWebView(ctx).apply {
                // Wrapping its content, a WebView measures 100vh by the content, which breaks the paged columns.
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
                        // A colour or layout change reloads the same text, so the old fraction still points at about the same place.
                        // Pages restore once the page script has counted them.
                        if (paged) return
                        val f = restoreAt ?: return
                        restoreAt = null
                        view.postDelayed({ showFraction(f) }, 80)
                    }
                }
                val slop = ViewConfiguration.get(ctx).scaledTouchSlop
                var downX = 0f
                var downY = 0f
                var swiping = false
                var moved = false
                var atTop = false
                var atBottom = false
                val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // Paged, a tap near either side turns the page; anywhere else shows or hides the islands.
                        val third = width / 4f
                        when {
                            turnsWhole && e.x < third -> turn(-1)
                            turnsWhole && e.x > width - third -> turn(1)
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
                            downX = e.x; downY = e.y; swiping = false; moved = false
                            atTop = if (view == "horizontal") !canScrollHorizontally(-1) else !canScrollVertically(-1)
                            atBottom = if (view == "horizontal") !canScrollHorizontally(1) else !canScrollVertically(1)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.x - downX
                            if (!moved && (abs(dx) > slop || abs(e.y - downY) > slop)) { moved = true; onMoveNow.value() }
                            if (turnsWhole && !swiping && abs(dx) > slop && abs(dx) > abs(e.y - downY)) {
                                swiping = true
                                // The page under the finger lets go of the press, so no link fires on lifting.
                                MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { v.onTouchEvent(it) }.recycle()
                            }
                        }
                        MotionEvent.ACTION_UP -> when {
                            swiping -> turn(if (e.x < downX) 1 else -1)
                            // Scrolling, a pull on past either end of the part opens the next or the previous one.
                            !turnsWhole && atBottom && (if (view == "horizontal") downX - e.x else downY - e.y) > slop * 4 -> onEdgeNow.value(1)
                            !turnsWhole && atTop && (if (view == "horizontal") e.x - downX else e.y - downY) > slop * 4 -> onEdgeNow.value(-1)
                        }
                    }
                    // One or two pages at a time, the WebView never scrolls itself: pages only turn whole.
                    turnsWhole && (swiping || e.actionMasked == MotionEvent.ACTION_MOVE)
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
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
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
