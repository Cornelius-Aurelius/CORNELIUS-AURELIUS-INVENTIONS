package com.cornelius.wordchecker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "WordChecker"
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "WordChecker v0.3"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "A smarter everyday keyboard: duplicate protection, spelling suggestions, emojis, fast delete and cursor control."
            textSize = 17f
            setPadding(0, dp(8), 0, dp(18))
        })

        root.addView(Button(this).apply {
            text = "1 · Enable WordChecker keyboard"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }, fullWidth())
        root.addView(Button(this).apply {
            text = "2 · Choose WordChecker"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "Try v0.3"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(6))
        })
        root.addView(EditText(this).apply {
            hint = "Try: I think think this is petfecf"
            minLines = 5
            gravity = Gravity.TOP
            textSize = 18f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))

        root.addView(Button(this).apply {
            text = "WordChecker settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "Tips: hold ⌫ to delete faster; drag left/right on the space bar to move the cursor; tap 😀 for emojis; long-press Q–P for 1–0."
            textSize = 14f
            setPadding(0, dp(18), 0, dp(24))
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
