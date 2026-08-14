package com.deskdock.app.data

import android.os.Handler
import android.os.Looper
import com.deskdock.app.model.DailyForecast
import com.deskdock.app.model.HourlyForecast
import com.deskdock.app.model.WeatherSnapshot
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class WeatherRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetch(latitude: Double, longitude: Double, callback: (Result<WeatherSnapshot>) -> Unit) {
        executor.execute {
            val result = runCatching { requestAndParse(latitude, longitude) }
            mainHandler.post { callback(result) }
        }
    }

    private fun requestAndParse(latitude: Double, longitude: Double): WeatherSnapshot {
        // ECMWF IFS full-resolution forecast for temperature/condition.
        // Rain probability is requested separately because it is a probabilistic ensemble product.
        val forecastEndpoint =
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&models=ecmwf_ifs" +
                "&hourly=temperature_2m,apparent_temperature,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=4"

        val probabilityEndpoint =
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&hourly=precipitation_probability" +
                "&daily=precipitation_probability_max" +
                "&timezone=auto&forecast_days=4"

        val forecast = getJson(forecastEndpoint)
        val probability = runCatching { getJson(probabilityEndpoint) }.getOrNull()
        return parse(forecast, probability)
    }

    private fun getJson(endpoint: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DeskDock/0.2")
        }
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(root: JSONObject, probabilityRoot: JSONObject?): WeatherSnapshot {
        val daily = root.getJSONObject("daily")
        val hourly = root.getJSONObject("hourly")

        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val feels = hourly.getJSONArray("apparent_temperature")
        val codes = hourly.getJSONArray("weather_code")

        val probabilityByTime = mutableMapOf<String, Int>()
        val probabilityByDate = mutableMapOf<String, Int>()
        probabilityRoot?.let { pRoot ->
            runCatching {
                val pHourly = pRoot.getJSONObject("hourly")
                val pTimes = pHourly.getJSONArray("time")
                val pValues = pHourly.getJSONArray("precipitation_probability")
                for (i in 0 until minOf(pTimes.length(), pValues.length())) {
                    if (!pValues.isNull(i)) probabilityByTime[pTimes.getString(i)] = pValues.optDouble(i, 0.0).roundToInt()
                }

                val pDaily = pRoot.getJSONObject("daily")
                val pDates = pDaily.getJSONArray("time")
                val pDailyValues = pDaily.getJSONArray("precipitation_probability_max")
                for (i in 0 until minOf(pDates.length(), pDailyValues.length())) {
                    if (!pDailyValues.isNull(i)) probabilityByDate[pDates.getString(i)] = pDailyValues.optDouble(i, 0.0).roundToInt()
                }
            }
        }

        val currentHourIso = LocalDateTime.now()
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

        var currentIndex = 0
        for (i in 0 until times.length()) {
            if (times.getString(i) >= currentHourIso) {
                currentIndex = i
                break
            }
        }

        val hours = mutableListOf<HourlyForecast>()
        for (i in currentIndex until times.length()) {
            val iso = times.getString(i)
            hours += HourlyForecast(
                hour = iso.substringAfter('T').substringBefore(':') + "h",
                temperatureC = temps.getDouble(i).roundToInt(),
                rainChance = probabilityByTime[iso] ?: 0,
                weatherCode = codes.getInt(i)
            )
            if (hours.size == 6) break
        }

        val dates = daily.getJSONArray("time")
        val maxs = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val dailyCodes = daily.getJSONArray("weather_code")
        val formatter = DateTimeFormatter.ofPattern("EEE", Locale("pt", "BR"))
        val next = mutableListOf<DailyForecast>()

        for (i in 1 until minOf(4, dates.length())) {
            val date = dates.getString(i)
            val label = LocalDate.parse(date)
                .format(formatter)
                .replaceFirstChar { it.uppercase(Locale("pt", "BR")) }
            next += DailyForecast(
                dayLabel = label,
                maxC = maxs.getDouble(i).roundToInt(),
                minC = mins.getDouble(i).roundToInt(),
                rainChance = probabilityByDate[date] ?: 0,
                weatherCode = dailyCodes.getInt(i)
            )
        }

        return WeatherSnapshot(
            temperatureC = temps.getDouble(currentIndex).roundToInt(),
            feelsLikeC = feels.getDouble(currentIndex).roundToInt(),
            weatherCode = codes.getInt(currentIndex),
            todayMaxC = maxs.getDouble(0).roundToInt(),
            todayMinC = mins.getDouble(0).roundToInt(),
            hourly = hours,
            nextDays = next
        )
    }
}
