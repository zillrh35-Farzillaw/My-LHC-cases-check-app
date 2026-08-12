package pk.advocate.casediary.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** WorkManager survives reboots, but re-arming here costs nothing and is safer. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Scheduler.rescheduleAll(context.applicationContext)
        }
    }
}
