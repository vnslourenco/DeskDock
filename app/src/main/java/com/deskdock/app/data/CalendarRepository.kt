package com.deskdock.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.deskdock.app.model.CalendarEvent

class CalendarRepository(private val context: Context) {
    data class CalendarState(val calendars: Int, val events: List<CalendarEvent>)

    fun loadState(limit: Int = 5): CalendarState {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return CalendarState(0, emptyList())
        return CalendarState(countCalendars(), loadUpcoming(limit))
    }

    private fun countCalendars(): Int = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        null, null, null
    )?.use { it.count } ?: 0

    private fun loadUpcoming(limit: Int): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val end = now + 7L * 24L * 60L * 60L * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION
        )
        val result = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            val title = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val begin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val finish = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDay = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val location = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            while (c.moveToNext() && result.size < limit) {
                val name = c.getString(title)?.trim().orEmpty()
                if (name.isNotEmpty()) result += CalendarEvent(name, c.getLong(begin), c.getLong(finish), c.getInt(allDay) == 1, c.getString(location))
            }
        }
        return result
    }
}
