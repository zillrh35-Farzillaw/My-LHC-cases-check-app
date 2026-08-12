package pk.advocate.casediary.db

data class Case(
    var id: Long = 0,
    var caseType: String = "",      // W.P. / Crl. Misc. / R.F.A. / C.R. etc.
    var caseNo: String = "",
    var caseYear: String = "",
    var petitioner: String = "",
    var respondent: String = "",
    var clientName: String = "",
    var clientPhone: String = "",
    var court: String = "",         // Bench: Lahore / Rawalpindi / Multan / Bahawalpur
    var judge: String = "",
    var stage: String = "",
    var nextDate: Long = 0L,        // epoch millis, 0 = not set
    var status: String = STATUS_ACTIVE,
    var feeTotal: Double = 0.0,
    var feeReceived: Double = 0.0,
    var notes: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L
) {
    /** "W.P. 12345/2025" */
    fun caseRef(): String {
        val sb = StringBuilder()
        if (caseType.isNotBlank()) sb.append(caseType.trim()).append(' ')
        if (caseNo.isNotBlank()) sb.append(caseNo.trim())
        if (caseYear.isNotBlank()) sb.append('/').append(caseYear.trim())
        return sb.toString().trim()
    }

    /** "Ali vs. State" */
    fun title(): String {
        val p = petitioner.trim()
        val r = respondent.trim()
        return when {
            p.isNotEmpty() && r.isNotEmpty() -> "$p vs. $r"
            p.isNotEmpty() -> p
            r.isNotEmpty() -> r
            else -> "(untitled)"
        }
    }

    fun feeOutstanding(): Double = (feeTotal - feeReceived).coerceAtLeast(0.0)

    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_DECIDED = "DECIDED"
        const val STATUS_ARCHIVED = "ARCHIVED"
    }
}

data class Hearing(
    var id: Long = 0,
    var caseId: Long = 0,
    var date: Long = 0L,
    var proceedings: String = "",
    var nextDate: Long = 0L,
    var createdAt: Long = 0L
)

/**
 * A hit found in a cause list — either by the background scraper or by
 * scanning a page in the in-app browser.
 */
data class Fixture(
    var id: Long = 0,
    var hash: String = "",
    var caseId: Long = 0,           // 0 when the row matched a watch term but no saved case
    var sourceLabel: String = "",
    var sourceUrl: String = "",
    var listDate: String = "",      // whatever date text was detected on the page
    var raw: String = "",
    var matchedTerm: String = "",
    /** WatchTerm.KIND_* — what kind of keyword caught this row. */
    var matchedKind: String = "",
    var foundAt: Long = 0L,
    var seen: Boolean = false
) {
    /** True when this row was tied to a case saved in the app. */
    fun isMine(): Boolean = caseId != 0L
}

/** A keyword the scraper looks for in cause list rows. */
data class WatchTerm(
    var id: Long = 0,
    var term: String = "",
    var kind: String = KIND_ADVOCATE,
    var enabled: Boolean = true
) {
    companion object {
        const val KIND_ADVOCATE = "ADVOCATE"
        const val KIND_PARTY = "PARTY"
        const val KIND_CASE = "CASE"
        const val KIND_OTHER = "OTHER"
    }
}

/** A cause list page to poll. */
data class Source(
    var id: Long = 0,
    var label: String = "",
    var url: String = "",
    var enabled: Boolean = true
)
