// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource as s
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.magicphone.core.*
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class MainActivity : ComponentActivity() {
    companion object {
        private const val OPEN_CHAT = "dev.magicphone.app.OPEN_CHAT"
        private const val CHAT_ID = "dev.magicphone.app.CHAT_ID"
        fun chatIntent(context: android.content.Context, conversation: String? = null) = Intent(context, MainActivity::class.java)
            .setAction(OPEN_CHAT)
            .setIdentifier(conversation)
            .putExtra(CHAT_ID, conversation)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    private var focusRequest by mutableLongStateOf(0L)
    private var sharedText by mutableStateOf("")
    private var sharedUri by mutableStateOf<Uri?>(null)

    override fun dump(
        prefix: String,
        fd: FileDescriptor?,
        writer: PrintWriter,
        args: Array<out String>?,
    ) {
        if (!BuildConfig.DEBUG || args?.firstOrNull() != "magicphone-status") {
            super.dump(prefix, fd, writer, args)
            return
        }
        // Shell-only, read-only diagnostics. Never print names, destinations, tokens or message text.
        val settings = runtime.settings.value
        val provider = settings.profiles.find { it.id == settings.selected }
        val conversation = runtime.archive.value.conversations.find { it.id == runtime.current.value }
        fun field(name: String, value: Any?) = writer.println("$prefix$name=$value")
        field("onboardingComplete", settings.onboarded)
        field("accessibilityConnected", runtime.connected.value)
        field("runState", runtime.agent.state.value)
        field("lastError", runtime.agent.error.value)
        field("versionName", BuildConfig.VERSION_NAME)
        field("fastDecisions", settings.fastDecisions)
        field("modelCalls", runtime.agent.metrics.value.modelCalls)
        field("modelMillis", runtime.agent.metrics.value.modelMs)
        field("toolMillis", runtime.agent.metrics.value.toolMs)
        field("elapsedMillis", runtime.agent.metrics.value.elapsedMs)
        field("responseCounters", runtime.agent.diagnostics.value)
        field("streamCharacters", runtime.agent.stream.value.length)
        field("questionCharacters", runtime.agent.question.value.length)
        field("providerKind", provider?.kind ?: "NONE")
        field("modelConfigured", provider?.model?.isNotBlank() == true)
        field("chatgptAccountSelected", settings.accounts.any { it.client == settings.activeAccount })
        field("planOnly", settings.policy.planOnly)
        field("allowAllApps", settings.policy.allowAllApps)
        field("readableAppCount", settings.policy.apps.values.count { it.observe && !it.deny })
        field("mutableAppCount", settings.policy.apps.values.count { it.mutate && !it.deny })
        field("conversationCount", runtime.archive.value.conversations.size)
        field("currentConversationState", conversation?.state)
        field("currentUserMessageCount", conversation?.messages?.count { it.role == "user" } ?: 0)
        field("auditCount", runtime.archive.value.audits.size)
        val requestedApp = args.getOrNull(1)
        if (requestedApp?.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")) == true) {
            val rule = settings.policy.apps[requestedApp] ?: AppRule()
            field("requestedAppRead", rule.observe)
            field("requestedAppAct", rule.mutate)
            field("requestedAppDenied", rule.deny)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receive(intent)
        setContent {
            MagicTheme {
                AppUi(
                    runtime,
                    focusRequest,
                    sharedText,
                    sharedUri,
                    {
                        sharedText = ""
                        sharedUri = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        if (intent.action == OPEN_CHAT || intent.action == Intent.ACTION_ASSIST) {
            val conversation = intent.getStringExtra(CHAT_ID)?.take(100)
            if (conversation != null && conversation != runtime.current.value &&
                runtime.archive.value.conversations.any { it.id == conversation })
                runtime.selectConversation(conversation)
            runtime.pauseForChat()
            focusRequest = android.os.SystemClock.elapsedRealtimeNanos()
        }
        sharedText =
            (intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
                ?.toString()
                ?.take(8000)
                .orEmpty()
        sharedUri =
            if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } else null
    }

    fun image(uri: Uri): String {
        require(uri.scheme == "content")
        val bytes =
            contentResolver.openInputStream(uri)!!.use { it.readBounded(5 * 1024 * 1024 + 1) }
        require(bytes.size <= 5 * 1024 * 1024)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(
            options.outWidth in 1..12000 &&
                options.outHeight in 1..12000 &&
                options.outMimeType in setOf("image/jpeg", "image/png", "image/webp")
        )
        val bitmap =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize =
                        (maxOf(options.outWidth, options.outHeight) / 1280).coerceAtLeast(1)
                },
            ) ?: error("image")
        val output = java.io.ByteArrayOutputStream()
        try {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output)
        } finally {
            bitmap.recycle()
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
    }
}

@Composable
fun MagicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xffb8f3d1),
                secondary = Color(0xfff5bc79),
                background = Color(0xff0c2021),
                surface = Color(0xff152d2d),
            )
        else
            lightColorScheme(
                primary = Color(0xff21624f),
                secondary = Color(0xff975b20),
                background = Color(0xfff6f6ee),
                surface = Color(0xfffffff9),
            )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun Info(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun BoxCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun Toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun Field(value: String, label: Int, change: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(
        value,
        change,
        label = { Text(s(label)) },
        modifier = Modifier.fillMaxWidth(),
        visualTransformation =
            if (secret) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

fun errorResource(code: String): Int =
    when {
        code == "voice_unavailable" -> R.string.voice_unavailable
        code == "voice_empty" -> R.string.voice_empty
        code == "voice_chat_changed" -> R.string.voice_chat_changed
        code == "empty_model_response" -> R.string.error_empty_response
        code == "accessibility_missing" -> R.string.error_accessibility
        code == "protected_control" -> R.string.error_protected_control
        code in setOf("screen_uncertain", "capture_uncertain") -> R.string.error_screen_uncertain
        code in setOf("app_not_allowed", "plan_only", "mcp_consent", "approval_denied") ->
            R.string.error_policy
        code in
            setOf(
                "stale_target",
                "stale_approval",
                "approval_expired",
                "fresh_observation_required",
            ) -> R.string.error_stale
        code in setOf("manual_secret", "manual_security", "device_locked", "secure_window") ->
            R.string.error_secret
        code in setOf("session_expired", "sign_in_required") -> R.string.error_session
        code in
            setOf(
                "usage_limit",
                "consent_or_eligibility",
                "consent_denied",
                "plan_consent_required",
            ) -> R.string.error_limit
        code == "action_uncertain" -> R.string.error_uncertain
        code in setOf("provider_required", "api_key_required", "models_failed") ->
            R.string.error_provider
        code == "revocation_unconfirmed" -> R.string.error_revoke
        code == "signed_out" -> R.string.signed_out
        else -> R.string.error_generic
    }

@Composable
fun stateLabel(state: RunState): String = s(stateResource(state))

fun stateResource(state: RunState): Int = when (state) {
            RunState.IDLE -> R.string.state_idle
            RunState.PLANNING -> R.string.state_planning
            RunState.WAITING_APPROVAL -> R.string.state_waiting_approval
            RunState.ACTING -> R.string.state_acting
            RunState.WAITING_USER -> R.string.state_waiting_user
            RunState.PAUSED -> R.string.state_paused
            RunState.FAILED -> R.string.state_failed
            RunState.COMPLETED -> R.string.state_completed
            RunState.STOPPED -> R.string.state_stopped
            RunState.INTERRUPTED -> R.string.state_interrupted
        }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUi(r: AppRuntime, focusRequest: Long, shared: String, uri: Uri?, consumed: () -> Unit) {
    val settings by r.settings.collectAsStateWithLifecycle()
    val notice by r.notice.collectAsStateWithLifecycle()
    val state by r.agent.state.collectAsStateWithLifecycle()
    val approval by r.approval.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val current by r.current.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(tab, current, lifecycle, settings.onboarded) {
        fun updateVisibility() {
            r.visibleChat.value = current.takeIf {
                settings.onboarded && tab == 0 && lifecycle.currentState == Lifecycle.State.RESUMED
            }
        }
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose { lifecycle.removeObserver(observer); r.visibleChat.value = null }
    }
    val draft = rememberSaveable { mutableStateOf("") }
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    LaunchedEffect(focusRequest) { if (focusRequest > 0) tab = 0 }
    LaunchedEffect(shared, uri) {
        if (shared.isNotBlank()) {
            draft.value = shared
            tab = 0
        }
        if (uri != null) {
            runCatching { withContext(Dispatchers.IO) { activity.image(uri) } }
                .onSuccess {
                    images = (images + it).take(3)
                    tab = 0
                }
                .onFailure { r.notice.value = "image_invalid" }
        }
        consumed()
    }
    if (!settings.onboarded) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Text("✦", fontSize = 72.sp, color = MaterialTheme.colorScheme.primary)
                Text(
                    s(R.string.onboarding_title),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(s(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge)
                Button(
                    {
                        r.saveSettings(settings.copy(onboarded = true))
                        tab = 3
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text(s(R.string.understand))
                }
            }
        }
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MagicPhone", fontWeight = FontWeight.Bold)
                        Text(
                            stateLabel(state),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    TextButton({
                        r.newConversation()
                        draft.value = ""
                        tab = 0
                    }) {
                        Text(s(R.string.new_chat))
                    }
                    Button(
                        { r.stop() },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        Text(s(R.string.stop))
                    }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(R.string.task, R.string.history, R.string.library, R.string.settings)
                    .forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Text(listOf("✦", "◷", "▤", "⚙")[index], fontSize = 22.sp) },
                            label = { Text(s(title)) },
                        )
                    }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()
            .fillMaxSize().padding(horizontal = 18.dp)) {
            when (tab) {
                0 -> TaskPage(r, draft, images, { images = it }, focusRequest)
                1 -> HistoryPage(r) { tab = 0 }
                2 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) { LibraryPage(r) }
                3 -> SettingsPage(r)
            }
        }
    }

    if (notice.isNotEmpty())
        AlertDialog(
            onDismissRequest = { r.notice.value = "" },
            title = { Text(s(errorResource(notice))) },
            text = { Text(notice, style = MaterialTheme.typography.labelSmall) },
            confirmButton = { TextButton({ r.notice.value = "" }) { Text(s(R.string.dismiss)) } },
        )
    if (approval != null)
        AlertDialog(
            onDismissRequest = {},
            title = { Text(s(R.string.approval_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(JsonCodec.encodeToString(Action.serializer(), approval!!.action))
                    Info(s(R.string.import_warning).substringBefore('.'))
                }
            },
            confirmButton = {
                Button({ r.localApproval(true) }) { Text(s(R.string.approve_once)) }
            },
            dismissButton = { TextButton({ r.localApproval(false) }) { Text(s(R.string.reject)) } },
        )
}

@Composable
fun TaskPage(
    r: AppRuntime,
    draft: MutableState<String>,
    images: List<String>,
    setImages: (List<String>) -> Unit,
    focusRequest: Long,
) {
    val data by r.archive.collectAsStateWithLifecycle()
    val current by r.current.collectAsStateWithLifecycle()
    val state by r.agent.state.collectAsStateWithLifecycle()
    val stream by r.agent.stream.collectAsStateWithLifecycle()
    val checklist by r.agent.checklist.collectAsStateWithLifecycle()
    val question by r.agent.question.collectAsStateWithLifecycle()
    val error by r.agent.error.collectAsStateWithLifecycle()
    val settings by r.settings.collectAsStateWithLifecycle()
    val usage by r.agent.usage.collectAsStateWithLifecycle()
    val metrics by r.agent.metrics.collectAsStateWithLifecycle()
    val actions by r.agent.actions.collectAsStateWithLifecycle()
    val conversation = data.conversations.find { it.id == current }
    val listState = rememberLazyListState()
    LaunchedEffect(current, conversation?.messages?.size, actions.size) {
        if (listState.layoutInfo.totalItemsCount > 0)
            listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "intro") {
                Label(conversation?.title ?: s(R.string.new_task))
                if (conversation?.messages.isNullOrEmpty()) Info(s(R.string.no_messages))
                if (settings.policy.planOnly) AssistChip(onClick = {}, label = { Text(s(R.string.plan_only)) })
                if (settings.policy.allowAllApps) BoxCard {
                    Text(s(R.string.all_apps_active), fontWeight = FontWeight.Bold)
                    TextButton({ r.setAllowAllApps(false) }) { Text(s(R.string.all_apps_disable)) }
                }
                if (conversation?.state == RunState.INTERRUPTED)
                    BoxCard {
                        Text(s(R.string.interrupted))
                        Button({
                            r.start(
                                "Inspect the current state before continuing this interrupted task. Never repeat a possibly completed action. Ask me if uncertain."
                            )
                        }) {
                            Text(s(R.string.inspect_resume))
                        }
                    }
            }
            items(conversation?.messages?.takeLast(60).orEmpty(), key = { it.id }) { msg ->
                BoxCard {
                    Text(if (msg.role == "user") "●" else "✦", color = MaterialTheme.colorScheme.primary)
                    if (msg.role == "assistant") Info(s(R.string.assistant_report))
                    Text(msg.text)
                    TextButton({ r.branch(conversation!!.id, msg.id) }) { Text(s(R.string.branch)) }
                }
            }
            item(key = "status") {
                if (actions.isNotEmpty())
                    BoxCard {
                        Text(s(R.string.action_results), fontWeight = FontWeight.Bold)
                        actions.takeLast(10).forEach {
                            Text(
                                "${it.operation} · ${it.app}\n${it.status}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                if (checklist.isNotEmpty())
                    BoxCard {
                        Text(s(R.string.checklist), fontWeight = FontWeight.Bold)
                        checklist.forEachIndexed { i, item -> Text("${i+1}. $item") }
                    }
                if (stream.isNotEmpty() && state in setOf(RunState.PLANNING, RunState.ACTING))
                    BoxCard { Text(stream) }
                if (question.isNotEmpty())
                    BoxCard {
                        Text(s(R.string.waiting_question), fontWeight = FontWeight.Bold)
                        Text(question)
                    }
                if (error.isNotEmpty()) Text(s(errorResource(error)), color = MaterialTheme.colorScheme.error)
                if (usage.isNotEmpty()) Info(usage)
                if (metrics.elapsedMs > 0 && state in setOf(RunState.COMPLETED, RunState.FAILED, RunState.STOPPED))
                    Info(s(R.string.run_timing_summary, metrics.elapsedMs / 1000.0, metrics.modelCalls,
                        metrics.modelMs / 1000.0, metrics.toolMs / 1000.0))
            }
        }
        TaskComposer(r, draft, images, setImages, focusRequest)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TaskComposer(r: AppRuntime, draft: MutableState<String>, images: List<String>,
    setImages: (List<String>) -> Unit, focusRequest: Long) {
    val state by r.agent.state.collectAsStateWithLifecycle()
    val settings by r.settings.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val window = LocalWindowInfo.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val imeVisible = WindowInsets.isImeVisible
    var consumedFocus by rememberSaveable { mutableLongStateOf(0L) }
    var voiceFocus by rememberSaveable { mutableLongStateOf(0L) }
    val requestedFocus = maxOf(focusRequest, voiceFocus)
    LaunchedEffect(requestedFocus, window.isWindowFocused, lifecycle, imeVisible) {
        if (requestedFocus > consumedFocus && window.isWindowFocused && lifecycle == Lifecycle.State.RESUMED) {
            withFrameNanos { }
            focusRequester.requestFocus()
            if (imeVisible) consumedFocus = requestedFocus
            else {
                // IME can reject a request before the resumed window is ready. Do not consume
                // the shortcut before focus returns; retry only during this explicit request.
                repeat(3) { keyboard?.show(); delay(150) }
                consumedFocus = requestedFocus
            }
        }
    }
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    val scope = rememberCoroutineScope()
    var secondary by remember { mutableStateOf(false) }
    var voicePending by rememberSaveable { mutableStateOf(false) }
    var voiceConversation by rememberSaveable { mutableStateOf<String?>(null) }
    val voice = rememberLauncherForActivityResult(VoiceInput()) { transcript ->
        val expectedConversation = voiceConversation
        val wasPending = voicePending
        voicePending = false
        voiceConversation = null
        if (wasPending && transcript != null) {
            if (transcript.isBlank()) r.notice.value = "voice_empty"
            else if (r.current.value == expectedConversation) {
                draft.value = listOf(draft.value.trimEnd(), transcript).filter { it.isNotEmpty() }.joinToString(" ")
                voiceFocus = android.os.SystemClock.elapsedRealtimeNanos()
            } else r.notice.value = "voice_chat_changed"
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { activity.image(uri) } }
                        .onSuccess { setImages((images + it).take(3)) }
                        .onFailure { r.notice.value = "image_invalid" }
                }
        }
    OutlinedTextField(
        draft.value,
        { draft.value = it },
        Modifier.fillMaxWidth().focusRequester(focusRequester),
        label = { Text(s(R.string.ask_task)) },
        placeholder = { Text(s(R.string.task_hint)) },
        minLines = 1,
        maxLines = 4,
        trailingIcon = {
            IconButton(onClick = {
                r.pauseForChat()
                keyboard?.hide()
                voiceConversation = r.current.value
                voicePending = true
                try {
                    voice.launch(Unit)
                } catch (_: ActivityNotFoundException) {
                    voicePending = false
                    r.notice.value = "voice_unavailable"
                } catch (_: SecurityException) {
                    voicePending = false
                    r.notice.value = "voice_unavailable"
                }
            }, enabled = !voicePending) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = s(R.string.voice_input))
            }
        },
    )
    if (images.isNotEmpty())
        TextButton({ setImages(emptyList()) }) {
            Text("${s(R.string.remove_image)} (${images.size})")
        }
    if (settings.secondary.isNotBlank())
        Toggle(s(R.string.use_secondary), secondary) { secondary = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ picker.launch("image/*") }) { Text(s(R.string.attach)) }
        Button(
            {
                r.start(draft.value, images, secondary)
                draft.value = ""
                setImages(emptyList())
            },
            enabled = draft.value.isNotBlank(),
        ) {
            Text(
                s(
                    if (
                        state in
                            setOf(
                                RunState.WAITING_USER,
                                RunState.ACTING,
                                RunState.PLANNING,
                                RunState.PAUSED,
                                RunState.WAITING_APPROVAL,
                            )
                    )
                        R.string.send
                    else R.string.start
                )
            )
        }
    }
    NotificationAccess(r)
    if (state !in setOf(RunState.IDLE, RunState.COMPLETED, RunState.STOPPED, RunState.FAILED))
        OutlinedButton({ if (state == RunState.PAUSED) r.agent.resume() else r.agent.pause() }) {
            Text(s(if (state == RunState.PAUSED) R.string.resume else R.string.pause))
        }

}

