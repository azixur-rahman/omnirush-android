package ai.omnirush.mobile

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private val Ink = Color(0xFFF5F4FA)
private val MutedInk = Color(0xFF9A98A8)
private val Canvas = Color(0xFF0B0B0D)
private val Glass = Color(0xFF17171C).copy(alpha = 0.88f)
private val GlassLight = Color(0xFF202027).copy(alpha = 0.92f)
private val Accent = Color(0xFFA99BFF)
private val AccentDeep = Color(0xFF7566E8)
private val Divider = Color.White.copy(alpha = 0.09f)
private val ErrorInk = Color(0xFFFFA5A5)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniRushTheme {
                OmniRushApp()
            }
        }
    }
}

private enum class MessageRole { User, Assistant }

private data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
)

private sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val modelLabel: String) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

@Composable
private fun OmniRushApp() {
    val context = LocalContext.current
    var localMode by rememberSaveable { mutableStateOf(true) }
    if (localMode) {
        LocalRuntimeApp()
        return
    }
    val preferences = remember {
        context.getSharedPreferences("omnirush-mobile", android.content.Context.MODE_PRIVATE)
    }
    val scope = rememberCoroutineScope()
    var endpoint by rememberSaveable {
        mutableStateOf(preferences.getString("endpoint", "http://10.0.2.2:4096") ?: "http://10.0.2.2:4096")
    }
    var token by rememberSaveable { mutableStateOf("") }
    var connection by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    var client by remember { mutableStateOf<OpenCodeClient?>(null) }
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var draft by rememberSaveable { mutableStateOf("") }
    var isThinking by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun disconnect() {
        client = null
        sessionId = null
        messages.clear()
        draft = ""
        isThinking = false
        connection = ConnectionState.Disconnected
    }

    fun connect() {
        val url = endpoint.trim().removeSuffix("/")
        if (url.isBlank()) {
            connection = ConnectionState.Failed("Enter the URL of your running OpenCode server.")
            return
        }

        scope.launch {
            connection = ConnectionState.Connecting
            try {
                val newClient = OpenCodeClient(url, token.trim())
                newClient.serverInfo()
                val model = runCatching { newClient.defaultModelLabel() }.getOrNull() ?: "Server default"
                val newSession = newClient.createSession()
                preferences.edit().putString("endpoint", url).apply()
                client = newClient
                sessionId = newSession
                messages.clear()
                connection = ConnectionState.Connected(model)
            } catch (error: Exception) {
                connection = ConnectionState.Failed(error.userMessage())
            }
        }
    }

    fun send(value: String) {
        val prompt = value.trim()
        val activeClient = client
        val activeSession = sessionId
        if (prompt.isEmpty() || activeClient == null || activeSession == null || isThinking) return

        messages += ChatMessage(UUID.randomUUID().toString(), MessageRole.User, prompt)
        draft = ""
        keyboard?.hide()
        isThinking = true

        scope.launch {
            try {
                val response = activeClient.prompt(activeSession, prompt)
                val assistantText = response?.text.orEmpty()
                if (assistantText.isNotBlank()) {
                    messages += ChatMessage(response?.id ?: UUID.randomUUID().toString(), MessageRole.Assistant, assistantText)
                } else {
                    val freshMessages = activeClient.messages(activeSession)
                    messages.clear()
                    messages.addAll(freshMessages)
                }
            } catch (error: Exception) {
                messages += ChatMessage(
                    UUID.randomUUID().toString(),
                    MessageRole.Assistant,
                    "The server could not complete that request. ${error.userMessage()}",
                )
            } finally {
                isThinking = false
            }
        }
    }

    when (val state = connection) {
        ConnectionState.Disconnected,
        is ConnectionState.Failed,
        ConnectionState.Connecting -> {
            ConnectionScreen(
                endpoint = endpoint,
                token = token,
                state = state,
                onEndpointChange = { endpoint = it },
                onTokenChange = { token = it },
                onConnect = ::connect,
            )
        }

        is ConnectionState.Connected -> {
            ChatScreen(
                modelLabel = state.modelLabel,
                messages = messages,
                draft = draft,
                isThinking = isThinking,
                onDraftChange = { draft = it },
                onSend = { send(draft) },
                onPrompt = ::send,
                onDisconnect = ::disconnect,
            )
        }
    }
}

