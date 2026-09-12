package com.cornelius.wordchecker.core

import java.util.Locale

enum class FieldContext {
    NORMAL,
    PASSWORD,
    SECURE,
    CODE,
    URL,
    EMAIL_ADDRESS,
    PHONE,
    NUMERIC
}

data class CorrectionDecision(
    val shouldCorrect: Boolean,
    val reason: String,
    val confidence: Double = 1.0,
    val word: String? = null,
    /** Gap before duplicate + the duplicate word itself. */
    val deletedText: String = "",
    /** Text typed after the duplicate, normally the completion delimiter. */
    val trailingText: String = "",
    /** Number of characters from the cursor to replace to apply this correction. */
    val tailLength: Int = 0,
    val correctedPrefix: String? = null,
)

/**
 * High-precision, deterministic duplicate-word detector.
 *
 * Design rule: a missed duplicate is preferable to deleting intended text.
 * This class has no Android dependency so the core can be JVM tested directly.
 */
class SafeDeleteEngine {
    private val wordRegex = Regex("""\p{L}+(?:['’]\p{L}+)*""")
    private val plainGapRegex = Regex("""[ \t]+""")
    private val whClauseRegex = Regex(
        """\b(?:what|who|where|when|why|how)\b[^.!?;\n]{0,80}$""",
        RegexOption.IGNORE_CASE,
    )

    private val grammatical = setOf("had", "that")
    private val contextual = setOf("is", "was", "were", "do", "does", "did")
    private val emphasis = setOf(
        "very", "really", "so", "no", "yes", "yeah", "yep", "nah", "please",
        "bye", "night", "go", "more", "again", "never", "ever", "far", "way",
        "much", "too", "ha", "haha", "blah",
    )
    private val protectedWords = grammatical + emphasis

    fun normalizeWord(word: String): String =
        word.replace('’', '\'').lowercase(Locale.ROOT)

    private fun normalize(word: String): String = normalizeWord(word)

    private fun sameCaseShape(a: String, b: String): Boolean =
        a == b || (a == a.lowercase(Locale.ROOT) && b == b.lowercase(Locale.ROOT)) ||
            (a == a.uppercase(Locale.ROOT) && b == b.uppercase(Locale.ROOT))

    fun lastWord(prefix: String): String? =
        wordRegex.findAll(prefix).lastOrNull()?.value?.let(::normalize)

    fun decide(
        prefix: String,
        fieldContext: FieldContext = FieldContext.NORMAL,
        enabled: Boolean = true,
        forceWordComplete: Boolean = false,
    ): CorrectionDecision {
        if (!enabled) return CorrectionDecision(false, "autocorrect_disabled")
        if (fieldContext != FieldContext.NORMAL) {
            return CorrectionDecision(false, "protected_input_context")
        }

        val words = wordRegex.findAll(prefix).toList()
        if (words.size < 2) return CorrectionDecision(false, "fewer_than_two_words")

        val first = words[words.size - 2]
        val second = words[words.size - 1]
        val firstWord = first.value
        val secondWord = second.value
        val n1 = normalize(firstWord)
        val n2 = normalize(secondWord)

        if (n1 != n2) return CorrectionDecision(false, "not_a_duplicate")

        val secondEndExclusive = second.range.last + 1
        if (!forceWordComplete && secondEndExclusive >= prefix.length) {
            return CorrectionDecision(false, "second_word_not_completed", word = secondWord)
        }

        val firstEndExclusive = first.range.last + 1
        val gap = prefix.substring(firstEndExclusive, second.range.first)
        if (!plainGapRegex.matches(gap)) {
            return CorrectionDecision(false, "punctuated_or_linebreak_separation", word = secondWord)
        }

        val trailing = prefix.substring(secondEndExclusive)
        if ('\n' in gap || '\r' in gap || '\n' in trailing || '\r' in trailing) {
            return CorrectionDecision(false, "linebreak_boundary", word = secondWord)
        }

        if (!sameCaseShape(firstWord, secondWord)) {
            return CorrectionDecision(false, "case_transition_protected", word = secondWord)
        }

        if (n2 in protectedWords) {
            return CorrectionDecision(
                false,
                "known_intentional_or_grammatical_repeat",
                confidence = 0.55,
                word = secondWord,
            )
        }

        val beforeFirst = prefix.substring(0, first.range.first)
        if (n2 in contextual && whClauseRegex.containsMatchIn(beforeFirst)) {
            return CorrectionDecision(
                false,
                "contextual_grammatical_repeat",
                confidence = 0.60,
                word = secondWord,
            )
        }

        val deleted = prefix.substring(firstEndExclusive, secondEndExclusive)
        val tailLength = prefix.length - firstEndExclusive
        val corrected = prefix.substring(0, firstEndExclusive) + trailing
        return CorrectionDecision(
            shouldCorrect = true,
            reason = "high_confidence_adjacent_duplicate",
            confidence = 0.995,
            word = secondWord,
            deletedText = deleted,
            trailingText = trailing,
            tailLength = tailLength,
            correctedPrefix = corrected,
        )
    }
}
