package pk.advocate.casediary.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import pk.advocate.casediary.databinding.FragmentJudgmentsBinding
import pk.advocate.casediary.databinding.ItemJudgmentBinding
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.Judgment

/**
 * A personal, searchable reference library of judgments/citations the lawyer
 * wants to remember — title, citation, court/year, the principle it stands
 * for, free notes and tags. Always typed in by hand: this never fetches or
 * invents case law on its own, since a wrong citation here could end up
 * relied on in a real filing.
 */
class JudgmentsFragment : Fragment() {

    private var _b: FragmentJudgmentsBinding? = null
    private val b get() = _b!!
    private lateinit var db: Db
    private var query: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentJudgmentsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = Db.get(requireContext())
        b.btnAdd.setOnClickListener { addJudgment() }
        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim()?.lowercase().orEmpty()
                render()
            }
        })
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun addJudgment() {
        val title = b.titleInput.text?.toString()?.trim().orEmpty()
        if (title.length < 3) {
            Snackbar.make(b.root, "Enter the case title.", Snackbar.LENGTH_SHORT).show()
            return
        }
        val j = Judgment(
            title = title,
            citation = b.citationInput.text?.toString()?.trim().orEmpty(),
            court = b.courtInput.text?.toString()?.trim().orEmpty(),
            year = b.yearInput.text?.toString()?.trim().orEmpty(),
            principle = b.principleInput.text?.toString()?.trim().orEmpty(),
            notes = b.notesInput.text?.toString()?.trim().orEmpty(),
            tags = parseTags(b.tagsInput.text?.toString())
        )
        db.addJudgment(j)

        for (input in listOf(
            b.titleInput, b.citationInput, b.courtInput, b.yearInput,
            b.principleInput, b.notesInput, b.tagsInput
        )) {
            input.setText("")
        }
        render()
    }

    private fun parseTags(raw: String?): List<String> =
        (raw ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun render() {
        b.judgmentList.removeAllViews()
        val all = db.listJudgments()
        b.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        if (all.isEmpty()) return

        val list = if (query.isEmpty()) all else all.filter { j ->
            listOf(j.title, j.citation, j.court, j.year, j.principle, j.notes, j.tags.joinToString(" "))
                .any { it.lowercase().contains(query) }
        }

        val inflater = LayoutInflater.from(requireContext())
        for (j in list) {
            val ib = ItemJudgmentBinding.inflate(inflater, b.judgmentList, false)
            ib.citation.visibility = if (j.citation.isBlank()) View.GONE else View.VISIBLE
            ib.citation.text = j.citation
            ib.title.text = j.title
            val courtYear = listOf(j.court, j.year).filter { it.isNotBlank() }.joinToString(" · ")
            ib.courtYear.visibility = if (courtYear.isBlank()) View.GONE else View.VISIBLE
            ib.courtYear.text = courtYear
            ib.principle.visibility = if (j.principle.isBlank()) View.GONE else View.VISIBLE
            ib.principle.text = j.principle
            ib.notes.visibility = if (j.notes.isBlank()) View.GONE else View.VISIBLE
            ib.notes.text = j.notes
            ib.tags.visibility = if (j.tags.isEmpty()) View.GONE else View.VISIBLE
            ib.tags.text = j.tags.joinToString("  ·  ") { "#$it" }

            ib.btnEdit.setOnClickListener { editDialog(j.id) }
            ib.btnRemove.setOnClickListener { confirmRemove(j) }
            b.judgmentList.addView(ib.root)
        }
    }

    private fun editDialog(id: Long) {
        val j = db.listJudgments().find { it.id == id } ?: return
        val container = LinearLayout(requireContext())
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)

        val titleInput = EditText(requireContext())
        titleInput.hint = "Case title"
        titleInput.setText(j.title)
        val citationInput = EditText(requireContext())
        citationInput.hint = "Citation"
        citationInput.setText(j.citation)
        val courtInput = EditText(requireContext())
        courtInput.hint = "Court"
        courtInput.setText(j.court)
        val yearInput = EditText(requireContext())
        yearInput.hint = "Year"
        yearInput.setText(j.year)
        val principleInput = EditText(requireContext())
        principleInput.hint = "Principle / holding"
        principleInput.setText(j.principle)
        val notesInput = EditText(requireContext())
        notesInput.hint = "Your notes"
        notesInput.setText(j.notes)
        val tagsInput = EditText(requireContext())
        tagsInput.hint = "Tags (comma-separated)"
        tagsInput.setText(j.tags.joinToString(", "))
        for (v in listOf(titleInput, citationInput, courtInput, yearInput, principleInput, notesInput, tagsInput)) {
            container.addView(v)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit judgment")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text?.toString()?.trim().orEmpty()
                if (title.isBlank()) {
                    Snackbar.make(b.root, "Enter the case title.", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                j.title = title
                j.citation = citationInput.text?.toString()?.trim().orEmpty()
                j.court = courtInput.text?.toString()?.trim().orEmpty()
                j.year = yearInput.text?.toString()?.trim().orEmpty()
                j.principle = principleInput.text?.toString()?.trim().orEmpty()
                j.notes = notesInput.text?.toString()?.trim().orEmpty()
                j.tags = parseTags(tagsInput.text?.toString())
                db.updateJudgment(j)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove(j: Judgment) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove this judgment?")
            .setMessage(j.title)
            .setPositiveButton("Remove") { _, _ ->
                db.deleteJudgment(j.id)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
