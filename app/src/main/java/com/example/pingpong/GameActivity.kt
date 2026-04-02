package com.example.pingpong

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.pingpong.databinding.ActivityGameBinding

class GameActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameBinding

    private var isFourPlayers = false
    private var winScore = 11
    private var bestOf = 1

    private var team1Name = "PLAYER 1"
    private var team2Name = "PLAYER 2"

    private var score1 = 0
    private var score2 = 0
    private var sets1 = 0
    private var sets2 = 0

    private val history = ArrayDeque<String>()
    private var setInProgress = true

    private var servingPlayer = 0
    private var serveStartTotal = 0

    private val server get() = SetupActivity.scoreServer

    // Voice
    private var voiceCommandManager: VoiceCommandManager? = null
    private var voiceEnabled = false
    private val MIC_PERMISSION_REQUEST = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen on while game is active
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        isFourPlayers = intent.getBooleanExtra("fourPlayers", false)
        winScore = intent.getIntExtra("winScore", 11)
        bestOf = intent.getIntExtra("bestOf", 1)

        val prefs = getSharedPreferences("pingpong_prefs", Context.MODE_PRIVATE)
        if (isFourPlayers) {
            team1Name = prefs.getString("team1Name", "TEAM 1") ?: "TEAM 1"
            team2Name = prefs.getString("team2Name", "TEAM 2") ?: "TEAM 2"
        } else {
            team1Name = prefs.getString("player1Name", "PLAYER 1") ?: "PLAYER 1"
            team2Name = prefs.getString("player2Name", "PLAYER 2") ?: "PLAYER 2"
        }

        voiceEnabled = prefs.getBoolean(VoiceSettingsActivity.KEY_VOICE_ENABLED, false)

        server?.gameStarted = true

        updateDisplay()
        updateServeDots()
        requestMicPermissionAndSetupVoice()
        askWhoServes()

        binding.tvScore1.setOnClickListener {
            if (!setInProgress) return@setOnClickListener
            history.addLast("1")
            score1++
            updateDisplay()
            updateServe()
            checkSetWin()
        }

        binding.tvScore2.setOnClickListener {
            if (!setInProgress) return@setOnClickListener
            history.addLast("2")
            score2++
            updateDisplay()
            updateServe()
            checkSetWin()
        }

        binding.tvTeam1Name.setOnClickListener { showEditNameDialog(1) }
        binding.tvTeam2Name.setOnClickListener { showEditNameDialog(2) }

        binding.dotServer1.setOnClickListener { setServer(1) }
        binding.dotServer2.setOnClickListener { setServer(2) }

        binding.btnSwap.setOnClickListener { swapSides() }

        binding.btnUndo1.setOnClickListener {
            if (score1 > 0) {
                score1--
                updateDisplay()
                recalculateServeFromTotal()
            }
        }

        binding.btnUndo2.setOnClickListener {
            if (score2 > 0) {
                score2--
                updateDisplay()
                recalculateServeFromTotal()
            }
        }

        binding.btnNewGame.setOnClickListener {
            if (score1 == 0 && score2 == 0) {
                resetSet()
                askWhoServes()
            } else {
                AlertDialog.Builder(this, R.style.DarkDialog)
                    .setTitle("New Set?")
                    .setMessage("Current scores will be lost.")
                    .setPositiveButton("Confirm") { _, _ ->
                        resetSet()
                        askWhoServes()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        binding.btnRestart.setOnClickListener {
            AlertDialog.Builder(this, R.style.DarkDialog)
                .setTitle("Restart Match?")
                .setMessage("This will reset all scores and sets.")
                .setPositiveButton("Restart") { _, _ ->
                    resetMatch()
                    askWhoServes()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnSetup.setOnClickListener {
            if (score1 == 0 && score2 == 0 && sets1 == 0 && sets2 == 0) {
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
            } else {
                AlertDialog.Builder(this, R.style.DarkDialog)
                    .setTitle("Go to Setup?")
                    .setMessage("Current match will be lost.")
                    .setPositiveButton("Confirm") { _, _ ->
                        startActivity(Intent(this, SetupActivity::class.java))
                        finish()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        binding.btnInfo.setOnClickListener { showInfoDialog() }
    }

    // ───────────────────────── VOICE ─────────────────────────

    private fun requestMicPermissionAndSetupVoice() {
        if (!voiceEnabled) {
            binding.tvMicIndicator.visibility = android.view.View.GONE
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            setupVoiceCommands()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupVoiceCommands()
            } else {
                // Permission denied — hide mic icon and disable voice silently
                binding.tvMicIndicator.visibility = android.view.View.GONE
            }
        }
    }

    private fun setupVoiceCommands() {
        if (!voiceEnabled) {
            binding.tvMicIndicator.visibility = android.view.View.GONE
            return
        }

        binding.tvMicIndicator.visibility = android.view.View.VISIBLE
        setMicIdle()

        voiceCommandManager = VoiceCommandManager(
            context = this,
            onPointLeft = {
                runOnUiThread {
                    if (!setInProgress) return@runOnUiThread
                    history.addLast("1")
                    score1++
                    updateDisplay()
                    updateServe()
                    flashMicRecognized()
                    checkSetWin()
                }
            },
            onPointRight = {
                runOnUiThread {
                    if (!setInProgress) return@runOnUiThread
                    history.addLast("2")
                    score2++
                    updateDisplay()
                    updateServe()
                    flashMicRecognized()
                    checkSetWin()
                }
            },
            onMinusLeft = {
                runOnUiThread {
                    if (score1 > 0) {
                        score1--
                        updateDisplay()
                        recalculateServeFromTotal()
                        flashMicRecognized()
                    }
                }
            },
            onMinusRight = {
                runOnUiThread {
                    if (score2 > 0) {
                        score2--
                        updateDisplay()
                        recalculateServeFromTotal()
                        flashMicRecognized()
                    }
                }
            },
            onModelLoadFailed = {
                runOnUiThread {
                    binding.tvMicIndicator.visibility = android.view.View.GONE
                    AlertDialog.Builder(this, R.style.DarkDialog)
                        .setTitle("Voice Unavailable")
                        .setMessage("Failed to load the speech model. Voice commands are disabled.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        )
        voiceCommandManager?.updatePlayerNames(team1Name, team2Name)
        voiceCommandManager?.start()
    }

    private fun setMicIdle() {
        binding.tvMicIndicator.setTextColor(Color.parseColor("#FF8F00"))
        binding.tvMicIndicator.alpha = 0.4f
    }

    private fun flashMicRecognized() {
        binding.tvMicIndicator.alpha = 1.0f
        binding.tvMicIndicator.setTextColor(Color.parseColor("#FF8F00"))
        binding.tvMicIndicator.postDelayed({ setMicIdle() }, 600)
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceCommandManager?.stop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        voiceCommandManager?.stop()
    }

    override fun onResume() {
        super.onResume()
        if (voiceEnabled) voiceCommandManager?.start()
    }

    // ─────────────────────── EXISTING LOGIC ───────────────────────

    private fun showInfoDialog() {
        HelpDialog.show(this, voiceEnabled)
    }

    private fun buildWinnerView(label: String, name: String, sub: String): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 24)
            minimumWidth = 0
        }

        val labelView = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }

        val nameView = TextView(this).apply {
            text = name
            textSize = 28f
            setTextColor(Color.parseColor("#FF8F00"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }

        val subView = TextView(this).apply {
            text = sub
            textSize = 13f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        }

        container.addView(labelView)
        container.addView(nameView)
        container.addView(subView)
        return container
    }

    private fun updateServer() {
        val s = server ?: return
        s.team1Name = team1Name
        s.team2Name = team2Name
        s.score1 = score1
        s.score2 = score2
        s.sets1 = sets1
        s.sets2 = sets2
        s.servingPlayer = servingPlayer
        s.bestOf = bestOf
        s.winScore = winScore
        s.gameStarted = true

        if (servingPlayer == 0) {
            s.dot1Count = 0
            s.dot2Count = 0
        } else {
            val total = score1 + score2
            val deuce = score1 >= winScore - 1 && score2 >= winScore - 1
            val pointsPerServe = if (deuce) 1 else 2
            val pointsUsed = if (total >= serveStartTotal)
                (total - serveStartTotal) % pointsPerServe else 0
            val dotsRemaining = pointsPerServe - pointsUsed
            if (servingPlayer == 1) {
                s.dot1Count = dotsRemaining
                s.dot2Count = 0
            } else {
                s.dot1Count = 0
                s.dot2Count = dotsRemaining
            }
        }
        s.pushUpdate()
    }

    private fun saveNames() {
        val prefs = getSharedPreferences("pingpong_prefs", Context.MODE_PRIVATE)
        if (isFourPlayers) {
            prefs.edit().putString("team1Name", team1Name).putString("team2Name", team2Name).apply()
        } else {
            prefs.edit().putString("player1Name", team1Name).putString("player2Name", team2Name).apply()
        }
    }

    private fun askWhoServes() {
        servingPlayer = 0
        serveStartTotal = 0
        updateServeDots()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 16)
        }

        fun makeNameButton(name: String, player: Int): TextView {
            return TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(24, 16, 24, 16)
                background = getDrawable(R.drawable.name_border)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (player == 1) marginEnd = 24 else marginStart = 24
                }
            }
        }

        val btn1 = makeNameButton(team1Name, 1)
        val btn2 = makeNameButton(team2Name, 2)
        container.addView(btn1)
        container.addView(btn2)

        val dialog = AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Who serves first?")
            .setView(container)
            .setCancelable(true)
            .create()

        btn1.setOnClickListener { setServer(1); dialog.dismiss() }
        btn2.setOnClickListener { setServer(2); dialog.dismiss() }

        dialog.show()
    }

    private fun setServer(player: Int) {
        servingPlayer = player
        serveStartTotal = score1 + score2
        updateServeDots()
        updateServer()
    }

    private fun updateServe() {
        if (servingPlayer == 0) return
        val total = score1 + score2
        val deuce = score1 >= winScore - 1 && score2 >= winScore - 1
        val pointsPerServe = if (deuce) 1 else 2
        val pointsSinceStart = total - serveStartTotal
        if (pointsSinceStart > 0 && pointsSinceStart % pointsPerServe == 0) {
            servingPlayer = if (servingPlayer == 1) 2 else 1
            serveStartTotal = total
        }
        updateServeDots()
    }

    private fun recalculateServeFromTotal() {
        if (servingPlayer == 0) return
        val total = score1 + score2
        val deuce = score1 >= winScore - 1 && score2 >= winScore - 1
        val pointsPerServe = if (deuce) 1 else 2
        val pointsSinceStart = total - serveStartTotal
        if (pointsSinceStart < 0) {
            servingPlayer = if (servingPlayer == 1) 2 else 1
            serveStartTotal = total - (total % pointsPerServe)
            if (serveStartTotal < 0) serveStartTotal = 0
        }
        updateServeDots()
    }

    private fun updateServeDots() {
        val dimAlpha = 0.15f
        val brightAlpha = 1.0f

        if (servingPlayer == 0) {
            binding.dot1a.alpha = dimAlpha
            binding.dot1b.alpha = dimAlpha
            binding.dot2a.alpha = dimAlpha
            binding.dot2b.alpha = dimAlpha
            updateServer()
            return
        }

        val total = score1 + score2
        val deuce = score1 >= winScore - 1 && score2 >= winScore - 1
        val pointsPerServe = if (deuce) 1 else 2
        val pointsUsed = (total - serveStartTotal) % pointsPerServe
        val dotsRemaining = pointsPerServe - pointsUsed

        if (servingPlayer == 1) {
            binding.dot1a.alpha = brightAlpha
            binding.dot1b.alpha = if (dotsRemaining >= 2) brightAlpha else dimAlpha
            binding.dot2a.alpha = dimAlpha
            binding.dot2b.alpha = dimAlpha
        } else {
            binding.dot1a.alpha = dimAlpha
            binding.dot1b.alpha = dimAlpha
            binding.dot2a.alpha = brightAlpha
            binding.dot2b.alpha = if (dotsRemaining >= 2) brightAlpha else dimAlpha
        }
        updateServer()
    }

    private fun swapSides() {
        val tempName = team1Name; team1Name = team2Name; team2Name = tempName
        val tempScore = score1; score1 = score2; score2 = tempScore
        val tempSets = sets1; sets1 = sets2; sets2 = tempSets
        for (i in history.indices) {
            history[i] = if (history[i] == "1") "2" else "1"
        }
        if (servingPlayer == 1) servingPlayer = 2
        else if (servingPlayer == 2) servingPlayer = 1

        // Update voice manager with new left/right player names after swap
        voiceCommandManager?.updatePlayerNames(team1Name, team2Name)

        updateDisplay()
        updateServeDots()
    }

    private fun showEditNameDialog(player: Int) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.setText(if (player == 1) team1Name else team2Name)
        input.setTextColor(0xFFFF8F00.toInt())
        input.setHintTextColor(0xFF888888.toInt())
        input.background = null
        input.setPadding(32, 16, 32, 16)
        input.setSelection(input.text.length)

        val container = android.widget.FrameLayout(this)
        container.setPadding(48, 16, 48, 0)
        container.addView(input)

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Edit name")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val name = input.text.toString().trim().uppercase()
                if (name.isNotEmpty()) {
                    if (player == 1) team1Name = name else team2Name = name
                    updateDisplay()
                    saveNames()
                    // Update voice manager with new name
                    voiceCommandManager?.updatePlayerNames(team1Name, team2Name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateDisplay() {
        binding.tvTeam1Name.text = team1Name
        binding.tvTeam2Name.text = team2Name
        binding.tvScore1.text = score1.toString()
        binding.tvScore2.text = score2.toString()
        binding.tvSetsScore.text = "$sets1 — $sets2"
        val formatLabel = when (bestOf) {
            3 -> "B3"
            5 -> "B5"
            else -> "B1"
        }
        binding.tvMatchFormat.text = "$formatLabel · $winScore"
        updateServer()
    }

    private fun checkSetWin() {
        val setsNeeded = (bestOf / 2) + 1
        val team1Wins = score1 >= winScore && (score1 - score2) >= 2
        val team2Wins = score2 >= winScore && (score2 - score1) >= 2

        if (team1Wins || team2Wins) {
            setInProgress = false
            if (team1Wins) sets1++ else sets2++

            val matchWinner = when {
                sets1 >= setsNeeded -> team1Name
                sets2 >= setsNeeded -> team2Name
                else -> null
            }

            if (matchWinner != null) {
                server?.setWinner = ""
                server?.matchWinner = matchWinner
                updateDisplay()
                showMatchWinner(matchWinner, team1Wins)
            } else {
                val setWinner = if (team1Wins) team1Name else team2Name
                server?.matchWinner = ""
                server?.setWinner = setWinner
                updateDisplay()

                val view = buildWinnerView("SET OVER", setWinner, "Sets  $sets1 — $sets2")
                val d = AlertDialog.Builder(this, R.style.DarkDialog)
                    .setView(view)
                    .setPositiveButton("Next Set") { _, _ ->
                        server?.setWinner = ""
                        resetSet()
                        setInProgress = true
                        askWhoServes()
                    }
                    .setNegativeButton("Restart Match") { _, _ ->
                        if (team1Wins) sets1-- else sets2--
                        server?.setWinner = ""
                        server?.matchWinner = ""
                        resetMatch()
                        setInProgress = true
                        askWhoServes()
                    }
                    .setCancelable(false)
                    .show()
                d.window?.setLayout(800, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun showMatchWinner(winner: String, team1Won: Boolean) {
        val view = buildWinnerView("MATCH OVER", winner, "Final Sets  $sets1 — $sets2")
        val d = AlertDialog.Builder(this, R.style.DarkDialog)
            .setView(view)
            .setPositiveButton("Play Again") { _, _ ->
                server?.matchWinner = ""
                server?.setWinner = ""
                resetMatch()
                setInProgress = true
                askWhoServes()
            }
            .setNegativeButton("Setup") { _, _ ->
                server?.matchWinner = ""
                server?.setWinner = ""
                startActivity(Intent(this, SetupActivity::class.java))
                finish()
            }
            .setCancelable(false)
            .show()
        d.window?.setLayout(800, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun resetSet() {
        score1 = 0
        score2 = 0
        history.clear()
        servingPlayer = 0
        serveStartTotal = 0
        updateDisplay()
        updateServeDots()
    }

    private fun resetMatch() {
        score1 = 0
        score2 = 0
        sets1 = 0
        sets2 = 0
        history.clear()
        servingPlayer = 0
        serveStartTotal = 0
        updateDisplay()
        updateServeDots()
    }
}