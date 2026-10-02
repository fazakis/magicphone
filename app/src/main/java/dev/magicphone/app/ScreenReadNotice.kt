// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal class ScreenReadNotice(private val context: Context, private val scope: CoroutineScope) {
    val visible = MutableStateFlow(false)
    private var toast: Toast? = null
    private var dismissal: Job? = null

    fun show() {
        if (visible.value) return
        toast = Toast.makeText(context, R.string.screen_read_continuing, Toast.LENGTH_SHORT).also { it.show() }
        visible.value = true
        dismissal = scope.launch {
            delay(2000)
            toast?.cancel()
            toast = null
            visible.value = false
        }
    }

    fun cancel() {
        dismissal?.cancel()
        toast?.cancel()
        toast = null
        visible.value = false
    }
}
