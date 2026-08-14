package com.deskdock.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.deskdock.app.model.CalendarEvent

class CalendarRepository(private val context: Context) {

    data class CalendarState(
        val visibleCalendars: Int,
        val events: List<CalendarEvent>
    )

    fun loadState(limit: Int = 5): CalendarState {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return CalendarState(0, emptyList())
        }

        val visibleCalendars = countVisibleCalendars()
        val events = loadUpcoming(limit)
        return CalendarState(visibleCalendars, events)
    }

    private fun countVisibleCalendars(): Int {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.VISIBLE}=1"
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )?.use { it.count } ?: 0
    }

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
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.VISIBLE
        )

        val events = mutableListOf<CalendarEvent>()
        val selection = "${CalendarContract.Instances.VISIBLE}=1"

        context.contentResolver.query(
            uri,
            projection,
            selection,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            val title = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val begin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val finish = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDay = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val location = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)

            while (c.moveToNext() && events.size < limit) {
                val name = c.getString(title)?.trim().orEmpty()
                if (name.isNotBlank()) {
                    events += CalendarEvent(
                        title = name,
                        startMillis = c.getLong(begin),
                        endMillis = c.getLong(finish),
                        allDay = c.getInt(allDay) == 1,
                        location = c.getString(location)
                    )
                }
            }
        }
        return events
    }
}
