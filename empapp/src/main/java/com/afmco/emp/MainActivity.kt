@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.afmco.emp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

private val Green = Color(0xFF0EA47A)
private val GreenD = Color(0xFF0B8263)
private val Gold = Color(0xFFD97706)
private val Red = Color(0xFFE11D48)
private val Amber = Color(0xFFD97706)
private val Blue = Color(0xFF2563EB)
private val Violet = Color(0xFF7C3AED)
private val Gray = Color(0xFF667588)
private val Bg = Color(0xFFF7F9FB)

// ═════════════════ اللغات / Languages ═════════════════
enum class Lang(val code: String, val rtl: Boolean, val label: String) {
    AR("ar", true, "العربية"), EN("en", false, "English"), UR("ur", true, "اردو")
}

class L(
    val empApp: String, val loginSub: String, val iqamaLabel: String, val pinLabel: String,
    val loginBtn: String, val loginFail: String, val firstHint: String,
    val hi: String, val project: String, val logout: String, val refresh: String,
    val newComplaint: String, val myComplaints: String, val noComplaints: String, val noComplaintsSub: String,
    val iqama: String, val assignedVehicles: String, val loadFail: String,
    val confirmClose: String, val awaitingYou: String,
    val chooseDept: String, val newComplaintT: String, val chooseDeptHint: String, val chooseCat: String,
    val dept: String, val type: String, val titleField: String, val detailsField: String, val locationField: String,
    val chooseVehicle: String, val manualPlate: String, val plateField: String, val chooseFromVehicles: String,
    val amountField: String, val maxWord: String, val amountExceeds: String, val priority: String,
    val photos: String, val uploadingTxt: String, val addPhoto: String, val submit: String, val submitFail: String,
    val photoReadFail: String, val photoUploadFail: String,
    val supResolved: String, val confirmFinal: String, val yesResolved: String, val notResolved: String,
    val closedOk: String, val reopened: String, val notResolvedNote: String,
    val respDept: String, val plateLabel: String, val amountLabel: String, val sar: String, val locationLabel: String,
    val photosAttached: String, val route: String, val conversation: String, val noMessages: String,
    val writeMessage: String, val sendBtn: String, val meWord: String, val supervisor: String,
    val stOpen: String, val stProg: String, val stEsc: String, val stDone: String,
    val prUrgent: String, val prHigh: String, val prMed: String, val prLow: String,
    val dVeh: String, val dHouse: String, val dFinance: String, val dHr: String,
    val cVehAccident: String, val cVehTheft: String, val cVehMaint: String,
    val cHouseMaint: String, val cHouseCustody: String, val cHouseTheft: String,
    val cFinDeduction: String, val cFinDamage: String, val cFinSalary: String, val cFinAdvance: String,
    val cHrStart: String, val cHrStop: String, val cHrProject: String, val cHrData: String
)

private val AR = L(
    "تطبيق الموظف","ارفع شكواك وتابعها حتى الحل","رقم الإقامة","الرمز السري (PIN)",
    "دخول","تعذّر الدخول","أول مرة؟ اختر رمزًا تتذكّره — سيصبح رمزك الدائم",
    "هلا","مشروع","خروج","تحديث",
    "＋ شكوى جديدة","شكاويّ","ما عندك شكاوى بعد","ارفع شكوى من الزر الذهبي تحت",
    "الإقامة","🚗 المركبات المُسندة إليك","تعذّر التحميل",
    "⏳ أكّد الإغلاق","⏳ بانتظار تأكيدك",
    "اختر القسم","شكوى جديدة","اختر القسم المناسب لشكواك","اختر نوع الشكوى",
    "القسم","النوع","عنوان مختصر","التفاصيل","الموقع (اختياري)",
    "اختر مركبتك","أو أدخل رقم اللوحة يدويًا","رقم لوحة المركبة","اختر من مركباتك",
    "المبلغ (ريال)","الحد الأقصى","المبلغ يتجاوز الحد الأقصى","الأولوية",
    "الصور","جارٍ الرفع…","📷 إضافة صورة","إرسال الشكوى ➤","تعذّر الإرسال",
    "تعذّر قراءة الصورة","تعذّر رفع الصورة",
    "⏳ المشرف يفيد بأن الشكوى حُلّت","تأكيدك هو الخطوة الأخيرة لإغلاق الشكوى.","✅ نعم، تم الحل","لم تُحل بعد",
    "تم إغلاق الشكوى ✅","أُعيد فتح الشكوى","الموظف يفيد بأن المشكلة لم تُحل",
    "القسم المسؤول","لوحة المركبة","المبلغ","ريال","الموقع",
    "📷 مرفق","🔀 مسار الشكوى","💬 المحادثة","لا رسائل بعد",
    "اكتب رسالة…","إرسال","أنا","المشرف",
    "جديدة","قيد المعالجة","مُصعّدة","منجزة",
    "عاجل","عالي","متوسط","منخفض",
    "المركبات","السكن","المالية","التوظيف",
    "حادث","سرقة","صيانة",
    "صيانة","عهدة","بلاغ سرقة",
    "خصميات","تلفيات","رواتب","سلف",
    "البدء","التوقف","تحديث مشروع","تحديث بيانات"
)

