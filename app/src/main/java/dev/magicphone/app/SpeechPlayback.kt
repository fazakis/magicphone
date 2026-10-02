// Copyright 2026 Nikos Fazakis. SPDX-License-Identifier: MIT OR Apache-2.0
package dev.magicphone.app

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** Explicit local playback only. Never started by a model or automatically on completion. */
class SpeechPlayback(private val context: Context) {
    val active = MutableStateFlow<String?>(null)
    val started = MutableStateFlow(false)
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, String>? = null
    private var epoch = 0L
    private var engineGeneration = 0L

    fun toggle(key: String, text: String) {
        if (active.value == key) { stop(); return }
        stop()
        if (text.isBlank()) return
        active.value = key
        pending = key to spokenText(text)
        if (ready) speakPending()
        else if (engine == null) {
            val generation = ++engineGeneration
            engine = TextToSpeech(context) { status -> handler.post {
                if (generation != engineGeneration) return@post
                if (status != TextToSpeech.SUCCESS) { fail(R.string.tts_unavailable); shutdown() }
                else {
                    ready = true
                    engine?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    speakPending()
                }
            } }
        }
    }
    private fun speakPending() {
        val (key, text) = pending ?: return
        pending = null
        val tts = engine ?: return
        val language = if (text.count { it in '\u0370'..'\u03ff' || it in '\u1f00'..'\u1fff' } > text.count { it.isLetter() } / 4)
            Locale.forLanguageTag("el-GR") else context.resources.configuration.locales[0]
        if (tts.setLanguage(language) < TextToSpeech.LANG_AVAILABLE) { fail(R.string.tts_language_missing); return }
        val run = epoch
        // Leave room below the platform limit and never cut a UTF-16 surrogate pair.
        val chunks = mutableListOf<String>()
        var start = 0
        val limit = minOf(TextToSpeech.getMaxSpeechInputLength() - 1, 3500)
        while (start < text.length) {
            var end = (start + limit).coerceAtMost(text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            chunks += text.substring(start, end)
            start = end
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { handler.post { if (epoch == run && id?.startsWith("$run:") == true) started.value = true } }
            override fun onDone(id: String?) { handler.post {
                if (epoch == run && id == "$run:${chunks.lastIndex}") { active.value = null; started.value = false }
            } }
            @Deprecated("Platform callback")
            override fun onError(id: String?) { handler.post { if (epoch == run && id?.startsWith("$run:") == true) fail(R.string.tts_unavailable) } }
        })
        if (active.value != key) return
        chunks.forEachIndexed { index, chunk ->
            if (tts.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null, "$run:$index") == TextToSpeech.ERROR) { fail(R.string.tts_unavailable); return }
        }
    }
    private fun fail(message: Int) { stop(); Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    fun stop() { epoch++; pending = null; active.value = null; started.value = false; engine?.stop() }
    fun shutdown() { engineGeneration++; stop(); ready = false; engine?.shutdown(); engine = null }
}

@Composable
fun ReadAloudButton(runtime: AppRuntime, key: String, text: String) {
    val active by runtime.speech.active.collectAsStateWithLifecycle()
    TextButton({ runtime.speech.toggle(key, text) }) {
        Text(stringResource(if (active == key) R.string.stop_reading else R.string.read_aloud))
    }
}

/** Keep link labels and prose readable without speaking Markdown formatting characters. */
internal fun spokenText(text: String): String = text
    .replace(Regex("\\[([^]]+)\\]\\([^)]+\\)"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s+"), "")
    .replace("**", "").replace("__", "").replace("`", "")
