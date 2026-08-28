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
    var updatedAt: Long = 0L,
    /** When false, this case's number/party is skipped while scanning cause lists. */
    var watched: Boolean = true
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

/** One keyword/case/pending-file term that matched a row, kept alongside its kind. */
data class TermHit(val term: String, val kind: String)

/**
 * A hit found in a cause list — either by the background scraper or by
 * scanning a page in the in-app browser.
 *
 * One row is one [Fixture], even when several keywords, a saved case number
 * and a pending file all match the same line — every term that matched is
 * kept in [terms] instead of duplicating the row per keyword. A C.M./
 * application row belonging to this same case (see
 * [pk.advocate.casediary.work.CauseListScraper]) is folded in as
 * [relatedRaw] rather than shown as a separate result.
 */
data class Fixture(
    var id: Long = 0,
    var hash: String = "",
    var caseId: Long = 0,           // 0 when the row matched a watch term but no saved case
    var pendingId: Long = 0,        // 0 unless this is a fuzzy match against a PendingFile
    var sourceLabel: String = "",
    var sourceUrl: String = "",
    var listDate: String = "",      // whatever date text was detected on the page
    var raw: String = "",
    var terms: List<TermHit> = emptyList(),
    var relatedRaw: List<String> = emptyList(),
    var foundAt: Long = 0L,
    var seen: Boolean = false
) {
    /** True when this row was tied to a case saved in the app. */
    fun isMine(): Boolean = caseId != 0L

    /** True when this row is a fuzzy match against a pending file, awaiting approval. */
    fun needsApproval(): Boolean = pendingId != 0L

    /** All matched terms joined for display, e.g. "Ali Raza  ·  NADRA". */
    fun termsLabel(): String = terms.joinToString("  ·  ") { it.term }

    /** Distinct kind labels across every matched term, for display. */
    fun kinds(): List<String> = terms.map { it.kind }.distinct()

    /** [raw] plus any folded-in C.M./application lines, laid out as one block for display. */
    fun detailText(): String {
        if (relatedRaw.isEmpty()) return raw
        val count = relatedRaw.size
        val block = relatedRaw.joinToString("\n") { "  • $it" }
        return "$raw\n\n+ $count related C.M./application line${if (count == 1) "" else "s"} in this case:\n$block"
    }

    /** [raw] plus related lines, joined for anything that needs the plain combined text (e.g. exports). */
    fun fullRaw(): String = (listOf(raw) + relatedRaw).joinToString("\n")
}

/** A keyword the scraper looks for in cause list rows. */
data class WatchTerm(
    var id: Long = 0,
    var term: String = "",
    var kind: String = KIND_ADVOCATE,
    var enabled: Boolean = true,
    var priority: String = PRIORITY_OTHER,
    /** Seeded by the app (NADRA, citizenship, etc.) — always on, cannot be deleted. */
    var builtin: Boolean = false
) {
    companion object {
        const val KIND_ADVOCATE = "ADVOCATE"
        const val KIND_PARTY = "PARTY"
        const val KIND_CASE = "CASE"
        const val KIND_OTHER = "OTHER"
        const val KIND_PENDING = "PENDING"

        const val PRIORITY_PRIMARY = "PRIMARY"
        const val PRIORITY_OTHER = "OTHER"
    }
}

/** A cause list page to poll. */
data class Source(
    var id: Long = 0,
    var label: String = "",
    var url: String = "",
    var enabled: Boolean = true
)

/**
 * A case whose file the lawyer already holds but which has not yet been fixed
 * (listed) in a cause list. Checked on every scan with fuzzy name matching;
 * once it turns up and is approved, it becomes a [FixedCase] and this entry
 * is deleted.
 */
data class PendingFile(
    var id: Long = 0,
    var title: String = "",
    var note: String = "",
    var addedAt: Long = 0L,
    /** Optional — once the file is registered (but still not fixed/listed), its
     *  case number can be added for an exact match alongside the fuzzy title one. */
    var caseType: String = "",
    var caseNo: String = "",
    var caseYear: String = ""
) {
    /** "W.P. 12345/2026", or blank if no case number has been added yet. */
    fun caseRef(): String {
        if (caseNo.isBlank()) return ""
        val sb = StringBuilder()
        if (caseType.isNotBlank()) sb.append(caseType.trim()).append(' ')
        sb.append(caseNo.trim())
        if (caseYear.isNotBlank()) sb.append('/').append(caseYear.trim())
        return sb.toString().trim()
    }
}

/**
 * A case the lawyer has approved/saved from a Diary match. Carries enough on
 * its own face (title & case no., court, judge, hearing date) that the lawyer
 * doesn't have to open a dialog just to see what it's about.
 */
data class FixedCase(
    var id: Long = 0,
    var titleNo: String = "",
    var court: String = "",
    var prayer: String = "",
    var proceedings: String = "",
    var causelistNo: String = "",
    var fixedDate: Long = 0L,
    var sourceRaw: String = "",
    /** 0 unless this came from (and links back to) a case saved in the Cases tab. */
    var caseId: Long = 0,
    /** The presiding judge, when the cause list row it came from carried one. */
    var judge: String = "",
    /** The cause list's own printed date — when this case is fixed for hearing. */
    var hearingDate: String = ""
)

/**
 * Something a senior advocate or officer told the lawyer to do, with a
 * deadline — tracked here so it's never missed. Unlike the web app, the
 * Android build can schedule a real alert for the deadline itself (see
 * [pk.advocate.casediary.work.TaskAlarmReceiver]), not just a warning shown
 * while the app happens to be open.
 */
data class LawTask(
    var id: Long = 0,
    var title: String = "",
    var assignedBy: String = "",
    var deadline: Long = 0L,
    var done: Boolean = false,
    var doneAt: Long = 0L,
    var createdAt: Long = 0L
)
