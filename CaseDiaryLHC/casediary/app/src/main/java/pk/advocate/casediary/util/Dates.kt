package pk.advocate.casediary.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Dates {

    private val display = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
    private val displayWithDay = SimpleDateFormat("EEE, dd MMM yyyy", Locale.ENGLISH)
    private val stamp = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH)
    private val iso = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

    fun fmt(millis: Long): String = if (millis <= 0L) "—" else display.format(Date(millis))

    fun fmtWithDay(millis: Long): String =
        if (millis <= 0L) "—" else displayWithDay.format(Date(millis))

    fun fmtStamp(millis: Long): String = if (millis <= 0L) "never" else stamp.format(Date(millis))

    fun isoDate(millis: Long): String = iso.format(Date(millis))

    fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun endOfDay(millis: Long): Long = startOfDay(millis) + 24L * 60 * 60 * 1000 - 1

    fun todayStart(): Long = startOfDay(System.currentTimeMillis())

    fun plusDays(millis: Long, days: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.add(Calendar.DAY_OF_YEAR, days)
        return c.timeInMillis
    }

    /** Millis of the next occurrence of [hour]:[minute] local time. */
    fun nextOccurrence(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    fun relativeDayLabel(millis: Long): String {
        if (millis <= 0L) return "No date set"
        val today = todayStart()
        val target = startOfDay(millis)
        val diffDays = ((target - today) / (24L * 60 * 60 * 1000)).toInt()
        return when {
            diffDays == 0 -> "Today"
            diffDays == 1 -> "Tomorrow"
            diffDays == -1 -> "Yesterday"
            diffDays in 2..6 -> "In $diffDays days"
            diffDays < -1 -> "${-diffDays} days ago"
            else -> fmt(millis)
        }
    }

    fun two(n: Int): String = if (n < 10) "0$n" else "$n"
}
