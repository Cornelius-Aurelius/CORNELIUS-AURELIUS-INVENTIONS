package com.cornelius.wordchecker.core

/** Pure helpers mirroring the cursor-relative edits used by the Android IME adapter. */
object CorrectionTextOps {
    fun apply(prefix: String, decision: CorrectionDecision): String {
        require(decision.shouldCorrect)
        require(decision.tailLength in 1..prefix.length)
        return prefix.dropLast(decision.tailLength) + decision.trailingText
    }

    fun undo(correctedPrefix: String, decision: CorrectionDecision): String {
        require(decision.shouldCorrect)
        require(correctedPrefix.endsWith(decision.trailingText))
        return correctedPrefix.dropLast(decision.trailingText.length) +
            decision.deletedText + decision.trailingText
    }

    fun restoreIntentionalTriple(
        currentPrefix: String,
        currentDecision: CorrectionDecision,
        previouslyDeletedText: String,
    ): String {
        require(currentDecision.shouldCorrect)
        return currentPrefix.dropLast(currentDecision.tailLength) +
            previouslyDeletedText + currentDecision.deletedText + currentDecision.trailingText
    }
}
