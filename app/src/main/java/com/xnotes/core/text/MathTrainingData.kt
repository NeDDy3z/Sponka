package com.xnotes.core.text

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the math models learned from this user, as one mergeable record: every training
 * formula they wrote (kept so any model can learn from it), which model read each one as what,
 * and per model the corrections learned in use (picked readings, edited formulas).
 *
 * Training is stored as facts rather than counts, so two devices merge by union without counting
 * anything twice; deleted formulas leave their id behind so a merge does not bring them back.
 */
class MathTrainingData {

    /** One written training formula: [xy] holds each stroke as x0, y0, x1, y1, ... in content px. */
    class Sample(
        val id: String,
        val session: String,
        val time: Long,
        val latex: String,
        val xy: List<DoubleArray>,
        val bounds: DoubleArray,
    )

    /** One training run: when it started and how many formulas were written in it. */
    class Session(val id: String, val time: Long, val count: Int)

    private val samples = LinkedHashMap<String, Sample>()
    private val deleted = HashSet<String>()

    /** model -> sample id -> what the model read. */
    private val read = HashMap<String, HashMap<String, String>>()

    /** model -> corrections learned outside training. */
    private val usage = HashMap<String, MathCorrections>()

    fun samples(): List<Sample> = samples.values.toList()

    fun sessions(): List<Session> = samples.values.groupBy { it.session }
        .map { (id, s) -> Session(id, s.minOf { it.time }, s.size) }
        .sortedByDescending { it.time }

    /** Model [model]'s corrections: everything its training taught plus what use taught it. */
    fun corrections(model: String): MathCorrections {
        val c = usage[model]?.let { MathCorrections.fromJson(it.toJson()) } ?: MathCorrections()
        for ((id, raw) in read[model].orEmpty()) samples[id]?.let { c.learn(raw, it.latex) }
        return c
    }

    /** How many training formulas model [model] has learned from. */
    fun trainedCount(model: String): Int = read[model].orEmpty().keys.count { it in samples }

    /** The kept formulas model [model] has not learned from yet. */
    fun unlearned(model: String): List<Sample> = samples.values.filter { it.id !in read[model].orEmpty() }

    fun add(sample: Sample) {
        if (sample.id !in deleted) samples[sample.id] = sample
    }

    /** Model [model] read training formula [sampleId] as [raw]. */
    fun learned(model: String, sampleId: String, raw: String) {
        read.getOrPut(model) { HashMap() }[sampleId] = raw
    }

    fun usage(model: String): MathCorrections = usage.getOrPut(model) { MathCorrections() }

    /** Forget training run [session] for every model. */
    fun deleteSession(session: String) {
        for (s in samples.values.filter { it.session == session }) delete(s.id)
    }

    fun deleteAll() {
        for (id in samples.keys.toList()) delete(id)
    }

    private fun delete(id: String) {
        samples.remove(id)
        deleted += id
        for (m in read.values) m.remove(id)
    }

    /**
     * Take in [other] (another device's copy): formulas and readings join by id, deletions on
     * either side win, and each learned-in-use count keeps the higher of the two.
     */
    fun merge(other: MathTrainingData) {
        deleted += other.deleted
        for (id in deleted) delete(id)
        for (s in other.samples.values) add(s)
        for ((model, m) in other.read) for ((id, raw) in m) if (id !in deleted) read.getOrPut(model) { HashMap() }.putIfAbsent(id, raw)
        for ((model, c) in other.usage) {
            val mine = usage[model]
            usage[model] = if (mine == null) MathCorrections.fromJson(c.toJson()) else MathCorrections.max(mine, c)
        }
    }

    /** Written in a fixed order, so equal data gives equal text and sync can tell nothing changed. */
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put(
            "samples",
            JSONArray(
                samples.values.sortedWith(compareBy({ it.time }, { it.id })).map { s ->
                    JSONObject()
                        .put("id", s.id).put("session", s.session).put("time", s.time).put("latex", s.latex)
                        .put("bounds", JSONArray(s.bounds.toList()))
                        .put("strokes", JSONArray(s.xy.map { JSONArray(it.toList()) }))
                },
            ),
        )
        .put("deleted", JSONArray(deleted.sorted()))
        .put("read", JSONObject(read.mapValues { (_, r) -> JSONObject(r.toSortedMap() as Map<*, *>) }.toSortedMap() as Map<*, *>))
        .put("usage", JSONObject(usage.mapValues { (_, c) -> c.toJson() }.toSortedMap() as Map<*, *>))

    companion object {

        fun fromJson(o: JSONObject): MathTrainingData = MathTrainingData().apply {
            o.optJSONArray("deleted")?.let { d -> for (i in 0 until d.length()) deleted += d.getString(i) }
            o.optJSONArray("samples")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val strokes = s.getJSONArray("strokes")
                    add(
                        Sample(
                            s.getString("id"), s.getString("session"), s.getLong("time"), s.getString("latex"),
                            (0 until strokes.length()).map { k -> strokes.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } } },
                            s.getJSONArray("bounds").let { b -> DoubleArray(b.length()) { b.getDouble(it) } },
                        ),
                    )
                }
            }
            o.optJSONObject("read")?.let { r ->
                for (m in r.keys()) {
                    val row = r.getJSONObject(m)
                    for (id in row.keys()) if (id !in deleted) learned(m, id, row.getString(id))
                }
            }
            o.optJSONObject("usage")?.let { u -> for (m in u.keys()) usage[m] = MathCorrections.fromJson(u.getJSONObject(m)) }
        }
    }
}
