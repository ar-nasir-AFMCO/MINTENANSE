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

/** نموذج الشكوى */
data class Complaint(
    val id: String,
    val code: String,
    val title: String,
    val status: String,
    val priority: String,
    val category: String,
    val projectCode: String?,
    val currentDept: String?,
    val description: String?,
    val location: String?,
    val plate: String?,
    val slaDueAt: String?,
    val createdAt: String?
) {
    companion object {
        fun from(o: JSONObject) = Complaint(
            id = o.optString("id"),
            code = o.optString("code"),
            title = o.optString("title"),
            status = o.optString("status", "open"),
            priority = o.optString("priority", "med"),
            category = o.optString("category", "other"),
            projectCode = o.optStringOrNull("project_code"),
            currentDept = o.optStringOrNull("current_dept"),
            description = o.optStringOrNull("description"),
            location = o.optStringOrNull("location"),
            plate = o.optStringOrNull("plate"),
            slaDueAt = o.optStringOrNull("sla_due_at"),
            createdAt = o.optStringOrNull("created_at")
        )
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

data class Event(val kind: String, val actorName: String?, val note: String?, val createdAt: String?) {
    companion object {
        fun from(o: JSONObject) = Event(
            o.optString("kind"), o.optStringOrNull("actor_name"),
            o.optStringOrNull("note"), o.optStringOrNull("created_at")
        )
    }
}

data class Session(val token: String, val name: String, val projectCode: String?, val iqama: String)

fun JSONObject.optStringOrNull(k: String): String? =
    if (isNull(k) || !has(k)) null else optString(k)

/** عميل Supabase عبر دوال RPC */
object Api {
    private const val SB_URL = "https://coueubvyaokyycqpmirs.supabase.co"
    private const val SB_KEY = "sb_publishable_OmxwJmaYvjGhC5S-VcjwHQ_atz1gleF"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun rpc(fn: String, args: JSONObject): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$SB_URL/rest/v1/rpc/$fn")
            .addHeader("apikey", SB_KEY)
            .addHeader("Content-Type", "application/json")
            .post(args.toString().toRequestBody(JSON))
            .build()
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
        return Session(
            token = o.getString("token"),
            name = o.optString("name", iqama),
            projectCode = o.optStringOrNull("project_code"),
            iqama = o.optString("iqama", iqama)
        )
    }

    suspend fun myComplaints(token: String): List<Complaint> {
        val arr = JSONArray(rpc("emp_my_complaints", JSONObject().put("p_token", token)))
        return (0 until arr.length()).map { Complaint.from(arr.getJSONObject(it)) }
    }

    suspend fun raise(
        token: String, category: String, priority: String,
        title: String, description: String, location: String, plate: String
    ): Complaint {
        val a = JSONObject()
            .put("p_token", token).put("p_category", category).put("p_priority", priority)
            .put("p_title", title).put("p_description", description)
            .put("p_location", location).put("p_plate", plate)
        val res = rpc("emp_raise", a).trim()
        val o = if (res.startsWith("[")) JSONArray(res).getJSONObject(0) else JSONObject(res)
        return Complaint.from(o)
    }

    suspend fun messages(token: String, complaintId: String): List<Msg> {
        val arr = JSONArray(rpc("emp_messages", JSONObject().put("p_token", token).put("p_complaint", complaintId)))
        return (0 until arr.length()).map { Msg.from(arr.getJSONObject(it)) }
    }

    suspend fun events(token: String, complaintId: String): List<Event> {
        val arr = JSONArray(rpc("emp_events", JSONObject().put("p_token", token).put("p_complaint", complaintId)))
        return (0 until arr.length()).map { Event.from(arr.getJSONObject(it)) }
    }

    suspend fun send(token: String, complaintId: String, body: String) {
        rpc("emp_send", JSONObject().put("p_token", token).put("p_complaint", complaintId).put("p_body", body))
    }
}
