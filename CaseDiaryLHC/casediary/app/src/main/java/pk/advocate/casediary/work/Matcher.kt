package pk.advocate.casediary.work

import pk.advocate.casediary.db.Case
import pk.advocate.casediary.db.PendingFile
import pk.advocate.casediary.db.WatchTerm
import java.security.MessageDigest
import java.util.Locale

/**
 * Decides whether a line of cause-list text is about one of the user's cases.
 *
 * Deliberately fuzzy: court lists are inconsistent about punctuation, spacing,
 * honorifics and the order of a party's names. A term matches when *every*
 * token in it appears somewhere in the row, so "Ahmad Raza" still matches
 * "RAZA, AHMAD  ADVOCATE" and "W.P. 12345/2025" matches "W P No. 12345 / 2025".
 */
object Matcher {

    data class Hit(val term: String, val kind: String, val caseId: Long, val pendingId: Long = 0L)

    private val STOPWORDS = setOf("vs", "etc", "and", "the", "of", "in", "re", "mst")

    /** Pending-file titles keep more of their words than a keyword would —
     *  stopwords and honorifics are dropped, but every remaining word must
     *  actually appear in the row; there is no tolerance for a missing one. */
    private fun pendingTokens(title: String): List<String> =
        normalize(title).split(' ').filter { it.length >= 3 && it !in STOPWORDS }

