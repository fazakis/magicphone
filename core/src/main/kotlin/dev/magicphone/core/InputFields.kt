// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

/** Android InputType class/variation values are enums, not independent password bits. */
object InputFields {
    fun password(markedPassword: Boolean, inputType: Int): Boolean {
        if (markedPassword) return true
        val kind = inputType and 0x0f
        val variation = inputType and 0x0ff0
        return (kind == 1 && variation in setOf(0x80, 0x90, 0xe0)) ||
            (kind == 2 && variation == 0x10)
    }
}
