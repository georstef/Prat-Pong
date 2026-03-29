package com.example.pingpong

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Manages always-on voice command recognition and TTS confirmation.
 *
 * English only. Commands:
 *   "point left"  / "point right"  / "point [name]"  → add point
 *   "minus left"  / "minus right"  / "minus [name]"  → remove point
 *   "remove left" / "remove right" / "remove [name]" → remove point
 *   "undo left"   / "undo right"   / "undo [name]"   → undo point
 */
class VoiceCommandManager(
    private val context: Context,
    private val onPointLeft: () -> Unit,
    private val onPointRight: () -> Unit,
    private val onMinusLeft: () -> Unit,
    private val onMinusRight: () -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isActive = false
    private var isSpeaking = false
    private val mainHandler = Handler(Looper.getMainLooper())

    var leftPlayerName: String = ""
    var rightPlayerName: String = ""

    // ─────────────────────── LIFECYCLE ───────────────────────

    fun start() {
        if (isActive) return
        isActive = true
        initTts()
        initRecognizer()
        beginListening()
    }

    fun stop() {
        isActive = false
        isSpeaking = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    fun updatePlayerNames(leftName: String, rightName: String) {
        leftPlayerName = leftName.lowercase().trim()
        rightPlayerName = rightName.lowercase().trim()
    }

    // ─────────────────────── TTS ───────────────────────

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        mainHandler.post { speechRecognizer?.stopListening() }
                    }

                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        mainHandler.postDelayed({
                            if (isActive && !isSpeaking) beginListening()
                        }, 300)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        mainHandler.postDelayed({
                            if (isActive && !isSpeaking) beginListening()
                        }, 300)
                    }
                })
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cmd_confirmation")
    }

    // ─────────────────────── RECOGNIZER ───────────────────────

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    android.util.Log.d("VoiceDebug", "RAW RECOGNIZED: ${matches[0]}")
                    handleResult(matches[0])
                } else {
                    if (isActive && !isSpeaking) beginListening()
                }
            }

            override fun onError(error: Int) {
                android.util.Log.w("VoiceDebug", "Recognition error code: $error")
                if (isActive && !isSpeaking) beginListening()
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun beginListening() {
        if (!isActive || isSpeaking) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─────────────────────── COMMAND PARSING ───────────────────────

    private fun handleResult(text: String) {
        android.util.Log.d("VoiceDebug", "HANDLE RESULT: $text")
        val input = text.lowercase().trim()
        val words = input.split("\\s+".toRegex())

        val actionWord = words.firstOrNull() ?: run {
            if (isActive && !isSpeaking) beginListening()
            return
        }

        val isAdd    = actionWord == "point"
        val isRemove = actionWord in setOf("minus", "remove", "undo")

        if (!isAdd && !isRemove) {
            if (isActive && !isSpeaking) beginListening()
            return
        }

        val rest = words.drop(1).joinToString(" ").trim()

        when {
            rest == "left" -> {
                speak("$actionWord left")
                if (isAdd) onPointLeft() else onMinusLeft()
            }
            rest == "right" -> {
                speak("$actionWord right")
                if (isAdd) onPointRight() else onMinusRight()
            }
            leftPlayerName.isNotEmpty() && rest.contains(leftPlayerName) -> {
                speak("$actionWord $leftPlayerName")
                if (isAdd) onPointLeft() else onMinusLeft()
            }
            rightPlayerName.isNotEmpty() && rest.contains(rightPlayerName) -> {
                speak("$actionWord $rightPlayerName")
                if (isAdd) onPointRight() else onMinusRight()
            }
            else -> {
                if (isActive && !isSpeaking) beginListening()
            }
        }
    }
}