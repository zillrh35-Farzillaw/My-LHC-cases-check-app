package pk.advocate.casediary.util

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import pk.advocate.casediary.db.Db
import java.io.File
import java.io.FileOutputStream

/**
 * Builds the "Urgent Cases Report" as a real PDF, matching the lawyer's own
 * document: Sr No. / Title & No. / Court / Prayer & Remarks / Proceedings /
 * Causelist No., followed by the pending-files list. No external library —
 * drawn directly with [PdfDocument] so it works fully offline and shares
 * cleanly (WhatsApp, email, print) via a plain content:// URI.
 */
object ReportPdf {

    private const val PAGE_W = 595 // A4 at 72dpi points
    private const val PAGE_H = 842
    private const val MARGIN = 32f

    fun export(context: Context): File {
        val db = Db.get(context)
        val fixed = db.listFixedCases()
        val pending = db.listPendingFiles()

        val doc = PdfDocument()
        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create()
        var page = doc.startPage(pageInfo)
        var canvas = page.canvas
        var y = MARGIN

        val titlePaint = TextPaint().apply { textSize = 15f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        val headPaint = TextPaint().apply { textSize = 9f; isFakeBoldText = true }
        val cellPaint = TextPaint().apply { textSize = 8.5f }
        val boldCenter = TextPaint().apply { textSize = 11f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        val notePaint = TextPaint().apply { textSize = 7.5f; color = 0xFF555555.toInt() }
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

        canvas.drawText("Urgent Cases Report – Lahore High Court LHR", PAGE_W / 2f, y + 10, titlePaint)
        y += 26

        val colWidths = floatArrayOf(22f, 172f, 88f, 108f, 68f, 73f)
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
        drawRow(
            listOf("Sr", "Title & No. of the case", "Name of the Court", "Nature of Prayer & Remarks", "Proceedings", "Urgent Causelist No."),
            headPaint
        )

        if (fixed.isEmpty()) {
            drawRow(listOf("", "No cases fixed yet", "", "", "", ""), cellPaint)
        } else {
            fixed.forEachIndexed { i, f ->
                drawRow(
                    listOf((i + 1).toString(), f.titleNo, f.court, f.prayer, f.proceedings, f.causelistNo),
                    cellPaint
                )
            }
        }

        y += 16
        ensureSpace(30f)
        canvas.drawText("Fixed for date", PAGE_W / 2f, y, boldCenter)
        y += 14
        canvas.drawText(Dates.fmt(System.currentTimeMillis()), PAGE_W / 2f, y, boldCenter)
        y += 22

        ensureSpace(16f)
        canvas.drawText("Pending files yet to be fixed:", MARGIN, y, headPaint)
        y += 14

        val noteText = "(Note: These files are already supplied. Once a file is fixed and approved " +
            "in the app, it moves into the table above and is removed from this list.)"
        val noteWidth = (PAGE_W - MARGIN * 2).toInt()
        val noteLayout = StaticLayout.Builder.obtain(noteText, 0, noteText.length, notePaint, noteWidth).build()
        ensureSpace(noteLayout.height.toFloat())
        canvas.save()
        canvas.translate(MARGIN, y)
        noteLayout.draw(canvas)
        canvas.restore()
        y += noteLayout.height + 10

        if (pending.isEmpty()) {
            ensureSpace(14f)
            canvas.drawText("None", MARGIN, y, cellPaint)
            y += 14
        } else {
            for (p in pending) {
                val text = "• ${p.title}" + if (p.note.isNotBlank()) "  (${p.note})" else ""
                val sl = StaticLayout.Builder.obtain(text, 0, text.length, cellPaint, noteWidth).build()
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
        val outFile = File(dir, "urgent-cases-report-${Dates.isoDate(System.currentTimeMillis())}.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }
}
