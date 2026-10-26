package com.afmco.emp

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

data class Session(val token: String, val name: String, val projectCode: String?, val iqama: String)

data class Category(
    val code: String, val nameAr: String,
    val needsPlate: Boolean, val needsPhoto: Boolean,
    val needsAmount: Boolean, val maxAmount: Double?
) {
    companion object {
        fun from(o: JSONObject) = Category(
            o.optString("code"), o.optString("name_ar"),
            o.optBoolean("needs_plate", false), o.optBoolean("needs_photo", false),
            o.optBoolean("needs_amount", false),
            if (o.isNull("max_amount")) null else o.optDouble("max_amount")
        )
    }
}

data class Department(val code: String, val nameAr: String, val icon: String?, val categories: List<Category>) {
    companion object {
        fun from(o: JSONObject): Department {
            val cats = o.optJSONArray("categories") ?: JSONArray()
            return Department(
                o.optString("code"), o.optString("name_ar"), o.optStringOrNull("icon"),
                (0 until cats.length()).map { Category.from(cats.getJSONObject(it)) }
            )
        }
    }
}

data class Complaint(
    val id: String, val code: String, val title: String, val status: String,
    val priority: String, val category: String, val departmentCode: String?,
    val projectCode: String?, val currentDept: String?, val description: String?,
    val location: String?, val plate: String?, val amount: Double?, val photos: List<String>,
    val slaDueAt: String?, val createdAt: String?, val resolvedAt: String?
) {
    /** المشرف طلب الإغلاق وينتظر تأكيد الموظف */
    val closePending: Boolean get() = resolvedAt != null && status != "done"
    companion object {
        fun from(o: JSONObject): Complaint {
            val ph = o.optJSONArray("photos") ?: JSONArray()
            return Complaint(
                id = o.optString("id"), code = o.optString("code"), title = o.optString("title"),
                status = o.optString("status", "open"), priority = o.optString("priority", "med"),
                category = o.optString("category"), departmentCode = o.optStringOrNull("department_code"),
                projectCode = o.optStringOrNull("project_code"),
                currentDept = o.optStringOrNull("current_dept"), description = o.optStringOrNull("description"),
                location = o.optStringOrNull("location"), plate = o.optStringOrNull("plate"),
                amount = if (o.isNull("amount")) null else o.optDouble("amount"),
                photos = (0 until ph.length()).map { ph.optString(it) },
                slaDueAt = o.optStringOrNull("sla_due_at"), createdAt = o.optStringOrNull("created_at"),
                resolvedAt = o.optStringOrNull("resolved_at")
            )
        }
    }
}

data class MyVehicle(val plate: String, val project: String?, val make: String?, val model: String?) {
    companion object {
        fun from(o: JSONObject) = MyVehicle(
            o.optString("plate"), o.optStringOrNull("project"),
            o.optStringOrNull("make"), o.optStringOrNull("model"))
    }
    val label: String get() = plate + listOfNotNull(make, model).joinToString(" ").let { if (it.isNotBlank()) " · $it" else "" }
}

data class Advance(val id: String, val amount: Double, val reason: String?, val status: String, val note: String?, val createdAt: String?)

data class Housing(val building: String?, val room: String?, val city: String?, val label: String) {
    companion object {
        fun from(o: JSONObject) = Housing(
            o.optStringOrNull("building"), o.optStringOrNull("room"),
            o.optStringOrNull("city"), o.optString("label"))
    }
}

data class Msg(val authorKind: String, val authorName: String?, val body: String, val createdAt: String?) {
    companion object { fun from(o: JSONObject) = Msg(o.optString("author_kind"), o.optStringOrNull("author_name"), o.optString("body"), o.optStringOrNull("created_at")) }
}
data class Event(val kind: String, val actorName: String?, val note: String?, val createdAt: String?) {
    companion object { fun from(o: JSONObject) = Event(o.optString("kind"), o.optStringOrNull("actor_name"), o.optStringOrNull("note"), o.optStringOrNull("created_at")) }
}