private val EN = L(
    "Employee App","Submit and track your complaints","Iqama Number","PIN",
    "Login","Login failed","First time? Pick a PIN you'll remember — it becomes your password",
    "Hi","Project","Logout","Refresh",
    "＋ New Complaint","My Complaints","No complaints yet","Tap the gold button below to add one",
    "Iqama","🚗 Vehicles assigned to you","Failed to load",
    "⏳ Confirm close","⏳ Awaiting your confirmation",
    "Choose department","New Complaint","Choose the right department for your complaint","Choose complaint type",
    "Department","Type","Short title","Details","Location (optional)",
    "Choose your vehicle","Or enter plate manually","Vehicle plate number","Choose from your vehicles",
    "Amount (SAR)","Max","Amount exceeds the max","Priority",
    "Photos","Uploading…","📷 Add photo","Submit complaint ➤","Submit failed",
    "Couldn't read image","Couldn't upload image",
    "⏳ Supervisor marked it resolved","Your confirmation is the final step to close it.","✅ Yes, resolved","Not resolved yet",
    "Complaint closed ✅","Complaint reopened","Employee reports the issue is not resolved",
    "Responsible dept","Vehicle plate","Amount","SAR","Location",
    "📷 Attached","🔀 Complaint route","💬 Conversation","No messages yet",
    "Write a message…","Send","Me","Supervisor",
    "New","In progress","Escalated","Done",
    "Urgent","High","Medium","Low",
    "Vehicles","Housing","Finance","Employment",
    "Accident","Theft","Maintenance",
    "Maintenance","Assets/Custody","Theft report",
    "Deductions","Damages","Salary","Advance",
    "Start work","Stop work","Update project","Update data"
)

private val UR = L(
    "ملازم ایپ","اپنی شکایت درج کریں اور پیروی کریں","اقامہ نمبر","پن کوڈ",
    "لاگ اِن","لاگ اِن ناکام","پہلی بار؟ یاد رہنے والا پن منتخب کریں — یہی آپ کا پاس ورڈ ہوگا",
    "خوش آمدید","پروجیکٹ","لاگ آؤٹ","تازہ کریں",
    "＋ نئی شکایت","میری شکایات","ابھی کوئی شکایت نہیں","درج کرنے کے لیے نیچے سنہری بٹن دبائیں",
    "اقامہ","🚗 آپ کو تفویض شدہ گاڑیاں","لوڈنگ ناکام",
    "⏳ بندش کی تصدیق","⏳ آپ کی تصدیق کے منتظر",
    "شعبہ منتخب کریں","نئی شکایت","اپنی شکایت کے لیے مناسب شعبہ منتخب کریں","شکایت کی قسم منتخب کریں",
    "شعبہ","قسم","مختصر عنوان","تفصیلات","مقام (اختیاری)",
    "اپنی گاڑی منتخب کریں","یا پلیٹ نمبر دستی درج کریں","گاڑی کا پلیٹ نمبر","اپنی گاڑیوں میں سے منتخب کریں",
    "رقم (ریال)","زیادہ سے زیادہ","رقم حد سے تجاوز کر گئی","ترجیح",
    "تصاویر","اپ لوڈ ہو رہا ہے…","📷 تصویر شامل کریں","شکایت بھیجیں ➤","بھیجنا ناکام",
    "تصویر پڑھی نہیں جا سکی","تصویر اپ لوڈ نہیں ہو سکی",
    "⏳ سپروائزر نے حل شدہ قرار دیا","آپ کی تصدیق بندش کا آخری مرحلہ ہے۔","✅ جی ہاں، حل ہو گیا","ابھی حل نہیں ہوا",
    "شکایت بند ہو گئی ✅","شکایت دوبارہ کھل گئی","ملازم کے مطابق مسئلہ حل نہیں ہوا",
    "ذمہ دار شعبہ","گاڑی پلیٹ","رقم","ریال","مقام",
    "📷 منسلک","🔀 شکایت کا سفر","💬 گفتگو","ابھی کوئی پیغام نہیں",
    "پیغام لکھیں…","بھیجیں","میں","سپروائزر",
    "نئی","زیرِ عمل","آگے بڑھائی گئی","مکمل",
    "فوری","اعلیٰ","درمیانہ","کم",
    "گاڑیاں","رہائش","مالیات","ملازمت",
    "حادثہ","چوری","مرمت",
    "مرمت","سامان/عہدہ","چوری کی رپورٹ",
    "کٹوتیاں","نقصانات","تنخواہ","ایڈوانس",
    "کام شروع","کام بند","پروجیکٹ اپ ڈیٹ","ڈیٹا اپ ڈیٹ"
)

