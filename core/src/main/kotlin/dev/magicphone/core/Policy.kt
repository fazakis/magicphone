// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Decision {
    data object Allow : Decision

    data class Deny(val reason: String) : Decision

    data object Approval : Decision
}

class Policy(private val ownPackage: String) {
    /** Shared by app discovery, observation and dispatch. Explicit blocks always win. */
    fun appRule(app: String, config: PolicyConfig): AppRule {
        val selected = config.apps[app] ?: AppRule()
        if (app == ownPackage || protectedPackage(app)) return AppRule(deny = true)
        return if (config.allowAllApps && !selected.deny) AppRule(true, true) else selected
    }

    fun preflight(action: Action, config: PolicyConfig) {
        try {
            action.validate()
        } catch (_: IllegalArgumentException) {
            throw SafeFailure("invalid_action")
        }
        if (action.isDevice) {
            if (action.app == ownPackage || protectedPackage(action.app))
                throw SafeFailure("manual_security")
            val rule = appRule(action.app, config)
            if (rule.deny || !rule.observe || (action.op.mutates && !rule.mutate))
                throw SafeFailure("app_not_allowed")
        }
    }

    fun decide(action: Action, screen: Screen, config: PolicyConfig, now: Long): Decision {
        try {
            action.validate()
        } catch (_: IllegalArgumentException) {
            return Decision.Deny("invalid_action")
        }
        if (action.op in setOf(Op.MCP, Op.MCP_CATALOG)) {
            if (action.tool !in config.mcp[action.server].orEmpty())
                return Decision.Deny("mcp_consent")
            return if (action.op == Op.MCP_CATALOG) Decision.Allow else Decision.Approval
        }
        if (!action.isDevice) return Decision.Allow
        if (action.app == ownPackage || protectedPackage(action.app))
            return Decision.Deny("manual_security")
        val rule = appRule(action.app, config)
        if (rule.deny || !rule.observe || (action.op.mutates && !rule.mutate))
            return Decision.Deny("app_not_allowed")
        if (screen.locked) return Decision.Deny("device_locked")
        if (action.op != Op.OPEN) {
            if (screen.mixed || !screen.focused || screen.app != action.app)
                return Decision.Deny("screen_uncertain")
            if (screen.sensitive) return Decision.Deny("manual_secret")
            if (action.snapshot.isNotBlank() && action.snapshot != screen.id)
                return Decision.Deny("stale_target")
            if (action.node.isNotBlank()) {
                val node =
                    screen.nodes.find { it.ref == action.node }
                        ?: return Decision.Deny("stale_target")
                if (node.sensitive) return Decision.Deny("manual_secret")
                if (action.op.mutates && !node.enabled) return Decision.Deny("invalid_target")
                if (action.op == Op.TEXT && !node.editable) return Decision.Deny("invalid_target")
                if (action.op == Op.SCROLL && !node.scrollable)
                    return Decision.Deny("invalid_target")
                if (
                    screen.protectedRects.any {
                        it.contains(
                            (node.bounds.left + node.bounds.right) / 2,
                            (node.bounds.top + node.bounds.bottom) / 2,
                        )
                    }
                )
                    return Decision.Deny("protected_control")
            }
            if (action.coordinate) {
                if (
                    action.x >= screen.width ||
                        action.y >= screen.height ||
                        (action.op == Op.SWIPE &&
                            (action.x2 >= screen.width || action.y2 >= screen.height))
                )
                    return Decision.Deny("invalid_coordinates")
                if (screen.protectedRects.any { r ->
                    if (action.op == Op.SWIPE) {
                        // Conservatively protect every control inside the gesture's bounding box.
                        minOf(action.x, action.x2) <= r.right && maxOf(action.x, action.x2) >= r.left &&
                            minOf(action.y, action.y2) <= r.bottom && maxOf(action.y, action.y2) >= r.top
                    } else r.contains(action.x, action.y)
                }) return Decision.Deny("protected_control")
            }
        }
        if (!action.op.mutates) return Decision.Allow
        // Local opt-in skips approval only after all target and screen checks pass.
        if (config.allowAllApps) return Decision.Allow
        if (
            !action.coordinate &&
                action.op != Op.TEXT &&
                config.grants.any {
                    it.app == action.app && action.op in it.ops && it.expiresAt > now
                }
        )
            return Decision.Allow
        return Decision.Approval
    }

    private fun protectedPackage(pkg: String) =
        pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg.contains("permissioncontroller") ||
            pkg.contains("packageinstaller") ||
            pkg == "com.android.settings" ||
            pkg.endsWith(".settings")
}

