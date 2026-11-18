package com.xnotes.platform

import org.json.JSONArray
import org.json.JSONObject

/**
 * One handwritten-maths model as its release's model.json describes it: what the picker shows
 * (description, license, size, RAM, speed), which files to fetch and how to run them. The app
 * keeps a copy next to the installed files, so all of it works offline.
 */
class MathModel(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val trainedOn: String,
    val license: String,
    val website: String?,
    val recommendedRamMb: Int,
    val minRamMb: Int,
    /** Seconds per average formula on a mid-range phone, as measured for the release. */
    val avgSeconds: Double,
    val files: List<File>,
    /** "encoder-decoder" (two graphs, decoded here) or "end-to-end" (one graph, image in, token ids out). */
    val pipeline: String,
    val input: Input,
    val decode: Decode,
    /** "byte-bpe" (GPT-2 style byte tokens) or "metaspace" (sentencepiece style, U+2581 for a space). */
    val tokenizer: String,
    val onnx: Onnx,
    /** The model.json this was read from, with each file's download URL filled in. */
    val json: JSONObject,
) {
    class File(val name: String, val size: Long, val sha256: String, val installedSize: Long, val url: String?)

    /**
     * [keepAspect] fits the image instead of squeezing it; [padding] says where it then sits and
     * what fills the rest. [margin] is the white border kept around the ink, in render pixels.
     */
    class Input(
        val width: Int,
        val height: Int,
        val channels: Int,
        val mean: FloatArray,
        val std: FloatArray,
        val keepAspect: Boolean,
        val padding: Padding = Padding.CENTER_WHITE,
        val margin: Int = 24,
    )

    enum class Padding {
        CENTER_WHITE,

        /** In the top-left corner, the rest zero after normalizing (the mean grey), as TexTeller trained. */
        TOP_LEFT_MEAN,

        /** Centered on black, as PP-FormulaNet trained. */
        CENTER_BLACK,
    }

    class Decode(val bos: Long, val eos: Long, val skipBelow: Int, val maxTokens: Int, val beam: Int)

    class Onnx(
        val encoder: String,
        val decoder: String,
        val model: String,
        val pixels: String,
        val ids: String,
        val hidden: String,
    )

    val downloadBytes: Long get() = files.sumOf { it.size }
    val installedBytes: Long get() = files.sumOf { it.installedSize }

    companion object {

        fun parse(o: JSONObject): MathModel {
            val input = o.optJSONObject("input") ?: JSONObject()
            val decode = o.optJSONObject("decode") ?: JSONObject()
            val onnx = o.optJSONObject("onnx") ?: JSONObject()
            val channels = input.optInt("channels", 3)
            val files = o.getJSONArray("files").let { arr ->
                (0 until arr.length()).map { i ->
                    val f = arr.getJSONObject(i)
                    val size = f.getLong("size")
                    File(f.getString("name"), size, f.getString("sha256"), f.optLong("installedSize", size), f.optString("url").ifEmpty { null })
                }
            }
            return MathModel(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                version = o.optString("version"),
                description = o.optString("description"),
                trainedOn = o.optString("trainedOn"),
                license = o.optString("license"),
                website = o.optString("website").ifEmpty { null },
                recommendedRamMb = o.optInt("recommendedRamMb", 0),
                minRamMb = o.optInt("minRamMb", 0),
                avgSeconds = o.optDouble("avgSeconds", 0.0),
                files = files,
                pipeline = o.optString("pipeline", "encoder-decoder"),
                input = Input(
                    input.optInt("width", 384),
                    input.optInt("height", 384),
                    channels,
                    floats(input.optJSONArray("mean"), channels, 0.5f),
                    floats(input.optJSONArray("std"), channels, 0.5f),
                    input.optBoolean("keepAspect", false),
                    when (input.optString("padding")) {
                        "top-left-mean" -> Padding.TOP_LEFT_MEAN
                        "center-black" -> Padding.CENTER_BLACK
                        else -> Padding.CENTER_WHITE
                    },
                    input.optInt("margin", 24),
                ),
                decode = Decode(
                    decode.optLong("bos", 1),
                    decode.optLong("eos", 2),
                    decode.optInt("skipBelow", 0),
                    decode.optInt("maxTokens", 256),
                    decode.optInt("beam", 4).coerceIn(1, 8),
                ),
                tokenizer = o.optString("tokenizer", "byte-bpe"),
                onnx = Onnx(
                    onnx.optString("encoder", "encoder_model.onnx"),
                    onnx.optString("decoder", "decoder_model.onnx"),
                    onnx.optString("model", "model.onnx"),
                    onnx.optString("pixels", "pixel_values"),
                    onnx.optString("ids", "input_ids"),
                    onnx.optString("hidden", "encoder_hidden_states"),
                ),
                json = o,
            )
        }

        private fun floats(arr: JSONArray?, n: Int, def: Float) =
            FloatArray(n) { i -> arr?.optDouble(minOf(i, arr.length() - 1), def.toDouble())?.toFloat() ?: def }

        /**
         * The model.json asset of each release in a GitHub releases listing, newest release first,
         * as (release tag, model.json URL, name -> asset URL). Releases without one are skipped.
         */
        fun releaseAssets(releases: JSONArray): List<Triple<String, String, Map<String, String>>> =
            (0 until releases.length()).map { releases.getJSONObject(it) }
                .filter { !it.optBoolean("draft") }
                .mapNotNull { r ->
                    val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                    val urls = (0 until assets.length()).map { assets.getJSONObject(it) }
                        .associate { it.optString("name") to it.optString("browser_download_url") }
                    val manifest = urls["model.json"] ?: return@mapNotNull null
                    Triple(r.optString("tag_name"), manifest, urls)
                }

        /** [model] (a release's model.json) with each file's URL taken from that release's [assets]. */
        fun withUrls(model: JSONObject, assets: Map<String, String>): JSONObject {
            val files = model.getJSONArray("files")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                assets[f.getString("name")]?.let { f.put("url", it) }
            }
            return model
        }

        /** "1.10" is newer than "1.9": numbers compare as numbers, missing parts as zero. */
        fun newer(a: String, b: String): Boolean {
            val x = a.split('.', '-').mapNotNull { it.toIntOrNull() }
            val y = b.split('.', '-').mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}
