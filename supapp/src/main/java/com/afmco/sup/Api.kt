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
data class DataUpdate(val id: String, val iqama: String?, val fieldLabel: String?, val oldValue: String?, val newValue: String, val createdAt: String?,
                      val name: String? = null, val project: String? = null,
                      val recStatus: String? = null, val recByName: String? = null, val recNote: String? = null,
                      val canApprove: Boolean? = null,
                      val field: String? = null, val holder: String? = null, val stage: String? = null,
                      val canConfirm: Boolean = false, val canReject: Boolean? = null) {
    companion object {
        fun from(o: JSONObject) = DataUpdate(
            o.optString("id"), o.optStringOrNull("iqama"), o.optStringOrNull("field_label"),
            o.optStringOrNull("old_value"), o.optString("new_value"), o.optStringOrNull("created_at"),
            o.optStringOrNull("name"), o.optStringOrNull("project"),
            o.optStringOrNull("rec_status"), o.optStringOrNull("rec_by_name"), o.optStringOrNull("rec_note"),
            if (o.has("can_approve") && !o.isNull("can_approve")) o.optBoolean("can_approve") else null,
            o.optStringOrNull("field"), o.optStringOrNull("holder"), o.optStringOrNull("stage"),
            o.optBoolean("can_confirm", false),
            if (o.has("can_reject") && !o.isNull("can_reject")) o.optBoolean("can_reject") else null)
    }
}

data class Advance(val id: String, val iqama: String?, val name: String?, val project: String?, val amount: Double, val reason: String?, val createdAt: String?,
                   val recStatus: String? = null, val recByName: String? = null, val recNote: String? = null) {
    companion object {
        fun from(o: JSONObject) = Advance(
            o.optString("id"), o.optStringOrNull("iqama"), o.optStringOrNull("name"),
            o.optStringOrNull("project"), o.optDouble("amount", 0.0), o.optStringOrNull("reason"), o.optStringOrNull("created_at"),
            o.optStringOrNull("rec_status"), o.optStringOrNull("rec_by_name"), o.optStringOrNull("rec_note"))
    }
}

/** صلاحيات المستخدم: من يعتمد ومن يوصي */
data class Caps(val role: String?, val approveUpdates: Boolean, val approveAdvances: Boolean, val depts: List<String>)
data class Event(val kind: String, val actorName: String?, val note: String?, val createdAt: String?) {
    companion object {
        fun from(o: JSONObject) = Event(
            o.optString("kind"), o.optStringOrNull("actor_name"),
            o.optStringOrNull("note"), o.optStringOrNull("created_at")
        )
    }
}

/** صف في روستر «المناديب على رأس العمل» (صوت): الحضور × المركبة المسلّمة */
data class RosterItem(
    val captainId: String?, val iqama: String, val name: String,
    val present: Boolean, val hours: Double?, val orders: Int,
    val short: Boolean, val absent: Boolean, val logged: Boolean, val warns: Int,
    val project: String = "", val plates: String? = null, val workDate: String? = null,
    val result: String = "", val level: String = ""   // present | absent_vehicle | absent | nodata
)

