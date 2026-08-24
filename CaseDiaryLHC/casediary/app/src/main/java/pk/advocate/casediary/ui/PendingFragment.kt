package pk.advocate.casediary.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import pk.advocate.casediary.databinding.FragmentPendingBinding
import pk.advocate.casediary.databinding.ItemPendingBinding
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.util.Dates
import pk.advocate.casediary.util.Prefs

/**
 * Case files the lawyer already holds but which have not been fixed (listed)
 * yet — checked with fuzzy name matching on every scan, from here on.
 */
class PendingFragment : Fragment() {

    private var _b: FragmentPendingBinding? = null
    private val b get() = _b!!
    private lateinit var db: Db
    private lateinit var prefs: Prefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentPendingBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = Db.get(requireContext())
        prefs = Prefs(requireContext())
        b.btnAdd.setOnClickListener { addPending() }
        b.swPendingSection.isChecked = prefs.pendingFilesEnabled
        b.swPendingSection.setOnCheckedChangeListener { _, checked ->
            prefs.pendingFilesEnabled = checked
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun addPending() {
        val title = b.titleInput.text?.toString()?.trim().orEmpty()
        if (title.length < 5) {
            Snackbar.make(b.root, "Enter the case title — at least a few words.", Snackbar.LENGTH_SHORT).show()
            return
        }
        val note = b.noteInput.text?.toString()?.trim().orEmpty()
        db.addPendingFile(title, note)
        b.titleInput.setText("")
        b.noteInput.setText("")
        render()
    }

    private fun render() {
        b.pendingList.removeAllViews()
        val items = db.listPendingFiles()
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val fixtures = db.listFixtures()
        val inflater = LayoutInflater.from(requireContext())
        for (p in items) {
            val ib = ItemPendingBinding.inflate(inflater, b.pendingList, false)
            ib.title.text = p.title
            ib.note.visibility = if (p.note.isBlank()) View.GONE else View.VISIBLE
            ib.note.text = p.note
            ib.added.text = "Added ${Dates.fmt(p.addedAt)}"

            val matchCount = fixtures.count { it.pendingId == p.id }
            if (matchCount > 0) {
                ib.matches.visibility = View.VISIBLE
                ib.matches.text = "$matchCount possible match${if (matchCount == 1) "" else "es"} — review in Diary"
            } else {
                ib.matches.visibility = View.GONE
            }

            ib.btnRemove.setOnClickListener { confirmRemove(p.id) }
            b.pendingList.addView(ib.root)
        }
    }

    private fun confirmRemove(id: Long) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove this pending file?")
            .setMessage("It will no longer be checked.")
            .setPositiveButton("Remove") { _, _ ->
                db.deletePendingFile(id)
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
