package com.danielrosehill.canopyvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

enum class Phase { Idle, Listening, Thinking, Speaking }

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private lateinit var recorder: Recorder

    private var phase by mutableStateOf(Phase.Idle)
    private var level by mutableIntStateOf(0)
    private var said by mutableStateOf("")
    private var reply by mutableStateOf("")
    private var error by mutableStateOf("")
    private var showSettings by mutableStateOf(false)

    // Follow-ups: the server keeps a conversation for session_ttl_s (180 s by default); stay under it.
    private var conversationId: String? = null
    private var conversationAt = 0L
    private val conversationTtlMs = 170_000L

    private var job: Job? = null
    private var player: MediaPlayer? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else error = "Microphone permission is needed to talk"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        recorder = Recorder(this)
        showSettings = !settings.configured
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Screen() } } }
        if (settings.configured && settings.listenOnOpen) listen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The assistant gesture or the launcher icon while open: start a new turn.
        if (settings.configured && phase == Phase.Idle) listen()
    }

    override fun onStop() {
        super.onStop()
        cancel()
    }

    private fun currentConversation(): String? =
        conversationId?.takeIf { System.currentTimeMillis() - conversationAt < conversationTtlMs }

    private fun cancel() {
        recorder.requestStop()
        job?.cancel()
        job = null
        player?.release()
        player = null
        phase = Phase.Idle
    }

    fun listen() {
        if (!settings.configured) { showSettings = true; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        cancel()
        error = ""
        job = lifecycleScope.launch {
            phase = Phase.Listening
            val audio = try {
                recorder.record(onLevel = { level = it })
            } catch (e: Exception) {
                error = "Recording failed: ${e.message}"; phase = Phase.Idle; return@launch
            }
            level = 0
            if (audio == null) { phase = Phase.Idle; return@launch }
            phase = Phase.Thinking
            val client = VoiceClient(settings.serverUrl, settings.token)
            val result = runCatching { withContext(Dispatchers.IO) { client.audioTurn(audio, currentConversation()) } }
            handle(result)
        }
    }

    fun sendText(text: String) {
        if (!settings.configured) { showSettings = true; return }
        cancel()
        error = ""
        job = lifecycleScope.launch {
            phase = Phase.Thinking
            val client = VoiceClient(settings.serverUrl, settings.token)
            handle(runCatching { withContext(Dispatchers.IO) { client.textTurn(text, currentConversation()) } })
        }
    }

    private suspend fun handle(result: Result<TurnResult>) {
        val r = result.getOrElse { e ->
            error = when (e) {
                is VoiceError -> e.message.orEmpty()
                else -> "Can't reach ${settings.serverUrl}: ${e.message}"
            }
            phase = Phase.Idle
            return
        }
        said = r.transcript
        reply = r.reply + (r.held?.let { "\n\nWaiting for a yes: $it" } ?: "")
        if (r.conversationId.isNotEmpty()) { conversationId = r.conversationId; conversationAt = System.currentTimeMillis() }
        r.wav?.let { phase = Phase.Speaking; play(it) }
        phase = Phase.Idle
        val asks = r.reply.trimEnd().endsWith("?") || r.held != null
        if (asks && settings.listenAfterQuestion) listen()
    }

    private suspend fun play(wav: ByteArray) {
        val f = File(cacheDir, "reply.wav")
        withContext(Dispatchers.IO) { f.writeBytes(wav) }
        suspendCancellableCoroutine { cont ->
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { it.release(); if (player === it) player = null; if (cont.isActive) cont.resume(Unit) }
            p.setOnErrorListener { mp, _, _ -> mp.release(); if (player === mp) player = null; if (cont.isActive) cont.resume(Unit); true }
            cont.invokeOnCancellation { runCatching { p.release() } }
            p.prepare()
            p.start()
        }
    }

    @Composable
    private fun Screen() {
        val scroll = rememberScrollState()
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp).verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Canopy Voice", fontSize = 22.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Done" else "Settings") }
            }
            if (showSettings) SettingsPanel()

            Spacer(Modifier.height(32.dp))
            val label = when (phase) {
                Phase.Idle -> "Talk"
                Phase.Listening -> "Listening…"
                Phase.Thinking -> "Thinking…"
                Phase.Speaking -> "Stop"
            }
            val grow = if (phase == Phase.Listening) (level / 32767f).coerceIn(0f, 1f) * 40 else 0f
            Button(
                onClick = {
                    when (phase) {
                        Phase.Idle -> listen()
                        Phase.Listening -> recorder.requestStop()
                        else -> cancel()
                    }
                },
                shape = CircleShape,
                modifier = Modifier.size((180 + grow).dp),
            ) { Text(label, fontSize = 24.sp) }

            if (currentConversation() != null) {
                TextButton(onClick = { conversationId = null }) { Text("New conversation") }
            }

            if (error.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            if (said.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("You", style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth())
                Text(said, modifier = Modifier.fillMaxWidth())
            }
            if (reply.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Reply", style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth())
                Text(reply, fontSize = 18.sp, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(24.dp))
            var typed by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(typed, { typed = it }, label = { Text("Or type") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (typed.isNotBlank()) { sendText(typed); typed = "" } }, enabled = phase == Phase.Idle) { Text("Send") }
            }
        }
    }

    @Composable
    private fun SettingsPanel() {
        var url by remember { mutableStateOf(settings.serverUrl) }
        var token by remember { mutableStateOf(settings.token) }
        var onOpen by remember { mutableStateOf(settings.listenOnOpen) }
        var afterQ by remember { mutableStateOf(settings.listenAfterQuestion) }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(url, { url = it; settings.serverUrl = it }, label = { Text("Server, e.g. http://10.0.0.2:8797") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it; settings.token = it }, label = { Text("Speaker token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(onOpen, { onOpen = it; settings.listenOnOpen = it }); Spacer(Modifier.width(8.dp)); Text("Listen when opened")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(afterQ, { afterQ = it; settings.listenAfterQuestion = it }); Spacer(Modifier.width(8.dp)); Text("Listen again after a question")
            }
            Text("Opening the app starts a turn. Put it on the home screen for one-tap talk.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
