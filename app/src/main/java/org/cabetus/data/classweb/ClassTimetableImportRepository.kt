package org.cabetus.data.classweb

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cabetus.data.local.ClassCourseDao
import org.cabetus.data.local.ClassCourseEntity
import org.cabetus.data.local.TimetableCellEntity
import org.cabetus.data.local.TimetableDao
import org.cabetus.data.local.TusDatabase
import org.cabetus.data.settings.SettingsRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Web の時間割画面から取り込む 1 コマ。 */
data class ClassTimetableSlot(
    val dayOfWeek: Int,
    val period: Int,
    val courseName: String,
    /** CLASS の科目詳細で確認できた正式な科目コード。不明なら null。 */
    val courseCode: String? = null,
    /** キャンパス表記を除いた教室。不明なら null。 */
    val room: String? = null,
)

/**
 * 保存前プレビュー。
 *
 * [blocked] が true の場合、保存してはいけない。CLASS の時間割画面からは年度・学期を
 * 取得できないため、既存の時間割がある状態では別学期の科目が混在するおそれがある。
 */
data class ClassTimetablePreview(
    val slots: List<ClassTimetableSlot>,
    val warnings: List<String>,
    val academicYear: Int,
    val blocked: Boolean,
    val blockedReason: String?,
    val existingCourseCount: Int,
    val newCourseCount: Int,
    val newCellCount: Int,
    val duplicateCellCount: Int,
    val sameSlotDifferentCourseCount: Int,
    val skippedAmbiguousCount: Int,
    /** DOM で正式な科目コードを確認できたコマ数。 */
    val officialCodeCellCount: Int = 0,
    /** 仮コードで保存するコマ数（正式コードが不明、または別科目名と競合）。 */
    val unknownCodeCellCount: Int = 0,
    /** 入力内で科目コードが重複・不一致だったコマ数（保留を含む）。 */
    val codeConflictCellCount: Int = 0,
    /** 既存科目とコードが競合して取り込まないコマ数。 */
    val skippedCodeConflictCount: Int = 0,
    /** 教室を確認できないコマ数。 */
    val unknownRoomCellCount: Int = 0,
) {
    val courseCount: Int get() = slots.map { it.courseName }.distinct().size
}

data class ClassTimetableSaveResult(
    val insertedCells: Int,
    val createdCourses: Int,
    val duplicateCells: Int,
    val sameSlotDifferentCourses: Int,
    val skippedAmbiguous: Int,
    val warnings: List<String>,
    val blocked: Boolean = false,
    val blockedReason: String? = null,
    val officialCodeCellCount: Int = 0,
    val unknownCodeCellCount: Int = 0,
    val codeConflictCellCount: Int = 0,
    val skippedCodeConflictCount: Int = 0,
    val unknownRoomCellCount: Int = 0,
)

/**
 * 取り込み内容と既存 Room データを突き合わせる純ロジック。
 *
 * 安全側に倒すため、次の場合は取り込まない。
 * - 既存の時間割が1コマでもある場合（年度・学期を画面から取得できず、混在を防げない）
 * - 同じコマに別の科目名がある場合（どちらが正しいか推測しない）
 * - 正式な科目コードが、別の科目名の既存科目に使われている場合（既存データを上書きしない）
 * - 同名の正式科目が既にある場合（科目名だけで別コードに統合しない）
 * - 同名の既存科目が複数ある場合（どれに対応するか推測しない）
 * - 同じ科目名で異なる正式コードが確認できた場合（同じ仮コードへ統合しない）
 * - 同じ科目名の一部で正式コードを確認できない場合（欠落コマを仮コードで統合しない）
 *
 * 上記の保留対象は、入力の並び順に依存せず、既存 DB との突き合わせ前に確定する。
 * 正式コードが不明なコマはこの機能専用の仮コード（CLASSWEB-）を使い、同じコードが
 * 別の科目名にも使われている場合は正式コードを採用しない。教室が不明・不一致のコマは
 * 教室を空欄にし、どちらも値や名前から推測しない。
 *
 * 仮コード（CLASSWEB-）の科目だけは、この機能が過去に作ったものとして同じコードを再利用する。
 */
