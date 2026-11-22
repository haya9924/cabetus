package org.cabetus.data.settings

/** 時間割の科目色の決め方。 */
enum class TimetableColorMode {
    /** 出席率から自動で色を決める。 */
    ATTENDANCE_RATE,

    /** ユーザーが手動で選んだ色を使う。 */
    MANUAL,
}

/** 科目色を保存する Preferences キーの接頭辞。キー名は `tt_color_<年度>:<科目コード>`。 */
internal const val TT_COLOR_PREFIX: String = "tt_color_"

/** 科目色の保存キーとして許す年度の下限。 */
const val TIMETABLE_COLOR_MIN_YEAR: Int = 1

/** 科目色の保存キーとして許す年度の上限。 */
const val TIMETABLE_COLOR_MAX_YEAR: Int = 9999

/**
 * 科目色の共通キー。`"<年度>:<科目コード>"`。
 *
 * [year] は 1..9999、[code] は空・空白のみを許可しない。
 * 時間割の色（自動・手動とも）はこのキーで科目を特定する。
 */
fun timetableColorKey(year: Int, code: String): String {
    require(year in TIMETABLE_COLOR_MIN_YEAR..TIMETABLE_COLOR_MAX_YEAR) {
        "year must be in $TIMETABLE_COLOR_MIN_YEAR..$TIMETABLE_COLOR_MAX_YEAR"
    }
    require(code.isNotBlank()) { "code must not be blank" }
    return "$year:$code"
}

/**
 * 保存キーを正規化して返す。形式が不正なら null。
 *
 * `"<年度>:<科目コード>"` として妥当なときだけ、数値化した年度と
 * コードを組み立て直した文字列を返す（`"01:code"` のような表記ゆれを弾く）。
 */
internal fun normalizeTimetableColorKey(key: String): String? {
    val separator = key.indexOf(':')
    if (separator <= 0 || separator == key.lastIndex) return null
    val year = key.substring(0, separator).toIntOrNull() ?: return null
    if (year !in TIMETABLE_COLOR_MIN_YEAR..TIMETABLE_COLOR_MAX_YEAR) return null
    val code = key.substring(separator + 1)
    if (code.isBlank()) return null
    val normalized = "$year:$code"
    return if (normalized == key) normalized else null
}
