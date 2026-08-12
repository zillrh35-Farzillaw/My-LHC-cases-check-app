package pk.advocate.casediary.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite. No ORM, no annotation processing — everything the app stores
 * lives in this one local file and works with the phone in flight mode.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                case_type TEXT NOT NULL DEFAULT '',
                case_no TEXT NOT NULL DEFAULT '',
                case_year TEXT NOT NULL DEFAULT '',
                petitioner TEXT NOT NULL DEFAULT '',
                respondent TEXT NOT NULL DEFAULT '',
                client_name TEXT NOT NULL DEFAULT '',
                client_phone TEXT NOT NULL DEFAULT '',
                court TEXT NOT NULL DEFAULT '',
                judge TEXT NOT NULL DEFAULT '',
                stage TEXT NOT NULL DEFAULT '',
                next_date INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                fee_total REAL NOT NULL DEFAULT 0,
                fee_received REAL NOT NULL DEFAULT 0,
                notes TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE hearings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                case_id INTEGER NOT NULL,
                date INTEGER NOT NULL DEFAULT 0,
                proceedings TEXT NOT NULL DEFAULT '',
                next_date INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(case_id) REFERENCES cases(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE fixtures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                hash TEXT NOT NULL UNIQUE,
                case_id INTEGER NOT NULL DEFAULT 0,
                pending_id INTEGER NOT NULL DEFAULT 0,
                source_label TEXT NOT NULL DEFAULT '',
                source_url TEXT NOT NULL DEFAULT '',
                list_date TEXT NOT NULL DEFAULT '',
                raw TEXT NOT NULL DEFAULT '',
                matched_term TEXT NOT NULL DEFAULT '',
                matched_kind TEXT NOT NULL DEFAULT '',
                found_at INTEGER NOT NULL DEFAULT 0,
                seen INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE watch_terms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                term TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'ADVOCATE',
                enabled INTEGER NOT NULL DEFAULT 1,
                priority TEXT NOT NULL DEFAULT 'OTHER',
                builtin INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE sources (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE pending_files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                added_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE fixed_cases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title_no TEXT NOT NULL,
                court TEXT NOT NULL DEFAULT '',
                prayer TEXT NOT NULL DEFAULT '',
                proceedings TEXT NOT NULL DEFAULT '',
                causelist_no TEXT NOT NULL DEFAULT '',
                fixed_date INTEGER NOT NULL DEFAULT 0,
                source_raw TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE scan_rows (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                scanned_at INTEGER NOT NULL DEFAULT 0,
                source_label TEXT NOT NULL DEFAULT '',
                row_text TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        createIndexes(db)
        seedSources(db)
        seedDefaultKeywords(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Migrations only ever add — never drop a user's data.
        if (oldVersion < 2) {
            createIndexes(db)
            try {
                db.execSQL("ALTER TABLE fixtures ADD COLUMN matched_kind TEXT NOT NULL DEFAULT ''")
            } catch (_: Exception) {
                // Column already present — nothing to do.
            }
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("ALTER TABLE fixtures ADD COLUMN pending_id INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) { /* already present */ }
            try {
                db.execSQL("ALTER TABLE watch_terms ADD COLUMN priority TEXT NOT NULL DEFAULT 'OTHER'")
            } catch (_: Exception) { /* already present */ }
            try {
                db.execSQL("ALTER TABLE watch_terms ADD COLUMN builtin INTEGER NOT NULL DEFAULT 0")
            } catch (_: Exception) { /* already present */ }
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS pending_files (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    note TEXT NOT NULL DEFAULT '',
                    added_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS fixed_cases (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title_no TEXT NOT NULL,
                    court TEXT NOT NULL DEFAULT '',
                    prayer TEXT NOT NULL DEFAULT '',
                    proceedings TEXT NOT NULL DEFAULT '',
                    causelist_no TEXT NOT NULL DEFAULT '',
                    fixed_date INTEGER NOT NULL DEFAULT 0,
                    source_raw TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS scan_rows (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    scanned_at INTEGER NOT NULL DEFAULT 0,
                    source_label TEXT NOT NULL DEFAULT '',
                    row_text TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            seedDefaultKeywords(db)
        }
        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hearings_case ON hearings(case_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cases_next ON cases(next_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fixtures_found ON fixtures(found_at)")
        // The two hottest filters: the case list is always filtered by status,
        // and the reminder query is status + next_date.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cases_status_next ON cases(status, next_date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_fixtures_seen ON fixtures(seen)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_rows_at ON scan_rows(scanned_at)")
    }

    /**
     * NADRA and citizenship/identity-card matters must never be missed —
     * these are seeded on first run (and during upgrade) as locked, always-on,
     * PRIMARY-priority keywords. Idempotent: skips any term already present.
     */
    private fun seedDefaultKeywords(db: SQLiteDatabase) {
        val defaults = listOf(
            "NADRA" to WatchTerm.KIND_OTHER,
            "National Database" to WatchTerm.KIND_OTHER,
            "Identity Card" to WatchTerm.KIND_OTHER,
            "Citizenship" to WatchTerm.KIND_OTHER
        )
        val existing = HashSet<String>()
        db.rawQuery("SELECT term FROM watch_terms", null).use { cur ->
            while (cur.moveToNext()) existing.add(cur.getString(0).trim().lowercase())
        }
        for ((term, kind) in defaults) {
            if (existing.contains(term.lowercase())) continue
            val cv = ContentValues().apply {
                put("term", term)
                put("kind", kind)
                put("enabled", 1)
                put("priority", WatchTerm.PRIORITY_PRIMARY)
                put("builtin", 1)
            }
            db.insert("watch_terms", null, cv)
        }
    }

    private fun seedSources(db: SQLiteDatabase) {
        // The urgent list is the confirmed one and the one that matters daily —
        // LHC publishes it between 5 and 6pm. The rest are the same site's other
        // lists; their exact slugs are unverified, so they start switched off.
        // Turn one on and run a check to see whether it resolves.
        val defaults = listOf(
            Triple(
                "Urgent Cause List",
                "https://data.lhc.gov.pk/index.php/case_management/urgent_cause_list_final",
                true
            ),
            Triple(
                "Regular Cause List",
                "https://data.lhc.gov.pk/index.php/case_management/regular_cause_list",
                false
            ),
            Triple(
                "Supplementary Cause List",
                "https://data.lhc.gov.pk/index.php/case_management/supplementary_cause_list",
                false
            ),
            Triple(
                "Supplementary (Red) List",
                "https://data.lhc.gov.pk/index.php/case_management/supplementary_red_cause_list",
                false
            ),
            Triple(
                "Joint Cause List",
                "https://data.lhc.gov.pk/index.php/case_management/joint_cause_list",
                false
            )
        )
        for ((label, url, enabled) in defaults) {
            val cv = ContentValues().apply {
                put("label", label)
                put("url", url)
                put("enabled", if (enabled) 1 else 0)
            }
            db.insert("sources", null, cv)
        }
    }

    // ---------------------------------------------------------------- cases

    fun saveCase(c: Case): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("case_type", c.caseType)
            put("case_no", c.caseNo)
            put("case_year", c.caseYear)
            put("petitioner", c.petitioner)
            put("respondent", c.respondent)
            put("client_name", c.clientName)
            put("client_phone", c.clientPhone)
            put("court", c.court)
            put("judge", c.judge)
            put("stage", c.stage)
            put("next_date", c.nextDate)
            put("status", c.status)
            put("fee_total", c.feeTotal)
            put("fee_received", c.feeReceived)
            put("notes", c.notes)
            put("updated_at", now)
        }
        return if (c.id == 0L) {
            cv.put("created_at", now)
            val id = writableDatabase.insert("cases", null, cv)
            c.id = id
            id
        } else {
            writableDatabase.update("cases", cv, "id=?", arrayOf(c.id.toString()))
            c.id
        }
    }

    fun deleteCase(id: Long) {
        writableDatabase.delete("cases", "id=?", arrayOf(id.toString()))
    }

    fun getCase(id: Long): Case? =
        readableDatabase.query("cases", null, "id=?", arrayOf(id.toString()), null, null, null)
            .use { cur -> if (cur.moveToFirst()) readCase(cur) else null }

    /**
     * @param status one of Case.STATUS_*, or null for every case
     * @param query  free text matched against ref, parties, client and court
     */
    fun listCases(status: String?, query: String?): List<Case> {
        val where = StringBuilder("1=1")
        val args = ArrayList<String>()
        if (!status.isNullOrBlank()) {
            where.append(" AND status=?")
            args.add(status)
        }
        if (!query.isNullOrBlank()) {
            val like = "%${query.trim()}%"
            where.append(
                " AND (case_no LIKE ? OR case_type LIKE ? OR case_year LIKE ?" +
                    " OR petitioner LIKE ? OR respondent LIKE ? OR client_name LIKE ?" +
                    " OR court LIKE ? OR judge LIKE ? OR stage LIKE ? OR notes LIKE ?)"
            )
            repeat(10) { args.add(like) }
        }
        val out = ArrayList<Case>()
        readableDatabase.query(
            "cases", null, where.toString(), args.toTypedArray(), null, null,
            "CASE WHEN next_date=0 THEN 1 ELSE 0 END ASC, next_date ASC, updated_at DESC"
        ).use { cur -> while (cur.moveToNext()) out.add(readCase(cur)) }
        return out
    }

    /** Active cases whose next hearing falls between [from] and [to]. */
    fun casesBetween(from: Long, to: Long): List<Case> {
        val out = ArrayList<Case>()
        readableDatabase.query(
            "cases", null,
            "next_date>=? AND next_date<=? AND next_date>0 AND status=?",
            arrayOf(from.toString(), to.toString(), Case.STATUS_ACTIVE),
            null, null, "next_date ASC"
        ).use { cur -> while (cur.moveToNext()) out.add(readCase(cur)) }
        return out
    }

    fun countByStatus(status: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM cases WHERE status=?", arrayOf(status))
            .use { cur -> if (cur.moveToFirst()) cur.getInt(0) else 0 }

    private fun readCase(cur: Cursor) = Case(
        id = cur.getLong(cur.getColumnIndexOrThrow("id")),
        caseType = cur.getString(cur.getColumnIndexOrThrow("case_type")),
        caseNo = cur.getString(cur.getColumnIndexOrThrow("case_no")),
        caseYear = cur.getString(cur.getColumnIndexOrThrow("case_year")),
        petitioner = cur.getString(cur.getColumnIndexOrThrow("petitioner")),
        respondent = cur.getString(cur.getColumnIndexOrThrow("respondent")),
        clientName = cur.getString(cur.getColumnIndexOrThrow("client_name")),
        clientPhone = cur.getString(cur.getColumnIndexOrThrow("client_phone")),
        court = cur.getString(cur.getColumnIndexOrThrow("court")),
        judge = cur.getString(cur.getColumnIndexOrThrow("judge")),
        stage = cur.getString(cur.getColumnIndexOrThrow("stage")),
        nextDate = cur.getLong(cur.getColumnIndexOrThrow("next_date")),
        status = cur.getString(cur.getColumnIndexOrThrow("status")),
        feeTotal = cur.getDouble(cur.getColumnIndexOrThrow("fee_total")),
        feeReceived = cur.getDouble(cur.getColumnIndexOrThrow("fee_received")),
        notes = cur.getString(cur.getColumnIndexOrThrow("notes")),
        createdAt = cur.getLong(cur.getColumnIndexOrThrow("created_at")),
        updatedAt = cur.getLong(cur.getColumnIndexOrThrow("updated_at"))
    )

    // ------------------------------------------------------------- hearings

    fun saveHearing(h: Hearing): Long {
        val cv = ContentValues().apply {
            put("case_id", h.caseId)
            put("date", h.date)
            put("proceedings", h.proceedings)
            put("next_date", h.nextDate)
        }
        return if (h.id == 0L) {
            cv.put("created_at", System.currentTimeMillis())
            writableDatabase.insert("hearings", null, cv)
        } else {
            writableDatabase.update("hearings", cv, "id=?", arrayOf(h.id.toString()))
            h.id
        }
    }

    fun deleteHearing(id: Long) {
        writableDatabase.delete("hearings", "id=?", arrayOf(id.toString()))
    }

    fun listHearings(caseId: Long): List<Hearing> {
        val out = ArrayList<Hearing>()
        readableDatabase.query(
            "hearings", null, "case_id=?", arrayOf(caseId.toString()),
            null, null, "date DESC, id DESC"
        ).use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    Hearing(
                        id = cur.getLong(cur.getColumnIndexOrThrow("id")),
                        caseId = cur.getLong(cur.getColumnIndexOrThrow("case_id")),
                        date = cur.getLong(cur.getColumnIndexOrThrow("date")),
                        proceedings = cur.getString(cur.getColumnIndexOrThrow("proceedings")),
                        nextDate = cur.getLong(cur.getColumnIndexOrThrow("next_date")),
                        createdAt = cur.getLong(cur.getColumnIndexOrThrow("created_at"))
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------- fixtures

    /**
     * Insert a page's worth of hits in one transaction and return only the ones
     * that were genuinely new. Doing this row-by-row costs a separate fsync per
     * insert, which is what makes a large cause list feel slow.
     */
    fun insertFixtures(list: List<Fixture>): List<Fixture> {
        if (list.isEmpty()) return emptyList()
        val fresh = ArrayList<Fixture>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (f in list) {
                if (insertFixtureIfNew(f)) fresh.add(f)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return fresh
    }

    /** @return true if this row was new (and therefore worth notifying about) */
    fun insertFixtureIfNew(f: Fixture): Boolean {
        val cv = ContentValues().apply {
            put("hash", f.hash)
            put("case_id", f.caseId)
            put("pending_id", f.pendingId)
            put("source_label", f.sourceLabel)
            put("source_url", f.sourceUrl)
            put("list_date", f.listDate)
            put("raw", f.raw)
            put("matched_term", f.matchedTerm)
            put("matched_kind", f.matchedKind)
            put("found_at", if (f.foundAt == 0L) System.currentTimeMillis() else f.foundAt)
            put("seen", if (f.seen) 1 else 0)
        }
        val id = writableDatabase.insertWithOnConflict(
            "fixtures", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        return id != -1L
    }

    fun listFixtures(limit: Int = 300): List<Fixture> {
        val out = ArrayList<Fixture>()
        readableDatabase.query(
            "fixtures", null, null, null, null, null, "found_at DESC, id DESC", limit.toString()
        ).use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    Fixture(
                        id = cur.getLong(cur.getColumnIndexOrThrow("id")),
                        hash = cur.getString(cur.getColumnIndexOrThrow("hash")),
                        caseId = cur.getLong(cur.getColumnIndexOrThrow("case_id")),
                        pendingId = cur.getLong(cur.getColumnIndexOrThrow("pending_id")),
                        sourceLabel = cur.getString(cur.getColumnIndexOrThrow("source_label")),
                        sourceUrl = cur.getString(cur.getColumnIndexOrThrow("source_url")),
                        listDate = cur.getString(cur.getColumnIndexOrThrow("list_date")),
                        raw = cur.getString(cur.getColumnIndexOrThrow("raw")),
                        matchedTerm = cur.getString(cur.getColumnIndexOrThrow("matched_term")),
                        matchedKind = cur.getString(cur.getColumnIndexOrThrow("matched_kind")),
                        foundAt = cur.getLong(cur.getColumnIndexOrThrow("found_at")),
                        seen = cur.getInt(cur.getColumnIndexOrThrow("seen")) == 1
                    )
                )
            }
        }
        return out
    }

    fun deleteFixture(id: Long) {
        writableDatabase.delete("fixtures", "id=?", arrayOf(id.toString()))
    }

    fun markAllFixturesSeen() {
        val cv = ContentValues().apply { put("seen", 1) }
        writableDatabase.update("fixtures", cv, "seen=0", null)
    }

    fun unseenFixtureCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM fixtures WHERE seen=0", null)
            .use { cur -> if (cur.moveToFirst()) cur.getInt(0) else 0 }

    fun clearFixtures() {
        writableDatabase.delete("fixtures", null, null)
    }

    /** Drop hits older than [days] so the table cannot grow without bound. */
    fun pruneFixtures(days: Int = 60) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        writableDatabase.delete("fixtures", "found_at < ?", arrayOf(cutoff.toString()))
    }

    // ---------------------------------------------------------- watch terms

    fun addWatchTerm(term: String, kind: String, priority: String = WatchTerm.PRIORITY_OTHER): Long {
        val cv = ContentValues().apply {
            put("term", term.trim())
            put("kind", kind)
            put("enabled", 1)
            put("priority", priority)
            put("builtin", 0)
        }
        return writableDatabase.insert("watch_terms", null, cv)
    }

    /** Builtin (NADRA/citizenship defaults) keywords are locked — this is a no-op for them. */
    fun deleteWatchTerm(id: Long) {
        writableDatabase.delete("watch_terms", "id=? AND builtin=0", arrayOf(id.toString()))
    }

    fun setWatchTermEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        writableDatabase.update("watch_terms", cv, "id=? AND builtin=0", arrayOf(id.toString()))
    }

    fun setWatchTermPriority(id: Long, priority: String) {
        val cv = ContentValues().apply { put("priority", priority) }
        writableDatabase.update("watch_terms", cv, "id=? AND builtin=0", arrayOf(id.toString()))
    }

    fun listWatchTerms(onlyEnabled: Boolean = false): List<WatchTerm> {
        val out = ArrayList<WatchTerm>()
        val where = if (onlyEnabled) "enabled=1" else null
        readableDatabase.query(
            "watch_terms", null, where, null, null, null,
            "CASE WHEN priority='PRIMARY' THEN 0 ELSE 1 END ASC, kind ASC, term ASC"
        ).use { cur ->
                while (cur.moveToNext()) {
                    out.add(
                        WatchTerm(
                            id = cur.getLong(cur.getColumnIndexOrThrow("id")),
                            term = cur.getString(cur.getColumnIndexOrThrow("term")),
                            kind = cur.getString(cur.getColumnIndexOrThrow("kind")),
                            enabled = cur.getInt(cur.getColumnIndexOrThrow("enabled")) == 1,
                            priority = cur.getString(cur.getColumnIndexOrThrow("priority")),
                            builtin = cur.getInt(cur.getColumnIndexOrThrow("builtin")) == 1
                        )
                    )
                }
            }
        return out
    }

    // -------------------------------------------------------------- sources

    fun addSource(label: String, url: String): Long {
        val cv = ContentValues().apply {
            put("label", label.trim())
            put("url", url.trim())
            put("enabled", 1)
        }
        return writableDatabase.insert("sources", null, cv)
    }

    fun deleteSource(id: Long) {
        writableDatabase.delete("sources", "id=?", arrayOf(id.toString()))
    }

    fun setSourceEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        writableDatabase.update("sources", cv, "id=?", arrayOf(id.toString()))
    }

    fun listSources(onlyEnabled: Boolean = false): List<Source> {
        val out = ArrayList<Source>()
        val where = if (onlyEnabled) "enabled=1" else null
        readableDatabase.query("sources", null, where, null, null, null, "id ASC").use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    Source(
                        id = cur.getLong(cur.getColumnIndexOrThrow("id")),
                        label = cur.getString(cur.getColumnIndexOrThrow("label")),
                        url = cur.getString(cur.getColumnIndexOrThrow("url")),
                        enabled = cur.getInt(cur.getColumnIndexOrThrow("enabled")) == 1
                    )
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ pending files

    fun addPendingFile(title: String, note: String): Long {
        val cv = ContentValues().apply {
            put("title", title.trim())
            put("note", note.trim())
            put("added_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("pending_files", null, cv)
    }

    fun deletePendingFile(id: Long) {
        writableDatabase.delete("pending_files", "id=?", arrayOf(id.toString()))
        // A removed pending file has nothing left to approve.
        writableDatabase.delete("fixtures", "pending_id=?", arrayOf(id.toString()))
    }

    fun listPendingFiles(): List<PendingFile> {
        val out = ArrayList<PendingFile>()
        readableDatabase.query("pending_files", null, null, null, null, null, "added_at DESC")
            .use { cur ->
                while (cur.moveToNext()) {
                    out.add(
                        PendingFile(
                            id = cur.getLong(cur.getColumnIndexOrThrow("id")),
                            title = cur.getString(cur.getColumnIndexOrThrow("title")),
                            note = cur.getString(cur.getColumnIndexOrThrow("note")),
                            addedAt = cur.getLong(cur.getColumnIndexOrThrow("added_at"))
                        )
                    )
                }
            }
        return out
    }

    // ------------------------------------------------------------- fixed cases

    fun addFixedCase(f: FixedCase): Long {
        val cv = ContentValues().apply {
            put("title_no", f.titleNo)
            put("court", f.court)
            put("prayer", f.prayer)
            put("proceedings", f.proceedings)
            put("causelist_no", f.causelistNo)
            put("fixed_date", if (f.fixedDate == 0L) System.currentTimeMillis() else f.fixedDate)
            put("source_raw", f.sourceRaw)
        }
        return writableDatabase.insert("fixed_cases", null, cv)
    }

    fun deleteFixedCase(id: Long) {
        writableDatabase.delete("fixed_cases", "id=?", arrayOf(id.toString()))
    }

    fun listFixedCases(): List<FixedCase> {
        val out = ArrayList<FixedCase>()
        readableDatabase.query("fixed_cases", null, null, null, null, null, "fixed_date DESC, id DESC")
            .use { cur ->
                while (cur.moveToNext()) {
                    out.add(
                        FixedCase(
                            id = cur.getLong(cur.getColumnIndexOrThrow("id")),
                            titleNo = cur.getString(cur.getColumnIndexOrThrow("title_no")),
                            court = cur.getString(cur.getColumnIndexOrThrow("court")),
                            prayer = cur.getString(cur.getColumnIndexOrThrow("prayer")),
                            proceedings = cur.getString(cur.getColumnIndexOrThrow("proceedings")),
                            causelistNo = cur.getString(cur.getColumnIndexOrThrow("causelist_no")),
                            fixedDate = cur.getLong(cur.getColumnIndexOrThrow("fixed_date")),
                            sourceRaw = cur.getString(cur.getColumnIndexOrThrow("source_raw"))
                        )
                    )
                }
            }
        return out
    }

    // -------------------------------------------------------------- scan rows

    /** Every line seen in a scan, kept briefly so it can be searched later —
     *  even rows that matched nothing (a judge's name, a serial number). */
    fun insertScanRows(sourceLabel: String, rows: List<String>) {
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (r in rows) {
                val cv = ContentValues().apply {
                    put("scanned_at", now)
                    put("source_label", sourceLabel)
                    put("row_text", if (r.length > 300) r.substring(0, 300) + "…" else r)
                }
                db.insert("scan_rows", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Simple substring search (SQLite's LIKE is already case-insensitive for ASCII). */
    fun searchScanRows(query: String, limit: Int = 50): List<Pair<String, Long>> {
        val like = "%${query.trim()}%"
        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery(
            "SELECT row_text, scanned_at FROM scan_rows WHERE row_text LIKE ? ORDER BY scanned_at DESC LIMIT ?",
            arrayOf(like, limit.toString())
        ).use { cur -> while (cur.moveToNext()) out.add(cur.getString(0) to cur.getLong(1)) }
        return out
    }

    fun scanRowCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM scan_rows", null)
            .use { cur -> if (cur.moveToFirst()) cur.getInt(0) else 0 }

    /** Bounded to a few days — this is a search convenience, not a permanent archive. */
    fun pruneScanRows(days: Int = 3) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        writableDatabase.delete("scan_rows", "scanned_at < ?", arrayOf(cutoff.toString()))
    }

    companion object {
        private const val NAME = "casediary.db"
        private const val VERSION = 3

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context).also { instance = it }
            }
    }
}
