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
        val fuzzy: Boolean = false
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

        for (pf in pending) {
            val toks = pendingTokens(pf.title)
            if (toks.isNotEmpty()) {
                out.add(Probe(pf.title, WatchTerm.KIND_PENDING, 0L, pf.id, toks, "pending:${pf.id}", fuzzy = true))
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

    /** Non-fuzzy probes need every token; fuzzy (pending-file) probes tolerate a few misses. */
    private fun probeMatches(norm: String, rowTokens: Set<String>, p: Probe): Boolean {
        var hitCount = 0
        for (t in p.tokens) {
            if (rowTokens.contains(t) || (t.length >= 6 && norm.contains(t))) hitCount++
        }
        val misses = p.tokens.size - hitCount
        return if (p.fuzzy) misses <= allowedMisses(p.tokens.size) else misses == 0
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