@Composable
private fun NotificationAccess(r: AppRuntime, showHelp: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by remember { mutableStateOf(TaskNotifications.enabled(context)) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = TaskNotifications.enabled(context)
        r.phone?.updateControls()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = TaskNotifications.enabled(context)
        r.phone?.updateControls()
    }
    if (showHelp) Info(s(R.string.notification_help))
    if (!enabled) {
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !permissionAsked &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permissionAsked = true
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else context.startActivity(Intent(
                if (context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled())
                    AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                else AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, TaskNotifications.CHANNEL))
        }) { Text(s(R.string.notifications)) }
    }
}

@Composable
fun HistoryPage(r: AppRuntime, open: () -> Unit) {
    val archive by r.archive.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf("") }
    val matches = remember(archive.conversations, search) {
        archive.conversations.asReversed().filter { c ->
            c.title.contains(search, true) || c.messages.any { it.text.contains(search, true) }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        item { Label(s(R.string.history)); Field(search, R.string.search, { search = it }) }
        if (archive.conversations.isEmpty()) item { Info(s(R.string.no_history)) }
        items(matches, key = { it.id }) { c ->
            BoxCard {
                Text(c.title, fontWeight = FontWeight.Bold)
                Info(stateLabel(c.state))
                Row {
                    TextButton({ r.selectConversation(c.id); open() }) { Text(s(R.string.select)) }
                    TextButton({ r.deleteConversation(c.id) }) { Text(s(R.string.delete)) }
                }
            }
        }
    }
}

