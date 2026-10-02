// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlin.test.*

class InputFieldsTest {
    @Test fun ordinaryInputsAreNotCredentials() {
        // Normal/email/person/postal/message/filter/phonetic/web-edit/web-email text,
        // normal/signed/decimal numbers, phone and date/time inputs, with extra flags.
        for (type in listOf(1, 0x21, 0x61, 0x71, 0x81 + 0x20, 0xb1, 0xc1, 0xd1,
                0x20001, 2, 0x1002, 0x2002, 3, 4, 0x14, 0x24)) {
            assertFalse(InputFields.password(false, type), "Ordinary input type $type")
        }
    }
    @Test fun actualPasswordFieldsRemainManual() {
        for (type in listOf(0x81, 0x91, 0xe1, 0x12, 0x80081, 0x200e1))
            assertTrue(InputFields.password(false, type), "Password input type $type")
        assertTrue(InputFields.password(true, 1))
        assertTrue(InputFields.password(true, 0))
    }
}
