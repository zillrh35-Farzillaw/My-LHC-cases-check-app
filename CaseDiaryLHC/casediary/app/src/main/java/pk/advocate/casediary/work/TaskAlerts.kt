package pk.advocate.casediary.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.util.Notifications
import java.util.concurrent.TimeUnit

/**
 * Schedules a real, one-off alert for a [pk.advocate.casediary.db.LawTask]'s own
 * deadline — unlike the web app, which can only warn while a page happens to be
 * open, WorkManager fires this even if the app has been closed the whole time.
 */
object TaskAlerts {

    private fun workName(taskId: Long) = "task_deadline_$taskId"

    /** (Re)books the deadline alert for one task. A past deadline is not scheduled. */
    fun schedule(context: Context, taskId: Long, deadline: Long) {
        val delay = deadline - System.currentTimeMillis()
        if (delay <= 0L) {
            cancel(context, taskId)
            return
        }
        val request = OneTimeWorkRequestBuilder<TaskAlertWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(TaskAlertWorker.KEY_TASK_ID, taskId).build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(taskId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId))
    }

    /** Re-arms every open task's alert — called on boot as a safety net. */
    fun rescheduleAll(context: Context) {
        val db = Db.get(context)
        for (t in db.listTasks()) {
            if (!t.done && t.deadline > System.currentTimeMillis()) {
                schedule(context, t.id, t.deadline)
            }
        }
    }

    const val TAG = "task_alerts"
}

class TaskAlertWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, 0L)
        if (taskId == 0L) return Result.failure()

        val db = Db.get(applicationContext)
        val task = db.getTask(taskId) ?: return Result.success()
        if (!task.done) {
            Notifications.notifyTaskDue(applicationContext, task.id, task.title)
        }
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}
