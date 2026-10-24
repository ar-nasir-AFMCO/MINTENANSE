@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.afmco.emp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

private val CATEGORIES = listOf(
    "veh" to "عطل / صيانة مركبة", "salary" to "راتب / مستحقات",
    "id" to "معرّف / حساب منصة", "attend" to "دوام / إجازة", "other" to "شكوى عامة"
)
private val PRIORITIES = listOf("urgent" to "عاجل", "high" to "عالي", "med" to "متوسط", "low" to "منخفض")

fun catLabel(c: String) = CATEGORIES.firstOrNull { it.first == c }?.second ?: c
fun prioLabel(p: String) = PRIORITIES.firstOrNull { it.first == p }?.second ?: p
fun prioColor(p: String) = when (p) { "urgent" -> Red; "high" -> Amber; "med" -> Blue; else -> Gray }
fun statusLabel(s: String) = when (s) { "open" -> "جديدة"; "prog" -> "قيد المعالجة"; "esc" -> "مُصعّدة"; "done" -> "منجزة"; else -> s }
fun statusColor(s: String) = when (s) { "open" -> Red; "prog" -> Amber; "esc" -> Violet; "done" -> Green; else -> Gray }

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
    var session by remember { mutableStateOf<Session?>(null) }
    var detail by remember { mutableStateOf<Complaint?>(null) }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        val s = session
        when {
            s == null -> LoginScreen { session = it }
            detail != null -> DetailScreen(s, detail!!) { detail = null }
            else -> HomeScreen(s, onOpen = { detail = it }, onLogout = { session = null })
        }
    }
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Surface(color = Green, shadowElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("→", color = Color.White, fontSize = 20.sp) }
                Spacer(Modifier.width(4.dp))
            }
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

    Column(
        Modifier.fillMaxSize().background(Bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(color = Green, shape = RoundedCornerShape(20.dp)) {
            Text("صوت", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("تطبيق الموظف", color = GreenD, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("ارفع شكواك وتابعها حتى الحل", color = Gray, fontSize = 13.sp)
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = iqama, onValueChange = { iqama = it.filter { c -> c.isDigit() } },
            label = { Text("رقم الإقامة") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it },
            label = { Text("الرمز السري (PIN)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                error = null; loading = true
                scope.launch {
                    try { onLogin(Api.login(iqama.trim(), pin.trim())) }
                    catch (e: Exception) { error = e.message ?: "تعذّر الدخول" }
                    finally { loading = false }
                }
            },
            enabled = !loading && iqama.isNotBlank() && pin.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("دخول", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HomeScreen(session: Session, onOpen: (Complaint) -> Unit, onLogout: () -> Unit) {
    var list by remember { mutableStateOf<List<Complaint>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        loading = true; error = null
        try { list = Api.myComplaints(session.token) }
        catch (e: Exception) { error = e.message }
        finally { loading = false }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopBar("هلا ${session.name}", session.projectCode?.let { "مشروع $it" }) {
                TextButton(onClick = onLogout) { Text("خروج", color = Color.White, fontSize = 13.sp) }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true }, containerColor = Gold, contentColor = Color.White
            ) { Text("＋ شكوى جديدة", fontWeight = FontWeight.Bold) }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                error != null -> Text("تعذّر التحميل: $error", Modifier.align(Alignment.Center).padding(24.dp), color = Red)
                list.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ما عندك شكاوى بعد", color = Gray)
                    Text("ارفع شكوى من الزر الذهبي تحت", color = Gray, fontSize = 12.sp)
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(list) { c -> ComplaintCard(c) { onOpen(c) } }
                    item { Spacer(Modifier.height(70.dp)) }
                }
            }
        }
    }

    if (showNew) NewComplaintDialog(session, onDismiss = { showNew = false }, onCreated = {
        showNew = false; refreshKey++
    })
}

@Composable
fun ComplaintCard(c: Complaint, onClick: () -> Unit) {
    Card(
        onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(5.dp).height(92.dp).background(prioColor(c.priority)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    StatusBadge(statusLabel(c.status), statusColor(c.status))
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Chip("📁 ${c.projectCode ?: "—"}", Green)
                    Spacer(Modifier.width(6.dp))
                    Chip(catLabel(c.category), Blue)
                }
                Spacer(Modifier.height(6.dp))
                Text("${c.code} · ${c.currentDept ?: ""}", color = Gray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = .14f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp))
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Surface(color = color.copy(alpha = .10f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun NewComplaintDialog(session: Session, onDismiss: () -> Unit, onCreated: () -> Unit) {
    var category by remember { mutableStateOf("veh") }
    var priority by remember { mutableStateOf("med") }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var loc by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf("") }
    var catMenu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        confirmButton = {
            Button(onClick = {
                error = null; loading = true
                scope.launch {
                    try { Api.raise(session.token, category, priority, title.trim(), desc.trim(), loc.trim(), plate.trim()); onCreated() }
                    catch (e: Exception) { error = e.message ?: "تعذّر الإرسال"; loading = false }
                }
            }, enabled = !loading && title.isNotBlank()) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("إرسال")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("إلغاء") } },
        title = { Text("شكوى جديدة", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("نوع الشكوى", fontSize = 12.sp, color = Gray)
                Box {
                    OutlinedButton(onClick = { catMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(catLabel(category)); Spacer(Modifier.weight(1f)); Text("▾")
                    }
                    DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        CATEGORIES.forEach { (k, v) ->
                            DropdownMenuItem(text = { Text(v) }, onClick = { category = k; catMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("الأولوية", fontSize = 12.sp, color = Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PRIORITIES.forEach { (k, v) ->
                        val sel = priority == k
                        Surface(
                            color = if (sel) prioColor(k) else Color(0xFFF0F3F1),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(v, color = if (sel) Color.White else Gray, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
                                    .clickableNoRipple { priority = k })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(title, { title = it }, label = { Text("عنوان مختصر") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(desc, { desc = it }, label = { Text("التفاصيل") },
                    modifier = Modifier.fillMaxWidth().height(90.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(loc, { loc = it }, label = { Text("الموقع (اختياري)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (category == "veh") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(plate, { plate = it }, label = { Text("لوحة المركبة (اختياري)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (error != null) { Spacer(Modifier.height(8.dp)); Text(error!!, color = Red, fontSize = 12.sp) }
            }
        }
    )
}

@Composable
fun DetailScreen(session: Session, c: Complaint, onBack: () -> Unit) {
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshKey) {
        try {
            events = Api.events(session.token, c.id)
            msgs = Api.messages(session.token, c.id)
        } catch (_: Exception) {}
    }

    Scaffold(
        containerColor = Bg,
        topBar = { TopBar(c.title, "${c.code} · ${catLabel(c.category)}", onBack = onBack) },
        bottomBar = {
            if (c.status != "done") Surface(color = Color.White, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(reply, { reply = it }, placeholder = { Text("اكتب رسالة للمشرف…") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (reply.isBlank()) return@Button
                            sending = true
                            scope.launch {
                                try { Api.send(session.token, c.id, reply.trim()); reply = ""; refreshKey++ }
                                catch (_: Exception) {} finally { sending = false }
                            }
                        }, enabled = !sending, shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 18.dp)
                    ) { Text("إرسال") }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row { StatusBadge(statusLabel(c.status), statusColor(c.status)); Spacer(Modifier.width(6.dp))
                            Chip(prioLabel(c.priority), prioColor(c.priority)) }
                        if (!c.description.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(c.description, fontSize = 13.sp) }
                        Spacer(Modifier.height(6.dp))
                        Text("القسم المسؤول: ${c.currentDept ?: "—"}", color = Gray, fontSize = 12.sp)
                        if (!c.location.isNullOrBlank()) Text("الموقع: ${c.location}", color = Gray, fontSize = 12.sp)
                    }
                }
            }
            item { SectionTitle("🔀 مسار الشكوى") }
            items(events) { e -> TimelineRow(e) }
            item { SectionTitle("💬 المحادثة") }
            if (msgs.isEmpty()) item { Text("لا رسائل بعد", color = Gray, fontSize = 12.sp) }
            items(msgs) { m -> MessageRow(m) }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD)

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
        Surface(
            color = if (me) Color(0xFFE9F5EE) else Color.White,
            shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(m.authorName ?: (if (me) "أنا" else "المشرف"), fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = if (me) Green else Blue)
                Text(m.body, fontSize = 13.sp)
            }
        }
    }
}

/** نقرة بدون أثر مرئي للعناصر البسيطة */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    return this.then(
        androidx.compose.foundation.clickable(
            interactionSource = interaction, indication = null, onClick = onClick
        )
    )
}
