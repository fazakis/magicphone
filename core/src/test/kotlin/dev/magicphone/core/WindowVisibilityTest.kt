// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*

class WindowVisibilityTest {
    private val target = Rect(0, 100, 500, 900)
    private val cover = Rect(300, 500, 600, 1000)
    private val base = Screen(id = "visible", app = "test.fixture", width = 1000, height = 1000,
        focused = false, locked = false, mixed = false, windowBounds = target, captureBounds = target,
        protectedRects = listOf(cover), visibleRegions = WindowVisibility.exposed(target, listOf(cover)),
        nodes = listOf(Node("button", "Add", Rect(20, 200, 200, 300), true, true, true)))
    private val policy = Policy("own.app")
    private val config = PolicyConfig(allowAllApps = true)
    private fun action(op: Op) = Action(op, base.app, base.id, "button", text = "forward")
    @Test fun visibleUnfocusedWindowCanBeReadAndTappedButMustFocusBeforeTypingOrNavigation() {
        for (op in listOf(Op.OBSERVE, Op.SCREENSHOT, Op.TAP))
            assertEquals(Decision.Allow, policy.decide(action(op), base, config, 0), op.name)
        for (op in listOf(Op.TEXT, Op.SCROLL, Op.LONG_PRESS, Op.BACK, Op.HOME, Op.RECENTS, Op.NOTIFICATIONS)) {
            assertEquals(Decision.Deny("window_not_focused"), policy.decide(action(op), base, config, 0), op.name)
            assertEquals(Decision.Allow, policy.decide(action(op), base.copy(focused = true), config, 0), op.name)
        }
    }
    @Test fun geometryNotOtherWindowExistenceDeterminesReachableTargets() {
        val tap = Action(Op.TAP, base.app, base.id, x = 30, y = 400)
        assertEquals(Decision.Allow, policy.decide(tap, base, config, 0))
        for ((x,y) in listOf(400 to 600, 700 to 200, 20 to 50, 500 to 400))
            assertEquals(Decision.Deny("protected_control"), policy.decide(tap.copy(x=x,y=y), base, config, 0))
        val across = tap.copy(op = Op.SWIPE, x = 400, y = 400, x2 = 200, y2 = 800)
        assertEquals(Decision.Deny("protected_control"), policy.decide(across, base.copy(focused=true), config, 0))
        assertEquals(Decision.Allow, policy.decide(across.copy(x=200), base.copy(focused=true), config, 0))
    }
    @Test fun fullCoverageAndUnknownGeometryNeverAuthorizeBackgroundReads() {
        assertTrue(WindowVisibility.exposed(target, listOf(target)).isEmpty())
        assertTrue(WindowVisibility.exposed(Rect(0,0,0,0), emptyList()).isEmpty())
        for (s in listOf(base.copy(visibleRegions=emptyList()),base.copy(mixed=true),base.copy(locked=true),base.copy(sensitive=true)))
            assertIs<Decision.Deny>(policy.decide(Action(Op.OBSERVE,base.app),s,config,0))
        assertIs<Decision.Deny>(policy.decide(action(Op.TAP),base,config.copy(apps=mapOf(base.app to AppRule(deny=true))),0))
    }
    @Test fun movedWindowsAndFocusChangesInvalidateOldContext() {
        assertNotEquals(base.binding,base.copy(focused=true).binding)
        assertNotEquals(base.captureBinding,base.copy(windowLayout="moved").captureBinding)
        assertNotEquals(base.binding,base.copy(visibleRegions=listOf(target)).binding)
        assertEquals(Decision.Deny("stale_target"),policy.decide(action(Op.TAP).copy(snapshot="old"),base,config,0))
    }
    @Test fun multipleOccludersAndXiaomiCaptionOnlySubtractTheirOwnArea() {
        val target = Rect(0, 0, 1914, 2160)
        val caption = Rect(872, 0, 1043, 117)
        val pieces = WindowVisibility.exposed(target,listOf(caption,Rect(1500,1500,2200,2500)))
        assertTrue(pieces.any { it.contains(500,270) })
        assertFalse(pieces.any { it.contains(950,50) || it.contains(1600,1600) })
        assertEquals(listOf(target),WindowVisibility.exposed(target,listOf(Rect(2000,0,3000,100))))
        assertEquals(emptyList(),WindowVisibility.exposed(target,listOf(Rect(0,0,1000,2160),Rect(1000,0,1914,2160))))
    }
}
