package com.deskdock.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

class LocationRepository(private val context: Context) {
    data class Coordinates(val latitude: Double, val longitude: Double)

    fun getCurrent(callback: (Coordinates?) -> Unit) {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return callback(null)

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull(Location::getTime)
        if (last != null) return callback(Coordinates(last.latitude, last.longitude))

        val provider = providers.firstOrNull() ?: return callback(null)
        var delivered = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (delivered) return
                delivered = true
                runCatching { manager.removeUpdates(this) }
                callback(Coordinates(location.latitude, location.longitude))
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in Android")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }.onFailure { callback(null) }
    }
}
