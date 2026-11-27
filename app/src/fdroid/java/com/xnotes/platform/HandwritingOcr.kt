package com.xnotes.platform

import com.xnotes.core.model.Stroke

/** F-Droid stand-in: ML Kit is non-free, so this build has no handwriting OCR and never calls these. */
@Suppress("UNUSED_PARAMETER")
object HandwritingOcr {
    val LANGUAGES: List<String> = emptyList()
    fun downloadedLanguages(): Set<String> = emptySet()
    fun deleteModel(tag: String): Unit = throw UnsupportedOperationException()
    fun ensureModelDownloaded(tag: String): Unit = throw UnsupportedOperationException()
    fun recognize(strokes: List<Stroke>, tag: String): String = throw UnsupportedOperationException()
}
