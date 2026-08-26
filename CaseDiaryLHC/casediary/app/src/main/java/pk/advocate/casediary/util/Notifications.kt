package pk.advocate.casediary.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pk.advocate.casediary.R
import pk.advocate.casediary.ui.MainActivity

object Notifications {

    const val CH_FIXTURES = "fixtures"
    const val CH_REMINDERS = "reminders"
    const val CH_TASKS = "tasks"

    private const val ID_FIXTURES = 1001
    private const val ID_REMINDERS = 1002
    private const val ID_TASK_BASE = 2000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val fixtures = NotificationChannel(
            CH_FIXTURES,
            context.getString(R.string.ch_fixtures),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.ch_fixtures_desc)
            enableVibration(true)
        }

        val reminders = NotificationChannel(
            CH_REMINDERS,
            context.getString(R.string.ch_reminders),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.ch_reminders_desc)
        }

        val tasks = NotificationChannel(
            CH_TASKS,
            context.getString(R.string.ch_tasks),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.ch_tasks_desc)
            enableVibration(true)
        }

        nm.createNotificationChannel(fixtures)
        nm.createNotificationChannel(reminders)
        nm.createNotificationChannel(tasks)
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppIntent(context: Context, tab: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, tab)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, tab.hashCode(), intent, flags)
    }

    /** Fired when new rows matching the user's keywords show up in a cause list. */
    fun notifyFixtures(context: Context, count: Int, lines: List<String>) {
        if (!canNotify(context) || count <= 0) return

        val title = if (count == 1) {
            context.getString(R.string.notif_fixture_one)
        } else {
            context.getString(R.string.notif_fixture_many, count)
        }

        val style = NotificationCompat.InboxStyle()
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.setSummaryText("+${lines.size - 6} more")

        val n = NotificationCompat.Builder(context, CH_FIXTURES)
            .setSmallIcon(R.drawable.ic_gavel)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.TAB_DIARY))
            .build()

        safeNotify(context, ID_FIXTURES, n)
    }

    /** Fired the evening before a hearing you entered yourself. */
    fun notifyHearingReminders(context: Context, lines: List<String>) {
        if (!canNotify(context) || lines.isEmpty()) return

        val style = NotificationCompat.InboxStyle()
        lines.take(6).forEach { style.addLine(it) }

        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_gavel)
            .setContentTitle(context.getString(R.string.notif_reminder_title, lines.size))
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.TAB_DIARY))
            .build()

        safeNotify(context, ID_REMINDERS, n)
    }

    /** Fired at (or shortly after) a task's own deadline — see [pk.advocate.casediary.work.TaskAlerts]. */
    fun notifyTaskDue(context: Context, taskId: Long, title: String) {
        if (!canNotify(context)) return

        val n = NotificationCompat.Builder(context, CH_TASKS)
            .setSmallIcon(R.drawable.ic_task)
            .setContentTitle(context.getString(R.string.notif_task_due, title))
            .setContentText(context.getString(R.string.tab_tasks))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, MainActivity.TAB_TASKS))
            .build()

        safeNotify(context, ID_TASK_BASE + (taskId % 10_000).toInt(), n)
    }

    private fun safeNotify(context: Context, id: Int, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post — nothing to do.
        }
    }
}
