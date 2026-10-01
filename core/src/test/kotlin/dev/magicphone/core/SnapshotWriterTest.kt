// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotWriterTest {
    @Test fun writesStayOrderedAndFlushWaitsWithoutBlockingCaller() = runTest {
        val values = mutableListOf<Int>()
        val writer = SnapshotWriter<Int>(backgroundScope, StandardTestDispatcher(testScheduler),
            failed = { throw it }) { delay(100); values += it }
        writer.submit(1)
        writer.submit(2)
        assertTrue(values.isEmpty())
        val barrier = async { writer.flush() }
        runCurrent()
        assertFalse(barrier.isCompleted)
        advanceTimeBy(201)
        barrier.await()
        assertEquals(listOf(1, 2), values)
    }

    @Test fun failedPersistenceCannotPassTheDurabilityBarrier() = runTest {
        val errors = mutableListOf<Throwable>()
        val writer = SnapshotWriter<Int>(backgroundScope, StandardTestDispatcher(testScheduler),
            failed = { errors += it }) { throw SafeFailure("storage_failed") }
        writer.submit(1)
        runCurrent()
        assertFailsWith<SafeFailure> { writer.flush() }
        assertEquals(1, errors.size)
    }

    @Test fun stopDuringAuditFlushPreventsDispatch() = runTest {
        var dispatched = false
        lateinit var gateway: Gateway
        gateway = Gateway(Policy("own.app"), { PolicyConfig() }, object : DevicePort {
            override suspend fun inspect(app: String) = Screen()
            override suspend fun execute(action: Action, screen: Screen): ToolResult {
                dispatched = true
                return ToolResult("reported")
            }
        }, object : ApprovalPort { override suspend fun request(approval: Approval) = false },
            event = { _, _ -> delay(100); gateway.stop() })
        gateway.start()
        assertFailsWith<CancellationException> { gateway.run(Action(Op.COMPLETE)) }
        assertFalse(dispatched)
    }
}