val STR = mapOf(Lang.AR to AR, Lang.EN to EN, Lang.UR to UR)
val LocalL = staticCompositionLocalOf { AR }
val LocalLang = staticCompositionLocalOf { Lang.AR }
val LocalSetLang = staticCompositionLocalOf<(Lang) -> Unit> { {} }

fun deptName(code: String?, L: L) = when (code) { "veh" -> L.dVeh; "house" -> L.dHouse; "finance" -> L.dFinance; "hr" -> L.dHr; else -> code ?: "" }
fun catName(code: String?, fallback: String, L: L) = when (code) {
    "veh_accident" -> L.cVehAccident; "veh_theft" -> L.cVehTheft; "veh_maint" -> L.cVehMaint
    "house_maint" -> L.cHouseMaint; "house_custody" -> L.cHouseCustody; "house_theft" -> L.cHouseTheft
    "fin_deduction" -> L.cFinDeduction; "fin_damage" -> L.cFinDamage; "fin_salary" -> L.cFinSalary; "fin_advance" -> L.cFinAdvance
    "hr_start" -> L.cHrStart; "hr_stop" -> L.cHrStop; "hr_project" -> L.cHrProject; "hr_data" -> L.cHrData
    else -> fallback
}

private val PRIOS = listOf("urgent", "high", "med", "low")
fun prioLabel(p: String, L: L) = when (p) { "urgent" -> L.prUrgent; "high" -> L.prHigh; "med" -> L.prMed; "low" -> L.prLow; else -> p }
fun prioColor(p: String) = when (p) { "urgent" -> Red; "high" -> Amber; "med" -> Blue; else -> Gray }
fun statusLabel(s: String, L: L) = when (s) { "open" -> L.stOpen; "prog" -> L.stProg; "esc" -> L.stEsc; "done" -> L.stDone; else -> s }
fun statusColor(s: String) = when (s) { "open" -> Red; "prog" -> Amber; "esc" -> Violet; "done" -> Green; else -> Gray }
fun deptIcon(code: String?) = when (code) { "veh" -> "🚗"; "house" -> "🏠"; "finance" -> "💰"; "hr" -> "👤"; else -> "📋" }

/** حفظ بيانات الدخول واللغة */
object Prefs {
    private const val F = "soot_emp"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(F, Context.MODE_PRIVATE)
    fun save(ctx: Context, iqama: String, pin: String) = sp(ctx).edit().putString("iqama", iqama).putString("pin", pin).apply()
    fun iqama(ctx: Context) = sp(ctx).getString("iqama", null)
    fun pin(ctx: Context) = sp(ctx).getString("pin", null)
    fun clearCreds(ctx: Context) = sp(ctx).edit().remove("iqama").remove("pin").apply()
    fun lang(ctx: Context): Lang = runCatching { Lang.valueOf(sp(ctx).getString("lang", "AR") ?: "AR") }.getOrDefault(Lang.AR)
    fun setLang(ctx: Context, l: Lang) = sp(ctx).edit().putString("lang", l.name).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = this
        val scheme = lightColorScheme(primary = Green, onPrimary = Color.White, secondary = Gold, background = Bg, surface = Color.White)
        setContent {
            var lang by remember { mutableStateOf(Prefs.lang(ctx)) }
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides (if (lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr),
                    LocalL provides STR[lang]!!,
                    LocalLang provides lang,
                    LocalSetLang provides { nl -> Prefs.setLang(ctx, nl); lang = nl }
                ) { AppRoot() }
            }
        }
    }
}

