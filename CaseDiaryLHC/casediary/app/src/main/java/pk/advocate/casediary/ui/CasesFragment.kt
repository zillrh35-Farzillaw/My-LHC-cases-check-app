package pk.advocate.casediary.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import pk.advocate.casediary.R
import pk.advocate.casediary.databinding.FragmentCasesBinding
import pk.advocate.casediary.databinding.ItemCaseBinding
import pk.advocate.casediary.db.Case
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.util.Dates

/**
 * Every tracked case, one place — status filters plus a search box across
 * case number/type/year, party names, court, judge, stage and notes (all
 * handled by Db.listCases()'s existing query). Tapping a row opens
 * CaseDetailActivity; "+" opens CaseEditActivity to add a new one.
 */
class CasesFragment : Fragment() {

    private var _b: FragmentCasesBinding? = null
    private val b get() = _b!!
    private lateinit var db: Db
    private lateinit var adapter: CaseAdapter

    private var filterStatus: String? = null // null = All
    private var query: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentCasesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        db = Db.get(requireContext())

        adapter = CaseAdapter { case ->
            startActivity(
                Intent(requireContext(), CaseDetailActivity::class.java)
                    .putExtra(CaseDetailActivity.EXTRA_CASE_ID, case.id)
            )
        }
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        b.btnAdd.setOnClickListener {
            startActivity(Intent(requireContext(), CaseEditActivity::class.java))
        }

        b.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                render()
            }
        })

        b.fAll.setOnClickListener { setFilter(null, b.fAll) }
        b.fActive.setOnClickListener { setFilter(Case.STATUS_ACTIVE, b.fActive) }
        b.fDecided.setOnClickListener { setFilter(Case.STATUS_DECIDED, b.fDecided) }
        b.fArchived.setOnClickListener { setFilter(Case.STATUS_ARCHIVED, b.fArchived) }
        setFilter(null, b.fAll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun setFilter(status: String?, selected: MaterialButton) {
        filterStatus = status
        val density = resources.displayMetrics.density
        for (btn in listOf(b.fAll, b.fActive, b.fDecided, b.fArchived)) {
            val isSel = btn === selected
            btn.backgroundTintList = ContextCompat.getColorStateList(
                requireContext(), if (isSel) R.color.brand else android.R.color.transparent
            )
            btn.setTextColor(
                ContextCompat.getColor(requireContext(), if (isSel) R.color.white else R.color.brand)
            )
            btn.strokeWidth = if (isSel) 0 else (density).toInt()
            btn.strokeColor = ContextCompat.getColorStateList(requireContext(), R.color.divider)
        }
        render()
    }

    private fun render() {
        val cases = db.listCases(filterStatus, query.ifBlank { null })
        b.empty.visibility = if (cases.isEmpty()) View.VISIBLE else View.GONE
        b.list.visibility = if (cases.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(cases)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}

private class CaseAdapter(
    private val onClick: (Case) -> Unit
) : RecyclerView.Adapter<CaseAdapter.VH>() {

    private var items: List<Case> = emptyList()

    fun submit(list: List<Case>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ib = ItemCaseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(ib)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(private val b: ItemCaseBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(c: Case, onClick: (Case) -> Unit) {
            val ctx = b.root.context

            b.ref.text = c.caseRef().ifBlank { "(no case number)" }
            b.title.text = c.title()

            when (c.status) {
                Case.STATUS_DECIDED -> {
                    b.status.text = ctx.getString(R.string.filter_decided)
                    b.status.setBackgroundColor(ContextCompat.getColor(ctx, R.color.divider))
                    b.status.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
                Case.STATUS_ARCHIVED -> {
                    b.status.text = ctx.getString(R.string.filter_archived)
                    b.status.setBackgroundColor(ContextCompat.getColor(ctx, R.color.accent_light))
                    b.status.setTextColor(ContextCompat.getColor(ctx, R.color.accent_dark))
                }
                else -> {
                    b.status.text = ctx.getString(R.string.filter_active)
                    b.status.setBackgroundColor(ContextCompat.getColor(ctx, R.color.brand_light))
                    b.status.setTextColor(ContextCompat.getColor(ctx, R.color.brand))
                }
            }

            val metaParts = listOfNotNull(
                c.court.takeIf { it.isNotBlank() },
                c.judge.takeIf { it.isNotBlank() },
                c.stage.takeIf { it.isNotBlank() }
            )
            if (metaParts.isNotEmpty()) {
                b.meta.visibility = View.VISIBLE
                b.meta.text = metaParts.joinToString(" · ")
            } else {
                b.meta.visibility = View.GONE
            }

            b.nextDate.text = if (c.nextDate > 0) {
                "Next hearing: ${Dates.fmt(c.nextDate)}"
            } else {
                "No hearing date set"
            }

            b.root.setOnClickListener { onClick(c) }
        }
    }
}
