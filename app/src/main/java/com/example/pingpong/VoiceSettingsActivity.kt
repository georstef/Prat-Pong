package com.example.pingpong

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

class VoiceSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS = "pingpong_prefs"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_VOICE_LANGUAGES = "voice_languages"

        // English is always the only enabled language.
        fun getLockedLanguages(): Set<String> = setOf("en")

        // Kept for future use if multi-language support is re-introduced.
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
            langs.remove("en")
            return langs
        }

        // Always returns English only.
        fun loadLanguageSelection(context: Context): Map<String, Boolean> {
            return mapOf("en" to true)
        }

        fun saveLanguageSelection(context: Context, selection: Map<String, Boolean>) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val encoded = selection.entries.joinToString(",") { "${it.key}:${it.value}" }
            prefs.edit().putString(KEY_VOICE_LANGUAGES, encoded).apply()
        }

        // Always returns just English.
        fun getEnabledLanguages(context: Context): List<String> = listOf("en")

        fun getLanguageDisplayName(code: String): String {
            return try {
                Locale(code).getDisplayLanguage(Locale.ENGLISH).replaceFirstChar { it.uppercase() }
            } catch (e: Exception) {
                code.uppercase()
            }
        }

        // Kept for future use.
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
            translator.translate("test")
                .addOnSuccessListener { onResult(true) }
                .addOnFailureListener { onResult(false) }
                .also { translator.close() }
        }
    }

    private val amber = Color.parseColor("#FF8F00")
    private val gray = Color.parseColor("#AAAAAA")
    private val dimGray = Color.parseColor("#444444")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
        }

        // Title
        container.addView(TextView(this).apply {
            text = "VOICE INSTRUCTIONS"
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
        // English row is shown locked, purely for reference.
        container.addView(makeSectionTitle("LANGUAGE"))
        container.addView(makeSectionBody("Voice commands are in English."))
        container.addView(makeEnglishRow())
        container.addView(makeDivider())

        // --- VOICE COMMANDS SECTION ---
        container.addView(makeSectionTitle("VOICE COMMANDS"))
        container.addView(makeSectionBody(
            "Add a point:\n" +
                    "  \"Point left\"  /  \"Point right\"  /  \"Point [name]\"\n\n" +
                    "Remove a point:\n" +
                    "  \"Minus left\"  /  \"Minus right\"  /  \"Minus [name]\"\n" +
                    "  \"Remove left\"  /  \"Remove right\"  /  \"Remove [name]\"\n" +
                    "  \"Undo left\"  /  \"Undo right\"  /  \"Undo [name]\"\n\n" +
                    "Left and right refer to the screen position of the player."
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
            "• Speak clearly and at a normal pace.\n" +
                    "• Short player names work best.\n" +
                    "• The mic listens continuously — no need to tap anything."
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

    /** English row shown as permanently locked — for future reference only. */
    private fun makeEnglishRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12; topMargin = 4 }
        }

        val cb = CheckBox(this).apply {
            isChecked = true
            isEnabled = false
            androidx.core.widget.CompoundButtonCompat.setButtonTintList(
                this,
                android.content.res.ColorStateList.valueOf(dimGray)
            )
        }

        val labelCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        }

        labelCol.addView(TextView(this).apply {
            text = "English  🔒"
            textSize = 15f
            setTextColor(dimGray)
        })

        row.addView(cb)
        row.addView(labelCol)
        return row
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