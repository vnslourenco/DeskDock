package com.deskdock.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class LocationRepository(private val context: Context) {
    data class Coordinates(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val timestampMs: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getCurrent(callback: (Coordinates?) -> Unit) {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return callback(null)

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
        }
        if (providers.isEmpty()) return callback(null)

        val now = System.currentTimeMillis()
        val maxAgeMs = 15 * 60_000L
        val maxAccuracyMeters = 5_000f

        val recent = providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { location ->
                val freshEnough = now - location.time <= maxAgeMs
                val accurateEnough = !location.hasAccuracy() || location.accuracy <= maxAccuracyMeters
                freshEnough && accurateEnough
            }
            .maxByOrNull(Location::getTime)

        if (recent != null) {
            return callback(recent.toCoordinates())
        }

        val provider = providers.first()
        var delivered = false

        lateinit var listener: LocationListener
        val timeout = Runnable {
            if (delivered) return@Runnable
            delivered = true
            runCatching { manager.removeUpdates(listener) }
            callback(null)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (delivered) return
                delivered = true
                mainHandler.removeCallbacks(timeout)
                runCatching { manager.removeUpdates(this) }
                callback(location.toCoordinates())
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        mainHandler.postDelayed(timeout, 10_000L)
        runCatching {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure {
            mainHandler.removeCallbacks(timeout)
            if (!delivered) {
                delivered = true
                callback(null)
            }
        }
    }

    private fun Location.toCoordinates() = Coordinates(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else -1f,
        timestampMs = time
    )
}
