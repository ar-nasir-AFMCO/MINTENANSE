package com.afmco.sup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun JSONObject.optStringOrNull(k: String): String? =
    if (!has(k) || isNull(k)) null else optString(k)

data class SupSession(val token: String, val name: String, val role: String)

data class Complaint(
    val id: String,
    val code: String,
    val title: String,
    val status: String,
    val priority: String,
    val category: String,
    val categoryLabel: String?,
    val departmentCode: String?,
    val departmentName: String?,
    val projectCode: String?,
    val projectName: String?,
    val currentDept: String?,
    val description: String?,
    val location: String?,
    val plate: String?,
    val amount: Double?,
    val photos: List<String>,
    val toMaintenance: Boolean,
    val toWatheeq: Boolean,
    val empName: String?,
    val empIqama: String?,
    val resolvedAt: String?,
    val overdue: Boolean,
    val createdAt: String?
) {
    /** طلب المشرف الإغلاق وينتظر تأكيد الموظف */
    val closePending: Boolean get() = resolvedAt != null && status != "done"
    companion object {
        fun from(o: JSONObject): Complaint {
            val ph = o.optJSONArray("photos") ?: JSONArray()
            return Complaint(
                id = o.optString("id"),
                code = o.optString("code"),
                title = o.optString("title"),
                status = o.optString("status", "open"),
                priority = o.optString("priority", "med"),
                category = o.optString("category", "other"),
                categoryLabel = o.optStringOrNull("category_label"),
                departmentCode = o.optStringOrNull("department_code"),
                departmentName = o.optStringOrNull("department_name"),
                projectCode = o.optStringOrNull("project_code"),
                projectName = o.optStringOrNull("project_name"),
                currentDept = o.optStringOrNull("current_dept"),
                description = o.optStringOrNull("description"),
                location = o.optStringOrNull("location"),
                plate = o.optStringOrNull("plate"),
                amount = if (o.isNull("amount")) null else o.optDouble("amount"),
                photos = (0 until ph.length()).map { ph.optString(it) },
                toMaintenance = o.optBoolean("to_maintenance", false),
                toWatheeq = o.optBoolean("to_watheeq", false),
                empName = o.optStringOrNull("emp_name"),
                empIqama = o.optStringOrNull("emp_iqama"),
                resolvedAt = o.optStringOrNull("resolved_at"),
                overdue = o.optBoolean("overdue", false),
                createdAt = o.optStringOrNull("created_at")
            )
        }
    }
}

data class Msg(val authorKind: String, val authorName: String?, val body: String, val createdAt: String?) {
    companion object {
        fun from(o: JSONObject) = Msg(
            o.optString("author_kind"), o.optStringOrNull("author_name"),
            o.optString("body"), o.optStringOrNull("created_at")
        )
    }
}

data class EmpInfo(val phone: String?, val area: String?, val vehicles: List<String>, val housing: String?)
data class Person(val id: String, val name: String, val role: String?)
data class ExtraParty(val kind: String, val deptCode: String?, val label: String?)
data class DataUpdate(val id: String, val iqama: String?, val fieldLabel: String?, val oldValue: String?, val newValue: String, val createdAt: String?) {
    companion object {
        fun from(o: JSONObject) = DataUpdate(
            o.optString("id"), o.optStringOrNull("iqama"), o.optStringOrNull("field_label"),
            o.optStringOrNull("old_value"), o.optString("new_value"), o.optStringOrNull("created_at"))
    }
}
data class Event(val kind: String, val actorName: String?, val note: String?, val createdAt: String?) {
    companion object {
        fun from(o: JSONObject) = Event(
            o.optString("kind"), o.optStringOrNull("actor_name"),
            o.optStringOrNull("note"), o.optStringOrNull("created_at")
        )
    }
}

object Api {
    private const val SB_URL = "https://coueubvyaokyycqpmirs.supabase.co"
    private const val SB_KEY = "sb_publishable_OmxwJmaYvjGhC5S-VcjwHQ_atz1gleF"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun errorOf(code: Int, body: String): String {
        return try {
            val o = JSONObject(body)
            o.optString("message", o.optString("error_description", o.optString("msg", body)))
        } catch (e: Exception) { if (body.isBlank()) "خطأ ($code)" else body }
    }

