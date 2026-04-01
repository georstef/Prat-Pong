package com.example.pingpong

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.pingpong.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    companion object {
        var scoreServer: ScoreServer? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start server if not already running
        if (scoreServer == null) {
            scoreServer = ScoreServer(applicationContext)
            try {
                scoreServer?.startAll()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Show splash on TV
        scoreServer?.gameStarted = false
        scoreServer?.pushUpdate()

        loadLastSettings()
        updateVoiceSettingsRowState()

        binding.btnStartGame.setOnClickListener {
            saveSettings()
            startGame()
        }

        binding.btnInfo.setOnClickListener {
            val voiceEnabled = binding.rbVoiceEnabled.isChecked
            HelpDialog.show(this, voiceEnabled)
        }

        // Voice enable/disable radio buttons
        binding.rgVoice.setOnCheckedChangeListener { _, _ ->
            saveSettings()
            updateVoiceSettingsRowState()
        }

        // Voice Settings row tap
        binding.rowVoiceSettings.setOnClickListener {
            val enabled = binding.rbVoiceEnabled.isChecked
            if (enabled) {
                startActivity(Intent(this, VoiceSettingsActivity::class.java))
            }
        }
    }

    private fun updateVoiceSettingsRowState() {
        val enabled = binding.rbVoiceEnabled.isChecked
        binding.rowVoiceSettings.alpha = if (enabled) 1.0f else 0.35f
        binding.rowVoiceSettings.isClickable = enabled
        binding.rowVoiceSettings.isFocusable = enabled
    }

    private fun loadLastSettings() {
        val prefs = getSharedPreferences("pingpong_prefs", Context.MODE_PRIVATE)
        val fourPlayers = prefs.getBoolean("fourPlayers", false)
        val winScore = prefs.getInt("winScore", 11)
        val bestOf = prefs.getInt("bestOf", 1)
        val voiceEnabled = prefs.getBoolean(VoiceSettingsActivity.KEY_VOICE_ENABLED, false)

        if (fourPlayers) binding.rb4Players.isChecked = true
        else binding.rb2Players.isChecked = true

        if (winScore == 21) binding.rb21.isChecked = true
        else binding.rb11.isChecked = true

        when (bestOf) {
            3 -> binding.rbBo3.isChecked = true
            5 -> binding.rbBo5.isChecked = true
            else -> binding.rbBo1.isChecked = true
        }

        if (voiceEnabled) binding.rbVoiceEnabled.isChecked = true
        else binding.rbVoiceDisabled.isChecked = true
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("pingpong_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("fourPlayers", binding.rb4Players.isChecked)
            .putInt("winScore", if (binding.rb21.isChecked) 21 else 11)
            .putInt("bestOf", when (binding.rgBestOf.checkedRadioButtonId) {
                R.id.rbBo3 -> 3
                R.id.rbBo5 -> 5
                else -> 1
            })
            .putBoolean(VoiceSettingsActivity.KEY_VOICE_ENABLED, binding.rbVoiceEnabled.isChecked)
            .apply()
    }

    private fun startGame() {
        val isFourPlayers = binding.rb4Players.isChecked
        val winScore = if (binding.rb21.isChecked) 21 else 11
        val bestOf = when (binding.rgBestOf.checkedRadioButtonId) {
            R.id.rbBo3 -> 3
            R.id.rbBo5 -> 5
            else -> 1
        }

        val intent = Intent(this, GameActivity::class.java).apply {
            putExtra("fourPlayers", isFourPlayers)
            putExtra("winScore", winScore)
            putExtra("bestOf", bestOf)
        }
        startActivity(intent)
    }
}