@Composable
private fun LocalRuntimeApp() {
    val context = LocalContext.current
    val manager = remember { LocalRuntimeManager(context) }
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf<LocalRuntimeState>(
            when {
                manager.hasOmniRush() -> LocalRuntimeState.OmniRushReady
                manager.hasUbuntu() -> LocalRuntimeState.UbuntuReady
                else -> LocalRuntimeState.Empty
            },
        )
    }
    var output by remember { mutableStateOf("") }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var draft by rememberSaveable { mutableStateOf("") }
    var isThinking by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun appendOutput(line: String) {
        output = (output + line + "\n").takeLast(4_000)
    }

    fun prepareRuntime() {
        scope.launch {
            try {
                state = LocalRuntimeState.Downloading("Preparing Ubuntu runtime", 0)
                manager.prepare { label, progress -> state = LocalRuntimeState.Downloading(label, progress) }
                state = if (manager.hasOmniRush()) LocalRuntimeState.OmniRushReady else LocalRuntimeState.UbuntuReady
            } catch (error: Exception) {
                state = LocalRuntimeState.Failed(error.message ?: "The Linux runtime could not be prepared.")
            }
        }
    }

    fun installOmniRush() {
        scope.launch {
            try {
                output = "Installing Node.js and the published OmniRush CLI…\n"
                state = LocalRuntimeState.Downloading("Installing OmniRush", -1)
                val result = manager.installOmniRush(::appendOutput)
                state = if (result.exitCode == 0) {
                    LocalRuntimeState.OmniRushReady
                } else {
                    LocalRuntimeState.Failed("OmniRush installation failed with exit code ${result.exitCode}.")
                }
            } catch (error: Exception) {
                state = LocalRuntimeState.Failed(error.message ?: "OmniRush installation failed.")
            }
        }
    }

    fun login() {
        scope.launch {
            try {
                output = "Starting real OmniRush login…\n"
                state = LocalRuntimeState.Downloading("Waiting for OmniRush login", -1)
                val result = manager.login { line ->
                    appendOutput(line)
                    extractLoginUrl(line)?.let { url ->
                        loginUrl = url
                        state = LocalRuntimeState.LoginRequired(url)
                    }
                }
                loginUrl = result.loginUrl
                state = if (result.exitCode == 0) {
                    LocalRuntimeState.Ready
                } else {
                    LocalRuntimeState.Failed("OmniRush login stopped with exit code ${result.exitCode}. Open the login link and try again.")
                }
            } catch (error: Exception) {
                state = LocalRuntimeState.Failed(error.message ?: "OmniRush login failed.")
            }
        }
    }

    fun sendLocalPrompt(value: String) {
        val prompt = value.trim()
        if (prompt.isEmpty() || isThinking || !manager.hasOmniRush()) return
        messages += ChatMessage(UUID.randomUUID().toString(), MessageRole.User, prompt)
        draft = ""
        keyboard?.hide()
        isThinking = true
        scope.launch {
            try {
                val result = manager.runPrompt(prompt, ::appendOutput)
                val answer = cleanCliOutput(result.output)
                if (result.exitCode == 0 && answer.isNotBlank()) {
                    messages += ChatMessage(UUID.randomUUID().toString(), MessageRole.Assistant, answer)
                } else {
                    messages += ChatMessage(
                        UUID.randomUUID().toString(),
                        MessageRole.Assistant,
                        "OmniRush returned exit code ${result.exitCode}.\n\n$answer",
                    )
                }
            } catch (error: Exception) {
                messages += ChatMessage(
                    UUID.randomUUID().toString(),
                    MessageRole.Assistant,
                    "The local OmniRush runtime failed. ${error.message ?: "Try the runtime setup again."}",
                )
            } finally {
                isThinking = false
            }
        }
    }

    if (state is LocalRuntimeState.Ready) {
        LocalChatScreen(
            messages = messages,
            draft = draft,
            isThinking = isThinking,
            onDraftChange = { draft = it },
            onSend = { sendLocalPrompt(draft) },
            onPrompt = ::sendLocalPrompt,
            onDisconnect = { state = LocalRuntimeState.OmniRushReady },
        )
    } else {
        LocalRuntimeSetupScreen(
            state = state,
            output = output,
            loginUrl = loginUrl,
            onPrepare = ::prepareRuntime,
            onInstall = ::installOmniRush,
            onLogin = ::login,
            onRetry = when {
                manager.hasOmniRush() -> ::login
                manager.hasUbuntu() -> ::installOmniRush
                else -> ::prepareRuntime
            },
            onOpenLogin = { url ->
                if (url != null) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
        )
    }
}

