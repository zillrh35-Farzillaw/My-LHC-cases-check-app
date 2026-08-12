package pk.advocate.casediary.util

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import pk.advocate.casediary.db.Case
import pk.advocate.casediary.db.Db
import pk.advocate.casediary.db.Hearing
import pk.advocate.casediary.db.WatchTerm
import java.io.File

/**
 * Everything lives on the phone, so an export is the only backup there is.
 * Plain JSON — readable, and restorable on a new device.
 */
object Backup {

    fun exportToCache(context: Context): File {
        val db = Db.get(context)
        val root = JSONObject()
        root.put("format", "casediary-backup")
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val cases = JSONArray()
        for (c in db.listCases(null, null)) {
            val o = JSONObject()
            o.put("caseType", c.caseType)
            o.put("caseNo", c.caseNo)
            o.put("caseYear", c.caseYear)
            o.put("petitioner", c.petitioner)
            o.put("respondent", c.respondent)
            o.put("clientName", c.clientName)
            o.put("clientPhone", c.clientPhone)
            o.put("court", c.court)
            o.put("judge", c.judge)
            o.put("stage", c.stage)
            o.put("nextDate", c.nextDate)
            o.put("status", c.status)
            o.put("feeTotal", c.feeTotal)
            o.put("feeReceived", c.feeReceived)
            o.put("notes", c.notes)

            val hs = JSONArray()
            for (h in db.listHearings(c.id)) {
                val ho = JSONObject()
                ho.put("date", h.date)
                ho.put("proceedings", h.proceedings)
                ho.put("nextDate", h.nextDate)
                hs.put(ho)
            }
            o.put("hearings", hs)
            cases.put(o)
        }
        root.put("cases", cases)

        val terms = JSONArray()
        for (t in db.listWatchTerms()) {
            val o = JSONObject()
            o.put("term", t.term)
            o.put("kind", t.kind)
            o.put("enabled", t.enabled)
            terms.put(o)
        }
        root.put("watchTerms", terms)

        val sources = JSONArray()
        for (s in db.listSources()) {
            val o = JSONObject()
            o.put("label", s.label)
            o.put("url", s.url)
            o.put("enabled", s.enabled)
            sources.put(o)
        }
        root.put("sources", sources)

        val dir = File(context.cacheDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "case-diary-backup-${Dates.isoDate(System.currentTimeMillis())}.json")
        file.writeText(root.toString(2), Charsets.UTF_8)
        return file
    }

    data class ImportSummary(val cases: Int, val hearings: Int, val terms: Int, val sources: Int)

    fun importFrom(context: Context, uri: Uri): ImportSummary {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalArgumentException("Could not read that file")

        val root = JSONObject(text)
        require(root.optString("format") == "casediary-backup") {
            "That file is not a Case Diary backup"
        }

        val db = Db.get(context)
        var nCases = 0
        var nHearings = 0
        var nTerms = 0
        var nSources = 0

        val cases = root.optJSONArray("cases") ?: JSONArray()
        for (i in 0 until cases.length()) {
            val o = cases.getJSONObject(i)
            val c = Case(
                caseType = o.optString("caseType"),
                caseNo = o.optString("caseNo"),
                caseYear = o.optString("caseYear"),
                petitioner = o.optString("petitioner"),
                respondent = o.optString("respondent"),
                clientName = o.optString("clientName"),
                clientPhone = o.optString("clientPhone"),
                court = o.optString("court"),
                judge = o.optString("judge"),
                stage = o.optString("stage"),
                nextDate = o.optLong("nextDate", 0L),
                status = o.optString("status", Case.STATUS_ACTIVE),
                feeTotal = o.optDouble("feeTotal", 0.0),
                feeReceived = o.optDouble("feeReceived", 0.0),
                notes = o.optString("notes")
            )
            val id = db.saveCase(c)
            nCases++

            val hs = o.optJSONArray("hearings") ?: JSONArray()
            for (j in 0 until hs.length()) {
                val ho = hs.getJSONObject(j)
                db.saveHearing(
                    Hearing(
                        caseId = id,
                        date = ho.optLong("date", 0L),
                        proceedings = ho.optString("proceedings"),
                        nextDate = ho.optLong("nextDate", 0L)
                    )
                )
                nHearings++
            }
        }

        val terms = root.optJSONArray("watchTerms") ?: JSONArray()
        val existingTerms = db.listWatchTerms().map { it.term.lowercase() }.toHashSet()
        for (i in 0 until terms.length()) {
            val o = terms.getJSONObject(i)
            val term = o.optString("term")
            if (term.isBlank() || existingTerms.contains(term.lowercase())) continue
            db.addWatchTerm(term, o.optString("kind", WatchTerm.KIND_ADVOCATE))
            nTerms++
        }

        val sources = root.optJSONArray("sources") ?: JSONArray()
        val existingUrls = db.listSources().map { it.url.lowercase() }.toHashSet()
        for (i in 0 until sources.length()) {
            val o = sources.getJSONObject(i)
            val url = o.optString("url")
            if (url.isBlank() || existingUrls.contains(url.lowercase())) continue
            db.addSource(o.optString("label", url), url)
            nSources++
        }

        return ImportSummary(nCases, nHearings, nTerms, nSources)
    }
}
