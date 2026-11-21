package com.danielrosehill.canopyvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.provider.Settings as AndroidSettings

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var showSettings by mutableStateOf(false)
    private var permTick by mutableIntStateOf(0) // bumps on resume so permission rows refresh

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicGranted?.invoke() else Assistant.error.value = "Microphone permission is needed"
        afterMicGranted = null
    }
    private var afterMicGranted: (() -> Unit)? = null
    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        showSettings = !settings.configured
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { Screen() } } }
        Assistant.syncPaused(this)
        if (settings.wakeWordEnabled && !settings.listeningPaused) withMic { WakeWordService.start(this) }
        onIntent(intent, firstLaunch = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onIntent(intent, firstLaunch = false)
    }

    override fun onResume() {
        super.onResume()
        permTick++
        Assistant.syncPaused(this)
        // Resuming from the Quick Settings tile opens the app so the wake word service may start (foreground only).
        if (settings.wakeWordEnabled && !settings.listeningPaused && !Assistant.wakeRunning.value &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) WakeWordService.start(this)
    }

    private fun onIntent(intent: Intent?, firstLaunch: Boolean) {
        if (intent?.getBooleanExtra(EXTRA_FROM_WAKE, false) == true) return // the turn is already running
        if (intent?.getBooleanExtra(EXTRA_NO_LISTEN, false) == true) return // opened by the tile to resume
        val explicit = intent?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
            intent?.action == Intent.ACTION_ASSIST || intent?.action == Intent.ACTION_VOICE_COMMAND
        // Paused: no listening on open; an explicit request (assist gesture, headset button) still listens.
        val onOpen = firstLaunch && settings.listenOnOpen && !settings.listeningPaused
        if (settings.configured && (explicit || onOpen) && Assistant.phase.value == Phase.Idle) listen()
    }

    private fun withMic(then: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) then()
        else { afterMicGranted = then; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun listen() {
        if (!settings.configured) { showSettings = true; return }
        withMic { Assistant.listen(this) }
    }

    @Composable
    private fun Screen() {
        val phase by Assistant.phase.collectAsState()
        val level by Assistant.level.collectAsState()
        val said by Assistant.said.collectAsState()
        val reply by Assistant.reply.collectAsState()
        val error by Assistant.error.collectAsState()
        val wakeOn by Assistant.wakeRunning.collectAsState()
        val paused by Assistant.paused.collectAsState()
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.yossi_mascot), contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Yossi", fontSize = 22.sp)
                    if (paused) Text("Listening paused", style = MaterialTheme.typography.bodySmall)
                    else if (wakeOn) Text("Listening for “Hey Yossi”", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showSettings = !showSettings }) { Text(if (showSettings) "Done" else "Settings") }
            }
            if (showSettings) SettingsPanel()

            Spacer(Modifier.height(16.dp))
            ListeningToggle(paused)

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
                        Phase.Listening -> Assistant.stopRecording()
                        else -> Assistant.cancel()
                    }
                },
                shape = CircleShape,
                modifier = Modifier.size((180 + grow).dp),
            ) { Text(label, fontSize = 24.sp) }

            if (Assistant.currentConversation() != null && phase == Phase.Idle) {
                TextButton(onClick = { Assistant.newConversation(); Assistant.said.value = ""; Assistant.reply.value = "" }) {
                    Text("New conversation")
                }
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
                Text("Yossi", style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth())
                Text(reply, fontSize = 18.sp, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(24.dp))
            var typed by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(typed, { typed = it }, label = { Text("Or type") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (typed.isNotBlank()) { Assistant.sendText(this@MainActivity, typed); typed = "" } },
                    enabled = phase == Phase.Idle) { Text("Send") }
            }
        }
    }

    @Composable
    private fun SettingsPanel() {
        var url by remember { mutableStateOf(settings.serverUrl) }
        var token by remember { mutableStateOf(settings.token) }
        var cfId by remember { mutableStateOf(settings.cfClientId) }
        var cfSecret by remember { mutableStateOf(settings.cfClientSecret) }
        var onOpen by remember { mutableStateOf(settings.listenOnOpen) }
        var afterQ by remember { mutableStateOf(settings.listenAfterQuestion) }
        var wake by remember { mutableStateOf(settings.wakeWordEnabled) }
        var cutoff by remember { mutableFloatStateOf(settings.wakeCutoff.takeIf { it > 0f } ?: 0.97f) }
        var folder by remember { mutableStateOf(settings.wakeModelFolder) }
        val wakeLevel by Assistant.wakeLevel.collectAsState()
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(url, { url = it; settings.serverUrl = it }, label = { Text("Server, e.g. http://10.0.0.2:8797") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it; settings.token = it }, label = { Text("Speaker token") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Cloudflare Access service token (optional, for an https server behind Cloudflare Access)",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            OutlinedTextField(cfId, { cfId = it; settings.cfClientId = it }, label = { Text("CF-Access-Client-Id") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cfSecret, { cfSecret = it; settings.cfClientSecret = it }, label = { Text("CF-Access-Client-Secret") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            SwitchRow("Listen when opened", onOpen) { onOpen = it; settings.listenOnOpen = it }
            SwitchRow("Listen again after a question", afterQ) { afterQ = it; settings.listenAfterQuestion = it }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SwitchRow("Wake word “Hey Yossi” (on the phone)", wake) { on ->
                wake = on; settings.wakeWordEnabled = on
                if (on && !settings.listeningPaused) withMic { WakeWordService.start(this@MainActivity) }
                else if (!on) WakeWordService.stop(this@MainActivity)
            }
            Text("Strictness ${"%.3f".format(cutoff)} (higher means fewer false wakes) · now ${"%.2f".format(wakeLevel)}",
                style = MaterialTheme.typography.bodySmall)
            Slider(cutoff, { cutoff = it }, valueRange = 0.80f..0.995f,
                onValueChangeFinished = {
                    settings.wakeCutoff = cutoff
                    if (wake && !settings.listeningPaused) {
                        WakeWordService.stop(this@MainActivity); WakeWordService.start(this@MainActivity)
                    }
                })
            OutlinedTextField(folder, { folder = it; settings.wakeModelFolder = it },
                label = { Text("Custom wake word folder (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Permissions", style = MaterialTheme.typography.titleSmall)
            permTick // read so the rows recompose on resume
            PermissionRow("Microphone",
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                withMic { permTick++ }
            }
            if (Build.VERSION.SDK_INT >= 33) PermissionRow("Notifications (wake word status)",
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PermissionRow("Display over other apps (wake word opens Yossi)", AndroidSettings.canDrawOverlays(this@MainActivity)) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            PermissionRow("All files access (custom wake word folder)", Environment.isExternalStorageManager()) {
                startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
            val pm = getSystemService(PowerManager::class.java)
            PermissionRow("Unrestricted battery (keeps the wake word alive)", pm.isIgnoringBatteryOptimizations(packageName)) {
                @Suppress("BatteryLife")
                startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            PermissionRow("Default digital assistant (assist gesture opens Yossi)", isDefaultAssistant()) {
                startActivity(Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }
    }

    /** The one listening on/off: pauses listen-on-open, listening again after a question and the wake word. */
    @Composable
    private fun ListeningToggle(paused: Boolean) {
        val toggle = { pause: Boolean ->
            if (pause) Assistant.setPaused(this, true) else withMic { Assistant.setPaused(this, false) }
        }
        Surface(
            onClick = { toggle(!paused) },
            shape = MaterialTheme.shapes.large,
            color = if (paused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (paused) "Listening paused" else "Listening on", fontSize = 18.sp)
                    Text(
                        if (paused) "Yossi only listens when you tap Talk" else "Hands-free: on open, after questions, wake word",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = !paused, onCheckedChange = { on -> toggle(!on) })
            }
        }
    }

    private fun isDefaultAssistant(): Boolean =
        AndroidSettings.Secure.getString(contentResolver, "assistant")?.startsWith("$packageName/") == true

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked, onChange); Spacer(Modifier.width(8.dp)); Text(label)
        }
    }

    @Composable
    private fun PermissionRow(label: String, granted: Boolean, grant: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text((if (granted) "✓ " else "• ") + label, modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium)
            if (!granted) TextButton(onClick = grant) { Text("Grant") }
        }
    }

    companion object {
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_FROM_WAKE = "from_wake"
        const val EXTRA_NO_LISTEN = "no_listen"
    }
}