@Composable
private fun LocalRuntimeSetupScreen(
    state: LocalRuntimeState,
    output: String,
    loginUrl: String?,
    onPrepare: () -> Unit,
    onInstall: () -> Unit,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
    onOpenLogin: (String?) -> Unit,
) {
    val busy = state is LocalRuntimeState.Downloading
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(34.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("OmniRush on Android", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier.fillMaxSize().weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(82.dp).background(
                    Brush.radialGradient(listOf(Accent.copy(alpha = 0.72f), AccentDeep.copy(alpha = 0.24f))),
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = "OmniRush", tint = Ink, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("Run the real OmniRush CLI locally", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.55).sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(9.dp))
            Text(
                "The APK installs a rootless Ubuntu userland with PRoot, then installs the published OmniRush CLI. Your prompts run inside that local runtime.",
                color = MutedInk,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))

            when (state) {
                LocalRuntimeState.Empty -> SetupAction("1  Prepare Ubuntu runtime", "Unpacks the Ubuntu and PRoot files already bundled in this APK.", onPrepare)
                LocalRuntimeState.UbuntuReady -> SetupAction("2  Install OmniRush", "Unpacks Node.js and the published OmniRush CLI already bundled in this APK.", onInstall)
                LocalRuntimeState.OmniRushReady -> SetupAction("3  Sign in to OmniRush", "Opens the real OmniRush account login flow.", onLogin)
                is LocalRuntimeState.LoginRequired -> {
                    Text("Approve the sign-in request in your browser, then return here.", color = MutedInk, fontSize = 13.sp, textAlign = TextAlign.Center)
                    if (loginUrl != null) {
                        Spacer(Modifier.height(9.dp))
                        Button(
                            onClick = { onOpenLogin(loginUrl) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = GlassLight, contentColor = Ink),
                        ) { Text("Open OmniRush login in browser") }
                    }
                }
                is LocalRuntimeState.Failed -> {
                    GlassSurface(Modifier.fillMaxWidth(), Color(0xFF3B2024).copy(alpha = 0.82f), RoundedCornerShape(16.dp)) {
                        Text(state.message, color = ErrorInk, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(14.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    SetupAction("Retry", "Try the failed step again.", onRetry)
                }
                is LocalRuntimeState.Downloading -> {
                    GlassSurface(Modifier.fillMaxWidth(), Glass, RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(state.label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text(if (state.progress >= 0) "${state.progress}%" else "This may take a few minutes…", color = Accent, fontSize = 13.sp)
                        }
                    }
                }
                LocalRuntimeState.Ready -> Unit
            }

            if (output.isNotBlank() && !busy) {
                Spacer(Modifier.height(14.dp))
                GlassSurface(Modifier.fillMaxWidth(), Glass, RoundedCornerShape(16.dp)) {
                    Text(output.takeLast(1_600), color = MutedInk, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(13.dp))
                }
            }
        }

        Text(
            "This APK includes the Linux runtime, so first setup only unpacks files on the device.",
            color = MutedInk.copy(alpha = 0.72f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 18.dp),
        )
    }
}

@Composable
private fun SetupAction(title: String, subtitle: String, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().clickable(onClick = onClick), Glass, RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = MutedInk, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun LocalChatScreen(
    messages: List<ChatMessage>,
    draft: String,
    isThinking: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPrompt: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Canvas).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ChatTopBar(modelLabel = "GPT-6 Astra  •  Local OmniRush", onDisconnect = onDisconnect)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) WelcomeContent(onPrompt) else Conversation(messages, isThinking)
        }
        Composer(draft, !isThinking, onDraftChange, onSend)
    }
}