data class Approval(val id: String, val action: Action, val binding: String, val expiresAt: Long)

interface DevicePort {
    // Package metadata only; never read another app's screen before policy preflight.
    suspend fun foregroundPackage(): String = ""

    suspend fun inspect(app: String): Screen

    suspend fun execute(action: Action, screen: Screen): ToolResult
}

interface ApprovalPort {
    suspend fun request(approval: Approval): Boolean
}

class RunBudget(
    val maxSteps: Int = 80,
    val maxMillis: Long = 600_000,
    private val now: () -> Long,
) {
    private val start = now()
    private var steps = 0
    private var failures = 0

    fun consume() {
        if (++steps > maxSteps || now() - start > maxMillis) throw SafeFailure("run_budget")
    }

    fun result(success: Boolean) {
        if (success) failures = 0 else if (++failures >= 3) throw SafeFailure("repeated_failure")
    }
}

/** The only dispatch boundary. Neither model responses nor imported data can mint approval. */
class Gateway(
    private val policy: Policy,
    private val config: () -> PolicyConfig,
    private val device: DevicePort,
    private val approvals: ApprovalPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val event: suspend (Action, String) -> Unit = { _, _ -> },
    private val remote: suspend (Action) -> ToolResult = { throw SafeFailure("mcp_disabled") },
) {
    private val mutex = Mutex()
    @Volatile private var generation = 0L
    @Volatile private var stopped = true
    @Volatile private var paused = false

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    private var budget = RunBudget(now = now)

    fun start() {
        generation++
        stopped = false
        paused = false
        budget = RunBudget(now = now)
    }

    fun stop() {
        stopped = true
        generation++
    }

    suspend fun permittedForegroundPackage(): String? {
        val run = generation
        if (stopped) throw CancellationException("stopped")
        val app = device.foregroundPackage()
        if (stopped || run != generation) throw CancellationException("stopped")
        return app.takeIf { it.isNotBlank() && policy.appRule(it, config()).let { rule -> rule.observe && !rule.deny } }
    }

    suspend fun run(action: Action): ToolResult = mutex.withLock {
        val run = generation
        fun live() {
            if (stopped || run != generation) throw CancellationException("stopped")
        }
        suspend fun boundary() {
            while (paused) {
                live()
                delay(50)
            }
            live()
            currentCoroutineContext().ensureActive()
        }
        boundary()
        budget.consume()
        // App permission is checked before inspecting any window tree.
        val conf = config()
        policy.preflight(action, conf)
        var screen =
            if (action.isDevice) device.inspect(action.app)
            else Screen(locked = false, mixed = false)
        var decision = policy.decide(action, screen, conf, now())
        if (decision is Decision.Deny) throw SafeFailure(decision.reason)
        if (decision == Decision.Approval) {
            val proposal = Approval(id(), action, action.fingerprint(screen), now() + 60_000)
            if (!withTimeout(60_000) { approvals.request(proposal) })
                throw SafeFailure("approval_denied")
            boundary()
            if (now() >= proposal.expiresAt) throw SafeFailure("approval_expired")
            screen = if (action.isDevice) device.inspect(action.app) else screen
            if (action.fingerprint(screen) != proposal.binding) throw SafeFailure("stale_approval")
            decision = policy.decide(action, screen, config(), now())
            if (decision is Decision.Deny) throw SafeFailure(decision.reason)
        }
        boundary()
        event(
            action,
            "dispatching",
        ) // Persist before dispatch; never replay this after process death.
        boundary() // Persistence may suspend while the user pauses or stops the run.
        policy.preflight(action, config())
        val result =
            try {
                withTimeout(15_000) {
                    when (action.op) {
                        Op.MCP,
                        Op.MCP_CATALOG -> remote(action)
                        Op.WAIT -> {
                            delay(action.millis.toLong())
                            ToolResult("waited")
                        }
                        else -> device.execute(action, screen)
                    }
                }
            } catch (e: CancellationException) {
                event(action, "uncertain")
                throw e
            } catch (e: SafeFailure) {
                event(action, "failed")
                throw e
            } catch (_: Exception) {
                event(action, "uncertain")
                throw SafeFailure("action_uncertain")
            }
        live()
        event(action, result.status)
        budget.result(result.status !in setOf("failed", "uncertain"))
        result
    }

    suspend fun batch(actions: List<Action>): List<ToolResult> {
        require(actions.size in 1..20)
        return actions.map { run(it) }
    }
}
