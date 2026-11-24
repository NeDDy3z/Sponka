package com.xnotes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Slider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.ReaderKind
import com.xnotes.platform.AndroidText
import com.xnotes.platform.MathRendering
import com.xnotes.platform.ReaderHtml
import com.xnotes.platform.ReaderPrint
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/** A file open in the read-only reader. */
data class ReaderFile(val uri: String, val name: String)

private sealed interface ReaderContent {
    class Html(val body: String, val wide: Boolean) : ReaderContent
    object Failed : ReaderContent
}

/**
 * The read-only reader over the explorer: Markdown, Word, PowerPoint, Excel, CSV and images as HTML in a
 * script-less, offline WebView. The light/dark choice is an app preference and never touches the file.
 */
@Composable
internal fun ReaderScreen(editor: Editor, file: ReaderFile, onClose: () -> Unit, onWikiLink: (String) -> Unit) {
    val context = LocalContext.current
    val appPalette = LocalPalette.current
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    val dark = prefs.readerDark ?: appPalette.isDark
    // The app theme itself, or its other light/dark variant when the reader is switched.
    val palette = remember(appPalette, dark) { if (dark == appPalette.isDark) appPalette else editor.readerPalette(dark) }
    val background = palette.paper
    val text = palette.text
    val labels = readerLabels(context)
    BackHandler(onBack = onClose)

    val content by produceState<ReaderContent?>(null, file.uri) {
        value = withContext(Dispatchers.IO) { runCatching { readerHtmlBody(context, editor, file, labels) }.getOrNull() ?: ReaderContent.Failed }
    }

    val zoom = remember(file.uri) { mutableFloatStateOf(1f) }

    // Find in page: the WebView finds in HTML itself.
    var finding by remember(file.uri) { mutableStateOf(false) }
    var query by remember(file.uri) { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webHits by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(query, finding, content, webView) {
        val q = if (finding) query.trim() else ""
        webView?.let { wv -> if (q.isEmpty()) { wv.clearMatches(); webHits = 0 to 0 } else wv.findAllAsync(q) }
    }
    val findStatus = if (query.isBlank()) "" else "${webHits.first} / ${webHits.second}"
    Column(Modifier.fillMaxSize().background(background.toComposeColor())) {
        // The bar is app chrome like the note toolbar; only the page below follows the reader's light/dark.
        ReaderBar(
            file, dark,
            zoom = zoom.floatValue,
            onZoom = { zoom.floatValue = it },
            onToggleDark = { editor.applyHomePreferences(editor.preferences.copy(readerDark = !dark)) },
            onFind = { finding = !finding },
            onClose = onClose,
        )
        if (finding) FindBar(query, { query = it }, findStatus, { webView?.findNext(false) }, { webView?.findNext(true) }, { finding = false })
        CompositionLocalProvider(LocalPalette provides palette) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val c = content) {
                    null -> ReaderMessage(stringResource(R.string.loading), text)
                    ReaderContent.Failed -> ReaderMessage(stringResource(R.string.reader_open_failed), text)
                    is ReaderContent.Html -> {
                        val html = remember(c, palette) { ReaderHtml.page(c.body, readerColors(palette), c.wide) }
                        ReaderWebView(
                            html, background.toComposeColor(), (zoom.floatValue * 100).roundToInt(),
                            onCreated = { wv ->
                                webView = wv
                                wv.setFindListener { active, count, _ -> webHits = (if (count == 0) 0 else active + 1) to count }
                            },
                        ) { url -> openLink(context, url, onWikiLink) }
                    }
                }
            }
        }
    }
}