    /** Collapse punctuation and whitespace so two spellings compare equal. */
    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input.lowercase(Locale.ENGLISH)) {
            if (ch.isLetterOrDigit()) sb.append(ch) else sb.append(' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun tokens(term: String): List<String> =
        normalize(term).split(' ').filter { it.isNotBlank() }

    /**
     * Tokens shorter than this are ignored unless the whole term is short,
     * so single stray letters don't match every row on the page.
     */
    private fun meaningfulTokens(term: String): List<String> {
        val all = tokens(term)
        val long = all.filter { it.length >= 3 || it.any { c -> c.isDigit() } }
        return if (long.isEmpty()) all else long
    }

    /**
     * Word-level, not substring: "Ali" must not match "Salim". Long tokens
     * (6+ characters) additionally allow a substring hit, so a court that
     * prints "AlNoorBakers" as one word is still caught.
     */
    private fun matches(normalizedRow: String, rowTokens: Set<String>, term: String): Boolean {
        val toks = meaningfulTokens(term)
        if (toks.isEmpty()) return false
        // A single very short token is too weak to trust on its own.
        if (toks.size == 1 && toks[0].length < 3) return false
        return toks.all { t ->
            rowTokens.contains(t) || (t.length >= 6 && normalizedRow.contains(t))
        }
    }

    /** Convenience overload for one-off checks. */
    fun rowMatches(normalizedRow: String, term: String): Boolean =
        matches(normalizedRow, tokenSet(normalizedRow), term)

    private fun tokenSet(normalizedRow: String): Set<String> =
        normalizedRow.split(' ').filter { it.isNotBlank() }.toHashSet()

    /**
     * A watch term with its tokens already worked out.
     *
     * Tokenising is the expensive half of matching, and the terms do not change
     * while a scan runs — so it happens once per scan instead of once per
     * (row × term). On a 2,000-row list with 100 saved cases that is the
     * difference between ~200,000 tokenisations and ~200.
     */
    class Probe(
        val label: String,
        val kind: String,
        val caseId: Long,
        val pendingId: Long,
        val tokens: List<String>,
        /** Key used to collapse duplicate hits on the same row. */
        val key: String
    ) {
        /** Long tokens may also match inside a run-together word. */
        val longTokens: List<String> = tokens.filter { it.length >= 6 }
    }

    /** Build the probe list once, before walking the rows. */
    fun compile(
        terms: List<WatchTerm>,
        cases: List<Case>,
        pending: List<PendingFile> = emptyList()
    ): List<Probe> {
        val out = ArrayList<Probe>(terms.size + cases.size * 2 + pending.size)

        for (t in terms) {
            if (!t.enabled) continue
            val toks = usableTokens(t.term) ?: continue
            out.add(Probe(t.term, t.kind, 0L, 0L, toks, "key:" + normalize(t.term)))
        }

        for (c in cases) {
            if (c.status == Case.STATUS_ARCHIVED) continue
            if (!c.watched) continue
            val ref = c.caseRef()

            // A case number plus its year is the strongest, most precise signal,
            // so it goes in first and wins the de-duplication.
            if (c.caseNo.isNotBlank() && c.caseYear.isNotBlank()) {
                usableTokens("${c.caseNo} ${c.caseYear}")?.let {
                    out.add(Probe(ref, WatchTerm.KIND_CASE, c.id, 0L, it, "case:" + normalize(ref)))
                }
            }

            val party = c.petitioner.trim()
            if (party.length >= 4) {
                usableTokens(party)?.let {
                    val label = ref.ifBlank { party }
                    out.add(Probe(label, WatchTerm.KIND_PARTY, c.id, 0L, it, "party:" + normalize(label)))
                }
            }
        }

        // No fuzzy tolerance: a pending file only lists as a hit when every one
        // of its (stopword-stripped) title words is actually present in the
        // row — no "possible match" guesswork, an exact title match only.
        for (pf in pending) {
            val toks = pendingTokens(pf.title)
            if (toks.isEmpty()) continue
            out.add(Probe(pf.title, WatchTerm.KIND_PENDING, 0L, pf.id, toks, "pending:${pf.id}"))

            // Once a pending file's case number is known (registered but not yet
            // fixed), match it exactly too — alongside the exact title match.
            if (pf.caseNo.isNotBlank() && pf.caseYear.isNotBlank()) {
                usableTokens("${pf.caseNo} ${pf.caseYear}")?.let {
                    out.add(Probe(pf.caseRef(), WatchTerm.KIND_CASE, 0L, pf.id, it, "pendingcase:${pf.id}"))
                }
            }
        }

        return out
    }

    /** Tokens for a term, or null when the term is too weak to match on. */
    private fun usableTokens(term: String): List<String>? {
        val toks = meaningfulTokens(term)
        if (toks.isEmpty()) return null
        if (toks.size == 1 && toks[0].length < 3) return null
        return toks
    }

    /** Match one row against pre-compiled probes. This is the hot loop. */
    fun findHits(row: String, probes: List<Probe>): List<Hit> {
        if (probes.isEmpty()) return emptyList()
        val norm = normalize(row)
        if (norm.length < 6) return emptyList()
        val rowTokens = tokenSet(norm)

        var hits: LinkedHashMap<String, Hit>? = null
        for (p in probes) {
            if (!probeMatches(norm, rowTokens, p)) continue
            val map = hits ?: LinkedHashMap<String, Hit>(4).also { hits = it }
            map.putIfAbsent(p.key, Hit(p.label, p.kind, p.caseId, p.pendingId))
        }
        return hits?.values?.toList() ?: emptyList()
    }

    private fun tokenPresent(norm: String, rowTokens: Set<String>, t: String): Boolean =
        rowTokens.contains(t) || (t.length >= 6 && norm.contains(t))

    /** Every probe (keywords, case numbers, saved-case parties, pending files)
     *  requires every one of its tokens to be present — an exact match only. */
    private fun probeMatches(norm: String, rowTokens: Set<String>, p: Probe): Boolean =
        p.tokens.all { tokenPresent(norm, rowTokens, it) }

    /** Convenience for one-off checks and tests; compiles then matches. */
    fun findHits(row: String, terms: List<WatchTerm>, cases: List<Case>): List<Hit> =
        findHits(row, compile(terms, cases))

    private val OTHER_BENCH = Regex("\\b(bahawalpur|multan|rawalpindi)\\b", RegexOption.IGNORE_CASE)
    /** Circuit-bench (Bahawalpur/Multan/Rawalpindi) case numbers are bracketed
     *  and HYPHEN-delimited — "[6458-B-26]" or "[10060-26]" — confirmed against
     *  a real LHC export; Principal Seat (Lahore) rows never use brackets.
     *  Also used for own/parent case-ref extraction — see [BRACKET_CORE]. */
    private const val BRACKET_CORE = "\\[(\\d{2,7})(?:-[A-Za-z]{1,4})?-(\\d{2,4})\\]"
    private const val BRACKET_CORE_NC = "\\[\\d{2,7}(?:-[A-Za-z]{1,4})?-\\d{2,4}\\]"
    private val BRACKETED_CASE_RE = Regex(BRACKET_CORE)

    /**
     * Circuit-bench listings (Bahawalpur / Multan / Rawalpindi) are printed in
     * the same cause list as Principal Seat (Lahore) cases. Callers can skip
     * them via [pk.advocate.casediary.util.Prefs.principalSeatOnly] — on by
     * default, since most practices here only run at Lahore.
     *
     * Circuit-bench rows print their case number wrapped in square brackets,
     * e.g. "[W.P.1234/26]" — Principal Seat (Lahore) rows don't. That's a far
     * more reliable signal than the bench name showing up in the text, so
     * it's checked first.
     */
    fun isOtherBench(row: String): Boolean =
        BRACKETED_CASE_RE.containsMatchIn(row) || OTHER_BENCH.containsMatchIn(row)

    /**
     * A C.M./C.M.A./Crl.M.A. row is a miscellaneous application filed IN a
     * main case — the cause list often prints it as its own line, right
     * alongside (or instead of) the main case's own line. It is the same
     * matter, so callers (see [pk.advocate.casediary.work.CauseListScraper])
     * fold it back into the main case instead of listing it separately.
     *
     * Confirmed against a real LHC "Urgent Cause List" export, the court
     * prints a C.M./application's parent case three different ways depending
     * on bench:
     * - Lahore Principal Seat main rows print a BARE "number/year" with no
     *   case-type text next to it at all (the type lives in a separate
     *   "category" column) — e.g. Case# "48064/26" for the main case, and
     *   its C.M. as a third segment folded into the C.M.'s own number,
     *   "CM/1/48064/26" (application-no/main-no/year, no "in" at all). The
     *   C.M. row is also prefixed by a repeated "and"/category before "CM"
     *   ever appears, so detecting it must not require "CM" at the very
     *   start of the row.
     * - Circuit-bench rows (Multan/Bahawalpur/Rawalpindi) use bracketed,
     *   HYPHEN-delimited numbers instead of slashes — "[6458-B-26]" or
     *   "[10060-26]" — and spell the parent out as "[childNo] in [parentNo]".
     *   A page-to-text copy (or a WebView's innerText) can split that "in"
     *   across its own line when the table cell itself wraps, so the row
     *   list is re-joined (see [pk.advocate.casediary.work.CauseListScraper])
     *   before any of this runs.
     */
    private const val CASE_TYPE_ALT =
        "W\\.?\\s*P\\.?|Crl\\.?\\s*Misc\\.?|C\\.?\\s*R\\.?|R\\.?\\s*F\\.?\\s*A\\.?|" +
            "I\\.?\\s*C\\.?\\s*A\\.?|F\\.?\\s*A\\.?\\s*O\\.?|Crl\\.?\\s*A\\.?|C\\.?\\s*A\\.?"
    private const val CM_DESIGNATION = "(?:C\\.?\\s*M\\.?\\s*A?\\.?|Crl\\.?\\s*M\\.?\\s*A\\.?)"
    /** The gap between the designation and its number varies: "C.M.No.11/2026"
     *  (word "No." then number), but the real, confirmed Lahore form is simply
     *  "CM/1/48064/26" — no "No.", the designation is followed immediately by
     *  a slash. This gap allows either. */
    private const val CM_GAP = "\\s*/?\\s*(?:No\\.?)?\\s*/?\\s*"
    /** Not anchored to the start of the row: real rows prefix the C.M.'s own
     *  entry with a repeated "and"/category before the "CM" text ever appears. */
    private val CM_LEAD_RE = Regex(
        "\\b$CM_DESIGNATION$CM_GAP\\d+(?:\\s*/\\s*\\d+){1,2}\\b",
        RegexOption.IGNORE_CASE
    )
    private val OWN_REF_RE = Regex("\\b($CASE_TYPE_ALT)\\s*(?:No\\.?)?\\s*(\\d+)\\s*/\\s*(\\d{2,4})\\b", RegexOption.IGNORE_CASE)
    private val CM_PARENT_RE = Regex("\\bin\\s+($CASE_TYPE_ALT)\\s*(?:No\\.?)?\\s*(\\d+)\\s*/\\s*(\\d{2,4})\\b", RegexOption.IGNORE_CASE)
    private val CM_EMBEDDED_PARENT_RE = Regex(
        "$CM_DESIGNATION$CM_GAP\\d+\\s*/\\s*(\\d+)\\s*/\\s*(\\d{2,4})\\b",
        RegexOption.IGNORE_CASE
    )
    /** Lahore main-case rows with no case-type text next to the number at
     *  all — the fallback once OWN_REF_RE (which requires a type word) comes
     *  up empty. */
    private val BARE_CASE_NO_RE = Regex("\\b(\\d{2,7})\\s*/\\s*(\\d{2,4})\\b")
    /** A circuit-bench C.M./sub-application spells its parent out as
     *  "[childNo] in [parentNo]" instead of folding it into its own number. */
    private val BRACKET_CM_RE = Regex(
        "$BRACKET_CORE_NC\\s*in\\s*$BRACKET_CORE",
        RegexOption.IGNORE_CASE
    )

    private fun canonicalCaseRef(m: MatchResult): String {
        val type = m.groupValues[1].filter { it.isLetter() }.uppercase(Locale.ENGLISH)
        val no = m.groupValues[2].trimStart('0').ifEmpty { "0" }
        val yr = m.groupValues[3].let { if (it.length == 2) "20$it" else it }
        return "$type:$no:$yr"
    }

    /** Type-agnostic identity: used whenever the parent's own case-type text
     *  isn't printed (the embedded C.M.No.<app>/<main>/<year> form, a bare
     *  Lahore number/year, or a circuit-bench bracketed number), so a plain
     *  number+year is the only thing to match on. */
    private fun looseCaseRef(no: String, yr: String): String {
        val n = no.trimStart('0').ifEmpty { "0" }
        val y = if (yr.length == 2) "20$yr" else yr
        return "*:$n:$y"
    }

    data class ParsedCaseRef(val caseType: String, val caseNo: String, val caseYear: String)

    /** Pulls a "W.P. 12345/2026"-shaped case reference out of free text —
     *  used when approving a Diary hit to auto-create a full Case record. */
    fun parseCaseRef(text: String): ParsedCaseRef? {
        OWN_REF_RE.find(text)?.let {
            val yr = it.groupValues[3].let { y -> if (y.length == 2) "20$y" else y }
            return ParsedCaseRef(it.groupValues[1].trim(), it.groupValues[2], yr)
        }
        BARE_CASE_NO_RE.find(text)?.let {
            val yr = it.groupValues[2].let { y -> if (y.length == 2) "20$y" else y }
            return ParsedCaseRef("", it.groupValues[1], yr)
        }
        BRACKETED_CASE_RE.find(text)?.let {
            val yr = it.groupValues[2].let { y -> if (y.length == 2) "20$y" else y }
            return ParsedCaseRef("", it.groupValues[1], yr)
        }
        return null
    }

    fun isCmRow(row: String): Boolean = CM_LEAD_RE.containsMatchIn(row) || BRACKET_CM_RE.containsMatchIn(row)

    fun ownCaseRefs(row: String): List<String> {
        OWN_REF_RE.find(row)?.let { return listOf(canonicalCaseRef(it), looseCaseRef(it.groupValues[2], it.groupValues[3])) }
        BARE_CASE_NO_RE.find(row)?.let { return listOf(looseCaseRef(it.groupValues[1], it.groupValues[2])) }
        BRACKETED_CASE_RE.find(row)?.let { return listOf(looseCaseRef(it.groupValues[1], it.groupValues[2])) }
        return emptyList()
    }

    fun cmParentRefs(row: String): List<String> {
        val out = ArrayList<String>()
        CM_PARENT_RE.find(row)?.let {
            out.add(canonicalCaseRef(it))
            out.add(looseCaseRef(it.groupValues[2], it.groupValues[3]))
        }
        CM_EMBEDDED_PARENT_RE.find(row)?.let {
            out.add(looseCaseRef(it.groupValues[1], it.groupValues[2]))
        }
        BRACKET_CM_RE.find(row)?.let {
            out.add(looseCaseRef(it.groupValues[1], it.groupValues[2]))
        }
        return out
    }

    /** A table cell that wraps across lines (a C.M.'s "childNo" / "in" / "parentNo"
     *  cross-reference) can arrive as separate physical lines from a page-to-text
     *  copy or a WebView's innerText. A line that is *only* the word "in" is never
     *  real case text on its own, so it's stitched back onto its neighbours to
     *  restore the original "in" phrase before anything else runs. */
    fun mergeSplitCmLines(rows: List<String>): List<String> {
        if (rows.none { it.trim().equals("in", ignoreCase = true) }) return rows
        val out = ArrayList<String>(rows.size)
        var i = 0
        while (i < rows.size) {
            val r = rows[i]
            if (r.trim().equals("in", ignoreCase = true) && out.isNotEmpty() && i + 1 < rows.size) {
                out[out.size - 1] = out.last() + " in " + rows[i + 1]
                i += 2
                continue
            }
            out.add(r)
            i++
        }
        return out
    }

    /** Stable identity for a cause-list row so it is only notified once. */
    fun hashOf(vararg parts: String): String {
        val joined = parts.joinToString("|") { normalize(it) }
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(joined.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
