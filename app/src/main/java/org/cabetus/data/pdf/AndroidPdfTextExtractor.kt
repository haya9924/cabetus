package org.cabetus.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream

/**
 * pdfbox-android を用いて PDF から文字＋座標(TextRun)を抽出する。
 * 抽出結果は純ロジックのパーサ(AttendanceParser/TimetableParser)に渡す。
 */
object AndroidPdfTextExtractor {

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
                            TextRun(
                                page = page,
                                x = tp.xDirAdj,
                                y = tp.yDirAdj,
                                width = tp.widthDirAdj,
                                height = tp.heightDir,
                                text = u,
                            ),
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
}
