package com.deskdock.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.deskdock.app.model.CalendarEvent
import java.util.Calendar

class CalendarRepository(private val context: Context) {
    data class CalendarState(
        val visibleCalendars: Int,
        val events: List<CalendarEvent>,
        val calendarNames: List<String>
    )

    fun loadState(limit: Int = 5): CalendarState {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return CalendarState(0, emptyList(), emptyList())
        }
        val names = loadVisibleCalendarNames()
        return CalendarState(names.size, loadRemainingToday(limit), names)
    }

    private fun loadVisibleCalendarNames(): List<String> {
        val names = mutableListOf<String>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE
        )
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE}=1",
            null,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " ASC"
        )?.use { c ->
            val display = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val account = c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
            while (c.moveToNext()) {
                val d = if (display >= 0) c.getString(display)?.trim().orEmpty() else ""
                val a = if (account >= 0) c.getString(account)?.trim().orEmpty() else ""
                val label = when {
                    d.isNotEmpty() -> d
                    a.isNotEmpty() -> a
                    else -> "Calendário"
                }
                if (label !in names) names += label
            }
        }
        return names
    }

    private fun loadRemainingToday(limit: Int): List<CalendarEvent> {
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
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

        val result = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri,
            projection,
            "${CalendarContract.Instances.VISIBLE}=1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            val title = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val begin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val finish = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDay = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val location = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            while (c.moveToNext() && result.size < limit) {
                val name = c.getString(title)?.trim().orEmpty()
                val eventEnd = c.getLong(finish)
                val isAllDay = c.getInt(allDay) == 1

                // Keep all-day events and meetings that are upcoming or currently in progress.
                // Once a timed event has ended, it disappears from the dock agenda.
                val stillRelevant = isAllDay || eventEnd > now
                if (name.isNotEmpty() && stillRelevant) {
                    result += CalendarEvent(
                        title = name,
                        startMillis = c.getLong(begin),
                        endMillis = eventEnd,
                        allDay = isAllDay,
                        location = c.getString(location)
                    )
                }
            }
        }
        return result
    }
}
