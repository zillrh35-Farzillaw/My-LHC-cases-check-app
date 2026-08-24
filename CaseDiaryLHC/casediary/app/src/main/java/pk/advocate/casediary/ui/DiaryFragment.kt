package pk.advocate.casediary.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.core.content.FileProvider
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
import pk.advocate.casediary.databinding.ItemDiaryApproveBinding
import pk.advocate.casediary.databinding.ItemDiaryBinding
import pk.advocate.casediary.databinding.ItemDiaryHeaderBinding
import pk.advocate.casediary.databinding.ItemFixedBinding
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.FixedCase
import pk.advocate.casediary.db.WatchTerm
import pk.advocate.casediary.util.Dates
import pk.advocate.casediary.util.ReportPdf
import pk.advocate.casediary.work.CheckWorker

/**
 * One combined feed: pending-file matches, the fixed-cases report (= "My
 * Cases directory"), hearings you entered yourself, and everything the cause
 * list checker matched. Every match — whichever section it's in — has a
 * "Save to My Cases" action, so any result the lawyer confirms is theirs
 * lands in the same exportable place, linked back to a saved Case when there
 * is one.
 */
class DiaryFragment : Fragment() {

    private var _b: FragmentDiaryBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: DiaryAdapter
    private lateinit var db: Db

    /** Transient (not persisted) selection state for bulk actions. */
    private val selectedHits = HashSet<Long>()
    private val selectedFixed = HashSet<Long>()
    private var selectableHitIds: List<Long> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentDiaryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = Db.get(requireContext())

