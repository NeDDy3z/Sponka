package com.xnotes.platform

import android.content.Context
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.MathOrigin
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.text.MathCorrections
import com.xnotes.core.text.MathTrainingData
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The training data of the math models on this device, in one file that settings backups carry
 * and cloud sync merges. Deleting or switching a model never touches it.
 */
object MathTraining {

    private var data: MathTrainingData? = null

    fun file(context: Context) = File(context.filesDir, "math-training/training.json")

    @Synchronized
    private fun data(context: Context): MathTrainingData = data ?: run {
        val f = file(context)
        val loaded = runCatching { MathTrainingData.fromJson(JSONObject(f.readText())) }.getOrNull() ?: MathTrainingData()
        data = loaded
        loaded
    }

    @Synchronized
    private fun save(context: Context) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(data(context).toJson().toString())
        tmp.renameTo(f)
    }

    @Synchronized
    fun corrections(context: Context, model: String): MathCorrections = data(context).corrections(model)

    @Synchronized
    fun trainedCount(context: Context, model: String): Int = data(context).trainedCount(model)

    @Synchronized
    fun sessions(context: Context): List<MathTrainingData.Session> = data(context).sessions()

    @Synchronized
    fun savedCount(context: Context): Int = data(context).samples().size

    @Synchronized
    fun unlearnedCount(context: Context, model: String): Int = data(context).unlearned(model).size

    /** The user picked [meant] for what model [model] read as [raw]. */
    @Synchronized
    fun learn(context: Context, model: String, raw: String, meant: String) {
        data(context).usage(model).learn(raw, meant)
        save(context)
    }

    /** A formula the model made went from [from] to [to] (an edit, or its undo). */
    @Synchronized
    fun relearn(context: Context, origin: MathOrigin, from: String, to: String) {
        val c = data(context).usage(origin.model)
        c.unlearn(origin.raw, from)
        c.learn(origin.raw, to)
        save(context)
    }

    fun newSession(): String = UUID.randomUUID().toString()

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Recognize a training formula and [record] it, in the background and in order, so the user
     * can keep writing; it finishes even if the training screen closes. Nothing read, nothing kept.
     */
    fun trainLater(context: Context, model: String, session: String, latex: String, strokes: List<Stroke>, bounds: Rect) {
        val app = context.applicationContext
        worker.launch {
            val raw = runCatching { MathOcr.recognize(app, model, strokes, bounds).firstOrNull() }.getOrNull()
            if (!raw.isNullOrBlank()) record(app, model, session, latex, strokes, bounds, raw)
        }
    }

    /** In training run [session], the user wrote [latex] as [strokes] and model [model] read it as [raw]. */
    @Synchronized
    fun record(context: Context, model: String, session: String, latex: String, strokes: List<Stroke>, bounds: Rect, raw: String) {
        val id = UUID.randomUUID().toString()
        val xy = strokes.map { s -> DoubleArray(s.sampleCount * 2) { i -> if (i % 2 == 0) s.xAt(i / 2) else s.yAt(i / 2) } }
        val d = data(context)
        d.add(MathTrainingData.Sample(id, session, System.currentTimeMillis(), latex, xy, doubleArrayOf(bounds.x, bounds.y, bounds.w, bounds.h)))
        d.learned(model, id, raw)
        save(context)
    }

    @Synchronized
    fun deleteSession(context: Context, session: String) {
        data(context).deleteSession(session)
        save(context)
    }

    @Synchronized
    fun deleteAll(context: Context) {
        data(context).deleteAll()
        save(context)
    }

    /** Take in another copy of the training data (a backup, or another device's), keeping both sides. */
    @Synchronized
    fun merge(context: Context, json: JSONObject) {
        data(context).merge(MathTrainingData.fromJson(json))
        save(context)
    }

    @Synchronized
    fun json(context: Context): JSONObject = data(context).toJson()

    /** Teach model [model] from every kept formula it has not learned yet; blocks, call off the main thread. */
    fun trainFromSamples(context: Context, model: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val todo = synchronized(this) { data(context).unlearned(model) }
        for ((i, s) in todo.withIndex()) {
            val strokes = s.xy.map { a -> Stroke(Tool.PEN, ToolConfig(), (0 until a.size / 2).map { Sample(a[2 * it], a[2 * it + 1], 1.0) }) }
            val b = s.bounds
            MathOcr.recognize(context, model, strokes, Rect(b[0], b[1], b[2], b[3])).firstOrNull()?.let { raw ->
                synchronized(this) {
                    data(context).learned(model, s.id, raw)
                    save(context)
                }
            }
            onProgress(i + 1, todo.size)
        }
    }
}
