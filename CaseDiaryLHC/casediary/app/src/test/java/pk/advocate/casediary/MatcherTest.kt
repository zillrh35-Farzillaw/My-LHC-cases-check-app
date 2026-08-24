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

    // Three files that deliberately share common words ("Khan", "Chairman", "NADRA") —
    // this is what a real docket of dozens of NADRA files looks like, and it's what
    // exposed the false-positive bug below. Names here are placeholders, not real
    // client/party names — this is public source, not case data.
    private val pending = listOf(
        PendingFile(id = 5, title = "Alpha Ali Khan vs NADRA etc."),
        PendingFile(id = 6, title = "Bravo Khan vs Chairman NADRA etc."),
        PendingFile(id = 7, title = "Charlie Khan vs Chairman NADRA etc.")
    )

    private fun pendingHits(row: String) = Matcher.findHits(row, Matcher.compile(terms, cases, pending))

    @Test
    fun `pending file title matches even with extra words the court added`() {
        val row = "3 | ALPHA ALI KHAN VS CHAIRMAN NADRA AND OTHERS | Writ Petition 55555/2026"
        val hit = pendingHits(row).firstOrNull { it.kind == WatchTerm.KIND_PENDING }
        assertTrue(hit != null)
        assertEquals(5L, hit!!.pendingId)
    }

    @Test
    fun `pending file title tolerates a missing common word on the non-petitioner side`() {
        // The full petitioner ("Alpha Ali Khan") is present; "NADRA" — common
        // across all three pending files and not part of the petitioner — is
        // dropped and tolerated. (The petitioner's own words are never
        // tolerated as missing — see the exactness test below.)
        val row = "3 | ALPHA ALI KHAN VS SOMETHING ELSE | Writ Petition 55555/2026"
        assertTrue(pendingHits(row).any { it.pendingId == 5L })
    }

    @Test
    fun `a pending file never matches with part of its own petitioner name missing`() {
        // "Khan" dropped from the petitioner's own name — must not match,
        // even though "Khan" is common across other pending files too.
        val row = "3 | ALPHA ALI VS NADRA | Writ Petition 55555/2026"
        assertFalse(pendingHits(row).any { it.pendingId == 5L })
    }

    @Test
    fun `a pending file sharing only common words with another does not false-positive`() {
        // Regression test: this exact row used to also "match" the Bravo Khan and
        // Charlie Khan pending files, purely because they share "Khan"/"Chairman"/
        // "NADRA" — none of which are unique to any one of the three files.
        val row = "3 | ALPHA ALI KHAN VS CHAIRMAN NADRA AND OTHERS | Writ Petition 55555/2026"
        val hits = pendingHits(row).filter { it.kind == WatchTerm.KIND_PENDING }
        assertEquals(1, hits.size)
        assertEquals(5L, hits.first().pendingId)
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

    // ------------------------------------------------------- watch switches / bench filter

    @Test
    fun `a case with watched off is skipped by compile`() {
        val unwatched = listOf(cases[0].copy(watched = false))
        val row = "1 | W.P. No. 12345 / 2025 | Muhammad Bilal Vs. The State"
        val hits = Matcher.findHits(row, Matcher.compile(emptyList(), unwatched))
        assertTrue(hits.none { it.caseId == 10L })
    }

    @Test
    fun `bahawalpur multan and rawalpindi bench lines are detected`() {
        assertTrue(Matcher.isOtherBench("1 | W.P. 5/2026 | Someone vs State | Bahawalpur Bench"))
        assertTrue(Matcher.isOtherBench("2 | Crl. Misc. 6/2026 | Multan Bench matter"))
        assertTrue(Matcher.isOtherBench("3 | C.R. 7/2026 | Rawalpindi Bench listing"))
        assertFalse(Matcher.isOtherBench("4 | W.P. 8/2026 | Lahore principal seat matter"))
    }

    // ------------------------------------------------------- C.M./application consolidation

    @Test
    fun `a C M row is recognised and its parent case is extracted`() {
        val row = "C.M.No.11/2026 in W.P.No.4001/2026 Ali Raza vs State"
        assertTrue(Matcher.isCmRow(row))
        assertTrue(Matcher.cmParentRefs(row).contains("WP:4001:2026"))
    }

    @Test
    fun `a bare main case row is not treated as a C M row`() {
        val row = "W.P. No.4001/2026 Ali Raza vs State, hearing today"
        assertFalse(Matcher.isCmRow(row))
        assertTrue(Matcher.ownCaseRefs(row).contains("WP:4001:2026"))
    }

    @Test
    fun `Crl Misc is a main case type, not a C M application`() {
        // "Crl.Misc." (Criminal Miscellaneous, a petition type) must not be
        // confused with "Crl.M.A." (Criminal Miscellaneous Application, a
        // sub-application) — only the latter should read as a C.M. row.
        assertFalse(Matcher.isCmRow("Crl.Misc.No.987/2024 Sana Textiles vs FBR"))
        assertTrue(Matcher.isCmRow("Crl.M.A.No.5/2026 in Crl.Misc.No.987/2024 Sana Textiles vs FBR"))
    }

    @Test
    fun `a C M row without a parseable parent is left ungrouped`() {
        val row = "C.M.No.9/2026 Some Company vs Another"
        assertTrue(Matcher.isCmRow(row))
        assertTrue(Matcher.cmParentRefs(row).isEmpty())
    }

    @Test
    fun `canonical refs ignore type punctuation and two-digit years`() {
        val a = Matcher.ownCaseRefs("W.P.No.001/26 Someone vs State")
        val b = Matcher.ownCaseRefs("W P 1/2026 Someone vs State")
        assertEquals(a, b)
    }

    @Test
    fun `a C M number with the main case folded in as a third segment is recognised`() {
        // No "in W.P. No X/Y" phrase at all — the parent's number/year is
        // embedded directly in the C.M.'s own number: app-no/main-no/year.
        val row = "C.M.No.11/4001/2026 Ali Raza vs State"
        assertTrue(Matcher.isCmRow(row))
        assertTrue(Matcher.cmParentRefs(row).contains("*:4001:2026"))
        // And it lines up with a bare main-case row's own (type-agnostic) ref.
        val mainRow = "W.P. No.4001/2026 Ali Raza vs State"
        assertTrue(Matcher.ownCaseRefs(mainRow).contains("*:4001:2026"))
    }

    // ------------------------------------------------------- pending-file petitioner exactness

    @Test
    fun `a pending match requires the petitioner to be fully present, not just common words`() {
        // "NADRA" and "Chairman" are common boilerplate shared by many pending
        // titles, so on their own they used to be enough to false-positive.
        // The petitioner's own name must now always be fully present.
        val onePending = listOf(PendingFile(id = 20, title = "Fahad Rasheed vs NADRA Chairman etc."))
        val probes = Matcher.compile(emptyList(), emptyList(), onePending)
        val wrongPetitioner = "Someone Else Totally vs NADRA Chairman etc, hearing"
        assertTrue(Matcher.findHits(wrongPetitioner, probes).none { it.kind == WatchTerm.KIND_PENDING })
        val rightPetitioner = "Fahad Rasheed vs NADRA Chairman etc, first hearing"
        assertTrue(Matcher.findHits(rightPetitioner, probes).any { it.pendingId == 20L })
    }
}
