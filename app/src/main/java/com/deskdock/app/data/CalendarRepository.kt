package com.deskdock.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.deskdock.app.model.CalendarEvent

class CalendarRepository(private val context: Context) {
    fun loadUpcoming(limit: Int = 3): List<CalendarEvent> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val now = System.currentTimeMillis()
        val end = now + 36L * 60L * 60L * 1000L
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
        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            val title = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val begin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val finish = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDay = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val location = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            while (c.moveToNext() && events.size < limit) {
                val name = c.getString(title)?.trim().orEmpty()
                if (name.isNotBlank()) events += CalendarEvent(name, c.getLong(begin), c.getLong(finish), c.getInt(allDay) == 1, c.getString(location))
            }
        }
        return events
    }
}
