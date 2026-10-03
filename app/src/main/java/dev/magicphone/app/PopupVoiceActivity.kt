// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Private, translucent bridge to Android dictation; does not open or alter the full chat. */
class PopupVoiceActivity : ComponentActivity() {
    private var delivered = false
    private val token get() = intent.getStringExtra("voice_session").orEmpty()
    private val voice = registerForActivityResult(VoiceInput()) { transcript ->
        deliver(transcript)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (runtime.phone?.quickPrompt?.pendingVoiceId != token || token.isBlank()) { finish(); return }
        if (savedInstanceState == null) {
            try { voice.launch(Unit) }
            catch (_: ActivityNotFoundException) { deliver(null, R.string.voice_unavailable) }
            catch (_: SecurityException) { deliver(null, R.string.voice_unavailable) }
        }
    }
    private fun deliver(transcript: String?, error: Int? = null) {
        delivered = true
        finish()
        runtime.phone?.quickPrompt?.finishVoice(token, transcript, error)
    }
    override fun onDestroy() {
        if (isFinishing && !delivered) runtime.phone?.quickPrompt?.finishVoice(token, null)
        super.onDestroy()
    }
}
