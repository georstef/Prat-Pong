package com.example.pingpong

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
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
 * Manages always-on voice command recognition using Vosk (Offline Engine).
 */
class VoiceCommandManager(
    private val context: Context,
    private val onPointLeft: () -> Unit,
    private val onPointRight: () -> Unit,
    private val onMinusLeft: () -> Unit,
    private val onMinusRight: () -> Unit,
    private val onModelLoadFailed: (() -> Unit)? = null
) {

    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskSpeechService: SpeechService? = null
    private var voskReady = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var isActive = false
    private var isSpeaking = false
    private val mainHandler = Handler(Looper.getMainLooper())

    var leftPlayerName: String = ""
    var rightPlayerName: String = ""

    companion object {
        private const val TAG = "VoiceCommandManager"
        private const val MODEL_ASSETS_DIR = "model-en-us"
        private const val MODEL_CACHE_DIR  = "vosk-model-en-us"
        private const val EXTRACTION_DONE_MARKER = ".extracted"
        private const val SAMPLE_RATE = 16000.0f
    }

    fun start() {
        if (isActive) return
        isActive = true
        Log.d(TAG, "Starting VoiceCommandManager")
        initTts()

        Thread {
            try {
                val modelPath = ensureModelExtracted(context)
                voskModel = Model(modelPath)
                voskRecognizer = Recognizer(voskModel, SAMPLE_RATE)
                voskReady = true
                Log.d(TAG, "Vosk model loaded")
                mainHandler.post { startVoskService() }
            } catch (e: Exception) {
                Log.e(TAG, "Vosk model failed: ${e.message}")
                onModelLoadFailed?.invoke()
            }
        }.start()
    }

    fun stop() {
        isActive = false
        isSpeaking = false
        stopVosk()
        voskRecognizer?.close()
        voskModel?.close()
        tts?.stop()
        tts?.shutdown()
    }

    fun stopRecognizerOnly() {
        isActive = false
        stopVosk()
    }

    fun updatePlayerNames(leftName: String, rightName: String) {
        leftPlayerName = leftName.lowercase().trim()
        rightPlayerName = rightName.lowercase().trim()
    }

    private fun startVoskService() {
        if (!voskReady || voskSpeechService != null) return
        try {
            voskSpeechService = SpeechService(voskRecognizer, SAMPLE_RATE)
            voskSpeechService?.startListening(object : org.vosk.android.RecognitionListener {
                override fun onResult(hypothesis: String?) {
                    hypothesis?.let {
                        val text = JSONObject(it).optString("text", "")
                        if (text.isNotBlank()) {
                            Log.d(TAG, "Vosk Result: $text")
                            handleResult(text)
                        }
                    }
                }
                override fun onPartialResult(hypothesis: String?) {}
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(e: Exception?) { Log.e(TAG, "Vosk Error: $e") }
                override fun onTimeout() {}
            })
        } catch (e: IOException) {
            Log.e(TAG, "Vosk Service Start Failed: $e")
        }
    }

    private fun stopVosk() {
        voskSpeechService?.stop()
        voskSpeechService?.shutdown()
        voskSpeechService = null
    }

    private fun ensureModelExtracted(context: Context): String {
        val outDir = File(context.filesDir, MODEL_CACHE_DIR)
        val marker = File(outDir, EXTRACTION_DONE_MARKER)
        if (marker.exists()) return outDir.absolutePath
        outDir.deleteRecursively(); outDir.mkdirs()
        copyAssetFolder(context.assets, MODEL_ASSETS_DIR, outDir.absolutePath)
        marker.createNewFile()
        return outDir.absolutePath
    }

    private fun copyAssetFolder(assets: AssetManager, assetPath: String, outPath: String) {
        val list = assets.list(assetPath)
        if (list.isNullOrEmpty()) {
            assets.open(assetPath).use { i -> File(outPath).outputStream().use { o -> i.copyTo(o) } }
        } else {
            File(outPath).mkdirs()
            for (item in list) copyAssetFolder(assets, "$assetPath/$item", "$outPath/$item")
        }
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        isSpeaking = true
                        mainHandler.post { voskSpeechService?.setPause(true) }
                    }
                    override fun onDone(id: String?) {
                        isSpeaking = false
                        mainHandler.post { if (isActive) voskSpeechService?.setPause(false) }
                    }
                    override fun onError(id: String?) { isSpeaking = false }
                })
            }
        }
    }

    fun speak(text: String, queued: Boolean = false) {
        if (!ttsReady) return
        val mode = if (queued) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        tts?.speak(text, mode, null, "v_cmd")
    }

    private fun handleResult(text: String) {
        val input = text.lowercase().trim()
        val words = input.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return

        val addIdx = findAddAction(words)
        val removeIdx = findRemoveAction(words)

        val isAdd = if (addIdx >= 0 && (removeIdx < 0 || addIdx < removeIdx)) true
        else if (removeIdx >= 0) false
        else return

        val actionIdx = if (isAdd) addIdx else removeIdx
        val rest = words.drop(actionIdx + 1).joinToString(" ").trim()
        if (rest.isEmpty()) return

        val leftNameScore = if (leftPlayerName.isNotEmpty()) fuzzyScore(rest, leftPlayerName) else null
        val rightNameScore = if (rightPlayerName.isNotEmpty()) fuzzyScore(rest, rightPlayerName) else null
        val sideLeftScore = if (matchesSide(rest, "left")) 90 else null
        val sideRightScore = if (matchesSide(rest, "right")) 90 else null

        // Data structure to hold match info
        data class MatchResult(val score: Int, val isLeft: Boolean, val labelToSpeak: String)

        val bestMatch = listOfNotNull(
            sideLeftScore?.let { MatchResult(it, true, "left") },
            sideRightScore?.let { MatchResult(it, false, "right") },
            leftNameScore?.let { MatchResult(it, true, leftPlayerName) },
            rightNameScore?.let { MatchResult(it, false, rightPlayerName) }
        ).maxByOrNull { it.score } ?: return

        // NOW it speaks the logic-selected name/side, not the raw input
        val actionWord = if (isAdd) "point" else "minus"
        speak("$actionWord ${bestMatch.labelToSpeak}")

        Log.d(TAG, "Executed: $actionWord ${bestMatch.labelToSpeak} (Input was: $rest)")

        if (bestMatch.isLeft) {
            if (isAdd) onPointLeft() else onMinusLeft()
        } else {
            if (isAdd) onPointRight() else onMinusRight()
        }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1] else 1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
        }
        return dp[a.length][b.length]
    }

    private fun phoneticDigits(word: String): String {
        val codeMap = mapOf('b' to '1', 'f' to '1', 'p' to '1', 'v' to '1', 'c' to '2', 'g' to '2', 'j' to '2', 'k' to '2', 'q' to '2', 's' to '2', 'x' to '2', 'z' to '2', 'd' to '3', 't' to '3', 'l' to '4', 'm' to '5', 'n' to '5', 'r' to '6')
        val sb = StringBuilder(); var last: Char? = null
        for (ch in word.lowercase()) {
            val code = codeMap[ch] ?: continue
            if (code != last) { sb.append(code); last = code }
        }
        return sb.toString()
    }

    private fun fuzzyScore(heard: String, target: String): Int? {
        if (heard.contains(target)) return 100
        val targetDigits = phoneticDigits(target)
        for (word in heard.split(" ")) {
            if (editDistance(word, target) <= 1) return 80
            if (editDistance(phoneticDigits(word), targetDigits) <= 1) return 40
        }
        return null
    }

    private fun findAddAction(words: List<String>): Int {
        val pD = phoneticDigits("point")
        return words.indexOfFirst {
            editDistance(it, "point") <= 1 ||
                    editDistance(phoneticDigits(it), pD) <= 1 ||
                    (editDistance(it, "add") <= 1 && it != "and")
        }
    }

    private fun findRemoveAction(words: List<String>): Int =
        words.indexOfFirst { w -> listOf("minus", "remove", "undo").any { editDistance(w, it) <= 1 } }

    private fun matchesSide(rest: String, side: String): Boolean =
        rest.split(" ").any { editDistance(it, side) <= 1 }

    fun pause() {
        voskSpeechService?.setPause(true)
    }

    fun resume() {
        if (!isActive) return
        if (voskSpeechService == null && voskReady) {
            startVoskService()   // only restarts if it was actually torn down
        } else {
            voskSpeechService?.setPause(false)
        }
    }
}