object Api {
    private const val SB_URL = "https://coueubvyaokyycqpmirs.supabase.co"
    private const val SB_KEY = "sb_publishable_OmxwJmaYvjGhC5S-VcjwHQ_atz1gleF"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS).build()

    private suspend fun rpc(fn: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$SB_URL/rest/v1/rpc/$fn")
            .addHeader("apikey", SB_KEY).addHeader("Content-Type", "application/json")
            .post(args.toString().toRequestBody(JSON)).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(body).optString("message", body) } catch (e: Exception) { body }
                throw RuntimeException(if (msg.isBlank()) "خطأ (${resp.code})" else msg)
            }
            body
        }
    }

    suspend fun login(iqama: String, pin: String): Session {
        val o = JSONObject(rpc("emp_login", JSONObject().put("p_iqama", iqama).put("p_pin", pin)))
        return Session(o.getString("token"), o.optString("name", iqama), o.optStringOrNull("project_code"), o.optString("iqama", iqama))
    }

    suspend fun departments(token: String): List<Department> {
        val arr = JSONArray(rpc("emp_departments", JSONObject().put("p_token", token)))
        return (0 until arr.length()).map { Department.from(arr.getJSONObject(it)) }
    }

    suspend fun myComplaints(token: String): List<Complaint> {
        val arr = JSONArray(rpc("emp_my_complaints", JSONObject().put("p_token", token)))
        return (0 until arr.length()).map { Complaint.from(arr.getJSONObject(it)) }
    }

    suspend fun raise(token: String, category: String, priority: String, title: String,
                      description: String, location: String, plate: String,
                      amount: Double?, photos: List<String>): Complaint {
        val a = JSONObject().put("p_token", token).put("p_category", category).put("p_priority", priority)
            .put("p_title", title).put("p_description", description).put("p_location", location)
            .put("p_plate", plate).put("p_amount", amount ?: JSONObject.NULL)
            .put("p_photos", JSONArray(photos))
        val res = rpc("emp_raise", a).trim()
        val o = if (res.startsWith("[")) JSONArray(res).getJSONObject(0) else JSONObject(res)
        return Complaint.from(o)
    }

    suspend fun messages(token: String, id: String): List<Msg> {
        val arr = JSONArray(rpc("emp_messages", JSONObject().put("p_token", token).put("p_complaint", id)))
        return (0 until arr.length()).map { Msg.from(arr.getJSONObject(it)) }
    }
    suspend fun events(token: String, id: String): List<Event> {
        val arr = JSONArray(rpc("emp_events", JSONObject().put("p_token", token).put("p_complaint", id)))
        return (0 until arr.length()).map { Event.from(arr.getJSONObject(it)) }
    }
    suspend fun send(token: String, id: String, body: String) {
        rpc("emp_send", JSONObject().put("p_token", token).put("p_complaint", id).put("p_body", body))
    }

    /** تأكيد أو رفض إغلاق الشكوى (الخطوة النهائية للموظف) */
    suspend fun confirmClose(token: String, id: String, approve: Boolean, note: String) {
        rpc("emp_confirm_close", JSONObject().put("p_token", token).put("p_complaint", id)
            .put("p_approve", approve).put("p_note", note))
    }

    /** مركبات الموظف النشطة للاختيار بدل الإدخال اليدوي */
    suspend fun myVehicles(token: String): List<MyVehicle> {
        val arr = JSONArray(rpc("emp_my_vehicles", JSONObject().put("p_token", token)))
        return (0 until arr.length()).map { MyVehicle.from(arr.getJSONObject(it)) }
    }

    /** سكن الموظف (المبنى/الغرفة) من وثيق */
    suspend fun myHousing(token: String): List<Housing> {
        val arr = JSONArray(rpc("emp_housing", JSONObject().put("p_token", token)))
        return (0 until arr.length()).map { Housing.from(arr.getJSONObject(it)) }
    }

    /** طلب تحديث بيانات الموظف (يعتمده المشرف) — الحقل: phone | project_code | bank_account */
    suspend fun requestUpdate(token: String, field: String, newValue: String) {
        rpc("emp_request_update", JSONObject().put("p_token", token).put("p_field", field).put("p_new_value", newValue))
    }

    /** طلب سلفة (حد أعلى 300 ريال) */
    suspend fun requestAdvance(token: String, amount: Double, reason: String?) {
        rpc("emp_request_advance", JSONObject().put("p_token", token).put("p_amount", amount).put("p_reason", reason ?: JSONObject.NULL))
    }

    /** سلف الموظف وحالتها */
    suspend fun myAdvances(token: String): List<Advance> {
        val arr = JSONArray(rpc("emp_my_advances", JSONObject().put("p_token", token)))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Advance(o.optString("id"), o.optDouble("amount", 0.0), o.optStringOrNull("reason"),
                o.optString("status"), o.optStringOrNull("note"), o.optStringOrNull("created_at"))
        }
    }

    /** رفع صورة إلى تخزين Supabase (bucket عام) وإرجاع الرابط العام */
    suspend fun uploadPhoto(bytes: ByteArray, ext: String): String = withContext(Dispatchers.IO) {
        val name = "c_" + System.currentTimeMillis() + "_" + (0..9999).random() + "." + ext
        val mt = ("image/" + if (ext == "png") "png" else "jpeg").toMediaType()
        val req = Request.Builder().url("$SB_URL/storage/v1/object/complaints/$name")
            .addHeader("apikey", SB_KEY).addHeader("Content-Type", mt.toString())
            .post(bytes.toRequestBody(mt)).build()
        client.newCall(req).execute().use { r ->
            val t = r.body?.string() ?: ""
            if (!r.isSuccessful) throw RuntimeException("تعذّر رفع الصورة (${r.code})")
        }
        "$SB_URL/storage/v1/object/public/complaints/$name"
    }
}
