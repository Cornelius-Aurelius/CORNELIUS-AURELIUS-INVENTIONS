package com.cornelius.wordchecker

import android.content.Context

object Prefs {
    private const val FILE = "wordchecker_prefs"
    const val ENABLED = "enabled"
    const val HAPTIC = "haptic"
    const val SHOW_COUNT = "show_count"
    const val CORRECTION_COUNT = "correction_count"
    const val LEARN_FROM_UNDO = "learn_from_undo"
    const val LEARNED_REPEATS = "learned_repeats"
    const val SPELLCHECK = "spellcheck"
    const val AUTOCORRECT = "autocorrect"
    const val DOUBLE_SPACE_PERIOD = "double_space_period"
    const val AUTO_CAPITALIZE = "auto_capitalize"
    const val RECENT_EMOJIS = "recent_emojis"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
