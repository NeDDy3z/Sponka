package com.xnotes.platform

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln

/** The model-independent arithmetic of math recognition: pixels in, beam steps, token ids out. */
internal object MathTokens {

    /** id -> token from a Hugging Face tokenizer.json; BPE keeps its vocab as a map, Unigram as a list. */
    fun vocab(tokenizer: JSONObject): Map<Int, String> {
        val out = HashMap<Int, String>()
        when (val v = tokenizer.getJSONObject("model").get("vocab")) {
            is JSONObject -> for (k in v.keys()) out[v.getInt(k)] = k
            is JSONArray -> for (i in 0 until v.length()) out[i] = v.getJSONArray(i).getString(0)
        }
        tokenizer.optJSONArray("added_tokens")?.let { added ->
            for (i in 0 until added.length()) added.getJSONObject(i).let { out[it.getInt("id")] = it.getString("content") }
        }
        return out
    }

    /** Text of [ids], leaving out every id below [skipBelow] (the model's special tokens). */
    fun decode(ids: List<Long>, vocab: Map<Int, String>, kind: String, skipBelow: Int): String {
        val tokens = ids.filter { it >= skipBelow }.mapNotNull { vocab[it.toInt()] }
        return if (kind == "metaspace") metaspace(tokens) else byteBpe(tokens)
    }

    /** Byte-level BPE: each token character stands for one byte of UTF-8 (GPT-2's table). */
    private fun byteBpe(tokens: List<String>): String {
        val bytes = ByteArrayOutputStream()
        for (t in tokens) for (ch in t) BYTE_OF[ch]?.let { bytes.write(it) }
        return bytes.toString("UTF-8").trim()
    }

    /** Sentencepiece style: U+2581 marks a space, "<0x41>" is a raw byte. */
    private fun metaspace(tokens: List<String>): String {
        val bytes = ByteArrayOutputStream()
        for (t in tokens) {
            val raw = Regex("^<0x([0-9A-Fa-f]{2})>$").find(t)
            if (raw != null) bytes.write(raw.groupValues[1].toInt(16)) else bytes.write(t.replace('\u2581', ' ').toByteArray())
        }
        return bytes.toString("UTF-8").trim()
    }

    private val BYTE_OF: Map<Char, Int> by lazy {
        val bs = ArrayList<Int>()
        bs.addAll('!'.code..'~'.code)
        bs.addAll(0xA1..0xAC)
        bs.addAll(0xAE..0xFF)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        bs.indices.associate { cs[it].toChar() to bs[it] }
    }

    /**
     * One beam step. [logits] is the decoder's [beams, steps, vocab] output; each beam's last row
     * becomes log-probabilities added to its [scores]. Returns the best [width] continuations of
     * each beam as (beam, token, score), best first.
     */
    fun extend(logits: FloatBuffer, vocab: Int, scores: List<Double>, steps: Int, width: Int): List<Triple<Int, Long, Double>> {
        val out = ArrayList<Triple<Int, Long, Double>>()
        for (k in scores.indices) {
            val base = (k * steps + steps - 1) * vocab
            var max = Float.NEGATIVE_INFINITY
            for (v in 0 until vocab) max = maxOf(max, logits.get(base + v))
            var sum = 0.0
            for (v in 0 until vocab) sum += exp((logits.get(base + v) - max).toDouble())
            val norm = max + ln(sum)
            val best = IntArray(width) { -1 }
            for (v in 0 until vocab) {
                val x = logits.get(base + v)
                var at = width
                while (at > 0 && (best[at - 1] < 0 || logits.get(base + best[at - 1]) < x)) at--
                if (at < width) {
                    for (j in width - 1 downTo at + 1) best[j] = best[j - 1]
                    best[at] = v
                }
            }
            for (v in best) if (v >= 0) out += Triple(k, v.toLong(), scores[k] + logits.get(base + v) - norm)
        }
        return out.sortedByDescending { it.third }
    }

    /**
     * ARGB pixels to a CHW float image, normalized per channel; one channel means grey. Outside
     * [image] (its width and height from the top-left corner), when given, every value is zero.
     */
    fun normalize(px: IntArray, input: MathModel.Input, image: Pair<Int, Int>? = null): FloatArray {
        val plane = px.size
        val out = FloatArray(input.channels * plane)
        for (i in 0 until plane) {
            if (image != null && (i % input.width >= image.first || i / input.width >= image.second)) continue
            val c = px[i]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            if (input.channels == 1) {
                out[i] = ((0.299f * r + 0.587f * g + 0.114f * b) - input.mean[0]) / input.std[0]
            } else {
                out[i] = (r - input.mean[0]) / input.std[0]
                out[plane + i] = (g - input.mean[1]) / input.std[1]
                out[2 * plane + i] = (b - input.mean[2]) / input.std[2]
            }
        }
        return out
    }
}