private fun cleanCliOutput(value: String): String {
    return Regex("\\u001B\\[[;\\d]*[ -/]*[@-~]")
        .replace(value, "")
        .replace("\r", "")
        .trim()
}

private fun extractLoginUrl(value: String): String? =
    Regex("https?://[^\\s)]+")
        .find(value)
        ?.value
        ?.trimEnd('.', ',', ';')

@Composable
private fun ConnectionScreen(
    endpoint: String,
    token: String,
    state: ConnectionState,
    onEndpointChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(34.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("OmniRush", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .background(
                        Brush.radialGradient(listOf(Accent.copy(alpha = 0.72f), AccentDeep.copy(alpha = 0.24f))),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = "OmniRush",
                    tint = Ink,
                    modifier = Modifier.size(38.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Connect to your real agent",
                color = Ink,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.6).sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = "This APK talks to an OpenCode-compatible OmniRush server. No fake replies, no hardcoded model.",
                color = MutedInk,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            GlassInput(
                label = "Server URL",
                value = endpoint,
                onValueChange = onEndpointChange,
                placeholder = "https://your-server.example.com",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            GlassInput(
                label = "Bearer token (optional for local servers)",
                value = token,
                onValueChange = onTokenChange,
                placeholder = "Paste your server token",
                secret = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(16.dp))

            val connecting = state is ConnectionState.Connecting
            Button(
                onClick = onConnect,
                enabled = !connecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = Canvas,
                    disabledContainerColor = Accent.copy(alpha = 0.4f),
                ),
            ) {
                Text(if (connecting) "Connecting…" else "Connect to OmniRush", fontWeight = FontWeight.SemiBold)
            }

            if (state is ConnectionState.Failed) {
                Spacer(Modifier.height(14.dp))
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF3B2024).copy(alpha = 0.82f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        text = state.message,
                        color = ErrorInk,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        Text(
            text = "Emulator default: http://10.0.2.2:4096  •  Physical phone: use your computer's LAN IP",
            color = MutedInk.copy(alpha = 0.72f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 18.dp),
        )
    }
}

@Composable
private fun GlassInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Glass,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp)) {
            Text(label, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp),
                textStyle = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 22.sp),
                singleLine = true,
                cursorBrush = SolidColor(Accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = keyboardOptions,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) Text(placeholder, color = MutedInk, fontSize = 16.sp)
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun ChatScreen(
    modelLabel: String,
    messages: List<ChatMessage>,
    draft: String,
    isThinking: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPrompt: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ChatTopBar(modelLabel = modelLabel, onDisconnect = onDisconnect)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                WelcomeContent(onPrompt = onPrompt)
            } else {
                Conversation(messages = messages, isThinking = isThinking)
            }
        }
        Composer(
            value = draft,
            enabled = !isThinking,
            onValueChange = onDraftChange,
            onSend = onSend,
        )
    }
}

@Composable
private fun ChatTopBar(modelLabel: String, onDisconnect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(icon = Icons.Outlined.Menu, label = "Open menu", onClick = {})
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OmniRush", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Accent, modifier = Modifier.size(15.dp))
            }
            Text(modelLabel, color = MutedInk, fontSize = 11.sp)
        }
        GlassIconButton(icon = Icons.Outlined.Settings, label = "Connection settings", onClick = onDisconnect)
    }
}

