@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.afmco.emp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
fun deptIcon(code: String?) = when (code) { "veh" -> "🚗"; "house" -> "🏠"; "finance" -> "💰"; "hr" -> "👤"; else -> "📋" }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scheme = lightColorScheme(primary = Green, onPrimary = Color.White, secondary = Gold, background = Bg, surface = Color.White)
        setContent {
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var session by remember { mutableStateOf<Session?>(null) }
    var detail by remember { mutableStateOf<Complaint?>(null) }
    var newFlow by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        val s = session
        when {
            s == null -> LoginScreen { session = it }
            newFlow -> NewComplaintFlow(s, onDone = { newFlow = false; reload++ }, onCancel = { newFlow = false })
            detail != null -> DetailScreen(s, detail!!) { detail = null }
            else -> HomeScreen(s, reload, onOpen = { detail = it }, onNew = { newFlow = true }, onLogout = { session = null })
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
fun LoginScreen(onLogin: (Session) -> Unit) {
    var iqama by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(color = Green, shape = RoundedCornerShape(20.dp)) {
            Text("صوت", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("تطبيق الموظف", color = GreenD, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("ارفع شكواك وتابعها حتى الحل", color = Gray, fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(iqama, { iqama = it.filter { c -> c.isDigit() } }, label = { Text("رقم الإقامة") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pin, { pin = it }, label = { Text("الرمز السري (PIN)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        if (error != null) { Spacer(Modifier.height(10.dp)); Text(error!!, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            error = null; loading = true
            scope.launch { try { onLogin(Api.login(iqama.trim(), pin.trim())) } catch (e: Exception) { error = e.message ?: "تعذّر الدخول" } finally { loading = false } }
        }, enabled = !loading && iqama.isNotBlank() && pin.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("دخول", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HomeScreen(session: Session, reload: Int, onOpen: (Complaint) -> Unit, onNew: () -> Unit, onLogout: () -> Unit) {
    var list by remember { mutableStateOf<List<Complaint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        loading = true; error = null
        try { list = Api.myComplaints(session.token) } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    Scaffold(containerColor = Bg,
        topBar = { TopBar("هلا ${session.name}", session.projectCode?.let { "مشروع $it" }) { TextButton(onClick = onLogout) { Text("خروج", color = Color.White, fontSize = 13.sp) } } },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNew, containerColor = Gold, contentColor = Color.White) { Text("＋ شكوى جديدة", fontWeight = FontWeight.Bold) } }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                error != null -> Text("تعذّر التحميل: $error", Modifier.align(Alignment.Center).padding(24.dp), color = Red)
                list.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ما عندك شكاوى بعد", color = Gray); Text("ارفع شكوى من الزر الذهبي تحت", color = Gray, fontSize = 12.sp)
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list) { c -> ComplaintCard(c) { onOpen(c) } }
                    item { Spacer(Modifier.height(70.dp)) }
                }
            }
        }
    }
}

@Composable
fun ComplaintCard(c: Complaint, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(5.dp).height(92.dp).background(prioColor(c.priority)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    StatusBadge(statusLabel(c.status), statusColor(c.status))
                }
                Spacer(Modifier.height(8.dp))
                Row { Chip("${deptIcon(c.departmentCode)} ${c.currentDept ?: ""}", Blue); Spacer(Modifier.width(6.dp)); Chip("📁 ${c.projectCode ?: "—"}", Green) }
                Spacer(Modifier.height(6.dp))
                Text(c.code + (if (c.photos.isNotEmpty()) "  ·  📷 ${c.photos.size}" else ""), color = Gray, fontSize = 11.sp)
            }
        }
    }
}

@Composable fun StatusBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(50)) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)) }
}
@Composable fun Chip(text: String, color: Color) {
    Surface(color = color.copy(alpha = .10f), shape = RoundedCornerShape(50)) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
}

@Composable
fun NewComplaintFlow(session: Session, onDone: () -> Unit, onCancel: () -> Unit) {
    var depts by remember { mutableStateOf<List<Department>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var dept by remember { mutableStateOf<Department?>(null) }
    var cat by remember { mutableStateOf<Category?>(null) }

    LaunchedEffect(Unit) {
        try { depts = Api.departments() } catch (e: Exception) { error = e.message } finally { loading = false }
    }

    val title = when { cat != null -> "${dept?.nameAr} · ${cat?.nameAr}"; dept != null -> dept?.nameAr ?: "شكوى جديدة"; else -> "اختر القسم" }
    Scaffold(containerColor = Bg, topBar = {
        TopBar(title, "شكوى جديدة", onBack = { when { cat != null -> cat = null; dept != null -> dept = null; else -> onCancel() } })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                error != null -> Text("تعذّر: $error", Modifier.align(Alignment.Center).padding(24.dp), color = Red)
                cat != null -> ComplaintForm(session, dept!!, cat!!, onDone)
                dept != null -> PickList("اختر نوع الشكوى", dept!!.categories.map { it.nameAr to it }) { cat = it }
                else -> PickGrid(depts) { dept = it }
            }
        }
    }
}

@Composable
fun PickGrid(depts: List<Department>, onPick: (Department) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("اختر القسم المناسب لشكواك", color = Gray, fontSize = 13.sp) }
        items(depts) { d ->
            Card(onClick = { onPick(d) }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Green.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text(deptIcon(d.code), fontSize = 24.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.nameAr, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(d.categories.joinToString(" · ") { it.nameAr }, color = Gray, fontSize = 11.sp)
                    }
                    Text("‹", color = Gray, fontSize = 22.sp)
                }
            }
        }
    }
}

