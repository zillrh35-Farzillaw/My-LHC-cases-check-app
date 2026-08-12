package pk.advocate.casediary.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pk.advocate.casediary.databinding.FragmentDiaryBinding
import pk.advocate.casediary.databinding.ItemDiaryBinding
import pk.advocate.casediary.databinding.ItemDiaryHeaderBinding
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.WatchTerm
import pk.advocate.casediary.util.Dates
import pk.advocate.casediary.util.Prefs
import pk.advocate.casediary.work.CheckWorker

/**
 * One combined feed: hearings you entered yourself, plus anything the cause
 * list checker matched.
 */
class DiaryFragment : Fragment() {

    private var _b: FragmentDiaryBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: DiaryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentDiaryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DiaryAdapter { row ->
            when (row) {
                is DiaryRow.Upcoming -> openCase(row.caseId)
                is DiaryRow.Hit -> showHit(row)
                is DiaryRow.Header -> Unit
            }
        }
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        b.btnCheck.setOnClickListener { runCheck() }
        b.btnBrowser.setOnClickListener {
            startActivity(Intent(requireContext(), BrowserActivity::class.java))
        }
        b.swipe.setOnRefreshListener { runCheck() }

        // Long-press the status line to wipe the hit history.
        b.status.setOnLongClickListener {
            confirmClear()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
        Db.get(requireContext()).markAllFixturesSeen()
    }

    /**
     * Three sections, in the order they matter on a court morning:
     * your own cases that turned up in a list, then keyword-only matches worth
     * a look, then the hearings you already knew about.
     */
    private fun reload() {
        val db = Db.get(requireContext())
        val prefs = Prefs(requireContext())

        val mine = ArrayList<DiaryRow>()
        val others = ArrayList<DiaryRow>()
        val upcoming = ArrayList<DiaryRow>()

        for (f in db.listFixtures()) {
            val row = DiaryRow.Hit(
                badge = f.sourceLabel.ifBlank { "Cause list" },
                whenText = Dates.fmtStamp(f.foundAt),
                headline = f.matchedTerm.ifBlank { "Match" },
                detail = f.raw,
                listDate = f.listDate,
                url = f.sourceUrl,
                kind = kindLabel(f.matchedKind),
                caseId = f.caseId
            )
            if (f.isMine()) mine.add(row) else others.add(row)
        }

        // Next 14 days of hearings you entered yourself.
        val from = Dates.todayStart()
        val to = Dates.endOfDay(Dates.plusDays(from, 14))
        for (c in db.casesBetween(from, to)) {
            upcoming.add(
                DiaryRow.Upcoming(
                    caseId = c.id,
                    badge = Dates.relativeDayLabel(c.nextDate),
                    whenText = Dates.fmtWithDay(c.nextDate),
                    headline = c.caseRef().ifBlank { c.title() },
                    detail = listOfNotNull(
                        c.title().takeIf { c.caseRef().isNotBlank() },
                        c.court.takeIf { it.isNotBlank() },
                        c.stage.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                )
            )
        }

        val rows = ArrayList<DiaryRow>()
        if (mine.isNotEmpty()) {
            rows.add(DiaryRow.Header("My cases fixed", mine.size))
            rows.addAll(mine)
        }
        if (others.isNotEmpty()) {
            rows.add(DiaryRow.Header("Other keyword matches", others.size))
            rows.addAll(others)
        }
        if (upcoming.isNotEmpty()) {
            rows.add(DiaryRow.Header("Upcoming hearings", upcoming.size))
            rows.addAll(upcoming)
        }

        adapter.submit(rows)
        b.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        b.status.text = "Last check: ${Dates.fmtStamp(prefs.lastCheckAt)}" +
            if (prefs.lastCheckResult.isBlank()) "" else " — ${prefs.lastCheckResult}"
    }

    private fun kindLabel(kind: String): String = when (kind) {
        WatchTerm.KIND_ADVOCATE -> "counsel name"
        WatchTerm.KIND_PARTY -> "party name"
        WatchTerm.KIND_CASE -> "case number"
        WatchTerm.KIND_OTHER -> "keyword"
        else -> ""
    }

    private fun runCheck() {
        b.swipe.isRefreshing = true
        b.btnCheck.isEnabled = false
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CheckWorker.runOnce(appContext) }
            }
            if (_b == null) return@launch
            b.swipe.isRefreshing = false
            b.btnCheck.isEnabled = true
            reload()

