// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*

class CapturePolicyTest {
    private val pkg = "test.fixture"
    private val policy = Policy("own.app")
    private val config = PolicyConfig(apps = mapOf(pkg to AppRule(true, true)), allowAllApps = true)
    private val screen = Screen(id = "fresh", app = pkg, window = 7, width = 500, height = 900,
        locked = false, mixed = false, focused = true, captureBounds = Rect(0, 0, 500, 900))
    @Test fun screenshotReadsCurrentPermittedAppButStaleMutationStillFails() {
        assertEquals(Decision.Allow, policy.decide(Action(Op.SCREENSHOT, pkg, "old"), screen, config, 0))
        assertEquals(Decision.Deny("stale_target"), policy.decide(Action(Op.TAP, pkg, "old", x = 10, y = 10), screen, config, 0))
        for (invalid in listOf(screen.copy(locked = true), screen.copy(mixed = true), screen.copy(focused = false), screen.copy(sensitive = true), screen.copy(app = "other.app")))
            assertIs<Decision.Deny>(policy.decide(Action(Op.SCREENSHOT, pkg, "old"), invalid, config, 0))
        assertIs<Decision.Deny>(policy.decide(Action(Op.SCREENSHOT, pkg, "old"), screen, PolicyConfig(), 0))
        assertEquals(Decision.Allow, policy.decide(Action(Op.SCREENSHOT, pkg, "old"), screen.copy(displayId = 1), config, 0))
        assertEquals(Decision.Deny("screen_uncertain"), policy.decide(Action(Op.TAP, pkg, "fresh", x = 10, y = 10), screen.copy(displayId = 1), config, 0))
    }
    @Test fun captureIgnoresChangingTextButBindsWindowGeometryAndProtection() {
        assertEquals(screen.captureBinding, screen.copy(id = "next", revision = 2, partial = true,
            nodes = listOf(Node("new", "Toolbar updated", Rect(0, 0, 20, 20), false, false, false))).captureBinding)
        for (changed in listOf(screen.copy(window = 8), screen.copy(displayId = 1), screen.copy(width = 250),
            screen.copy(rotation = 1), screen.copy(focused = false), screen.copy(locked = true), screen.copy(mixed = true),
            screen.copy(sensitive = true), screen.copy(captureReady = false), screen.copy(captureBounds = Rect(0, 0, 250, 900)),
            screen.copy(windowBounds = Rect(10, 10, 500, 900)), screen.copy(protectedRects = listOf(Rect(0, 500, 500, 900)))))
            assertNotEquals(screen.captureBinding, changed.captureBinding)
    }
}
