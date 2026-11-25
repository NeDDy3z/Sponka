package com.xnotes.core.util

/**
 * Files the explorer lists and syncs but only opens in the read-only reader. Only [DocumentKind]
 * files are ever edited; these are shown as they are and never written back.
 */
enum class ReaderKind(val label: String, vararg val extensions: String) {
    MARKDOWN("MD", "md", "markdown"),
    DOCX("DOCX", "docx"),
    PPTX("PPTX", "pptx"),
    XLSX("XLSX", "xlsx"),
    CSV("CSV", "csv"),
    TXT("TXT", "txt"),
    RTF("RTF", "rtf"),
    XML("XML", "xml"),
    ODT("ODT", "odt"),
    PPT("PPT", "ppt"),
    TEX("TEX", "tex"),
    EPUB("EPUB", "epub"),
    FB2("FB2", "fb2"),
    MOBI("MOBI", "mobi", "prc", "azw", "azw3"),
    IMAGE("IMG", "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg"),
    ;

    companion object {
        fun ofName(name: String): ReaderKind? {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty() || ext == name.lowercase()) return null
            return entries.firstOrNull { ext in it.extensions }
        }

        fun isReadable(name: String): Boolean = ofName(name) != null

        /** [name] without the extension of a document or a reader file, as the explorer shows names unless asked for extensions. */
        fun stripListedSuffix(name: String): String =
            if (isReadable(name)) name.substringBeforeLast('.').ifEmpty { name } else DocumentKind.stripSuffix(name)

        /** Whether [name] is an e-book, which is kept as it is rather than turned into a note's text. */
        fun isBook(name: String): Boolean = ofName(name).let { it == EPUB || it == FB2 || it == MOBI }

        /** Whether the app can do nothing with [name]: listed only when the user asks to see such files. */
        fun isUnsupported(name: String): Boolean = !isListed(name)

        /** The short type a file's tile shows: the reader kind's, else its own extension. */
        fun badge(name: String): String? = ofName(name)?.label
            ?: name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it != name }?.uppercase()

        /** Whether the explorer lists [name] as a file at all: an editable document or a readable one. */
        fun isListed(name: String): Boolean = DocumentKind.isDocument(name) || isReadable(name)
    }
}