            result.onSuccess { r ->
                val msg = when {
                    r.errors.isNotEmpty() && r.rowsScanned == 0 ->
                        "Could not read the cause lists. Try the built-in browser."
                    r.newHits > 0 -> "${r.newHits} new match(es) found"
                    r.totalHits > 0 -> "Nothing new — ${r.totalHits} already-seen match(es)"
                    else -> "No matches in ${r.rowsScanned} rows"
                }
                Snackbar.make(b.root, msg, Snackbar.LENGTH_LONG).show()
            }.onFailure {
                Snackbar.make(
                    b.root,
                    "Check failed: ${it.message ?: "unknown error"}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openCase(caseId: Long) {
        startActivity(
            Intent(requireContext(), CaseDetailActivity::class.java)
                .putExtra(CaseDetailActivity.EXTRA_CASE_ID, caseId)
        )
    }

    private fun showHit(row: DiaryRow.Hit) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(row.headline)
            .setMessage(
                buildString {
                    append(row.detail)
                    append("\n\n")
                    append("Source: ${row.badge}")
                    if (row.kind.isNotBlank()) append("\nMatched on: ${row.kind}")
                    if (row.listDate.isNotBlank()) append("\nList date: ${row.listDate}")
                    append("\n\nFound: ${row.whenText}")
                }
            )
            .setPositiveButton("Open page") { _, _ ->
                startActivity(
                    Intent(requireContext(), BrowserActivity::class.java)
                        .putExtra(BrowserActivity.EXTRA_URL, row.url)
                )
            }
            .setNegativeButton("Close", null)

        if (row.caseId != 0L) {
            dialog.setNeutralButton("Open case") { _, _ -> openCase(row.caseId) }
        }
        dialog.show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear all cause list hits?")
            .setMessage("Your cases and hearings are not touched.")
            .setPositiveButton("Clear") { _, _ ->
                Db.get(requireContext()).clearFixtures()
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

sealed class DiaryRow {

    data class Header(val title: String, val count: Int) : DiaryRow()

    data class Upcoming(
        val caseId: Long,
        val badge: String,
        val whenText: String,
        val headline: String,
        val detail: String
    ) : DiaryRow()

    data class Hit(
        val badge: String,
        val whenText: String,
        val headline: String,
        val detail: String,
        val listDate: String,
        val url: String,
        val kind: String,
        val caseId: Long
    ) : DiaryRow()
}

class DiaryAdapter(private val onClick: (DiaryRow) -> Unit) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<DiaryRow>()

    fun submit(list: List<DiaryRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class RowVH(val b: ItemDiaryBinding) : RecyclerView.ViewHolder(b.root)
    class HeaderVH(val b: ItemDiaryHeaderBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int =
        if (items[position] is DiaryRow.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemDiaryHeaderBinding.inflate(inflater, parent, false))
        } else {
            RowVH(ItemDiaryBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = items[position]) {
            is DiaryRow.Header -> {
                val h = holder as HeaderVH
                h.b.sectionTitle.text = row.title
                h.b.sectionCount.text = row.count.toString()
            }
            is DiaryRow.Upcoming -> {
                val v = holder as RowVH
                v.b.badge.text = row.badge
                v.b.whenText.text = row.whenText
                v.b.headline.text = row.headline
                bindDetail(v, row.detail)
                v.b.root.setOnClickListener { onClick(row) }
            }
            is DiaryRow.Hit -> {
                val v = holder as RowVH
                v.b.badge.text = row.badge
                v.b.whenText.text =
                    if (row.kind.isBlank()) row.whenText else "${row.kind} · ${row.whenText}"
                v.b.headline.text = row.headline
                bindDetail(v, row.detail)
                v.b.root.setOnClickListener { onClick(row) }
            }
        }
    }

    private fun bindDetail(v: RowVH, detail: String) {
        v.b.detail.text = detail
        v.b.detail.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_HEADER = 1
    }
}
