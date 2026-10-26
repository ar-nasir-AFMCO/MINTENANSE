package com.afmco.sup

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object Loc {
    fun hasPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** محاولة الحصول على موقع حالي (fix) مع مهلة، والرجوع لآخر موقع معروف عند التعذّر */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 12000): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()

        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) { try { lm.removeUpdates(this) } catch (_: Exception) {}; if (cont.isActive) cont.resume(l) }
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                    @Deprecated("Deprecated in API 29") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                }
                try { lm.requestLocationUpdates(provider, 0L, 0f, listener, context.mainLooper) }
                catch (e: Exception) { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { try { lm.removeUpdates(listener) } catch (_: Exception) {} }
            }
        }
        return fresh ?: runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }
}
