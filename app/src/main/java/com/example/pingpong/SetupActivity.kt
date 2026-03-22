package com.example.pingpong

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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

        binding.btnStartGame.setOnClickListener {
            saveSettings()
            startGame()
        }

        binding.btnInfo.setOnClickListener {
            showInfoDialog()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "unavailable"
    }

    private fun showInfoDialog() {
        val ip = getLocalIpAddress()
        val amber = Color.parseColor("#FF8F00")
        val gray = Color.parseColor("#AAAAAA")

        val sb = SpannableStringBuilder()

        fun addSection(title: String, body: String) {
            val start = sb.length
            sb.append(title).append("\n")
            sb.setSpan(ForegroundColorSpan(amber), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(1.1f), start, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val bodyStart = sb.length
            sb.append(body).append("\n\n")
            sb.setSpan(ForegroundColorSpan(gray), bodyStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        addSection("NAMES", "Tap a player name to edit it.")
        addSection("SCORING", "Tap the score number to add a point.\nTap −1 to remove a point.")
        addSection("SWAP", "Tap ⇄ to swap sides.")
        addSection("SERVE",
            "At the start of each set, a popup asks who serves first.\nThe green dots indicate the current server — starting with 2 dots.\nThe dots switch automatically every 2 points (every 1 point at deuce).")

        // TV title
        val tvTitleStart = sb.length
        sb.append("TV SCOREBOARD").append("\n")
        sb.setSpan(ForegroundColorSpan(amber), tvTitleStart, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.1f), tvTitleStart, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // TV body
        val tvBodyStart = sb.length
        sb.append("Make sure your phone and TV are on the same Wi-Fi.\nOpen the browser on your TV and type: ")
        sb.setSpan(ForegroundColorSpan(gray), tvBodyStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // IP inline
        val ipStart = sb.length
        sb.append("http://$ip:8080")
        sb.setSpan(ForegroundColorSpan(amber), ipStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.1f), ipStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val tv = TextView(this).apply {
            text = sb
            textSize = 13f
            setPadding(48, 24, 48, 24)
            setTextColor(Color.WHITE)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(tv)
            setPadding(0, 8, 0, 8)
        }

        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Help")
            .setView(scrollView)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun loadLastSettings() {
        val prefs = getSharedPreferences("pingpong_prefs", Context.MODE_PRIVATE)
        val fourPlayers = prefs.getBoolean("fourPlayers", false)
        val winScore = prefs.getInt("winScore", 11)
        val bestOf = prefs.getInt("bestOf", 1)

        if (fourPlayers) binding.rb4Players.isChecked = true
        else binding.rb2Players.isChecked = true

        if (winScore == 21) binding.rb21.isChecked = true
        else binding.rb11.isChecked = true

        when (bestOf) {
            3 -> binding.rbBo3.isChecked = true
            5 -> binding.rbBo5.isChecked = true
            else -> binding.rbBo1.isChecked = true
        }
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