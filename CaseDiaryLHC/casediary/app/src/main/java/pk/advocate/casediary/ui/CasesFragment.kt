package pk.advocate.casediary.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pk.advocate.casediary.R
import pk.advocate.casediary.databinding.FragmentCasesBinding
import pk.advocate.casediary.databinding.ItemCaseBinding
import pk.advocate.casediary.db.Case
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.util.Dates

/**
 * "Saved Cases" — every case being tracked, whether it landed here because
 * you approved a match in the Diary (see
 * [pk.advocate.casediary.db.Db.resolveCaseFromApproval]) or you added it
 * yourself with the + button. Both routes end up in the same list.
 */
class CasesFragment : Fragment() {

    private var _b: FragmentCasesBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: CaseAdapter
    private var statusFilter: String? = Case.STATUS_ACTIVE
    private var query: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentCasesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = CaseAdapter { case ->
            startActivity(
                Intent(requireContext(), CaseDetailActivity::class.java)
                    .putExtra(CaseDetailActivity.EXTRA_CASE_ID, case.id)
            )
        }
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        // Debounced: a ten-character search used to fire ten LIKE queries across
        // every column, on the main thread. Now it fires one, off it.
        b.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                searchRunnable?.let { handler.removeCallbacks(it) }
                val r = Runnable { reload() }
                searchRunnable = r
                handler.postDelayed(r, SEARCH_DEBOUNCE_MS)
            }
        })

        b.filterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            statusFilter = when (checkedIds.firstOrNull()) {
                R.id.chipDecided -> Case.STATUS_DECIDED
                R.id.chipArchived -> Case.STATUS_ARCHIVED
                R.id.chipAll -> null
                else -> Case.STATUS_ACTIVE
            }
            reload()
        }

        b.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), CaseEditActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val appContext = requireContext().applicationContext
        val status = statusFilter
        val q = query
        viewLifecycleOwner.lifecycleScope.launch {
            val db = Db.get(appContext)
            val cases = withContext(Dispatchers.IO) { db.listCases(status, q) }
            val fixedIds = withContext(Dispatchers.IO) { db.fixedCaseIds() }
            // The user may have typed on, or navigated away, while we queried.
            if (_b == null || status != statusFilter || q != query) return@launch
            adapter.submitList(cases.map { CaseRow(it, fixedIds.contains(it.id)) })
            b.empty.visibility = if (cases.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchRunnable = null
        _b = null
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
    }
}

/** One saved-case row plus whatever the list needs to know beyond the [Case] itself. */
data class CaseRow(val case: Case, val isFixed: Boolean)

private val DIFF = object : DiffUtil.ItemCallback<CaseRow>() {
    override fun areItemsTheSame(a: CaseRow, b: CaseRow) = a.case.id == b.case.id
    override fun areContentsTheSame(a: CaseRow, b: CaseRow) = a == b
}

/** ListAdapter + DiffUtil so a filter change or a new approved case animates
 *  in/out and reorders instead of the whole list silently repainting. */
class CaseAdapter(private val onClick: (Case) -> Unit) :
    ListAdapter<CaseRow, CaseAdapter.VH>(DIFF) {

    class VH(val b: ItemCaseBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(ItemCaseBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (c, isFixed) = getItem(position)
        val ctx = holder.b.root.context
        holder.b.ref.text = c.caseRef().ifBlank { "(no case number)" }
        holder.b.title.text = c.title()

        val (pillText, pillBg, pillFg) = when (c.status) {
            Case.STATUS_DECIDED -> Triple(ctx.getString(R.string.filter_decided), R.color.accent_light, R.color.accent_dark)
            Case.STATUS_ARCHIVED -> Triple(ctx.getString(R.string.filter_archived), R.color.divider, R.color.text_secondary)
            else -> Triple(ctx.getString(R.string.filter_active), R.color.brand_light, R.color.brand_dark)
        }
        holder.b.statusPill.text = pillText
        holder.b.statusPill.setBackgroundColor(ContextCompat.getColor(ctx, pillBg))
        holder.b.statusPill.setTextColor(ContextCompat.getColor(ctx, pillFg))

        val bits = ArrayList<String>()
        if (c.court.isNotBlank()) bits.add(c.court)
        if (c.judge.isNotBlank()) bits.add(c.judge)
        if (c.stage.isNotBlank()) bits.add(c.stage)
        if (c.clientName.isNotBlank()) bits.add("Client: ${c.clientName}")
        if (isFixed) bits.add("✓ Fixed in cause list")
        if (!c.watched) bits.add("Not auto-checked")
        holder.b.meta.text = bits.joinToString(" · ")
        holder.b.meta.visibility = if (bits.isEmpty()) View.GONE else View.VISIBLE

        if (c.nextDate > 0) {
            holder.b.next.visibility = View.VISIBLE
            holder.b.next.text = "${Dates.relativeDayLabel(c.nextDate)} · ${Dates.fmt(c.nextDate)}"
        } else {
            holder.b.next.visibility = View.GONE
        }

        holder.b.root.setOnClickListener { onClick(c) }
    }
}
