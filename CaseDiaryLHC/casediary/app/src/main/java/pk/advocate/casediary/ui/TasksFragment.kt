package pk.advocate.casediary.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import pk.advocate.casediary.R
import pk.advocate.casediary.databinding.FragmentTasksBinding
import pk.advocate.casediary.databinding.ItemTaskBinding
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.LawTask
import pk.advocate.casediary.util.Dates
import pk.advocate.casediary.work.TaskAlerts
import java.util.Calendar

/**
 * Things a senior advocate or officer has told the lawyer to do, each with a
 * deadline. Unlike the web app, a real alert is scheduled for the deadline
 * itself via WorkManager (see [TaskAlerts]), so it fires even if the app is
 * closed — nothing told by a senior should ever be missed.
 */
class TasksFragment : Fragment() {

    private var _b: FragmentTasksBinding? = null
    private val b get() = _b!!
    private lateinit var db: Db
    private var pickedDeadline: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentTasksBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = Db.get(requireContext())
        b.btnDeadline.setOnClickListener { pickDeadline() }
        b.btnAdd.setOnClickListener { addTask() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun pickDeadline() {
        val c = Calendar.getInstance()
        if (pickedDeadline > 0) c.timeInMillis = pickedDeadline
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val picked = Calendar.getInstance()
                picked.timeInMillis = if (pickedDeadline > 0) pickedDeadline else System.currentTimeMillis()
                picked.set(year, month, day)
                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        picked.set(Calendar.HOUR_OF_DAY, hour)
                        picked.set(Calendar.MINUTE, minute)
                        picked.set(Calendar.SECOND, 0)
                        picked.set(Calendar.MILLISECOND, 0)
                        pickedDeadline = picked.timeInMillis
                        b.btnDeadline.text = Dates.fmtStamp(pickedDeadline)
                    },
                    picked.get(Calendar.HOUR_OF_DAY), picked.get(Calendar.MINUTE), false
                ).show()
            },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun addTask() {
        val title = b.titleInput.text?.toString()?.trim().orEmpty()
        if (title.length < 3) {
            Snackbar.make(b.root, "Enter what needs to be done.", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (pickedDeadline <= 0L) {
            Snackbar.make(b.root, "Pick a deadline for this task.", Snackbar.LENGTH_SHORT).show()
            return
        }
        val assignedBy = b.assignedByInput.text?.toString()?.trim().orEmpty()
        val id = db.addTask(LawTask(title = title, assignedBy = assignedBy, deadline = pickedDeadline))
        TaskAlerts.schedule(requireContext(), id, pickedDeadline)

        b.titleInput.setText("")
        b.assignedByInput.setText("")
        pickedDeadline = 0L
        b.btnDeadline.text = getString(R.string.task_deadline_hint)
        render()
    }

    private fun render() {
        b.taskList.removeAllViews()
        val items = db.listTasks().sortedWith(compareBy({ it.done }, { it.deadline }))
        b.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(requireContext())
        val now = System.currentTimeMillis()
        for (t in items) {
            val ib = ItemTaskBinding.inflate(inflater, b.taskList, false)
            ib.title.text = t.title
            ib.assignedBy.visibility = if (t.assignedBy.isBlank()) View.GONE else View.VISIBLE
            ib.assignedBy.text = "Assigned by ${t.assignedBy}"

            val overdue = !t.done && t.deadline in 1 until now
            val soon = !t.done && !overdue && t.deadline - now <= 2L * 24 * 60 * 60 * 1000

            when {
                t.done -> {
                    ib.status.text = getString(R.string.task_done)
                    ib.status.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider))
                    ib.status.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    ib.deadline.text = "Was due ${Dates.fmtStamp(t.deadline)} · done ${Dates.fmtStamp(t.doneAt)}"
                }
                overdue -> {
                    ib.status.text = getString(R.string.task_overdue)
                    ib.status.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_light))
                    ib.status.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
                    ib.deadline.text = "Was due ${Dates.fmtStamp(t.deadline)}"
                }
                soon -> {
                    ib.status.text = getString(R.string.task_due_soon)
                    ib.status.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.accent_light))
                    ib.status.setTextColor(ContextCompat.getColor(requireContext(), R.color.urgent))
                    ib.deadline.text = "Due ${Dates.fmtStamp(t.deadline)}"
                }
                else -> {
                    ib.status.text = getString(R.string.task_open)
                    ib.status.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.brand_light))
                    ib.status.setTextColor(ContextCompat.getColor(requireContext(), R.color.brand))
                    ib.deadline.text = "Due ${Dates.fmtStamp(t.deadline)}"
                }
            }

            ib.btnDone.text = getString(if (t.done) R.string.mark_incomplete else R.string.mark_complete)
            ib.btnDone.setOnClickListener {
                db.setTaskDone(t.id, !t.done)
                if (!t.done) TaskAlerts.cancel(requireContext(), t.id) else TaskAlerts.schedule(requireContext(), t.id, t.deadline)
                render()
            }
            ib.btnRemove.setOnClickListener { confirmRemove(t) }
            b.taskList.addView(ib.root)
        }
    }

    private fun confirmRemove(t: LawTask) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove this task?")
            .setMessage(t.title)
            .setPositiveButton("Remove") { _, _ ->
                db.deleteTask(t.id)
                TaskAlerts.cancel(requireContext(), t.id)
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