@Composable
fun SettingsPage(r: AppRuntime) {
    val settings by r.settings.collectAsStateWithLifecycle()
    val connected by r.connected.collectAsStateWithLifecycle()
    val models by r.models.collectAsStateWithLifecycle()
    val busy by r.busy.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    fun change(value: Settings) {
        r.stop()
        r.saveSettings(value)
    }
    var appQuery by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<Pair<String, String>>>(emptyList(), r) {
        value = withContext(Dispatchers.IO) { r.launchable() }
    }
    val filteredApps = remember(apps, appQuery) {
        apps.filter { it.first != context.packageName &&
            (it.second.contains(appQuery, true) || it.first.contains(appQuery, true)) }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        item(key = "preferences") { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Info("MagicPhone ${BuildConfig.VERSION_NAME}")
                BoxCard {
                    Text(s(R.string.all_apps_title), fontWeight = FontWeight.Bold)
                    Info(s(R.string.all_apps_help))
                    Button({ r.setAllowAllApps(!settings.policy.allowAllApps) }) {
                        Text(s(if (settings.policy.allowAllApps) R.string.all_apps_disable else R.string.all_apps_enable))
                    }
                    if (settings.policy.allowAllApps) Info(s(R.string.all_apps_active))
                }
                Label(s(R.string.permissions))
                BoxCard {
                    Text(
                        s(if (connected) R.string.connected else R.string.disconnected),
                        fontWeight = FontWeight.Bold,
                    )
                    Info(s(R.string.permission_help))
                    OutlinedButton({
                        context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) {
                        Text(s(R.string.accessibility))
                    }
                    NotificationAccess(r, showHelp = true)
                    Info(s(R.string.voice_help))
                    Toggle(s(R.string.plan_only), settings.policy.planOnly) {
                        change(settings.copy(policy = settings.policy.copy(
                            planOnly = it,
                            allowAllApps = if (it) false else settings.policy.allowAllApps,
                        )))
                    }
                    Info(s(R.string.plan_help))
                    Toggle(s(R.string.fast_decisions), settings.fastDecisions) {
                        change(settings.copy(fastDecisions = it))
                    }
                    Info(s(R.string.fast_decisions_help))
                }
                Label(s(R.string.providers))
                BoxCard {
                    Button(
                        {
                            r.signIn({ url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            })
                        },
                        enabled = !busy,
                    ) {
                        Text(s(if (busy) R.string.busy else R.string.continue_chatgpt))
                    }
                    Info(s(R.string.signin_note))
                    settings.accounts.forEach { a ->
                        Text("${a.email} · ${a.client.takeLast(8)}")
                        Row {
                            TextButton({
                                r.stop()
                                r.saveSettings(settings.copy(activeAccount = a.client, selected = "chatgpt"))
                                r.models.value = emptyList()
                            }) {
                                Text(s(R.string.select))
                            }
                            TextButton({
                                r.signIn(
                                    { url ->
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    },
                                    a.client,
                                )
                            }) {
                                Text(s(R.string.continue_chatgpt))
                            }
                            TextButton({ r.logout(a.client) }) { Text(s(R.string.signout)) }
                        }
                    }
                    settings.profiles.forEach { p ->
                        HorizontalDivider()
                        Text("${p.name} · ${p.model.ifEmpty { "—" }}", fontWeight = FontWeight.Bold)
                        Info(if (p.kind == ProviderKind.MOCK) "Local" else p.endpoint)
                        Row {
                            TextButton({
                                change(settings.copy(selected = p.id))
                                r.models.value = emptyList()
                            }) {
                                Text(s(if (settings.selected == p.id) R.string.active else R.string.select))
                            }
                            TextButton({ change(settings.copy(secondary = p.id)) }) {
                                Text(s(R.string.secondary))
                            }
                            TextButton({
                                r.stop()
                                r.vault.delete("secret-${p.id}")
                                change(
                                    settings.copy(
                                        profiles = settings.profiles.filterNot { it.id == p.id },
                                        selected = if (settings.selected == p.id) "" else settings.selected,
                                        secondary = if (settings.secondary == p.id) "" else settings.secondary,
                                    )
                                )
                            }) {
                                Text(s(R.string.delete))
                            }
                        }
                        if (settings.selected == p.id && p.kind != ProviderKind.MOCK) {
                            var model by remember(p.id, p.model) { mutableStateOf(p.model) }
                            Field(model, R.string.model, { model = it })
                            Button({
                                change(
                                    settings.copy(
                                        profiles =
                                            settings.profiles.map {
                                                if (it.id == p.id) it.copy(model = model) else it
                                            }
                                    )
                                )
                            }) {
                                Text(s(R.string.save))
                            }
                            TextButton({ r.discoverModels() }) { Text(s(R.string.discover)) }
                            models.forEach { m ->
                                TextButton({
                                    change(
                                        settings.copy(
                                            profiles =
                                                settings.profiles.map {
                                                    if (it.id == p.id) it.copy(model = m.id) else it
                                                }
                                        )
                                    )
                                }) {
                                    Text(m.name)
                                }
                            }
                        }
                    }
                }
                ProviderEditor(r)
                BoxCard {
                    Text(s(R.string.practice), fontWeight = FontWeight.Bold)
                    Info(s(R.string.practice_help))
                    Button({ r.configurePractice() }) { Text(s(R.string.practice)) }
                }
        } }
        item(key = "app-search") {
            Label(s(R.string.app_access))
            Field(appQuery, R.string.search, { appQuery = it })
        }
        items(filteredApps, key = { it.first }) { (pkg, name) ->
            val rule = settings.policy.apps[pkg] ?: AppRule()
            fun setRule(value: AppRule) =
                change(
                    settings.copy(
                        policy = settings.policy.copy(apps = settings.policy.apps + (pkg to value))
                    )
                )
            BoxCard {
                Text(name, fontWeight = FontWeight.Bold)
                Info(pkg)
                if (settings.policy.allowAllApps) {
                    Info(s(R.string.all_apps_rule_help))
                } else {
                    Toggle(s(R.string.observe), rule.observe) { setRule(rule.copy(observe = it)) }
                    Toggle(s(R.string.mutate), rule.mutate) { setRule(rule.copy(mutate = it)) }
                }
                Toggle(s(R.string.deny), rule.deny) { setRule(rule.copy(deny = it)) }
                if (!settings.policy.allowAllApps && rule.observe && rule.mutate && !rule.deny) {
                    Info(s(R.string.grant_help))
                    TextButton({
                        change(
                            settings.copy(
                                policy =
                                    settings.policy.copy(
                                        grants =
                                            settings.policy.grants +
                                                Grant(
                                                    pkg,
                                                    setOf(Op.TAP, Op.SCROLL, Op.OPEN),
                                                    System.currentTimeMillis() + 300000,
                                                )
                                    )
                            )
                        )
                    }) {
                        Text(s(R.string.grant))
                    }
                }
            }
        }
        item(key = "extensions-data") { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TextButton({ change(settings.copy(policy = settings.policy.copy(grants = emptyList(), allowAllApps = false))) }) {
                    Text(s(R.string.revoke))
                }
                McpPage(r)
                DataPage(r)
        } }
    }
}

