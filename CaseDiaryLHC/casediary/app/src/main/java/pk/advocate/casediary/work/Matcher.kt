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
     *  stopwords and honorifics are dropped, but nothing else is filtered by length. */
    private fun pendingTokens(title: String): List<String> =
        normalize(title).split(' ').filter { it.length >= 3 && it !in STOPWORDS }

    /** How many of a fuzzy probe's tokens are allowed to miss on a row. */
    private fun allowedMisses(n: Int): Int = when {
        n <= 2 -> 0
        n <= 4 -> 1
        n <= 7 -> 2
        else -> 3
    }

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
        val key: String,
        /** Pending-file probes tolerate a few missing tokens; everything else needs all of them. */
        val fuzzy: Boolean = false,
        /** For fuzzy probes: this title's own rare words, which must always be present. */
        val mustMatch: Set<String> = emptySet()
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

        // Words like "Khan", "Chairman" or "NADRA" repeat across dozens of pending
        // titles — tolerating a miss on those is fine, but a title must never match
        // purely on words it shares with every other file. Each title's own rare
        // words (appearing in at most 2 pending titles) must always be present;
        // only the common words it shares with many other titles may be missed.
        val freq = HashMap<String, Int>()
        for (pf in pending) {
            for (tok in pendingTokens(pf.title).toHashSet()) freq[tok] = (freq[tok] ?: 0) + 1
        }
        for (pf in pending) {
            val toks = pendingTokens(pf.title)
            if (toks.isEmpty()) continue
            var mustMatch = toks.filter { (freq[it] ?: 0) <= 2 }.toHashSet()
            if (mustMatch.isEmpty()) mustMatch = hashSetOf(toks[0])
            out.add(Probe(pf.title, WatchTerm.KIND_PENDING, 0L, pf.id, toks, "pending:${pf.id}", fuzzy = true, mustMatch = mustMatch))
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

    /** Non-fuzzy probes need every token; fuzzy (pending-file) probes tolerate a few misses
     *  of their common words, but never of their distinctive (mustMatch) words. */
    private fun probeMatches(norm: String, rowTokens: Set<String>, p: Probe): Boolean {
        var hitCount = 0
        for (t in p.tokens) {
            if (tokenPresent(norm, rowTokens, t)) hitCount++
        }
        val misses = p.tokens.size - hitCount
        if (!p.fuzzy) return misses == 0
        if (misses > allowedMisses(p.tokens.size)) return false
        return p.mustMatch.all { tokenPresent(norm, rowTokens, it) }
    }

    /** Convenience for one-off checks and tests; compiles then matches. */
    fun findHits(row: String, terms: List<WatchTerm>, cases: List<Case>): List<Hit> =
        findHits(row, compile(terms, cases))

    private val OTHER_BENCH = Regex("\\b(bahawalpur|multan|rawalpindi)\\b", RegexOption.IGNORE_CASE)

    /**
     * Circuit-bench listings (Bahawalpur / Multan / Rawalpindi) are printed in
     * the same cause list as Principal Seat (Lahore) cases. Callers can skip
     * them via [pk.advocate.casediary.util.Prefs.principalSeatOnly] — on by
     * default, since most practices here only run at Lahore.
     */
    fun isOtherBench(row: String): Boolean = OTHER_BENCH.containsMatchIn(row)

    /**
     * A C.M./C.M.A./Crl.M.A. row is a miscellaneous application filed IN a
     * main case — the cause list often prints it as its own line, right
     * alongside (or instead of) the main case's own line. It is the same
     * matter, so callers (see [pk.advocate.casediary.work.CauseListScraper])
     * fold it back into the main case instead of listing it separately.
     */
    private const val CASE_TYPE_ALT =
        "W\\.?\\s*P\\.?|Crl\\.?\\s*Misc\\.?|C\\.?\\s*R\\.?|R\\.?\\s*F\\.?\\s*A\\.?|" +
            "I\\.?\\s*C\\.?\\s*A\\.?|F\\.?\\s*A\\.?\\s*O\\.?|Crl\\.?\\s*A\\.?|C\\.?\\s*A\\.?"
    private val CM_LEAD_RE = Regex(
        "^\\s*(?:\\d+\\s*[|.]?\\s*)?(?:C\\.?\\s*M\\.?\\s*A?\\.?|Crl\\.?\\s*M\\.?\\s*A\\.?)\\s*(?:No\\.?)?\\s*\\d+\\s*/\\s*\\d{2,4}\\b",
        RegexOption.IGNORE_CASE
    )
    private val OWN_REF_RE = Regex("\\b($CASE_TYPE_ALT)\\s*(?:No\\.?)?\\s*(\\d+)\\s*/\\s*(\\d{2,4})\\b", RegexOption.IGNORE_CASE)
    private val CM_PARENT_RE = Regex("\\bin\\s+($CASE_TYPE_ALT)\\s*(?:No\\.?)?\\s*(\\d+)\\s*/\\s*(\\d{2,4})\\b", RegexOption.IGNORE_CASE)

    private fun canonicalCaseRef(m: MatchResult): String {
        val type = m.groupValues[1].filter { it.isLetter() }.uppercase(Locale.ENGLISH)
        val no = m.groupValues[2].trimStart('0').ifEmpty { "0" }
        val yr = m.groupValues[3].let { if (it.length == 2) "20$it" else it }
        return "$type:$no:$yr"
    }

    fun isCmRow(row: String): Boolean = CM_LEAD_RE.containsMatchIn(row)
    fun ownCaseRef(row: String): String? = OWN_REF_RE.find(row)?.let { canonicalCaseRef(it) }
    fun cmParentRef(row: String): String? = CM_PARENT_RE.find(row)?.let { canonicalCaseRef(it) }

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
