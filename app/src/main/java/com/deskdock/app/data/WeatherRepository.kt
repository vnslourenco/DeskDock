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
        val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,apparent_temperature,weather_code&hourly=temperature_2m,precipitation_probability,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=4"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            parse(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally { connection.disconnect() }
    }

    private fun parse(root: JSONObject): WeatherSnapshot {
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val hourly = root.getJSONObject("hourly")
        val currentTime = current.optString("time")
        val currentHourIso = if (currentTime.length >= 13) currentTime.substring(0,13) + ":00" else currentTime
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val rain = hourly.getJSONArray("precipitation_probability")
        val codes = hourly.getJSONArray("weather_code")
        val hours = mutableListOf<HourlyForecast>()
        for (i in 0 until times.length()) {
            val iso = times.getString(i)
            if (iso >= currentHourIso) {
                hours += HourlyForecast(iso.substringAfter('T').substringBefore(':') + "h", temps.getDouble(i).roundToInt(), rain.optDouble(i,0.0).roundToInt(), codes.getInt(i))
                if (hours.size == 6) break
            }
        }
        val dates = daily.getJSONArray("time")
        val maxs = daily.getJSONArray("temperature_2m_max")
        val mins = daily.getJSONArray("temperature_2m_min")
        val rains = daily.getJSONArray("precipitation_probability_max")
        val dailyCodes = daily.getJSONArray("weather_code")
        val formatter = DateTimeFormatter.ofPattern("EEE", Locale("pt","BR"))
        val next = mutableListOf<DailyForecast>()
        for (i in 1 until minOf(4, dates.length())) {
            val label = LocalDate.parse(dates.getString(i)).format(formatter).replaceFirstChar { it.uppercase(Locale("pt","BR")) }
            next += DailyForecast(label, maxs.getDouble(i).roundToInt(), mins.getDouble(i).roundToInt(), rains.optDouble(i,0.0).roundToInt(), dailyCodes.getInt(i))
        }
        return WeatherSnapshot(current.getDouble("temperature_2m").roundToInt(), current.getDouble("apparent_temperature").roundToInt(), current.getInt("weather_code"), maxs.getDouble(0).roundToInt(), mins.getDouble(0).roundToInt(), hours, next)
    }
}
