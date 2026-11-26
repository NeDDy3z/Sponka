package com.xnotes.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.ReaderKind
import com.xnotes.platform.AndroidText
import com.xnotes.platform.MathRendering
import com.xnotes.platform.ReaderHtml
import com.xnotes.platform.ReaderPrint
import com.xnotes.ui.theme.Palette
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

/** A file open in the read-only reader. */
data class ReaderFile(val uri: String, val name: String)

internal sealed interface ReaderContent {
    class Html(val body: String, val wide: Boolean) : ReaderContent
    object Failed : ReaderContent
}

internal fun readerLabels(context: Context) = ReaderHtml.Labels(
    slide = { n, total -> context.getString(R.string.reader_slide, n, total) },
    notes = context.getString(R.string.reader_notes),
    truncatedRows = { context.getString(R.string.reader_rows_truncated, it) },
    truncatedCells = { r, c -> context.getString(R.string.reader_truncated_cells, r, c) },
    image = context.getString(R.string.reader_image),
    empty = context.getString(R.string.reader_empty),
)

internal fun readerColors(p: Palette, background: String = Rgba.toHex(p.paper)) = ReaderHtml.Colors(
    background, Rgba.toHex(p.text), Rgba.toHex(p.textDim), Rgba.toHex(p.accent), Rgba.toHex(raisedOnPaper(p)), Rgba.toHex(p.border),
)

/**
 * Prints a reader file as the light reader shows it, on white paper, into an A4 PDF in the
 * cache (turned sideways for wide content), for reading or turning into a note; null when it will
 * not open. Without [to], each call replaces the previous one's file.
 */
internal suspend fun readerFileToPdf(context: Context, editor: Editor, file: ReaderFile, to: File? = null, preview: Boolean = false): File? {
    val content = withContext(Dispatchers.IO) { runCatching { readerHtmlBody(context, editor, file, readerLabels(context)) }.getOrNull() } ?: return null
    // A preview needs the first page only, so a long book prints just its start.
    val body = if (preview && content.body.length > PREVIEW_CHARS) content.body.substring(0, content.body.indexOf('<', PREVIEW_CHARS).takeIf { it > 0 } ?: PREVIEW_CHARS) else content.body
    val html = ReaderHtml.printPage(body, readerColors(editor.readerPalette(dark = false), "#ffffff"), content.wide)
    val a4 = android.print.PrintAttributes.MediaSize.ISO_A4
    val media = if (content.wide) a4.asLandscape() else a4
    val out = to ?: withContext(Dispatchers.IO) {
        File(context.cacheDir, "convert").apply { deleteRecursively(); mkdirs() }.resolve("${file.name.substringBeforeLast('.')}.pdf")
    }
    return out.takeIf { ReaderPrint.toPdf(context, html, media, out) }
}

/** How much of a file's HTML a preview prints: a few pages' worth. */
private const val PREVIEW_CHARS = 12_000

/** The theme surface that stands out from the page enough to show code blocks and table headers. */
private fun raisedOnPaper(p: Palette): Rgba {
    fun luma(c: Rgba) = (c.r * 299 + c.g * 587 + c.b * 114) / 1000
    val gaps = listOf(p.panel, p.bg, p.surface, p.surfaceHi).map { it to abs(luma(it) - luma(p.paper)) }
    return (gaps.filter { it.second >= 14 }.minByOrNull { it.second } ?: gaps.maxBy { it.second }).first
}

/** The HTML body of a reader file; null for an unknown kind. */
internal fun readerHtmlBody(context: Context, editor: Editor, file: ReaderFile, labels: ReaderHtml.Labels): ReaderContent.Html? {
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
        ReaderKind.TXT -> ReaderContent.Html(com.xnotes.platform.OfficeHtml.text(readText(context, uri), labels), wide = false)
        ReaderKind.RTF -> ReaderContent.Html(com.xnotes.platform.OfficeHtml.rtf(readText(context, uri), labels), wide = false)
        ReaderKind.TEX -> ReaderContent.Html(com.xnotes.platform.OfficeHtml.tex(readText(context, uri), labels), wide = false)
        ReaderKind.XML -> ReaderContent.Html(com.xnotes.platform.OfficeHtml.xml(readText(context, uri), labels), wide = true)
        ReaderKind.ODT, ReaderKind.ODP -> ReaderContent.Html(withZip(context, uri) { com.xnotes.platform.OfficeHtml.odt(it, ::newParser, labels) }, wide = false)
        ReaderKind.PPT -> ReaderContent.Html(com.xnotes.platform.OfficeHtml.ppt(readBytes(context, uri), labels), wide = false)
        ReaderKind.EPUB -> ReaderContent.Html(withZip(context, uri) { com.xnotes.platform.BookHtml.epub(it, labels) }, wide = false)
        ReaderKind.FB2 -> ReaderContent.Html(com.xnotes.platform.BookHtml.fb2(readBytes(context, uri), ::newParser, labels), wide = false)
        ReaderKind.MOBI -> ReaderContent.Html(com.xnotes.platform.BookHtml.mobi(readBytes(context, uri), labels), wide = false)
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
