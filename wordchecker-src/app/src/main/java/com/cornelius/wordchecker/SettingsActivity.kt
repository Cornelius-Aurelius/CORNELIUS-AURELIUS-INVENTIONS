package com.cornelius.wordchecker

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var countView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.prefs(this)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "WordChecker settings"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(toggle(
            "Automatic duplicate removal",
            prefs.getBoolean(Prefs.ENABLED, true),
        ) { prefs.edit().putBoolean(Prefs.ENABLED, it).apply() })

        root.addView(toggle(
            "Haptic confirmation",
            prefs.getBoolean(Prefs.HAPTIC, true),
        ) { prefs.edit().putBoolean(Prefs.HAPTIC, it).apply() })

        root.addView(toggle(
            "Show correction count on keyboard",
            prefs.getBoolean(Prefs.SHOW_COUNT, true),
        ) { prefs.edit().putBoolean(Prefs.SHOW_COUNT, it).apply() })

        countView = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(20), 0, dp(8))
        }
        root.addView(countView)
        updateCount()

        root.addView(Button(this).apply {
            text = "Reset correction count"
            setOnClickListener {
                prefs.edit().putLong(Prefs.CORRECTION_COUNT, 0L).apply()
                updateCount()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        root.addView(TextView(this).apply {
            text = "Privacy: the prototype uses on-device deterministic rules. It declares no internet permission and stores only settings plus a numeric correction count."
            textSize = 13f
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch =
        Switch(this).apply {
            text = label
            textSize = 16f
            isChecked = checked
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun updateCount() {
        val count = Prefs.prefs(this).getLong(Prefs.CORRECTION_COUNT, 0L)
        countView.text = "Automatic corrections: $count"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
