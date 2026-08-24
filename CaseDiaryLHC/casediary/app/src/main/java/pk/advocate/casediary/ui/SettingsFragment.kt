package pk.advocate.casediary.ui

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import pk.advocate.casediary.R
import pk.advocate.casediary.databinding.FragmentSettingsBinding
import pk.advocate.casediary.databinding.ItemSourceBinding
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.WatchTerm
import pk.advocate.casediary.util.Backup
import pk.advocate.casediary.util.Dates
import pk.advocate.casediary.util.Prefs
import pk.advocate.casediary.work.Scheduler

class SettingsFragment : Fragment() {

    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    private lateinit var db: Db
    private lateinit var prefs: Prefs

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                val s = Backup.importFrom(requireContext(), uri)
                renderTerms()
                renderSources()
                Snackbar.make(
                    b.root,
                    "Imported ${s.cases} case(s), ${s.hearings} hearing(s), ${s.terms} keyword(s)",
                    Snackbar.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Snackbar.make(
                    b.root,
                    "Import failed: ${e.message ?: "unreadable file"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = Db.get(requireContext())
        prefs = Prefs(requireContext())

        b.btnAddTerm.setOnClickListener { addTermDialog() }
        b.btnAddSource.setOnClickListener { addSourceDialog() }

        b.swAuto.isChecked = prefs.autoCheckEnabled
        b.swAuto.setOnCheckedChangeListener { _, checked ->
            prefs.autoCheckEnabled = checked
            Scheduler.rescheduleAll(requireContext().applicationContext)
        }

        b.swReminders.isChecked = prefs.hearingRemindersEnabled
        b.swReminders.setOnCheckedChangeListener { _, checked ->
            prefs.hearingRemindersEnabled = checked
            Scheduler.rescheduleAll(requireContext().applicationContext)
        }

        b.swAutoScan.isChecked = prefs.autoScanInBrowser
        b.swAutoScan.setOnCheckedChangeListener { _, checked ->
            prefs.autoScanInBrowser = checked
        }

        b.swPrincipalSeat.isChecked = prefs.principalSeatOnly
        b.swPrincipalSeat.setOnCheckedChangeListener { _, checked ->
            prefs.principalSeatOnly = checked
        }

        b.btnMorning.setOnClickListener {
            pickTime(prefs.morningHour, prefs.morningMinute) { h, m ->
                prefs.morningHour = h
                prefs.morningMinute = m
                Scheduler.rescheduleAll(requireContext().applicationContext)
                renderTimes()
            }
        }

        b.btnEvening.setOnClickListener {
            pickTime(prefs.eveningHour, prefs.eveningMinute) { h, m ->
                prefs.eveningHour = h
                prefs.eveningMinute = m
                Scheduler.rescheduleAll(requireContext().applicationContext)
                renderTimes()
            }
        }

        b.btnExport.setOnClickListener { exportBackup() }
        b.btnImport.setOnClickListener {
            importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        renderTerms()
        renderSources()
        renderTimes()
    }

    override fun onResume() {
        super.onResume()
        renderTimes()
    }

    private fun renderTimes() {
        b.btnMorning.text = getString(R.string.morning_time) +
            ": ${Dates.two(prefs.morningHour)}:${Dates.two(prefs.morningMinute)}"
        b.btnEvening.text = getString(R.string.evening_time) +
            ": ${Dates.two(prefs.eveningHour)}:${Dates.two(prefs.eveningMinute)}"
        b.lastCheck.text = getString(R.string.last_check) +
            ": ${Dates.fmtStamp(prefs.lastCheckAt)}" +
            if (prefs.lastCheckResult.isBlank()) "" else "\n${prefs.lastCheckResult}"
    }

    private fun pickTime(hour: Int, minute: Int, onSet: (Int, Int) -> Unit) {
        TimePickerDialog(
            requireContext(),
            { _, h, m -> onSet(h, m) },
            hour, minute, true
        ).show()
    }

    private fun renderTerms() {
        b.termChips.removeAllViews()
        val terms = db.listWatchTerms() // already sorted PRIMARY first
        if (terms.isEmpty()) {
            val chip = Chip(requireContext())
            chip.text = "No keywords yet"
            chip.isEnabled = false
            b.termChips.addView(chip)
            return
        }
        for (t in terms) {
            val chip = Chip(requireContext())
            val star = if (t.priority == WatchTerm.PRIORITY_PRIMARY) "★ " else ""
            val tag = if (t.builtin) "always on" else t.kind.lowercase()
            chip.text = "$star${t.term}  ·  $tag"
            chip.isCheckable = !t.builtin
            chip.isChecked = t.enabled
            chip.isCloseIconVisible = !t.builtin
            if (!t.builtin) {
                chip.setOnCheckedChangeListener { _, checked ->
                    db.setWatchTermEnabled(t.id, checked)
                }
                chip.setOnCloseIconClickListener {
                    db.deleteWatchTerm(t.id)
                    renderTerms()
                }
                chip.setOnLongClickListener {
                    db.setWatchTermPriority(
                        t.id,
                        if (t.priority == WatchTerm.PRIORITY_PRIMARY) WatchTerm.PRIORITY_OTHER else WatchTerm.PRIORITY_PRIMARY
                    )
                    renderTerms()
                    true
                }
            }
            b.termChips.addView(chip)
        }
    }

    private fun addTermDialog() {
        val container = LinearLayout(requireContext())
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)

        val input = EditText(requireContext())
        input.hint = "e.g. your name as printed in the cause list"

        val kinds = listOf(
            WatchTerm.KIND_ADVOCATE to "Advocate / counsel name",
            WatchTerm.KIND_PARTY to "Party name",
            WatchTerm.KIND_CASE to "Case number",
            WatchTerm.KIND_OTHER to "Other"
        )
        val spinner = Spinner(requireContext())
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            kinds.map { it.second }
        )

        val primaryCheck = com.google.android.material.checkbox.MaterialCheckBox(requireContext())
        primaryCheck.text = "Primary keyword — flagged first, treated as high priority"

        container.addView(input)
        container.addView(spinner)
        container.addView(primaryCheck)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_keyword)
            .setMessage("Matching ignores case, spacing and punctuation, so \"Ahmad Raza\" also finds \"RAZA, AHMAD\". Long-press a keyword chip any time to toggle its priority.")
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.length < 3) {
                    Snackbar.make(b.root, "Keyword is too short", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val kind = kinds[spinner.selectedItemPosition].first
                val priority = if (primaryCheck.isChecked) WatchTerm.PRIORITY_PRIMARY else WatchTerm.PRIORITY_OTHER
                db.addWatchTerm(text, kind, priority)
                renderTerms()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderSources() {
        b.sourceList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (s in db.listSources()) {
            val sb = ItemSourceBinding.inflate(inflater, b.sourceList, false)
            sb.label.text = s.label.ifBlank { s.url }
            sb.url.text = s.url
            sb.enabled.isChecked = s.enabled
            sb.enabled.setOnCheckedChangeListener { _, checked ->
                db.setSourceEnabled(s.id, checked)
            }
            sb.btnDelete.setOnClickListener {
                db.deleteSource(s.id)
                renderSources()
            }
            b.sourceList.addView(sb.root)
        }
    }

    private fun addSourceDialog() {
        val container = LinearLayout(requireContext())
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)

        val labelInput = EditText(requireContext())
        labelInput.hint = getString(R.string.label)
        val urlInput = EditText(requireContext())
        urlInput.hint = getString(R.string.url)
        urlInput.setText("https://")

        container.addView(labelInput)
        container.addView(urlInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_source)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = urlInput.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http")) {
                    Snackbar.make(b.root, "That does not look like a URL", Snackbar.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                db.addSource(labelInput.text?.toString()?.trim().orEmpty().ifBlank { url }, url)
                renderSources()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportBackup() {
        try {
            val file = Backup.exportToCache(requireContext())
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Save or send backup"))
        } catch (e: Exception) {
            Snackbar.make(
                b.root,
                "Export failed: ${e.message ?: "unknown error"}",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
