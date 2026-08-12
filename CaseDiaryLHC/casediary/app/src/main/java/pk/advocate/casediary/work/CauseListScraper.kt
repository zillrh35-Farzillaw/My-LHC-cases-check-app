package pk.advocate.casediary.work

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.Fixture
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
        val lines: List<String>
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
        val errors = ArrayList<String>()
        val lines = ArrayList<String>()

        for (src in sources) {
            try {
                val html = fetch(src.url)
                val r = scanHtml(html, src.label, src.url)
                newHits += r.newHits
                totalHits += r.totalHits
                rows += r.rowsScanned
                lines.addAll(r.lines)
            } catch (e: Exception) {
                errors.add("${src.label}: ${e.message ?: e.javaClass.simpleName}")
            }
        }

        db.pruneFixtures()
        return Result(newHits, totalHits, rows, errors, lines)
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
        rows: List<String>,
        listDate: String,
        sourceLabel: String,
        sourceUrl: String
    ): Result {
        val terms = db.listWatchTerms(onlyEnabled = true)
        val cases = db.listCases(null, null)
        if (terms.isEmpty() && cases.isEmpty()) {
            return Result(
                0, 0, rows.size,
                listOf("Nothing to look for yet — add a keyword or a case first"),
                emptyList()
            )
        }

        // Tokenise the terms once, not once per row.
        val probes = Matcher.compile(terms, cases)

        var newHits = 0
        var totalHits = 0
        val lines = ArrayList<String>()
        val found = ArrayList<Fixture>()
        val now = System.currentTimeMillis()

        for (row in rows) {
            val all = Matcher.findHits(row, probes)
            if (all.isEmpty()) continue
            // A row that is one of the user's own cases belongs under "My cases"
            // only — a keyword matching the same line must not duplicate it.
            val mine = all.filter { it.caseId != 0L }
            val hits = if (mine.isNotEmpty()) mine else all
            val clipped = if (row.length > 400) row.substring(0, 400) + "…" else row
            for (h in hits) {
                totalHits++
                found.add(
                    Fixture(
                        hash = Matcher.hashOf(sourceUrl, listDate, clipped, h.term),
                        caseId = h.caseId,
                        sourceLabel = sourceLabel,
                        sourceUrl = sourceUrl,
                        listDate = listDate,
                        raw = clipped,
                        matchedTerm = h.term,
                        matchedKind = h.kind,
                        foundAt = now,
                        seen = false
                    )
                )
            }
        }

        // One transaction for the whole page rather than one per row.
        val inserted = db.insertFixtures(found)
        for (f in inserted) {
            newHits++
            lines.add(
                "${f.matchedTerm} — $sourceLabel" +
                    if (listDate.isNotBlank()) " ($listDate)" else ""
            )
        }

        return Result(newHits, totalHits, rows.size, emptyList(), lines)
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
