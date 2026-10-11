package dev.glasslauncher.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class AerialClockTest {
    private fun at(h: Int, m: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        set(2026, Calendar.OCTOBER, 10, h, m, 0); set(Calendar.MILLISECOND, 0)
    }.time

    private fun parts(h: Int, m: Int, is24: Boolean): Pair<String, String?> {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try { return AerialClock.parts(at(h, m), is24, Locale.US) } finally { TimeZone.setDefault(old) }
    }

    @Test fun twentyFourHourModeHasNoMarker() {
        assertEquals("21:41" to null, parts(21, 41, true))
        assertNull(parts(9, 5, true).second)
    }

    @Test fun twelveHourModeSplitsTheMarker() {
        assertEquals("9:41" to "PM", parts(21, 41, false))
        assertEquals("9:05" to "AM", parts(9, 5, false))
        assertEquals("12:00" to "PM", parts(12, 0, false))
        assertEquals("12:30" to "AM", parts(0, 30, false))
    }
}
