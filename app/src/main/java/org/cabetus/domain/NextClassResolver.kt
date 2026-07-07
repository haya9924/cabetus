package org.cabetus.domain

import org.cabetus.data.local.TimetableCellEntity
import java.time.LocalDateTime
import java.time.LocalTime

/** ハイライト対象のコマ（時間割画面用）。 */
data class HighlightSlot(val dayOfWeek: Int, val period: Int, val isOngoing: Boolean)

/** 次の授業情報（ウィジェット用）。 */
data class NextClassInfo(
    val cell: TimetableCellEntity,
    val dayOfWeek: Int,
    val period: Int,
    val start: LocalTime,
    val end: LocalTime,
    val isOngoing: Boolean,
)

/**
 * 進行中の授業を優先し、なければ今日以降（7日先まで）で最初の授業を返す。
 * 曜日は 1=月 … 6=土（日曜=授業なし）。純粋関数なので JVM テスト可能。
 */
object NextClassResolver {

    /**
     * 授業が存在するコマ集合 (dayOfWeek, period) からハイライト対象を求める。
     */
    fun resolveSlot(
        slots: Set<Pair<Int, Int>>,
        now: LocalDateTime = LocalDateTime.now(),
    ): HighlightSlot? {
        if (slots.isEmpty()) return null
        for (offset in 0..7) {
            val dow = now.toLocalDate().plusDays(offset.toLong()).dayOfWeek.value // 1..7
            val periods = slots.filter { it.first == dow }.map { it.second }.sorted()
            for (p in periods) {
                val pt = PeriodTimes.of(p) ?: continue
                if (offset == 0) {
                    val time = now.toLocalTime()
                    if (time.isAfter(pt.end)) continue // 今日の終わったコマは飛ばす
                    val ongoing = !time.isBefore(pt.start) && !time.isAfter(pt.end)
                    return HighlightSlot(dow, p, ongoing)
                }
                return HighlightSlot(dow, p, false)
            }
        }
        return null
    }

    /**
     * 時間割セルから次の授業情報を求める（ウィジェット用）。
     */
    fun resolve(
        cells: List<TimetableCellEntity>,
        now: LocalDateTime = LocalDateTime.now(),
    ): NextClassInfo? {
        val slots = cells.map { it.dayOfWeek to it.period }.toSet()
        val slot = resolveSlot(slots, now) ?: return null
        val cell = cells.firstOrNull {
            it.dayOfWeek == slot.dayOfWeek && it.period == slot.period
        } ?: return null
        val pt = PeriodTimes.of(slot.period) ?: return null
        return NextClassInfo(cell, slot.dayOfWeek, slot.period, pt.start, pt.end, slot.isOngoing)
    }
}