@Composable
fun ProviderEditor(r: AppRuntime) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("https://openrouter.ai/api/v1/") }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var openai by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(false) }
    var functions by remember { mutableStateOf(true) }
    BoxCard {
        Toggle(s(R.string.openai_key), openai) {
            openai = it
            endpoint = if (it) "https://api.openai.com/v1/" else "https://openrouter.ai/api/v1/"
        }
        Field(name, R.string.provider_name, { name = it })
        Field(endpoint, R.string.endpoint, { endpoint = it })
        Field(key, R.string.api_key, { key = it }, true)
        Field(model, R.string.model, { model = it })
        Toggle(s(R.string.local_optin), local) { local = it }
        Toggle(s(R.string.images_cap), images) { images = it }
        Toggle(s(R.string.functions_cap), functions) { functions = it }
        Button({
            runCatching {
                r.saveProfile(
                    Profile(
                        name = name.ifBlank { "Connection" },
                        kind = if (openai) ProviderKind.OPENAI else ProviderKind.COMPATIBLE,
                        endpoint = endpoint,
                        model = model,
                        localOptIn = local,
                        images = images,
                        functions = functions,
                    ),
                    key,
                )
            }
                .onSuccess {
                    key = ""
                    name = ""
                }
                .onFailure { r.notice.value = "invalid_destination" }
        }) {
            Text(s(R.string.save))
        }
    }
}

