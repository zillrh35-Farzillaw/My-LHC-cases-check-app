package pk.advocate.casediary.util

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import pk.advocate.casediary.db.Case
import pk.advocate.casediary.db.Db
import java.io.File
import java.io.FileOutputStream

/**
 * A clean "All Cases" report as a real PDF — every case being tracked, sorted
 * Active first (soonest hearing first), then Decided, then Archived, followed
 * by the pending files not yet fixed. No external library — drawn directly
 * with [PdfDocument] so it works fully offline and shares cleanly (WhatsApp,
 * email, print) via a plain content:// URI.
 */
object ReportPdf {

    private const val PAGE_W = 595 // A4 at 72dpi points
    private const val PAGE_H = 842
    private const val MARGIN = 32f

    fun export(context: Context): File {
        val db = Db.get(context)
        val cases = db.listCases(null, null).sortedWith(
            compareBy(
                { statusRank(it.status) },
                { if (it.nextDate > 0) it.nextDate else Long.MAX_VALUE }
            )
        )
        val pending = db.listPendingFiles()

        val doc = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        var y = MARGIN

        val titlePaint = TextPaint().apply { textSize = 15f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        val subPaint = TextPaint().apply { textSize = 9f; color = 0xFF555555.toInt(); textAlign = Paint.Align.CENTER }
        val headPaint = TextPaint().apply { textSize = 9f; isFakeBoldText = true }
        val cellPaint = TextPaint().apply { textSize = 8.5f }
        val sectionPaint = TextPaint().apply { textSize = 11f; isFakeBoldText = true }
        val linePaint = Paint().apply { strokeWidth = 0.75f; color = 0xFF000000.toInt() }

        fun newPage() {
            doc.finishPage(page)
            pageNum++
            pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create()
            page = doc.startPage(pageInfo)
            canvas = page.canvas
            y = MARGIN
        }

        fun ensureSpace(need: Float) {
            if (y + need > PAGE_H - MARGIN) newPage()
        }

        canvas.drawText("CCMS — All Cases Report", PAGE_W / 2f, y + 10, titlePaint)
        y += 16
        canvas.drawText("Generated ${Dates.fmtStamp(System.currentTimeMillis())}", PAGE_W / 2f, y + 8, subPaint)
        y += 26

        val colWidths = floatArrayOf(20f, 95f, 165f, 90f, 70f, 75f)
        val colX = FloatArray(colWidths.size)
        run {
            var x = MARGIN
            for (i in colWidths.indices) {
                colX[i] = x
                x += colWidths[i]
            }
        }
        val tableRight = MARGIN + colWidths.sum()

        fun drawRow(cells: List<String>, paint: TextPaint) {
            val rowPad = 4f
            val layouts = cells.mapIndexed { i, text ->
                val width = (colWidths[i] - 4).toInt().coerceAtLeast(10)
                StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
            }
            val maxH = layouts.maxOf { it.height }.toFloat()
            ensureSpace(maxH + rowPad * 2)
            layouts.forEachIndexed { i, sl ->
                canvas.save()
                canvas.translate(colX[i] + 2, y + rowPad)
                sl.draw(canvas)
                canvas.restore()
            }
            y += maxH + rowPad * 2
            canvas.drawLine(MARGIN, y, tableRight, y, linePaint)
        }

        canvas.drawLine(MARGIN, y, tableRight, y, linePaint)
        drawRow(listOf("Sr", "Case No.", "Title", "Court / Judge", "Stage", "Next Hearing"), headPaint)

        if (cases.isEmpty()) {
            drawRow(listOf("", "No cases tracked yet", "", "", "", ""), cellPaint)
        } else {
            var lastRank = -1
            cases.forEachIndexed { i, c ->
                val rank = statusRank(c.status)
                if (rank != lastRank) {
                    lastRank = rank
                    ensureSpace(20f)
                    y += 6
                    canvas.drawText(statusLabel(c.status), MARGIN, y + 8, sectionPaint)
                    y += 16
                }
                val courtJudge = listOf(c.court, c.judge).filter { it.isNotBlank() }.joinToString(" · ")
                drawRow(
                    listOf(
                        (i + 1).toString(),
                        c.caseRef().ifBlank { "—" },
                        c.title(),
                        courtJudge,
                        c.stage,
                        if (c.nextDate > 0) Dates.fmt(c.nextDate) else "—"
                    ),
                    cellPaint
                )
            }
        }

        y += 20
        ensureSpace(16f)
        canvas.drawText("Pending files (not yet fixed):", MARGIN, y, headPaint)
        y += 14

        val fullWidth = (PAGE_W - MARGIN * 2).toInt()
        if (pending.isEmpty()) {
            ensureSpace(14f)
            canvas.drawText("None", MARGIN, y, cellPaint)
            y += 14
        } else {
            for (p in pending) {
                val ref = p.caseRef()
                val text = "• ${p.title}" +
                    (if (ref.isNotBlank()) "  [$ref]" else "") +
                    (if (p.note.isNotBlank()) "  (${p.note})" else "")
                val sl = StaticLayout.Builder.obtain(text, 0, text.length, cellPaint, fullWidth).build()
                ensureSpace(sl.height.toFloat() + 4)
                canvas.save()
                canvas.translate(MARGIN, y)
                sl.draw(canvas)
                canvas.restore()
                y += sl.height + 4
            }
        }

        doc.finishPage(page)

        val dir = File(context.cacheDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        val outFile = File(dir, "ccms-all-cases-${Dates.isoDate(System.currentTimeMillis())}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    private fun statusRank(status: String): Int = when (status) {
        Case.STATUS_ACTIVE -> 0
        Case.STATUS_DECIDED -> 1
        else -> 2
    }

    private fun statusLabel(status: String): String = when (status) {
        Case.STATUS_ACTIVE -> "Active"
        Case.STATUS_DECIDED -> "Decided"
        else -> "Archived"
    }
}
