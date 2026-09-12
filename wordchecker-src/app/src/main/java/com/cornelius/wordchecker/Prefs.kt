package com.cornelius.wordchecker

import android.content.Context

object Prefs {
    private const val FILE = "wordchecker_prefs"
    const val ENABLED = "enabled"
    const val HAPTIC = "haptic"
    const val SHOW_COUNT = "show_count"
    const val CORRECTION_COUNT = "correction_count"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