data class RosterResult(val rows: List<RosterItem>, val projects: List<String>, val dates: Map<String, String>)

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
        val arr = JSONArray(rpc(token, "soot_pending_updates", JSONObject()).ifBlank { "[]" })
        return (0 until arr.length()).map { DataUpdate.from(arr.getJSONObject(it)) }
    }

    /** السلف المعلّقة */
    suspend fun pendingAdvances(token: String): List<Advance> {
        val arr = JSONArray(rpc(token, "soot_pending_advances", JSONObject()).ifBlank { "[]" })
        return (0 until arr.length()).map { Advance.from(arr.getJSONObject(it)) }
    }

    /** اعتماد/رفض سلفة */
    suspend fun reviewAdvance(token: String, request: String, approve: Boolean, note: String?) {
        rpc(token, "sup_review_advance", JSONObject().put("p_request", request).put("p_approve", approve).put("p_note", note ?: JSONObject.NULL))
    }

    /** صلاحيات المستخدم الحالي (التوظيف/المالية/الإدارة تعتمد — المشرف يوصي) */
    suspend fun caps(token: String): Caps {
        val o = JSONObject(rpc(token, "soot_my_caps", JSONObject()))
        val d = o.optJSONArray("depts")
        return Caps(o.optStringOrNull("role"), o.optBoolean("approve_updates", false), o.optBoolean("approve_advances", false),
            if (d == null) emptyList() else (0 until d.length()).map { d.optString(it) })
    }

    /** توصية المشرف على سلفة */
    suspend fun recommendAdvance(token: String, request: String, ok: Boolean, note: String?) {
        rpc(token, "sup_recommend_advance", JSONObject().put("p_request", request).put("p_ok", ok).put("p_note", note ?: JSONObject.NULL))
    }

    /** توصية المشرف على طلب تحديث بيانات */
    suspend fun recommendUpdate(token: String, request: String, ok: Boolean, note: String?) {
        rpc(token, "sup_recommend_update", JSONObject().put("p_request", request).put("p_ok", ok).put("p_note", note ?: JSONObject.NULL))
    }

    /** اعتماد/رفض طلب تحديث (يحدّث وثيق عند الاعتماد) */
    suspend fun reviewUpdate(token: String, request: String, approve: Boolean, note: String?) {
        rpc(token, "sup_review_update", JSONObject().put("p_request", request).put("p_approve", approve).put("p_note", note ?: JSONObject.NULL))
    }

    /** حالة حضور المشرف الحالية */
    suspend fun myStatus(token: String): JSONObject = JSONObject(rpc(token, "sup_my_status", JSONObject()))

    /** تسجيل حضور (مع الموقع) */
    suspend fun checkIn(token: String, lat: Double, lng: Double): JSONObject =
        JSONObject(rpc(token, "sup_checkin", JSONObject().put("p_lat", lat).put("p_lng", lng)))

    /** تسجيل انصراف */
    suspend fun checkOut(token: String, lat: Double, lng: Double): JSONObject =
        JSONObject(rpc(token, "sup_checkout", JSONObject().put("p_lat", lat).put("p_lng", lng)))

    /** نبضة موقع دورية */
    suspend fun ping(token: String, lat: Double, lng: Double, acc: Double?) {
        rpc(token, "sup_ping", JSONObject().put("p_lat", lat).put("p_lng", lng).put("p_acc", acc ?: JSONObject.NULL))
    }

    /** ترجمة نص حر إلى اللغة الهدف مع كشف تلقائي للمصدر (Google gtx المجاني) */
    suspend fun translate(text: String, target: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext text
        try {
            val q = java.net.URLEncoder.encode(text, "UTF-8")
            val req = Request.Builder()
                .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t&q=$q")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(req).execute().use { r ->
                val t = r.body?.string() ?: return@withContext text
                // الصيغة: [[["ترجمة","أصل",...],["جزء2",...]], ...]
                val segs = JSONArray(t).optJSONArray(0) ?: return@withContext text
                val sb = StringBuilder()
                for (i in 0 until segs.length()) {
                    segs.optJSONArray(i)?.optString(0)?.let { sb.append(it) }
                }
                val out = sb.toString()
                if (out.isBlank()) text else out
            }
        } catch (e: Exception) {
            // احتياطي: MyMemory مع افتراض الإنجليزية كمصدر
            try {
                val q = java.net.URLEncoder.encode(text, "UTF-8")
                val req = Request.Builder().url("https://api.mymemory.translated.net/get?q=$q&langpair=en|$target").build()
                client.newCall(req).execute().use { r ->
                    val t = r.body?.string() ?: return@withContext text
                    JSONObject(t).optJSONObject("responseData")?.optString("translatedText") ?: text
                }
            } catch (e2: Exception) { text }
        }
    }

    // ════════ صوت: المناديب على رأس العمل · إنذارات · رسائل موجّهة ════════

    /** معرّف المستخدم (sub) من توكن الجلسة لتسجيله كمُصدِر الإنذار/الرسالة */
    private fun jwtSub(token: String): String? = try {
        val part = token.split(".")[1]
        val pad = part.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val json = String(android.util.Base64.decode(pad, android.util.Base64.DEFAULT))
        JSONObject(json).optStringOrNull("sub")
    } catch (_: Exception) { null }

    /** روستر المناديب حسب مشاريع المستخدم: حضور آخر يوم (attendance_daily) × المركبة المسلّمة */
    suspend fun roster(token: String, project: String? = null): RosterResult {
        val o = JSONObject(rpc(token, "soot_roster", JSONObject().put("p_project", project ?: JSONObject.NULL)))
        val pa = o.optJSONArray("projects") ?: JSONArray()
        val projects = (0 until pa.length()).map { pa.optString(it) }
        val dj = o.optJSONObject("dates") ?: JSONObject()
        val dates = HashMap<String, String>(); dj.keys().forEach { k -> dates[k] = dj.optString(k) }
        val ra = o.optJSONArray("rows") ?: JSONArray()
        val rows = (0 until ra.length()).map { i ->
            val r = ra.getJSONObject(i)
            val level = r.optString("level")
            RosterItem(
                captainId = r.optStringOrNull("captain_id"), iqama = r.optString("iqama"),
                name = r.optStringOrNull("name") ?: r.optString("iqama"),
                present = r.optBoolean("present", false),
                hours = if (r.isNull("hours")) null else r.optDouble("hours"),
                orders = r.optInt("orders", 0), short = r.optBoolean("short", false),
                absent = level == "absent" || level == "absent_vehicle",
                logged = r.optBoolean("logged", false), warns = r.optInt("warns", 0),
                project = r.optString("project"), plates = r.optStringOrNull("plates"),
                workDate = r.optStringOrNull("work_date"), result = r.optString("result"), level = level)
        }
        return RosterResult(rows, projects, dates)
    }

    /** إصدار إنذار للمندوب (حتى ٣) — يُسجَّل في وثيق ويظهر للموظف */
    suspend fun issueWarning(token: String, iqama: String, reason: String, captainId: String?, supName: String?, project: String? = null): Pair<Boolean, String> {
        val a = JSONObject().put("p_iqama", iqama).put("p_reason", reason).put("p_project", (project ?: "ninja").lowercase())
            .put("p_captain", captainId?.toLongOrNull() ?: JSONObject.NULL)
            .put("p_sup_id", jwtSub(token) ?: JSONObject.NULL)
            .put("p_sup_name", supName ?: JSONObject.NULL)
        val res = rpc(token, "soot_warn_issue", a).trim()
        val o = if (res.startsWith("[")) JSONArray(res).optJSONObject(0) ?: JSONObject() else JSONObject(res)
        return if (o.optBoolean("ok", false)) true to o.optInt("level", 0).toString()
        else false to o.optString("msg", "تعذّر إصدار الإنذار")
    }

    /** إرسال رسالة موجّهة للمناديب (حسب مشروع/مدينة/مركبة/فرع) */
    suspend fun sendBroadcast(token: String, title: String?, body: String, target: JSONObject, supName: String?) {
        rpc(token, "soot_broadcast_send", JSONObject()
            .put("p_title", title ?: JSONObject.NULL).put("p_body", body)
            .put("p_target", target).put("p_by", jwtSub(token) ?: JSONObject.NULL)
            .put("p_by_name", supName ?: JSONObject.NULL).put("p_expires", JSONObject.NULL))
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
