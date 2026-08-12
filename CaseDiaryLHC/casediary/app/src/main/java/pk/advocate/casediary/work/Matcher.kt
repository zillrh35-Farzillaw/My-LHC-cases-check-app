package pk.advocate.casediary.work

import pk.advocate.casediary.db.Case
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

    data class Hit(val term: String, val kind: String, val caseId: Long)

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
        val tokens: List<String>,
        /** Key used to collapse duplicate hits on the same row. */
        val key: String
    ) {
        /** Long tokens may also match inside a run-together word. */
        val longTokens: List<String> = tokens.filter { it.length >= 6 }
    }

    /** Build the probe list once, before walking the rows. */
    fun compile(terms: List<WatchTerm>, cases: List<Case>): List<Probe> {
        val out = ArrayList<Probe>(terms.size + cases.size * 2)

        for (t in terms) {
            if (!t.enabled) continue
            val toks = usableTokens(t.term) ?: continue
            out.add(Probe(t.term, t.kind, 0L, toks, normalize(t.term)))
        }

        for (c in cases) {
            if (c.status == Case.STATUS_ARCHIVED) continue
            val ref = c.caseRef()

            // A case number plus its year is the strongest, most precise signal,
            // so it goes in first and wins the de-duplication.
            if (c.caseNo.isNotBlank() && c.caseYear.isNotBlank()) {
                usableTokens("${c.caseNo} ${c.caseYear}")?.let {
                    out.add(Probe(ref, WatchTerm.KIND_CASE, c.id, it, normalize(ref)))
                }
            }

            val party = c.petitioner.trim()
            if (party.length >= 4) {
                usableTokens(party)?.let {
                    val label = ref.ifBlank { party }
                    out.add(Probe(label, WatchTerm.KIND_PARTY, c.id, it, normalize(label)))
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
            map.putIfAbsent(p.key, Hit(p.label, p.kind, p.caseId))
        }
        return hits?.values?.toList() ?: emptyList()
    }

    private fun probeMatches(norm: String, rowTokens: Set<String>, p: Probe): Boolean {
        for (t in p.tokens) {
            if (rowTokens.contains(t)) continue
            if (t.length >= 6 && norm.contains(t)) continue
            return false
        }
        return true
    }

    /** Convenience for one-off checks and tests; compiles then matches. */
    fun findHits(row: String, terms: List<WatchTerm>, cases: List<Case>): List<Hit> =
        findHits(row, compile(terms, cases))

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
