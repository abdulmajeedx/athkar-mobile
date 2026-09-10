package com.athkar.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.athkar.core.prayer.Coordinates
import com.athkar.domain.Cities
import com.athkar.domain.DeviceLocationSource
import com.athkar.domain.Place
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * One-shot device location built on the platform [LocationManager] rather than Play Services: the
 * app has no other Google dependency, and prayer times need a fix accurate to kilometres, not
 * metres — a coarse network fix moves a prayer time by seconds.
 *
 * The place is named from the bundled city list instead of a reverse geocoder, because a geocoder
 * needs the network that this app is built to work without.
 */
@Singleton
class AndroidLocationSource @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceLocationSource {

    private val locationManager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override suspend fun currentPlace(): Place? {
        if (!hasPermission()) return null
        val manager = locationManager ?: return null

        val cached = bestLastKnown(manager)
        if (cached != null && cached.isFresh()) return cached.toPlace()

        val live = requestSingleFix(manager)
        // A stale cached fix still beats nothing: a device that has not moved continents since
        // yesterday gets correct times, and the alternative is an empty screen.
        return (live ?: cached)?.toPlace()
    }

    private fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The permission is checked by the only caller before this runs, and the catch is what covers
     * the gap between that check and this call — the user can revoke it in settings while the fix
     * is in flight. `runCatching` covered it too, but lint cannot see through it: it recognises a
     * literal `catch (SecurityException)` and nothing else, and this project treats lint warnings
     * as build failures.
     */
    private fun bestLastKnown(manager: LocationManager): Location? =
        PROVIDERS.mapNotNull { provider ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }

    /**
     * Listens for the first fix from any enabled provider, giving up after [FIX_TIMEOUT_MILLIS].
     * Registration happens on the main looper because [LocationManager] delivers callbacks there
     * unless given its own thread.
     */
    private suspend fun requestSingleFix(manager: LocationManager): Location? {
        val providers = PROVIDERS.filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) return null

        return try {
            withTimeout(FIX_TIMEOUT_MILLIS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                manager.removeUpdates(this)
                                if (continuation.isActive) continuation.resume(location)
                            }

                            // Required on API 26-28; the default implementations were only added later.
                            override fun onProviderEnabled(provider: String) = Unit
                            override fun onProviderDisabled(provider: String) = Unit

                            @Deprecated("Required by the API level this app supports")
                            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                        }
                        continuation.invokeOnCancellation {
                            runCatching { manager.removeUpdates(listener) }
                        }
                        // Literal catch rather than runCatching, for the same reason as
                        // bestLastKnown: this is the form lint can see.
                        val registered = providers.any { provider ->
                            try {
                                manager.requestLocationUpdates(
                                    provider,
                                    0L,
                                    0f,
                                    listener,
                                    Looper.getMainLooper(),
                                )
                                true
                            } catch (e: SecurityException) {
                                false
                            } catch (e: IllegalArgumentException) {
                                // A provider can disappear between the isProviderEnabled check
                                // above and this call.
                                false
                            }
                        }
                        if (!registered && continuation.isActive) continuation.resume(null)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } catch (e: SecurityException) {
            // The permission can be revoked between the check above and the request.
            null
        }
    }

    private fun Location.isFresh(): Boolean =
        System.currentTimeMillis() - time < CACHE_MAX_AGE_MILLIS

    private fun Location.toPlace(): Place {
        val coordinates = Coordinates(latitude, longitude)
        return Place(
            coordinates = coordinates,
            name = nameFor(coordinates),
            isAutomatic = true,
        )
    }

    /** Names the fix after the nearest bundled city, or falls back to the coordinates themselves. */
    private fun nameFor(coordinates: Coordinates): String {
        val nearest = Cities.ALL.minByOrNull { it.coordinates.distanceKmTo(coordinates) }
        val distance = nearest?.coordinates?.distanceKmTo(coordinates)
        return if (nearest != null && distance != null && distance <= CITY_MATCH_RADIUS_KM) {
            nearest.name
        } else {
            "%.3f، %.3f".format(coordinates.latitude, coordinates.longitude)
        }
    }

    private fun Coordinates.distanceKmTo(other: Coordinates): Double {
        val results = FloatArray(1)
        Location.distanceBetween(latitude, longitude, other.latitude, other.longitude, results)
        return results[0] / 1000.0
    }

    private companion object {
        // GPS is omitted deliberately: with coarse permission the OS blurs its fix to roughly the
        // same precision anyway, while a satellite lock can take tens of seconds outdoors and never
        // arrives indoors. The network and passive providers answer in milliseconds.
        val PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        const val FIX_TIMEOUT_MILLIS = 12_000L
        const val CACHE_MAX_AGE_MILLIS = 30 * 60 * 1000L
        const val CITY_MATCH_RADIUS_KM = 40.0
    }
}
