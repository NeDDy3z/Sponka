package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathLlmTest {
    @Test fun endpointAddsChatCompletionsOnce() {
        assertEquals("https://api.openai.com/v1/chat/completions", MathLlm.endpoint("https://api.openai.com/v1/"))
        assertEquals("http://box:11434/v1/chat/completions", MathLlm.endpoint(" http://box:11434/v1/chat/completions "))
    }

    @Test fun requestCarriesPromptAndImage() {
        val r = MathLlm.request("gpt-4o-mini", "read it", "AAAA")
        assertEquals("gpt-4o-mini", r.getString("model"))
        val content = r.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("read it", content.getJSONObject(0).getString("text"))
        assertEquals("data:image/png;base64,AAAA", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test fun replyDropsFencesAndDelimiters() {
        fun body(text: String) = """{"choices":[{"message":{"role":"assistant","content":${org.json.JSONObject.quote(text)}}}]}"""
        assertEquals("y=2x-3", MathLlm.reply(body("y=2x-3")))
        assertEquals("\\frac{a}{b}", MathLlm.reply(body("```latex\n\\frac{a}{b}\n```")))
        assertEquals("x^2", MathLlm.reply(body("\$x^2\$")))
    }

    @Test fun errorMessageUsesServerText() {
        assertEquals("HTTP 401: Incorrect API key", MathLlm.errorMessage(401, """{"error":{"message":"Incorrect API key"}}"""))
        assertEquals("HTTP 502", MathLlm.errorMessage(502, "<html>bad gateway</html>"))
    }

    @Test fun onlyRemotePlainHttpIsInsecure() {
        assertTrue(MathLlm.insecure("http://example.com/v1"))
        assertFalse(MathLlm.insecure("https://example.com/v1"))
        assertFalse(MathLlm.insecure("http://192.168.1.5:11434/v1"))
        assertFalse(MathLlm.insecure("http://172.20.0.2/v1"))
        assertFalse(MathLlm.insecure("http://localhost:1234/v1"))
    }
}
