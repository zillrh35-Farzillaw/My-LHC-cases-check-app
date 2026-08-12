package pk.advocate.casediary.util

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("casediary", Context.MODE_PRIVATE)

    var autoCheckEnabled: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    /**
     * Evening run. LHC posts the urgent cause list between 5 and 6pm, so this
     * defaults to 18:30 — comfortably after, without waiting all evening.
     */
    var eveningHour: Int
        get() = sp.getInt(K_EVE_H, 18)
        set(v) = sp.edit().putInt(K_EVE_H, v).apply()

    var eveningMinute: Int
        get() = sp.getInt(K_EVE_M, 30)
        set(v) = sp.edit().putInt(K_EVE_M, v).apply()

    /** Morning run — catches supplementary and urgent lists added overnight. */
    var morningHour: Int
        get() = sp.getInt(K_MOR_H, 7)
        set(v) = sp.edit().putInt(K_MOR_H, v).apply()

    var morningMinute: Int
        get() = sp.getInt(K_MOR_M, 30)
        set(v) = sp.edit().putInt(K_MOR_M, v).apply()

    /** Remind about a hearing the evening before it. */
    var hearingRemindersEnabled: Boolean
        get() = sp.getBoolean(K_REMIND, true)
        set(v) = sp.edit().putBoolean(K_REMIND, v).apply()

    var lastCheckAt: Long
        get() = sp.getLong(K_LAST_AT, 0L)
        set(v) = sp.edit().putLong(K_LAST_AT, v).apply()

    var lastCheckResult: String
        get() = sp.getString(K_LAST_RES, "") ?: ""
        set(v) = sp.edit().putString(K_LAST_RES, v).apply()

    /** Auto-run a scan when a page finishes loading in the built-in browser. */
    var autoScanInBrowser: Boolean
        get() = sp.getBoolean(K_AUTOSCAN, true)
        set(v) = sp.edit().putBoolean(K_AUTOSCAN, v).apply()

    companion object {
        private const val K_AUTO = "auto_check"
        private const val K_EVE_H = "eve_h"
        private const val K_EVE_M = "eve_m"
        private const val K_MOR_H = "mor_h"
        private const val K_MOR_M = "mor_m"
        private const val K_REMIND = "hearing_reminders"
        private const val K_LAST_AT = "last_check_at"
        private const val K_LAST_RES = "last_check_result"
        private const val K_AUTOSCAN = "auto_scan_browser"
    }
}
