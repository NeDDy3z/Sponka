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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.shadow
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
    // Slides are pages already and do not break into columns, so a deck always scrolls.
    val paged = prefs.readerPaged && ReaderKind.ofName(file.name) !in SLIDE_KINDS
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
    // Paged, a book is shown a part at a time, since laying it all out in columns takes minutes.
    val chunks = remember(content) { (content as? ReaderContent.Html)?.let { ReaderHtml.pageChunks(it.body) } ?: listOf("") }
    var chunk by remember(file.uri) { mutableIntStateOf(0) }
    // Where the next load opens, as a fraction of what it shows; null keeps the place as it was.
    var enterAt by remember { mutableStateOf<Float?>(null) }
    fun showBook(f: Float) {
        val t = f.coerceIn(0f, 1f) * chunks.size
        val target = t.toInt().coerceAtMost(chunks.lastIndex)
        if (target == chunk) webView?.showFraction(t - target) else { enterAt = t - target; chunk = target }
    }
    var shownPaged by remember { mutableStateOf(paged) }
    if (shownPaged != paged && content is ReaderContent.Html) {
        shownPaged = paged
        if (paged) {
            val t = bookAt * chunks.size
            chunk = t.toInt().coerceAtMost(chunks.lastIndex)
            enterAt = t - chunk
        } else {
            enterAt = bookAt
        }
    }
    Column(Modifier.fillMaxSize().background(palette.paper.toComposeColor())) {
        if (finding) FindBar(query, { query = it }, findStatus, { webView?.findNext(false) }, { webView?.findNext(true) }, { finding = false })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            CompositionLocalProvider(LocalPalette provides palette) {
                when (val c = content) {
                    null -> ReaderMessage(stringResource(R.string.loading), palette.text)
                    ReaderContent.Failed -> ReaderMessage(stringResource(R.string.reader_open_failed), palette.text)
                    is ReaderContent.Html -> {
                        val part = chunk.coerceIn(0, chunks.lastIndex)
                        val html = remember(c, palette, paged, part) { ReaderHtml.page(if (paged) chunks[part] else c.body, readerColors(palette), c.wide, paged) }
                        // A paper format shows its page whole in the middle, the app's background round it.
                        val aspect = if (paged) paperAspect(prefs) else null
                        Box(
                            Modifier.fillMaxSize().background((if (aspect != null) appPalette.bg else palette.paper).toComposeColor()),
                            contentAlignment = Alignment.Center,
                        ) {
                            ReaderWebView(
                                html, palette.paper, prefs.readerTextZoom, paged, enterAt,
                                modifier = if (aspect == null) Modifier.fillMaxSize() else Modifier.padding(16.dp).aspectRatio(aspect).shadow(4.dp).border(1.dp, appPalette.border.toComposeColor()),
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
                                    bookAt = if (paged) (part + a / n.toFloat()) / chunks.size else a / PERMILLE.toFloat()
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
                    FileReaderSettingsButton(editor, fullscreen, onToggleFullscreen) { panelOpen = it }
                },
            ) {
                val scrub = Modifier.weight(1f).padding(horizontal = SCRUBBER_ROOM)
                if (paged) {
                    // The arrows and the count go by the part shown; the scrubber spans the whole file.
                    ToolbarIcon(XnotesIcons.prev, stringResource(R.string.previous_page), enabled = at > 0 || chunk > 0) { webView?.turn(-1) }
                    Label("${at + 1} " + stringResource(R.string.page_of_count, count), Modifier.widthIn(min = 64.dp))
                    PageScrubber(
                        scrubTo ?: (bookAt * PERMILLE).roundToInt(), PERMILLE, emptyList(), scrubbing,
                        onScrub = { scrubbing = true; scrubTo = it },
                        onLetGo = {
                            scrubbing = false
                            scrubTo?.let { showBook(it / PERMILLE.toFloat()) }
                            scrubTo = null
                        },
                        modifier = scrub,
                    )
                    ToolbarIcon(XnotesIcons.next, stringResource(R.string.next_page), enabled = at < count - 1 || chunk < chunks.lastIndex) { webView?.turn(1) }
                } else {
                    Label("${at / 10}%", Modifier.padding(start = 12.dp).widthIn(min = 44.dp))
                    PageScrubber(at, PERMILLE, emptyList(), scrubbing, onScrub = { scrubbing = true; at = it; webView?.showPermille(it) }, onLetGo = { scrubbing = false }, modifier = scrub)
                }
            }
        }
    }
}

private const val PERMILLE = 1000

private val SLIDE_KINDS = setOf(ReaderKind.PPTX, ReaderKind.PPT)

/** Width over height of the paper format a book is paged on; null for the screen's own shape. */
private fun paperAspect(p: Preferences): Float? {
    val (w, h) = when (p.readerPageFormat) {
        "a6" -> 105f to 148f
        "a5" -> 148f to 210f
        "a4" -> 210f to 297f
        "letter" -> 216f to 279f
        else -> return null
    }
    return if (p.readerLandscape) h / w else w / h
}

@Composable
private fun ReaderMessage(message: String, text: Rgba) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = text.toComposeColor(), fontSize = 15.sp)
    }
}

