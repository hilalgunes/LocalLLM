package com.hilal.llm.test

import org.junit.Assert.assertEquals
import org.junit.Test

class TestFlowNormalizationTest {
    @Test
    fun acceptsYesWithWhitespaceAndPunctuation() {
        assertEquals("EVET", normalizeClassificationOutput("  EVET.\n"))
    }

    @Test
    fun usesOnlyFirstToken() {
        assertEquals("EVET", normalizeClassificationOutput("evet açıklama"))
        assertEquals("HAYIR", normalizeClassificationOutput("HAYIR, çünkü"))
    }

    @Test
    fun unexpectedOutputDoesNotBecomeYes() {
        assertEquals("BELKI", normalizeClassificationOutput("Belki"))
        assertEquals("", normalizeClassificationOutput("..."))
    }
}
