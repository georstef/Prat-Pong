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
    private var isListening = false
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
        isListening = false
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

    /** Stops only the speech recognizer, leaving TTS alive so queued announcements still play. */
    fun stopRecognizerOnly() {
        isActive = false
        isListening = false
        unregisterNetworkCallback()
        stopOnlineRecognizer()
        stopVosk()
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
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Log.d(TAG, "Online result: ${matches[0]}")
                    handleResult(matches[0])
                }
                if (isActive && !isSpeaking) mainHandler.postDelayed({ startOnlineRecognizer() }, 100)
            }
            override fun onError(error: Int) {
                isListening = false
                when (error) {
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // Should no longer fire since startOnlineRecognizer() guards
                        // against double-starts with isListening. Log and ignore.
                        Log.d(TAG, "Recognizer busy (ERROR_CLIENT) — ignoring")
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
        if (isListening) {
            Log.d(TAG, "startOnlineRecognizer skipped — already listening")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        try {
            isListening = true
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            isListening = false
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

    fun speak(text: String, queued: Boolean = false) {
        if (!ttsReady) return
        val mode = if (queued) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val id   = if (queued) "announcement" else "cmd_confirmation"
        tts?.speak(text, mode, null, id)
    }

    // ─────────────────────── COMMAND PARSING ───────────────────────

    /**
     * Levenshtein edit distance between two strings.
     */
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

    /**
     * Consonant-group digit code for [word].
     * Letters are mapped to sound groups (Soundex-style), vowels/h/w/y dropped,
     * and consecutive identical codes collapsed.
     *
     * Sound groups:
     *   1 → b f p v
     *   2 → c g j k q s x z   (includes ch/sh sounds)
     *   3 → d t
     *   4 → l
     *   5 → m n
     *   6 → r
     *
     * "george" → "262"   "chorrch" → "262"   "tseorts" → "32632"
     */
    private fun phoneticDigits(word: String): String {
        val lower = word.lowercase()
        val codeMap = mapOf(
            'b' to '1', 'f' to '1', 'p' to '1', 'v' to '1',
            'c' to '2', 'g' to '2', 'j' to '2', 'k' to '2',
            'q' to '2', 's' to '2', 'x' to '2', 'z' to '2',
            'd' to '3', 't' to '3',
            'l' to '4',
            'm' to '5', 'n' to '5',
            'r' to '6'
        )
        val sb = StringBuilder()
        var last: Char? = null
        for (ch in lower) {
            val code = codeMap[ch] ?: continue   // vowels / h w y → dropped
            if (code != last) { sb.append(code); last = code }
        }
        return sb.toString()
    }

    /**
     * True if [needle] appears as a subsequence inside [haystack].
     * e.g. "262" is a subsequence of "32632" → true.
     */
    private fun isSubsequence(needle: String, haystack: String): Boolean {
        var hi = 0
        for (ch in needle) {
            while (hi < haystack.length && haystack[hi] != ch) hi++
            if (hi >= haystack.length) return false
            hi++
        }
        return true
    }

    /**
     * Returns a confidence score for how well [heard] matches [target].
     * Higher = better match. Returns null if no layer matches at all.
     *
     * Layer 1 — Exact containment:          score = 100
     * Layer 2 — Raw edit distance within threshold:
     *            score = 80 - (editDist * 10)   e.g. ed=0→80, ed=1→70, ed=2→60
     * Layer 3 — Phonetic edit distance ≤ 1:
     *            score = 50 - (phonEd * 10)
     * Layer 4 — Phonetic subsequence:        score = 20
     *            Only fires when targetDigits.length ≥ 2, to prevent single-digit
     *            codes (e.g. "ida"→"3") from matching almost everything.
     *
     * When two names both match, the one with the higher score wins.
     */
    private fun fuzzyScore(heard: String, target: String): Int? {
        if (heard.isEmpty()) return null
        if (heard.contains(target)) return 100
        val targetDigits = phoneticDigits(target)
        val rawThreshold = minOf(3, maxOf(1, target.length / 3))
        var bestScore: Int? = null
        for (word in heard.split("\\s+".toRegex())) {
            // Layer 2: raw edit distance
            val ed = editDistance(word, target)
            if (ed <= rawThreshold) {
                val s = 80 - ed * 10
                if (bestScore == null || s > bestScore!!) bestScore = s
                continue
            }
            // Layer 3: phonetic edit distance.
            // Guard: also require the raw edit distance is within a loose bound
            // (word length + 2) so that a completely different word that merely
            // shares one consonant group (e.g. "either"→"36" vs "ida"→"3", ed=1)
            // does not score here.
            val wordDigits = phoneticDigits(word)
            val phonEd = editDistance(wordDigits, targetDigits)
            if (phonEd <= 1 && ed <= target.length + 1) {
                val s = 50 - phonEd * 10
                if (bestScore == null || s > bestScore!!) bestScore = s
                continue
            }
            // Layer 4: phonetic subsequence.
            // Only for targets with ≥ 2 consonant groups (single-digit codes like
            // "ida"→"3" would match almost any word containing a D/T sound).
            if (targetDigits.length >= 2 && word.length >= target.length - 1) {
                if (isSubsequence(targetDigits, wordDigits) || isSubsequence(wordDigits, targetDigits)) {
                    if (bestScore == null || 20 > bestScore!!) bestScore = 20
                }
            }
        }
        return bestScore
    }

    /**
     * Tries to find an ADD action keyword anywhere in [words].
     * Accepts "point"/"points" exactly, plus fuzzy variants with edit
     * distance ≤ 1 (e.g. "pint", "ponte", "pointed").
     */
    private fun findAddAction(words: List<String>): Int {
        // Accepted add keywords:
        //   "point" — matched raw (ed≤1) and phonetically (code "153").
        //             Catches "find","fond","faint","bind","pound" via phonetic.
        //   "add"   — matched raw only (ed≤1). Its phonetic code is "3" (just the
        //             D-sound), which collides with "undo"/"take" phonetically, so
        //             we never use phonetic matching for "add".
        //             "and" (ed=1 from "add") is explicitly excluded — it's a common
        //             filler word, not a command.
        val removeWords = setOf("undo", "unto", "minus", "remove", "delete", "take")
        val notAddWords = setOf("and", "ant", "any")  // common words too close to "add"
        val pointDigits = phoneticDigits("point")      // "153"
        for ((idx, w) in words.withIndex()) {
            if (w in removeWords || w in notAddWords) continue
            // "add" — raw match only, no phonetic (code too short/ambiguous)
            if (editDistance(w, "add") <= 1) return idx
            // "point" — raw + phonetic
            if (editDistance(w, "point") <= 1) return idx
            if (editDistance(phoneticDigits(w), pointDigits) <= 1) return idx
        }
        return -1
    }

    /**
     * Tries to find a REMOVE action keyword anywhere in [words].
     * Accepts "minus", "remove", "undo" exactly, plus fuzzy variants
     * with edit distance ≤ 1 on both raw spelling and phonetic code.
     */
    private fun findRemoveAction(words: List<String>): Int {
        // Raw edit distance only — no phonetic matching for remove words.
        // "undo"/"minus"/"remove" are short and distinct enough that ed≤1
        // already covers all realistic mishearings (unto, undu, minos, remov…).
        // Phonetic matching caused false positives: "at"→"3" is phonetically
        // close to "undo"→"53" and would incorrectly trigger a remove action.
        for ((idx, w) in words.withIndex()) {
            if (editDistance(w, "minus")  <= 1) return idx
            if (editDistance(w, "remove") <= 1) return idx
            if (editDistance(w, "undo")   <= 1) return idx
        }
        return -1
    }

    /**
     * Check whether [rest] matches the side keyword "left" or "right",
     * using exact match OR fuzzy (edit distance ≤ 1).
     */
    private fun matchesSide(rest: String, side: String): Boolean {
        if (rest == side) return true
        return rest.split("\\s+".toRegex()).any { editDistance(it, side) <= 1 }
    }

    private fun handleResult(text: String) {
        Log.d(TAG, "HANDLE: $text")
        val input = text.lowercase().trim()
        val words = input.split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.isEmpty()) return

        // Determine action: try ADD first, then REMOVE
        val addIdx    = findAddAction(words)
        val removeIdx = findRemoveAction(words)

        val isAdd: Boolean
        val actionIdx: Int
        when {
            addIdx >= 0 && removeIdx < 0 -> { isAdd = true;  actionIdx = addIdx }
            removeIdx >= 0 && addIdx < 0 -> { isAdd = false; actionIdx = removeIdx }
            addIdx >= 0 && removeIdx >= 0 -> {
                // Both found — pick whichever comes first
                isAdd = addIdx <= removeIdx; actionIdx = minOf(addIdx, removeIdx)
            }
            else -> {
                Log.d(TAG, "No action word found in: '$input'")
                return
            }
        }

        // Everything after the action word is the target
        val rest = words.drop(actionIdx + 1).joinToString(" ").trim()
        if (rest.isEmpty()) {
            Log.d(TAG, "Action word found but no target spoken — ignoring")
            return
        }
        val actionLabel = if (isAdd) "point" else "minus"

        // Side keywords ("left" / "right") get a high fixed score so they
        // always beat a name match when explicitly spoken.
        val sideLeftScore  = if (matchesSide(rest, "left"))  90 else null
        val sideRightScore = if (matchesSide(rest, "right")) 90 else null

        // Score both player names. The higher score wins, preventing short names
        // like "ida" (phonetic code "3") from losing to the other player's name
        // on an ambiguous transcription.
        val leftNameScore  = if (leftPlayerName.isNotEmpty())  fuzzyScore(rest, leftPlayerName)  else null
        val rightNameScore = if (rightPlayerName.isNotEmpty()) fuzzyScore(rest, rightPlayerName) else null

        data class Candidate(val score: Int, val isLeft: Boolean, val label: String, val spokenName: String)
        val candidates = listOfNotNull(
            sideLeftScore?.let  { Candidate(it, true,  "LEFT (keyword)",              "left") },
            sideRightScore?.let { Candidate(it, false, "RIGHT (keyword)",             "right") },
            leftNameScore?.let  { Candidate(it, true,  "LEFT (name '$leftPlayerName')",  leftPlayerName) },
            rightNameScore?.let { Candidate(it, false, "RIGHT (name '$rightPlayerName')", rightPlayerName) }
        )

        val best = candidates.maxByOrNull { it.score }
        if (best == null) {
            Log.d(TAG, "No target matched for: '$rest' (full input: '$input')")
            return
        }

        Log.d(TAG, "Matched: $actionLabel ${best.label} score=${best.score}")
        speak("$actionLabel ${best.spokenName}")
        if (best.isLeft) {
            if (isAdd) onPointLeft() else onMinusLeft()
        } else {
            if (isAdd) onPointRight() else onMinusRight()
        }
    }
}