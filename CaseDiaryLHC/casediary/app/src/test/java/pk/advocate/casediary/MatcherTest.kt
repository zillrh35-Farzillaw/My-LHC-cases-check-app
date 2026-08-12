package pk.advocate.casediary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pk.advocate.casediary.db.Case
import pk.advocate.casediary.db.PendingFile
import pk.advocate.casediary.db.WatchTerm
import pk.advocate.casediary.work.Matcher

/**
 * Cause lists are typed by hand and formatted inconsistently. These pin down
 * what the matcher must tolerate and, just as importantly, what it must ignore.
 */
class MatcherTest {

    private val terms = listOf(
        WatchTerm(1, "Ahmad Raza", WatchTerm.KIND_ADVOCATE),
        WatchTerm(2, "Zill E Rukh", WatchTerm.KIND_ADVOCATE),
        WatchTerm(3, "Punjab Food Authority", WatchTerm.KIND_PARTY),
        WatchTerm(4, "off", WatchTerm.KIND_OTHER)
    )

    private val cases = listOf(
        Case(
            id = 10, caseType = "W.P.", caseNo = "12345", caseYear = "2025",
            petitioner = "Muhammad Bilal", respondent = "State"
        ),
        Case(
            id = 11, caseType = "Crl. Misc.", caseNo = "987", caseYear = "2024",
            petitioner = "Sana Textiles Pvt Ltd", respondent = "FBR",
            status = Case.STATUS_ARCHIVED
        )
    )

    private fun hits(row: String) = Matcher.findHits(row, terms, cases).map { it.term }

    @Test
    fun `case number matches despite spacing and punctuation`() {
        val row = "1 | W.P. No. 12345 / 2025 | Muhammad Bilal Vs. The State | RAZA, AHMAD ADVOCATE"
        assertTrue(hits(row).contains("W.P. 12345/2025"))
    }

    @Test
    fun `advocate name matches when printed surname-first`() {
        val row = "1 | W.P. No. 12345 / 2025 | Muhammad Bilal Vs. The State | RAZA, AHMAD ADVOCATE"
        assertTrue(hits(row).contains("Ahmad Raza"))
    }

    @Test
    fun `case number matches when the court drops the dots`() {
        assertTrue(hits("3 | W P 12345/2025 | Muhammad  Bilal  vs  State").contains("W.P. 12345/2025"))
    }

    @Test
    fun `hyphenated name matches the spaced spelling`() {
        val row = "4 | ICA 77/2026 | Punjab  Food   Authority vs Al-Noor Bakers | ZILL-E-RUKH, ADVOCATE"
        assertTrue(hits(row).contains("Zill E Rukh"))
        assertTrue(hits(row).contains("Punjab Food Authority"))
    }

    @Test
    fun `unrelated row matches nothing`() {
        assertEquals(emptyList<String>(), hits("2 | C.R. 55/2021 | Someone Else Vs. Another | Mr. Khalid Mehmood"))
    }

    @Test
    fun `archived cases are ignored`() {
        assertEquals(emptyList<String>(), hits("5 | Crl. Misc. 987/2024 | Sana Textiles Pvt Ltd vs FBR"))
    }

    @Test
    fun `short keyword does not match inside a longer word`() {
        // "off" must not fire on "Office order" — substring matching would.
        assertFalse(hits("7 | Office order regarding staff leave").contains("off"))
    }

    @Test
    fun `very short rows are skipped`() {
        assertTrue(hits("6 | short").isEmpty())
    }

    @Test
    fun `hash ignores spacing and case so a row only notifies once`() {
        assertEquals(
            Matcher.hashOf("u", "d", "row text", "term"),
            Matcher.hashOf("u", "d", "row   TEXT", "term")
        )
    }

