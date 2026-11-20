package com.danielrosehill.canopyvoice

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

data class TurnResult(
    val transcript: String,
    val reply: String,
    val conversationId: String,
    val held: String?,
    val wav: ByteArray?,
)

class VoiceError(message: String) : Exception(message)

/** Client for canopy-voice (Canopy docs/voice.md): POST /v1/turn/audio and POST /v1/turn. Blocking; call off the main thread. */
class VoiceClient(private val baseUrl: String, private val token: String) {

    private fun open(path: String): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code != 200) {
            val body = (c.errorStream ?: c.inputStream)?.bufferedReader()?.readText().orEmpty()
            throw VoiceError("HTTP $code ${body.take(300)}")
        }
    }

    /**
     * Audio turn. The server returns the spoken reply as WAV with the text in headers; its JSON mode carries no
     * audio, so `held` is not available here (a held action is read back as a question anyway).
     */
    fun audioTurn(audio: File, conversationId: String?): TurnResult {
        val boundary = "----canopyvoice" + UUID.randomUUID().toString().replace("-", "")
        val c = open("/v1/turn/audio")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { os ->
            fun field(name: String, value: String) {
                os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            if (conversationId != null) field("conversation_id", conversationId)
            field("reply", "wav")
            os.write(("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"turn.m4a\"\r\n" +
                "Content-Type: audio/mp4\r\n\r\n").toByteArray())
            audio.inputStream().use { it.copyTo(os) }
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        if (c.responseCode == 422) throw VoiceError("Didn't catch that")
        check(c)
        val ct = c.contentType.orEmpty()
        if (ct.startsWith("audio/")) {
            fun h(name: String) = c.getHeaderField(name)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
            val wav = c.inputStream.use { it.readBytes() }
            return TurnResult(h("X-Transcript"), h("X-Reply-Text"), h("X-Conversation-Id"), null, wav)
        }
        // JSON comes back when the reply is empty or the server has no TTS.
        return parse(c.inputStream.bufferedReader().readText())
    }

    fun textTurn(text: String, conversationId: String?): TurnResult {
        val c = open("/v1/turn")
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("text", text).put("speak", true)
        if (conversationId != null) body.put("conversation_id", conversationId)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        check(c)
        return parse(c.inputStream.bufferedReader().readText(), text)
    }

    private fun parse(raw: String, said: String = ""): TurnResult {
        val j = JSONObject(raw)
        val audio = j.optString("audio_b64", "")
        val held = if (j.isNull("held")) null else j.optString("held").ifEmpty { null }
        return TurnResult(
            transcript = j.optString("transcript", said),
            reply = j.optString("reply", ""),
            conversationId = j.optString("conversation_id", ""),
            held = held,
            wav = if (audio.isNotEmpty()) Base64.getDecoder().decode(audio) else null,
        )
    }
}