internal object ClassTimetableMergePlanner {

    const val PROVISIONAL_CODE_PREFIX = "CLASSWEB-"

    data class Plan(
        val coursesToUpsert: List<ClassCourseEntity>,
        val cellsToInsert: List<TimetableCellEntity>,
        val warnings: List<String>,
        val existingCourseCount: Int,
        val newCourseCount: Int,
        val duplicateCellCount: Int,
        val sameSlotDifferentCourseCount: Int,
        val skippedAmbiguousCount: Int,
        val officialCodeCellCount: Int = 0,
        val unknownCodeCellCount: Int = 0,
        val codeConflictCellCount: Int = 0,
        val skippedCodeConflictCount: Int = 0,
        val unknownRoomCellCount: Int = 0,
        val blocked: Boolean = false,
        val blockedReason: String? = null,
    )

    private const val MAX_COURSE_NAME_LENGTH = 200

    private data class NormalizedSlot(
        val dayOfWeek: Int,
        val period: Int,
        val courseName: String,
        val nameKey: String,
        val courseCode: String?,
        val room: String?,
    )

    private data class SlotKey(
        val dayOfWeek: Int,
        val period: Int,
        val nameKey: String,
        val courseCode: String?,
        val room: String?,
    )

    private data class CellGroup(
        val dayOfWeek: Int,
        val period: Int,
        val courseName: String,
        val nameKey: String,
        val courseCode: String?,
        val room: String?,
        val hasMissingCode: Boolean,
        val codeConflict: Boolean,
        val roomConflict: Boolean,
    )