@Composable
fun LibraryPage(r: AppRuntime) {
    val archive by r.archive.collectAsStateWithLifecycle()
    var noteId by remember { mutableStateOf(id()) }
    var title by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var playbook by remember { mutableStateOf(false) }
    Label(s(R.string.notes))
    archive.knowledge.forEach { n ->
        BoxCard {
            Text(n.title, fontWeight = FontWeight.Bold)
            Info("${n.app} · ${n.kind}")
            if (!n.reviewed)
                Text(s(R.string.unreviewed), color = MaterialTheme.colorScheme.secondary)
            Text(n.content)
            Row {
                TextButton({
                    noteId = n.id
                    title = n.title
                    pkg = n.app
                    content = n.content
                    playbook = n.kind == "playbook"
                }) {
                    Text(s(R.string.edit))
                }
                TextButton({
                    r.saveArchive(
                        archive.copy(knowledge = archive.knowledge.filterNot { it.id == n.id })
                    )
                }) {
                    Text(s(R.string.delete))
                }
            }
        }
    }
    BoxCard {
        Field(title, R.string.title, { title = it })
        Field(pkg, R.string.app_package, { pkg = it })
        Field(content, R.string.content, { content = it })
        Toggle(s(R.string.playbook), playbook) { playbook = it }
        Button({
            if (title.isNotBlank() && pkg.isNotBlank() && content.length <= 16000) {
                r.saveArchive(
                    archive.copy(
                        knowledge =
                            archive.knowledge.filterNot { it.id == noteId } +
                                Knowledge(
                                    noteId,
                                    pkg,
                                    title,
                                    Sanitizer.text(content),
                                    if (playbook) "playbook" else "memory",
                                    true,
                                )
                    )
                )
                noteId = id()
                title = ""
                content = ""
            }
        }) {
            Text(s(R.string.review_save))
        }
    }
    Label(s(R.string.scripts))
    Info(s(R.string.script_help))
    var scriptText by remember {
        mutableStateOf(
            """{"id":"practice-read","name":"Read practice screen","parameters":[],"steps":[{"action":{"op":"OBSERVE","app":"dev.magicphone.fixture"}}],"enabled":false}"""
        )
    }
    var values by remember { mutableStateOf("{}") }
    archive.scripts.forEach { script ->
        BoxCard {
            Text(script.name, fontWeight = FontWeight.Bold)
            TextButton({ scriptText = JsonCodec.encodeToString(Script.serializer(), script) }) {
                Text(s(R.string.edit))
            }
            if (!script.enabled) Text(s(R.string.unreviewed))
            Button(
                {
                    runCatching {
                        val args =
                            JsonCodec.parseToJsonElement(values).jsonObject.mapValues {
                                it.value.jsonPrimitive.content
                            }
                        r.runScript(script, args)
                    }
                        .onFailure { r.notice.value = "invalid_script" }
                },
                enabled = script.enabled,
            ) {
                Text(s(R.string.run_script))
            }
            TextButton({
                r.saveArchive(
                    archive.copy(scripts = archive.scripts.filterNot { it.id == script.id })
                )
            }) {
                Text(s(R.string.delete))
            }
        }
    }
    Field(values, R.string.parameters, { values = it })
    Field(scriptText, R.string.script_json, { scriptText = it })
    Button({
        runCatching {
            require(scriptText.length <= 64000)
            val script = JsonCodec.decodeFromString(Script.serializer(), scriptText)
            ScriptRunner.validate(script)
            require(script.id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
            r.saveArchive(
                archive.copy(
                    scripts =
                        archive.scripts.filterNot { it.id == script.id } +
                            script.copy(enabled = true)
                )
            )
        }
            .onFailure { r.notice.value = "invalid_script" }
    }) {
        Text(s(R.string.enable_script))
    }
}

@Composable
fun McpPage(r: AppRuntime) {
    val settings by r.settings.collectAsStateWithLifecycle()
    val tools by r.mcpCatalog.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var local by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(id()) }
    Label(s(R.string.mcp))
    Info(s(R.string.mcp_help))
    settings.servers.forEach { server ->
        BoxCard {
            Text(server.name)
            Info(server.endpoint)
            TextButton({
                editing = server.id
                name = server.name
                endpoint = server.endpoint
                local = server.localOptIn
                r.mcpCatalog.value = emptyList()
            }) {
                Text(s(R.string.edit))
            }
            TextButton({
                r.stop()
                r.saveSettings(
                    settings.copy(
                        servers =
                            settings.servers.map {
                                if (it.id == server.id)
                                    it.copy(enabled = false, reviewed = emptyMap())
                                else it
                            },
                        policy = settings.policy.copy(mcp = settings.policy.mcp - server.id),
                    )
                )
            }) {
                Text(s(R.string.disable))
            }
        }
    }
    BoxCard {
        Field(name, R.string.provider_name, { name = it })
        Field(endpoint, R.string.endpoint, { endpoint = it })
        Field(token, R.string.api_key, { token = it }, true)
        Toggle(s(R.string.local_optin), local) { local = it }
        Button({
            runCatching {
                Destinations.url(endpoint, local)
                val previous = settings.servers.find { it.id == editing }
                if (previous?.endpoint != endpoint) r.vault.delete("secret-mcp-$editing")
                if (token.isNotBlank()) r.secret("mcp-$editing", endpoint, token)
                token = ""
                r.reviewMcp(McpServer(editing, name, endpoint, local))
            }
                .onFailure { r.notice.value = "invalid_destination" }
        }) {
            Text(s(R.string.inspect_tools))
        }
        tools.forEach { tool ->
            HorizontalDivider()
            Text(tool.name, fontWeight = FontWeight.Bold)
            Text(tool.description)
            Info(tool.schema.toString())
            Button({
                r.stop()
                r.saveSettings(
                    r.settings.value.copy(
                        servers =
                            r.settings.value.servers.map {
                                if (it.id == editing)
                                    it.copy(
                                        reviewed = it.reviewed + (tool.name to tool.fingerprint),
                                        schemas = it.schemas + (tool.name to tool.schema),
                                    )
                                else it
                            },
                        policy =
                            r.settings.value.policy.copy(
                                mcp =
                                    r.settings.value.policy.mcp +
                                        (editing to
                                            (r.settings.value.policy.mcp[editing].orEmpty() +
                                                tool.name))
                            ),
                    )
                )
            }) {
                Text(s(R.string.consent_tool))
            }
        }
    }
}

