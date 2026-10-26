package com.afmco.sup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** تنبيهات داخل التطبيق (إشعارات محلية عند تحديثات الشكاوى/الطلبات). */
object Notif {
    private const val CH = "soot_alerts"
    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.getNotificationChannel(CH) == null) {
                val ch = NotificationChannel(CH, "تنبيهات صوت", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "تحديثات الشكاوى والطلبات"
                nm.createNotificationChannel(ch)
            }
        }
    }
    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    fun show(ctx: Context, title: String, body: String, id: Int) {
        ensure(ctx)
        if (!allowed(ctx)) return
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pi = if (launch != null)
            PendingIntent.getActivity(ctx, id, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null
        val b = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
        if (pi != null) b.setContentIntent(pi)
        try { NotificationManagerCompat.from(ctx).notify(id, b.build()) } catch (_: Exception) {}
    }
}
