package org.cabetus.data.weather

import org.cabetus.domain.Campus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 表示用の天気サマリ。 */
data class WeatherSummary(
    val emoji: String,
    val label: String,
    val currentTemp: Double?,
    val maxTemp: Double?,
    val minTemp: Double?,
    val precipProbability: Int?,
    /** 3時間ごと・12時間分の予報（現在+3/6/9/12h）。 */
    val hourly: List<HourlyForecast> = emptyList(),
)

/** 3時間ごとの予報1点。 */
data class HourlyForecast(
    val hourLabel: String,
    val emoji: String,
    val temp: Int?,
    val precip: Int?,
)

@Serializable
private data class OpenMeteoResponse(
    val current: Current? = null,
    val daily: Daily? = null,
    val hourly: Hourly? = null,
) {
    @Serializable
    data class Current(
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
    )

    @Serializable
    data class Daily(
        @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
        @SerialName("temperature_2m_max") val tempMax: List<Double> = emptyList(),
        @SerialName("temperature_2m_min") val tempMin: List<Double> = emptyList(),
        @SerialName("precipitation_probability_max") val precipMax: List<Int> = emptyList(),
    )

    @Serializable
    data class Hourly(
        val time: List<String> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int> = emptyList(),
        @SerialName("precipitation_probability") val precipProbability: List<Int> = emptyList(),
    )
}

/** Open-Meteo（APIキー不要）から天気を取得。キャンパスごとに30分キャッシュ。 */
@Singleton
class WeatherRepository @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private data class Cached(val at: Long, val summary: WeatherSummary)
    private val cache = mutableMapOf<Campus, Cached>()
    private val ttl = 30 * 60 * 1000L

    suspend fun getWeather(campus: Campus): WeatherSummary? = withContext(Dispatchers.IO) {
        cache[campus]?.let { if (System.currentTimeMillis() - it.at < ttl) return@withContext it.summary }
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${campus.latitude}&longitude=${campus.longitude}" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&hourly=temperature_2m,weather_code,precipitation_probability" +
            "&timezone=Asia%2FTokyo&forecast_days=2"
        try {
            val body = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }
            val parsed = json.decodeFromString(OpenMeteoResponse.serializer(), body)
            val code = parsed.current?.weatherCode ?: parsed.daily?.weatherCode?.firstOrNull() ?: 0
            val hourly = parsed.hourly?.let {
                pickHourly(it.time, it.temperature, it.weatherCode, it.precipProbability)
            } ?: emptyList()
            val summary = WeatherSummary(
                emoji = WmoCode.emoji(code),
                label = WmoCode.label(code),
                currentTemp = parsed.current?.temperature,
                maxTemp = parsed.daily?.tempMax?.firstOrNull(),
                minTemp = parsed.daily?.tempMin?.firstOrNull(),
                precipProbability = parsed.daily?.precipMax?.firstOrNull(),
                hourly = hourly,
            )
            cache[campus] = Cached(System.currentTimeMillis(), summary)
            summary
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * 現在時刻以降の最初の時刻から3時間刻みで5点（現在+3/6/9/12h）を抽出する。
         * 時刻文字列はローカル（Asia/Tokyo）の "yyyy-MM-ddTHH:mm" 形式。
         */
        fun pickHourly(
            times: List<String>,
            temps: List<Double>,
            codes: List<Int>,
            precs: List<Int>,
            now: LocalDateTime = LocalDateTime.now(ZoneId.of("Asia/Tokyo")),
        ): List<HourlyForecast> {
            if (times.isEmpty()) return emptyList()
            val startIdx = times.indexOfFirst {
                runCatching { LocalDateTime.parse(it) }.getOrNull()?.let { t -> !t.isBefore(now) } == true
            }
            if (startIdx < 0) return emptyList()
            return (0..4).mapNotNull { step ->
                val i = startIdx + step * 3
                val t = times.getOrNull(i) ?: return@mapNotNull null
                val ldt = runCatching { LocalDateTime.parse(t) }.getOrNull() ?: return@mapNotNull null
                HourlyForecast(
                    hourLabel = "${ldt.hour}時",
                    emoji = WmoCode.emoji(codes.getOrNull(i) ?: 0),
                    temp = temps.getOrNull(i)?.let { Math.round(it).toInt() },
                    precip = precs.getOrNull(i),
                )
            }
        }
    }
}

/** WMO weather code → 絵文字・日本語ラベル。 */
object WmoCode {
    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67 -> "🌧️"
        in 71..77 -> "🌨️"
        in 80..82 -> "🌦️"
        in 85..86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    fun label(code: Int): String = when (code) {
        0 -> "快晴"
        1 -> "晴れ"
        2 -> "薄曇り"
        3 -> "曇り"
        45, 48 -> "霧"
        in 51..57 -> "霧雨"
        in 61..67 -> "雨"
        in 71..77 -> "雪"
        in 80..82 -> "にわか雨"
        in 85..86 -> "にわか雪"
        in 95..99 -> "雷雨"
        else -> "—"
    }
}