@Composable
fun LangButton(tint: Color = Color.White) {
    var open by remember { mutableStateOf(false) }
    val setLang = LocalSetLang.current
    Box {
        TextButton(onClick = { open = true }) { Text("🌐 ${LocalLang.current.label}", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Lang.values().forEach { l ->
                DropdownMenuItem(text = { Text(l.label) }, onClick = { setLang(l); open = false })
            }
        }
    }
}

@Composable
fun AppRoot() {
    var session by remember { mutableStateOf<Session?>(null) }
    var detail by remember { mutableStateOf<Complaint?>(null) }
    var newFlow by remember { mutableStateOf(false) }
    var updateFlow by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var booting by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val lang = LocalLang.current

    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var updateDone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tTitle = when (lang) { Lang.EN -> "App update"; Lang.UR -> "ایپ اپ ڈیٹ"; else -> "تحديث التطبيق" }
    fun tDownloading(v: String) = when (lang) {
        Lang.EN -> "New version ($v) — downloading…"; Lang.UR -> "نیا ورژن ($v) — ڈاؤن لوڈ ہو رہا ہے…"
        else -> "تحديث جديد ($v) — جارٍ التنزيل…" }
    val tReady = when (lang) { Lang.EN -> "Ready — finish from the install screen"; Lang.UR -> "تیار — انسٹال اسکرین سے مکمل کریں"; else -> "جاهز للتثبيت — أكمل من شاشة التثبيت" }
    val tFail = when (lang) { Lang.EN -> "Download failed"; Lang.UR -> "ڈاؤن لوڈ ناکام"; else -> "تعذّر التنزيل" }
    val tRetry = when (lang) { Lang.EN -> "Retry"; Lang.UR -> "دوبارہ کوشش"; else -> "إعادة المحاولة" }
    val tLater = when (lang) { Lang.EN -> "Later"; Lang.UR -> "بعد میں"; else -> "لاحقًا" }

    fun runUpdate(info: UpdateInfo) {
        scope.launch {
            update = info; updateDone = false
            updateMsg = tDownloading(info.versionName)
            try { Updater.downloadAndInstall(context, info); updateMsg = tReady; updateDone = true }
            catch (e: Exception) { updateMsg = e.message ?: tFail; updateDone = true }
        }
    }

    LaunchedEffect(Unit) {
        val iq = Prefs.iqama(context); val pn = Prefs.pin(context)
        if (!iq.isNullOrBlank() && !pn.isNullOrBlank()) {
            try { session = Api.login(iq, pn) } catch (_: Exception) { Prefs.clearCreds(context) }
        }
        booting = false
    }

    // فحص التحديث الذاتي عند الفتح
    LaunchedEffect(Unit) {
        val info = Updater.check(context)
        if (info != null) runUpdate(info)
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        val s = session
        when {
            booting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) }
            s == null -> LoginScreen(onLogin = { sess, iq, pn -> Prefs.save(context, iq, pn); session = sess })
            newFlow -> NewComplaintFlow(s, onDone = { newFlow = false; reload++ }, onCancel = { newFlow = false })
            updateFlow -> DataUpdateScreen(s) { updateFlow = false }
            detail != null -> DetailScreen(s, detail!!, onChanged = { reload++ }) { detail = null }
            else -> HomeScreen(s, reload, onOpen = { detail = it }, onNew = { newFlow = true }, onUpdateData = { updateFlow = true },
                onLogout = { Prefs.clearCreds(context); session = null; detail = null })
        }
    }

    update?.let { info ->
        AlertDialog(
            onDismissRequest = { if (updateDone) update = null },
            icon = { Text("⬇️", fontSize = 28.sp) },
            title = { Text(tTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(updateMsg ?: "", fontSize = 14.sp)
                    if (!updateDone) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green) }
                    if (info.notes.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(info.notes, fontSize = 12.sp, color = Gray) }
                }
            },
            confirmButton = { if (updateDone) TextButton(onClick = { runUpdate(info) }) { Text(tRetry, color = Green, fontWeight = FontWeight.Bold) } },
            dismissButton = { if (updateDone) TextButton(onClick = { update = null }) { Text(tLater, color = Gray) } }
        )
    }
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Surface(color = Green, shadowElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) { TextButton(onClick = onBack) { Text(if (LocalLang.current.rtl) "→" else "←", color = Color.White, fontSize = 20.sp) }; Spacer(Modifier.width(4.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
            }
            if (action != null) action()
        }
    }
}