/** Built from the note toolbar's own pieces (icons, monospace labels, separators) so the two bars match. */
@Composable
private fun ReaderBar(
    file: ReaderFile,
    dark: Boolean,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onToggleDark: () -> Unit,
    onFind: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(50.dp).background(palette.panel.toComposeColor()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarIcon(XnotesIcons.prev, stringResource(R.string.reader_close), onClick = onClose)
            Label(file.name, Modifier.widthIn(max = 160.dp))
            Separator()
            Icon(XnotesIcons.lock, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.padding(start = 4.dp).size(16.dp))
            Label(stringResource(R.string.reader_read_only))
            Separator()
            // A pinch can go past the slider's range; the slider then rests at its nearest end.
            Slider(
                value = zoom.coerceIn(ZOOM_STEPS.first(), ZOOM_STEPS.last()),
                onValueChange = { v -> onZoom(ZOOM_STEPS.minBy { abs(it - v) }) },
                valueRange = ZOOM_STEPS.first()..ZOOM_STEPS.last(),
                steps = ZOOM_STEPS.size - 2,
                modifier = Modifier.width(140.dp),
            )
            Label("${(zoom * 100).roundToInt()}%", Modifier.widthIn(min = 44.dp))
            ToolbarIcon(Icons.Outlined.RestartAlt, stringResource(R.string.reader_zoom_reset), enabled = zoom != 1f) { onZoom(1f) }
        }
        ToolbarIcon(XnotesIcons.search, stringResource(R.string.find_in_page), onClick = onFind)
        ToolbarIcon(
            if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            stringResource(if (dark) R.string.reader_switch_to_light else R.string.reader_switch_to_dark),
            onClick = onToggleDark,
        )
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun ReaderMessage(message: String, text: Rgba) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = text.toComposeColor(), fontSize = 15.sp)
    }
}

@Composable
private fun ReaderWebView(html: String, background: Color, textZoom: Int, onCreated: (WebView) -> Unit, onLink: (String) -> Unit) {
    val onLinkNow = rememberUpdatedState(onLink)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                // Scripts, file access and the network stay off: the page is only the file's own content.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("about:blank#")) return false
                        onLinkNow.value(url)
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // A colour change reloads the same layout, so the old offset still points at the same text.
                        val y = (view.getTag(R.id.reader_scroll) as? Int) ?: return
                        view.postDelayed({ view.scrollTo(0, y) }, 60)
                    }
                }
                onCreated(this)
            }
        },
        update = { wv ->
            wv.setBackgroundColor(background.toArgb())
            if (wv.settings.textZoom != textZoom) wv.settings.textZoom = textZoom
            if (wv.tag != html) {
                if (wv.tag != null) wv.setTag(R.id.reader_scroll, wv.scrollY)
                wv.tag = html
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun readerLabels(context: Context) = ReaderHtml.Labels(
    slide = { n, total -> context.getString(R.string.reader_slide, n, total) },
    notes = context.getString(R.string.reader_notes),
    truncatedRows = { context.getString(R.string.reader_rows_truncated, it) },
    truncatedCells = { r, c -> context.getString(R.string.reader_truncated_cells, r, c) },
    image = context.getString(R.string.reader_image),
    empty = context.getString(R.string.reader_empty),
)

private fun readerColors(p: Palette, background: String = Rgba.toHex(p.paper)) = ReaderHtml.Colors(
    background, Rgba.toHex(p.text), Rgba.toHex(p.textDim), Rgba.toHex(p.accent), Rgba.toHex(raisedOnPaper(p)), Rgba.toHex(p.border),
)

/**
 * Prints a reader file as the light reader shows it (on white paper) into a PDF in the cache,
 * for turning into a note; null when it will not open. Without [to], each call replaces the previous one's file.
 */
internal suspend fun readerFileToPdf(context: Context, editor: Editor, file: ReaderFile, to: File? = null): File? {
    val content = withContext(Dispatchers.IO) { runCatching { readerHtmlBody(context, editor, file, readerLabels(context)) }.getOrNull() } ?: return null
    val html = ReaderHtml.printPage(content.body, readerColors(editor.readerPalette(dark = false), "#ffffff"), content.wide)
    val out = to ?: withContext(Dispatchers.IO) {
        File(context.cacheDir, "convert").apply { deleteRecursively(); mkdirs() }.resolve("${file.name.substringBeforeLast('.')}.pdf")
    }
    return out.takeIf { ReaderPrint.toPdf(context, html, landscape = content.wide, out) }
}

/** The theme surface that stands out from the page enough to show code blocks and table headers. */
private fun raisedOnPaper(p: Palette): Rgba {
    fun luma(c: Rgba) = (c.r * 299 + c.g * 587 + c.b * 114) / 1000
    val gaps = listOf(p.panel, p.bg, p.surface, p.surfaceHi).map { it to abs(luma(it) - luma(p.paper)) }
    return (gaps.filter { it.second >= 14 }.minByOrNull { it.second } ?: gaps.maxBy { it.second }).first
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

private val ZOOM_STEPS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f)

/** The HTML body of a reader file; null for an unknown kind. */
private fun readerHtmlBody(context: Context, editor: Editor, file: ReaderFile, labels: ReaderHtml.Labels): ReaderContent.Html? {
    val uri = Uri.parse(file.uri)
    return when (ReaderKind.ofName(file.name)) {
        ReaderKind.MARKDOWN -> ReaderContent.Html(
            ReaderHtml.markdown(readText(context, uri), labels, ::readerMath) { readLinkedImage(context, editor, uri, it) }, wide = false,
        )
        ReaderKind.IMAGE -> ReaderContent.Html(ReaderHtml.image(readBytes(context, uri), file.name), wide = false)
        ReaderKind.CSV -> ReaderContent.Html(ReaderHtml.csv(readText(context, uri), labels), wide = true)
        ReaderKind.DOCX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.docx(it, ::newParser, labels) }, wide = false)
        ReaderKind.PPTX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.pptx(it, ::newParser, labels) }, wide = false)
        ReaderKind.XLSX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.xlsx(it, ::newParser, labels) }, wide = true)
        null -> null
    }
}

