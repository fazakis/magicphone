// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class PolicyTest {
    private val pkg = "test.fixture"
    private val policy = Policy("dev.magicphone.app")
    private val config = PolicyConfig(apps = mapOf(pkg to AppRule(true, true)))
    private val screen =
        Screen(
            "s1",
            pkg,
            1,
            1,
            100,
            200,
            0,
            true,
            false,
            false,
            false,
            listOf(Node("s1:0", "Send", Rect(0, 20, 80, 80), true, true, true)),
        )

    private fun tap() = Action(Op.TAP, pkg, "s1", "s1:0")

    @Test
    fun automaticAccessDefaultsOffInExistingSettings() {
        val old = JsonCodec.decodeFromString(PolicyConfig.serializer(), """{}""")
        assertFalse(old.allowAllApps)
        assertEquals(Decision.Deny("app_not_allowed"), policy.decide(tap(), screen, old, 0))
        assertEquals(Decision.Approval, policy.decide(tap(), screen, config, 0))
    }

    @Test
    fun automaticAccessAllowsUnlistedAppsAndOrdinaryActions() {
        val automatic = PolicyConfig(allowAllApps = true)
        val actions = listOf(
            Action(Op.OBSERVE, pkg), Action(Op.OPEN, pkg), tap(),
            Action(Op.TAP, pkg, "s1", x = 30, y = 40),
            tap().copy(op = Op.TEXT, text = "δοκιμή"),
            Action(Op.SCROLL, pkg, "s1", "s1:0", text = "forward"),
        )
        actions.forEach {
            policy.preflight(it, automatic)
            assertEquals(Decision.Allow, policy.decide(it, screen, automatic, 0), it.op.name)
        }
        assertEquals(AppRule(true, true), policy.appRule("newly.installed", automatic))
        assertEquals(AppRule(true, true), policy.appRule(pkg, automatic.copy(apps = mapOf(pkg to AppRule()))))
    }

    @Test
    fun automaticAccessPreservesSafetyAndExplicitBlocks() {
        val automatic = config.copy(allowAllApps = true)
        val blocked = automatic.copy(apps = mapOf(pkg to AppRule(true, true, true)))
        assertEquals(Decision.Deny("app_not_allowed"), policy.decide(tap(), screen, blocked, 0))
        assertFailsWith<SafeFailure> { policy.preflight(Action(Op.OBSERVE, pkg), blocked) }
        for (p in listOf("dev.magicphone.app", "com.android.settings", "com.android.systemui", "com.google.android.permissioncontroller")) {
            assertTrue(policy.appRule(p, automatic).deny)
            assertEquals(Decision.Deny("manual_security"), policy.decide(Action(Op.OPEN, p), screen, automatic, 0))
        }
        for (unsafe in listOf(
            screen.copy(locked = true), screen.copy(mixed = true), screen.copy(sensitive = true),
            screen.copy(focused = false), screen.copy(app = "another.app"),
            screen.copy(protectedRects = listOf(Rect(0, 0, 100, 100))),
        )) assertIs<Decision.Deny>(policy.decide(tap(), unsafe, automatic, 0))
        assertEquals(Decision.Deny("stale_target"), policy.decide(tap().copy(snapshot = "old"), screen, automatic, 0))
        assertEquals(Decision.Deny("manual_secret"), policy.decide(
            tap().copy(op = Op.TEXT, text = "secret"),
            screen.copy(nodes = screen.nodes.map { it.copy(sensitive = true) }), automatic, 0,
        ))
        val remote = Action(Op.MCP, server = "server", tool = "tool")
        assertEquals(Decision.Deny("mcp_consent"), policy.decide(remote, screen, automatic, 0))
        assertEquals(Decision.Approval, policy.decide(remote, screen, automatic.copy(mcp = mapOf("server" to setOf("tool"))), 0))
    }

    @Test
    fun automaticGatewaySkipsApprovalsAndRevocationRestoresThem() = runTest {
        var active = PolicyConfig(allowAllApps = true)
        val device = Device(screen)
        var requests = 0
        val gate = Gateway(policy, { active }, device, object : ApprovalPort {
            override suspend fun request(approval: Approval): Boolean { requests++; return false }
        })
        gate.start()
        gate.batch(listOf(Action(Op.OPEN, pkg), tap(), tap().copy(op = Op.TEXT, text = "ordinary")))
        assertEquals(3, device.executions)
        assertEquals(0, requests)
        active = active.copy(allowAllApps = false)
        assertEquals("app_not_allowed", assertFailsWith<SafeFailure> { gate.run(Action(Op.OBSERVE, pkg)) }.code)
        assertEquals(3, device.inspectCount)
        active = config
        assertEquals("approval_denied", assertFailsWith<SafeFailure> { gate.run(tap()) }.code)
        assertEquals(1, requests)
        assertEquals(3, device.executions)
    }

    @Test
    fun englishAndGreekNeedSameApproval() {
        for (label in listOf("Send", "Αποστολή", "Pay", "Πληρωμή")) assertEquals(
            Decision.Approval,
            policy.decide(
                tap(),
                screen.copy(nodes = screen.nodes.map { it.copy(label = label) }),
                config,
                0,
            ),
        )
    }

    @Test
    fun coordinatesNeedApprovalEvenWithGrant() {
        val c = config.copy(grants = listOf(Grant(pkg, setOf(Op.TAP), 1000)))
        assertEquals(
            Decision.Approval,
            policy.decide(Action(Op.TAP, pkg, "s1", x = 30, y = 40), screen, c, 0),
        )
    }

    @Test
    fun staleReferenceRejected() {
        assertIs<Decision.Deny>(policy.decide(tap().copy(snapshot = "old"), screen, config, 0))
        assertIs<Decision.Deny>(policy.decide(tap().copy(node = "old:0"), screen, config, 0))
    }

    @Test
    fun missingContextRejected() {
        assertIs<Decision.Deny>(policy.decide(Action(Op.TAP, pkg, x = 3, y = 4), screen, config, 0))
    }

    @Test
    fun readOnlyAppsBlockEveryUnadvertisedMutation() {
        Op.entries
            .filter { it.mutates }
            .forEach { op ->
                assertIs<Decision.Deny>(
                    policy.decide(tap().copy(op = op), screen, config.copy(apps = mapOf(pkg to AppRule(true, false))), 0),
                    op.name,
                )
            }
    }

    @Test
    fun emptyPlanNeverChangesPolicy() {
        val before = config.copy(apps = mapOf(pkg to AppRule(true, false)))
        policy.decide(Action(Op.PLAN), screen, before, 0)
        assertIs<Decision.Deny>(policy.decide(tap(), screen, before, 0))
    }

    @Test
    fun denyOverridesAllowAndGrant() {
        val c =
            config.copy(
                apps = mapOf(pkg to AppRule(true, true, true)),
                grants = listOf(Grant(pkg, setOf(Op.TAP), 1000)),
            )
        assertIs<Decision.Deny>(policy.decide(tap(), screen, c, 0))
        assertIs<Decision.Deny>(policy.decide(Action(Op.OBSERVE, pkg), screen, c, 0))
    }

    @Test
    fun separateReadAndWrite() {
        val c = config.copy(apps = mapOf(pkg to AppRule(true, false)))
        assertEquals(Decision.Allow, policy.decide(Action(Op.OBSERVE, pkg), screen, c, 0))
        assertIs<Decision.Deny>(policy.decide(tap(), screen, c, 0))
    }

    @Test
    fun ownAndSecurityPackagesProtected() {
        for (p in
            listOf(
                "dev.magicphone.app",
                "com.android.settings",
                "com.google.android.permissioncontroller",
            )) assertIs<Decision.Deny>(
            policy.decide(
                Action(Op.OPEN, p),
                screen,
                config.copy(apps = mapOf(p to AppRule(true, true))),
                0,
            )
        )
    }

    @Test
    fun mixedWindowsAndSecretsNotObserved() {
        listOf(
                screen.copy(mixed = true),
                screen.copy(sensitive = true),
                screen.copy(locked = true),
                screen.copy(focused = false),
                screen.copy(app = "other.app"),
            )
            .forEach {
                assertIs<Decision.Deny>(policy.decide(Action(Op.OBSERVE, pkg), it, config, 0))
            }
    }

    @Test
    fun partialFlagDoesNotRelaxWindowOrSensitiveScreenGuards() {
        for (s in listOf(screen.copy(mixed = true), screen.copy(focused = false),
                screen.copy(sensitive = true), screen.copy(locked = true), screen.copy(app = "other.app"))) {
            assertIs<Decision.Deny>(policy.decide(Action(Op.OBSERVE, pkg), s.copy(partial = true), config, 0))
            assertIs<Decision.Deny>(policy.decide(tap(), s.copy(partial = true), config, 0))
        }
    }

    @Test
    fun protectedOverlayBlocksCoordinatesAndCoveredNodes() {
        val s = screen.copy(protectedRects = listOf(Rect(0, 0, 100, 100)))
        assertIs<Decision.Deny>(policy.decide(tap(), s, config, 0))
        assertIs<Decision.Deny>(
            policy.decide(Action(Op.TAP, pkg, "s1", x = 20, y = 30), s, config, 0)
        )
    }

    @Test
    fun floatingControlsOnlyBlockGesturesThatCouldTouchThem() {
        val s = screen.copy(protectedRects = listOf(Rect(10, 10, 30, 30)))
        val outside = Action(Op.TAP, pkg, "s1", x = 60, y = 100)
        assertEquals(Decision.Approval, policy.decide(outside, s, config, 0))
        val automatic = config.copy(allowAllApps = true)
        assertEquals(Decision.Allow, policy.decide(outside, s, automatic, 0))
        assertEquals(Decision.Deny("protected_control"), policy.decide(outside.copy(x = 20, y = 20), s, automatic, 0))
        val swipe = outside.copy(op = Op.SWIPE, x = 0, y = 0, x2 = 90, y2 = 90)
        assertEquals(Decision.Deny("protected_control"), policy.decide(swipe, s, automatic, 0))
        assertEquals(Decision.Allow, policy.decide(swipe.copy(y = 100, y2 = 150), s, automatic, 0))
    }

    @Test
    fun expiryAndRevocation() {
        val grant = Grant(pkg, setOf(Op.TAP), 100)
        assertEquals(
            Decision.Allow,
            policy.decide(tap(), screen, config.copy(grants = listOf(grant)), 99),
        )
        assertEquals(
            Decision.Approval,
            policy.decide(tap(), screen, config.copy(grants = listOf(grant)), 100),
        )
        assertEquals(Decision.Approval, policy.decide(tap(), screen, config, 0))
    }

    private class Device(var screen: Screen) : DevicePort {
        var inspectCount = 0
        var executions = 0

        override suspend fun inspect(app: String): Screen {
            inspectCount++
            return screen
        }

        override suspend fun execute(action: Action, screen: Screen): ToolResult {
            executions++
            return ToolResult("dispatched")
        }
    }

    @Test
    fun blockedAppNotEvenInspected() = runTest {
        val device = Device(screen)
        val gate =
            Gateway(
                policy,
                { PolicyConfig() },
                device,
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = true
                },
            )
        gate.start()
        assertFailsWith<SafeFailure> { gate.run(Action(Op.OBSERVE, pkg)) }
        assertEquals(0, device.inspectCount)
    }

    @Test
    fun changedScreenDuringApprovalNeverDispatches() = runTest {
        val device = Device(screen)
        val gate =
            Gateway(
                policy,
                { config },
                device,
                object : ApprovalPort {
                    override suspend fun request(approval: Approval): Boolean {
                        device.screen = screen.copy(revision = 2)
                        return true
                    }
                },
            )
        gate.start()
        assertEquals("stale_approval", assertFailsWith<SafeFailure> { gate.run(tap()) }.code)
        assertEquals(0, device.executions)
    }

    @Test
    fun expiryCheckedAfterApproval() = runTest {
        var clock = 0L
        val device = Device(screen)
        val gate =
            Gateway(
                policy,
                { config },
                device,
                object : ApprovalPort {
                    override suspend fun request(approval: Approval): Boolean {
                        clock = 61000
                        return true
                    }
                },
                now = { clock },
            )
        gate.start()
        assertEquals("approval_expired", assertFailsWith<SafeFailure> { gate.run(tap()) }.code)
        assertEquals(0, device.executions)
    }

    @Test
    fun approvalCannotBeReplayed() = runTest {
        var approvals = 0
        val device = Device(screen)
        val gate =
            Gateway(
                policy,
                { config },
                device,
                object : ApprovalPort {
                    override suspend fun request(approval: Approval): Boolean {
                        approvals++
                        return approvals == 1
                    }
                },
            )
        gate.start()
        gate.run(tap())
        assertFailsWith<SafeFailure> { gate.run(tap()) }
        assertEquals(1, device.executions)
        assertEquals(2, approvals)
    }

    @Test
    fun stopWhileApprovalPendingPreventsDispatch() = runTest {
        val answer = CompletableDeferred<Boolean>()
        val device = Device(screen)
        val gate =
            Gateway(
                policy,
                { config },
                device,
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = answer.await()
                },
            )
        gate.start()
        val job = launch { gate.run(tap()) }
        runCurrent()
        gate.stop()
        answer.complete(true)
        job.join()
        assertEquals(0, device.executions)
    }

    @Test
    fun batchesCannotBypassAppPermissions() = runTest {
        val device = Device(screen)
        val gate =
            Gateway(
                policy,
                { config.copy(apps = mapOf(pkg to AppRule(true, false))) },
                device,
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = true
                },
            )
        gate.start()
        assertFailsWith<SafeFailure> { gate.batch(listOf(Action(Op.OBSERVE, pkg), tap())) }
        assertEquals(1, device.executions)
    }

    @Test
    fun policyChangeWhileWaitingInvalidatesApproval() = runTest {
        var c = config
        val device = Device(screen)
        val gate =
            Gateway(
                policy,
                { c },
                device,
                object : ApprovalPort {
                    override suspend fun request(approval: Approval): Boolean {
                        c = c.copy(apps = mapOf(pkg to AppRule(true, false)))
                        return true
                    }
                },
            )
        gate.start()
        assertFailsWith<SafeFailure> { gate.run(tap()) }
        assertEquals(0, device.executions)
    }

    @Test
    fun mcpConsentAndRevocationEnforced() {
        val a = Action(Op.MCP, server = "s", tool = "t")
        assertIs<Decision.Deny>(policy.decide(a, screen, config, 0))
        assertEquals(
            Decision.Approval,
            policy.decide(a, screen, config.copy(mcp = mapOf("s" to setOf("t"))), 0),
        )
        assertIs<Decision.Deny>(
            policy.decide(
                a,
                screen,
                config.copy(mcp = emptyMap()),
                0,
            )
        )
    }

    @Test
    fun uncertainActionsAreNeverRetried() = runTest {
        var n = 0
        val d =
            object : DevicePort {
                override suspend fun inspect(app: String) = screen

                override suspend fun execute(action: Action, screen: Screen): ToolResult {
                    n++
                    throw java.io.IOException("disconnect")
                }
            }
        val gate =
            Gateway(
                policy,
                { config },
                d,
                object : ApprovalPort {
                    override suspend fun request(approval: Approval) = true
                },
            )
        gate.start()
        assertEquals("action_uncertain", assertFailsWith<SafeFailure> { gate.run(tap()) }.code)
        assertEquals(1, n)
    }

    @Test
    fun runBudgetStopsAtLimit() {
        var now = 0L
        val b = RunBudget(2, 100, { now })
        b.consume()
        b.consume()
        assertFailsWith<SafeFailure> { b.consume() }
        val elapsed = RunBudget(now = { now })
        now = 700000
        assertFailsWith<SafeFailure> { elapsed.consume() }
    }
}
