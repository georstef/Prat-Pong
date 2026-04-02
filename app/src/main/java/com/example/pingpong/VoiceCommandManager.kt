package com.example.pingpong

import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Manages always-on voice command recognition and TTS confirmation.
 * Uses SpeechRecognizer (Google cloud) when online, Vosk when offline.
 * Switches instantly using ConnectivityManager.NetworkCallback.
 *
 * Commands:
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
    private val onMinusRight: () -> Unit,
    private val onModelLoadFailed: (() -> Unit)? = null
) {

    // Online (SpeechRecognizer)
    private var speechRecognizer: SpeechRecognizer? = null

    // Offline (Vosk)
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskSpeechService: SpeechService? = null
    private var voskReady = false

    // TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var isActive = false
    private var isSpeaking = false
    private val mainHandler = Handler(Looper.getMainLooper())

    var leftPlayerName: String = ""
    var rightPlayerName: String = ""

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val MODEL_ASSETS_DIR = "model-en-us"
        private const val MODEL_CACHE_DIR  = "vosk-model-en-us"
        private const val EXTRACTION_DONE_MARKER = ".extracted"
        private const val SAMPLE_RATE = 16000.0f
    }

    // ─────────────────────── NETWORK CALLBACK ───────────────────────

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available — switching to SpeechRecognizer")
            mainHandler.post {
                if (isActive) {
                    stopVosk()
                    startOnlineRecognizer() // recognizer already exists, just starts listening
                }
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost — switching to Vosk")
            mainHandler.post {
                if (isActive) {
                    speechRecognizer?.stopListening() // pause listening, keep instance alive
                    if (voskReady) startVoskService()
                    else Log.w(TAG, "Network lost but Vosk not ready yet")
                }
            }
        }
    }

    // ─────────────────────── LIFECYCLE ───────────────────────

    fun start() {
        if (isActive) return
        isActive = true
        initTts()
        registerNetworkCallback()
        initOnlineRecognizer() // warm up immediately, used when online
        // Load Vosk in background so it's ready if we go offline
        Thread {
            try {
                val modelPath = ensureModelExtracted(context)
                voskModel = Model(modelPath)
                voskRecognizer = Recognizer(voskModel, SAMPLE_RATE)
                voskReady = true
                Log.d(TAG, "Vosk model ready")
            } catch (e: Exception) {
                Log.e(TAG, "Vosk model failed to load: ${e.message}")
            }
            mainHandler.post { beginListening() }
        }.start()
    }

    fun stop() {
        isActive = false
        isSpeaking = false
        unregisterNetworkCallback()
        stopOnlineRecognizer()
        stopVosk()
        voskRecognizer?.close()
        voskRecognizer = null
        voskModel?.close()
        voskModel = null
        voskReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    fun updatePlayerNames(leftName: String, rightName: String) {
        leftPlayerName = leftName.lowercase().trim()
        rightPlayerName = rightName.lowercase().trim()
    }

    // ─────────────────────── CONNECTIVITY ───────────────────────

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister network callback: ${e.message}")
        }
    }

    private fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ─────────────────────── ROUTING ───────────────────────

    private fun beginListening() {
        if (!isActive || isSpeaking) return
        if (isOnline()) {
            startOnlineRecognizer()
            Log.d(TAG, "Using SpeechRecognizer (online)")
        } else if (voskReady) {
            startVoskService()
            Log.d(TAG, "Using Vosk (offline)")
        } else {
            // Vosk still loading — retry shortly
            mainHandler.postDelayed({ beginListening() }, 500)
        }
    }

    // ─────────────────────── ONLINE: SpeechRecognizer ───────────────────────

    private fun initOnlineRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "Online result: ${matches[0]}")
                    handleResult(matches[0])
                }
                // Stop before restarting to avoid ERROR_CLIENT (5)
                speechRecognizer?.stopListening()
                if (isActive && !isSpeaking) mainHandler.postDelayed({ startOnlineRecognizer() }, 100)
            }
            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Already listening — just let it continue, don't restart
                        Log.d(TAG, "Recognizer busy, skipping restart")
                    }
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // Silence or unclear audio — restart after short delay
                        if (isActive && !isSpeaking) mainHandler.postDelayed({ startOnlineRecognizer() }, 200)
                    }
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        // Network issue — wait longer before retry
                        if (isActive && !isSpeaking) mainHandler.postDelayed({ startOnlineRecognizer() }, 1000)
                    }
                    else -> {
                        if (isActive && !isSpeaking) mainHandler.postDelayed({ startOnlineRecognizer() }, 300)
                    }
                }
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

    private fun startOnlineRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start online recognizer: ${e.message}")
        }
    }

    private fun stopOnlineRecognizer() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ─────────────────────── OFFLINE: Vosk ───────────────────────

    private fun startVoskService() {
        if (voskSpeechService != null) return
        try {
            voskSpeechService = SpeechService(voskRecognizer, SAMPLE_RATE)
            voskSpeechService?.startListening(object : org.vosk.android.RecognitionListener {
                override fun onResult(hypothesis: String?) {
                    hypothesis ?: return
                    try {
                        val text = JSONObject(hypothesis).optString("text", "")
                        if (text.isNotBlank()) {
                            Log.d(TAG, "Vosk result: $text")
                            handleResult(text)
                        }
                    } catch (e: Exception) { }
                }
                override fun onPartialResult(hypothesis: String?) {}
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) {
                    Log.w(TAG, "Vosk error: ${exception?.message}")
                }
                override fun onTimeout() {}
            })
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start Vosk: ${e.message}")
        }
    }

    private fun stopVosk() {
        voskSpeechService?.stop()
        voskSpeechService?.shutdown()
        voskSpeechService = null
    }

    // ─────────────────────── MODEL EXTRACTION ───────────────────────

    private fun ensureModelExtracted(context: Context): String {
        val outDir = File(context.filesDir, MODEL_CACHE_DIR)
        val marker = File(outDir, EXTRACTION_DONE_MARKER)
        if (marker.exists()) return outDir.absolutePath
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()
        copyAssetFolder(context.assets, MODEL_ASSETS_DIR, outDir.absolutePath)
        marker.createNewFile()
        return outDir.absolutePath
    }

    private fun copyAssetFolder(assets: AssetManager, assetPath: String, outPath: String) {
        val list = assets.list(assetPath)
        if (list.isNullOrEmpty()) {
            assets.open(assetPath).use { input ->
                File(outPath).outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            File(outPath).mkdirs()
            for (item in list) {
                copyAssetFolder(assets, "$assetPath/$item", "$outPath/$item")
            }
        }
    }

    // ─────────────────────── TTS ───────────────────────

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        mainHandler.post {
                            speechRecognizer?.stopListening()
                            voskSpeechService?.setPause(true)
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        mainHandler.postDelayed({
                            if (isActive && !isSpeaking) {
                                voskSpeechService?.setPause(false)
                                if (speechRecognizer != null) startOnlineRecognizer()
                            }
                        }, 300)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isSpeaking = false
                        mainHandler.postDelayed({
                            if (isActive && !isSpeaking) {
                                voskSpeechService?.setPause(false)
                                if (speechRecognizer != null) startOnlineRecognizer()
                            }
                        }, 300)
                    }
                })
            }
        }
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cmd_confirmation")
    }

    // ─────────────────────── COMMAND PARSING ───────────────────────

    private fun isFuzzyMatch(heard: String, name: String): Boolean {
        if (heard.contains(name)) return true
        val threshold = maxOf(1, name.length / 3)
        return heard.split(" ").any { word -> editDistance(word, name) <= threshold }
    }

    private fun editDistance(a: String, b: String): Int {
        val m = a.length; val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1]
            else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
        }
        return dp[m][n]
    }

    private fun handleResult(text: String) {
        Log.d(TAG, "HANDLE: $text")
        val input = text.lowercase().trim()
        val words = input.split("\\s+".toRegex())

        val actionWord = words.firstOrNull() ?: return
        val isAdd    = actionWord == "point"
        val isRemove = actionWord in setOf("minus", "remove", "undo")
        if (!isAdd && !isRemove) return

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
            leftPlayerName.isNotEmpty() && isFuzzyMatch(rest, leftPlayerName) -> {
                speak("$actionWord $leftPlayerName")
                if (isAdd) onPointLeft() else onMinusLeft()
            }
            rightPlayerName.isNotEmpty() && isFuzzyMatch(rest, rightPlayerName) -> {
                speak("$actionWord $rightPlayerName")
                if (isAdd) onPointRight() else onMinusRight()
            }
            else -> Log.d(TAG, "No command matched for: '$rest'")
        }
    }
}