        adapter = DiaryAdapter(
            onClick = { row ->
                when (row) {
                    is DiaryRow.Upcoming -> openCase(row.caseId)
                    is DiaryRow.Hit -> showHit(row)
                    is DiaryRow.FixedRow -> editFixedDialog(row.id)
                    else -> Unit
                }
            },
            onApprove = { row -> saveDialog(row.fixtureId) },
            onReject = { row ->
                db.deleteFixture(row.fixtureId)
                reload()
            },
            onSaveHit = { row -> saveDialog(row.fixtureId) },
            onRemoveHit = { row ->
                db.deleteFixture(row.fixtureId)
                selectedHits.remove(row.fixtureId)
                reload()
            },
            onToggleHitSelect = { id ->
                if (!selectedHits.add(id)) selectedHits.remove(id)
                reload()
            },
            onToggleFixedSelect = { id ->
                if (!selectedFixed.add(id)) selectedFixed.remove(id)
                reload()
            },
            isHitSelected = { selectedHits.contains(it) },
            isFixedSelected = { selectedFixed.contains(it) }
        )
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        b.btnCheck.setOnClickListener { runCheck() }
        b.btnBrowser.setOnClickListener {
            startActivity(Intent(requireContext(), BrowserActivity::class.java))
        }
        b.btnSearch.setOnClickListener { searchDialog() }
        b.btnExportPdf.setOnClickListener { exportPdf() }
        b.btnSaveSelected.setOnClickListener { saveSelectedHits() }
        b.btnSelectAll.setOnClickListener { selectAllHits() }
        b.btnClearSelected.setOnClickListener { confirmClearSelected() }
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
        db.markAllFixturesSeen()
    }

    private fun reload() {
        val prefs = pk.advocate.casediary.util.Prefs(requireContext())

        val approval = ArrayList<DiaryRow>()
        val mine = ArrayList<DiaryRow>()
        val others = ArrayList<DiaryRow>()
        val fixedRows = ArrayList<DiaryRow>()
        val upcoming = ArrayList<DiaryRow>()

        for (f in db.listFixtures()) {
            if (f.needsApproval()) {
                val pendingTitle = if (f.pendingId != 0L) db.listPendingFiles().find { it.id == f.pendingId }?.title else null
                approval.add(
                    DiaryRow.Approval(
                        fixtureId = f.id,
                        pendingId = f.pendingId,
                        headline = pendingTitle ?: f.termsLabel().ifBlank { "Pending file match" },
                        detail = f.raw,
                        whenText = Dates.fmtStamp(f.foundAt)
                    )
                )
                continue
            }
            val row = DiaryRow.Hit(
                fixtureId = f.id,
                badge = f.sourceLabel.ifBlank { "Cause list" },
                whenText = Dates.fmtStamp(f.foundAt),
                headline = f.termsLabel().ifBlank { "Match" },
                detail = f.raw,
                listDate = f.listDate,
                url = f.sourceUrl,
                kind = f.kinds().joinToString(", ") { kindLabel(it) },
                caseId = f.caseId,
                multiTerm = f.terms.size > 1
            )
            if (f.isMine()) mine.add(row) else others.add(row)
        }

        db.listFixedCases().forEachIndexed { i, fc ->
            fixedRows.add(
                DiaryRow.FixedRow(
                    id = fc.id,
                    srNo = i + 1,
                    titleNo = fc.titleNo,
                    court = fc.court,
                    prayer = fc.prayer,
                    proceedings = fc.proceedings,
                    causelistNo = fc.causelistNo
                )
            )
        }

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

        selectableHitIds = (approval.map { (it as DiaryRow.Approval).fixtureId } +
            mine.map { (it as DiaryRow.Hit).fixtureId } +
            others.map { (it as DiaryRow.Hit).fixtureId })

        val rows = ArrayList<DiaryRow>()
        if (approval.isNotEmpty()) {
            rows.add(DiaryRow.Header("Needs your approval", approval.size))
            rows.addAll(approval)
        }
        if (fixedRows.isNotEmpty()) {
            rows.add(DiaryRow.Header("Fixed cases — My Cases directory", fixedRows.size))
            rows.addAll(fixedRows)
        }
        if (mine.isNotEmpty()) {
            rows.add(DiaryRow.Header("My saved cases — matched today", mine.size))
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

        b.btnExportPdf.text = if (selectedFixed.isNotEmpty()) {
            "Export ${selectedFixed.size} selected as PDF"
        } else {
            "Export report PDF"
        }

        if (selectedHits.isNotEmpty()) {
            b.btnSaveSelected.visibility = View.VISIBLE
            b.btnSaveSelected.text = "Save ${selectedHits.size} selected to My Cases"
        } else {
            b.btnSaveSelected.visibility = View.GONE
        }

        b.btnSelectAll.visibility = if (selectableHitIds.isNotEmpty()) View.VISIBLE else View.GONE
        b.btnSelectAll.text = "Select all (${selectableHitIds.size})"
        b.btnClearSelected.visibility = if (selectedHits.isNotEmpty()) View.VISIBLE else View.GONE
        b.btnClearSelected.text = "Clear ${selectedHits.size} selected"
    }

    private fun selectAllHits() {
        selectedHits.addAll(selectableHitIds)
        reload()
    }

    private fun confirmClearSelected() {
        if (selectedHits.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove ${selectedHits.size} selected match(es)?")
            .setMessage("Your cases are not touched.")
            .setPositiveButton("Remove") { _, _ ->
                for (id in HashSet(selectedHits)) db.deleteFixture(id)
                selectedHits.clear()
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                    r.newHits > 0 -> "${r.newHits} new match(es) found" +
                        if (r.approvalHits > 0) " — ${r.approvalHits} need approval" else ""
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
        val savedCase = if (row.caseId != 0L) db.getCase(row.caseId) else null
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(row.headline)
            .setMessage(
                buildString {
                    append(row.detail)
                    append("\n\n")
                    if (savedCase != null) {
                        if (savedCase.court.isNotBlank()) append("Bench: ${savedCase.court}\n")
                        if (savedCase.judge.isNotBlank()) append("Judge / court: ${savedCase.judge}\n")
                        if (savedCase.stage.isNotBlank()) append("Stage: ${savedCase.stage}\n")
                    }
                    append("Source: ${row.badge}")
                    if (row.kind.isNotBlank()) append("\nMatched on: ${row.kind}")
                    if (row.listDate.isNotBlank()) append("\nList date: ${row.listDate}")
                    append("\n\nFound: ${row.whenText}")
                }
            )
            .setPositiveButton("Save to My Cases") { _, _ -> saveDialog(row.fixtureId) }
            .setNegativeButton("Close", null)

        if (row.caseId != 0L) {
            dialog.setNeutralButton("Open case") { _, _ -> openCase(row.caseId) }
        }
        dialog.show()
    }

    /** Save any fixture (approval, mine, or keyword-only) into the Fixed-cases report. */
    private fun saveDialog(fixtureId: Long) {
        val f = db.listFixtures().find { it.id == fixtureId } ?: return
        val pending = if (f.pendingId != 0L) db.listPendingFiles().find { it.id == f.pendingId } else null
        val savedCase = if (f.caseId != 0L) db.getCase(f.caseId) else null
        val defaultTitle = pending?.title
            ?: savedCase?.let { it.caseRef().ifBlank { it.title() } }
            ?: f.termsLabel()

        val container = LinearLayout(requireContext())
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)

        val titleInput = EditText(requireContext())
        titleInput.hint = "Title & No. of the case"
        titleInput.setText(defaultTitle)
        val courtInput = EditText(requireContext())
        courtInput.hint = "Name of the Court (e.g. Justice Asad Ali Bajwa)"
        val prayerInput = EditText(requireContext())
        prayerInput.hint = "Nature of Prayer & Remarks"
        val proceedingsInput = EditText(requireContext())
        proceedingsInput.hint = "Proceedings (e.g. First Hearing)"
        val causelistInput = EditText(requireContext())
        causelistInput.hint = "Urgent Causelist No."
        for (v in listOf(titleInput, courtInput, prayerInput, proceedingsInput, causelistInput)) {
            container.addView(v)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save to My Cases")
            .setMessage(f.raw)
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val titleNo = titleInput.text?.toString()?.trim().orEmpty()
                if (titleNo.isBlank()) {
                    Snackbar.make(b.root, "Add the case title", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                db.addFixedCase(
                    FixedCase(
                        titleNo = titleNo,
                        court = courtInput.text?.toString()?.trim().orEmpty(),
                        prayer = prayerInput.text?.toString()?.trim().orEmpty(),
                        proceedings = proceedingsInput.text?.toString()?.trim().orEmpty(),
                        causelistNo = causelistInput.text?.toString()?.trim().orEmpty(),
                        sourceRaw = f.raw,
                        caseId = f.caseId
                    )
                )
                if (f.pendingId != 0L) db.deletePendingFile(f.pendingId)
                db.deleteFixture(f.id)
                selectedHits.remove(f.id)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Bulk version — skips the per-item form; details can be added later by tapping the row. */
    private fun saveSelectedHits() {
        if (selectedHits.isEmpty()) return
        val fixtures = db.listFixtures()
        for (id in HashSet(selectedHits)) {
            val f = fixtures.find { it.id == id } ?: continue
            val pending = if (f.pendingId != 0L) db.listPendingFiles().find { it.id == f.pendingId } else null
            val savedCase = if (f.caseId != 0L) db.getCase(f.caseId) else null
            val titleNo = pending?.title
                ?: savedCase?.let { it.caseRef().ifBlank { it.title() } }
                ?: f.termsLabel()
            db.addFixedCase(FixedCase(titleNo = titleNo, sourceRaw = f.raw, caseId = f.caseId))
            if (f.pendingId != 0L) db.deletePendingFile(f.pendingId)
            db.deleteFixture(f.id)
        }
        selectedHits.clear()
        reload()
    }

    private fun editFixedDialog(id: Long) {
        val f = db.listFixedCases().find { it.id == id } ?: return
        val container = LinearLayout(requireContext())
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)

        val titleInput = EditText(requireContext())
        titleInput.setText(f.titleNo)
        val courtInput = EditText(requireContext())
        courtInput.hint = "Name of the Court"
        courtInput.setText(f.court)
        val prayerInput = EditText(requireContext())
        prayerInput.hint = "Nature of Prayer & Remarks"
        prayerInput.setText(f.prayer)
        val proceedingsInput = EditText(requireContext())
        proceedingsInput.hint = "Proceedings"
        proceedingsInput.setText(f.proceedings)
        val causelistInput = EditText(requireContext())
        causelistInput.hint = "Urgent Causelist No."
        causelistInput.setText(f.causelistNo)
        for (v in listOf(titleInput, courtInput, prayerInput, proceedingsInput, causelistInput)) {
            container.addView(v)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit case in My Cases")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val titleNo = titleInput.text?.toString()?.trim().orEmpty()
                if (titleNo.isBlank()) {
                    Snackbar.make(b.root, "Add the case title", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                f.titleNo = titleNo
                f.court = courtInput.text?.toString()?.trim().orEmpty()
                f.prayer = prayerInput.text?.toString()?.trim().orEmpty()
                f.proceedings = proceedingsInput.text?.toString()?.trim().orEmpty()
                f.causelistNo = causelistInput.text?.toString()?.trim().orEmpty()
                db.updateFixedCase(f)
                reload()
            }
            .setNeutralButton("Remove") { _, _ ->
                db.deleteFixedCase(id)
                selectedFixed.remove(id)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun searchDialog() {
        val container = LinearLayout(requireContext())
        container.orientation = LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)

        val input = EditText(requireContext())
        input.hint = "Name, judge, Sr No., case no…"
        val results = ListView(requireContext())
        val resultAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ArrayList<String>())
        results.adapter = resultAdapter
        results.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (300 * resources.displayMetrics.density).toInt()
        )

        container.addView(input)
        container.addView(results)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Search checked lists")
            .setMessage("Looks across every cause list you've already scanned — judge names, serial numbers, case numbers, party names — even if it isn't one of your keywords.")
            .setView(container)
            .setPositiveButton("Search") { _, _ -> }
            .setNegativeButton("Close", null)
            .create()

        fun runSearch() {
            val q = input.text?.toString()?.trim().orEmpty()
            if (q.length < 2) {
                resultAdapter.clear()
                resultAdapter.add("Type at least 2 characters")
                resultAdapter.notifyDataSetChanged()
                return
            }
            val hits = db.searchScanRows(q)
            resultAdapter.clear()
            if (hits.isEmpty()) {
                resultAdapter.add("No matches across ${db.scanRowCount()} checked line(s)")
            } else {
                for ((rowText, at) in hits) {
                    resultAdapter.add("$rowText\n— ${Dates.fmtStamp(at)}")
                }
            }
            resultAdapter.notifyDataSetChanged()
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { runSearch() }
        }
        dialog.show()
    }

    private fun exportPdf() {
        try {
            val file = ReportPdf.export(requireContext(), selectedFixed)
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share urgent cases report"))
        } catch (e: Exception) {
            Snackbar.make(
                b.root,
                "Export failed: ${e.message ?: "unknown error"}",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear all cause list hits?")
            .setMessage("Your cases, fixed-cases report and pending files are not touched.")
            .setPositiveButton("Clear") { _, _ ->
                Db.get(requireContext()).clearFixtures()
                selectedHits.clear()
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
        val fixtureId: Long,
        val badge: String,
        val whenText: String,
        val headline: String,
        val detail: String,
        val listDate: String,
        val url: String,
        val kind: String,
        val caseId: Long,
        val multiTerm: Boolean = false
    ) : DiaryRow()

    data class Approval(
        val fixtureId: Long,
        val pendingId: Long,
        val headline: String,
        val detail: String,
        val whenText: String
    ) : DiaryRow()

    data class FixedRow(
        val id: Long,
        val srNo: Int,
        val titleNo: String,
        val court: String,
        val prayer: String,
        val proceedings: String,
        val causelistNo: String
    ) : DiaryRow()
}

class DiaryAdapter(
    private val onClick: (DiaryRow) -> Unit,
    private val onApprove: (DiaryRow.Approval) -> Unit,
    private val onReject: (DiaryRow.Approval) -> Unit,
    private val onSaveHit: (DiaryRow.Hit) -> Unit,
    private val onRemoveHit: (DiaryRow.Hit) -> Unit,
    private val onToggleHitSelect: (Long) -> Unit,
    private val onToggleFixedSelect: (Long) -> Unit,
    private val isHitSelected: (Long) -> Boolean,
    private val isFixedSelected: (Long) -> Boolean
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<DiaryRow>()

    fun submit(list: List<DiaryRow>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class RowVH(val b: ItemDiaryBinding) : RecyclerView.ViewHolder(b.root)
    class HeaderVH(val b: ItemDiaryHeaderBinding) : RecyclerView.ViewHolder(b.root)
    class ApproveVH(val b: ItemDiaryApproveBinding) : RecyclerView.ViewHolder(b.root)
    class FixedVH(val b: ItemFixedBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DiaryRow.Header -> TYPE_HEADER
        is DiaryRow.Approval -> TYPE_APPROVE
        is DiaryRow.FixedRow -> TYPE_FIXED
        else -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemDiaryHeaderBinding.inflate(inflater, parent, false))
            TYPE_APPROVE -> ApproveVH(ItemDiaryApproveBinding.inflate(inflater, parent, false))
            TYPE_FIXED -> FixedVH(ItemFixedBinding.inflate(inflater, parent, false))
            else -> RowVH(ItemDiaryBinding.inflate(inflater, parent, false))
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
                v.b.actionRow.visibility = View.GONE
                v.b.root.setOnClickListener { onClick(row) }
            }
            is DiaryRow.Hit -> {
                val v = holder as RowVH
                v.b.badge.text = if (row.multiTerm) "${row.badge} · multiple keywords" else row.badge
                v.b.whenText.text =
                    if (row.kind.isBlank()) row.whenText else "${row.kind} · ${row.whenText}"
                v.b.headline.text = if (isHitSelected(row.fixtureId)) "☑ ${row.headline}" else row.headline
                bindDetail(v, row.detail)
                v.b.root.setOnClickListener { onToggleHitSelect(row.fixtureId) }
                v.b.root.setOnLongClickListener { onClick(row); true }
                v.b.actionRow.visibility = View.VISIBLE
                v.b.saveBtn.setOnClickListener { onSaveHit(row) }
                v.b.removeBtn2.setOnClickListener { onRemoveHit(row) }
            }
            is DiaryRow.Approval -> {
                val v = holder as ApproveVH
                v.b.whenText.text = row.whenText
                v.b.headline.text = row.headline
                v.b.detail.text = row.detail
                v.b.btnApprove.setOnClickListener { onApprove(row) }
                v.b.btnReject.setOnClickListener { onReject(row) }
            }
            is DiaryRow.FixedRow -> {
                val v = holder as FixedVH
                val mark = if (isFixedSelected(row.id)) "☑" else "☐"
                v.b.headline.text = "$mark ${row.srNo}. ${row.titleNo}"
                v.b.detail.text = listOfNotNull(
                    row.court.takeIf { it.isNotBlank() },
                    row.prayer.takeIf { it.isNotBlank() },
                    row.proceedings.takeIf { it.isNotBlank() },
                    row.causelistNo.takeIf { it.isNotBlank() }?.let { "Causelist No. $it" }
                ).joinToString(" · ")
                v.b.root.setOnClickListener { onClick(row) }
                v.b.root.setOnLongClickListener { onToggleFixedSelect(row.id); true }
                v.b.removeBtn.text = "Select for export"
                v.b.removeBtn.setOnClickListener { onToggleFixedSelect(row.id) }
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
        private const val TYPE_APPROVE = 2
        private const val TYPE_FIXED = 3
    }
}
