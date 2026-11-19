package org.cabetus.data.classweb

import org.cabetus.domain.Campus

/**
 * CLASS の科目詳細から読み取った科目コード・教室の正規化。
 *
 * DOM 由来の値はそのまま信用せず、時間割PDFと揃えた形式だけを受け付ける。
 * 形式が確認できない値は null（不明）にし、名前などから補完・推測しない。
 * 値そのものはログへ出さない。
 */
internal object ClassTimetableCourseMetadata {

    /** 時間割PDFの科目コードと同じ、大文字英数字7桁のみ。 */
    private val OFFICIAL_COURSE_CODE = Regex("^[0-9A-Z]{7}$")

    /**
     * 教室は「キャンパス略字 + 全角コロン + 室名」で始まる。
     * 例: 「葛：E501教室」。キャンパス略字は [Campus.shortCode] と一致するものだけ。
     */
    private val CAMPUS_CODES = Campus.entries.map { it.shortCode }.toSet()
    private val ROOM_PREFIX = Regex(
        "^([" + Campus.entries.joinToString("") { Regex.escape(it.shortCode) } + "])：(.+)$",
    )

    private const val MAX_ROOM_LENGTH = 100

    fun normalizeOfficialCourseCode(value: String?): String? {
        val text = normalize(value)
        return text.takeIf { OFFICIAL_COURSE_CODE.matches(it) }
    }

    /**
     * 「葛：E501教室」を時間割PDFと同じ「E501」に揃える。
     * キャンパス表記や室名を確認できない場合は null にして、教室を推測しない。
     */
    fun normalizeRoom(value: String?): String? = normalizeRoom(value, requireCampusPrefix = false)

    /**
     * DOM の教室欄は必ず「キャンパス略字 + 全角コロン」で始まる。
     * この形式でない値は、教室名を推測せず null にする。
     */
    fun normalizeRoomFromDetail(value: String?): String? =
        normalizeRoom(value, requireCampusPrefix = true)

    private fun normalizeRoom(value: String?, requireCampusPrefix: Boolean): String? {
        val text = normalize(value)
        if (text.isEmpty()) return null

        val match = ROOM_PREFIX.matchEntire(text)
        if (match == null) {
            if (requireCampusPrefix) return null
            // すでに正規化済みの教室（例: E501）は受け付けるが、
            // キャンパス表記やコロンが残る値は推測せず拒否する。
            if (text.contains('：') || text.contains(':') || text.length > MAX_ROOM_LENGTH) {
                return null
            }
            return text
        }

        val campus = match.groupValues[1]
        if (campus !in CAMPUS_CODES) return null

        var room = normalize(match.groupValues[2])
        if (room.endsWith("教室")) {
            room = normalize(room.removeSuffix("教室"))
        }
        if (room.isEmpty() || room.length > MAX_ROOM_LENGTH) return null
        return room
    }

    private fun normalize(value: String?): String =
        (value ?: "")
            .replace('\u00a0', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")
}
