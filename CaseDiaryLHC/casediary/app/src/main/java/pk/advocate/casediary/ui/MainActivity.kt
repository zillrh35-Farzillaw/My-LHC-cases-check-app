package pk.advocate.casediary.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import pk.advocate.casediary.R
import pk.advocate.casediary.databinding.ActivityMainBinding
import pk.advocate.casediary.util.Notifications

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var currentTab: String = TAB_CASES

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_cases -> show(TAB_CASES)
                R.id.nav_diary -> show(TAB_DIARY)
                R.id.nav_settings -> show(TAB_SETTINGS)
                else -> false
            }
        }

        val requested = intent?.getStringExtra(EXTRA_OPEN_TAB) ?: TAB_CASES
        selectTab(requested)

        askForNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_TAB)?.let { selectTab(it) }
    }

    private fun selectTab(tab: String) {
        b.bottomNav.selectedItemId = when (tab) {
            TAB_DIARY -> R.id.nav_diary
            TAB_SETTINGS -> R.id.nav_settings
            else -> R.id.nav_cases
        }
    }

    private fun show(tab: String): Boolean {
        currentTab = tab
        val fragment: Fragment = when (tab) {
            TAB_DIARY -> DiaryFragment()
            TAB_SETTINGS -> SettingsFragment()
            else -> CasesFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(b.container.id, fragment, tab)
            .commit()

        b.toolbar.title = when (tab) {
            TAB_DIARY -> getString(R.string.tab_diary)
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
        const val TAB_CASES = "cases"
        const val TAB_DIARY = "diary"
        const val TAB_SETTINGS = "settings"
    }
}