    // ── دخول بحساب المنصة (GoTrue) ──
    suspend fun login(email: String, password: String): SupSession = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("password", password)
        val req = Request.Builder()
            .url("$SB_URL/auth/v1/token?grant_type=password")
            .addHeader("apikey", SB_KEY).addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON)).build()
        val (token, uid) = client.newCall(req).execute().use { r ->
            val t = r.body?.string() ?: ""
            if (!r.isSuccessful) throw RuntimeException(errorOf(r.code, t))
            val o = JSONObject(t)
            o.getString("access_token") to (o.optJSONObject("user")?.optString("id") ?: "")
        }
        // جلب الاسم والدور من profiles
        var name = email; var role = "supervisor"
        try {
            val pr = Request.Builder()
                .url("$SB_URL/rest/v1/profiles?id=eq.$uid&select=full_name,role")
                .addHeader("apikey", SB_KEY).addHeader("Authorization", "Bearer $token").build()
            client.newCall(pr).execute().use { r ->
                val arr = JSONArray(r.body?.string() ?: "[]")
                if (arr.length() > 0) {
                    val p = arr.getJSONObject(0)
                    name = p.optStringOrNull("full_name") ?: email
                    role = p.optStringOrNull("role") ?: "supervisor"
                }
            }
        } catch (_: Exception) {}
        SupSession(token, name, role)
    }

    private suspend fun get(token: String, path: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$SB_URL/rest/v1/$path")
            .addHeader("apikey", SB_KEY).addHeader("Authorization", "Bearer $token").build()
        client.newCall(req).execute().use { r ->
            val t = r.body?.string() ?: ""
            if (!r.isSuccessful) throw RuntimeException(errorOf(r.code, t))
            t
        }
    }

    private suspend fun rpc(token: String, fn: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$SB_URL/rest/v1/rpc/$fn")
            .addHeader("apikey", SB_KEY).addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(args.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { r ->
            val t = r.body?.string() ?: ""
            if (!r.isSuccessful) throw RuntimeException(errorOf(r.code, t))
            t
        }
    }

    suspend fun complaints(token: String): List<Complaint> {
        val arr = JSONArray(get(token, "v_complaints?select=*&order=created_at.desc"))
        return (0 until arr.length()).map { Complaint.from(arr.getJSONObject(it)) }
    }

    suspend fun messages(token: String, id: String): List<Msg> {
        val arr = JSONArray(get(token, "complaint_messages?complaint_id=eq.$id&order=created_at.asc&select=*"))
        return (0 until arr.length()).map { Msg.from(arr.getJSONObject(it)) }
    }

    suspend fun events(token: String, id: String): List<Event> {
        val arr = JSONArray(get(token, "complaint_events?complaint_id=eq.$id&order=created_at.asc&select=*"))
        return (0 until arr.length()).map { Event.from(arr.getJSONObject(it)) }
    }

    suspend fun reply(token: String, id: String, body: String) {
        rpc(token, "sup_reply", JSONObject().put("p_complaint", id).put("p_body", body))
    }

    suspend fun escalate(token: String, id: String, note: String) {
        rpc(token, "sup_escalate", JSONObject().put("p_complaint", id).put("p_note", note))
    }

    suspend fun resolve(token: String, id: String, note: String) {
        rpc(token, "sup_resolve", JSONObject().put("p_complaint", id).put("p_note", note))
    }

    /** إرفاق صورة على بلاغ قائم (مشرف/قسم) */
    suspend fun addPhoto(token: String, id: String, url: String) {
        rpc(token, "sup_add_photo", JSONObject().put("p_complaint", id).put("p_url", url))
    }

    /** بيانات الموظف الكاملة: الجوال + المنطقة + المركبات (مع مكتب الإيجار والمنطقة) + السكن */
    suspend fun empInfo(token: String, iqama: String): EmpInfo {
        var phone: String? = null; var area: String? = null
        try {
            val arr = JSONArray(get(token, "employees?iqama=eq.$iqama&select=phone,area&limit=1"))
            if (arr.length() > 0) { val o = arr.getJSONObject(0); phone = o.optStringOrNull("phone"); area = o.optStringOrNull("area") }
        } catch (_: Exception) {}
        val veh = mutableListOf<String>()
        try {
            val arr = JSONArray(rpc(token, "vehicles_by_iqama", JSONObject().put("p_iqama", iqama)))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val parts = mutableListOf(o.optString("plate"))
                listOfNotNull(o.optStringOrNull("make"), o.optStringOrNull("model")).joinToString(" ").let { if (it.isNotBlank()) parts.add(it) }
                o.optStringOrNull("rental_office")?.let { parts.add("🏢 $it") }
                o.optStringOrNull("area")?.let { parts.add("📍 $it") }
                veh.add(parts.joinToString(" · "))
            }
        } catch (_: Exception) {}
        var housing: String? = null
        try {
            val arr = JSONArray(rpc(token, "housing_by_iqama", JSONObject().put("p_iqama", iqama)))
            if (arr.length() > 0) housing = arr.getJSONObject(0).optStringOrNull("label")
        } catch (_: Exception) {}
        return EmpInfo(phone, area, veh, housing)
    }

    /** الأطراف المُشارَكة في البلاغ */
    suspend fun extraParties(token: String, id: String): List<ExtraParty> {
        val arr = JSONArray(get(token, "complaint_extra_parties?complaint_id=eq.$id&order=created_at.asc&select=*"))
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); ExtraParty(o.optString("kind"), o.optStringOrNull("dept_code"), o.optStringOrNull("label")) }
    }

    /** إشراك قسم أو شخص (الـ@) */
    suspend fun addParty(token: String, id: String, type: String, key: String, label: String) {
        rpc(token, "soot_add_party", JSONObject().put("p_complaint", id).put("p_type", type).put("p_key", key).put("p_label", label))
    }

    /** الأشخاص المتاحون للإشراك (الإداريون) */
    suspend fun people(token: String): List<Person> {
        val arr = JSONArray(get(token, "profiles?select=id,full_name,role&order=full_name.asc"))
        return (0 until arr.length()).mapNotNull { val o = arr.getJSONObject(it); val n = o.optStringOrNull("full_name"); if (n == null) null else Person(o.optString("id"), n, o.optStringOrNull("role")) }
    }

    /** الأولوية يحددها المشرف */
    suspend fun setPriority(token: String, id: String, priority: String) {
        rpc(token, "sup_set_priority", JSONObject().put("p_complaint", id).put("p_priority", priority))
    }

    /** طلبات تحديث بيانات الموظفين المعلّقة */
    suspend fun pendingUpdates(token: String): List<DataUpdate> {
        val arr = JSONArray(get(token, "data_update_requests?status=eq.pending&order=created_at.desc&select=*"))
        return (0 until arr.length()).map { DataUpdate.from(arr.getJSONObject(it)) }
    }

    /** اعتماد/رفض طلب تحديث (يحدّث وثيق عند الاعتماد) */
    suspend fun reviewUpdate(token: String, request: String, approve: Boolean, note: String?) {
        rpc(token, "sup_review_update", JSONObject().put("p_request", request).put("p_approve", approve).put("p_note", note ?: JSONObject.NULL))
    }

    /** رفع صورة إلى تخزين Supabase وإرجاع الرابط العام */
    suspend fun uploadPhoto(bytes: ByteArray, ext: String): String = withContext(Dispatchers.IO) {
        val name = "s_" + System.currentTimeMillis() + "_" + (0..9999).random() + "." + ext
        val mt = ("image/" + if (ext == "png") "png" else "jpeg").toMediaType()
        val req = Request.Builder().url("$SB_URL/storage/v1/object/complaints/$name")
            .addHeader("apikey", SB_KEY).addHeader("Content-Type", mt.toString())
            .post(bytes.toRequestBody(mt)).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw RuntimeException("تعذّر رفع الصورة (${r.code})")
        }
        "$SB_URL/storage/v1/object/public/complaints/$name"
    }
}
