package com.example.pingpong

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object HelpDialog {

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

    fun show(context: Context, voiceEnabled: Boolean) {
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
        addSection(
            "SERVE",
            "At the start of each set, a popup asks who serves first.\nThe green dots indicate the current server — starting with 2 dots.\nThe dots switch automatically every 2 points (every 1 point at deuce)."
        )

        if (voiceEnabled) {
            addSection(
                "VOICE COMMANDS",
                 "\"Point left\" / \"Point right\" / \"Point [name]\" — add a point\n" +
                        "\"Add left\" / \"Add right\" / \"Add [name]\" — add a point\n" +
                        "\"Minus left\" / \"Minus right\" / \"Minus [name]\" — remove a point\n" +
                        "\"Remove left\" / \"Remove right\" / \"Remove [name]\" — remove a point\n" +
                        "\"Undo left\" / \"Undo right\" / \"Undo [name]\" — remove a point\n" +
                        "The app will repeat the command back if recognized."
            )
        }

        val tvTitleStart = sb.length
        sb.append("LIVE SCOREBOARD").append("\n")
        sb.setSpan(ForegroundColorSpan(amber), tvTitleStart, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.1f), tvTitleStart, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val tvBodyStart = sb.length
        sb.append("The app hosts a web page on your phone. Any device on the same Wi-Fi — TV, laptop, tablet, another phone — can open it in a browser. No app or casting needed.\n\n1. Connect your phone and the display device to the same Wi-Fi.\n2. On the display device, open any browser and go to: ")
        sb.setSpan(ForegroundColorSpan(gray), tvBodyStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val ipStart = sb.length
        sb.append("http://$ip:8080")
        sb.setSpan(ForegroundColorSpan(amber), ipStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.1f), ipStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val tvBody2Start = sb.length
        sb.append("\n\nThe scoreboard updates instantly as points are scored. This page will show \"Game Starting Soon\" until a match begins.")
        sb.setSpan(ForegroundColorSpan(gray), tvBody2Start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val tv = TextView(context).apply {
            text = sb
            textSize = 13f
            setPadding(48, 24, 48, 24)
            setTextColor(Color.WHITE)
        }

        val scrollView = android.widget.ScrollView(context).apply {
            addView(tv)
            setPadding(0, 8, 0, 8)
        }

        AlertDialog.Builder(context, R.style.DarkDialog)
            .setTitle("Help")
            .setView(scrollView)
            .setPositiveButton("Got it", null)
            .show()
    }
}