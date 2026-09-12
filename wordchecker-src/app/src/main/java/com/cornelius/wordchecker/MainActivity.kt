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
import android.widget.Space
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "WordChecker"

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(TextView(this).apply {
            text = "WordChecker"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "A privacy-first keyboard that removes accidental repeated words while you type."
            textSize = 17f
            setPadding(0, dp(8), 0, dp(20))
        })

        root.addView(step("1", "Enable WordChecker Keyboard in Android's keyboard settings."))
        root.addView(step("2", "Choose WordChecker Keyboard as your current keyboard."))
        root.addView(step("3", "Try typing:  I think think we should go"))

        root.addView(Button(this).apply {
            text = "1 · Enable keyboard"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }, fullWidth())

        root.addView(Button(this).apply {
            text = "2 · Choose WordChecker"
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        }, fullWidth())

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(16)))
        root.addView(TextView(this).apply {
            text = "Live test field"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(EditText(this).apply {
            hint = "Type ‘hello hello there’"
            minLines = 4
            gravity = Gravity.TOP
            textSize = 18f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        root.addView(Button(this).apply {
            text = "WordChecker settings"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }, fullWidth())

        root.addView(TextView(this).apply {
            text = "Prototype note: WordChecker only reads a short text window around the cursor when a word is completed. It has no network permission and stores no message content."
            textSize = 13f
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(root)
    }

    private fun step(number: String, text: String): TextView = TextView(this).apply {
        this.text = "$number.  $text"
        textSize = 15f
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