@Composable
fun <T> PickList(hint: String, items: List<Pair<String, T>>, onPick: (T) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(hint, color = Gray, fontSize = 13.sp) }
        items(items) { (label, value) ->
            Card(onClick = { onPick(value) }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("‹", color = Gray, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
fun ComplaintForm(session: Session, dept: Department, cat: Category, onDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var loc by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("med") }
    var photos by remember { mutableStateOf<List<String>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploading = true; error = null
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw RuntimeException("تعذّر قراءة الصورة")
                    val type = context.contentResolver.getType(uri) ?: ""
                    val ext = if (type.contains("png")) "png" else "jpg"
                    val url = Api.uploadPhoto(bytes, ext)
                    photos = photos + url
                } catch (e: Exception) { error = e.message ?: "تعذّر رفع الصورة" } finally { uploading = false }
            }
        }
    }

    val amountVal = amount.toDoubleOrNull()
    val amountBad = cat.needsAmount && cat.maxAmount != null && amountVal != null && amountVal > cat.maxAmount
    val canSubmit = title.isNotBlank() && !submitting && !uploading && !amountBad &&
            (!cat.needsAmount || amountVal != null)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("القسم: ${dept.nameAr}  ·  النوع: ${cat.nameAr}", color = GreenD, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(title, { title = it }, label = { Text("عنوان مختصر") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(desc, { desc = it }, label = { Text("التفاصيل") }, modifier = Modifier.fillMaxWidth().height(100.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(loc, { loc = it }, label = { Text("الموقع (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        if (cat.needsPlate) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(plate, { plate = it }, label = { Text("رقم لوحة المركبة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (cat.needsAmount) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("المبلغ (ريال)" + (cat.maxAmount?.let { " — الحد الأقصى ${it.toInt()}" } ?: "")) },
                singleLine = true, isError = amountBad,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (amountBad) Text("المبلغ يتجاوز الحد الأقصى ${cat.maxAmount?.toInt()} ريال", color = Red, fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))
        Text("الأولوية", fontSize = 12.sp, color = Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRIORITIES.forEach { (k, v) ->
                val sel = priority == k
                Surface(color = if (sel) prioColor(k) else Color(0xFFF0F3F1), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f)) {
                    Text(v, color = if (sel) Color.White else Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth().clickable { priority = k })
                }
            }
        }

        if (cat.needsPhoto) {
            Spacer(Modifier.height(14.dp))
            Text("الصور", fontSize = 12.sp, color = Gray)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !uploading, modifier = Modifier.fillMaxWidth()) {
                if (uploading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("جارٍ الرفع…") }
                else Text("📷 إضافة صورة" + (if (photos.isNotEmpty()) "  (${photos.size})" else ""))
            }
        }

        if (error != null) { Spacer(Modifier.height(10.dp)); Text(error!!, color = Red, fontSize = 12.sp) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = {
            error = null; submitting = true
            scope.launch {
                try { Api.raise(session.token, cat.code, priority, title.trim(), desc.trim(), loc.trim(), plate.trim(), if (cat.needsAmount) amountVal else null, photos); onDone() }
                catch (e: Exception) { error = e.message ?: "تعذّر الإرسال"; submitting = false }
            }
        }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            if (submitting) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("إرسال الشكوى ➤", fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun DetailScreen(session: Session, c: Complaint, onBack: () -> Unit) {
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refreshKey) {
        try { events = Api.events(session.token, c.id); msgs = Api.messages(session.token, c.id) } catch (_: Exception) {}
    }
    Scaffold(containerColor = Bg,
        topBar = { TopBar(c.title, "${c.code} · ${c.currentDept ?: ""}", onBack = onBack) },
        bottomBar = {
            if (c.status != "done") Surface(color = Color.White, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(reply, { reply = it }, placeholder = { Text("اكتب رسالة…") }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (reply.isBlank()) return@Button
                        scope.launch { try { Api.send(session.token, c.id, reply.trim()); reply = ""; refreshKey++ } catch (_: Exception) {} }
                    }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 18.dp)) { Text("إرسال") }
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row { StatusBadge(statusLabel(c.status), statusColor(c.status)); Spacer(Modifier.width(6.dp)); Chip(prioLabel(c.priority), prioColor(c.priority)) }
                        if (!c.description.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(c.description, fontSize = 13.sp) }
                        Spacer(Modifier.height(6.dp))
                        Text("القسم المسؤول: ${c.currentDept ?: "—"}", color = Gray, fontSize = 12.sp)
                        if (!c.plate.isNullOrBlank()) Text("لوحة المركبة: ${c.plate}", color = Gray, fontSize = 12.sp)
                        if (c.amount != null) Text("المبلغ: ${c.amount.toInt()} ريال", color = Gray, fontSize = 12.sp)
                        if (!c.location.isNullOrBlank()) Text("الموقع: ${c.location}", color = Gray, fontSize = 12.sp)
                        if (c.photos.isNotEmpty()) Text("📷 مرفق ${c.photos.size} صورة", color = Blue, fontSize = 12.sp)
                    }
                }
            }
            item { Text("🔀 مسار الشكوى", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD) }
            items(events) { e -> TimelineRow(e) }
            item { Text("💬 المحادثة", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD) }
            if (msgs.isEmpty()) item { Text("لا رسائل بعد", color = Gray, fontSize = 12.sp) }
            items(msgs) { m -> MessageRow(m) }
            item { Spacer(Modifier.height(10.dp)) }
        }
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
    val me = m.authorKind == "employee"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
        Surface(color = if (me) Color(0xFFE9F5EE) else Color.White, shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp, modifier = Modifier.widthIn(max = 280.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(m.authorName ?: (if (me) "أنا" else "المشرف"), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (me) Green else Blue)
                Text(m.body, fontSize = 13.sp)
            }
        }
    }
}
