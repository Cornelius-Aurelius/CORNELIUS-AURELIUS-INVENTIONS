package com.cornelius.wordchecker.core

import org.junit.Assert.*
import org.junit.Test

class SafeDeleteEngineTest {
    private val engine = SafeDeleteEngine()

    @Test fun basicDuplicate() {
        val d = engine.decide("I think think ")
        assertTrue(d.shouldCorrect)
        assertEquals("I think ", d.correctedPrefix)
        assertEquals(" think", d.deletedText)
        assertEquals(" ", d.trailingText)
    }

    @Test fun waitsUntilWordCompletion() {
        assertFalse(engine.decide("I think think").shouldCorrect)
        assertTrue(engine.decide("I think think", forceWordComplete = true).shouldCorrect)
    }

    @Test fun protectedGrammarAndEmphasis() {
        listOf(
            "I had had enough. ",
            "I know that that works. ",
            "What it was was unusual. ",
            "very very good ",
            "no no don't ",
        ).forEach { assertFalse("Should protect: $it", engine.decide(it).shouldCorrect) }
    }

    @Test fun punctuationAndCaseAreProtected() {
        assertFalse(engine.decide("hello, hello ").shouldCorrect)
        assertFalse(engine.decide("Will will ").shouldCorrect)
    }

    @Test fun protectedFieldsNeverRewrite() {
        FieldContext.entries.filter { it != FieldContext.NORMAL }.forEach {
            assertFalse(engine.decide("token token ", it).shouldCorrect)
        }
    }
}
