package org.cabetus

import org.cabetus.data.weather.WeatherRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class WeatherHourlyTest {

    private fun hours(startHour: Int, count: Int): List<String> =
        (0 until count).map { "2026-07-06T%02d:00".format((startHour + it) % 24) }

    @Test
    fun picksEveryThirdHourStartingFromNow() {
        // 00:00 起点で 24 時間分。現在 09:15 → 最初に 10:00 が拾われ、3時間刻みで5点。
        val times = hours(0, 24)
        val temps = List(24) { it.toDouble() }
        val codes = List(24) { 0 }
        val precs = List(24) { it }
        val now = LocalDateTime.of(2026, 7, 6, 9, 15)

        val result = WeatherRepository.pickHourly(times, temps, codes, precs, now)

        assertEquals(5, result.size)
        // 最初は 10 時（09:15 以降で最初の丸時）
        assertEquals("10時", result[0].hourLabel)
        assertEquals("13時", result[1].hourLabel)
        assertEquals("22時", result[4].hourLabel)
        assertEquals(10, result[0].temp)
        assertEquals(10, result[0].precip)
    }

    @Test
    fun exactHourIsIncluded() {
        val times = hours(0, 24)
        val temps = List(24) { it.toDouble() }
        val codes = List(24) { 0 }
        val precs = List(24) { 0 }
        val now = LocalDateTime.of(2026, 7, 6, 9, 0)

        val result = WeatherRepository.pickHourly(times, temps, codes, precs, now)

        assertEquals("9時", result[0].hourLabel)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        val result = WeatherRepository.pickHourly(
            emptyList(), emptyList(), emptyList(), emptyList(),
            LocalDateTime.of(2026, 7, 6, 9, 0),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun truncatesWhenNotEnoughFutureHours() {
        // 現在以降のデータが少ない場合は取れる分だけ返す。
        val times = hours(8, 4) // 08,09,10,11
        val temps = List(4) { 20.0 }
        val codes = List(4) { 0 }
        val precs = List(4) { 0 }
        val now = LocalDateTime.of(2026, 7, 6, 9, 0)

        val result = WeatherRepository.pickHourly(times, temps, codes, precs, now)

        // 09時のみ（次は12時だがデータなし）
        assertEquals(1, result.size)
        assertEquals("9時", result[0].hourLabel)
    }
}
