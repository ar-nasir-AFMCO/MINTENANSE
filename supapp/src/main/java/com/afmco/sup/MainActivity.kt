@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.afmco.sup

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch

private val Green = Color(0xFF0B7A4B)
private val GreenD = Color(0xFF064E30)
private val Gold = Color(0xFFC99A2E)
private val Red = Color(0xFFD84646)
private val Amber = Color(0xFFD9961F)
private val Blue = Color(0xFF2D72C6)
private val Violet = Color(0xFF7A4FC0)
private val Gray = Color(0xFF8A9A92)
private val Bg = Color(0xFFF1F6F3)

private val PRIORITIES = listOf("urgent" to "عاجل", "high" to "عالي", "med" to "متوسط", "low" to "منخفض")
fun prioLabel(p: String) = PRIORITIES.firstOrNull { it.first == p }?.second ?: p
fun prioColor(p: String) = when (p) { "urgent" -> Red; "high" -> Amber; "med" -> Blue; else -> Gray }
fun statusLabel(s: String) = when (s) { "open" -> "جديدة"; "prog" -> "قيد المعالجة"; "esc" -> "مُصعّدة"; "done" -> "منجزة"; else -> s }
fun statusColor(s: String) = when (s) { "open" -> Red; "prog" -> Amber; "esc" -> Violet; "done" -> Green; else -> Gray }
fun deptIcon(code: String?) = when (code) { "veh" -> "🚗"; "house" -> "🏠"; "finance" -> "💵"; "hr" -> "👷"; else -> "🗂" }
fun fmtAmount(a: Double): String = if (a == a.toLong().toDouble()) a.toLong().toString() else String.format("%.2f", a)

/** حفظ بيانات دخول المشرف للدخول التلقائي */
object Prefs {
    private const val F = "soot_sup"
    fun save(ctx: Context, email: String, pass: String) =
        ctx.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
            .putString("email", email).putString("pass", pass).apply()
    fun email(ctx: Context) = ctx.getSharedPreferences(F, Context.MODE_PRIVATE).getString("email", null)
    fun pass(ctx: Context) = ctx.getSharedPreferences(F, Context.MODE_PRIVATE).getString("pass", null)
    fun clear(ctx: Context) = ctx.getSharedPreferences(F, Context.MODE_PRIVATE).edit().clear().apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scheme = lightColorScheme(
            primary = Green, onPrimary = Color.White, secondary = Gold,
            background = Bg, surface = Color.White
        )
        setContent {
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var session by remember { mutableStateOf<SupSession?>(null) }
    var detail by remember { mutableStateOf<Complaint?>(null) }
    var booting by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val em = Prefs.email(context); val ps = Prefs.pass(context)
        if (!em.isNullOrBlank() && !ps.isNullOrBlank()) {
            try { session = Api.login(em, ps) } catch (_: Exception) { Prefs.clear(context) }
        }
        booting = false
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        val s = session
        when {
            booting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) }
            s == null -> LoginScreen(onLogin = { sess, em, ps -> Prefs.save(context, em, ps); session = sess })
            detail != null -> DetailScreen(s, detail!!, onChanged = { /* refreshed on back */ }) { detail = null }
            else -> HomeScreen(s, onOpen = { detail = it }, onLogout = { Prefs.clear(context); session = null; detail = null })
        }
    }
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Surface(color = Green, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { TextButton(onClick = onBack) { Text("→", color = Color.White, fontSize = 20.sp) }; Spacer(Modifier.width(4.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
            }
            if (action != null) action()
        }
    }
}

