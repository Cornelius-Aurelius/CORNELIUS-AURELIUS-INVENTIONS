package com.cornelius.wordchecker.core

import java.util.Locale

data class SpellTarget(
    val word: String,
    val trailingText: String,
    val tailLength: Int,
)

class SpellTextEngine {
    private val wordRegex = Regex("""\p{L}+(?:['’]\p{L}+)*""")
    private val common = mapOf(
        "teh" to listOf("the"), "realy" to listOf("really"), "recieve" to listOf("receive"),
        "definately" to listOf("definitely"), "seperate" to listOf("separate"), "occured" to listOf("occurred"),
        "untill" to listOf("until"), "wich" to listOf("which"), "becuase" to listOf("because"),
        "thier" to listOf("their"), "freind" to listOf("friend"), "wierd" to listOf("weird"),
        "alot" to listOf("a lot"), "adress" to listOf("address"), "tomorow" to listOf("tomorrow"),
        "begining" to listOf("beginning"), "goverment" to listOf("government"), "enviroment" to listOf("environment"),
        "neccessary" to listOf("necessary"), "arguement" to listOf("argument"), "accomodate" to listOf("accommodate"),
        "comming" to listOf("coming"), "writting" to listOf("writing"), "typying" to listOf("typing"),
        "spellling" to listOf("spelling"), "petfecf" to listOf("perfect"), "dont" to listOf("don't"),
        "cant" to listOf("can't"), "wont" to listOf("won't"), "doesnt" to listOf("doesn't"),
        "isnt" to listOf("isn't"), "wasnt" to listOf("wasn't"), "couldnt" to listOf("couldn't"),
        "shouldnt" to listOf("shouldn't"), "wouldnt" to listOf("wouldn't"), "ive" to listOf("I've"),
        "im" to listOf("I'm"), "ill" to listOf("I'll"), "youre" to listOf("you're"),
        "theyre" to listOf("they're"), "weve" to listOf("we've"), "thats" to listOf("that's"),
    )

    fun normalize(word: String): String = word.replace('’', '\'').lowercase(Locale.ROOT)
    fun fallbackSuggestions(word: String): List<String> = common[normalize(word)].orEmpty()

    fun targetFromPrefix(prefix: String): SpellTarget? {
        val matches = wordRegex.findAll(prefix).toList()
        val last = matches.lastOrNull() ?: return null
        val wordEnd = last.range.last + 1
        val trailing = prefix.substring(wordEnd)
        if (trailing.any { it.isLetterOrDigit() || it == '\n' || it == '\r' }) return null
        if (trailing.length > 3) return null
        return SpellTarget(last.value, trailing, prefix.length - last.range.first)
    }

    fun replacementText(target: SpellTarget, suggestion: String): String = suggestion + target.trailingText
}