/** A WebView that tells how far its content reaches, which paging and the scrubber are worked out from. */
internal class ReaderWebView(context: Context) : WebView(context) {
    var paged = false
    var onPosition: (Int, Int) -> Unit = { _, _ -> }

    /** Hears a page turn run past the first or the last page, -1 or 1. */
    var onEdge: (Int) -> Unit = {}

    /** Where to put the reader once a reload lands, as a fraction of the way through; null to leave it. */
    var restoreAt: Float? = null

    private val rangeX get() = computeHorizontalScrollRange()
    private val rangeY get() = computeVerticalScrollRange()

    fun pageCount(): Int = if (width == 0) 1 else ((rangeX + width / 2) / width).coerceAtLeast(1)

    fun showPage(page: Int) = scrollTo(page.coerceIn(0, pageCount() - 1) * width, 0)

    fun pageNow(): Int = (scrollX.toFloat() / width.coerceAtLeast(1)).roundToInt()

    /** Turn [by] pages, on into the next or the previous part past either end. */
    fun turn(by: Int) {
        val page = pageNow() + by
        if (page < 0 || page >= pageCount()) onEdge(by) else showPage(page)
    }

    fun showPermille(p: Int) = scrollTo(0, ((rangeY - height).coerceAtLeast(0).toLong() * p / PERMILLE).toInt())

    /** How far through the reader is, from 0 to 1. */
    fun fraction(): Float =
        if (paged) pageNow().toFloat() / pageCount()
        else (rangeY - height).let { max -> if (max <= 0) 0f else scrollY.toFloat() / max }

    fun showFraction(f: Float) {
        if (paged) showPage((pageCount() * f).toInt()) else showPermille((f * PERMILLE).roundToInt())
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        // Each frame may follow a scroll or a relayout; the callback only changes state when the numbers do.
        if (paged) {
            val n = pageCount()
            onPosition(pageNow().coerceIn(0, n - 1), n)
        } else {
            val max = rangeY - height
            onPosition(if (max <= 0) 0 else (scrollY.toLong() * PERMILLE / max).toInt().coerceIn(0, PERMILLE), PERMILLE)
        }
    }
}

@Composable
private fun ReaderWebView(
    html: String,
    background: Rgba,
    textZoom: Int,
    pagedMode: Boolean,
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
                // Scripts, file access and the network stay off: the page is only the file's own content.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                this.onPosition = { a, n -> onPositionNow.value(a, n) }
                this.onEdge = { onEdgeNow.value(it) }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("about:blank#")) return false
                        onLinkNow.value(url)
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // A colour or layout change reloads the same text, so the old fraction still points at about the same place.
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
                val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        // Paged, a tap near either side turns the page; anywhere else shows or hides the islands.
                        val third = width / 4f
                        when {
                            paged && e.x < third -> turn(-1)
                            paged && e.x > width - third -> turn(1)
                            else -> onTapNow.value()
                        }
                        return false
                    }
                })
                @SuppressLint("ClickableViewAccessibility")
                setOnTouchListener { v, e ->
                    taps.onTouchEvent(e)
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiping = false; moved = false }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = e.x - downX
                            if (!moved && (abs(dx) > slop || abs(e.y - downY) > slop)) { moved = true; onMoveNow.value() }
                            if (paged && !swiping && abs(dx) > slop && abs(dx) > abs(e.y - downY)) {
                                swiping = true
                                // The page under the finger lets go of the press, so no link fires on lifting.
                                MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.also { v.onTouchEvent(it) }.recycle()
                            }
                        }
                        MotionEvent.ACTION_UP -> if (swiping) turn(if (e.x < downX) 1 else -1)
                    }
                    // Paged, the WebView never scrolls itself: pages only turn whole.
                    paged && (swiping || e.actionMasked == MotionEvent.ACTION_MOVE)
                }
                onCreated(this)
            }
        },
        update = { wv ->
            wv.setBackgroundColor(background.toComposeColor().toArgb())
            if (wv.settings.textZoom != textZoom) wv.settings.textZoom = textZoom
            wv.settings.setSupportZoom(!pagedMode)
            val tag = "$pagedMode|$html"
            if (wv.tag != tag) {
                wv.restoreAt = enterAt ?: if (wv.tag != null) wv.fraction() else null
                onEntered()
                wv.tag = tag
                wv.paged = pagedMode
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