    fun plan(
        slots: List<ClassTimetableSlot>,
        existingCourses: List<ClassCourseEntity>,
        existingCells: List<TimetableCellEntity>,
        academicYear: Int,
    ): Plan {
        val warnings = mutableListOf<String>()

        var invalidSlots = 0
        val normalizedSlots = mutableListOf<NormalizedSlot>()
        slots.forEach { slot ->
            val name = normalizeDisplayName(slot.courseName)
            if (slot.dayOfWeek !in 1..6 ||
                slot.period !in 1..7 ||
                name.isBlank() ||
                name.length > MAX_COURSE_NAME_LENGTH
            ) {
                invalidSlots++
                return@forEach
            }
            normalizedSlots += NormalizedSlot(
                dayOfWeek = slot.dayOfWeek,
                period = slot.period,
                courseName = name,
                nameKey = normalizeKey(name),
                courseCode = ClassTimetableCourseMetadata.normalizeOfficialCourseCode(slot.courseCode),
                room = ClassTimetableCourseMetadata.normalizeRoom(slot.room),
            )
        }
        if (invalidSlots > 0) {
            warnings += "形式が不正なコマを${invalidSlots}件スキップしました。"
        }

        // 年度・学期を取得できないため、既存の時間割がある状態での追加は行わない。
        if (existingCells.isNotEmpty()) {
            val reason = "CLASS の時間割画面から年度・学期を取得できません。" +
                "既存の時間割がある状態で追加すると別の年度・学期の科目が混在するおそれがあるため、" +
                "取り込みを中止しました。既存の時間割を空にしてから再試行してください。"
            warnings += reason
            return Plan(
                coursesToUpsert = emptyList(),
                cellsToInsert = emptyList(),
                warnings = warnings,
                existingCourseCount = 0,
                newCourseCount = 0,
                duplicateCellCount = 0,
                sameSlotDifferentCourseCount = 0,
                skippedAmbiguousCount = 0,
                blocked = true,
                blockedReason = reason,
            )
        }

        // 完全に同じセル・科目・コード・教室の重複リンクだけを畳む。
        // コードや教室が異なる重複は残し、後段で不一致として扱う。
        val uniqueSlots = LinkedHashMap<SlotKey, NormalizedSlot>()
        normalizedSlots.forEach { slot ->
            uniqueSlots.putIfAbsent(
                SlotKey(
                    slot.dayOfWeek,
                    slot.period,
                    slot.nameKey,
                    slot.courseCode,
                    slot.room,
                ),
                slot,
            )
        }

        val slotsByCellAndName =
            LinkedHashMap<Triple<Int, Int, String>, MutableList<NormalizedSlot>>()
        uniqueSlots.values.forEach { slot ->
            slotsByCellAndName.getOrPut(
                Triple(slot.dayOfWeek, slot.period, slot.nameKey),
            ) { mutableListOf() }.add(slot)
        }

        // 同じコマに別の科目名が複数ある入力は、どれを採用するか推測できないため全件除外する。
        val groupsByPosition = slotsByCellAndName.entries.groupBy { it.key.first to it.key.second }
        val conflictingPositions = groupsByPosition
            .filterValues { groups -> groups.size > 1 }
            .keys
        var sameSlotDifferentCourseCount = 0
        if (conflictingPositions.isNotEmpty()) {
            val conflictCount = conflictingPositions.sumOf { position ->
                groupsByPosition[position].orEmpty().sumOf { it.value.size }
            }
            sameSlotDifferentCourseCount += conflictCount
            warnings += "同じコマに別の科目名が複数ある${conflictCount}コマは、" +
                "どれが正しいか推測できないため取り込みません。"
        }

        val activeGroups = slotsByCellAndName.entries.filterNot {
            (it.key.first to it.key.second) in conflictingPositions
        }

        // 同じセル・科目名の重複から、コード・教室を一意に決められる場合だけ採用する。
        val provisionalGroups = activeGroups.map { (key, entries) ->
            val codes = entries.mapNotNull { it.courseCode }.distinct()
            val rooms = entries.mapNotNull { it.room }.distinct()
            val codeConflict = codes.size > 1
            val roomConflict = rooms.size > 1
            CellGroup(
                dayOfWeek = key.first,
                period = key.second,
                courseName = entries.first().courseName,
                nameKey = key.third,
                courseCode = if (codeConflict) null else codes.singleOrNull(),
                room = if (roomConflict) null else rooms.singleOrNull(),
                hasMissingCode = entries.any { it.courseCode == null },
                codeConflict = codeConflict,
                roomConflict = roomConflict,
            )
        }

        // 科目名と正式コードの対応は、既存 DB との突き合わせより前に確定する。
        // 後段の処理順で「正式コードを使う/仮コードにする」が変わらないようにするため。
        val nameToOfficialCodes = mutableMapOf<String, MutableSet<String>>()
        val codeToNames = mutableMapOf<String, MutableSet<String>>()
        activeGroups.forEach { (key, entries) ->
            entries.forEach { entry ->
                val code = entry.courseCode ?: return@forEach
                nameToOfficialCodes.getOrPut(key.third) { mutableSetOf() }.add(code)
                codeToNames.getOrPut(code) { mutableSetOf() }.add(key.third)
            }
        }

        // 同名で異なる正式コードが確認できた科目は、別科目として扱うか保留すべきで、
        // 同じ仮コード（科目名から生成）へ統合してはいけない。ここでは安全側に保留する。
        val ambiguousNameCodes = nameToOfficialCodes
            .filterValues { it.size > 1 }
            .keys

        var codeConflictCellCount = 0
        var fallbackCodeConflictCellCount = 0
        var heldMultipleCodeNameCount = 0
        var heldPartialCodeNameCount = 0
        val finalizedGroups = provisionalGroups.mapNotNull { group ->
            val officialCodes = nameToOfficialCodes[group.nameKey].orEmpty()

            if (group.nameKey in ambiguousNameCodes) {
                // 同名で複数の正式コード。どれが同じ科目か推測できないため全コマ保留。
                codeConflictCellCount++
                heldMultipleCodeNameCount++
                return@mapNotNull null
            }

            if (group.hasMissingCode && officialCodes.isNotEmpty()) {
                // 同じ科目名の別コマでは正式コードを確認できている。
                // コード欠落コマを仮コードにすると同名の正式科目と二重になるため保留する。
                heldPartialCodeNameCount++
                return@mapNotNull null
            }

            val candidateCode = group.courseCode
            val codeSharedByDifferentNames =
                candidateCode != null && codeToNames[candidateCode].orEmpty().size > 1
            if (codeSharedByDifferentNames) {
                // 同じ正式コードが別の科目名にも使われている。正式コードは採用しない。
                codeConflictCellCount++
                fallbackCodeConflictCellCount++
            }
            group.copy(courseCode = if (codeSharedByDifferentNames) null else candidateCode)
        }

        val officialCodeCellCount = finalizedGroups.count { it.courseCode != null }
        val unknownCodeCellCount = finalizedGroups.count { it.courseCode == null }
        val unknownRoomCellCount = finalizedGroups.count { it.room == null }
        val roomConflictCellCount = provisionalGroups.count { it.roomConflict }

        if (heldMultipleCodeNameCount > 0) {
            warnings += "同じ科目名で異なる正式な科目コードが確認できた" +
                "${heldMultipleCodeNameCount}コマは、同じ仮コードへ統合せず取り込みません。" +
                "別学期・別科目の可能性があるため、対象を保留しました。"
        }
        if (heldPartialCodeNameCount > 0) {
            warnings += "同じ科目名の一部で正式な科目コードを確認できない" +
                "${heldPartialCodeNameCount}コマは、仮コードで統合せず取り込みません。" +
                "対象を保留しました。"
        }
        if (fallbackCodeConflictCellCount > 0) {
            warnings += "科目コードが重複・不一致の${fallbackCodeConflictCellCount}コマは、" +
                "正式コードを使わず仮コード（${PROVISIONAL_CODE_PREFIX}…）で保存します。"
        }
        val missingCodeCellCount =
            (unknownCodeCellCount - fallbackCodeConflictCellCount).coerceAtLeast(0)
        if (missingCodeCellCount > 0) {
            warnings += "科目コードを確認できない${missingCodeCellCount}コマは、" +
                "正式コードを使わず仮コード（${PROVISIONAL_CODE_PREFIX}…）で保存します。"
        }
        if (roomConflictCellCount > 0) {
            warnings += "同じコマで複数の教室が確認できた${roomConflictCellCount}コマは、" +
                "教室を推測せず空欄にします。"
        }
        val missingRoomCellCount =
            (unknownRoomCellCount - roomConflictCellCount).coerceAtLeast(0)
        if (missingRoomCellCount > 0) {
            warnings += "教室を確認できない${missingRoomCellCount}コマは、教室を空欄にします。"
        }

        val existingByName = existingCourses
            .groupBy { normalizeKey(it.name) }
            .mapValues { it.value.toMutableList() }
            .toMutableMap()
        val existingByCode = existingCourses.associateBy { it.code }.toMutableMap()
        val newCourses = LinkedHashMap<String, ClassCourseEntity>()
        val newCells = mutableListOf<TimetableCellEntity>()
        val workingCells = existingCells.toMutableList()

        var existingCourseCount = 0
        var duplicateCellCount = 0
        var skippedAmbiguousCount = heldMultipleCodeNameCount + heldPartialCodeNameCount
        var skippedOfficialNameCount = 0
        var skippedCodeConflictCount = 0
        var provisionalReuseCount = 0
        var newProvisionalCourseCount = 0
        var newOfficialCourseCount = 0

        fun registerCourse(course: ClassCourseEntity) {
            newCourses[course.code] = course
            existingByCode[course.code] = course
            existingByName.getOrPut(normalizeKey(course.name)) { mutableListOf() }.add(course)
            if (isProvisionalCourseCode(course.code)) {
                newProvisionalCourseCount++
            } else {
                newOfficialCourseCount++
            }
        }

        for (group in finalizedGroups) {
            val nameKey = group.nameKey
            val officialCode = group.courseCode
            val generatedCode = generatedCourseCode(group.courseName)

            val courseCode = if (officialCode != null) {
                val codeOwner = existingByCode[officialCode]
                when {
                    codeOwner != null -> {
                        if (normalizeKey(codeOwner.name) != nameKey) {
                            // 既存の別科目が使っている正式コード。上書きも付け替えもしない。
                            skippedCodeConflictCount++
                            continue
                        }
                        if (!newCourses.containsKey(codeOwner.code)) {
                            existingCourseCount++
                        }
                        codeOwner.code
                    }

                    else -> {
                        val existingMatches = existingByName[nameKey].orEmpty()
                        val officialMatches = existingMatches.filterNot {
                            isProvisionalCourseCode(it.code)
                        }
                        when {
                            officialMatches.isNotEmpty() -> {
                                // 同名の正式科目が別コードで既にある。名前だけで統合しない。
                                skippedOfficialNameCount++
                                continue
                            }

                            existingMatches.size > 1 -> {
                                skippedAmbiguousCount++
                                continue
                            }

                            existingMatches.size == 1 -> {
                                val existing = existingMatches.single()
                                if (isProvisionalCourseCode(existing.code) &&
                                    existing.code == generatedCode
                                ) {
                                    // 既存の仮科目（出席情報の紐づき先）を壊さず再利用する。
                                    provisionalReuseCount++
                                    existingCourseCount++
                                    existing.code
                                } else {
                                    skippedAmbiguousCount++
                                    continue
                                }
                            }

                            else -> {
                                val course = ClassCourseEntity(
                                    code = officialCode,
                                    name = group.courseName,
                                    instructor = "",
                                    credits = null,
                                    attendanceRate = null,
                                )
                                registerCourse(course)
                                officialCode
                            }
                        }
                    }
                }
            } else {
                // 正式コードが無い場合は、この機能専用の仮コードだけを使う。
                val existingMatches = existingByName[nameKey].orEmpty()
                val officialMatches = existingMatches.filterNot {
                    isProvisionalCourseCode(it.code)
                }
                val codeOwner = existingByCode[generatedCode]
                when {
                    officialMatches.isNotEmpty() -> {
                        skippedOfficialNameCount++
                        continue
                    }

                    existingMatches.size > 1 -> {
                        skippedAmbiguousCount++
                        continue
                    }

                    existingMatches.size == 1 &&
                        existingMatches.single().code != generatedCode -> {
                        skippedAmbiguousCount++
                        continue
                    }

                    codeOwner != null -> {
                        if (!isProvisionalCourseCode(codeOwner.code) ||
                            normalizeKey(codeOwner.name) != nameKey
                        ) {
                            skippedAmbiguousCount++
                            continue
                        }
                        if (!newCourses.containsKey(codeOwner.code)) {
                            existingCourseCount++
                        }
                        codeOwner.code
                    }

                    else -> {
                        val course = ClassCourseEntity(
                            code = generatedCode,
                            name = group.courseName,
                            instructor = "",
                            credits = null,
                            attendanceRate = null,
                        )
                        registerCourse(course)
                        generatedCode
                    }
                }
            }

            val duplicate = workingCells.any { existing ->
                existing.dayOfWeek == group.dayOfWeek &&
                    existing.period == group.period &&
                    normalizeKey(existing.courseName) == nameKey
            }
            if (duplicate) {
                duplicateCellCount++
                continue
            }

            val hasOtherCourseInSlot = workingCells.any {
                it.dayOfWeek == group.dayOfWeek && it.period == group.period
            }
            if (hasOtherCourseInSlot) {
                sameSlotDifferentCourseCount++
                continue
            }

            val cell = TimetableCellEntity(
                dayOfWeek = group.dayOfWeek,
                period = group.period,
                courseCode = courseCode,
                courseName = group.courseName,
                instructor = "",
                room = group.room,
                isOnline = false,
                isMultiSession = false,
            )
            newCells += cell
            workingCells += cell
        }

        if (skippedOfficialNameCount > 0) {
            warnings += "同名の正式科目が既にある${skippedOfficialNameCount}コマは、" +
                "科目名だけで正式な科目コードに統合しないため取り込みません。"
        }
        val existingAmbiguousSkipCount = skippedAmbiguousCount -
            heldMultipleCodeNameCount - heldPartialCodeNameCount
        if (existingAmbiguousSkipCount > 0) {
            warnings += "同名の既存科目が複数ある、または仮コードを確認できない" +
                "${existingAmbiguousSkipCount}コマは、推測せず取り込みません。"
        }
        if (skippedCodeConflictCount > 0) {
            warnings += "正式な科目コードが既存の別科目と競合する${skippedCodeConflictCount}コマは、" +
                "既存データを上書きしないため取り込みません。"
        }
        if (existingCourseCount > 0) {
            warnings += "既存の科目とコード・科目名が一致する${existingCourseCount}コマは既存科目を再利用します。" +
                "CLASS の時間割画面から年度・学期を確認できないため、" +
                "既存科目が別の年度・学期のものではないか確認してください。"
        }
        if (provisionalReuseCount > 0) {
            warnings += "既存の仮コード科目を再利用した${provisionalReuseCount}コマは、" +
                "出席情報との紐づきを壊さないため、正式コードへ置き換えません。"
        }
        if (newProvisionalCourseCount > 0) {
            warnings += "正式な科目コードを確認できない科目は仮コード（${PROVISIONAL_CODE_PREFIX}…）で追加します" +
                "（${newProvisionalCourseCount}科目）。仮コードはCLASSの正式な科目コードや" +
                "出席情報と自動で紐づきません。"
        }
        if (newOfficialCourseCount > 0) {
            warnings += "正式な科目コードを確認できた${newOfficialCourseCount}科目は、" +
                "そのコードで追加します。既存の科目は上書きしません。"
        }
        if (duplicateCellCount > 0) {
            warnings += "既存と同じ科目・コマが${duplicateCellCount}件あるため、重複保存しません。"
        }
        if (academicYear <= 0) {
            warnings += "年度が未設定です。設定画面で年度を確認してください。"
        }
        warnings += "取り込むのは科目名・曜日・時限・科目コード・教室です。" +
            "教員・単位数は CLASS の詳細から取り込みません。"
        warnings += "CLASS の時間割画面から年度・学期は取得できません。" +
            "既存の時間割がある場合は混在防止のため取り込みを中止します。"
        if (newProvisionalCourseCount > 0 || provisionalReuseCount > 0) {
            warnings += "仮コード（${PROVISIONAL_CODE_PREFIX}…）の科目は、CLASSの正式な科目コードや" +
                "出席情報と自動で紐づきません。"
        }

        return Plan(
            coursesToUpsert = newCourses.values.toList(),
            cellsToInsert = newCells,
            warnings = warnings,
            existingCourseCount = existingCourseCount,
            newCourseCount = newCourses.size,
            duplicateCellCount = duplicateCellCount,
            sameSlotDifferentCourseCount = sameSlotDifferentCourseCount,
            skippedAmbiguousCount = skippedAmbiguousCount,
            officialCodeCellCount = officialCodeCellCount,
            unknownCodeCellCount = unknownCodeCellCount,
            codeConflictCellCount = codeConflictCellCount,
            skippedCodeConflictCount = skippedCodeConflictCount,
            unknownRoomCellCount = unknownRoomCellCount,
        )
    }

