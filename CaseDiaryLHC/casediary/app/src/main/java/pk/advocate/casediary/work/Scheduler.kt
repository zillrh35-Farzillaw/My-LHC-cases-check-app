package pk.advocate.casediary.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import pk.advocate.casediary.util.Dates
import pk.advocate.casediary.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * Two self-renewing one-shot jobs rather than a periodic job, because
 * PeriodicWorkRequest cannot be pinned to a wall-clock time and the whole point
 * here is "check after the evening list is published, and again before court".
 */
object Scheduler {

    private const val WORK_MORNING = "causelist_morning"
    private const val WORK_EVENING = "causelist_evening"

    fun rescheduleAll(context: Context) {
        scheduleSlot(context, CheckWorker.SLOT_MORNING)
        scheduleSlot(context, CheckWorker.SLOT_EVENING)
    }

    fun scheduleSlot(context: Context, slot: String) {
        val prefs = Prefs(context)
        val name = if (slot == CheckWorker.SLOT_EVENING) WORK_EVENING else WORK_MORNING

        if (!prefs.autoCheckEnabled && !prefs.hearingRemindersEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork(name)
            return
        }

        val target = if (slot == CheckWorker.SLOT_EVENING) {
            Dates.nextOccurrence(prefs.eveningHour, prefs.eveningMinute)
        } else {
            Dates.nextOccurrence(prefs.morningHour, prefs.morningMinute)
        }
        val delay = (target - System.currentTimeMillis()).coerceAtLeast(60_000L)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<CheckWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(CheckWorker.KEY_SLOT, slot).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_MORNING)
        wm.cancelUniqueWork(WORK_EVENING)
    }

    const val TAG = "causelist"
}
