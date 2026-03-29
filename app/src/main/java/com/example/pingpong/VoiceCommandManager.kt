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
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Manages always-on voice command recognition and TTS confirmation.
 *
 * Language handling:
 *   1. Speech recognizer returns text in whatever language was spoken.
 *   2. ML Kit language identification checks if the text is English.
 *   3. If English → parse directly (no ML Kit translator needed).
 *   4. If not English → translate to English via ML Kit (on-device, offline
 *      after model download), then parse.
 *
 * Commands (in any language):
 *   "point left"  / "point right"  / "point [name]"  → add point
 *   "minus left"  / "minus right"  / "minus [name]"  → remove point
 *   "remove left" / "remove right" / "remove [name]" → remove point
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

    private val enabledLanguages: List<String> by lazy {
        VoiceSettingsActivity.getEnabledLanguages(context)
    }

    // Cache translators so we don't recreate them on every command
    private val translatorCache = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

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
        translatorCache.values.forEach { it.close() }
        translatorCache.clear()
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
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cmd_confirmation")
        }
    }

    // ─────────────────────── RECOGNIZER ───────────────────────

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    processRecognizedText(matches[0])
                } else {
                    if (isActive && !isSpeaking) beginListening()
                }
            }

            override fun onError(error: Int) {
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
        val primaryLang = if (enabledLanguages.isNotEmpty()) enabledLanguages[0] else "en"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLang)
            if (enabledLanguages.size > 1) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLang)
                putExtra(
                    "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES",
                    enabledLanguages.drop(1).toTypedArray()
                )
            }
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─────────────────────── LANGUAGE DETECTION + TRANSLATION ───────────────────────

    /**
     * Entry point after speech recognition.
     * Identifies the language, skips translation if already English,
     * otherwise translates then parses.
     */
    private fun processRecognizedText(text: String) {
        val langId = LanguageIdentification.getClient()
        langId.identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                langId.close()
                if (langCode == "en" || langCode == "und") {
                    // English or undetected — parse directly
                    mainHandler.post { handleResult(text) }
                } else {
                    translateToEnglish(text, langCode) { translated ->
                        mainHandler.post { handleResult(translated) }
                    }
                }
            }
            .addOnFailureListener {
                langId.close()
                // If detection fails, try parsing as-is
                mainHandler.post { handleResult(text) }
            }
    }

    /**
     * Translates [text] from [sourceLangCode] to English using the on-device
     * ML Kit translator. The model must already be downloaded (guaranteed if
     * the user enabled the language in Voice Settings).
     * Calls [onDone] with the translated text, or original text on failure.
     */
    private fun translateToEnglish(text: String, sourceLangCode: String, onDone: (String) -> Unit) {
        val mlKitCode = TranslateLanguage.fromLanguageTag(sourceLangCode)
        if (mlKitCode == null) {
            onDone(text)
            return
        }

        val translator = translatorCache.getOrPut(sourceLangCode) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitCode)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options)
        }

        translator.translate(text)
            .addOnSuccessListener { translated -> onDone(translated.lowercase().trim()) }
            .addOnFailureListener { onDone(text) } // fall back to original on error
    }

    // ─────────────────────── COMMAND PARSING ───────────────────────

    /**
     * Parses an English command string. Always receives English —
     * either directly from the recognizer or after ML Kit translation.
     */
    private fun handleResult(text: String) {
        val input = text.lowercase().trim()
        val words = input.split("\\s+".toRegex())

        val actionWord = words.firstOrNull() ?: run {
            if (isActive && !isSpeaking) beginListening()
            return
        }
        val isAdd    = actionWord == "point"
        val isRemove = actionWord == "minus" || actionWord == "remove"

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
                // Action word matched but no valid direction/name — resume listening
                if (isActive && !isSpeaking) beginListening()
            }
        }
    }
}