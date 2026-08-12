package pk.advocate.casediary

import android.app.Application
import pk.advocate.casediary.util.Notifications
import pk.advocate.casediary.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // Re-arm on every launch so a missed chain link repairs itself.
        Scheduler.rescheduleAll(this)
    }
}
