@file:Suppress("DEPRECATION") // Movie is the one GIF player on API 26-27.

package com.xnotes.canvas

import android.graphics.Canvas
import android.graphics.Movie
import android.os.SystemClock
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import java.util.WeakHashMap

/**
 * Animated GIFs on a note's pages. The page caches hold a GIF's first frame like any picture; the
 * view paints the frame due now over it, each frame, the way highlighters are composited.
 */
// shortcut: the moving frame is painted over the page, so ink drawn over a GIF is hidden while it plays and a transparent GIF shows its first frame behind; give the page caches a hole for it if that bothers.
internal object AnimatedGifs {

    /** Each picture's decoded animation, or null for one that does not move. */
    private val movies = WeakHashMap<ImageData, Movie?>()

    /** How often a playing GIF is redrawn. */
    const val FRAME_MS = 40L

    /** [image]'s animation, read once; null unless it is a GIF of more than one frame. */
    fun movieOf(image: ImageData): Movie? = synchronized(movies) {
        if (movies.containsKey(image)) return movies[image]
        val movie = runCatching {
            val head = ByteArray(4)
            val read = image.file.inputStream().use { it.read(head) }
            if (read == 4 && String(head, Charsets.ISO_8859_1) == "GIF8") {
                Movie.decodeFile(image.file.path)?.takeIf { it.duration() > 0 && it.width() > 0 && it.height() > 0 }
            } else null
        }.getOrNull()
        movies[image] = movie
        movie
    }

    /** Paint [item]'s current frame on [canvas], whose matrix maps [item]'s page space. */
    fun drawFrame(canvas: Canvas, item: ImageItem, movie: Movie) {
        movie.setTime((SystemClock.uptimeMillis() % movie.duration()).toInt())
        val rect = item.rect
        // The picture turns by its orientation, is mirrored, then turned by its angle, as the renderers place it.
        val (w, h) = if (item.orientation % 180 != 0) rect.h to rect.w else rect.w to rect.h
        val part = item.part ?: com.xnotes.core.geometry.Rect(0.0, 0.0, 1.0, 1.0)
        val mw = movie.width().toDouble()
        val mh = movie.height().toDouble()
        val sx = w / (part.w * mw)
        val sy = h / (part.h * mh)
        canvas.save()
        canvas.translate(rect.centerX.toFloat(), rect.centerY.toFloat())
        canvas.rotate(Math.toDegrees(item.angle).toFloat())
        canvas.scale(if (item.flip.x) -1f else 1f, if (item.flip.y) -1f else 1f)
        canvas.rotate(item.orientation.toFloat())
        canvas.clipRect((-w / 2).toFloat(), (-h / 2).toFloat(), (w / 2).toFloat(), (h / 2).toFloat())
        canvas.translate((-w / 2 - part.left * mw * sx).toFloat(), (-h / 2 - part.top * mh * sy).toFloat())
        canvas.scale(sx.toFloat(), sy.toFloat())
        movie.draw(canvas, 0f, 0f)
        canvas.restore()
    }
}
