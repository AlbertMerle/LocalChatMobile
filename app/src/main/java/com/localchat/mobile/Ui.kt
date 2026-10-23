package com.localchat.mobile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onCall: () -> Unit) {
    val messages by vm.conversation.messages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalChat") },
                actions = {
                    val errCount by ErrorLog.entries.collectAsStateWithLifecycle()
                    if (errCount.isNotEmpty()) TextButton(onClick = { vm.show(Screen.Log) }) {
                        Text("Errors (${errCount.size})", color = MaterialTheme.colorScheme.error)
                    }
                    if (messages.isNotEmpty()) TextButton(onClick = vm::clearChat) { Text("Clear") }
                    IconButton(onClick = onCall) { Icon(Icons.Default.Call, contentDescription = "Call") }
                    IconButton(onClick = { vm.show(Screen.Settings) }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.id }) { Bubble(it) }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp),
                )
                if (busy) {
                    FilledIconButton(onClick = vm::stopText) { Icon(Icons.Default.Close, contentDescription = "Stop") }
                } else {
                    FilledIconButton(
                        onClick = { vm.send(input); input = "" },
                        enabled = input.isNotBlank(),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            if (m.text.isNotEmpty()) Text(m.text, color = fg)
            else if (m.streaming) Text("…", color = fg)
            m.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun CallScreen(engine: CallEngine, conversation: Conversation, onEnd: () -> Unit) {
    val phase by engine.phase.collectAsStateWithLifecycle()
    val level by engine.level.collectAsStateWithLifecycle()
    val heard by engine.heard.collectAsStateWithLifecycle()
    val error by engine.error.collectAsStateWithLifecycle()
    val muted by engine.muted.collectAsStateWithLifecycle()
    val messages by conversation.messages.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandlerCompat(onEnd)

    val color = when (phase) {
        CallPhase.Listening -> Color(0xFF64748B)
        CallPhase.UserSpeaking -> Color(0xFF22C55E)
        CallPhase.Thinking -> Color(0xFFF59E0B)
        CallPhase.Speaking -> Color(0xFF38BDF8)
    }
    val label = when (phase) {
        CallPhase.Listening -> "Listening…"
        CallPhase.UserSpeaking -> "You're speaking"
        CallPhase.Thinking -> "Thinking…"
        CallPhase.Speaking -> "Speaking"
    }
    val pulse by animateFloatAsState(if (phase == CallPhase.UserSpeaking) 1f + level * 0.6f else 1f, label = "pulse")

    val lastAi = messages.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text.orEmpty()

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0F172A)).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(32.dp))
        Box(Modifier.size(180.dp).scale(pulse).clip(CircleShape).background(color.copy(alpha = 0.85f)))
        Spacer(Modifier.height(32.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            if (heard.isNotEmpty()) Text(heard, color = Color(0xFF86EFAC), textAlign = TextAlign.Center)
            else if (lastAi.isNotEmpty()) Text(lastAi, color = Color(0xFFCBD5E1), textAlign = TextAlign.Center)
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color(0xFFFCA5A5), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text("Full details: Errors > Copy all, after ending the call", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { engine.setMuted(!muted) }) { Text(if (muted) "Unmute" else "Mute", color = Color.White) }
            Button(onClick = onEnd, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))) { Text("End call") }
        }
    }
}

@Composable
private fun BackHandlerCompat(onBack: () -> Unit) = androidx.activity.compose.BackHandler(onBack = onBack)

@Composable
fun SettingsScreen(
    initial: Settings,
    probe: List<String>?,
    onTest: (Settings) -> Unit,
    onLog: () -> Unit,
    onSave: (Settings) -> Unit,
) {
    var s by remember { mutableStateOf(initial) }
    var showKey by remember { mutableStateOf(false) }

    androidx.compose.material3.Surface(Modifier.fillMaxSize()) { Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Server", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = s.host, onValueChange = { s = s.copy(host = it) },
            label = { Text("IP address") }, placeholder = { Text("192.168.12.24") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = s.apiKey, onValueChange = { s = s.copy(apiKey = it) },
            label = { Text("API key") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
        )

        Text("Ports (leave blank for default)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PortField("Chat", s.llmPort, DEFAULT_LLM_PORT, Modifier.weight(1f)) { s = s.copy(llmPort = it) }
            PortField("STT", s.sttPort, DEFAULT_STT_PORT, Modifier.weight(1f)) { s = s.copy(sttPort = it) }
            PortField("TTS", s.ttsPort, DEFAULT_TTS_PORT, Modifier.weight(1f)) { s = s.copy(ttsPort = it) }
        }

        OutlinedButton(onClick = { onTest(s) }, enabled = s.isConfigured, modifier = Modifier.fillMaxWidth()) { Text("Test connection") }
        probe?.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        Button(onClick = { onSave(s) }, enabled = s.isConfigured, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        TextButton(onClick = onLog, modifier = Modifier.fillMaxWidth()) { Text("View error log") }
    } }
}

@Composable
private fun PortField(label: String, value: String, default: Int, modifier: Modifier, onChange: (String) -> Unit) =
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label, maxLines = 1) }, placeholder = { Text("$default") },
        singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(settings: Settings, onBack: () -> Unit) {
    val entries by ErrorLog.entries.collectAsStateWithLifecycle()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Error log") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(ErrorLog.dump(settings))) }) { Text("Copy all") }
                    TextButton(onClick = ErrorLog::clear) { Text("Clear") }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (entries.isEmpty()) item { Text("No errors so far.") }
            items(entries) { e ->
                Column {
                    Text("${fmt.format(java.util.Date(e.time))}  [${e.code}]", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    Text(e.message)
                    if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
