package com.example.pingpong

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class VoiceSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "pingpong_prefs"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_VOICE_LANGUAGES = "voice_languages"

        // Only English is locked on
        fun getLockedLanguages(): Set<String> = setOf("en")

        // Get keyboard-detected languages (excluding English, which is always present)
        fun getKeyboardLanguages(context: Context): Set<String> {
            val langs = mutableSetOf<String>()
            try {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val methods = imm.enabledInputMethodList
                for (method in methods) {
                    val subtypes = imm.getEnabledInputMethodSubtypeList(method, true)
                    for (subtype in subtypes) {
                        val locale = subtype.locale
                        if (locale.isNotEmpty()) {
                            val lang = locale.split("_")[0].lowercase()
                            if (lang.length == 2 || lang.length == 3) langs.add(lang)
                        }
                    }
                }
                val primaryLang = Locale.getDefault().language
                if (primaryLang.isNotEmpty()) langs.add(primaryLang)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            langs.remove("en") // English is handled separately as locked
            return langs
        }

        // Load saved language selection
        // English is always true; others default to false unless saved as true
        fun loadLanguageSelection(context: Context): Map<String, Boolean> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val keyboardLangs = getKeyboardLanguages(context)
            val savedJson = prefs.getString(KEY_VOICE_LANGUAGES, null)

            val savedMap = mutableMapOf<String, Boolean>()
            if (savedJson != null) {
                savedJson.split(",").forEach { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) savedMap[parts[0]] = parts[1] == "true"
                }
            }

            val result = mutableMapOf<String, Boolean>()
            result["en"] = true // English always on
            for (lang in keyboardLangs) {
                result[lang] = savedMap[lang] == true // default false unless explicitly saved true
            }
            return result
        }

        fun saveLanguageSelection(context: Context, selection: Map<String, Boolean>) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encoded = selection.entries.joinToString(",") { "${it.key}:${it.value}" }
            prefs.edit().putString(KEY_VOICE_LANGUAGES, encoded).apply()
        }

        // Returns enabled language codes for the speech recognizer
        fun getEnabledLanguages(context: Context): List<String> {
            return loadLanguageSelection(context).filter { it.value }.keys.toList()
        }

        fun getLanguageDisplayName(code: String): String {
            return try {
                Locale(code).getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.uppercase() }
            } catch (e: Exception) {
                code.uppercase()
            }
        }

        // Check whether ML Kit already has the model for a given language downloaded
        fun isModelDownloaded(langCode: String, onResult: (Boolean) -> Unit) {
            val mlKitCode = TranslateLanguage.fromLanguageTag(langCode) ?: run {
                onResult(false)
                return
            }
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mlKitCode)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            val translator = Translation.getClient(options)
            // Attempt a translation with no download — if models are missing it will fail
            translator.translate("test")
                .addOnSuccessListener { onResult(true) }
                .addOnFailureListener { onResult(false) }
                .also { translator.close() }
        }
    }

    private val amber = Color.parseColor("#FF8F00")
    private val gray = Color.parseColor("#AAAAAA")
    private val dimGray = Color.parseColor("#444444")

    private lateinit var languageSelection: MutableMap<String, Boolean>
    private val checkBoxes = mutableMapOf<String, CheckBox>()
    private val statusLabels = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        languageSelection = loadLanguageSelection(this).toMutableMap()

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
        }

        // Title
        container.addView(TextView(this).apply {
            text = "VOICE SETTINGS"
            textSize = 22f
            setTextColor(Color.WHITE)
            letterSpacing = 0.2f
            typeface = android.graphics.Typeface.create("sans-serif-thin", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        })

        container.addView(View(this).apply {
            setBackgroundColor(amber)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { bottomMargin = 40 }
        })

        // --- LANGUAGES SECTION ---
        container.addView(makeSectionTitle("LANGUAGES"))
        container.addView(makeSectionBody(
            "English is always enabled. Enable additional languages to use voice commands in them. " +
                    "A one-time model download (~15 MB) may be required per language."
        ))

        // English row — locked
        container.addView(makeLanguageRow("en", locked = true))

        // Other keyboard languages — unchecked by default
        val otherLangs = getKeyboardLanguages(this)
            .sortedBy { getLanguageDisplayName(it) }

        for (lang in otherLangs) {
            container.addView(makeLanguageRow(lang, locked = false))
        }

        container.addView(makeDivider())

        // --- VOICE COMMANDS SECTION ---
        container.addView(makeSectionTitle("VOICE COMMANDS"))
        container.addView(makeSectionBody(
            "Speak commands in any enabled language — the app translates them automatically.\n\n" +
                    "Add a point:  \"Point left\"  /  \"Point right\"  /  \"Point [name]\"\n" +
                    "Remove a point:  \"Minus left\"  /  \"Minus right\"  /  \"Remove left\"  /  \"Remove right\"\n\n" +
                    "Left and right refer to screen position. Player names work in any language."
        ))

        container.addView(makeDivider())

        // --- CONFIRMATION SECTION ---
        container.addView(makeSectionTitle("CONFIRMATION"))
        container.addView(makeSectionBody(
            "When a command is recognized the app repeats it back via text-to-speech.\n\n" +
                    "Silence means the command was not understood — simply try again.\n\n" +
                    "This feature is in Beta — accuracy may vary."
        ))

        container.addView(makeDivider())

        // --- TIPS ---
        container.addView(makeSectionTitle("TIPS"))
        container.addView(makeSectionBody(
            "• Speak clearly and at a normal volume.\n" +
                    "• Short player names work best for voice recognition.\n" +
                    "• Language models are downloaded once and work offline afterwards."
        ))

        container.addView(TextView(this).apply {
            text = "← BACK"
            textSize = 13f
            setTextColor(amber)
            letterSpacing = 0.1f
            setPadding(0, 32, 0, 16)
            setOnClickListener { finish() }
        })

        scroll.addView(container)
        setContentView(scroll)
    }

    private fun makeLanguageRow(lang: String, locked: Boolean): LinearLayout {
        val isChecked = languageSelection[lang] == true

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 }
        }

        val cb = CheckBox(this).apply {
            this.isChecked = isChecked
            this.isEnabled = !locked
            androidx.core.widget.CompoundButtonCompat.setButtonTintList(
                this,
                android.content.res.ColorStateList.valueOf(if (locked) dimGray else amber)
            )
        }

        val labelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        }

        val nameLabel = TextView(this).apply {
            text = getLanguageDisplayName(lang) + if (locked) "  🔒" else ""
            textSize = 15f
            setTextColor(if (locked) dimGray else Color.WHITE)
        }

        val statusLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(gray)
            visibility = if (locked) View.GONE else View.VISIBLE
            text = if (isChecked) "Enabled" else ""
        }

        labelCol.addView(nameLabel)
        labelCol.addView(statusLabel)
        row.addView(cb)
        row.addView(labelCol)

        checkBoxes[lang] = cb
        statusLabels[lang] = statusLabel

        if (!locked) {
            val listener = android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
                if (checked) {
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = false
                    cb.isEnabled = false
                    statusLabel.text = "Checking\u2026"
                    handleLanguageEnable(lang, cb, statusLabel)
                } else {
                    languageSelection[lang] = false
                    saveLanguageSelection(this, languageSelection)
                    statusLabel.text = ""
                }
            }
            cb.setOnCheckedChangeListener(listener)
            cb.tag = listener
        }

        return row
    }

    private fun handleLanguageEnable(lang: String, cb: CheckBox, statusLabel: TextView) {
        val mlKitCode = TranslateLanguage.fromLanguageTag(lang)
        if (mlKitCode == null) {
            // ML Kit doesn't support this language
            cb.isEnabled = true
            statusLabel.text = "Not supported"
            return
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlKitCode)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)

        // Try translating without downloading — succeeds only if models already present
        translator.translate("test")
            .addOnSuccessListener {
                translator.close()
                // Models already on device — enable immediately, no confirmation needed
                runOnUiThread {
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = true
                    cb.isEnabled = true
                    @Suppress("UNCHECKED_CAST")
                    cb.setOnCheckedChangeListener(cb.tag as? android.widget.CompoundButton.OnCheckedChangeListener)
                    languageSelection[lang] = true
                    saveLanguageSelection(this, languageSelection)
                    statusLabel.text = "Enabled"
                }
            }
            .addOnFailureListener {
                translator.close()
                // Models not present — ask user to confirm download
                runOnUiThread {
                    cb.isEnabled = true
                    statusLabel.text = ""
                    showDownloadConfirmation(lang, cb, statusLabel)
                }
            }
    }

    private fun showDownloadConfirmation(lang: String, cb: CheckBox, statusLabel: TextView) {
        val displayName = getLanguageDisplayName(lang)
        val view = TextView(this).apply {
            text = "Enabling $displayName requires downloading the language model (~15 MB).\n\n" +
                    "This is a one-time download. After that it works fully offline.\n\n" +
                    "Download now?"
            textSize = 13f
            setTextColor(gray)
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this, R.style.DarkDialog)
            .setTitle("Download required")
            .setView(view)
            .setPositiveButton("Download") { _, _ ->
                startModelDownload(lang, cb, statusLabel)
            }
            .setNegativeButton("Cancel") { _, _ ->
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = false
                @Suppress("UNCHECKED_CAST")
                cb.setOnCheckedChangeListener(cb.tag as? android.widget.CompoundButton.OnCheckedChangeListener)
                statusLabel.text = ""
            }
            .setCancelable(false)
            .show()
    }

    private fun startModelDownload(lang: String, cb: CheckBox, statusLabel: TextView) {
        cb.isEnabled = false
        statusLabel.text = "Downloading…"

        val mlKitCode = TranslateLanguage.fromLanguageTag(lang) ?: return
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(mlKitCode)
            .setTargetLanguage(TranslateLanguage.ENGLISH)
            .build()
        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build() // allow any network

        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.close()
                runOnUiThread {
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = true
                    cb.isEnabled = true
                    @Suppress("UNCHECKED_CAST")
                    cb.setOnCheckedChangeListener(cb.tag as? android.widget.CompoundButton.OnCheckedChangeListener)
                    languageSelection[lang] = true
                    saveLanguageSelection(this, languageSelection)
                    statusLabel.text = "Enabled"
                }
            }
            .addOnFailureListener {
                translator.close()
                runOnUiThread {
                    cb.setOnCheckedChangeListener(null)
                    cb.isChecked = false
                    cb.isEnabled = true
                    @Suppress("UNCHECKED_CAST")
                    cb.setOnCheckedChangeListener(cb.tag as? android.widget.CompoundButton.OnCheckedChangeListener)
                    statusLabel.text = ""
                    val view = TextView(this).apply {
                        text = "Could not download the ${getLanguageDisplayName(lang)} language model.\n\n" +
                                "Please check your internet connection and try again."
                        textSize = 13f
                        setTextColor(gray)
                        setPadding(48, 24, 48, 8)
                    }
                    AlertDialog.Builder(this, R.style.DarkDialog)
                        .setTitle("Download failed")
                        .setView(view)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
    }

    private fun makeSectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 10f
        setTextColor(amber)
        letterSpacing = 0.15f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 14 }
    }

    private fun makeSectionBody(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(gray)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 28 }
    }

    private fun makeDivider() = View(this).apply {
        setBackgroundColor(Color.parseColor("#1A1A1A"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { topMargin = 8; bottomMargin = 32 }
    }
}