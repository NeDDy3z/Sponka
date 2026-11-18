package com.xnotes.platform

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.util.Base64
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Stroke
import com.xnotes.core.text.RecognizedLatex
import com.xnotes.settings.LiveSettings
import com.xnotes.settings.SettingsRepository
import com.xnotes.sync.SecureBlob
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handwriting to LaTeX through an LLM the user points at: any OpenAI-compatible chat completions
 * API (OpenAI, Gemini, Anthropic, OpenRouter, Ollama, LM Studio...). URL, model and prompt are
 * synced settings; the API key stays encrypted on this device.
 */
object MathLlm {

    const val DEFAULT_PROMPT = "Transcribe the handwritten math in this image to LaTeX. " +
        "Reply with only the LaTeX, without \$ or \\[ \\] delimiters and without any explanation."

    private fun blob(context: Context) = SecureBlob(context, "math_llm_secure", "n_notes_math_llm_key", "key")

    fun key(context: Context): String = blob(context).load().orEmpty()

    fun setKey(context: Context, key: String) = blob(context).save(key)

    private fun prefs(context: Context) = LiveSettings.get(SettingsRepository(context)).prefs

    fun offline(context: Context): Boolean = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork == null

    fun ready(context: Context): Boolean = prefs(context).let { it.mathLlmUrl.isNotBlank() && it.mathLlmModel.isNotBlank() }

    /** Read [strokes] (inside [bounds]) as LaTeX. Blocks on the network; call off the main thread. */
    fun recognize(context: Context, strokes: List<Stroke>, bounds: Rect): List<String> {
        val bmp = MathOcr.inkBitmap(strokes, bounds, 24)
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bmp.recycle()
        return listOf(recognizePng(context, png)).filter { it.isNotBlank() }
    }

    /** Send one PNG and return the reading; throws with the server's message when it fails. */
    fun recognizePng(context: Context, png: ByteArray): String {
        val p = prefs(context)
        val body = request(p.mathLlmModel.trim(), p.mathLlmPrompt.ifBlank { DEFAULT_PROMPT }, Base64.encodeToString(png, Base64.NO_WRAP))
        val conn = URL(endpoint(p.mathLlmUrl)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            key(context).trim().takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(errorMessage(code, text))
            return reply(text)
        } finally {
            conn.disconnect()
        }
    }

    /** `{base}/chat/completions`, whether or not the user typed the trailing slash or the whole path. */
    internal fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    internal fun request(model: String, prompt: String, pngBase64: String): JSONObject = JSONObject()
        .put("model", model)
        .put("temperature", 0)
        .put("max_tokens", 512)
        .put(
            "messages",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,$pngBase64"))),
                ),
            ),
        )

    /** The reply's LaTeX: the first choice's text without code fences or math delimiters. */
    internal fun reply(json: String): String {
        val content = JSONObject(json).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        val unfenced = content.trim().removePrefix("```latex").removePrefix("```tex").removePrefix("```").removeSuffix("```").trim()
        return RecognizedLatex.clean(unfenced)
    }

    /** A plain-http URL to a host outside the local network, where the key and handwriting travel unencrypted. */
    fun insecure(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!u.startsWith("http://")) return false
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        val private = host == "localhost" || host.endsWith(".local") || host.startsWith("127.") || host.startsWith("10.") ||
            host.startsWith("192.168.") || Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
        return !private
    }

    internal fun errorMessage(code: Int, body: String): String {
        val message = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        return "HTTP $code" + (message?.let { ": $it" } ?: "")
    }
}