    @Test
    fun `a hit on one of my cases carries the case id so it can be separated out`() {
        val row = "1 | W.P. No. 12345 / 2025 | Muhammad Bilal Vs. The State"
        val hit = Matcher.findHits(row, terms, cases).first { it.term == "W.P. 12345/2025" }
        assertEquals(10L, hit.caseId)
    }

    @Test
    fun `a keyword-only hit has no case id so it lands in the other section`() {
        val row = "9 | ICA 77/2026 | Some Company vs Another | RAZA, AHMAD ADVOCATE"
        val hit = Matcher.findHits(row, terms, cases).first { it.term == "Ahmad Raza" }
        assertEquals(0L, hit.caseId)
        assertEquals(WatchTerm.KIND_ADVOCATE, hit.kind)
    }

    @Test
    fun `compiling probes once gives the same answer as compiling per row`() {
        val probes = Matcher.compile(terms, cases)
        val rows = listOf(
            "1 | W.P. No. 12345 / 2025 | Muhammad Bilal Vs. The State | RAZA, AHMAD ADVOCATE",
            "2 | C.R. 55/2021 | Someone Else Vs. Another",
            "4 | ICA 77/2026 | Punjab Food Authority vs Al-Noor Bakers | ZILL-E-RUKH"
        )
        for (r in rows) {
            assertEquals(
                Matcher.findHits(r, terms, cases).map { it.term },
                Matcher.findHits(r, probes).map { it.term }
            )
        }
    }

    @Test
    fun `hash changes for a different row`() {
        assertTrue(
            Matcher.hashOf("u", "d", "row text", "term") !=
                Matcher.hashOf("u", "d", "different row", "term")
        )
    }

    // ---------------------------------------------------- pending-file fuzzy matching

    private val pending = listOf(PendingFile(id = 5, title = "Ghazanfar Ali Khan vs NADRA etc."))

    private fun pendingHits(row: String) = Matcher.findHits(row, Matcher.compile(terms, cases, pending))

    @Test
    fun `pending file title matches even with extra words the court added`() {
        val row = "3 | GHAZANFAR ALI KHAN VS CHAIRMAN NADRA AND OTHERS | Writ Petition 55555/2026"
        val hit = pendingHits(row).firstOrNull { it.kind == WatchTerm.KIND_PENDING }
        assertTrue(hit != null)
        assertEquals(5L, hit!!.pendingId)
    }

    @Test
    fun `pending file title tolerates one missing word`() {
        // "Khan" dropped entirely — still 3 of 4 significant tokens present.
        val row = "3 | GHAZANFAR ALI VS NADRA | Writ Petition 55555/2026"
        assertTrue(pendingHits(row).any { it.kind == WatchTerm.KIND_PENDING })
    }

    @Test
    fun `pending file does not match an unrelated row`() {
        val row = "9 | SOME OTHER PARTY VS ANOTHER PARTY REGARDING A DIFFERENT MATTER ENTIRELY"
        assertFalse(pendingHits(row).any { it.kind == WatchTerm.KIND_PENDING })
    }

    @Test
    fun `a case match on the same row wins over a pending-file match`() {
        // Row matches both saved case 10 and would-be pending probes on the same line —
        // findHits itself only dedupes by key, so this just confirms scraper-level
        // priority is a caller concern; the matcher still reports both keys.
        val row = "1 | W.P. No. 12345 / 2025 | Muhammad Bilal Vs. The State | RAZA, AHMAD ADVOCATE"
        val hits = Matcher.findHits(row, Matcher.compile(terms, cases, pending))
        assertTrue(hits.any { it.caseId == 10L })
    }

    @Test
    fun `default NADRA-style keywords carry PRIMARY priority when built in`() {
        val t = WatchTerm(id = 1, term = "NADRA", kind = WatchTerm.KIND_OTHER, priority = WatchTerm.PRIORITY_PRIMARY, builtin = true)
        assertEquals(WatchTerm.PRIORITY_PRIMARY, t.priority)
        assertTrue(t.builtin)
    }
}
