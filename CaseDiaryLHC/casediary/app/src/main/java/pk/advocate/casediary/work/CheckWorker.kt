package pk.advocate.casediary.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.util.Dates
import pk.advocate.casediary.util.Notifications
import pk.advocate.casediary.util.Prefs

/**
 * The scheduled cause-list check. Runs twice a day, notifies about anything new,
 * then books its own next run so the chain keeps going.
 */
class CheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val slot = inputData.getString(KEY_SLOT) ?: SLOT_MORNING

        try {
            if (prefs.autoCheckEnabled) {
                val result = CauseListScraper(applicationContext).runAll()

                prefs.lastCheckAt = System.currentTimeMillis()
                prefs.lastCheckResult = buildString {
                    append("${result.newHits} new, ${result.totalHits} matched, ")
                    append("${result.rowsScanned} rows")
                    if (result.errors.isNotEmpty()) {
                        append(" — ${result.errors.size} source(s) failed: ")
                        append(result.errors.joinToString("; ").take(220))
                    }
                }

                if (result.newHits > 0) {
                    Notifications.notifyFixtures(applicationContext, result.newHits, result.lines)
                }
            }

            // The evening run doubles as a reminder for hearings you entered yourself.
            if (slot == SLOT_EVENING && prefs.hearingRemindersEnabled) {
                sendHearingReminders()
            }

            Result.success()
        } catch (e: Exception) {
            prefs.lastCheckAt = System.currentTimeMillis()
            prefs.lastCheckResult = "Failed: ${e.message ?: e.javaClass.simpleName}"
            Result.retry()
        } finally {
            // Always book the next run, even if this one failed.
            Scheduler.scheduleSlot(applicationContext, slot)
        }
    }

    private fun sendHearingReminders() {
        val db = Db.get(applicationContext)
        val tomorrow = Dates.plusDays(Dates.todayStart(), 1)
        val cases = db.casesBetween(Dates.startOfDay(tomorrow), Dates.endOfDay(tomorrow))
        if (cases.isEmpty()) return

        val lines = cases.map { c ->
            val ref = c.caseRef().ifBlank { c.title() }
            val where = if (c.court.isBlank()) "" else " · ${c.court}"
            "$ref — ${c.title()}$where"
        }
        Notifications.notifyHearingReminders(applicationContext, lines)
    }

    companion object {
        const val KEY_SLOT = "slot"
        const val SLOT_MORNING = "morning"
        const val SLOT_EVENING = "evening"

        /** Also used by the "Check now" button, off the main thread. */
        fun runOnce(context: Context): CauseListScraper.Result {
            val prefs = Prefs(context)
            val result = CauseListScraper(context).runAll()
            prefs.lastCheckAt = System.currentTimeMillis()
            prefs.lastCheckResult =
                "${result.newHits} new, ${result.totalHits} matched, ${result.rowsScanned} rows" +
                    if (result.errors.isEmpty()) "" else " — ${result.errors.joinToString("; ").take(220)}"
            if (result.newHits > 0) {
                Notifications.notifyFixtures(context, result.newHits, result.lines)
            }
            return result
        }
    }
}
