package pk.advocate.casediary.work

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.Fixture
import pk.advocate.casediary.db.TermHit
import pk.advocate.casediary.util.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.regex.Pattern

/**
 * Pulls each configured cause-list page and looks for the user's keywords.
 *
 * The LHC site's markup is not documented and changes between list types, so
 * this reads *any* tabular or list-like content rather than depending on a
 * fixed set of CSS selectors. If a list is only reachable by submitting a form,
 * use the in-app browser and its "Scan page" button instead — the same matching
 * logic runs there via [scanHtml].
 */
class CauseListScraper(private val context: Context) {

    data class Result(
        val newHits: Int,
        val totalHits: Int,
        val rowsScanned: Int,
        val errors: List<String>,
        val lines: List<String>,
        val approvalHits: Int = 0
    )

    private val db = Db.get(context)

    fun runAll(): Result {
        val sources = db.listSources(onlyEnabled = true)
        if (sources.isEmpty()) {
            return Result(0, 0, 0, listOf("No cause list sources enabled"), emptyList())
        }

        var newHits = 0
        var totalHits = 0
        var rows = 0
        var approvalHits = 0
        val errors = ArrayList<String>()
        val lines = ArrayList<String>()

        for (src in sources) {
            try {
                val html = fetch(src.url)
                val r = scanHtml(html, src.label, src.url)
                newHits += r.newHits
                totalHits += r.totalHits
                rows += r.rowsScanned
                approvalHits += r.approvalHits
                lines.addAll(r.lines)
            } catch (e: Exception) {
                errors.add("${src.label}: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        db.pruneFixtures()
        db.pruneScanRows()
        return Result(newHits, totalHits, rows, errors, lines, approvalHits)
    }

    fun fetch(urlString: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 25_000
            conn.readTimeout = 35_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** Runs the matcher over already-fetched HTML. Shared with the browser scan. */
    fun scanHtml(html: String, sourceLabel: String, sourceUrl: String): Result {
        val doc = Jsoup.parse(html, sourceUrl)
        val listDate = detectListDate(doc)
        val rows = extractRows(doc)
        return scanRows(rows, listDate, sourceLabel, sourceUrl)
    }

    /** Runs the matcher over plain text pulled out of a WebView. */
    fun scanText(text: String, sourceLabel: String, sourceUrl: String): Result {
        val rows = text.split('\n').map { it.trim() }.filter { it.length >= 8 }
        val listDate = detectDateIn(text)
        return scanRows(rows, listDate, sourceLabel, sourceUrl)
    }

    private fun scanRows(
        rowsIn: List<String>,
        listDate: String,
        sourceLabel: String,
        sourceUrl: String
    ): Result {
        val terms = db.listWatchTerms(onlyEnabled = true)
        val cases = db.listCases(null, null)
        val pending = db.listPendingFiles()
        val merged = Matcher.mergeSplitCmLines(rowsIn)
        db.insertScanRows(sourceLabel, merged)

        val prefs = Prefs(context)
        val rows = if (prefs.principalSeatOnly) merged.filterNot { Matcher.isOtherBench(it) } else merged
        // Section-level pause: the lawyer can turn off pending-file matching for
        // the whole list at once (e.g. while tidying it up) without deleting it.
        val activePending = if (prefs.pendingFilesEnabled) pending else emptyList()

        if (terms.isEmpty() && cases.isEmpty() && pending.isEmpty()) {
            return Result(
                0, 0, rowsIn.size,
                listOf("Nothing to look for yet — add a keyword, a case or a pending file first"),
                emptyList()
            )
        }

        // Tokenise the terms once, not once per row.
        val probes = Matcher.compile(terms, cases, activePending)

        var totalHits = 0
        val lines = ArrayList<String>()
        // One Fixture per row — every keyword, case number and pending file
        // that matched the same line is merged into that row's term list
        // instead of duplicating the row per keyword.
        val found = ArrayList<Fixture>()
        val now = System.currentTimeMillis()

        // Pass 1: find every matched row and compute its own identity (hash)
        // and, for C.M./application rows, which main case they belong to.
        data class Entry(
            val row: String, val hits: List<Matcher.Hit>, val cm: Boolean, val parents: List<String>,
            val clipped: String, val hash: String, var groupHash: String = ""
        )
        // The presiding judge is printed once per bench section (a date/bench-
        // type header, then one or two judge lines), never on the case row
        // itself — tracked here across rows in print order and prefixed onto
        // whatever this section's rows turn out to match.
        var currentJudges = mutableListOf<String>()
        val entries = rows.mapNotNull { row ->
            if (Matcher.isSectionReset(row)) currentJudges = mutableListOf()
            Matcher.extractJudge(row)?.let { currentJudges.add(it); return@mapNotNull null }

            val all = Matcher.findHits(row, probes)
            if (all.isEmpty()) return@mapNotNull null
            val cm = Matcher.isCmRow(row)
            val parents = if (cm) Matcher.cmParentRefs(row) else emptyList()
            val judge = currentJudges.joinToString(" & ")
            val base = if (row.length > 400) row.substring(0, 400) + "…" else row
            val clipped = if (judge.isNotBlank()) "[$judge] $base" else base
            Entry(row, all, cm, parents, clipped, Matcher.hashOf(sourceUrl, listDate, clipped))
        }

        // Pass 2: every bare main-case row this scan matched, indexed by its
        // ref, so a C.M. filed in it can be found regardless of print order.
        val mainCaseHashByRef = HashMap<String, String>()
        for (e in entries) {
            if (e.cm) continue
            for (ref in Matcher.ownCaseRefs(e.row)) mainCaseHashByRef.putIfAbsent(ref, e.hash)
        }

        // Pass 3: work out which single "box" each row belongs to. A C.M.
        // whose main case also matched joins that case's box; a C.M. whose
        // main case did NOT turn up on its own still gets a box — the first
        // such C.M. becomes that box, and any further C.M.s of the very same
        // case join it — so the same case is never shown twice just because
        // several of its applications matched.
        val cmGroupHash = HashMap<String, String>()
        for (e in entries) {
            e.groupHash = if (e.cm && e.parents.isNotEmpty()) {
                val mainRef = e.parents.firstOrNull { mainCaseHashByRef.containsKey(it) }
                if (mainRef != null) {
                    mainCaseHashByRef.getValue(mainRef)
                } else {
                    cmGroupHash.getOrPut(e.parents[0]) { e.hash }
                }
            } else {
                e.hash
            }
        }

        // Pass 4: fold every non-root row into its group's root entry as "related".
        val roots = LinkedHashMap<String, Pair<Entry, MutableList<Entry>>>()
        for (e in entries) if (e.hash == e.groupHash) roots[e.hash] = e to ArrayList()
        for (e in entries) {
            if (e.hash == e.groupHash) continue
            val g = roots[e.groupHash]
            if (g != null) g.second.add(e) else roots[e.hash] = e to ArrayList() // safety net — never silently lose a row
        }

        for ((entry, related) in roots.values) {
            val allHits = (listOf(entry) + related).flatMap { it.hits }
            val mine = allHits.filter { it.caseId != 0L }
            val hits = if (mine.isNotEmpty()) mine else allHits
            totalHits += hits.size

            val caseId = hits.firstOrNull { it.caseId != 0L }?.caseId ?: 0L
            val pendingId = if (caseId == 0L) hits.firstOrNull { it.pendingId != 0L }?.pendingId ?: 0L else 0L
            val seenTerms = HashSet<String>()
            val termHits = ArrayList<TermHit>()
            for (h in hits) {
                val key = Matcher.normalize(h.term)
                if (!seenTerms.add(key)) continue
                termHits.add(TermHit(h.term, h.kind))
            }

            found.add(
                Fixture(
                    hash = entry.hash,
                    caseId = caseId,
                    pendingId = pendingId,
                    sourceLabel = sourceLabel,
                    sourceUrl = sourceUrl,
                    listDate = listDate,
                    raw = entry.clipped,
                    terms = termHits,
                    relatedRaw = related.map { it.clipped },
                    foundAt = now,
                    seen = false
                )
            )
        }

        // One transaction for the whole page rather than one per row.
        val inserted = db.insertFixtures(found)
        var approvalHits = 0
        for (f in inserted) {
            if (f.needsApproval()) approvalHits++
            lines.add(
                buildString {
                    append(f.termsLabel().ifBlank { "Match" }).append(" — ").append(sourceLabel)
                    if (listDate.isNotBlank()) append(" (").append(listDate).append(")")
                    if (f.needsApproval()) append(" — needs approval")
                }
            )
        }

        return Result(inserted.size, totalHits, rowsIn.size, emptyList(), lines, approvalHits)
    }

    /**
     * Every table row, list item and paragraph on the page, flattened to one
     * line of text each. Cells are joined with " | " so a row stays readable.
     */
    private fun extractRows(doc: Document): List<String> {
        val out = LinkedHashSet<String>()

        for (tr in doc.select("tr")) {
            val cells = tr.select("th, td")
            val text = if (cells.isEmpty()) {
                tr.text()
            } else {
                cells.joinToString(" | ") { it.text().trim() }
            }
            val clean = collapse(text)
            if (clean.length >= 8) out.add(clean)
        }

        for (el in doc.select("li, p, div.row, .cause-list-item, article")) {
            if (el.select("tr").isNotEmpty()) continue   // already covered above
            val clean = collapse(el.ownText().ifBlank { el.text() })
            if (clean.length in 8..600) out.add(clean)
        }

        // Nothing structured found — fall back to the raw page text, line by line.
        if (out.isEmpty()) {
            doc.body()?.wholeText()?.split('\n')?.forEach {
                val clean = collapse(it)
                if (clean.length >= 8) out.add(clean)
            }
        }

        return out.toList()
    }

    private fun collapse(s: String): String =
        s.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    private fun detectListDate(doc: Document): String {
        val head = collapse(doc.title() + " " + (doc.body()?.text()?.take(1200) ?: ""))
        return detectDateIn(head)
    }

    private fun detectDateIn(text: String): String {
        for (p in DATE_PATTERNS) {
            val m = p.matcher(text)
            if (m.find()) return m.group().trim()
        }
        return ""
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        private val DATE_PATTERNS = listOf(
            Pattern.compile("\\b\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}\\b"),
            Pattern.compile(
                "\\b\\d{1,2}\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{4}\\b",
                Pattern.CASE_INSENSITIVE
            ),
            Pattern.compile(
                "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+\\d{1,2},?\\s+\\d{4}\\b",
                Pattern.CASE_INSENSITIVE
            )
        )

        fun shortLabel(url: String): String = try {
            URL(url).host.lowercase(Locale.ENGLISH)
        } catch (_: Exception) {
            url.take(40)
        }
    }
}
