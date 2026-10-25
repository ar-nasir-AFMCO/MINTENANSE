package com.afmco.emp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(val versionCode: Int, val versionName: String, val url: String, val notes: String)

/** التحديث الذاتي للتطبيق: يفحص أحدث نسخة منشورة ويُنزّلها ويفتح شاشة التثبيت. */
object Updater {
    private const val VERSION_URL =
        "https://github.com/ar-nasir-AFMCO/MINTENANSE/releases/download/emp-latest/emp-version.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun installedCode(context: Context): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    /** يرجّع معلومات التحديث إن وُجدت نسخة أحدث، وإلا null */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(VERSION_URL).build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string()
                if (!r.isSuccessful || body.isNullOrBlank()) return@withContext null
                val o = JSONObject(body)
                val info = UpdateInfo(
                    o.optInt("versionCode", 0), o.optString("versionName", ""),
                    o.optString("url", ""), o.optString("notes", ""))
                if (info.versionCode > installedCode(context) && info.url.isNotBlank()) info else null
            }
        } catch (e: Exception) { null }
    }

    /** ينزّل الـAPK ثم يفتح شاشة التثبيت (المستخدم يضغط "تثبيت") */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val apk = withContext(Dispatchers.IO) {
            val dir = context.externalCacheDir ?: context.cacheDir
            val f = File(dir, "update-${info.versionCode}.apk")
            // تفادي إعادة تنزيل نفس النسخة لو سبق تنزيلها كاملة
            if (!f.exists() || f.length() < 1_000_000L) {
                val req = Request.Builder().url(info.url).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw RuntimeException("تعذّر تنزيل التحديث (${r.code})")
                    f.outputStream().use { out -> r.body?.byteStream()?.copyTo(out) }
                }
            }
            f
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
