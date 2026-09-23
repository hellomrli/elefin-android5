package com.flex.elefin.screens

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EndsAtTest {
    private val ticksPerMinute = 60L * 10_000_000
    private lateinit var savedZone: TimeZone
    private lateinit var savedLocale: Locale
    private val now = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 23, 21, 0)
    }.timeInMillis

    @Before
    fun pinClock() {
        savedZone = TimeZone.getDefault()
        savedLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreClock() {
        TimeZone.setDefault(savedZone)
        Locale.setDefault(savedLocale)
    }

    @Test
    fun fullRuntimeFromNow() {
        assertEquals("22:45", formatEndsAt(105 * ticksPerMinute, null, use24Hour = true, nowMillis = now))
        assertEquals("10:45 PM", formatEndsAt(105 * ticksPerMinute, null, use24Hour = false, nowMillis = now))
    }

    @Test
    fun resumePositionShortensRemainingTime() {
        assertEquals("21:30", formatEndsAt(90 * ticksPerMinute, 60 * ticksPerMinute, use24Hour = true, nowMillis = now))
    }

    @Test
    fun unknownOrFinishedRuntimeShowsNothing() {
        assertEquals("", formatEndsAt(null, null, use24Hour = true, nowMillis = now))
        assertEquals("", formatEndsAt(0, null, use24Hour = true, nowMillis = now))
        assertEquals("", formatEndsAt(30 * ticksPerMinute, 30 * ticksPerMinute, use24Hour = true, nowMillis = now))
    }
}
