package org.cabetus.domain

import java.time.LocalTime

/** 1時限分の開始・終了時刻。 */
data class PeriodTime(val period: Int, val start: LocalTime, val end: LocalTime)

/**
 * 時限時刻表。時間割PDFの脚注からパースできればそれを、失敗時はキャンパス別の既定値を使う。
 * 既定は葛飾キャンパスの時刻。
 */
object PeriodTimes {

    /** 葛飾キャンパス（既定）: 1限08:50〜10:20 … 7限19:50〜21:20 */
    private val KATSUSHIKA = listOf(
        PeriodTime(1, LocalTime.of(8, 50), LocalTime.of(10, 20)),
        PeriodTime(2, LocalTime.of(10, 30), LocalTime.of(12, 0)),
        PeriodTime(3, LocalTime.of(13, 0), LocalTime.of(14, 30)),
        PeriodTime(4, LocalTime.of(14, 40), LocalTime.of(16, 10)),
        PeriodTime(5, LocalTime.of(16, 20), LocalTime.of(17, 50)),
        PeriodTime(6, LocalTime.of(18, 10), LocalTime.of(19, 40)),
        PeriodTime(7, LocalTime.of(19, 50), LocalTime.of(21, 20)),
    )

    /** 神楽坂・野田も同一の時程を既定として用いる（PDF脚注があれば上書きされる）。 */
    fun defaultFor(@Suppress("UNUSED_PARAMETER") campus: Campus): List<PeriodTime> = KATSUSHIKA

    fun of(period: Int, custom: List<PeriodTime>? = null): PeriodTime? {
        val table = custom?.takeIf { it.isNotEmpty() } ?: KATSUSHIKA
        return table.firstOrNull { it.period == period }
    }
}
