package com.afmco.sup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** خدمة أمامية ترسل نبضة الموقع كل ٥ دقائق أثناء الدوام (مع إشعار دائم). */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var token: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        scope.launch { loop() }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "تتبّع الدوام", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("دوام صوت نشط")
            .setContentText("يتم تسجيل موقعك أثناء الدوام فقط")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                if (token == null) {
                    val em = Prefs.email(this); val ps = Prefs.pass(this)
                    if (!em.isNullOrBlank() && !ps.isNullOrBlank()) token = Api.login(em, ps).token
                }
                val loc = Loc.current(this)
                val t = token
                if (loc != null && t != null) Api.ping(t, loc.latitude, loc.longitude, loc.accuracy.toDouble())
            } catch (_: Exception) {}
            delay(60 * 60 * 1000L)   // تحقق صامت كل ساعة
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "tracking"
        private const val NOTIF_ID = 42
        fun start(ctx: Context) {
            val i = Intent(ctx, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { try { ctx.stopService(Intent(ctx, TrackingService::class.java)) } catch (_: Exception) {} }
    }
}
