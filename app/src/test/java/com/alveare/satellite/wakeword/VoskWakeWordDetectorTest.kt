package com.alveare.satellite.wakeword

import org.junit.Assert.*
import org.junit.Test

class VoskWakeWordDetectorTest {

    @Test
    fun testWakePhraseMatchingPositive() {
        val target = "ehi alveare"
        
        val fullMatchJson = """{"text": "ehi alveare"}"""
        assertTrue(VoskWakeWordDetector.matchesWakePhrase(fullMatchJson, target))

        val partialMatchJson = """{"partial": "ehi alveare"}"""
        assertTrue(VoskWakeWordDetector.matchesWakePhrase(partialMatchJson, target))

        val withPunctuation = """{"text": "ehi alveare."}"""
        assertTrue(VoskWakeWordDetector.matchesWakePhrase(withPunctuation, target))

        val withLeadingText = """{"text": "[unk] ehi alveare"}"""
        assertTrue(VoskWakeWordDetector.matchesWakePhrase(withLeadingText, target))
    }

    @Test
    fun testWakePhraseMatchingNegative() {
        val target = "ehi alveare"

        val unknownOnly = """{"text": "[unk]"}"""
        assertFalse(VoskWakeWordDetector.matchesWakePhrase(unknownOnly, target))

        val partialSingleWord = """{"partial": "ehi"}"""
        assertFalse(VoskWakeWordDetector.matchesWakePhrase(partialSingleWord, target))

        val differentWord = """{"text": "ciao a tutti"}"""
        assertFalse(VoskWakeWordDetector.matchesWakePhrase(differentWord, target))

        val emptyJson = "{}"
        assertFalse(VoskWakeWordDetector.matchesWakePhrase(emptyJson, target))

        val invalidJson = "not json"
        assertFalse(VoskWakeWordDetector.matchesWakePhrase(invalidJson, target))
    }

    @Test
    fun testGrammarJsonFormat() {
        val grammar = VoskWakeWordDetector.buildGrammarJson("ehi alveare")
        assertEquals("[\"ehi alveare\", \"[unk]\"]", grammar)
    }
}
