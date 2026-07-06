package org.cabetus

import org.cabetus.data.pdf.TextRun
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.InputStream

/**
 * テスト側で org.apache.pdfbox を用いて実PDFから TextRun を生成する。
 * 本番の AndroidPdfTextExtractor（pdfbox-android）と同一ロジックで、
 * 純パーサ(AttendanceParser/TimetableParser)を実PDFで検証する。
 */
object TestPdfExtractor {
    fun extract(input: InputStream): List<TextRun> {
        val runs = mutableListOf<TextRun>()
        PDDocument.load(input).use { doc ->
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    val page = currentPageNo - 1
                    for (tp in textPositions) {
                        val u = tp.unicode ?: continue
                        if (u.isBlank()) continue
                        runs.add(
                            TextRun(page, tp.xDirAdj, tp.yDirAdj, tp.widthDirAdj, tp.heightDir, u),
                        )
                    }
                }
            }
            stripper.sortByPosition = true
            stripper.startPage = 1
            stripper.endPage = doc.numberOfPages
            stripper.getText(doc)
        }
        return runs
    }

    fun resource(name: String): List<TextRun> {
        val stream: InputStream = requireNotNull(
            TestPdfExtractor::class.java.classLoader?.getResourceAsStream(name),
        ) { "test resource $name not found" }
        return stream.use { extract(it) }
    }
}