@Composable
fun LoginScreen(onLogin: (Session, String, String) -> Unit) {
    val L = LocalL.current
    var iqama by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Bg).padding(24.dp).statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { LangButton(tint = GreenD) }
        Spacer(Modifier.height(4.dp))
        Surface(color = Green, shape = RoundedCornerShape(20.dp)) {
            Text("صوت", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(L.empApp, color = GreenD, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(L.loginSub, color = Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(iqama, { iqama = it.filter { c -> c.isDigit() } }, label = { Text(L.iqamaLabel) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(pin, { pin = it }, label = { Text(L.pinLabel) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(L.firstHint, color = Gray, fontSize = 11.sp, textAlign = TextAlign.Center)
        if (error != null) { Spacer(Modifier.height(10.dp)); Text(error!!, color = Red, fontSize = 13.sp, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = {
            error = null; loading = true
            scope.launch { try { val iq = iqama.trim(); val pn = pin.trim(); onLogin(Api.login(iq, pn), iq, pn) } catch (e: Exception) { error = e.message ?: L.loginFail; loading = false } }
        }, enabled = !loading && iqama.isNotBlank() && pin.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(L.loginBtn, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun HomeScreen(session: Session, reload: Int, onOpen: (Complaint) -> Unit, onNew: () -> Unit, onUpdateData: () -> Unit, onLogout: () -> Unit) {
    val L = LocalL.current
    val lang = LocalLang.current
    var list by remember { mutableStateOf<List<Complaint>>(emptyList()) }
    var vehicles by remember { mutableStateOf<List<MyVehicle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(reload) {
        loading = true; error = null
        try { list = Api.myComplaints(session.token) } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    LaunchedEffect(Unit) { try { vehicles = Api.myVehicles(session.token) } catch (_: Exception) {} }
    Scaffold(containerColor = Bg,
        topBar = { TopBar("${L.hi} ${session.name}", session.projectCode?.let { "${L.project} $it" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LangButton()
                TextButton(onClick = onLogout) { Text(L.logout, color = Color.White, fontSize = 13.sp) }
            }
        } },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNew, containerColor = Gold, contentColor = Color.White) { Text(L.newComplaint, fontWeight = FontWeight.Bold) } }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                error != null -> Text("${L.loadFail}: $error", Modifier.align(Alignment.Center).padding(24.dp), color = Red)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { ProfileCard(session, vehicles) }
                    item {
                        OutlinedButton(onClick = onUpdateData, modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)) {
                            Text("✎  " + when (lang) { Lang.EN -> "Update my data"; Lang.UR -> "میرا ڈیٹا اپ ڈیٹ کریں"; else -> "تحديث بياناتي" },
                                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GreenD)
                        }
                    }
                    val pending = list.count { it.closePending }
                    if (pending > 0) item {
                        Surface(color = Amber.copy(alpha = .14f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("⏳ " + (if (LocalLang.current == Lang.EN) "$pending complaint(s) awaiting your confirmation" else if (LocalLang.current == Lang.UR) "$pending شکایت آپ کی تصدیق کے منتظر" else "لديك $pending شكوى بانتظار تأكيدك"),
                                color = Color(0xFF9A6B10), fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                        }
                    }
                    item { Text("${L.myComplaints} (${list.size})", fontWeight = FontWeight.Bold, color = GreenD, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp)) }
                    if (list.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(L.noComplaints, color = Gray); Text(L.noComplaintsSub, color = Gray, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    }
                    items(list) { c -> ComplaintCard(c) { onOpen(c) } }
                    item { Spacer(Modifier.height(70.dp)) }
                }
            }
        }
    }
}

@Composable
fun ProfileCard(session: Session, vehicles: List<MyVehicle>) {
    val L = LocalL.current
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(Green.copy(alpha = .12f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text("👤", fontSize = 26.sp) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GreenD)
                    Spacer(Modifier.height(3.dp))
                    Text("🆔 ${L.iqama}: ${session.iqama}", color = Gray, fontSize = 12.sp)
                    Text("📁 ${L.project}: ${session.projectCode ?: "—"}", color = Gray, fontSize = 12.sp)
                }
            }
            if (vehicles.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Surface(color = Green.copy(alpha = .06f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(L.assignedVehicles, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = GreenD)
                        Spacer(Modifier.height(4.dp))
                        vehicles.forEach { v ->
                            Text("• ${v.plate}" + listOfNotNull(v.make, v.model).joinToString(" ").let { if (it.isNotBlank()) " — $it" else "" }
                                + (v.project?.let { " · $it" } ?: ""), color = Color(0xFF223229), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ComplaintCard(c: Complaint, onClick: () -> Unit) {
    val L = LocalL.current
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(5.dp).height(92.dp).background(prioColor(c.priority)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (c.closePending) StatusBadge(L.confirmClose, Amber) else StatusBadge(statusLabel(c.status, L), statusColor(c.status))
                }
                Spacer(Modifier.height(8.dp))
                Row { Chip("${deptIcon(c.departmentCode)} ${deptName(c.departmentCode, L)}", Blue); Spacer(Modifier.width(6.dp)); Chip("📁 ${c.projectCode ?: "—"}", Green) }
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
    val L = LocalL.current
    var depts by remember { mutableStateOf<List<Department>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var dept by remember { mutableStateOf<Department?>(null) }
    var cat by remember { mutableStateOf<Category?>(null) }

    LaunchedEffect(Unit) {
        try { depts = Api.departments(session.token) } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    BackHandler { when { cat != null -> cat = null; dept != null -> dept = null; else -> onCancel() } }

    val title = when {
        cat != null -> "${deptName(dept?.code, L)} · ${catName(cat?.code, cat?.nameAr ?: "", L)}"
        dept != null -> deptName(dept?.code, L)
        else -> L.chooseDept
    }
    Scaffold(containerColor = Bg, topBar = {
        TopBar(title, L.newComplaintT, onBack = { when { cat != null -> cat = null; dept != null -> dept = null; else -> onCancel() } })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Green)
                error != null -> Text("${L.loadFail}: $error", Modifier.align(Alignment.Center).padding(24.dp), color = Red)
                cat != null -> ComplaintForm(session, dept!!, cat!!, onDone)
                dept != null -> PickList(L.chooseCat, dept!!.categories.map { catName(it.code, it.nameAr, L) to it }) { cat = it }
                else -> PickGrid(depts) { dept = it }
            }
        }
    }
}

@Composable
fun PickGrid(depts: List<Department>, onPick: (Department) -> Unit) {
    val L = LocalL.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(L.chooseDeptHint, color = Gray, fontSize = 13.sp) }
        items(depts) { d ->
            Card(onClick = { onPick(d) }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).background(Green.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text(deptIcon(d.code), fontSize = 24.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(deptName(d.code, L), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(d.categories.joinToString(" · ") { catName(it.code, it.nameAr, L) }, color = Gray, fontSize = 11.sp)
                    }
                    Text(if (LocalLang.current.rtl) "‹" else "›", color = Gray, fontSize = 22.sp)
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
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(if (LocalLang.current.rtl) "‹" else "›", color = Gray, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
fun ComplaintForm(session: Session, dept: Department, cat: Category, onDone: () -> Unit) {
    val L = LocalL.current
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
    var vehicles by remember { mutableStateOf<List<MyVehicle>>(emptyList()) }
    var vehExpanded by remember { mutableStateOf(false) }
    var manualPlate by remember { mutableStateOf(false) }
    var housing by remember { mutableStateOf<Housing?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lang = LocalLang.current

    LaunchedEffect(cat.needsPlate) {
        if (cat.needsPlate) {
            try { val vs = Api.myVehicles(session.token); vehicles = vs; if (plate.isBlank() && vs.isNotEmpty()) plate = vs[0].plate } catch (_: Exception) {}
        }
    }
    LaunchedEffect(dept.code) {
        if (dept.code == "house") {
            try { housing = Api.myHousing(session.token).firstOrNull() } catch (_: Exception) {}
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploading = true; error = null
            scope.launch {
                try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw RuntimeException(L.photoReadFail)
                    val type = context.contentResolver.getType(uri) ?: ""
                    val ext = if (type.contains("png")) "png" else "jpg"
                    photos = photos + Api.uploadPhoto(bytes, ext)
                } catch (e: Exception) { error = e.message ?: L.photoUploadFail } finally { uploading = false }
            }
        }
    }

    val amountVal = amount.toDoubleOrNull()
    val amountBad = cat.needsAmount && cat.maxAmount != null && amountVal != null && amountVal > cat.maxAmount
    val canSubmit = title.isNotBlank() && !submitting && !uploading && !amountBad && (!cat.needsAmount || amountVal != null)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("${L.dept}: ${deptName(dept.code, L)}  ·  ${L.type}: ${catName(cat.code, cat.nameAr, L)}", color = GreenD, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(title, { title = it }, label = { Text(L.titleField) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(desc, { desc = it }, label = { Text(L.detailsField) }, modifier = Modifier.fillMaxWidth().height(100.dp))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(loc, { loc = it }, label = { Text(L.locationField) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        if (dept.code == "house" && housing != null) {
            Spacer(Modifier.height(10.dp))
            Surface(color = Color(0xFFE6F6F1), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🏠", fontSize = 18.sp); Spacer(Modifier.width(9.dp))
                    Column {
                        Text(when (lang) { Lang.EN -> "Your housing"; Lang.UR -> "آپ کی رہائش"; else -> "سكنك" },
                            fontSize = 11.sp, color = Gray, fontWeight = FontWeight.Bold)
                        Text(housing!!.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GreenD)
                    }
                }
            }
        }

        if (cat.needsPlate) {
            Spacer(Modifier.height(10.dp))
            if (vehicles.isNotEmpty() && !manualPlate) {
                ExposedDropdownMenuBox(expanded = vehExpanded, onExpandedChange = { vehExpanded = !vehExpanded }) {
                    OutlinedTextField(value = plate, onValueChange = {}, readOnly = true, label = { Text(L.chooseVehicle) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = vehExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = vehExpanded, onDismissRequest = { vehExpanded = false }) {
                        vehicles.forEach { v -> DropdownMenuItem(text = { Text(v.label) }, onClick = { plate = v.plate; vehExpanded = false }) }
                    }
                }
                TextButton(onClick = { manualPlate = true; plate = "" }) { Text(L.manualPlate, fontSize = 12.sp) }
            } else {
                OutlinedTextField(plate, { plate = it }, label = { Text(L.plateField) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (vehicles.isNotEmpty()) TextButton(onClick = { manualPlate = false; plate = "" }) { Text("${L.chooseFromVehicles} (${vehicles.size})", fontSize = 12.sp) }
            }
        }
        if (cat.needsAmount) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(L.amountField + (cat.maxAmount?.let { " — ${L.maxWord} ${it.toInt()}" } ?: "")) },
                singleLine = true, isError = amountBad, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            if (amountBad) Text("${L.amountExceeds} ${cat.maxAmount?.toInt()} ${L.sar}", color = Red, fontSize = 12.sp)
        }

        // الأولوية صار يحددها المشرف — تُرسَل افتراضيًا "med"

        if (cat.needsPhoto) {
            Spacer(Modifier.height(14.dp))
            Text(L.photos, fontSize = 12.sp, color = Gray)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { picker.launch("image/*") }, enabled = !uploading, modifier = Modifier.fillMaxWidth()) {
                if (uploading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(L.uploadingTxt) }
                else Text(L.addPhoto + (if (photos.isNotEmpty()) "  (${photos.size})" else ""))
            }
        }

        if (error != null) { Spacer(Modifier.height(10.dp)); Text(error!!, color = Red, fontSize = 12.sp) }
        Spacer(Modifier.height(18.dp))
        Button(onClick = {
            error = null; submitting = true
            scope.launch {
                try { Api.raise(session.token, cat.code, priority, title.trim(), desc.trim(), loc.trim(), plate.trim(), if (cat.needsAmount) amountVal else null, photos); onDone() }
                catch (e: Exception) { error = e.message ?: L.submitFail; submitting = false }
            }
        }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
            if (submitting) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(L.submit, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
fun DataUpdateScreen(session: Session, onBack: () -> Unit) {
    val lang = LocalLang.current
    val scope = rememberCoroutineScope()
    var fieldKey by remember { mutableStateOf("phone") }
    var expanded by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler { onBack() }

    val fields = listOf(
        "phone" to (when (lang) { Lang.EN -> "Phone number"; Lang.UR -> "فون نمبر"; else -> "رقم الاتصال" }),
        "project_code" to (when (lang) { Lang.EN -> "Project"; Lang.UR -> "پروجیکٹ"; else -> "المشروع" }),
        "bank_account" to (when (lang) { Lang.EN -> "Bank account"; Lang.UR -> "بینک اکاؤنٹ"; else -> "الحساب البنكي" })
    )
    val fieldLabel = fields.first { it.first == fieldKey }.second
    val title = when (lang) { Lang.EN -> "Update my data"; Lang.UR -> "میرا ڈیٹا اپ ڈیٹ"; else -> "تحديث بياناتي" }
    val tField = when (lang) { Lang.EN -> "Field to update"; Lang.UR -> "اپ ڈیٹ فیلڈ"; else -> "الحقل المراد تحديثه" }
    val tNew = when (lang) { Lang.EN -> "New value"; Lang.UR -> "نئی قدر"; else -> "القيمة الجديدة" }
    val tSubmit = when (lang) { Lang.EN -> "Send request"; Lang.UR -> "درخواست بھیجیں"; else -> "إرسال الطلب" }
    val tHint = when (lang) { Lang.EN -> "Your supervisor reviews and approves the change in Watheeq."; Lang.UR -> "آپ کا سپروائزر وثیق میں تبدیلی کی منظوری دیتا ہے۔"; else -> "يراجع المشرف الطلب ويعتمده في وثيق." }
    val tDone = when (lang) { Lang.EN -> "Request sent to your supervisor"; Lang.UR -> "درخواست سپروائزر کو بھیج دی گئی"; else -> "تم إرسال الطلب للمشرف" }

    Scaffold(containerColor = Bg, topBar = { TopBar(title, onBack = onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            if (done) {
                Spacer(Modifier.height(40.dp))
                Text("✅", fontSize = 40.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
                Text(tDone, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = GreenD, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(when (lang) { Lang.EN -> "Done"; Lang.UR -> "ہو گیا"; else -> "تم" }, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(tField, fontSize = 12.sp, color = Gray)
                Spacer(Modifier.height(6.dp))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(value = fieldLabel, onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        fields.forEach { f -> DropdownMenuItem(text = { Text(f.second) }, onClick = { fieldKey = f.first; expanded = false }) }
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value, { value = it }, label = { Text(tNew) }, singleLine = true,
                    keyboardOptions = if (fieldKey == "phone" || fieldKey == "bank_account") KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Surface(color = Color(0xFFF1F5F9), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("ℹ  $tHint", fontSize = 11.5.sp, color = Gray, modifier = Modifier.padding(11.dp))
                }
                if (error != null) { Spacer(Modifier.height(10.dp)); Text(error!!, color = Red, fontSize = 12.sp) }
                Spacer(Modifier.height(20.dp))
                Button(onClick = {
                    error = null; submitting = true
                    scope.launch {
                        try { Api.requestUpdate(session.token, fieldKey, value.trim()); done = true }
                        catch (e: Exception) { error = e.message ?: "تعذّر الإرسال"; submitting = false }
                    }
                }, enabled = value.isNotBlank() && !submitting, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                    if (submitting) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(tSubmit, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun DetailScreen(session: Session, c: Complaint, onChanged: () -> Unit, onBack: () -> Unit) {
    val L = LocalL.current
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var events by remember { mutableStateOf<List<Event>>(emptyList()) }
    var reply by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    LaunchedEffect(refreshKey) {
        try { events = Api.events(session.token, c.id); msgs = Api.messages(session.token, c.id) } catch (_: Exception) {}
    }
    Scaffold(containerColor = Bg,
        topBar = { TopBar(c.title, "${c.code} · ${deptName(c.departmentCode, L)}", onBack = onBack) },
        bottomBar = {
            if (c.status != "done") Surface(color = Color.White, shadowElevation = 8.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(reply, { reply = it }, placeholder = { Text(L.writeMessage) }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (reply.isBlank()) return@Button
                        scope.launch { try { Api.send(session.token, c.id, reply.trim()); reply = ""; refreshKey++ } catch (_: Exception) {} }
                    }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 18.dp)) { Text(L.sendBtn) }
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (c.closePending) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E8)), shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().border(1.5.dp, Amber, RoundedCornerShape(16.dp))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(L.supResolved, fontWeight = FontWeight.Bold, color = Color(0xFF9A6B10), fontSize = 14.sp)
                        Text(L.confirmFinal, color = Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                busy = true
                                scope.launch { try { Api.confirmClose(session.token, c.id, true, ""); toast = L.closedOk; onChanged(); onBack() } catch (e: Exception) { toast = e.message; busy = false } }
                            }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Green), modifier = Modifier.weight(1f)) { Text(L.yesResolved) }
                            OutlinedButton(onClick = {
                                busy = true
                                scope.launch { try { Api.confirmClose(session.token, c.id, false, L.notResolvedNote); toast = L.reopened; onChanged(); onBack() } catch (e: Exception) { toast = e.message; busy = false } }
                            }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(L.notResolved) }
                        }
                        if (toast != null) { Spacer(Modifier.height(8.dp)); Text(toast!!, color = GreenD, fontSize = 12.sp) }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row { StatusBadge(if (c.closePending) L.awaitingYou else statusLabel(c.status, L), if (c.closePending) Amber else statusColor(c.status)); Spacer(Modifier.width(6.dp)); Chip(prioLabel(c.priority, L), prioColor(c.priority)) }
                        if (!c.description.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(c.description, fontSize = 13.sp) }
                        Spacer(Modifier.height(6.dp))
                        Text("${L.respDept}: ${deptName(c.departmentCode, L)}", color = Gray, fontSize = 12.sp)
                        if (!c.plate.isNullOrBlank()) Text("${L.plateLabel}: ${c.plate}", color = Gray, fontSize = 12.sp)
                        if (c.amount != null) Text("${L.amountLabel}: ${c.amount.toInt()} ${L.sar}", color = Gray, fontSize = 12.sp)
                        if (!c.location.isNullOrBlank()) Text("${L.locationLabel}: ${c.location}", color = Gray, fontSize = 12.sp)
                        if (c.photos.isNotEmpty()) Text("${L.photosAttached} ${c.photos.size}", color = Blue, fontSize = 12.sp)
                    }
                }
            }
            item { Text(L.route, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD) }
            items(events) { e -> TimelineRow(e) }
            item { Text(L.conversation, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = GreenD) }
            if (msgs.isEmpty()) item { Text(L.noMessages, color = Gray, fontSize = 12.sp) }
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
    val L = LocalL.current
    val me = m.authorKind == "employee"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start) {
        Surface(color = if (me) Color(0xFFE9F5EE) else Color.White, shape = RoundedCornerShape(14.dp), shadowElevation = 1.dp, modifier = Modifier.widthIn(max = 280.dp)) {
            Column(Modifier.padding(10.dp)) {
                Text(m.authorName ?: (if (me) L.meWord else L.supervisor), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (me) Green else Blue)
                Text(m.body, fontSize = 13.sp)
            }
        }
    }
}
