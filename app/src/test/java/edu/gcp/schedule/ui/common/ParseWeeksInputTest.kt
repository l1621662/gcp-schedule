package edu.gcp.schedule.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseWeeksInputTest {
    @Test
    fun parseRange() {
        assertEquals(setOf(1, 2, 3, 4, 5), parseWeeksInput("1-5"))
    }

    @Test
    fun parseMixed() {
        assertEquals(setOf(1, 2, 3, 5, 8), parseWeeksInput("1-3,5,8"))
    }

    @Test
    fun parseOddEvenHelpers() {
        assertTrue(oddWeeks().startsWith("1,"))
        assertTrue(evenWeeks().startsWith("2,"))
    }
}
