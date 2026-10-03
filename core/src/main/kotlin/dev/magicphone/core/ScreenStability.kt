// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

/** Local readiness hint only; it never authorizes an action or weakens snapshot checks. */
class ScreenStability(private val quietMillis: Long = 80) {
    private var binding: String? = null
    private var since = 0L

    fun ready(screen: Screen?, now: Long): Boolean {
        if (screen == null || screen.id.isBlank() || !screen.readable || screen.sensitive) {
            binding = null
            return false
        }
        if (binding != screen.binding) {
            binding = screen.binding
            since = now
            return false
        }
        return now - since >= quietMillis
    }
}
