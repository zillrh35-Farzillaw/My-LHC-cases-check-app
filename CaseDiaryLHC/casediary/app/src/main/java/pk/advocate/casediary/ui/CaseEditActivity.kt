package pk.advocate.casediary.ui

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import pk.advocate.casediary.R
import pk.advocate.casediary.databinding.ActivityCaseEditBinding
import pk.advocate.casediary.db.Case
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.util.Dates
import java.util.Calendar

class CaseEditActivity : AppCompatActivity() {

    private lateinit var b: ActivityCaseEditBinding
    private lateinit var db: Db
    private var model = Case()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCaseEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        val caseId = intent.getLongExtra(EXTRA_CASE_ID, 0L)
        if (caseId != 0L) {
            model = db.getCase(caseId) ?: Case()
            b.toolbar.title = getString(R.string.edit_case)
            b.btnDelete.visibility = android.view.View.VISIBLE
        } else {
            b.toolbar.title = getString(R.string.add_case)
        }

        b.toolbar.setNavigationOnClickListener { finish() }
        bind()

        b.btnDate.setOnClickListener { pickDate() }
        b.btnClearDate.setOnClickListener {
            model.nextDate = 0L
            updateDateButton()
        }
        b.btnSave.setOnClickListener { save() }
        b.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun bind() {
        b.caseType.setText(model.caseType)
        b.caseNo.setText(model.caseNo)
        b.caseYear.setText(model.caseYear)
        b.petitioner.setText(model.petitioner)
        b.respondent.setText(model.respondent)
        b.clientName.setText(model.clientName)
        b.clientPhone.setText(model.clientPhone)
        b.court.setText(model.court)
        b.judge.setText(model.judge)
        b.stage.setText(model.stage)
        if (model.feeTotal > 0) b.feeTotal.setText(model.feeTotal.toString())
        if (model.feeReceived > 0) b.feeReceived.setText(model.feeReceived.toString())
        b.notes.setText(model.notes)
        b.swWatched.isChecked = model.watched

        when (model.status) {
            Case.STATUS_DECIDED -> b.stDecided.isChecked = true
            Case.STATUS_ARCHIVED -> b.stArchived.isChecked = true
            else -> b.stActive.isChecked = true
        }

        updateDateButton()
    }

    private fun updateDateButton() {
        b.btnDate.text = if (model.nextDate > 0) {
            Dates.fmtWithDay(model.nextDate)
        } else {
            getString(R.string.pick_date)
        }
    }

    private fun pickDate() {
        val c = Calendar.getInstance()
        if (model.nextDate > 0) c.timeInMillis = model.nextDate
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance()
                picked.set(year, month, day, 10, 0, 0)
                picked.set(Calendar.MILLISECOND, 0)
                model.nextDate = picked.timeInMillis
                updateDateButton()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun save() {
        model.caseType = b.caseType.text?.toString()?.trim().orEmpty()
        model.caseNo = b.caseNo.text?.toString()?.trim().orEmpty()
        model.caseYear = b.caseYear.text?.toString()?.trim().orEmpty()
        model.petitioner = b.petitioner.text?.toString()?.trim().orEmpty()
        model.respondent = b.respondent.text?.toString()?.trim().orEmpty()
        model.clientName = b.clientName.text?.toString()?.trim().orEmpty()
        model.clientPhone = b.clientPhone.text?.toString()?.trim().orEmpty()
        model.court = b.court.text?.toString()?.trim().orEmpty()
        model.judge = b.judge.text?.toString()?.trim().orEmpty()
        model.stage = b.stage.text?.toString()?.trim().orEmpty()
        model.feeTotal = b.feeTotal.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        model.feeReceived = b.feeReceived.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
        model.notes = b.notes.text?.toString()?.trim().orEmpty()
        model.watched = b.swWatched.isChecked
        model.status = when {
            b.stDecided.isChecked -> Case.STATUS_DECIDED
            b.stArchived.isChecked -> Case.STATUS_ARCHIVED
            else -> Case.STATUS_ACTIVE
        }

        if (model.caseNo.isBlank() && model.petitioner.isBlank() && model.respondent.isBlank()) {
            Snackbar.make(b.root, "Add at least a case number or a party name", Snackbar.LENGTH_LONG)
                .show()
            return
        }

        db.saveCase(model)
        finish()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this case?")
            .setMessage("Its hearings will be deleted too. This cannot be undone.")
            .setPositiveButton(R.string.delete) { _, _ ->
                db.deleteCase(model.id)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_CASE_ID = "case_id"
    }
}
