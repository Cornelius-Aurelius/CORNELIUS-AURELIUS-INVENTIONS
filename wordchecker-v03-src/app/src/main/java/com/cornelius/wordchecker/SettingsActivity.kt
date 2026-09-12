package com.cornelius.wordchecker

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var countView: TextView
    private lateinit var learnedView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.prefs(this)
        val pad = dp(20)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        content.addView(TextView(this).apply {
            text = "WordChecker v0.3 settings"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        content.addView(section("WordChecker protection"))
        content.addView(toggle("Automatic duplicate removal", prefs.getBoolean(Prefs.ENABLED, true)) {
            prefs.edit().putBoolean(Prefs.ENABLED, it).apply()
        })
        content.addView(toggle("Learn intentional repeats when I tap Undo", prefs.getBoolean(Prefs.LEARN_FROM_UNDO, true)) {
            prefs.edit().putBoolean(Prefs.LEARN_FROM_UNDO, it).apply()
        })

        content.addView(section("Spelling & typing"))
        content.addView(toggle("Spell-check suggestions", prefs.getBoolean(Prefs.SPELLCHECK, true)) {
            prefs.edit().putBoolean(Prefs.SPELLCHECK, it).apply()
        })
        content.addView(toggle("Autocorrect very common misspellings", prefs.getBoolean(Prefs.AUTOCORRECT, false)) {
            prefs.edit().putBoolean(Prefs.AUTOCORRECT, it).apply()
        })
        content.addView(toggle("Double-space inserts a full stop", prefs.getBoolean(Prefs.DOUBLE_SPACE_PERIOD, true)) {
            prefs.edit().putBoolean(Prefs.DOUBLE_SPACE_PERIOD, it).apply()
        })
        content.addView(toggle("Auto-capitalise sentences", prefs.getBoolean(Prefs.AUTO_CAPITALIZE, true)) {
            prefs.edit().putBoolean(Prefs.AUTO_CAPITALIZE, it).apply()
        })

        content.addView(section("Keyboard feedback"))
        content.addView(toggle("Haptic key feedback", prefs.getBoolean(Prefs.HAPTIC, true)) {
            prefs.edit().putBoolean(Prefs.HAPTIC, it).apply()
        })
        content.addView(toggle("Show correction count", prefs.getBoolean(Prefs.SHOW_COUNT, true)) {
            prefs.edit().putBoolean(Prefs.SHOW_COUNT, it).apply()
        })

        countView = TextView(this).apply { textSize = 16f; setPadding(0, dp(18), 0, dp(8)) }
        content.addView(countView)
        learnedView = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        content.addView(learnedView)
        updateStats()

        content.addView(Button(this).apply {
            text = "Clear learned repeats"
            setOnClickListener {
                prefs.edit().remove(Prefs.LEARNED_REPEATS).apply()
                updateStats()
            }
        }, fullWidth())

        content.addView(Button(this).apply {
            text = "Reset correction count"
            setOnClickListener {
                prefs.edit().putLong(Prefs.CORRECTION_COUNT, 0L).apply()
                updateStats()
            }
        }, fullWidth())

        content.addView(TextView(this).apply {
            text = "Privacy: duplicate checking, learned repeats, emoji recents and the built-in common-misspelling list stay on this device. Broad spelling suggestions are requested from Android's enabled system spell-check service. WordChecker itself declares no Internet permission and stores no message content."
            textSize = 13f
            setPadding(0, dp(24), 0, dp(24))
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun section(label: String) = TextView(this).apply {
        text = label
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(22), 0, dp(6))
    }

    @Suppress("DEPRECATION")
    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            textSize = 16f
            isChecked = checked
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun updateStats() {
        val prefs = Prefs.prefs(this)
        countView.text = "Automatic duplicate corrections: ${prefs.getLong(Prefs.CORRECTION_COUNT, 0L)}"
        val learned = prefs.getStringSet(Prefs.LEARNED_REPEATS, emptySet()).orEmpty().sorted()
        learnedView.text = if (learned.isEmpty()) "Learned repeats: none yet" else "Learned repeats: ${learned.joinToString(", ")}"
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
