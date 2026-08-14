package com.deskdock.app.model

data class HourlyForecast(val hour: String, val temperatureC: Int, val rainChance: Int, val weatherCode: Int)
data class DailyForecast(val dayLabel: String, val maxC: Int, val minC: Int, val rainChance: Int, val weatherCode: Int)
data class WeatherSnapshot(
    val temperatureC: Int,
    val feelsLikeC: Int,
    val weatherCode: Int,
    val todayMaxC: Int,
    val todayMinC: Int,
    val hourly: List<HourlyForecast>,
    val nextDays: List<DailyForecast>,
    val updatedAtMillis: Long = System.currentTimeMillis()
)
data class CalendarEvent(val title: String, val startMillis: Long, val endMillis: Long, val allDay: Boolean, val location: String? = null)
data class BatteryInfo(val percent: Int, val charging: Boolean)
