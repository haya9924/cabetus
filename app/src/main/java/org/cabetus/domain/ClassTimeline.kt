package org.cabetus.domain

import org.cabetus.data.local.TimetableCellEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 1コマの進行状態。 */
enum class ClassPhase { UPCOMING, ONGOING, FINISHED }

/** ホーム表示用の、ある1日のコマの状態。 */
data class ClassSlotState(
    val period: Int,
    val courseCode: String,
    val name: String,
    val room: String?,
    val isOnline: Boolean,
    val start: LocalTime?,
    val end: LocalTime?,
    val phase: ClassPhase,
    /** 実施中のときだけ、終了時刻までの残り秒数。 */
    val remainingSeconds: Long?,
) {
    val isCurrent: Boolean get() = phase == ClassPhase.ONGOING
}

/** 今日の時間割と、実施中・次の授業。 */
data class ClassTimeline(
    val date: LocalDate,
    /** 判定に使った現在時刻（「開始まで」の表示に使う）。 */
    val now: LocalDateTime?,
    val slots: List<ClassSlotState>,
    val current: ClassSlotState?,
    val next: ClassSlotState?,
) {
    val hasClassesToday: Boolean get() = slots.isNotEmpty()
}

/**
 * 時間割セルと現在時刻から、ホーム表示用の今日のコマ・残り時間を求める純粋ロジック。
 *
 * 時限の開始・終了時刻は [PeriodTimes]（アプリ内の既定値）を基準にする。
 * 端末の現在時刻をそのまま使い、タイムゾーン変換は行わない。
 */
object ClassTimelineResolver {

    fun resolve(
        cells: List<TimetableCellEntity>,
        nameByCode: Map<String, String>,
        now: LocalDateTime,
        periodTimes: List<PeriodTime>? = null,
    ): ClassTimeline {
        val nowTime = now.toLocalTime()
        val dow = now.toLocalDate().dayOfWeek.value // 1=Mon..7=Sun
        val slots = cells
            .filter { it.dayOfWeek == dow }
            .sortedBy { it.period }
            .map { c ->
                val pt = PeriodTimes.of(c.period, periodTimes)
                val phase = phase(pt?.start, pt?.end, nowTime)
                ClassSlotState(
                    period = c.period,
                    courseCode = c.courseCode,
                    name = nameByCode[c.courseCode]?.takeIf { it.isNotBlank() }
                        ?: c.courseName.ifBlank { c.courseCode },
                    room = c.room,
                    isOnline = c.isOnline,
                    start = pt?.start,
                    end = pt?.end,
                    phase = phase,
                    remainingSeconds = if (phase == ClassPhase.ONGOING && pt != null) {
                        remainingSeconds(pt.end, nowTime)
                    } else {
                        null
                    },
                )
            }
        return ClassTimeline(
            date = now.toLocalDate(),
            now = now,
            slots = slots,
            current = slots.firstOrNull { it.phase == ClassPhase.ONGOING },
            next = slots.firstOrNull {
                it.start != null && it.start.isAfter(nowTime)
            },
        )
    }

    /** 開始時刻ちょうど〜終了時刻ちょうどを実施中として扱う。 */
    fun phase(start: LocalTime?, end: LocalTime?, now: LocalTime): ClassPhase = when {
        start == null || end == null -> ClassPhase.UPCOMING
        now.isBefore(start) -> ClassPhase.UPCOMING
        now.isAfter(end) -> ClassPhase.FINISHED
        else -> ClassPhase.ONGOING
    }

    /** 終了時刻までの残り秒数（負にはならない）。 */
    fun remainingSeconds(end: LocalTime, now: LocalTime): Long =
        Duration.between(now, end).seconds.coerceAtLeast(0L)

    /** 開始時刻までの秒数（過去なら0）。同日の時刻同士を比較する。 */
    fun secondsUntil(target: LocalTime, now: LocalTime): Long =
        Duration.between(now, target).seconds.coerceAtLeast(0L)

    /** 「残り12分34秒」のようなリアルタイム表示用文字列。 */
    fun formatRemaining(seconds: Long): String = "残り" + formatDuration(seconds)

    /** 「12分34秒」のような長さ表記。 */
    fun formatDuration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0L)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return when {
            hours > 0 -> "%d時間%02d分%02d秒".format(hours, minutes, secs)
            minutes > 0 -> "%d分%02d秒".format(minutes, secs)
            else -> "%d秒".format(secs)
        }
    }
}
