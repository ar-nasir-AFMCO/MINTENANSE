package com.afmco.emp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * صوت — الرسائل الموجّهة العاجلة:
 *  • فحص في الخلفية كل 15 دقيقة (حتى والتطبيق مقفل) → إشعار بصوت المنبّه
 *  • داخل التطبيق: شاشة كاملة بصوت عالي لا تُقفل إلا بتأكيد القراءة
 */
object Urgent {
    private const val CH = "soot_urgent_v1"
    private const val WORK = "soot_urgent_check"
    private const val SP = "soot_urgent"

    private fun alarmUri() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CH) == null) {
                val ch = NotificationChannel(CH, "رسائل عاجلة من الإدارة", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "رسائل موجّهة تحتاج تأكيد قراءة"
                ch.enableVibration(true)
                ch.vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
                ch.setSound(alarmUri(), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                ch.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                nm.createNotificationChannel(ch)
            }
        }
    }

    fun notify(ctx: Context, b: Broadcast) {
        ensureChannel(ctx)
        if (!Notif.allowed(ctx)) return
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val pi = if (launch != null) PendingIntent.getActivity(ctx, b.id.toInt(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null
        val title = "🔔 رسالة عاجلة" + (b.title?.let { ": $it" } ?: "")
        val nb = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title).setContentText(b.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(b.body + "\n\nافتح التطبيق وأكّد قراءة الرسالة"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alarmUri(), AudioManager.STREAM_ALARM)
            .setVibrate(longArrayOf(0, 800, 400, 800, 400, 800))
            .setOngoing(true).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (pi != null) { nb.setContentIntent(pi); nb.setFullScreenIntent(pi, true) }
        try { NotificationManagerCompat.from(ctx).notify(900000 + (b.id % 100000).toInt(), nb.build()) } catch (_: Exception) {}
    }

    /** إزالة إشعار الرسالة بعد تأكيد قراءتها */
    fun clear(ctx: Context, id: Long) {
        try { NotificationManagerCompat.from(ctx).cancel(900000 + (id % 100000).toInt()) } catch (_: Exception) {}
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UrgentWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** الرسائل اللي سبق تنبيه المندوب عنها في الخلفية (حتى لا يتكرر الإشعار كل 15 دقيقة) */
    fun wasNotified(ctx: Context, id: Long) =
        ctx.getSharedPreferences(SP, Context.MODE_PRIVATE).getStringSet("seen", emptySet())!!.contains(id.toString())

    fun markNotified(ctx: Context, id: Long) {
        val sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val s = HashSet(sp.getStringSet("seen", emptySet())!!); s.add(id.toString())
        sp.edit().putStringSet("seen", s).apply()
    }
}

/** فحص الخلفية: يجيب الرسائل غير المؤكَّدة ويطلق إشعار المنبّه */
class UrgentWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        var token = Prefs.token(ctx)
        val list = try {
            if (token.isNullOrBlank()) throw RuntimeException("no token")
            Api.unreadBroadcasts(token)
        } catch (e: Exception) {
            // الجلسة انتهت → دخول صامت بالبيانات المحفوظة
            val iq = Prefs.iqama(ctx); val pn = Prefs.pin(ctx)
            if (iq.isNullOrBlank() || pn.isNullOrBlank()) return Result.success()
            try {
                val s = Api.login(iq, pn); token = s.token; Prefs.saveToken(ctx, s.token)
                Api.unreadBroadcasts(s.token)
            } catch (_: Exception) { return Result.retry() }
        }
        for (b in list) if (!Urgent.wasNotified(ctx, b.id)) { Urgent.notify(ctx, b); Urgent.markNotified(ctx, b.id) }
        return Result.success()
    }
}

/** صوت المنبّه العالي داخل التطبيق (يتكرر لين يأكد المندوب) */
object AlarmPlayer {
    private var mp: MediaPlayer? = null
    fun start(ctx: Context) {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { mp = null }
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            val pattern = longArrayOf(0, 800, 500)
            if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            else v?.vibrate(pattern, 0)
        } catch (_: Exception) {}
    }
    fun stop(ctx: Context? = null) {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        if (ctx != null) try { (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.cancel() } catch (_: Exception) {}
    }
}
