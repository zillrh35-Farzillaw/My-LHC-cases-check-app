package pk.advocate.casediary.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import pk.advocate.casediary.R
import pk.advocate.casediary.databinding.ActivityCaseDetailBinding
import pk.advocate.casediary.databinding.ItemHearingBinding
import pk.advocate.casediary.db.Case
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.Hearing
import pk.advocate.casediary.util.Dates
import java.util.Calendar

class CaseDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityCaseDetailBinding
    private lateinit var db: Db
    private var caseId: Long = 0L
    private var model: Case? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCaseDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        db = Db.get(this)

        caseId = intent.getLongExtra(EXTRA_CASE_ID, 0L)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.btnEdit.setOnClickListener {
            startActivity(
                Intent(this, CaseEditActivity::class.java)
                    .putExtra(CaseEditActivity.EXTRA_CASE_ID, caseId)
            )
        }

        b.btnCall.setOnClickListener {
            val phone = model?.clientPhone?.trim().orEmpty()
            if (phone.isBlank()) {
                Snackbar.make(b.root, "No client phone saved", Snackbar.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            }
        }

        b.btnAddHearing.setOnClickListener { addHearingDialog() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val c = db.getCase(caseId)
        if (c == null) {
            finish()
            return
        }
        model = c
        b.toolbar.title = c.caseRef().ifBlank { c.title() }
        b.ref.text = c.caseRef().ifBlank { "(no case number)" }
        b.title.text = c.title()

        val lines = ArrayList<String>()
        if (c.court.isNotBlank()) lines.add("Bench: ${c.court}")
        if (c.judge.isNotBlank()) lines.add("Judge / court: ${c.judge}")
        if (c.stage.isNotBlank()) lines.add("Stage: ${c.stage}")
        lines.add("Next hearing: ${if (c.nextDate > 0) Dates.fmtWithDay(c.nextDate) else "not set"}")
        if (c.clientName.isNotBlank()) lines.add("Client: ${c.clientName}")
        if (c.clientPhone.isNotBlank()) lines.add("Phone: ${c.clientPhone}")
        if (c.feeTotal > 0) {
            lines.add(
                "Fee: ${fmtMoney(c.feeReceived)} received of ${fmtMoney(c.feeTotal)}" +
                    " (${fmtMoney(c.feeOutstanding())} outstanding)"
            )
        }
        lines.add("Status: ${c.status.lowercase().replaceFirstChar { it.uppercase() }}")
        if (!c.watched) lines.add("Not checked automatically when scanning cause lists")
        val fixedEntry = db.listFixedCases().find { it.caseId == caseId }
        if (fixedEntry != null) {
            lines.add(
                "\n✓ Fixed in cause list — " +
                    (fixedEntry.proceedings.ifBlank { "listed" }) +
                    (if (fixedEntry.causelistNo.isNotBlank()) " (Causelist No. ${fixedEntry.causelistNo})" else "") +
                    " on ${Dates.fmt(fixedEntry.fixedDate)}"
            )
        } else {
            lines.add("\nNot yet seen in a checked cause list.")
        }
        if (c.notes.isNotBlank()) lines.add("\n${c.notes}")
        b.details.text = lines.joinToString("\n")

        renderHearings()
    }

    private fun fmtMoney(v: Double): String =
        "Rs " + String.format("%,.0f", v)

    private fun renderHearings() {
        b.hearingList.removeAllViews()
        val hearings = db.listHearings(caseId)
        if (hearings.isEmpty()) {
            val tv = android.widget.TextView(this)
            tv.text = "No hearings recorded yet."
            tv.textSize = 13f
            tv.setPadding(0, 8, 0, 0)
            b.hearingList.addView(tv)
            return
        }
        val inflater = LayoutInflater.from(this)
        for (h in hearings) {
            val hb = ItemHearingBinding.inflate(inflater, b.hearingList, false)
            hb.date.text = Dates.fmtWithDay(h.date) +
                if (h.nextDate > 0) "  →  next ${Dates.fmt(h.nextDate)}" else ""
            hb.proceedings.text = h.proceedings.ifBlank { "(no note)" }
            hb.btnDelete.setOnClickListener {
                db.deleteHearing(h.id)
                renderHearings()
            }
            b.hearingList.addView(hb.root)
        }
    }

    private fun addHearingDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)

        val noteInput = EditText(this)
        noteInput.hint = getString(R.string.proceedings)
        noteInput.setLines(3)
        noteInput.gravity = android.view.Gravity.TOP

        val dateBtn = com.google.android.material.button.MaterialButton(this)
        var hearingDate = Dates.todayStart()
        var nextDate = 0L
        dateBtn.text = "Hearing date: ${Dates.fmt(hearingDate)}"
        dateBtn.setOnClickListener {
            val c = Calendar.getInstance()
            c.timeInMillis = hearingDate
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    val picked = Calendar.getInstance()
                    picked.set(y, m, d, 10, 0, 0)
                    picked.set(Calendar.MILLISECOND, 0)
                    hearingDate = picked.timeInMillis
                    dateBtn.text = "Hearing date: ${Dates.fmt(hearingDate)}"
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        val nextBtn = com.google.android.material.button.MaterialButton(this)
        nextBtn.text = "Next date: not set"
        nextBtn.setOnClickListener {
            val c = Calendar.getInstance()
            if (nextDate > 0) c.timeInMillis = nextDate
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    val picked = Calendar.getInstance()
                    picked.set(y, m, d, 10, 0, 0)
                    picked.set(Calendar.MILLISECOND, 0)
                    nextDate = picked.timeInMillis
                    nextBtn.text = "Next date: ${Dates.fmt(nextDate)}"
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        container.addView(dateBtn)
        container.addView(nextBtn)
        container.addView(noteInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_hearing)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                db.saveHearing(
                    Hearing(
                        caseId = caseId,
                        date = hearingDate,
                        proceedings = noteInput.text?.toString()?.trim().orEmpty(),
                        nextDate = nextDate
                    )
                )
                // Recording a next date on a hearing moves the case forward too.
                if (nextDate > 0) {
                    model?.let {
                        it.nextDate = nextDate
                        db.saveCase(it)
                    }
                }
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_CASE_ID = "case_id"
    }
}
