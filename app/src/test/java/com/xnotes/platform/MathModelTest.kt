package com.xnotes.platform

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer

class MathModelTest {

    private val bundled = File("src/main/assets/math/pix2text-mfr.json").readText()

    @Test fun parsesBundledManifest() {
        val m = MathModel.parse(JSONObject(bundled))
        assertEquals("pix2text-mfr", m.id)
        assertEquals(3, m.files.size)
        assertEquals(87_510_770L + 32_026_253L + 113_168L, m.downloadBytes)
        assertEquals(384, m.input.width)
        assertEquals(5, m.decode.skipBelow)
        assertEquals("byte-bpe", m.tokenizer)
        assertTrue(m.files.all { it.url!!.startsWith("https://github.com/NeDDy3z/Sponka-Math-Models/releases/download/pix2text-mfr-v1.5/") })
    }

    @Test fun releaseListingGivesManifestsAndFileUrls() {
        val releases = JSONArray(
            """[
              {"tag_name":"texteller-v3.0","draft":false,"assets":[
                {"name":"model.json","browser_download_url":"https://x/t/model.json"},
                {"name":"encoder_model.onnx","browser_download_url":"https://x/t/encoder_model.onnx"}]},
              {"tag_name":"onnxruntime-v1.30.0","draft":false,"assets":[{"name":"libonnxruntime.so.gz","browser_download_url":"https://x/r"}]},
              {"tag_name":"wip","draft":true,"assets":[{"name":"model.json","browser_download_url":"https://x/w"}]}
            ]""",
        )
        val found = MathModel.releaseAssets(releases)
        assertEquals(listOf("texteller-v3.0"), found.map { it.first })
        val model = MathModel.withUrls(
            JSONObject("""{"id":"texteller","files":[{"name":"encoder_model.onnx","size":1,"sha256":"a"}]}"""),
            found[0].third,
        )
        assertEquals("https://x/t/encoder_model.onnx", MathModel.parse(model).files[0].url)
    }

    @Test fun versionsCompareAsNumbers() {
        assertTrue(MathModel.newer("1.10", "1.9"))
        assertFalse(MathModel.newer("1.5", "1.5"))
        assertTrue(MathModel.newer("3.0.1", "3.0"))
    }

    @Test fun decodesBothTokenizers() {
        val bpe = MathTokens.vocab(JSONObject("""{"model":{"vocab":{"<s>":0,"\\":5,"frac":6,"\u0120x":7}}}"""))
        assertEquals("\\frac x", MathTokens.decode(listOf(0, 5, 6, 7), bpe, "byte-bpe", 5))
        val uni = MathTokens.vocab(JSONObject("""{"model":{"vocab":[["<s>",0],["\u2581x",0],["^",0],["<0x32>",0]]},"added_tokens":[{"id":4,"content":"</s>"}]}"""))
        assertEquals("x^2", MathTokens.decode(listOf(0, 1, 2, 3), uni, "metaspace", 1))
        assertEquals("</s>", uni[4])
    }

    @Test fun beamStepKeepsBestContinuationsPerBeam() {
        // Two beams, one step, vocab of 3. Beam 0 prefers token 2, beam 1 token 0.
        val logits = FloatBuffer.wrap(floatArrayOf(0f, 1f, 5f, 4f, 0f, 1f))
        val next = MathTokens.extend(logits, 3, listOf(0.0, -10.0), 1, 2)
        assertEquals(listOf(0 to 2L, 0 to 1L, 1 to 0L, 1 to 2L), next.map { it.first to it.second })
        assertTrue(next.zipWithNext().all { (a, b) -> a.third >= b.third })
    }

    @Test fun normalizesGreyAndColour() {
        val white = intArrayOf(0xFFFFFFFF.toInt())
        val grey = MathModel.Input(1, 1, 1, floatArrayOf(0.5f), floatArrayOf(0.5f), false)
        assertEquals(1f, MathTokens.normalize(white, grey)[0], 1e-4f)
        val rgb = MathModel.Input(1, 1, 3, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f), false)
        assertEquals(listOf(1f, 1f, 1f), MathTokens.normalize(white, rgb).toList())
        val wide = MathModel.Input(2, 1, 1, floatArrayOf(0.5f), floatArrayOf(0.5f), true, MathModel.Padding.TOP_LEFT_MEAN)
        assertEquals(listOf(1f, 0f), MathTokens.normalize(intArrayOf(-1, -1), wide, 1 to 1).toList())
        assertEquals(MathModel.Padding.TOP_LEFT_MEAN, MathModel.parse(JSONObject("""{"id":"t","files":[],"input":{"padding":"top-left-mean"}}""")).input.padding)
        val uni = MathModel.parse(JSONObject("""{"id":"u","files":[],"input":{"padding":"center-black","margin":0}}""")).input
        assertEquals(MathModel.Padding.CENTER_BLACK, uni.padding)
        assertEquals(0, uni.margin)
    }
}