    /**
     * 科目コードが DOM に無いため、科目名から決定的な仮コードを作る。
     * 実在の科目コードと誤認しないよう CLASSWEB- 接頭辞を付ける。
     */
    internal fun generatedCourseCode(courseName: String): String {
        val normalized = normalizeKey(courseName)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        val hex = digest.take(10).joinToString("") { "%02x".format(it) }
        return PROVISIONAL_CODE_PREFIX + hex
    }

    internal fun isProvisionalCourseCode(code: String): Boolean =
        code.startsWith(PROVISIONAL_CODE_PREFIX)

    private fun normalizeDisplayName(value: String): String =
        value.replace('\u00a0', ' ')
            .trim()
            .replace(Regex("\\s+"), " ")

    /**
     * 照合用の名前。全角/半角は揃えるが、内部の空白は削らない。
     * 「合成 科目A」と「合成科目A」は別の科目名として扱う。
     */
    private fun normalizeKey(value: String): String =
        java.text.Normalizer.normalize(normalizeDisplayName(value), java.text.Normalizer.Form.NFKC)
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}

/** Web の時間割画面から読み取った内容を、既存データを消さずに Room へ追加する。 */
@Singleton
class ClassTimetableImportRepository @Inject constructor(
    private val database: TusDatabase,
    private val classCourseDao: ClassCourseDao,
    private val timetableDao: TimetableDao,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun preview(slots: List<ClassTimetableSlot>): ClassTimetablePreview =
        withContext(Dispatchers.IO) {
            val academicYear = settingsRepository.current().academicYear
            val plan = ClassTimetableMergePlanner.plan(
                slots = slots,
                existingCourses = classCourseDao.getAll(),
                existingCells = timetableDao.getAll(),
                academicYear = academicYear,
            )
            ClassTimetablePreview(
                slots = slots,
                warnings = plan.warnings,
                academicYear = academicYear,
                blocked = plan.blocked,
                blockedReason = plan.blockedReason,
                existingCourseCount = plan.existingCourseCount,
                newCourseCount = plan.newCourseCount,
                newCellCount = plan.cellsToInsert.size,
                duplicateCellCount = plan.duplicateCellCount,
                sameSlotDifferentCourseCount = plan.sameSlotDifferentCourseCount,
                skippedAmbiguousCount = plan.skippedAmbiguousCount,
                officialCodeCellCount = plan.officialCodeCellCount,
                unknownCodeCellCount = plan.unknownCodeCellCount,
                codeConflictCellCount = plan.codeConflictCellCount,
                skippedCodeConflictCount = plan.skippedCodeConflictCount,
                unknownRoomCellCount = plan.unknownRoomCellCount,
            )
        }

    /** 既存行の DELETE は行わない。計画と保存を同一トランザクションで行う。 */
    suspend fun save(slots: List<ClassTimetableSlot>): ClassTimetableSaveResult =
        withContext(Dispatchers.IO) {
            // DataStore の読み取りは DB トランザクションの外で済ませ、ロック時間を短くする。
            val academicYear = settingsRepository.current().academicYear
            database.withTransaction {
                val plan = ClassTimetableMergePlanner.plan(
                    slots = slots,
                    existingCourses = classCourseDao.getAll(),
                    existingCells = timetableDao.getAll(),
                    academicYear = academicYear,
                )
                if (plan.blocked) {
                    return@withTransaction ClassTimetableSaveResult(
                        insertedCells = 0,
                        createdCourses = 0,
                        duplicateCells = 0,
                        sameSlotDifferentCourses = 0,
                        skippedAmbiguous = 0,
                        warnings = plan.warnings,
                        blocked = true,
                        blockedReason = plan.blockedReason,
                    )
                }
                if (plan.coursesToUpsert.isNotEmpty()) {
                    classCourseDao.upsertAll(plan.coursesToUpsert)
                }
                if (plan.cellsToInsert.isNotEmpty()) {
                    timetableDao.insertAll(plan.cellsToInsert)
                }
                ClassTimetableSaveResult(
                    insertedCells = plan.cellsToInsert.size,
                    createdCourses = plan.newCourseCount,
                    duplicateCells = plan.duplicateCellCount,
                    sameSlotDifferentCourses = plan.sameSlotDifferentCourseCount,
                    skippedAmbiguous = plan.skippedAmbiguousCount,
                    warnings = plan.warnings,
                    officialCodeCellCount = plan.officialCodeCellCount,
                    unknownCodeCellCount = plan.unknownCodeCellCount,
                    codeConflictCellCount = plan.codeConflictCellCount,
                    skippedCodeConflictCount = plan.skippedCodeConflictCount,
                    unknownRoomCellCount = plan.unknownRoomCellCount,
                )
            }
        }
}
