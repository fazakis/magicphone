// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.app.Application
import android.content.Intent
import dev.magicphone.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    val policy: PolicyConfig = PolicyConfig(),
    val profiles: List<Profile> = emptyList(),
    val selected: String = "",
    val accounts: List<Account> = emptyList(),
    val activeAccount: String = "",
    val host: String = "urn:uuid:${id()}",
    val servers: List<McpServer> = emptyList(),
    val retentionDays: Int = 30,
    // Decode existing installations without re-enabling the retired overlay controls.
    val floating: Boolean = false,
    val secondary: String = "",
)

class MagicApp : Application() {
    lateinit var runtime: AppRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = AppRuntime(this)
    }
}

val android.content.Context.runtime
    get() = (applicationContext as MagicApp).runtime

data class InputRequest(val conversation: String, val message: String, val id: String = id())

class AppRuntime(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val vault = Vault(app)
    val notice = MutableStateFlow("")
    val settings = MutableStateFlow(loadSettings())
    val archive = MutableStateFlow(loadArchive())
    val current = MutableStateFlow<String?>(archive.value.conversations.maxByOrNull { it.updated }?.id)
    val visibleChat = MutableStateFlow<String?>(null)
    val inputRequest = MutableStateFlow<InputRequest?>(null)
    private val historyWriter = SnapshotWriter<Archive>(scope, failed = {
        scope.launch { stop(); notice.value = "storage_recovery" }
    }) { value ->
        vault.write("history", JsonCodec.encodeToString(Archive.serializer(), Archives.clean(value)))
    }
    val approval = MutableStateFlow<Approval?>(null)
    val models = MutableStateFlow<List<ModelChoice>>(emptyList())
    val modelsLoading = MutableStateFlow(false)
    val modelsError = MutableStateFlow("")
    private var modelJob: Job? = null
    private var modelEpoch = 0L
    private var modelsOwner = ""
    val connected = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val mcpCatalog = MutableStateFlow<List<McpTool>>(emptyList())
    var phone: PhoneService? = null
    private var approvalAnswer: CompletableDeferred<Boolean>? = null
    private var login: LoginAttempt? = null
    private var catalogJob: Job? = null
    private var catalogEpoch = 0L
    private val auth = ChatGptAuth()
    private val port =
        object : DevicePort {
            override suspend fun foregroundPackage() = withContext(Dispatchers.Main.immediate) {
                phone?.foregroundPackage().orEmpty()
            }

            override suspend fun inspect(app: String) =
                withContext(Dispatchers.Main.immediate) {
                    phone?.inspect(app) ?: throw SafeFailure("accessibility_missing")
                }

            override suspend fun execute(action: Action, screen: Screen): ToolResult =
                withContext(Dispatchers.Main.immediate) {
                    when (action.op) {
                        Op.PLAN,
                        Op.CHECKLIST -> ToolResult("updated", Sanitizer.text(action.text))
                        Op.ASK -> ToolResult("waiting_user")
                        Op.COMPLETE -> ToolResult("reported", Sanitizer.text(action.text))
                        Op.MEMORY_PROPOSAL -> {
                            saveArchive(
                                archive.value.copy(
                                    knowledge =
                                        archive.value.knowledge +
                                            Knowledge(
                                                app = action.app,
                                                title = "Suggested memory",
                                                content = Sanitizer.text(action.text),
                                            )
                                )
                            )
                            ToolResult("pending_local_review")
                        }
                        Op.APPS ->
                            ToolResult(
                                "apps",
                                launchable()
                                    .filter {
                                        Policy(app.packageName).appRule(it.first, settings.value.policy)
                                            .let { rule -> rule.observe && !rule.deny }
                                    }
                                    .joinToString("\n") { "${it.first}: ${it.second}" },
                            )
                        else ->
                            phone?.perform(action, screen)
                                ?: throw SafeFailure("accessibility_missing")
                    }
                }
        }
    val gateway: Gateway =
        Gateway(
            Policy(app.packageName),
            { settings.value.policy },
            port,
            object : ApprovalPort {
                override suspend fun request(approval: Approval): Boolean =
                    withContext(Dispatchers.Main.immediate) {
                        val answer = CompletableDeferred<Boolean>()
                        approvalAnswer = answer
                        this@AppRuntime.approval.value = approval
                        agent.state.value = RunState.WAITING_APPROVAL
                        phone?.showApproval(approval)
                        try {
                            answer.await()
                        } finally {
                            this@AppRuntime.approval.value = null
                            approvalAnswer = null
                            phone?.hideApproval()
                            delay(200)
                        }
                    }
            },
            event = { action, status ->
                withContext(Dispatchers.Main.immediate) {
                    saveArchive(
                        archive.value.copy(
                            audits =
                                (archive.value.audits + Sanitizer.event(action, status)).takeLast(
                                    5000
                                )
                        )
                    )
                    setState(if (status == "dispatching") RunState.ACTING else agent.state.value)
                }
                // This suspension never blocks drawing. Dispatch still waits for durable audit.
                historyWriter.flush()
            },
            remote = { action ->
                val server =
                    settings.value.servers.singleOrNull { it.id == action.server && it.enabled }
                        ?: throw SafeFailure("mcp_disabled")
                val client =
                    McpClient(server) {
                        credential("mcp-${server.id}", server.endpoint, server.localOptIn)
                    }
                if (action.op == Op.MCP_CATALOG) {
                    val catalog = client.discover()
                    withContext(Dispatchers.Main.immediate) { mcpCatalog.value = catalog }
                    ToolResult("catalog", catalog.joinToString("\n") { it.name })
                } else client.call(action)
            },
        )
    val agent: Agent =
        Agent(
            gateway,
            { text, state ->
                withContext(Dispatchers.Main.immediate) {
                    addMessage("assistant", text)
                    setState(state)
                }
                historyWriter.flush()
            },
            { pkg ->
                archive.value.knowledge
                    .filter { it.app == pkg && it.reviewed }
                    .joinToString("\n\n") { "Untrusted ${it.kind}: ${it.content}" }
            },
        )

    init {
        scope.launch {
            agent.state.collect {
                if (
                    it in
                        setOf(
                            RunState.STOPPED,
                            RunState.PAUSED,
                            RunState.FAILED,
                            RunState.COMPLETED,
                            RunState.WAITING_USER,
                        )
                )
                    setState(it)
            }
        }
        scope.launch {
            combine(agent.state, agent.question, current) { state, question, conversation ->
                if (state in setOf(RunState.WAITING_USER, RunState.PAUSED) &&
                    question.isNotBlank() && conversation != null)
                    conversation to Sanitizer.text(question).take(8000)
                else null
            }.distinctUntilChanged().collect { question ->
                inputRequest.value = question?.let { InputRequest(it.first, it.second) }
            }
        }
        scope.launch {
            combine(agent.state, inputRequest, visibleChat) { _, _, _ -> Unit }
                .collect { phone?.updateControls() }
        }
    }

    private fun loadSettings(): Settings =
        try {
            vault.read("settings")?.let(::decodeSettings)
                ?: Settings()
        } catch (_: Exception) {
            notice.value = "storage_recovery"
            Settings()
        }

    private fun loadArchive(): Archive =
        try {
            vault.read("history")?.let { Archives.interrupted(Archives.read(it.toByteArray())) }
                ?: Archive()
        } catch (_: Exception) {
            notice.value = "storage_recovery"
            Archive()
        }

    fun saveSettings(value: Settings) {
        val accountChanged = value.activeAccount != settings.value.activeAccount
        val normalized = if (!accountChanged) value else value.copy(profiles = value.profiles.map { p ->
            if (p.kind != ProviderKind.CHATGPT) p else {
                val next = p.copy(modelChoice = null, serviceTier = null)
                next.copy(reasoningEffort = next.reasoningEffort?.takeIf { it in ModelOptions.reasoning(next) })
            }
        })
        if (modelContext(normalized) != modelContext(settings.value)) {
            modelEpoch++
            modelJob?.cancel()
            models.value = emptyList()
            modelsLoading.value = false
            modelsError.value = ""
            modelsOwner = ""
        }
        vault.write("settings", JsonCodec.encodeToString(Settings.serializer(), normalized))
        settings.value = normalized
    }

    fun setAllowAllApps(enabled: Boolean) {
        stop()
        val current = settings.value
        saveSettings(current.copy(policy = current.policy.copy(
            allowAllApps = enabled,
        )))
    }

    fun saveArchive(value: Archive) {
        val cutoff = System.currentTimeMillis() - settings.value.retentionDays * 86400000L
        val clean =
            value.copy(
                conversations =
                    value.conversations.filter { it.updated >= cutoff }.takeLast(500)
            )
        archive.value = clean
        historyWriter.submit(clean)
    }

    suspend fun flushHistory() = historyWriter.flush()

    fun localApproval(approved: Boolean) {
        approvalAnswer?.complete(approved)
    }

    fun stop() {
        catalogEpoch++
        catalogJob?.cancel()
        localApproval(false)
        agent.stop()
        phone?.hideApproval()
        phone?.hideInputBubble()
    }

    fun pauseForChat() {
        // WAITING_USER already suspends execution until Send. Do not add a second Resume step.
        if (agent.state.value != RunState.WAITING_USER) agent.pause()
    }

    fun launchable(): List<Pair<String, String>> =
        app.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                0,
            )
            .map { it.activityInfo.packageName to it.loadLabel(app.packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second }

    fun selectConversation(id: String) {
        stop()
        agent.clearView()
        current.value = id
        saveArchive(archive.value.copy(conversations = archive.value.conversations.map {
            if (it.id == id) it.copy(updated = System.currentTimeMillis()) else it
        }))
    }

    fun newConversation() {
        stop()
        agent.clearView()
        val c = Conversation(title = app.getString(R.string.new_task))
        saveArchive(archive.value.copy(conversations = archive.value.conversations + c))
        current.value = c.id
    }

    fun addMessage(role: String, text: String) {
        if (current.value == null) newConversation()
        val c = archive.value.conversations.find { it.id == current.value } ?: return
        val safe = Sanitizer.text(text)
        val updated =
            c.copy(
                title = if (c.messages.isEmpty() && role == "user") safe.take(80) else c.title,
                messages = (c.messages + Message(role = role, text = safe)).takeLast(1000),
                updated = System.currentTimeMillis(),
            )
        saveArchive(
            archive.value.copy(
                conversations =
                    archive.value.conversations.map { if (it.id == c.id) updated else it }
            )
        )
    }

    fun setState(state: RunState) {
        if (current.value == null) return
        if (archive.value.conversations.find { it.id == current.value }?.state == state) return
        saveArchive(
            archive.value.copy(
                conversations =
                    archive.value.conversations.map {
                        if (it.id == current.value)
                            it.copy(state = state, updated = System.currentTimeMillis())
                        else it
                    }
            )
        )
    }

    fun deleteConversation(id: String) {
        if (current.value == id) {
            stop()
            current.value = null
        }
        saveArchive(
            archive.value.copy(
                conversations = archive.value.conversations.filterNot { it.id == id }
            )
        )
    }

    fun branch(conversation: String, message: String) {
        stop()
        agent.clearView()
        val c = archive.value.conversations.single { it.id == conversation }
        val index = c.messages.indexOfFirst { it.id == message }
        require(index >= 0)
        val branch =
            c.copy(
                id = id(),
                title = c.title.take(70) + " ↗",
                messages = c.messages.take(index + 1),
                parent = c.id,
                state = RunState.IDLE,
                updated = System.currentTimeMillis(),
            )
        saveArchive(archive.value.copy(conversations = archive.value.conversations + branch))
        current.value = branch.id
    }

    fun secret(id: String, origin: String, value: String) {
        val u = Destinations.url(origin, true)
        vault.write(
            "secret-$id",
            JsonCodec.encodeToString(
                JsonObject.serializer(),
                obj("origin" to j(Destinations.origin(u)), "value" to j(value)),
            ),
        )
    }

    fun credential(id: String, origin: String, local: Boolean = false): BoundSecret? {
        val raw = vault.read("secret-$id") ?: return null
        val o = JsonCodec.parseToJsonElement(raw).jsonObject
        require(o.str("origin") == Destinations.origin(Destinations.url(origin, local)))
        return BoundSecret(o.str("origin"), o.str("value"))
    }

    fun saveProfile(profile: Profile, key: String) {
        stop()
        Destinations.profile(profile)
        val previous = settings.value.profiles.find { it.id == profile.id }
        if (previous?.endpoint != profile.endpoint) vault.delete("secret-${profile.id}")
        if (key.isNotBlank()) secret(profile.id, profile.endpoint, key)
        saveSettings(
            settings.value.copy(
                profiles = settings.value.profiles.filterNot { it.id == profile.id } + profile,
                selected = profile.id,
            )
        )
        models.value = emptyList()
    }

    private fun loadAccount(client: String): Account =
        vault.read("account-${digest(client).take(40)}")?.let {
            JsonCodec.decodeFromString(Account.serializer(), it)
        } ?: throw SafeFailure("sign_in_required")

    private fun saveAccount(account: Account) {
        vault.write(
            "account-${digest(account.client).take(40)}",
            JsonCodec.encodeToString(Account.serializer(), account),
        )
    }

    fun signIn(openBrowser: (String) -> Unit, returning: String? = null) {
        if (busy.value) return
        stop()
        busy.value = true
        scope.launch {
            try {
                val saved = returning?.let {
                    settings.value.accounts
                        .single { a -> a.client == it }
                        .copy(identity = runCatching { loadAccount(it).identity }.getOrDefault(""))
                }
                val attempt = withContext(Dispatchers.IO) { LoginAttempt(saved) }
                login?.close()
                login = attempt
                openBrowser(
                    attempt.pending.authorize(settings.value.host, saved?.identity.orEmpty())
                )
                val (code, client) = withTimeout(180_000) { attempt.awaitCode() }
                val account = auth.exchange(attempt.pending, code, client)
                saveAccount(account)
                val profile =
                    Profile(
                        id = "chatgpt",
                        name = "ChatGPT",
                        kind = ProviderKind.CHATGPT,
                        images = true,
                    )
                saveSettings(
                    settings.value.copy(
                        accounts =
                            settings.value.accounts.filterNot { it.client == client } +
                                account.copy(access = "", refresh = "", identity = ""),
                        activeAccount = client,
                        profiles =
                            settings.value.profiles.filterNot { it.id == "chatgpt" } + profile,
                        selected = "chatgpt",
                    )
                )
                discoverModels()
            } catch (e: Exception) {
                notice.value = (e as? SafeFailure)?.code ?: "sign_in_failed"
            } finally {
                login?.close()
                login = null
                busy.value = false
            }
        }
    }

    fun logout(client: String) {
        stop()
        scope.launch {
            var revoked = false
            try {
                revoked = auth.logout(loadAccount(client))
            } catch (_: Exception) {} finally {
                vault.delete("account-${digest(client).take(40)}")
                models.value = emptyList()
                if (settings.value.activeAccount == client)
                    saveSettings(settings.value.copy(activeAccount = ""))
                notice.value = if (revoked) "signed_out" else "revocation_unconfirmed"
            }
        }
    }

    private fun chatGptProvider(profile: Profile): ModelProvider {
        val client = settings.value.activeAccount
        return ResponsesProvider(profile) {
            if (client.isEmpty()) throw SafeFailure("sign_in_required")
            val a = auth.fresh({ loadAccount(client) }, ::saveAccount)
            BoundSecret("https://api.openai.com:443", a.access)
        }
    }

    fun provider(profile: Profile): ModelProvider =
        when (profile.kind) {
            ProviderKind.MOCK -> MockProvider()
            ProviderKind.CHATGPT ->
                chatGptProvider(profile)
            ProviderKind.OPENAI ->
                ResponsesProvider(profile) {
                    credential(profile.id, profile.endpoint)
                        ?: throw SafeFailure("api_key_required")
                }
            ProviderKind.COMPATIBLE ->
                CompatibleProvider(profile) {
                    credential(profile.id, profile.endpoint, profile.localOptIn)
                }
        }

    private fun modelContext(s: Settings): String {
        val p = s.profiles.find { it.id == s.selected }
        return "${p?.id}|${p?.kind}|${p?.endpoint}|${if (p?.kind == ProviderKind.CHATGPT) s.activeAccount else ""}"
    }

    fun discoverModels(refresh: Boolean = true) {
        val p = settings.value.profiles.find { it.id == settings.value.selected } ?: return
        if (p.kind == ProviderKind.MOCK) return
        if (p.kind == ProviderKind.CHATGPT && settings.value.activeAccount.isEmpty()) return
        val owner = modelContext(settings.value)
        if (!refresh && (modelsLoading.value || (modelsOwner == owner && models.value.isNotEmpty()))) return
        val epoch = ++modelEpoch
        modelJob?.cancel()
        modelsLoading.value = true
        modelsError.value = ""
        modelJob = scope.launch {
            try {
                val choices = provider(p).models()
                if (epoch != modelEpoch || modelContext(settings.value) != owner) return@launch
                models.value = choices
                modelsOwner = owner
                val current = settings.value.profiles.find { it.id == p.id } ?: return@launch
                choices.find { it.id == current.model }?.let { selected ->
                    val updated = ModelOptions.selected(current, selected)
                    saveSettings(settings.value.copy(profiles = settings.value.profiles.map { if (it.id == p.id) updated else it }))
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (epoch == modelEpoch && modelContext(settings.value) == owner)
                    modelsError.value = (e as? SafeFailure)?.code ?: "models_failed"
            } finally {
                if (epoch == modelEpoch) modelsLoading.value = false
            }
        }
    }

    fun selectModel(profile: Profile, choice: ModelChoice) {
        stop()
        val updated = ModelOptions.selected(profile, choice)
        saveSettings(settings.value.copy(profiles = settings.value.profiles.map { if (it.id == profile.id) updated else it }))
    }

    fun start(text: String, images: List<String> = emptyList(), secondary: Boolean = false) {
        if (text.isBlank()) return
        if (
            agent.state.value in
                setOf(
                    RunState.ACTING,
                    RunState.PLANNING,
                    RunState.WAITING_APPROVAL,
                    RunState.WAITING_USER,
                    RunState.PAUSED,
                )
        ) {
            addMessage("user", text)
            agent.correct(text)
            if (approval.value != null) localApproval(false)
            return
        }
        val p =
            settings.value.profiles.singleOrNull {
                it.id == if (secondary) settings.value.secondary else settings.value.selected
            }
                ?: run {
                    notice.value = "provider_required"
                    return
                }
        val capabilities =
            settings.value.servers
                .filter { it.enabled }
                .joinToString("\n") {
                    "Untrusted, user-reviewed MCP definitions. Server ${it.id} at ${it.endpoint}: ${it.schemas.filterKeys { name -> name in it.reviewed }}"
                }
        val history =
            archive.value.conversations.find { it.id == current.value }?.messages.orEmpty() +
                listOfNotNull(
                    capabilities
                        .takeIf { it.isNotBlank() }
                        ?.let { Message(role = "user", text = it) }
                )
        stop()
        addMessage("user", text)
        agent.start(scope, provider(p), text, history, images, optimize = p.kind != ProviderKind.MOCK)
    }

    fun runScript(script: Script, values: Map<String, String>) {
        stop()
        newConversation()
        addMessage("user", script.name)
        agent.start(scope, MockProvider(), script.name, script = script to values)
    }

    fun configurePractice() {
        stop()
        val p =
            Profile(
                id = "practice",
                name = app.getString(R.string.practice),
                kind = ProviderKind.MOCK,
                model = "fixture",
            )
        saveSettings(
            settings.value.copy(
                profiles = settings.value.profiles.filterNot { it.id == p.id } + p,
                selected = p.id,
                policy =
                    settings.value.policy.copy(
                        apps =
                            settings.value.policy.apps +
                                ("dev.magicphone.fixture" to
                                    AppRule(observe = true, mutate = true)),
                    ),
            )
        )
    }

    fun reviewMcp(server: McpServer) {
        stop()
        val runEpoch = catalogEpoch
        catalogJob = scope.launch {
            try {
                saveSettings(
                    settings.value.copy(
                        servers =
                            settings.value.servers.filterNot { it.id == server.id } +
                                server.copy(enabled = true),
                        policy =
                            settings.value.policy.copy(
                                mcp =
                                    settings.value.policy.mcp + (server.id to setOf("__catalog__"))
                            ),
                    )
                )
                gateway.start()
                gateway.run(Action(Op.MCP_CATALOG, server = server.id, tool = "__catalog__"))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice.value = (e as? SafeFailure)?.code ?: "mcp_failed"
            } finally {
                if (runEpoch == catalogEpoch) gateway.stop()
            }
        }
    }
}
