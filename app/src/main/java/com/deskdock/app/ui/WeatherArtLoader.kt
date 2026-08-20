package com.deskdock.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

object WeatherArtLoader {
    private val cache = mutableMapOf<Int, Bitmap?>()

    fun get(context: Context, resId: Int): Bitmap? = synchronized(cache) {
        cache.getOrPut(resId) { BitmapFactory.decodeResource(context.resources, resId) }
    }
}
