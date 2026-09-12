package com.cornelius.wordchecker.core

import org.junit.Assert.*
import org.junit.Test

class SpellTextEngineTest {
    private val engine = SpellTextEngine()

    @Test fun targetWhileTyping() {
        val t = engine.targetFromPrefix("I am typying")!!
        assertEquals("typying", t.word)
        assertEquals("", t.trailingText)
        assertEquals(7, t.tailLength)
    }

    @Test fun targetAfterSpace() {
        val t = engine.targetFromPrefix("I am typying ")!!
        assertEquals("typying", t.word)
        assertEquals(" ", t.trailingText)
        assertEquals(8, t.tailLength)
    }

    @Test fun fallbackCommonMistake() {
        assertEquals(listOf("typing"), engine.fallbackSuggestions("typying"))
        assertEquals(listOf("perfect"), engine.fallbackSuggestions("petfecf"))
    }

    @Test fun ignoresOldWord() {
        assertNull(engine.targetFromPrefix("hello world    "))
    }
}
