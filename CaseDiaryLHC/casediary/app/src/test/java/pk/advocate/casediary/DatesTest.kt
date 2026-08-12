package pk.advocate.casediary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.advocate.casediary.util.Dates
import java.util.Calendar

/** The twice-daily schedule and the evening reminder both rest on these. */
class DatesTest {

    @Test
    fun `nextOccurrence is always the next future instance of that clock time`() {
        for ((h, m) in listOf(7 to 30, 18 to 30, 0 to 0, 23 to 59)) {
            val t = Dates.nextOccurrence(h, m)
            val now = System.currentTimeMillis()
            val c = Calendar.getInstance().apply { timeInMillis = t }

            assertTrue("$h:$m must be in the future", t > now)
            assertTrue("$h:$m must be within 24h", t - now <= 24L * 60 * 60 * 1000 + 1000)
            assertEquals(h, c.get(Calendar.HOUR_OF_DAY))
            assertEquals(m, c.get(Calendar.MINUTE))
            assertEquals(0, c.get(Calendar.SECOND))
        }
    }

    @Test
    fun `day boundaries line up so no hearing falls between them`() {
        val today = Dates.todayStart()
        val c = Calendar.getInstance().apply { timeInMillis = today }
        assertEquals(0, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, c.get(Calendar.MILLISECOND))
        assertEquals(Dates.startOfDay(Dates.plusDays(today, 1)), Dates.endOfDay(today) + 1)
    }

    @Test
    fun `a hearing set for tomorrow morning is inside tomorrow's reminder window`() {
        val tomorrow = Dates.plusDays(Dates.todayStart(), 1)
        val at10 = tomorrow + 10L * 60 * 60 * 1000
        assertTrue(at10 >= Dates.startOfDay(tomorrow) && at10 <= Dates.endOfDay(tomorrow))
    }

    @Test
    fun `relative labels read the way a diary should`() {
        val today = Dates.todayStart()
        assertEquals("Today", Dates.relativeDayLabel(today + 3_600_000))
        assertEquals("Tomorrow", Dates.relativeDayLabel(Dates.plusDays(today, 1) + 3_600_000))
        assertEquals("Yesterday", Dates.relativeDayLabel(Dates.plusDays(today, -1) + 3_600_000))
        assertEquals("In 3 days", Dates.relativeDayLabel(Dates.plusDays(today, 3) + 3_600_000))
        assertEquals("5 days ago", Dates.relativeDayLabel(Dates.plusDays(today, -5) + 3_600_000))
        assertEquals("No date set", Dates.relativeDayLabel(0))
    }

    @Test
    fun `clock times are zero padded`() {
        assertEquals("07", Dates.two(7))
        assertEquals("30", Dates.two(30))
    }
}