// Painted black and used as a mask, so the formula takes the reader's text colour in either theme.
private fun readerMath(latex: String, display: Boolean): String? {
    // The box comes back in page px, so ask for the size that makes it come out in CSS px.
    val (bmp, box) = MathRendering.formulaBitmap(latex, READER_MATH_PX / AndroidText.POINTS_TO_PX, Rgba(0, 0, 0, 255), display) ?: return null
    val png = java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    bmp.recycle()
    return ReaderHtml.mathHtml(latex, png, box.width, box.ascent, box.descent, display)
}

// The reader's body font size in CSS px.
private const val READER_MATH_PX = 17.0

/** Office XML is read with prefixed names (w:p, r:id), which the converters match on. */
private fun newParser(): XmlPullParser =
    android.util.Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }

private fun <T> withZip(context: Context, uri: Uri, block: (ReaderHtml.ZipRead) -> T): T {
    val temp = File.createTempFile("reader", ".zip", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("cannot read file")
        return ZipFile(temp).use { zip ->
            block(ReaderHtml.ZipRead { path -> zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } })
        }
    } finally {
        temp.delete()
    }
}

/**
 * An image a Markdown file at [md] links to: [path] beside it first (tree ids are paths on local storage),
 * then any file of that name in the vault, the way Obsidian finds attachments.
 */
private fun readLinkedImage(context: Context, editor: Editor, md: Uri, path: String): ByteArray? {
    val besideMd = runCatching {
        val dir = DocumentsContract.getDocumentId(md).substringBeforeLast('/', "")
        val id = if (path.startsWith("/") || dir.isEmpty()) null else dir.substringBefore(':') + ":" + ReaderHtml.resolvePath(dir.substringAfter(':'), path)
        id?.let { readBytes(context, DocumentsContract.buildDocumentUriUsingTree(md, it)) }
    }.getOrNull()
    if (besideMd != null) return besideMd
    val hit = editor.browseRoot?.let { editor.findLinkedFile(it, path) } ?: return null
    return runCatching { readBytes(context, Uri.parse(hit.documentUri)) }.getOrNull()
}

private fun readBytes(context: Context, uri: Uri): ByteArray =
    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot read file")

/** UTF-8, falling back to the local legacy code page for files that are not (Excel's CSV export). */
private fun readText(context: Context, uri: Uri): String {
    val bytes = readBytes(context, uri)
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        val legacy = if (Locale.getDefault().language in setOf("cs", "sk", "pl", "hu", "sl", "hr")) "windows-1250" else "windows-1252"
        String(bytes, Charset.forName(legacy))
    }
}