@Composable
fun LoginScreen(onLogin: (SupSession, String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(color = Green, shape = RoundedCornerShape(20.dp)) {
            Text("صوت", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("تطبيق المشرف", color = GreenD, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("ادخل بحسابك في المنصة", color = Gray, fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(email, { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        if (error != null) { Spacer(Modifier.height(10.dp)); Text(error!!, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            error = null; loading = true
            scope.launch {
                try { val em = email.trim(); onLogin(Api.login(em, pass), em, pass) }
                catch (e: Exception) { error = e.message ?: "تعذّر الدخول"; loading = false }
            }
        }, enabled = !loading && email.isNotBlank() && pass.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("دخول", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HomeScreen(session: SupSession, onOpen: (Complaint) -> Unit, onLogout: () -> Unit) {
    var list by remember { mutableStateOf<List<Complaint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var deptFilter by remember { mutableStateOf<String?>(null) }
    var kpiFilter by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true; error = null
        try { list = Api.complaints(session.token) }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }

    fun kpiMatch(c: Complaint): Boolean = when (kpiFilter) {
        "overdue" -> c.overdue
        "urgent"  -> c.status != "done" && c.priority == "urgent"
        "open"    -> c.status != "done"
        "esc"     -> c.status == "esc"
        "done"    -> c.status == "done"
        "pending" -> c.closePending
        else -> true
    }
    val filtered = list.filter { c ->
        (deptFilter == null || c.departmentCode == deptFilter) && kpiMatch(c) &&
        (q.isBlank() || listOf(c.title, c.code, c.projectName ?: "", c.empName ?: "",
            c.empIqama ?: "", c.categoryLabel ?: "", c.plate ?: "", statusLabel(c.status))
            .joinToString(" ").contains(q.trim(), ignoreCase = true))
    }
    val active = list.filter { it.status != "done" }
    val pendingN = list.count { it.closePending }
    val depts = listOf("veh" to "المركبات", "house" to "السكن", "finance" to "المالية", "hr" to "التوظيف")

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopBar("لوحة المشرف", session.name) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { refreshKey++ }) { Text("تحديث", color = Color.White, fontSize = 13.sp) }
                    TextButton(onClick = onLogout) { Text("خروج", color = Color.White, fontSize = 13.sp) }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                error != null -> Text("تعذّر التحميل: $error", Modifier.align(Alignment.Center).padding(24.dp), color = Red)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Kpi("تجاوز المهلة", list.count { it.overdue }, Red, kpiFilter == "overdue", Modifier.weight(1f)) { kpiFilter = if (kpiFilter == "overdue") null else "overdue" }
                            Kpi("عاجلة", active.count { it.priority == "urgent" }, Amber, kpiFilter == "urgent", Modifier.weight(1f)) { kpiFilter = if (kpiFilter == "urgent") null else "urgent" }
                            Kpi("مفتوحة", active.size, Green, kpiFilter == "open", Modifier.weight(1f)) { kpiFilter = if (kpiFilter == "open") null else "open" }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Kpi("مُصعّدة", list.count { it.status == "esc" }, Violet, kpiFilter == "esc", Modifier.weight(1f)) { kpiFilter = if (kpiFilter == "esc") null else "esc" }
                            Kpi("بانتظار التأكيد", pendingN, Gold, kpiFilter == "pending", Modifier.weight(1f)) { kpiFilter = if (kpiFilter == "pending") null else "pending" }
                            Kpi("منجزة", list.count { it.status == "done" }, Green, kpiFilter == "done", Modifier.weight(1f)) { kpiFilter = if (kpiFilter == "done") null else "done" }
                        }
                    }
                    item {
                        OutlinedTextField(q, { q = it }, placeholder = { Text("ابحث… (العنوان/المشروع/الموظف/اللوحة)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChipItem("الكل", deptFilter == null) { deptFilter = null } }
                            items(depts) { (code, name) ->
                                val n = list.count { it.departmentCode == code }
                                FilterChipItem("${deptIcon(code)} $name ($n)", deptFilter == code) {
                                    deptFilter = if (deptFilter == code) null else code
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) item { Text("لا شكاوى", color = Gray, modifier = Modifier.padding(20.dp)) }
                    items(filtered) { c -> ComplaintCard(c) { onOpen(c) } }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
fun Kpi(label: String, n: Int, color: Color, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) color.copy(alpha = .12f) else Color.White),
        shape = RoundedCornerShape(14.dp),
        border = if (selected) androidx.compose.foundation.BorderStroke(1.5.dp, color) else null,
        modifier = modifier.clickable { onClick() }
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("$n", fontSize = 22.sp, fontWeight = FontWeight.Black, color = color)
            Text(label, fontSize = 11.sp, color = Gray)
        }
    }
}

@Composable
fun ComplaintCard(c: Complaint, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(5.dp).height(100.dp).background(prioColor(c.priority)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (c.closePending) StatusBadge("⏳ تأكيد الموظف", Gold) else StatusBadge(statusLabel(c.status), statusColor(c.status))
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Chip("${deptIcon(c.departmentCode)} ${c.departmentName ?: "—"}", Violet)
                    Spacer(Modifier.width(6.dp))
                    Chip(c.categoryLabel ?: c.category, Blue)
                }
                Spacer(Modifier.height(6.dp))
                Row {
                    Chip("📁 ${c.projectName ?: c.projectCode ?: "—"}", Green)
                    if (!c.plate.isNullOrBlank()) { Spacer(Modifier.width(6.dp)); Chip("🚗 ${c.plate}", GreenD) }
                    if (c.amount != null) { Spacer(Modifier.width(6.dp)); Chip("💰 ${fmtAmount(c.amount)} ر.س", Gold) }
                    if (c.photos.isNotEmpty()) { Spacer(Modifier.width(6.dp)); Chip("📷 ${c.photos.size}", Gray) }
                }
                Spacer(Modifier.height(6.dp))
                Text("👤 ${c.empName ?: "—"}" + (c.empIqama?.let { " · 🆔 $it" } ?: "") + " · ${c.code}", color = Gray, fontSize = 11.sp)
                if (c.overdue) { Spacer(Modifier.height(4.dp)); Text("⚠️ تجاوز المهلة", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Surface(color = color.copy(alpha = .10f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun FilterChipItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Green else Color.White,
        shape = RoundedCornerShape(50),
        shadowElevation = if (selected) 0.dp else 1.dp,
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(text, color = if (selected) Color.White else GreenD, fontSize = 12.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
fun DetailScreen(session: SupSession, c: Complaint, onChanged: () -> Unit, onBack: () -> Unit) {
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(c.status) }
    var awaiting by remember { mutableStateOf(c.closePending) }
    var toast by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var photoView by remember { mutableStateOf<String?>(null) }
    var addedPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canAttach = c.departmentCode == "veh" || c.departmentCode == "house"

    BackHandler { onBack() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploading = true
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw RuntimeException("تعذّر قراءة الصورة")
                    val type = context.contentResolver.getType(uri) ?: ""
                    val ext = if (type.contains("png")) "png" else "jpg"
                    val url = Api.uploadPhoto(bytes, ext)
                    Api.addPhoto(session.token, c.id, url)
                    addedPhotos = addedPhotos + url; toast = "تمت إضافة الصورة"; onChanged(); refreshKey++
                } catch (e: Exception) { toast = e.message ?: "تعذّر رفع الصورة" } finally { uploading = false }
            }
        }
    }

    LaunchedEffect(refreshKey) {
        try { events = Api.events(session.token, c.id); msgs = Api.messages(session.token, c.id) } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = Bg,
        topBar = { TopBar(c.title, "${c.code} · ${c.categoryLabel ?: c.category}", onBack = onBack) },
        bottomBar = {
            if (status != "done") Surface(color = Color.White, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(reply, { reply = it }, placeholder = { Text("ردّك للموظف…") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (reply.isBlank()) return@Button
                        busy = true
                        scope.launch {
                            try { Api.reply(session.token, c.id, reply.trim()); reply = ""; refreshKey++ }
                            catch (e: Exception) { toast = e.message } finally { busy = false }
                        }
                    }, enabled = !busy, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 18.dp)) { Text("إرسال") }
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row {
                            if (awaiting && status != "done") StatusBadge("⏳ بانتظار تأكيد الموظف", Gold)
                            else StatusBadge(statusLabel(status), statusColor(status))
                            Spacer(Modifier.width(6.dp)); Chip(prioLabel(c.priority), prioColor(c.priority))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Chip("${deptIcon(c.departmentCode)} ${c.departmentName ?: "—"}", Violet)
                            Spacer(Modifier.width(6.dp)); Chip(c.categoryLabel ?: c.category, Blue)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("👤 ${c.empName ?: "—"}" + (c.empIqama?.let { "  ·  🆔 $it" } ?: "") + "  ·  📁 ${c.projectName ?: c.projectCode ?: "—"}", fontSize = 12.sp, color = Gray)
                        if (!c.description.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(c.description, fontSize = 13.sp) }
                        Spacer(Modifier.height(6.dp))
                        if (!c.plate.isNullOrBlank()) InfoLine("🚗 لوحة المركبة", c.plate)
                        if (c.amount != null) InfoLine("💰 المبلغ", "${fmtAmount(c.amount)} ر.س")
                        if (!c.location.isNullOrBlank()) InfoLine("📍 الموقع", c.location)
                        InfoLine("🗂 القسم المسؤول", c.currentDept ?: c.departmentName ?: "—")
                        if (c.toMaintenance) { Spacer(Modifier.height(6.dp)); Chip("🔧 محوّلة لتطبيق الصيانة", GreenD) }
                        if (c.toWatheeq) { Spacer(Modifier.height(6.dp)); Chip("📑 مُسجّلة في وثيق", Blue) }
                    }
                }
            }
            if (c.photos.isNotEmpty() || addedPhotos.isNotEmpty() || canAttach) item {
                val allPhotos = c.photos + addedPhotos
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("📷 المرفقات (${allPhotos.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD)
                        if (allPhotos.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(allPhotos) { url ->
                                    AsyncImage(
                                        model = url, contentDescription = null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(12.dp))
                                            .background(Bg).clickable { photoView = url }
                                    )
                                }
                            }
                        }
                        if (canAttach) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !uploading, modifier = Modifier.fillMaxWidth()) {
                                if (uploading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("جارٍ الرفع…") }
                                else Text("📷 إرفاق صورة")
                            }
                        }
                    }
                }
            }
            if (awaiting && status != "done") item {
                Surface(color = Gold.copy(alpha = .14f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("⏳ طلبتَ الإغلاق — بانتظار تأكيد الموظف لإغلاق الشكوى نهائيًا.",
                        color = Color(0xFF9A6B10), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                }
            }
            if (status != "done" && !awaiting) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        busy = true
                        scope.launch {
                            try { Api.escalate(session.token, c.id, "تصعيد للإدارة"); status = "esc"; toast = "تم التصعيد"; onChanged(); refreshKey++ }
                            catch (e: Exception) { toast = e.message } finally { busy = false }
                        }
                    }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Gold), modifier = Modifier.weight(1f)) { Text("⬆ تصعيد") }
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            try { Api.resolve(session.token, c.id, "تم الحل"); awaiting = true; status = "prog"; toast = "بانتظار تأكيد الموظف"; onChanged(); refreshKey++ }
                            catch (e: Exception) { toast = e.message } finally { busy = false }
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("✓ تم الحل") }
                }
            }
            if (toast != null) item { Text(toast!!, color = GreenD, fontSize = 12.sp) }
            item { Text("🔀 مسار الشكوى", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD) }
            items(events) { e -> TimelineRow(e) }
            item { Text("💬 المحادثة", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD) }
            if (msgs.isEmpty()) item { Text("لا رسائل بعد", color = Gray, fontSize = 12.sp) }
            items(msgs) { m -> MessageRow(m) }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }

    if (photoView != null) {
        Dialog(onDismissRequest = { photoView = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color(0xE6000000)).clickable { photoView = null },
                contentAlignment = Alignment.Center) {
                AsyncImage(model = photoView, contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", color = Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color(0xFF223229), fontSize = 12.sp)
    }
}

@Composable
fun TimelineRow(e: Event) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(10.dp).background(Green, RoundedCornerShape(50)))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(e.note ?: e.kind, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("${e.actorName ?: ""} · ${e.createdAt?.take(16)?.replace("T", " ") ?: ""}", color = Gray, fontSize = 11.sp)
        }
    }
}

@Composable
fun MessageRow(m: Msg) {
    val emp = m.authorKind == "employee"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (emp) Arrangement.Start else Arrangement.End) {
        Surface(color = if (emp) Color.White else Color(0xFFE9F5EE), shape = RoundedCornerShape(14.dp),
            shadowElevation = 1.dp, modifier = Modifier.widthIn(max = 280.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(m.authorName ?: (if (emp) "الموظف" else "أنا"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (emp) Blue else Green)
                Text(m.body, fontSize = 13.sp)
            }
        }
    }
}
