package pk.advocate.casediary.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pk.advocate.casediary.R
import pk.advocate.casediary.databinding.ActivityMainBinding
import pk.advocate.casediary.util.Notifications
import pk.advocate.casediary.util.Prefs
import pk.advocate.casediary.util.UpdateChecker

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var currentTab: String = TAB_DIARY

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_diary -> show(TAB_DIARY)
                R.id.nav_pending -> show(TAB_PENDING)
                R.id.nav_tasks -> show(TAB_TASKS)
                R.id.nav_settings -> show(TAB_SETTINGS)
                else -> false
            }
        }

        val requested = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: TAB_DIARY
        selectTab(requested)

        askForNotificationPermission()
        checkForUpdateSilently()
    }

    /** At most once an hour, so opening/backgrounding the app repeatedly doesn't hammer the API. */
    private fun checkForUpdateSilently() {
        val prefs = Prefs(this)
        if (System.currentTimeMillis() - prefs.lastUpdateCheckAt < 60 * 60 * 1000L) return
        lifecycleScope.launch {
            val info = UpdateChecker.fetchLatest()
            prefs.lastUpdateCheckAt = System.currentTimeMillis()
            if (info != null && UpdateChecker.isNewer(info)) {
                UpdateChecker.promptInstall(this@MainActivity, lifecycleScope, b.root, info)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_TAB)?.let { selectTab(it) }
    }

    private fun selectTab(tab: String) {
        b.bottomNav.selectedItemId = when (tab) {
            TAB_PENDING -> R.id.nav_pending
            TAB_TASKS -> R.id.nav_tasks
            TAB_SETTINGS -> R.id.nav_settings
            else -> R.id.nav_diary
        }
    }

    private fun show(tab: String): Boolean {
        currentTab = tab
        val fragment: Fragment = when (tab) {
            TAB_PENDING -> PendingFragment()
            TAB_TASKS -> TasksFragment()
            TAB_SETTINGS -> SettingsFragment()
            else -> DiaryFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, fragment, tab)
            .commit()

        b.toolbar.title = when (tab) {
            TAB_PENDING -> getString(R.string.tab_pending)
            TAB_TASKS -> getString(R.string.tab_tasks)
            TAB_SETTINGS -> getString(R.string.tab_settings)
            else -> getString(R.string.app_name)
        }
        return true
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (Notifications.canNotify(this)) return
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_DIARY = "diary"
        const val TAB_PENDING = "pending"
        const val TAB_TASKS = "tasks"
        const val TAB_SETTINGS = "settings"
    }
}
