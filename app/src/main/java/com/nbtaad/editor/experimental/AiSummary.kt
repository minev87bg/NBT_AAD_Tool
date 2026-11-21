package com.nbtaad.editor.experimental

import android.content.Context
import com.nbtaad.editor.log.AppLog
import com.nbtaad.editor.net.Answered
import com.nbtaad.editor.net.Internet
import java.net.URL

/** The person's own Anthropic key, kept in the app's private storage on this phone. It is never written to the log. */
class AiKeyStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ai_summary", Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun set(key: String) {
        prefs.edit().putString(KEY, key.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "anthropic_key"
    }
}

/** Asks Claude (Anthropic's Messages API) for a summary of a page. */
object AiClient {
    class AiException(message: String) : Answered(message)

    /** The summary as Markdown. Throws [AiException] with a sentence for the person when Anthropic refuses. */
    fun summarize(ctx: Context, key: String, topic: Topic, comments: List<Comment>): String {
        val body = AiLogic.requestJson(topic, comments)
        AppLog.i("AI", "Asking ${AiLogic.MODEL} to summarize #${topic.number}: ${body.length} characters, ${comments.size} comments")
        return send(ctx, key, body, "Summary")
    }

    /** One answer of the API: its text (null when it had none) and why it stopped. */
    private class Reply(val text: String?, val stop: String)

    /**
     * Sends one prepared request ([bodyJson]) to the Messages API and returns the text of the answer. An answer without
     * any text happens now and then (the same request works the next time), so it is asked for once more before the
     * person is told; the reason it stopped is written to the log either way.
     */
    fun send(ctx: Context, key: String, bodyJson: String, what: String): String {
        val body = bodyJson.toByteArray(Charsets.UTF_8)
        val started = System.currentTimeMillis()
        var reply = once(ctx, key, body)
        if (reply.text == null) {
            AppLog.w("AI", "$what: Claude answered without any text (stop reason \"${reply.stop}\"), asking once more")
            reply = once(ctx, key, body)
        }
        val text = reply.text ?: run {
            AppLog.w("AI", "$what: again no text (stop reason \"${reply.stop}\")")
            throw AiException(AiLogic.emptyAnswerText(reply.stop))
        }
        AppLog.i("AI", "$what written in ${System.currentTimeMillis() - started} ms, ${text.length} characters")
        return text
    }

    private fun once(ctx: Context, key: String, body: ByteArray): Reply = Internet.run(ctx) { open ->
        val c = open(URL(AiLogic.API_URL))
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 90_000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", AiLogic.API_VERSION)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val answer = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            if (code !in 200..299) {
                AppLog.w("AI", "Anthropic answered $code")
                throw AiException(AiLogic.errorText(code, answer))
            }
            val stop = AiLogic.stopReason(answer)
            val out = AiLogic.answerText(answer)
            Reply(if (out != null && stop == "max_tokens") "$out\n\n_(The answer was cut short.)_" else out, stop)
        } finally {
            c.disconnect()
        }
    }
}