@Composable
private fun WelcomeContent(onPrompt: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(260)) + scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        ) {
            Box(
                modifier = Modifier.size(76.dp).background(
                    Brush.radialGradient(listOf(Accent.copy(alpha = 0.72f), AccentDeep.copy(alpha = 0.28f))),
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Ink, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("What can I help you make?", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.65).sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Ask anything, explore an idea, or start building.", color = MutedInk, fontSize = 14.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SuggestionCard(Icons.Outlined.AutoAwesome, "Design a beautiful mobile interface", "Start with a clear, polished direction") { onPrompt("Design a beautiful mobile interface") }
            SuggestionCard(Icons.Outlined.WbSunny, "Give me a fresh idea", "Turn a blank page into a first step") { onPrompt("Give me a fresh idea") }
        }
        Spacer(Modifier.height(22.dp))
        Text("Responses come from your connected OmniRush/OpenCode server.", color = MutedInk.copy(alpha = 0.72f), fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SuggestionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp).clickable(onClick = onClick),
        color = Glass,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(38.dp).background(Accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = MutedInk, fontSize = 12.sp)
            }
            Icon(Icons.Outlined.ArrowUpward, contentDescription = null, tint = MutedInk, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun Conversation(messages: List<ChatMessage>, isThinking: Boolean) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, isThinking) {
        val itemCount = messages.size + if (isThinking) 1 else 0
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(messages, key = { it.id }) { message -> MessageRow(message) }
        if (isThinking) item(key = "thinking") { ThinkingRow() }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val clipboard = LocalClipboardManager.current
    val isUser = message.role == MessageRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            AssistantMark()
            Spacer(Modifier.width(10.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 1f).animateContentSize(tween(220, easing = FastOutSlowInEasing)),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            GlassSurface(
                color = if (isUser) GlassLight else Color.Transparent,
                shape = RoundedCornerShape(20.dp, 20.dp, if (isUser) 20.dp else 6.dp, if (isUser) 6.dp else 20.dp),
            ) {
                Text(message.text, color = Ink, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp))
            }
            if (!isUser) {
                Row(modifier = Modifier.padding(top = 5.dp, start = 3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SmallAction(Icons.Outlined.ContentCopy, "Copy response") { clipboard.setText(AnnotatedString(message.text)) }
                    SmallAction(Icons.Outlined.Refresh, "Refresh conversation") {}
                    SmallAction(Icons.Outlined.MoreHoriz, "More response actions") {}
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow() {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistantMark()
        Spacer(Modifier.width(10.dp))
        Text("OmniRush is thinking…", color = MutedInk, fontSize = 14.sp)
    }
}

@Composable
private fun AssistantMark() {
    Box(modifier = Modifier.size(28.dp).background(Brush.linearGradient(listOf(Accent, AccentDeep)), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = "OmniRush", tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun Composer(value: String, enabled: Boolean, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    val canSend = value.trim().isNotEmpty() && enabled
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime).padding(bottom = 8.dp),
        color = GlassLight,
        shape = RoundedCornerShape(25.dp),
    ) {
        Row(modifier = Modifier.padding(start = 6.dp, end = 7.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            GlassIconButton(Icons.Outlined.Add, "Add attachment") {}
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                enabled = enabled,
                textStyle = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) Text("Message OmniRush…", color = MutedInk, fontSize = 16.sp)
                        innerTextField()
                    }
                },
            )
            SendButton(canSend, onSend)
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "send-button-press",
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier.size(42.dp).scale(scale).background(if (enabled) Accent else Color.White.copy(alpha = 0.08f), CircleShape),
    ) {
        Icon(Icons.Outlined.ArrowUpward, contentDescription = "Send message", tint = if (enabled) Canvas else MutedInk, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = label, tint = MutedInk, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun GlassIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(icon, contentDescription = label, tint = MutedInk, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun GlassSurface(modifier: Modifier = Modifier, color: Color, shape: RoundedCornerShape, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = color,
        shape = shape,
        border = BorderStroke(1.dp, Divider),
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}

private data class RemoteMessage(val id: String, val text: String)

private class OpenCodeClient(endpoint: String, private val token: String) {
    private val baseUrl = endpoint.removeSuffix("/")

    suspend fun serverInfo(): JSONObject = JSONObject(request("GET", "/api/info"))

    suspend fun defaultModelLabel(): String? {
        val raw = request("GET", "/api/model/default")
        val model = JSONObject(raw).let { it.optJSONObject("data") ?: it }
        val provider = model.optString("providerID").ifBlank { model.optString("providerId") }
        val id = model.optString("modelID").ifBlank { model.optString("id") }
        return listOf(provider, id).filter { it.isNotBlank() }.joinToString("/").ifBlank {
            model.optString("name").ifBlank { null }
        }
    }

    suspend fun createSession(): String {
        val raw = request(
            "POST",
            "/api/session",
            JSONObject().put("title", "OmniRush Android"),
        )
        val root = JSONObject(raw)
        val data = root.optJSONObject("data") ?: root
        return data.optString("id").ifBlank {
            throw ApiException(200, "The server did not return a session ID.")
        }
    }

    suspend fun prompt(sessionId: String, text: String): RemoteMessage? {
        val body = JSONObject().put(
            "parts",
            JSONArray().put(JSONObject().put("type", "text").put("text", text)),
        )
        val root = JSONObject(request("POST", "/api/session/$sessionId/prompt", body))
        return parseEnvelope(root)
    }

    suspend fun messages(sessionId: String): List<ChatMessage> {
        val root = JSONObjectOrArray(request("GET", "/api/session/$sessionId/message"))
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val envelope = array.optJSONObject(index)?.let(::parseEnvelope) ?: continue
                val text = envelope?.text.orEmpty()
                if (text.isNotBlank()) {
                    val role = array.optJSONObject(index)?.optJSONObject("info")?.optString("role")
                    add(ChatMessage(envelope.id, if (role == "user") MessageRole.User else MessageRole.Assistant, text))
                }
            }
        }
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw ApiException(status, readableError(response))
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseEnvelope(root: JSONObject): RemoteMessage? {
        val value = root.optJSONObject("data") ?: root
        val info = value.optJSONObject("info") ?: value.optJSONObject("message")
        val parts = value.optJSONArray("parts") ?: return null
        val text = buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
        return RemoteMessage(info?.optString("id").orEmpty().ifBlank { UUID.randomUUID().toString() }, text)
    }

    private fun readableError(response: String): String {
        return runCatching {
            val message = JSONObject(response).optString("message")
            message.ifBlank { response }
        }.getOrDefault(response).trim().ifBlank { "HTTP request failed." }
    }
}

private fun JSONObjectOrArray(value: String): Any = runCatching { JSONArray(value) }.getOrElse { JSONObject(value) }

private class ApiException(val status: Int, override val message: String) : Exception(message)

private fun Exception.userMessage(): String {
    return when (this) {
        is ApiException -> "Server returned HTTP $status: $message"
        else -> message?.ifBlank { "Check the URL, network, and server authentication." }
            ?: "Check the URL, network, and server authentication."
    }
}

@Composable
private fun OmniRushTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        background = Canvas,
        surface = Glass,
        surfaceVariant = GlassLight,
        primary = Accent,
        onPrimary = Canvas,
        onBackground = Ink,
        onSurface = Ink,
        onSurfaceVariant = MutedInk,
    )
    MaterialTheme(colorScheme = colors) {
        ProvideTextStyle(TextStyle(fontSize = 14.sp, lineHeight = 20.sp), content = content)
    }
}
