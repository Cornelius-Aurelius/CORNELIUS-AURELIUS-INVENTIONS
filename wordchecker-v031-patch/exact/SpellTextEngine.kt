package com.cornelius.wordchecker.core

import java.util.Locale

/** Pure text helper for the Android spelling UI. */
data class SpellTarget(
    val word: String,
    val trailingText: String,
    val tailLength: Int,
)

class SpellTextEngine {
    private val wordRegex = Regex("""\p{L}+(?:['’]\p{L}+)*""")

    /**
     * High-confidence offline fallbacks. Android's installed spell checker supplies the
     * broad dictionary; this small table makes common mistakes useful even when a device
     * has no spell-check service enabled.
     */
    private val common = mapOf(
        "teh" to listOf("the"),
        "adn" to listOf("and"),
        "hte" to listOf("the"),
        "taht" to listOf("that"),
        "thta" to listOf("that"),
        "realy" to listOf("really"),
        "realyl" to listOf("really"),
        "recieve" to listOf("receive"),
        "receieve" to listOf("receive"),
        "definately" to listOf("definitely"),
        "definetly" to listOf("definitely"),
        "definetely" to listOf("definitely"),
        "seperate" to listOf("separate"),
        "seperately" to listOf("separately"),
        "occured" to listOf("occurred"),
        "occurence" to listOf("occurrence"),
        "untill" to listOf("until"),
        "wich" to listOf("which"),
        "wihch" to listOf("which"),
        "becuase" to listOf("because"),
        "becasue" to listOf("because"),
        "beacuse" to listOf("because"),
        "thier" to listOf("their"),
        "theri" to listOf("their"),
        "freind" to listOf("friend"),
        "firend" to listOf("friend"),
        "wierd" to listOf("weird"),
        "alot" to listOf("a lot"),
        "adress" to listOf("address"),
        "addres" to listOf("address"),
        "tomorow" to listOf("tomorrow"),
        "tommorow" to listOf("tomorrow"),
        "begining" to listOf("beginning"),
        "goverment" to listOf("government"),
        "enviroment" to listOf("environment"),
        "neccessary" to listOf("necessary"),
        "neccesary" to listOf("necessary"),
        "arguement" to listOf("argument"),
        "accomodate" to listOf("accommodate"),
        "acommodate" to listOf("accommodate"),
        "comming" to listOf("coming"),
        "writting" to listOf("writing"),
        "typying" to listOf("typing"),
        "typeing" to listOf("typing"),
        "spellling" to listOf("spelling"),
        "speling" to listOf("spelling"),
        "petfecf" to listOf("perfect"),
        "perfecf" to listOf("perfect"),
        "perfcet" to listOf("perfect"),
        "langauge" to listOf("language"),
        "lanuage" to listOf("language"),
        "keyboardd" to listOf("keyboard"),
        "keybaord" to listOf("keyboard"),
        "mesage" to listOf("message"),
        "messsage" to listOf("message"),
        "quikc" to listOf("quick"),
        "qiuck" to listOf("quick"),
        "beter" to listOf("better"),
        "bettter" to listOf("better"),
        "peopel" to listOf("people"),
        "poeple" to listOf("people"),
        "woudl" to listOf("would"),
        "shoudl" to listOf("should"),
        "coudl" to listOf("could"),
        "whcih" to listOf("which"),
        "htey" to listOf("they"),
        "thye" to listOf("they"),
        "youu" to listOf("you"),
        "yuor" to listOf("your"),
        "yuo" to listOf("you"),
        "jsut" to listOf("just"),
        "juts" to listOf("just"),
        "knwo" to listOf("know"),
        "knoe" to listOf("know"),
        "waht" to listOf("what"),
        "whats" to listOf("what's"),
        "whta" to listOf("what"),
        "wiht" to listOf("with"),
        "withh" to listOf("with"),
        "somthing" to listOf("something"),
        "someting" to listOf("something"),
        "everytime" to listOf("every time"),
        "actualy" to listOf("actually"),
        "actaully" to listOf("actually"),
        "basicly" to listOf("basically"),
        "probaly" to listOf("probably"),
        "probablyy" to listOf("probably"),
        "remeber" to listOf("remember"),
        "rember" to listOf("remember"),
        "diffrent" to listOf("different"),
        "diferent" to listOf("different"),
        "immediatly" to listOf("immediately"),
        "instantlyy" to listOf("instantly"),
        "succesful" to listOf("successful"),
        "sucessful" to listOf("successful"),
        "availble" to listOf("available"),
        "avaiable" to listOf("available"),
        "calender" to listOf("calendar"),
        "definate" to listOf("definite"),
        "embarass" to listOf("embarrass"),
        "existance" to listOf("existence"),
        "finaly" to listOf("finally"),
        "foriegn" to listOf("foreign"),
        "fourty" to listOf("forty"),
        "guage" to listOf("gauge"),
        "happend" to listOf("happened"),
        "heigth" to listOf("height"),
        "independant" to listOf("independent"),
        "maintainance" to listOf("maintenance"),
        "millenium" to listOf("millennium"),
        "noticable" to listOf("noticeable"),
        "posession" to listOf("possession"),
        "prefered" to listOf("preferred"),
        "priviledge" to listOf("privilege"),
        "publically" to listOf("publicly"),
        "restaraunt" to listOf("restaurant"),
        "rythm" to listOf("rhythm"),
        "treshold" to listOf("threshold"),
        "truely" to listOf("truly"),
        "usefull" to listOf("useful"),
        "usualy" to listOf("usually"),
        "vaccum" to listOf("vacuum"),
        "untilll" to listOf("until"),
        "dont" to listOf("don't"),
        "cant" to listOf("can't"),
        "wont" to listOf("won't"),
        "doesnt" to listOf("doesn't"),
        "isnt" to listOf("isn't"),
        "wasnt" to listOf("wasn't"),
        "couldnt" to listOf("couldn't"),
        "shouldnt" to listOf("shouldn't"),
        "wouldnt" to listOf("wouldn't"),
        "ive" to listOf("I've"),
        "im" to listOf("I'm"),
        "youre" to listOf("you're"),
        "theyre" to listOf("they're"),
        "weve" to listOf("we've"),
        "thats" to listOf("that's"),
    )

    fun normalize(word: String): String =
        word.replace('’', '\'').lowercase(Locale.ROOT)

    fun fallbackSuggestions(word: String): List<String> = common[normalize(word)].orEmpty()

    fun targetFromPrefix(prefix: String): SpellTarget? {
        val matches = wordRegex.findAll(prefix).toList()
        val last = matches.lastOrNull() ?: return null
        val wordEnd = last.range.last + 1
        val trailing = prefix.substring(wordEnd)
        // Only spell-check the word at the cursor or the immediately completed word.
        if (trailing.any { it.isLetterOrDigit() || it == '\n' || it == '\r' }) return null
        if (trailing.length > 3) return null
        return SpellTarget(
            word = last.value,
            trailingText = trailing,
            tailLength = prefix.length - last.range.first,
        )
    }

    fun replacementText(target: SpellTarget, suggestion: String): String =
        suggestion + target.trailingText
}
