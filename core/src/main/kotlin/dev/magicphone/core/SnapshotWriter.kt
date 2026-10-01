// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Ordered background persistence. A barrier awaits all snapshots submitted before it. */
class SnapshotWriter<T>(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val failed: (Throwable) -> Unit,
    private val write: suspend (T) -> Unit,
) {
    private data class Pending<T>(val value: T, val done: CompletableDeferred<Unit>)
    private val queue = Channel<Pending<T>>(Channel.UNLIMITED)
    private var latest: Deferred<Unit> = CompletableDeferred(Unit)

    init {
        scope.launch(dispatcher) {
            for (pending in queue) {
                try {
                    write(pending.value)
                    pending.done.complete(Unit)
                } catch (e: Exception) {
                    pending.done.completeExceptionally(e)
                    if (e is CancellationException) throw e
                    failed(e)
                }
            }
        }
    }

    @Synchronized
    fun submit(value: T) {
        val done = CompletableDeferred<Unit>()
        queue.trySend(Pending(value, done)).getOrThrow()
        latest = done
    }

    suspend fun flush() = synchronized(this) { latest }.await()
}
