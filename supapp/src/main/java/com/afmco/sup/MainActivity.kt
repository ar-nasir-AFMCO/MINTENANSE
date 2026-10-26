@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.afmco.sup

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.delay

/** تحويل رقم الجوال السعودي لصيغة واتساب الدولية (9665XXXXXXXX) */
private fun waNumber(raw: String): String {
    var d = raw.filter { it.isDigit() }
    d = when {
        d.startsWith("00966") -> d.removePrefix("00")
        d.startsWith("966")   -> d
        d.startsWith("05")    -> "966" + d.drop(1)
        d.startsWith("5") && d.length == 9 -> "966$d"
        d.startsWith("0")     -> "966" + d.drop(1)
        else -> d
    }
    return d
}

private val Green = Color(0xFF0EA47A)
private val GreenD = Color(0xFF0B8263)
private val Gold = Color(0xFFD97706)
private val Red = Color(0xFFE11D48)
private val Amber = Color(0xFFD97706)
private val Blue = Color(0xFF2563EB)
private val Violet = Color(0xFF7C3AED)
private val Gray = Color(0xFF667588)
private val Bg = Color(0xFFF7F9FB)

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
    var showUpdates by remember { mutableStateOf(false) }
    var showAdvances by remember { mutableStateOf(false) }
    var booting by remember { mutableStateOf(true) }
    val context = LocalContext.current

    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var updateDone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runUpdate(info: UpdateInfo) {
        scope.launch {
            update = info; updateDone = false
            updateMsg = "تحديث جديد (${info.versionName}) — جارٍ التنزيل…"
            try { Updater.downloadAndInstall(context, info); updateMsg = "جاهز للتثبيت — أكمل من شاشة التثبيت"; updateDone = true }
            catch (e: Exception) { updateMsg = e.message ?: "تعذّر التنزيل"; updateDone = true }
        }
    }

    LaunchedEffect(Unit) {
        val em = Prefs.email(context); val ps = Prefs.pass(context)
        if (!em.isNullOrBlank() && !ps.isNullOrBlank()) {
            try { session = Api.login(em, ps) } catch (_: Exception) { Prefs.clear(context) }
        }
        booting = false
    }

    // فحص التحديث الذاتي عند الفتح
    LaunchedEffect(Unit) {
        val info = Updater.check(context)
        if (info != null) runUpdate(info)
    }

    // إذن الإشعارات (أندرويد 13+)
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        Notif.ensure(context)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Notif.allowed(context))
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        val s = session
        when {
            booting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) }
            s == null -> LoginScreen(onLogin = { sess, em, ps -> Prefs.save(context, em, ps); session = sess })
            showUpdates -> UpdatesScreen(s) { showUpdates = false }
            showAdvances -> AdvancesScreen(s) { showAdvances = false }
            detail != null -> DetailScreen(s, detail!!, onChanged = { /* refreshed on back */ }) { detail = null }
            else -> HomeScreen(s, onOpen = { detail = it }, onUpdates = { showUpdates = true }, onAdvances = { showAdvances = true }, onLogout = { Prefs.clear(context); session = null; detail = null })
        }
    }

    update?.let { info ->
        AlertDialog(
            onDismissRequest = { if (updateDone) update = null },
            icon = { Text("⬇️", fontSize = 28.sp) },
            title = { Text("تحديث التطبيق", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(updateMsg ?: "", fontSize = 14.sp)
                    if (!updateDone) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green) }
                    if (info.notes.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(info.notes, fontSize = 12.sp, color = Gray) }
                }
            },
            confirmButton = {
                if (updateDone) TextButton(onClick = { runUpdate(info) }) { Text("إعادة المحاولة", color = Green, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                if (updateDone) TextButton(onClick = { update = null }) { Text("لاحقًا", color = Gray) }
            }
        )
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
fun HomeScreen(session: SupSession, onOpen: (Complaint) -> Unit, onUpdates: () -> Unit, onAdvances: () -> Unit, onLogout: () -> Unit) {
    var list by remember { mutableStateOf<List<Complaint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var deptFilter by remember { mutableStateOf<String?>(null) }
    var kpiFilter by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var pendingUpdates by remember { mutableStateOf(0) }
    var pendingAdvances by remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey) { try { pendingUpdates = Api.pendingUpdates(session.token).size } catch (_: Exception) {} }
    LaunchedEffect(refreshKey) { try { pendingAdvances = Api.pendingAdvances(session.token).size } catch (_: Exception) {} }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var att by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var attBusy by remember { mutableStateOf(false) }
    var attMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refreshKey) { try { att = Api.myStatus(session.token) } catch (_: Exception) {} }
    fun doCheck(checkIn: Boolean) {
        attBusy = true; attMsg = null
        scope.launch {
            val loc = Loc.current(context)
            if (loc == null) { attMsg = "تعذّر تحديد الموقع — فعّل GPS وتأكد من الإذن"; attBusy = false; return@launch }
            try {
                if (checkIn) {
                    val r = Api.checkIn(session.token, loc.latitude, loc.longitude)
                    TrackingService.start(context)
                    attMsg = if (r.optBoolean("inside")) "تم الحضور · داخل ${r.optString("site")}"
                             else "تم الحضور · خارج المواقع (${r.optInt("dist")} م من ${r.optString("site", "أقرب موقع")})"
                } else {
                    Api.checkOut(session.token, loc.latitude, loc.longitude)
                    TrackingService.stop(context)
                    attMsg = "تم الانصراف"
                }
                att = Api.myStatus(session.token)
            } catch (e: Exception) { attMsg = e.message } finally { attBusy = false }
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) doCheck(true) else attMsg = "يلزم إذن الموقع لتسجيل الحضور"
    }
    fun startCheckIn() {
        if (Loc.hasPermission(context)) doCheck(true)
        else permLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // كشف الجديد لإطلاق الإشعارات الداخلية (null = أول تحميل، لا إشعار)
    var notifSeen by remember { mutableStateOf<Map<String, Pair<String, Boolean>>?>(null) }
    fun checkNotif(newList: List<Complaint>) {
        val cur = newList.associate { it.id to (it.status to it.closePending) }
        val prev = notifSeen
        if (prev != null) {
            for (c in newList) {
                val p = prev[c.id]
                if (p == null) Notif.show(context, "شكوى جديدة", "${c.title} · ${c.code}", c.id.hashCode())
                else if (p.first != "done" && c.status == "done") Notif.show(context, "تم إغلاق شكوى", "${c.code} — ${c.title}", c.id.hashCode() xor 1)
            }
        }
        notifSeen = cur
    }

    LaunchedEffect(refreshKey) {
        loading = true; error = null
        try { val n = Api.complaints(session.token); list = n; checkNotif(n) }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }

    // تحديث تلقائي صامت كل 25 ثانية طول ما الشاشة مفتوحة
    LaunchedEffect(Unit) {
        while (true) {
            delay(25_000L)
            try { val n = Api.complaints(session.token); list = n; checkNotif(n) } catch (_: Exception) {}
        }
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
                    TextButton(onClick = onUpdates) { Text("✎ طلبات" + (if (pendingUpdates > 0) " ($pendingUpdates)" else ""), color = if (pendingUpdates > 0) Gold else Color.White, fontSize = 13.sp, fontWeight = if (pendingUpdates > 0) FontWeight.Bold else FontWeight.Normal) }
                    TextButton(onClick = onAdvances) { Text("💵 سلف" + (if (pendingAdvances > 0) " ($pendingAdvances)" else ""), color = if (pendingAdvances > 0) Gold else Color.White, fontSize = 13.sp, fontWeight = if (pendingAdvances > 0) FontWeight.Bold else FontWeight.Normal) }
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
                    // كرت الحضور — يسجّل كل المشرفين حضورهم (المتابعة في صفحة الإدارة فقط)
                    item {
                        val checkedIn = att?.optBoolean("checked_in") == true
                        val site = att?.optString("site").takeUnless { it.isNullOrBlank() || it == "null" }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (checkedIn) Green.copy(alpha = .08f) else Color.White),
                            shape = RoundedCornerShape(14.dp),
                            border = if (checkedIn) androidx.compose.foundation.BorderStroke(1.5.dp, Green) else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (checkedIn) Green else Gray))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (checkedIn) "في الدوام" else "خارج الدوام", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (checkedIn) Green else Gray)
                                    Spacer(Modifier.weight(1f))
                                    if (attBusy) CircularProgressIndicator(Modifier.size(20.dp), color = Green, strokeWidth = 2.dp)
                                    else Button(
                                        onClick = { if (checkedIn) doCheck(false) else startCheckIn() },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (checkedIn) Red else Green),
                                        shape = RoundedCornerShape(10.dp)
                                    ) { Text(if (checkedIn) "انصراف" else "حضور", color = Color.White, fontWeight = FontWeight.Bold) }
                                }
                                if (checkedIn && site != null) { Spacer(Modifier.height(4.dp)); Text("الموقع: $site", fontSize = 12.sp, color = Gray) }
                                if (attMsg != null) { Spacer(Modifier.height(6.dp)); Text(attMsg!!, fontSize = 12.sp, color = Green) }
                                Spacer(Modifier.height(6.dp))
                                Text("يُسجَّل موقعك كل ٥ دقائق أثناء الدوام فقط", fontSize = 10.sp, color = Gray)
                            }
                        }
                    }
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
fun UpdatesScreen(session: SupSession, onBack: () -> Unit) {
    var list by remember { mutableStateOf<List<DataUpdate>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler { onBack() }
    LaunchedEffect(refreshKey) { loading = true; try { list = Api.pendingUpdates(session.token) } catch (_: Exception) {} finally { loading = false } }
    fun act(id: String, approve: Boolean) {
        scope.launch {
            try { Api.reviewUpdate(session.token, id, approve, null)
                android.widget.Toast.makeText(context, if (approve) "تم الاعتماد وتحديث وثيق" else "تم رفض الطلب", android.widget.Toast.LENGTH_SHORT).show()
                refreshKey++ }
            catch (e: Exception) { android.widget.Toast.makeText(context, e.message ?: "تعذّر", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    Scaffold(containerColor = Bg, topBar = { TopBar("طلبات تحديث البيانات", "${list.size} معلّقة", onBack = onBack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                list.isEmpty() -> Text("لا توجد طلبات معلّقة", Modifier.align(Alignment.Center).padding(24.dp), color = Gray)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list) { u ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text("✎ ${u.fieldLabel ?: "تحديث بيانات"}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GreenD)
                                Text("إقامة الموظف: ${u.iqama ?: "—"}", fontSize = 12.sp, color = Gray)
                                Spacer(Modifier.height(10.dp))
                                InfoLine("القيمة الحالية", if (u.oldValue.isNullOrBlank()) "— (غير مُسجّلة)" else u.oldValue)
                                InfoLine("القيمة الجديدة المطلوبة", u.newValue)
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = { act(u.id, true) }, colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(46.dp)) { Text("✓ اعتماد", fontWeight = FontWeight.Bold) }
                                    OutlinedButton(onClick = { act(u.id, false) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(46.dp)) { Text("✕ رفض", color = Red, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
fun AdvancesScreen(session: SupSession, onBack: () -> Unit) {
    var list by remember { mutableStateOf<List<Advance>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler { onBack() }
    LaunchedEffect(refreshKey) { loading = true; try { list = Api.pendingAdvances(session.token) } catch (_: Exception) {} finally { loading = false } }
    fun act(id: String, approve: Boolean) {
        scope.launch {
            try { Api.reviewAdvance(session.token, id, approve, null)
                android.widget.Toast.makeText(context, if (approve) "تم اعتماد السلفة" else "تم رفض السلفة", android.widget.Toast.LENGTH_SHORT).show()
                refreshKey++ }
            catch (e: Exception) { android.widget.Toast.makeText(context, e.message ?: "تعذّر", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    Scaffold(containerColor = Bg, topBar = { TopBar("طلبات السلف", "${list.size} معلّقة · الحد 300 ريال", onBack = onBack) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                list.isEmpty() -> Text("لا توجد سلف معلّقة", Modifier.align(Alignment.Center).padding(24.dp), color = Gray)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list) { a ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("💵 ${a.amount.toInt()} ريال", fontWeight = FontWeight.Black, fontSize = 18.sp, color = GreenD)
                                    Spacer(Modifier.weight(1f))
                                    a.createdAt?.let { Text(it.take(10), fontSize = 11.sp, color = Gray) }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("${a.name ?: "—"}" + (a.iqama?.let { "  ·  🆔 $it" } ?: "") + (a.project?.let { "  ·  📁 $it" } ?: ""), fontSize = 12.sp, color = Gray)
                                if (!a.reason.isNullOrBlank()) { Spacer(Modifier.height(6.dp)); Text("السبب: ${a.reason}", fontSize = 12.5.sp) }
                                Spacer(Modifier.height(12.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = { act(a.id, true) }, colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(46.dp)) { Text("✓ اعتماد", fontWeight = FontWeight.Bold) }
                                    OutlinedButton(onClick = { act(a.id, false) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f).height(46.dp)) { Text("✕ رفض", color = Red, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
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
    var empInfo by remember { mutableStateOf<EmpInfo?>(null) }
    var priority by remember { mutableStateOf(c.priority) }
    var people by remember { mutableStateOf<List<Person>>(emptyList()) }
    var parties by remember { mutableStateOf<List<ExtraParty>>(emptyList()) }
    var mentionQuery by remember { mutableStateOf("") }
    var mentionOpen by remember { mutableStateOf(false) }
    var descTr by remember { mutableStateOf<String?>(null) }
    var descBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canAttach = c.departmentCode == "veh" || c.departmentCode == "house"

    BackHandler { onBack() }

    LaunchedEffect(c.id) {
        if (!c.empIqama.isNullOrBlank()) { try { empInfo = Api.empInfo(session.token, c.empIqama) } catch (_: Exception) {} }
        try { people = Api.people(session.token) } catch (_: Exception) {}
        try { parties = Api.extraParties(session.token, c.id) } catch (_: Exception) {}
    }

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

    // تحديث المحادثة والمسار تلقائيًا كل 25 ثانية
    LaunchedEffect(c.id) {
        while (true) {
            delay(25_000L)
            try { events = Api.events(session.token, c.id); msgs = Api.messages(session.token, c.id) } catch (_: Exception) {}
        }
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
                        if (!c.description.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp)); Text(c.description, fontSize = 13.sp)
                            if (descTr == null) {
                                TextButton(onClick = {
                                    descBusy = true
                                    scope.launch { try { descTr = Api.translate(c.description, "ar") } catch (e: Exception) { toast = "تعذّرت الترجمة" } finally { descBusy = false } }
                                }, contentPadding = PaddingValues(0.dp)) {
                                    if (descBusy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Blue)
                                    else Text("🌐 ترجمة للعربية", fontSize = 12.sp, color = Blue)
                                }
                            } else {
                                Spacer(Modifier.height(4.dp))
                                Surface(color = Color(0xFFEFF6FF), shape = RoundedCornerShape(8.dp)) {
                                    Text(descTr!!, fontSize = 13.sp, color = Color(0xFF1E3A5F), modifier = Modifier.padding(8.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (!c.plate.isNullOrBlank()) InfoLine("🚗 لوحة المركبة", c.plate)
                        if (c.amount != null) InfoLine("💰 المبلغ", "${fmtAmount(c.amount)} ر.س")
                        if (!c.location.isNullOrBlank()) InfoLine("📍 الموقع", c.location)
                        InfoLine("🗂 القسم المسؤول", c.currentDept ?: c.departmentName ?: "—")
                        empInfo?.let { ei ->
                            if (!ei.phone.isNullOrBlank()) {
                                InfoLine("📞 جوال الموظف", ei.phone)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = {
                                            try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${ei.phone}"))) }
                                            catch (e: Exception) { toast = "تعذّر فتح الاتصال" }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f).height(46.dp)
                                    ) { Text("📞 اتصال", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                    Button(
                                        onClick = {
                                            try {
                                                val wa = waNumber(ei.phone)
                                                val msg = Uri.encode("السلام عليكم، بخصوص الشكوى ${c.code}: ${c.title}")
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$wa?text=$msg")))
                                            } catch (e: Exception) { toast = "تعذّر فتح واتساب" }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.weight(1f).height(46.dp)
                                    ) { Text("💬 واتساب", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            if (!ei.area.isNullOrBlank()) InfoLine("📍 منطقة الموظف", ei.area)
                            if (c.departmentCode == "veh" || ei.vehicles.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text("🚗 مركبات الموظف", color = Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("اللوحة · المركبة · مكتب الإيجار · المنطقة", color = Gray, fontSize = 10.sp)
                                if (ei.vehicles.isEmpty()) Text("لا توجد", color = Color(0xFF223229), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                                else ei.vehicles.forEach { v ->
                                    Text("• $v", color = Color(0xFF223229), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 3.dp))
                                }
                                Spacer(Modifier.height(2.dp))
                            }
                            if (!ei.housing.isNullOrBlank()) InfoLine("🏠 سكن الموظف", ei.housing)
                        }
                        if (c.toMaintenance) { Spacer(Modifier.height(6.dp)); Chip("🔧 محوّلة لتطبيق الصيانة", GreenD) }
                        if (c.toWatheeq) { Spacer(Modifier.height(6.dp)); Chip("📑 مُسجّلة في وثيق", Blue) }
                    }
                }
            }
            if (status != "done") item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("⚑ الأولوية (يحددها المشرف)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PRIORITIES.forEach { pr ->
                                val k = pr.first; val lbl = pr.second; val sel = priority == k
                                Surface(color = if (sel) prioColor(k) else Color(0xFFEFF2F0), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                                    Text(lbl, color = if (sel) Color.White else Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 9.dp).fillMaxWidth().clickable {
                                            scope.launch { try { Api.setPriority(session.token, c.id, k); priority = k; toast = "تم تحديد الأولوية"; onChanged() } catch (e: Exception) { toast = e.message } }
                                        })
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("🤝 الأطراف المُشارَكة", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD)
                        Spacer(Modifier.height(6.dp))
                        if (parties.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                parties.forEach { p -> Chip((if (p.kind == "dept") "🏢 " else "👤 ") + (p.label ?: p.deptCode ?: ""), Violet) }
                            }
                            Spacer(Modifier.height(8.dp))
                        } else { Text("لم تُشارَك أطراف بعد.", fontSize = 11.5.sp, color = Gray); Spacer(Modifier.height(8.dp)) }
                        val DEPTS = listOf("veh" to "🚗 المركبات", "house" to "🏠 السكن", "finance" to "💵 المالية", "hr" to "👷 التوظيف")
                        val q = mentionQuery.trim()
                        val deptMatches = DEPTS.filter { q.isBlank() || it.second.contains(q) }
                        val peopleMatches = people.filter { q.isBlank() || it.name.contains(q, true) }.take(8)
                        ExposedDropdownMenuBox(expanded = mentionOpen, onExpandedChange = { mentionOpen = !mentionOpen }) {
                            OutlinedTextField(mentionQuery, { mentionQuery = it; mentionOpen = true },
                                placeholder = { Text("@ إشراك قسم أو شخص من الإدارة") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().menuAnchor())
                            ExposedDropdownMenu(expanded = mentionOpen, onDismissRequest = { mentionOpen = false }) {
                                deptMatches.forEach { d ->
                                    DropdownMenuItem(text = { Text(d.second) }, onClick = {
                                        mentionOpen = false; mentionQuery = ""
                                        scope.launch { try { Api.addParty(session.token, c.id, "dept", d.first, d.second); parties = Api.extraParties(session.token, c.id); toast = "تم الإشراك" } catch (e: Exception) { toast = e.message } }
                                    })
                                }
                                peopleMatches.forEach { pers ->
                                    DropdownMenuItem(text = { Text("👤 ${pers.name}" + (pers.role?.let { " · $it" } ?: "")) }, onClick = {
                                        mentionOpen = false; mentionQuery = ""
                                        scope.launch { try { Api.addParty(session.token, c.id, "person", pers.id, pers.name); parties = Api.extraParties(session.token, c.id); toast = "تم الإشراك" } catch (e: Exception) { toast = e.message } }
                                    })
                                }
                            }
                        }
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
        Text(value, color = Color(0xFF223229), fontSize = 12.sp, modifier = Modifier.weight(1f))
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
    val scope = rememberCoroutineScope()
    var tr by remember(m.body) { mutableStateOf<String?>(null) }
    var busy by remember(m.body) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (emp) Arrangement.Start else Arrangement.End) {
        Surface(color = if (emp) Color.White else Color(0xFFE9F5EE), shape = RoundedCornerShape(14.dp),
            shadowElevation = 1.dp, modifier = Modifier.widthIn(max = 280.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(m.authorName ?: (if (emp) "الموظف" else "أنا"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (emp) Blue else Green)
                Text(m.body, fontSize = 13.sp)
                // زر ترجمة لرسائل الموظف فقط (قد تكون بلغة أخرى)
                if (emp) {
                    if (tr == null) {
                        TextButton(onClick = {
                            busy = true
                            scope.launch { try { tr = Api.translate(m.body, "ar") } catch (_: Exception) { tr = "تعذّرت الترجمة" } finally { busy = false } }
                        }, contentPadding = PaddingValues(0.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = Blue)
                            else Text("🌐 ترجمة", fontSize = 11.sp, color = Blue)
                        }
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text(tr!!, fontSize = 12.5.sp, color = Color(0xFF1E3A5F))
                    }
                }
            }
        }
    }
}