@Composable
fun DataPage(r: AppRuntime) {
    val settings by r.settings.collectAsStateWithLifecycle()
    val archive by r.archive.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var retention by remember { mutableStateOf(settings.retentionDays.toString()) }
    var pass by remember { mutableStateOf("") }
    var export by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<Archive?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    val write =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val bytes = export
            if (uri != null && bytes != null)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                                it.write(bytes)
                            }
                        }
                    }
                        .onFailure { r.notice.value = "export_failed" }
                    export = null
                }
        }
    val read =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                scope.launch {
                    try {
                        val phrase = pass.toCharArray()
                        pass = ""
                        preview =
                            withContext(Dispatchers.IO) {
                                val bytes =
                                    context.contentResolver.openInputStream(uri)!!.use {
                                        it.readBounded(Archives.MAX + 101)
                                    }
                                BackupCrypto.decrypt(bytes, phrase)
                            }
                    } catch (_: Exception) {
                        r.notice.value = "import_invalid"
                    }
                }
        }
    val diagnosticWrite =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
            uri ->
            if (uri != null)
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri, "wt")!!.use {
                                it.write(diagnostics.orEmpty().toByteArray())
                            }
                        }
                    }
                        .onFailure { r.notice.value = "export_failed" }
                }
        }
    Label(s(R.string.privacy))
    BoxCard {
        Field(retention, R.string.retention, { retention = it })
        Button({
            retention
                .toIntOrNull()
                ?.takeIf { it in 1..365 }
                ?.let {
                    r.saveSettings(settings.copy(retentionDays = it))
                    r.saveArchive(archive)
                }
        }) {
            Text(s(R.string.save))
        }
        Label(s(R.string.destinations))
        Info(s(R.string.destination_help))
        Text("OpenAI authentication: https://auth.openai.com")
        settings.profiles
            .filter { it.kind != ProviderKind.MOCK }
            .forEach { Text("${it.name}: ${it.endpoint}") }
        settings.servers.filter { it.enabled }.forEach { Text("${it.name}: ${it.endpoint}") }
    }
    Label(s(R.string.backup))
    Info(s(R.string.import_warning))
    Field(pass, R.string.passphrase, { pass = it }, true)
    Button(
        {
            scope.launch {
                try {
                    val phrase = pass.toCharArray()
                    pass = ""
                    export = withContext(Dispatchers.IO) { BackupCrypto.encrypt(archive, phrase) }
                    write.launch("magicphone-backup.mpb")
                } catch (_: Exception) {
                    r.notice.value = "backup_failed"
                }
            }
        },
        enabled = pass.length >= 12,
    ) {
        Text(s(R.string.export))
    }
    OutlinedButton(
        { read.launch(arrayOf("application/octet-stream", "*/*")) },
        enabled = pass.length >= 12,
    ) {
        Text(s(R.string.import_backup))
    }
    OutlinedButton({
        diagnostics =
            "MagicPhone ${BuildConfig.VERSION_NAME}\nAndroid API ${Build.VERSION.SDK_INT}\nAccessibility connected: ${r.connected.value}\nEvents (no arguments, messages, credentials or endpoints):\n" +
                archive.audits.takeLast(100).joinToString("\n") { "${it.operation}: ${it.status}" }
    }) {
        Text(s(R.string.diagnostics))
    }
    if (preview != null)
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(s(R.string.import_preview)) },
            text = {
                Column {
                    Text(s(R.string.import_warning))
                    Text(
                        "${preview!!.conversations.size} conversations · ${preview!!.scripts.size} scripts · ${preview!!.knowledge.size} notes"
                    )
                    preview!!.conversations.take(5).forEach { Text(it.title) }
                }
            },
            confirmButton = {
                Button({
                    val incoming = preview!!
                    r.stop()
                    r.saveArchive(
                        archive.copy(
                            conversations =
                                archive.conversations +
                                    incoming.conversations.map {
                                        it.copy(id = id(), parent = null)
                                    },
                            scripts =
                                archive.scripts +
                                    incoming.scripts.map { it.copy(id = id(), enabled = false) },
                            knowledge =
                                archive.knowledge +
                                    incoming.knowledge.map { it.copy(id = id(), reviewed = false) },
                        )
                    )
                    preview = null
                }) {
                    Text(s(R.string.confirm_import))
                }
            },
            dismissButton = { TextButton({ preview = null }) { Text(s(R.string.cancel)) } },
        )
    if (diagnostics != null)
        AlertDialog(
            onDismissRequest = { diagnostics = null },
            title = { Text(s(R.string.diagnostics)) },
            text = { Text(diagnostics!!, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton({ diagnosticWrite.launch("magicphone-diagnostics.txt") }) {
                    Text(s(R.string.export_diagnostics))
                }
            },
            dismissButton = { TextButton({ diagnostics = null }) { Text(s(R.string.cancel)) } },
        )
}
