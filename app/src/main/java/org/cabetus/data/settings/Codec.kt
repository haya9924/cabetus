package org.cabetus.data.settings

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.cabetus.domain.AttendanceGoal

/** 保存キーの区切り文字。 */
private const val KEY_SEPARATOR = ':'

/**
 * 出席目標の保存キー。`"<年度>:<科目コード>"`。
 *
 * [year] は 1 以上でなければならない（0 や負数は保存しない）。
 * 科目コードは空・空白のみを許可しない。
 */
fun attendanceGoalKey(year: Int, code: String): String {
    require(year > 0) { "year must be positive" }
    require(code.isNotBlank()) { "code must not be blank" }
    return "$year$KEY_SEPARATOR$code"
}

/**
 * 出席目標マップ（キーは [attendanceGoalKey]）と JSON 文字列の相互変換。
 *
 * 読み込み（[decode]）は壊れた JSON を空マップとして扱う（設定画面を壊さない）。
 * 書き込み前の読み込み（[decodeOrThrow]）は、壊れた JSON や不正な要素を
 * **例外にして書き込みを中止する**。壊れた値を上書きで消してしまい、
 * 他の設定（他の科目の目標など）を失わせないため。
 */
object AttendanceGoalCodec {

    /** 保存文字列の上限。異常に大きい値は読まない。 */
    const val MAX_ENCODED_LENGTH: Int = 256 * 1024

    /** 科目コードとして許す長さの上限。 */
    const val MAX_CODE_LENGTH: Int = 200

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mapSerializer =
        MapSerializer(String.serializer(), AttendanceGoal.serializer())

    /** マップを JSON 文字列にする。キーの順序は安定（年度→コードの辞書順）。 */
    fun encode(goals: Map<String, AttendanceGoal>): String {
        val sorted = goals.toSortedMap()
        return json.encodeToString(mapSerializer, sorted)
    }

    /**
     * 保存文字列を復元する。JSON として壊れている・上限超過なら空マップ。
     * 個々の要素が不正（キー形式違反・値が復元できない）なら、その要素だけを捨てる。
     */
    fun decode(raw: String?): Map<String, AttendanceGoal> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_ENCODED_LENGTH) return emptyMap()
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return emptyMap()
        val result = LinkedHashMap<String, AttendanceGoal>()
        obj.forEach { (key, element) ->
            if (!isValidKey(key)) return@forEach
            val goal = runCatching {
                json.decodeFromJsonElement(AttendanceGoal.serializer(), element)
            }.getOrNull() ?: return@forEach
            result[key] = goal
        }
        return result
    }

    /**
     * 更新前の読み込み。空なら空マップ、それ以外で復元できないなら
     * [IllegalStateException] を投げて呼び出し側の書き込みを中止させる。
     */
    fun decodeOrThrow(raw: String?): Map<String, AttendanceGoal> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        if (text.length > MAX_ENCODED_LENGTH) {
            throw IllegalStateException("保存済みの出席目標が大きすぎるため更新できません")
        }
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw IllegalStateException("保存済みの出席目標を読み取れないため更新できません")
        val result = LinkedHashMap<String, AttendanceGoal>()
        obj.forEach { (key, element) ->
            if (!isValidKey(key)) {
                throw IllegalStateException("保存済みの出席目標に不正なキーがあるため更新できません")
            }
            val goal = runCatching {
                json.decodeFromJsonElement(AttendanceGoal.serializer(), element)
            }.getOrNull()
                ?: throw IllegalStateException("保存済みの出席目標に不正な値があるため更新できません")
            result[key] = goal
        }
        return result
    }

    /** `"<年度>:<科目コード>"` として妥当か。年度は 1 以上。 */
    fun isValidKey(key: String): Boolean {
        val separator = key.indexOf(KEY_SEPARATOR)
        if (separator <= 0 || separator == key.lastIndex) return false
        val year = key.substring(0, separator).toIntOrNull() ?: return false
        if (year <= 0) return false
        val code = key.substring(separator + 1)
        if (code.isBlank() || code.length > MAX_CODE_LENGTH) return false
        return key == "$year$KEY_SEPARATOR$code"
    }
}
