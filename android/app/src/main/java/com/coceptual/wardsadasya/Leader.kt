package com.coceptual.wardsadasya

import android.Manifest
import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ================================================================ Leader app: DPCC President, General Secretary,
// District Presidents and Assembly Observers. One app; the login number decides which of these opens.

private const val PRESIDENT_NAME = "माननीय दिल्ली प्रदेश कांग्रेस कमेटी अध्यक्ष देवेन्द्र यादव जी"
private fun JSONArray?.list(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
private val GRADE_COLOR = mapOf("A" to Color(0xFF1D8A4B), "B" to Color(0xFF0F7D8C), "C" to Color(0xFFC48612), "D" to Color(0xFFC03E2A))
private val STATUS = mapOf("active" to "एक्टिव", "slow" to "धीमे", "inactive" to "बंद", "never" to "लॉगिन नहीं")
private val STATUS_COLOR = mapOf("active" to Color(0xFF1D8A4B), "slow" to Color(0xFFC48612), "inactive" to Color(0xFFC03E2A), "never" to Color(0xFF6B7590))
private val WARD_COLOR = mapOf("strong" to Color(0xFF1D8A4B), "ok" to Color(0xFF58A97A), "weak" to Color(0xFFD9A63A), "none" to Color(0xFFC03E2A))
private val WARD_LABEL = mapOf("strong" to "मज़बूत", "ok" to "ठीक", "weak" to "कमज़ोर", "none" to "कोई एक्टिव नहीं")
private val LEVEL_HI = mapOf("gs" to "महासचिव", "dp" to "ज़िला अध्यक्ष", "observer" to "विधानसभा ऑब्ज़र्वर")

/** Screens opened on top of the tabs. */
private data class Page(val kind: String, val arg: String? = null)

private class LeaderCtx(
    val role: String,
    val me: JSONObject,
    val ov: JSONObject,
    val open: (Page) -> Unit,
    val call: (Callee) -> Unit,
    val message: (Callee, String) -> Unit,
    val personal: Pair<Callee, String>?,
    val reload: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LeaderApp(role: String, onLogout: () -> Unit, onSwitch: (() -> Unit)? = null, openInbox: MutableState<Boolean> = mutableStateOf(false)) {
    val ctx = LocalContext.current
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var ov by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val stack = remember { mutableStateListOf<Page>() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var personal by remember { mutableStateOf<Pair<Callee, String>?>(null) }
    var lastBack by remember { mutableLongStateOf(0L) }

    LaunchedEffect(reloadKey) {
        loading = true; error = null
        try {
            me = Api.leader(ctx, "me")
            ov = Api.leader(ctx, "overview")
        } catch (e: ApiError) {
            if (e.code == "unauthorized") onLogout() else error = "डेटा लोड नहीं हुआ (${e.code})"
        } catch (e: Exception) { error = "इंटरनेट चेक करें।" }
        loading = false
    }
    // The President just calls; he is not asked how the call went.
    val caller = rememberCaller(askResult = role != "president")
    LaunchedEffect(openInbox.value) { if (openInbox.value && role != "president") { stack.add(Page("inbox")); openInbox.value = false } }

    BackHandler {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            stack.isNotEmpty() -> { if (stack.last().kind == "inbox") reloadKey++; stack.removeAt(stack.lastIndex) }
            tab != 0 -> tab = 0
            System.currentTimeMillis() - lastBack < 2000 -> (ctx as? Activity)?.finish()
            else -> { lastBack = System.currentTimeMillis(); Toast.makeText(ctx, "ऐप बंद करने के लिए फिर से वापस दबाएं", Toast.LENGTH_SHORT).show() }
        }
    }

    val m = me; val o = ov
    if (m == null || o == null) {
        Box(Modifier.fillMaxSize().background(Ground), contentAlignment = Alignment.Center) {
            if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = OrangeText)
                TextButton(onClick = { reloadKey++ }) { Text("फिर कोशिश करें") }
                TextButton(onClick = onLogout) { Text("लॉगआउट", color = Muted) }
            } else CircularProgressIndicator(color = Orange)
        }
        return
    }
    val leader = m.optJSONObject("leader") ?: JSONObject()
    val lc = LeaderCtx(role, leader, o, open = { stack.add(it) }, call = caller,
        message = { c, preset -> personal = c to preset; stack.add(Page("personal")) }, personal = personal, reload = { reloadKey++ })

    val top = stack.lastOrNull()
    if (top != null) {
        LeaderPage(top, lc, onBack = { if (top.kind == "inbox") reloadKey++; stack.removeAt(stack.lastIndex) }, onLogout = onLogout)
        return
    }

    val tabs = when (role) {
        "president" -> listOf(Icons.Filled.Home to "होम", Icons.Filled.Leaderboard to "प्रदर्शन", Icons.Filled.Map to "ज़िले", Icons.Filled.Star to "ऑब्ज़र्वर", Icons.Filled.GridView to "वार्ड")
        "gs" -> listOf(Icons.Filled.Home to "होम", Icons.Filled.Map to "ज़िला अध्यक्ष", Icons.Filled.Star to "ऑब्ज़र्वर", Icons.Filled.Groups to "सदस्य", Icons.Filled.TaskAlt to "टास्क")
        "dp" -> listOf(Icons.Filled.Home to "होम", Icons.Filled.TaskAlt to "टास्क", Icons.Filled.Star to "ऑब्ज़र्वर", Icons.Filled.Groups to "सदस्य", Icons.Filled.Mail to "मैसेज")
        else -> listOf(Icons.Filled.Home to "होम", Icons.Filled.TaskAlt to "टास्क", Icons.Filled.EditNote to "रिपोर्ट", Icons.Filled.Groups to "सदस्य", Icons.Filled.Mail to "मैसेज")
    }
    val menu: List<Triple<ImageVector, String, () -> Unit>> = buildList {
        when (role) {
            "president" -> {
                add(Triple(Icons.Filled.AssignmentInd, "टास्क असाइन करें") { stack.add(Page("task_create")) })
                add(Triple(Icons.Filled.Assessment, "टास्क रिपोर्ट और मंज़ूरी") { stack.add(Page("tasks_report")) })
                add(Triple(Icons.Filled.Campaign, "मैसेज या वॉइस नोट भेजें") { stack.add(Page("compose")) })
                add(Triple(Icons.Filled.RecordVoiceOver, "लोगों के संदेश (आपके लिए)") { stack.add(Page("president_inbox")) })
                add(Triple(Icons.Filled.EditNote, "फ़ील्ड रिपोर्ट") { stack.add(Page("reports")) })
                add(Triple(Icons.Filled.Call, "मेरी कॉल") { stack.add(Page("calls")) })
            }
            "gs" -> {
                add(Triple(Icons.Filled.Campaign, "मैसेज या वॉइस नोट भेजें") { stack.add(Page("compose")) })
                add(Triple(Icons.Filled.AddTask, "नया टास्क बनाएं") { stack.add(Page("task_create")) })
                add(Triple(Icons.Filled.Assessment, "टास्क रिपोर्ट") { stack.add(Page("tasks_report")) })
                add(Triple(Icons.Filled.EditNote, "फ़ील्ड रिपोर्ट") { stack.add(Page("reports")) })
                add(Triple(Icons.Filled.Call, "मेरी कॉल") { stack.add(Page("calls")) })
            }
            "dp" -> {
                add(Triple(Icons.Filled.Campaign, "मैसेज या वॉइस नोट भेजें") { stack.add(Page("compose")) })
                add(Triple(Icons.Filled.AddTask, "ऑब्ज़र्वर को टास्क दें") { stack.add(Page("task_create")) })
                add(Triple(Icons.Filled.Lightbulb, "नया टास्क सुझाएं") { stack.add(Page("task_create", "suggest")) })
                add(Triple(Icons.Filled.EditNote, "फ़ील्ड रिपोर्ट") { stack.add(Page("reports")) })
                add(Triple(Icons.Filled.Call, "मेरी कॉल") { stack.add(Page("calls")) })
            }
            else -> {
                add(Triple(Icons.Filled.EditNote, "नई विज़िट रिपोर्ट") { stack.add(Page("report_new")) })
                add(Triple(Icons.Filled.Campaign, "मैसेज या वॉइस नोट भेजें") { stack.add(Page("compose")) })
                add(Triple(Icons.Filled.Lightbulb, "नया टास्क सुझाएं") { stack.add(Page("task_create", "suggest")) })
                add(Triple(Icons.Filled.RecordVoiceOver, "अध्यक्ष जी को संदेश") { stack.add(Page("to_president")) })
                add(Triple(Icons.Filled.Call, "मेरी कॉल") { stack.add(Page("calls")) })
            }
        }
        add(Triple(Icons.Filled.Outbox, "भेजे गए मैसेज") { stack.add(Page("sent")) })
        // The President sends; nobody above him writes to his inbox. What people send him is under "लोगों के संदेश".
        if (role != "president") add(Triple(Icons.Filled.MarkEmailUnread, "मेरा इनबॉक्स") { stack.add(Page("inbox")) })
        if (onSwitch != null) add(Triple(Icons.Filled.SwitchAccount, "वार्ड सदस्य प्रोफ़ाइल खोलें") { onSwitch() })
        add(Triple(Icons.Filled.Person, "मेरी प्रोफ़ाइल") { stack.add(Page("profile")) })
        add(Triple(Icons.AutoMirrored.Filled.Logout, "लॉगआउट") { onLogout() })
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            SideMenu(
                name = leader.optString("name"), sub = ROLE_HI[role] ?: role,
                items = menu.map { (ic, label, act) ->
                    MenuItem(ic, label, badge = if (label == "मेरा इनबॉक्स") m.optInt("unread_inbox") else 0, danger = label == "लॉगआउट", onClick = act)
                },
                close = { scope.launch { drawer.close() } },
            )
        },
    ) {
    Scaffold(
        containerColor = Ground,
        topBar = {
            Row(Modifier.fillMaxWidth().background(Navy).padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scope.launch { drawer.open() } }) {
                    BadgedBox(badge = { if (m.optInt("unread_inbox") > 0) Badge(containerColor = Color(0xFFE5402F)) }) {
                        Icon(Icons.Filled.Menu, "मेन्यू", tint = Color.White)
                    }
                }
                Spacer(Modifier.width(4.dp))
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.logo), contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Color.White),
                )
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(ROLE_HI[role] ?: role, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "${leader.optString("name")}" + when (role) {
                            "dp" -> " · ${leader.optString("district")}"
                            "observer" -> " · ${leader.optString("assembly")}"
                            else -> " · पूरी दिल्ली"
                        },
                        color = Color(0xFFAFC0E3), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { reloadKey++ }) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, "रीफ्रेश", tint = Color.White)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.border(BorderStroke(1.dp, Line))) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Orange, selectedTextColor = Navy, indicatorColor = OrangeBg,
                    unselectedIconColor = Color(0xFF7C86A0), unselectedTextColor = Color(0xFF7C86A0),
                )
                tabs.forEachIndexed { i, (ic, label) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, colors = colors,
                        icon = { Icon(ic, null) }, label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) })
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (role) {
                "president" -> when (tab) { 0 -> PresidentHome(lc); 1 -> PerformanceTab(lc); 2 -> DistrictsTab(lc); 3 -> ObserversTab(lc, weakFirst = false); else -> WardsTab(lc) }
                "gs" -> when (tab) { 0 -> GsHome(lc); 1 -> DpsTab(lc); 2 -> ObserversTab(lc, weakFirst = true); 3 -> MembersTab(lc, withDistrict = true); else -> MyTasksTab(lc) }
                "dp" -> when (tab) { 0 -> DpHome(lc); 1 -> MyTasksTab(lc); 2 -> ObserversTab(lc, weakFirst = false); 3 -> MembersTab(lc, withDistrict = false); else -> MessagesTab(lc) }
                else -> when (tab) { 0 -> ObserverHome(lc); 1 -> MyTasksTab(lc); 2 -> ReportsTab(lc); 3 -> RatingsTab(lc); else -> MessagesTab(lc) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    }
}

// ================================================================ Building blocks

@Composable
private fun LCard(title: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null || action != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title ?: "", fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

/** Score with its grade. White text on a dark colour so it reads on every grade. */
@Composable
private fun GradeBadge(score: Int, grade: String, big: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(GRADE_COLOR[grade] ?: Muted).padding(horizontal = if (big) 10.dp else 7.dp, vertical = if (big) 4.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$score", color = Color.White, fontSize = if (big) 16.sp else 12.sp, fontWeight = FontWeight.ExtraBold)
        Text(" $grade", color = Color.White, fontSize = if (big) 13.sp else 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusChip(s: String) {
    Text(STATUS[s] ?: s, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(STATUS_COLOR[s] ?: Muted).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun CallButton(c: Callee?, lc: LeaderCtx) {
    if (c == null || c.mobile.isBlank()) return
    Box(Modifier.size(36.dp).clip(CircleShape).background(GreenBg).clickable { lc.call(c) }, contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Call, "कॉल करें", tint = Green, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MsgButton(c: Callee?, lc: LeaderCtx, preset: String = "") {
    if (c == null || lc.role != "president") return
    Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFE8F0FE)).clickable { lc.message(c, preset) }, contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Mail, "मैसेज", tint = Blue, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun RowItem(
    title: String, sub: String, rank: Int? = null, onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rank?.let { Text("$it", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Muted, modifier = Modifier.width(26.dp), textAlign = TextAlign.Center) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 12.5.sp, color = Muted, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun Kpis(items: List<Pair<String, String>>) {
    items.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (v, l) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text(v, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Navy)
                    Text(l, fontSize = 12.sp, color = Muted)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Bar(label: String, value: Float, right: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = Ink, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(6.dp)),
            color = if (value >= 0.6f) Color(0xFF1D8A4B) else if (value >= 0.4f) Color(0xFFC48612) else Color(0xFFC03E2A), trackColor = Color(0xFFEDF0F5),
        )
        Text("  $right", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Ink)
    }
}

/** Simple dropdown. */
@Composable
private fun Picker(label: String, value: String, options: List<Pair<String, String>>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(12.dp))
                .clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = Muted)
                Text(options.firstOrNull { it.first == value }?.second ?: value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
            }
            Icon(Icons.Filled.ArrowDropDown, null, tint = Muted)
        }
    }
    if (open) PickerDialog(label, value, options, onDismiss = { open = false }) { open = false; onPick(it) }
}

private fun JSONObject.dpCallee(): Callee? = optJSONObject("dp")?.let { Callee(it.optString("mobile"), it.optString("name"), "ज़िला अध्यक्ष, ${optString("name")}") }
private fun JSONObject.obsCallee(): Callee? = optJSONObject("observer")?.let { Callee(it.optString("mobile"), it.optString("name"), "ऑब्ज़र्वर, ${optString("name")}") }
private fun JSONObject.memberCallee() = Callee(optString("mobile"), optString("name"), "वार्ड ${optInt("ward_no")}")

@Composable
private fun DistrictRow(d: JSONObject, lc: LeaderCtx, preset: String = "") {
    val dp = d.optJSONObject("dp")
    RowItem(
        d.optString("name"),
        "${dp?.optString("name") ?: "ज़िला अध्यक्ष नहीं जुड़े"}${dp?.let { " · ${it.optString("mobile")}" } ?: ""} · एक्टिव ${d.optInt("active")}/${d.optInt("total")} · ${if (d.optInt("growth") >= 0) "+" else ""}${d.optInt("growth")}%",
        rank = d.optInt("rank"), onClick = { lc.open(Page("district", d.optString("name"))) },
    ) { GradeBadge(d.optInt("score"), d.optString("grade")); MsgButton(d.dpCallee(), lc, preset); CallButton(d.dpCallee(), lc) }
}

@Composable
private fun ObserverRow(a: JSONObject, lc: LeaderCtx, preset: String = "") {
    val o = a.optJSONObject("observer")
    val last = if (a.isNull("last_report_days")) "कोई रिपोर्ट नहीं" else "रिपोर्ट ${a.optInt("last_report_days")} दिन पहले"
    RowItem(
        o?.optString("name") ?: "ऑब्ज़र्वर नहीं जुड़े",
        "${a.optString("name")} · ${a.optString("district")} · ${a.optInt("active")}/${a.optInt("total")} एक्टिव · $last",
        rank = a.optInt("rank"), onClick = { lc.open(Page("assembly", a.optString("name"))) },
    ) { GradeBadge(a.optInt("score"), a.optString("grade")); MsgButton(a.obsCallee(), lc, preset); CallButton(a.obsCallee(), lc) }
}

@Composable
private fun MemberRow(mb: JSONObject, lc: LeaderCtx, rank: Int? = null) {
    val days = if (mb.isNull("last_active_days")) "कभी लॉगिन नहीं" else if (mb.optInt("last_active_days") == 0) "आज एक्टिव" else "${mb.optInt("last_active_days")} दिन पहले"
    RowItem(mb.optString("name"), "वार्ड ${mb.optInt("ward_no")} · ${fmtNum(mb.optInt("points"))} अंक · $days", rank = rank,
        onClick = { lc.open(Page("member", mb.optString("mobile"))) }) {
        StatusChip(mb.optString("status")); MsgButton(mb.memberCallee(), lc); CallButton(mb.memberCallee(), lc)
    }
}

// ================================================================ President

@Composable
private fun PresidentHome(lc: LeaderCtx) {
    val ov = lc.ov
    val t = ov.optJSONObject("totals") ?: JSONObject()
    val districts = ov.optJSONArray("districts").list()
    LCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("दिल्ली स्कोर", fontSize = 13.sp, color = Muted)
                Text("${districts.size} ज़िले · ${ov.optJSONArray("assemblies").list().size} विधानसभा · ${ov.optJSONArray("wards").list().size} वार्ड", fontSize = 12.sp, color = Muted)
            }
            GradeBadge(ov.optInt("delhi_score"), ov.optString("delhi_grade"), big = true)
        }
    }
    Kpis(listOf(
        fmtNum(t.optInt("members")) to "कुल सदस्य", fmtNum(t.optInt("logged")) to "ऐप में लॉगिन",
        fmtNum(t.optInt("active_today")) to "आज एक्टिव", fmtNum(t.optInt("uploads_week")) to "इस हफ़्ते फोटो/वीडियो/शेयर",
    ))
    LCard("ऐप कितना चल रहा है") {
        val total = t.optInt("members").coerceAtLeast(1).toFloat()
        Bar("लॉगिन किया", t.optInt("logged") / total, fmtNum(t.optInt("logged")))
        Bar("इस हफ़्ते एक्टिव", t.optInt("active_week") / total, fmtNum(t.optInt("active_week")))
        Bar("आज एक्टिव", t.optInt("active_today") / total, fmtNum(t.optInt("active_today")))
    }
    LCard("ध्यान दें") {
        Text("${t.optInt("wards_none")} वार्ड में कोई एक्टिव सदस्य नहीं", fontSize = 14.sp, color = Color(0xFF9B1C1C), fontWeight = FontWeight.SemiBold)
        Text("${t.optInt("observers_no_report")} ऑब्ज़र्वर ने 7 दिन से फ़ील्ड रिपोर्ट नहीं भेजी", fontSize = 14.sp, color = OrangeText, fontWeight = FontWeight.SemiBold)
    }
    TaskProgressCard(lc)
    LCard("सबसे आगे 5 संगठनात्मक ज़िले") {
        districts.take(5).forEach { DistrictRow(it, lc, "बधाई! आपके ज़िले का काम बहुत अच्छा चल रहा है। ऐसे ही आगे बढ़ते रहें।") }
        Text("बधाई संदेश भेजने के लिए मैसेज का बटन दबाएं।", fontSize = 12.sp, color = Muted)
    }
    LCard("सबसे पीछे 5 संगठनात्मक ज़िले") {
        districts.takeLast(5).reversed().forEach { DistrictRow(it, lc, "आपके ज़िले में और मेहनत की ज़रूरत है। मुझे भरोसा है आप इसे आगे ले जाएंगे।") }
        Text("प्रेरणा संदेश भेजने के लिए मैसेज का बटन दबाएं।", fontSize = 12.sp, color = Muted)
    }
    lc.ov.optJSONObject("gs")?.let { gs ->
        LCard("महासचिव") {
            val c = Callee(gs.optString("mobile"), gs.optString("name"), "महासचिव")
            RowItem(gs.optString("name"), gs.optString("mobile")) { MsgButton(c, lc); CallButton(c, lc) }
        }
    }
    Button(onClick = { lc.open(Page("compose")) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
        Icon(Icons.Filled.Campaign, null); Text("  सबको मैसेज या वॉइस नोट भेजें", fontWeight = FontWeight.Bold)
    }
}

/** Performance review: districts, assemblies or wards; top or bottom N. */
@Composable
private fun PerformanceTab(lc: LeaderCtx) {
    var level by remember { mutableStateOf("district") }
    var range by remember { mutableStateOf("top5") }
    val ranges = when (level) {
        "district" -> listOf("top5" to "टॉप 5", "bottom5" to "नीचे के 5", "all" to "सभी ज़िले")
        "assembly" -> listOf("top5" to "टॉप 5", "top10" to "टॉप 10", "top20" to "टॉप 20", "bottom5" to "नीचे के 5", "bottom10" to "नीचे के 10", "bottom20" to "नीचे के 20", "all" to "सभी विधानसभा")
        else -> listOf("top10" to "टॉप 10", "top20" to "टॉप 20", "top50" to "टॉप 50", "bottom10" to "नीचे के 10", "bottom20" to "नीचे के 20", "bottom25" to "नीचे के 25", "bottom50" to "नीचे के 50", "bottom75" to "नीचे के 75", "all" to "सभी वार्ड (रैंकिंग)")
    }
    val rangeNow = if (ranges.any { it.first == range }) range else ranges.first().first
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("स्तर", level, listOf("district" to "संगठनात्मक ज़िले", "assembly" to "विधानसभा", "ward" to "सभी वार्ड"), Modifier.weight(1f)) { level = it }
        Picker("रैंकिंग", rangeNow, ranges, Modifier.weight(1f)) { range = it }
    }
    fun <T> cut(xs: List<T>): List<T> {
        val n = rangeNow.filter { it.isDigit() }.toIntOrNull() ?: xs.size
        return if (rangeNow.startsWith("bottom")) xs.takeLast(n).reversed() else xs.take(n)
    }
    val title = ranges.first { it.first == rangeNow }.second
    when (level) {
        "district" -> LCard(title) { cut(lc.ov.optJSONArray("districts").list()).forEach { DistrictRow(it, lc) } }
        "assembly" -> LCard(title) { cut(lc.ov.optJSONArray("assemblies").list()).forEach { ObserverRow(it, lc) } }
        else -> LCard(title) {
            cut(lc.ov.optJSONArray("wards").list()).forEach { w ->
                RowItem("वार्ड ${w.optInt("no")}${w.str("name")?.let { " · $it" } ?: ""}",
                    "${w.optString("assembly")} · ${w.optInt("active")}/${w.optInt("total")} एक्टिव · ${WARD_LABEL[w.optString("level")]}",
                    rank = w.optInt("rank"), onClick = { lc.open(Page("ward", w.optInt("no").toString())) }) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(WARD_COLOR[w.optString("level")] ?: Muted))
                    Text("${w.optInt("score")}", fontWeight = FontWeight.ExtraBold, color = Ink)
                }
            }
        }
    }
}

@Composable
private fun DistrictsTab(lc: LeaderCtx) {
    LCard("संगठनात्मक ज़िलों की रैंकिंग") { lc.ov.optJSONArray("districts").list().forEach { DistrictRow(it, lc) } }
}

@Composable
private fun ObserversTab(lc: LeaderCtx, weakFirst: Boolean) {
    var dist by remember { mutableStateOf("all") }
    var list = lc.ov.optJSONArray("assemblies").list()
    val names = list.map { it.optString("district") }.filter { it.isNotBlank() }.distinct().sorted()
    if (lc.role == "president" || lc.role == "gs") Picker("संगठनात्मक ज़िला", dist, listOf("all" to "सभी ज़िले") + names.map { it to it }) { dist = it }
    if (dist != "all") list = list.filter { it.optString("district") == dist }
    if (weakFirst) list = list.reversed()
    LCard("${list.size} ऑब्ज़र्वर", action = { Text(if (weakFirst) "कमज़ोर पहले" else "अच्छे पहले", fontSize = 12.sp, color = Muted) }) {
        Text("स्कोर काम के हिसाब से अपने आप बनता है: सदस्य कितने एक्टिव, रिपोर्ट कितनी और कब।", fontSize = 12.sp, color = Muted)
        list.forEach { ObserverRow(it, lc) }
    }
}

@Composable
private fun WardsTab(lc: LeaderCtx) {
    val wards = lc.ov.optJSONArray("wards").list().sortedBy { it.optInt("no") }
    LCard("${wards.size} वार्ड", action = { Text("किसी पर टैप करें", fontSize = 12.sp, color = Muted) }) {
        wards.chunked(10).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { w ->
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(WARD_COLOR[w.optString("level")] ?: Muted)
                            .clickable { lc.open(Page("ward", w.optInt("no").toString())) },
                        contentAlignment = Alignment.Center,
                    ) { Text("${w.optInt("no")}", fontSize = 8.sp, color = Color.White) }
                }
                repeat(10 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            listOf("strong", "ok", "weak", "none").forEach { k ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(WARD_COLOR[k]!!))
                    Text(" ${WARD_LABEL[k]}", fontSize = 11.sp, color = Muted)
                }
            }
        }
    }
}

/** How far leader tasks have got, by level. */
@Composable
private fun TaskProgressCard(lc: LeaderCtx) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(Unit) { progress = try { Api.leader(ctx, "tasks").optJSONArray("progress").list() } catch (e: Exception) { emptyList() } }
    val p = progress ?: return
    if (p.isEmpty()) return
    LCard("टास्क कहाँ तक पहुँचे", action = { TextButton(onClick = { lc.open(Page("tasks_report")) }) { Text("पूरी रिपोर्ट") } }) {
        listOf("gs", "dp", "observer").forEach { lv ->
            val ts = p.filter { it.optString("level") == lv }
            val total = ts.sumOf { it.optInt("total") }; val done = ts.sumOf { it.optInt("done") }
            if (total > 0) Bar(LEVEL_HI[lv] ?: lv, done.toFloat() / total, "${done * 100 / total}%")
        }
    }
}

// ================================================================ General Secretary

@Composable
private fun GsHome(lc: LeaderCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Tasks the President gave the General Secretary come first.
    var mine by remember { mutableStateOf<List<JSONObject>?>(null) }
    var key by remember { mutableIntStateOf(0) }
    LaunchedEffect(key) { mine = try { Api.leader(ctx, "tasks").optJSONArray("mine").list() } catch (e: Exception) { emptyList() } }
    val due = mine.orEmpty().filter { !it.optBoolean("done") }
    LCard("अध्यक्ष जी के दिए टास्क", action = { Text(if (mine == null) "" else "${due.size} बाकी", fontSize = 12.sp, color = Muted) }) {
        if (mine == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else if (due.isEmpty()) Text("अभी कोई टास्क बाकी नहीं है।", color = Muted, fontSize = 13.sp)
        due.forEach { t ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.optString("title"), fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.5.sp)
                Text("$PRESIDENT_NAME की तरफ से" + (t.str("due_date")?.let { " · आख़िरी तारीख़ ${hiDate(it)}" } ?: ""), fontSize = 12.sp, color = Muted)
                t.str("description")?.let { Text(it, fontSize = 13.sp, color = Color(0xFF3A4560)) }
                t.str("audio_url")?.let { AudioPlayer(it) }
                OutlinedButton(onClick = { scope.launch { try { Api.leader(ctx, "task_done", JSONObject().put("task_id", t.optLong("id"))); key++ } catch (_: Exception) {} } },
                    shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)); Text(" हो गया") }
            }
        }
    }
    val sugg = lc.ov.optJSONArray("suggestions").list()
    listOf("observer" to "आज इन ऑब्ज़र्वर से बात करें", "member" to "आज इन वार्ड सदस्यों से बात करें").forEach { (g, title) ->
        // Older servers send no group: show everything under the first heading.
        val rows = sugg.filter { (it.str("group") ?: "observer") == g }
        if (rows.isNotEmpty()) LCard(title, action = { Text("${rows.size} सुझाव", fontSize = 12.sp, color = Muted) }) {
            rows.forEach { x ->
                val c = Callee(x.optString("mobile"), x.optString("name"), x.optString("role"))
                RowItem(x.optString("name"), "${x.optString("role")}\n${x.optString("why")}") { CallButton(c, lc) }
            }
        }
    }
    TaskProgressCard(lc)
    Button(onClick = { lc.open(Page("compose")) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
        Icon(Icons.Filled.Campaign, null); Text("  मैसेज या वॉइस नोट भेजें", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DpsTab(lc: LeaderCtx) {
    LCard("ज़िला अध्यक्ष", action = { Text("कमज़ोर पहले", fontSize = 12.sp, color = Muted) }) {
        lc.ov.optJSONArray("districts").list().reversed().forEach { DistrictRow(it, lc) }
    }
}

@Composable
private fun MembersTab(lc: LeaderCtx, withDistrict: Boolean) {
    var filter by remember { mutableStateOf("all") }
    var dist by remember { mutableStateOf("all") }
    var shown by remember { mutableIntStateOf(40) }
    var ms = lc.ov.optJSONArray("members").list()
    if (withDistrict) {
        val names = ms.map { it.optString("district") }.filter { it.isNotBlank() }.distinct().sorted()
        Picker("संगठनात्मक ज़िला", dist, listOf("all" to "सभी ज़िले") + names.map { it to it }) { dist = it; shown = 40 }
        if (dist != "all") ms = ms.filter { it.optString("district") == dist }
    }
    val counts = ms.groupingBy { it.optString("status") }.eachCount()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        (listOf("all" to "सभी ${ms.size}") + STATUS.map { (k, v) -> k to "$v ${counts[k] ?: 0}" }).forEach { (k, l) ->
            Text(l, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (filter == k) Color.White else Navy,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (filter == k) Navy else Color.White).border(1.dp, if (filter == k) Navy else Line, RoundedCornerShape(50))
                    .clickable { filter = k; shown = 40 }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    val list = (if (filter == "all") ms else ms.filter { it.optString("status") == filter }).sortedByDescending { it.optInt("points") }
    LCard {
        list.take(shown).forEach { MemberRow(it, lc) }
        if (list.isEmpty()) Text("कोई सदस्य नहीं", color = Muted)
        if (list.size > shown) TextButton(onClick = { shown += 40 }) { Text("और ${list.size - shown} देखें") }
    }
}

// ================================================================ District President

@Composable
private fun DpHome(lc: LeaderCtx) {
    val d = lc.ov.optJSONArray("districts").list().firstOrNull()
    val all = lc.ov.optJSONArray("district_ranks").list()
    if (d == null) { LCard { Text("आपका ज़िला (${lc.me.optString("district")}) सदस्य सूची में नहीं मिला। एडमिन से ज़िले का नाम ठीक करवाएं।", color = OrangeText) }; return }
    val ahead = all.firstOrNull { it.optInt("rank") == d.optInt("rank") - 1 }
    LCard {
        Text("${all.size} में से रैंक ${d.optInt("rank")}" + (ahead?.let { " · आगे वाला ज़िला: ${it.optString("name")} (${it.optInt("score")})" } ?: " · आप सबसे आगे हैं"), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
    PendingTasksBanner(lc)
    LCard("${d.optString("name")} ज़िला", action = { GradeBadge(d.optInt("score"), d.optString("grade"), big = true) }) {
        Text("${d.optInt("active")} एक्टिव / ${d.optInt("total")} सदस्य · हर वार्ड में कोई एक्टिव: ${d.optInt("coverage")}% · इस हफ़्ते ${if (d.optInt("growth") >= 0) "+" else ""}${d.optInt("growth")}%", fontSize = 13.sp, color = Muted)
    }
    LCard("विधानसभा और ऑब्ज़र्वर") { lc.ov.optJSONArray("assemblies").list().forEach { ObserverRow(it, lc) } }
    LatestReports(lc, 2)
}

/** "X टास्क माननीय ... की तरफ से आए हैं" */
@Composable
private fun PendingTasksBanner(lc: LeaderCtx) {
    val ctx = LocalContext.current
    var due by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { due = try { Api.leader(ctx, "tasks").optJSONArray("mine").list().count { !it.optBoolean("done") } } catch (e: Exception) { 0 } }
    if (due == 0) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(OrangeBg).border(1.dp, Color(0xFFF6C9A0), RoundedCornerShape(14.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.TaskAlt, null, tint = Orange)
        Text("  $due टास्क $PRESIDENT_NAME की तरफ से आए हैं", fontSize = 13.5.sp, color = OrangeText, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LatestReports(lc: LeaderCtx, n: Int) {
    val ctx = LocalContext.current
    var reps by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(Unit) { reps = try { Api.leader(ctx, "reports", JSONObject().put("limit", n)).optJSONArray("reports").list() } catch (e: Exception) { emptyList() } }
    LCard("ऑब्ज़र्वर की ताज़ा रिपोर्ट", action = { TextButton(onClick = { lc.open(Page("reports")) }) { Text("सब देखें") } }) {
        val r = reps
        if (r == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else if (r.isEmpty()) Text("अभी कोई रिपोर्ट नहीं", color = Muted, fontSize = 13.sp)
        else r.forEach { ReportCard(it, lc) }
    }
}

// ================================================================ Observer

@Composable
private fun ObserverHome(lc: LeaderCtx) {
    val a = lc.ov.optJSONArray("assemblies").list().firstOrNull()
    if (a == null) { LCard { Text("आपकी विधानसभा (${lc.me.optString("assembly")}) सदस्य सूची में नहीं मिली। एडमिन से नाम ठीक करवाएं।", color = OrangeText) }; return }
    LCard("मेरा स्कोर", action = { GradeBadge(a.optInt("score"), a.optString("grade"), big = true) }) {
        Text("${lc.ov.optInt("all_assembly_count")} में रैंक ${a.optInt("rank")} · ${a.optString("name")} · ${a.optInt("active")}/${a.optInt("total")} सदस्य एक्टिव", fontSize = 13.sp, color = Muted)
    }
    PendingTasksBanner(lc)
    val wards = lc.ov.optJSONArray("wards").list().sortedBy { it.optInt("score") }
    LCard("इस हफ़्ते कमज़ोर वार्ड") {
        wards.take(5).forEach { w ->
            RowItem("वार्ड ${w.optInt("no")}${w.str("name")?.let { " · $it" } ?: ""}", "${w.optInt("active")}/${w.optInt("total")} एक्टिव · ${WARD_LABEL[w.optString("level")]}",
                onClick = { lc.open(Page("ward", w.optInt("no").toString())) }) { Box(Modifier.size(12.dp).clip(CircleShape).background(WARD_COLOR[w.optString("level")] ?: Muted)) }
        }
    }
    val off = lc.ov.optJSONArray("members").list().count { it.optString("status") == "inactive" || it.optString("status") == "never" }
    if (off > 0) LCard { Text("$off सदस्य बंद हैं या लॉगिन नहीं किया। सदस्य टैब से कॉल करें।", color = Color(0xFF9B1C1C), fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
    Button(onClick = { lc.open(Page("report_new")) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
        Icon(Icons.Filled.EditNote, null); Text("  नई विज़िट रिपोर्ट", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ReportsTab(lc: LeaderCtx) {
    Button(onClick = { lc.open(Page("report_new")) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
        Icon(Icons.Filled.EditNote, null); Text("  नई विज़िट रिपोर्ट", fontWeight = FontWeight.Bold)
    }
    Text("जहाँ गए वहाँ के बारे में लिखें या बोलें, फोटो और वीडियो डालें। लोकेशन ऐप अपने आप ले लेता है।", fontSize = 12.5.sp, color = Muted)
    LatestReports(lc, 30)
}

/** Observer rates members on what they saw on the ground. */
@Composable
private fun RatingsTab(lc: LeaderCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ratings = remember { mutableStateMapOf<String, IntArray>() }
    LaunchedEffect(Unit) {
        try {
            Api.leader(ctx, "my_ratings").optJSONArray("ratings").list().forEach {
                ratings[it.optString("member_mobile")] = intArrayOf(it.optInt("ground"), it.optInt("connect"), it.optInt("booth"))
            }
        } catch (_: Exception) {}
    }
    Text("हर सदस्य को ज़मीन पर जो देखा उसके हिसाब से रेटिंग दें।", fontSize = 12.5.sp, color = Muted)
    val crits = listOf("ज़मीन पर दिखते हैं", "लोगों से जुड़ाव", "बूथ टीम")
    lc.ov.optJSONArray("members").list().sortedByDescending { it.optInt("points") }.forEach { mb ->
        LCard {
            MemberRow(mb, lc)
            if (mb.optString("status") == "never") Text("अभी लॉगिन नहीं किया। पहले कॉल करें।", fontSize = 12.5.sp, color = Color(0xFF9B1C1C))
            else crits.forEachIndexed { ci, c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c, fontSize = 13.sp, color = Ink, modifier = Modifier.weight(1f))
                    val cur = ratings[mb.optString("mobile")] ?: intArrayOf(0, 0, 0)
                    (1..5).forEach { n ->
                        Icon(if (n <= cur[ci]) Icons.Filled.Star else Icons.Filled.StarBorder, "$n", tint = Gold,
                            modifier = Modifier.size(28.dp).clickable {
                                val next = cur.copyOf().also { it[ci] = n }
                                ratings[mb.optString("mobile")] = next
                                scope.launch {
                                    try {
                                        Api.leader(ctx, "rate", JSONObject().put("member_mobile", mb.optString("mobile"))
                                            .put("ground", next[0]).put("connect", next[1]).put("booth", next[2]))
                                    } catch (_: Exception) {}
                                }
                            })
                    }
                }
            }
        }
    }
}

// ================================================================ Shared tabs: my tasks, messages

@Composable
private fun MyTasksTab(lc: LeaderCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var key by remember { mutableIntStateOf(0) }
    LaunchedEffect(key) { data = try { Api.leader(ctx, "tasks") } catch (e: Exception) { JSONObject() } }
    val d = data ?: run { CircularProgressIndicator(color = Orange); return }
    val mine = d.optJSONArray("mine").list()
    val due = mine.filter { !it.optBoolean("done") }; val done = mine.filter { it.optBoolean("done") }
    Kpis(listOf("${due.size}" to "बाकी टास्क", "${done.size}" to "पूरे हुए"))
    LCard("बाकी", action = { Text("अध्यक्ष जी की तरफ से", fontSize = 11.5.sp, color = Muted) }) {
        if (due.isEmpty()) Text("सब टास्क पूरे हैं।", color = Muted, fontSize = 13.sp)
        due.forEach { t ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.optString("title"), fontWeight = FontWeight.Bold, color = Ink, fontSize = 14.5.sp)
                Text("$PRESIDENT_NAME की तरफ से" + (t.str("due_date")?.let { " · आख़िरी तारीख़ ${hiDate(it)}" } ?: ""), fontSize = 12.sp, color = Muted)
                t.str("description")?.let { Text(it, fontSize = 13.sp, color = Color(0xFF3A4560)) }
                t.str("audio_url")?.let { AudioPlayer(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lc.role == "observer") Button(onClick = { lc.open(Page("report_new", t.optLong("id").toString())) }, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) { Text("रिपोर्ट भेजें") }
                    OutlinedButton(onClick = {
                        scope.launch { try { Api.leader(ctx, "task_done", JSONObject().put("task_id", t.optLong("id"))); key++ } catch (_: Exception) {} }
                    }, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)); Text(" हो गया") }
                }
                HorizontalDivider(color = Color(0xFFF0F2F6))
            }
        }
    }
    if (done.isNotEmpty()) LCard("पूरे हुए") { done.forEach { RowItem(it.optString("title"), "हो गया") { Icon(Icons.Filled.CheckCircle, null, tint = Green) } } }
    if (lc.role == "gs" || lc.role == "dp") {
        Button(onClick = { lc.open(Page("task_create")) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
            Icon(Icons.Filled.AddTask, null); Text(if (lc.role == "gs") "  ज़िला अध्यक्ष / ऑब्ज़र्वर के लिए टास्क बनाएं" else "  ऑब्ज़र्वर को टास्क दें", fontWeight = FontWeight.Bold)
        }
    }
    val created = d.optJSONArray("created").list()
    if (created.isNotEmpty()) LCard("मेरे बनाए टास्क") {
        created.forEach { t ->
            RowItem(t.optString("title"), LEVEL_HI[t.optString("level")] ?: "") {
                Chip(when (t.optString("status")) { "active" -> "भेजा गया"; "pending" -> "अध्यक्ष जी की मंज़ूरी बाकी"; "suggested" -> "सुझाव भेजा"; else -> "नहीं भेजा" }, Ground, Ink)
            }
        }
    }
}

@Composable
private fun MessagesTab(lc: LeaderCtx) {
    Button(onClick = { lc.open(Page("compose")) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
        Icon(Icons.Filled.Campaign, null); Text("  नया मैसेज या वॉइस नोट", fontWeight = FontWeight.Bold)
    }
    OutlinedButton(onClick = { lc.open(Page("task_create", "suggest")) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Filled.Lightbulb, null); Text("  नया टास्क सुझाएं")
    }
    if (lc.role == "observer") OutlinedButton(onClick = { lc.open(Page("to_president")) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Filled.RecordVoiceOver, null); Text("  अध्यक्ष जी को संदेश")
    }
    SentList()
}

@Composable
private fun SentList() {
    val ctx = LocalContext.current
    var sent by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(Unit) { sent = try { Api.leader(ctx, "sent").optJSONArray("sent").list() } catch (e: Exception) { emptyList() } }
    LCard("भेजे गए") {
        val s = sent
        if (s == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else if (s.isEmpty()) Text("अभी कोई मैसेज नहीं भेजा", color = Muted, fontSize = 13.sp)
        else s.forEach { x ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("${AUDIENCE_HI[x.optString("audience")] ?: x.optString("audience")} · ${hiDate(x.str("created_at"))}", fontSize = 12.sp, color = Muted)
                x.str("text")?.let { Text(it, fontSize = 14.sp, color = Ink) }
                x.str("audio_url")?.let { AudioPlayer(it) }
                HorizontalDivider(color = Color(0xFFF0F2F6), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

private val AUDIENCE_HI = mapOf("all" to "सबको", "gs" to "महासचिव", "dp" to "ज़िला अध्यक्षों को", "observer" to "ऑब्ज़र्वर को", "member" to "वार्ड सदस्यों को")

@Composable
private fun ReportCard(r: JSONObject, lc: LeaderCtx) {
    val ctx = LocalContext.current
    val from = r.optJSONObject("from")
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(from?.optString("name") ?: "", fontWeight = FontWeight.Bold, color = Ink)
                Text("${LEVEL_HI[from?.optString("role")] ?: ""} · ${from?.str("assembly") ?: from?.str("district") ?: ""} · ${hiDate(r.str("created_at"))}", fontSize = 12.sp, color = Muted)
            }
            from?.let { CallButton(Callee(it.optString("mobile"), it.optString("name"), LEVEL_HI[it.optString("role")] ?: ""), lc) }
        }
        r.str("note")?.let { Text(it, fontSize = 14.sp, color = Ink) }
        r.str("audio_url")?.let { AudioPlayer(it) }
        val media = r.optJSONArray("media").list()
        if (media.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            media.forEach { x ->
                val url = x.str("url") ?: return@forEach
                Box(Modifier.size(90.dp).clip(RoundedCornerShape(10.dp)).background(Navy).clickable { openLink(ctx, url) }, contentAlignment = Alignment.Center) {
                    if (x.optString("kind") == "photo") AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Filled.PlayCircle, "वीडियो", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }
        if (!r.isNull("lat")) Text("लोकेशन ऐप से अपने आप: ${"%.4f".format(r.optDouble("lat"))}, ${"%.4f".format(r.optDouble("lng"))}", fontSize = 11.5.sp, color = Blue,
            modifier = Modifier.clickable { openLink(ctx, "https://maps.google.com/?q=${r.optDouble("lat")},${r.optDouble("lng")}") })
        HorizontalDivider(color = Color(0xFFF0F2F6))
    }
}

// ================================================================ Pages on top of the tabs

@Composable
private fun LeaderPage(page: Page, lc: LeaderCtx, onBack: () -> Unit, onLogout: () -> Unit) {
    when (page.kind) {
        "inbox" -> { InboxScreen(onBack = onBack, leader = true); return }
        "to_president" -> { ToPresidentScreen(leader = true, onBack = onBack); return }
    }
    val title = when (page.kind) {
        "district" -> "${page.arg} ज़िला"; "assembly" -> page.arg ?: ""; "ward" -> "वार्ड ${page.arg}"; "member" -> "सदस्य"
        "tasks_report" -> "टास्क रिपोर्ट"; "reports" -> "फ़ील्ड रिपोर्ट"; "calls" -> "मेरी कॉल"; "sent" -> "भेजे गए मैसेज"
        "personal" -> "${lc.personal?.first?.name ?: ""} को संदेश"
        "compose" -> "मैसेज या वॉइस नोट"; "task_create" -> if (page.arg == "suggest") "नया टास्क सुझाएं" else if (lc.role == "president") "टास्क असाइन करें" else "नया टास्क"
        "report_new" -> "नई विज़िट रिपोर्ट"; "president_inbox" -> "लोगों के संदेश"; "profile" -> "मेरी प्रोफ़ाइल"; else -> ""
    }
    Column(Modifier.fillMaxSize().background(Ground)) {
        PageTop(title, onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page.kind) {
                "district" -> DistrictPage(page.arg ?: "", lc)
                "assembly" -> AssemblyPage(page.arg ?: "", lc)
                "ward" -> WardPage(page.arg?.toIntOrNull() ?: 0, lc)
                "member" -> MemberPage(page.arg ?: "", lc)
                "tasks_report" -> TasksReportPage(lc)
                "reports" -> LatestReports(lc, 60)
                "calls" -> CallsPage(lc)
                "sent" -> SentList()
                "compose" -> ComposePage(lc, onBack)
                "personal" -> lc.personal?.let { (c, preset) -> PersonalPage(c, preset, onBack) }
                "task_create" -> TaskCreatePage(lc, suggest = page.arg == "suggest", onBack = onBack)
                "report_new" -> ReportNewPage(page.arg?.toLongOrNull(), onBack)
                "president_inbox" -> PresidentInboxPage()
                "profile" -> {
                    LCard {
                        Text(lc.me.optString("name"), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        Text(ROLE_HI[lc.role] ?: lc.role, color = Muted)
                        lc.me.str("district")?.let { Text("ज़िला: $it") }
                        lc.me.str("assembly")?.let { Text("विधानसभा: $it") }
                        Text("लॉगिन नंबर: ${lc.me.optString("mobile")} (बदल नहीं सकते)", color = Muted, fontSize = 13.sp)
                    }
                    OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("लॉगआउट") }
                }
            }
        }
    }
}

@Composable
private fun DistrictPage(name: String, lc: LeaderCtx) {
    val d = lc.ov.optJSONArray("districts").list().firstOrNull { it.optString("name") == name }
    if (d != null) {
        LCard(action = { GradeBadge(d.optInt("score"), d.optString("grade"), big = true) }, title = "रैंक ${d.optInt("rank")}/${lc.ov.optInt("all_district_count")}") {
            Text("${d.optInt("active")} एक्टिव / ${d.optInt("total")} सदस्य · हर वार्ड में कोई एक्टिव: ${d.optInt("coverage")}% · इस हफ़्ते ${if (d.optInt("growth") >= 0) "+" else ""}${d.optInt("growth")}%", fontSize = 13.sp, color = Muted)
        }
        d.optJSONObject("dp")?.let { dp ->
            LCard("ज़िला अध्यक्ष") { RowItem(dp.optString("name"), dp.optString("mobile")) { MsgButton(d.dpCallee(), lc); CallButton(d.dpCallee(), lc) } }
        }
    }
    LCard("विधानसभा और ऑब्ज़र्वर") { lc.ov.optJSONArray("assemblies").list().filter { it.optString("district") == name }.forEach { ObserverRow(it, lc) } }
}

@Composable
private fun AssemblyPage(name: String, lc: LeaderCtx) {
    val a = lc.ov.optJSONArray("assemblies").list().firstOrNull { it.optString("name") == name }
    a?.let {
        LCard("ऑब्ज़र्वर", action = { GradeBadge(it.optInt("score"), it.optString("grade"), big = true) }) {
            ObserverRow(it, lc)
            Text("${it.optInt("active")}/${it.optInt("total")} एक्टिव सदस्य · ${it.optInt("wards_covered")}/${it.optInt("wards")} वार्ड में कोई एक्टिव", fontSize = 13.sp, color = Muted)
        }
    }
    val ms = lc.ov.optJSONArray("members").list().filter { it.optString("assembly") == name }
    ms.groupBy { it.optInt("ward_no") }.toSortedMap().forEach { (no, list) ->
        LCard("वार्ड $no", action = { Text("${list.size} सदस्य", fontSize = 12.sp, color = Muted) }) {
            list.sortedByDescending { it.optInt("points") }.forEach { MemberRow(it, lc) }
        }
    }
}

@Composable
private fun WardPage(no: Int, lc: LeaderCtx) {
    val w = lc.ov.optJSONArray("wards").list().firstOrNull { it.optInt("no") == no }
    w?.let {
        LCard(it.str("name") ?: "वार्ड $no", action = { Text(WARD_LABEL[it.optString("level")] ?: "", color = WARD_COLOR[it.optString("level")] ?: Muted, fontWeight = FontWeight.Bold) }) {
            Text("${it.optString("assembly")} · ${it.optString("district")} · रैंक ${it.optInt("rank")}", fontSize = 13.sp, color = Muted)
        }
    }
    LCard("अंकों के हिसाब से") {
        lc.ov.optJSONArray("members").list().filter { it.optInt("ward_no") == no }.sortedByDescending { it.optInt("points") }
            .forEachIndexed { i, m -> MemberRow(m, lc, rank = i + 1) }
    }
}

@Composable
private fun MemberPage(mobile: String, lc: LeaderCtx) {
    val m = lc.ov.optJSONArray("members").list().firstOrNull { it.optString("mobile") == mobile } ?: return
    LCard(m.optString("name"), action = { StatusChip(m.optString("status")) }) {
        Text("वार्ड ${m.optInt("ward_no")} · ${m.optString("assembly")} · ${m.optString("district")}", fontSize = 13.sp, color = Muted)
        Text("${fmtNum(m.optInt("points"))} अंक" + (if (!m.isNull("rating")) " · ऑब्ज़र्वर रेटिंग ${m.optDouble("rating")}" else ""), fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
    Kpis(listOf("${m.optInt("full_tasks")}" to "पूरे टास्क", "${m.optInt("videos")}" to "वीडियो", "${m.optInt("photos")}" to "फोटो", "${m.optInt("shares")}" to "शेयर"))
    Button(onClick = { lc.call(m.memberCallee()) }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Green)) {
        Icon(Icons.Filled.Call, null); Text("  कॉल करें", fontWeight = FontWeight.Bold)
    }
    if (lc.role == "president") OutlinedButton(onClick = { lc.message(m.memberCallee(), "") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
        Icon(Icons.Filled.Mail, null); Text("  व्यक्तिगत संदेश भेजें")
    }
}

@Composable
private fun TasksReportPage(lc: LeaderCtx) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var key by remember { mutableIntStateOf(0) }
    var openId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(key) { data = try { Api.leader(ctx, "tasks") } catch (e: Exception) { JSONObject() } }
    val d = data ?: run { CircularProgressIndicator(color = Orange); return }
    val pending = d.optJSONArray("pending").list()
    if (lc.role == "president" && pending.isNotEmpty()) LCard("महासचिव के बनाए टास्क: आपकी मंज़ूरी चाहिए") {
        Text("मंज़ूर करने पर ये आपकी तरफ से जाएंगे।", fontSize = 12.sp, color = Muted)
        pending.forEach { t ->
            Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.optString("title"), fontWeight = FontWeight.Bold)
                Text("${LEVEL_HI[t.optString("level")]}${t.str("target_district")?.let { " · $it" } ?: ""}", fontSize = 12.sp, color = Muted)
                t.str("description")?.let { Text(it, fontSize = 13.sp) }
                t.str("audio_url")?.let { AudioPlayer(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { try { Api.leader(ctx, "task_review", JSONObject().put("task_id", t.optLong("id")).put("approve", true)); key++ } catch (_: Exception) {} } },
                        colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(10.dp)) { Text("मंज़ूर करें") }
                    OutlinedButton(onClick = { scope.launch { try { Api.leader(ctx, "task_review", JSONObject().put("task_id", t.optLong("id")).put("approve", false)); key++ } catch (_: Exception) {} } },
                        shape = RoundedCornerShape(10.dp)) { Text("नहीं") }
                }
                HorizontalDivider(color = Color(0xFFF0F2F6))
            }
        }
    }
    val progress = d.optJSONArray("progress").list()
    listOf("gs", "dp", "observer").forEach { lv ->
        val ts = progress.filter { it.optString("level") == lv }
        if (ts.isNotEmpty()) LCard(LEVEL_HI[lv] ?: lv, action = { Text("${ts.size} टास्क", fontSize = 12.sp, color = Muted) }) {
            ts.forEach { t ->
                val total = t.optInt("total"); val done = t.optInt("done")
                Column(Modifier.fillMaxWidth().clickable { openId = if (openId == t.optLong("id")) null else t.optLong("id") }.padding(vertical = 4.dp)) {
                    Text(t.optString("title"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Bar("पूरा", if (total > 0) done.toFloat() / total else 0f, "$done/$total")
                    if (openId == t.optLong("id")) {
                        Text("अभी बाकी:", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                        t.optJSONArray("left").list().forEach { p ->
                            val c = Callee(p.optString("mobile"), p.optString("name"), LEVEL_HI[lv] ?: "")
                            RowItem(p.optString("name"), p.str("assembly") ?: p.str("district") ?: "") { CallButton(c, lc) }
                        }
                    }
                }
            }
        }
    }
    if (progress.isEmpty() && pending.isEmpty()) Text("अभी कोई टास्क नहीं।", color = Muted)
}

/** President: 5 random performing or non-performing observers or members to call. Everyone: call history. */
@Composable
private fun CallsPage(lc: LeaderCtx) {
    val ctx = LocalContext.current
    var calls by remember { mutableStateOf<List<JSONObject>?>(null) }
    var key by remember { mutableIntStateOf(0) }
    LaunchedEffect(key) { calls = try { Api.leader(ctx, "my_calls").optJSONArray("calls").list() } catch (e: Exception) { emptyList() } }
    if (lc.role == "president" || lc.role == "gs") {
        var kind by remember { mutableStateOf("good") }
        var who by remember { mutableStateOf("observer") }
        var seed by remember { mutableIntStateOf(0) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("प्रदर्शन", kind, listOf("good" to "परफ़ॉर्मिंग", "bad" to "नॉन-परफ़ॉर्मिंग"), Modifier.weight(1f)) { kind = it; seed++ }
            Picker("किससे", who, listOf("observer" to "ऑब्ज़र्वर", "member" to "वार्ड सदस्य"), Modifier.weight(1f)) { who = it; seed++ }
        }
        val pool: List<JSONObject> = remember(kind, who, seed) {
            if (who == "observer") {
                val base = lc.ov.optJSONArray("assemblies").list().filter { it.optJSONObject("observer") != null }
                val half = (base.size / 2).coerceAtLeast(1)
                (if (kind == "good") base.take(half) else base.takeLast(half)).shuffled().take(5)
            } else {
                val all = lc.ov.optJSONArray("members").list()
                if (kind == "good") all.filter { it.optString("status") == "active" && it.optInt("points") > 0 }.sortedByDescending { it.optInt("points") }.take(40).shuffled().take(5)
                else all.filter { it.optString("status") != "active" }.shuffled().take(5)
            }
        }
        LCard("इनसे बात करें", action = { TextButton(onClick = { seed++ }) { Text("दूसरे 5") } }) {
            pool.forEach { if (who == "observer") ObserverRow(it, lc) else MemberRow(it, lc) }
            if (pool.isEmpty()) Text(if (kind == "good") "अभी कोई एक्टिव सदस्य नहीं है।" else "इस सूची में अभी कोई नहीं है।", color = Muted, fontSize = 13.sp)
        }
    }
    LCard("मेरी कॉल", action = { TextButton(onClick = { key++ }) { Text("रीफ्रेश") } }) {
        val c = calls
        if (c == null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else if (c.isEmpty()) Text("अभी कोई कॉल नहीं की। किसी भी लिस्ट में कॉल का बटन दबाएं।", color = Muted, fontSize = 13.sp)
        else c.forEach { x ->
            RowItem(x.optString("to_name"), "${x.optString("to_role")} · ${hiDate(x.str("created_at"))}") {
                Chip(x.optString("outcome"), if (x.optString("outcome") == "बात हो गई") GreenBg else OrangeBg, if (x.optString("outcome") == "बात हो गई") GreenText else OrangeText)
            }
        }
    }
}

@Composable
private fun ComposePage(lc: LeaderCtx, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val audiences = when (lc.role) {
        "president" -> listOf("all" to "सबको", "gs" to "महासचिव", "dp" to "सभी ज़िला अध्यक्ष", "observer" to "सभी ऑब्ज़र्वर", "member" to "सभी वार्ड सदस्य")
        "gs" -> listOf("dp" to "सभी ज़िला अध्यक्ष", "observer" to "सभी ऑब्ज़र्वर", "member" to "सभी वार्ड सदस्य")
        "dp" -> listOf("observer" to "मेरे ज़िले के ऑब्ज़र्वर", "member" to "मेरे ज़िले के वार्ड सदस्य")
        else -> listOf("member" to "मेरी विधानसभा के वार्ड सदस्य")
    }
    var audience by remember { mutableStateOf(audiences.first().first) }
    var text by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf<File?>(null) }
    var sending by remember { mutableStateOf(false) }
    Picker("किसको", audience, audiences) { audience = it }
    OutlinedTextField(text, { text = it.take(2000) }, label = { Text("मैसेज लिखें") }, minLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
    VoiceNote(voice) { voice = it }
    val guard = rememberSendGuard()
    Button(
        onClick = { guard {
            if (text.isBlank() && voice == null) { Toast.makeText(ctx, "मैसेज लिखें या वॉइस नोट रिकॉर्ड करें", Toast.LENGTH_SHORT).show(); return@guard }
            sending = true
            scope.launch {
                try {
                    val p = JSONObject().put("audience", audience).put("text", text.trim())
                    voice?.let { p.put("audio_path", uploadFile(ctx, true, it, "audio", "audio/mp4")) }
                    Api.leader(ctx, "broadcast", p)
                    Toast.makeText(ctx, "भेज दिया गया", Toast.LENGTH_LONG).show()
                    onBack()
                } catch (e: Exception) { Toast.makeText(ctx, "भेजा नहीं जा सका", Toast.LENGTH_LONG).show() }
                sending = false
            }
        } },
        enabled = !sending, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy),
    ) { Icon(Icons.AutoMirrored.Filled.Send, null); Text(if (sending) "  भेजा जा रहा है…" else "  भेजें", fontWeight = FontWeight.Bold) }
}

@Composable
private fun TaskCreatePage(lc: LeaderCtx, suggest: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val levels = when {
        suggest -> listOf((if (lc.role == "dp") "observer" else "observer") to "ऑब्ज़र्वर के लिए")
        lc.role == "president" -> listOf("gs" to "महासचिव", "dp" to "ज़िला अध्यक्ष")
        lc.role == "gs" -> listOf("dp" to "ज़िला अध्यक्ष", "observer" to "विधानसभा ऑब्ज़र्वर")
        else -> listOf("observer" to "मेरे ज़िले के ऑब्ज़र्वर")
    }
    var level by remember { mutableStateOf(levels.first().first) }
    var district by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf<File?>(null) }
    var sending by remember { mutableStateOf(false) }
    val note = when {
        suggest -> "आपका सुझाव एडमिन तक जाएगा।"
        lc.role == "president" -> "टास्क आपकी तरफ से तुरंत चला जाएगा।"
        lc.role == "gs" -> "अध्यक्ष जी की मंज़ूरी के बाद यह टास्क उनकी तरफ से जाएगा।"
        else -> "टास्क आपके ज़िले के ऑब्ज़र्वर को जाएगा।"
    }
    Text(note, fontSize = 13.sp, color = Muted)
    if (levels.size > 1) Picker("किसके लिए", level, levels) { level = it }
    if ((lc.role == "president" || lc.role == "gs") && level != "gs" && !suggest) {
        val names = lc.ov.optJSONArray("districts").list().map { it.optString("name") }
        Picker("संगठनात्मक ज़िला", district, listOf("" to "सभी ज़िले") + names.map { it to it }) { district = it }
    }
    OutlinedTextField(title, { title = it.take(300) }, label = { Text("टास्क *") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
    OutlinedTextField(details, { details = it.take(2000) }, label = { Text("थोड़ा और बताएं") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
    VoiceNote(voice) { voice = it }
    val guard = rememberSendGuard()
    Button(
        onClick = { guard {
            if (title.isBlank()) { Toast.makeText(ctx, "टास्क लिखें", Toast.LENGTH_SHORT).show(); return@guard }
            sending = true
            scope.launch {
                try {
                    val p = JSONObject().put("level", level).put("title", title.trim()).put("description", details.trim())
                    if (district.isNotBlank()) p.put("target_district", district)
                    if (suggest) p.put("suggest", true)
                    voice?.let { p.put("audio_path", uploadFile(ctx, true, it, "audio", "audio/mp4")) }
                    Api.leader(ctx, "task_create", p)
                    Toast.makeText(ctx, if (suggest) "सुझाव भेज दिया" else "टास्क भेज दिया", Toast.LENGTH_LONG).show()
                    onBack()
                } catch (e: Exception) { Toast.makeText(ctx, "भेजा नहीं जा सका", Toast.LENGTH_LONG).show() }
                sending = false
            }
        } },
        enabled = !sending, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy),
    ) { Text(if (sending) "भेजा जा रहा है…" else if (suggest) "सुझाव भेजें" else "टास्क भेजें", fontWeight = FontWeight.Bold) }
}

/** Observer visit report: text, voice note, photos and videos, location picked up by the app. */
@Composable
private fun ReportNewPage(taskId: Long?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf<File?>(null) }
    var media by remember { mutableStateOf(listOf<Pair<String, Uri>>()) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { warmLocation(ctx) }
    LaunchedEffect(Unit) { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us -> media = (media + us.map { "photo" to it }).take(8) }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us -> media = (media + us.map { "video" to it }).take(8) }

    if (taskId != null) Text("यह रिपोर्ट टास्क के साथ जुड़ेगी और टास्क पूरा माना जाएगा।", fontSize = 13.sp, color = Muted)
    OutlinedTextField(note, { note = it.take(3000) }, label = { Text("कहाँ गए, क्या देखा, किससे मिले") }, minLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
    VoiceNote(voice) { voice = it }
    val guard = rememberSendGuard()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickPhotos.launch("image/*") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.PhotoCamera, null); Text(" फोटो") }
        OutlinedButton(onClick = { pickVideos.launch("video/*") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Videocam, null); Text(" वीडियो") }
    }
    if (media.isNotEmpty()) Text("${media.count { it.first == "photo" }} फोटो, ${media.count { it.first == "video" }} वीडियो चुने", fontSize = 13.sp, color = GreenText)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.LocationOn, null, tint = Muted, modifier = Modifier.size(16.dp))
        Text(" लोकेशन ऐप अपने आप ले लेता है।", fontSize = 12.5.sp, color = Muted)
    }
    if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = Blue)
    Button(
        onClick = { guard {
            if (note.isBlank() && voice == null && media.isEmpty()) { Toast.makeText(ctx, "कुछ लिखें, बोलें या फोटो डालें", Toast.LENGTH_SHORT).show(); return@guard }
            sending = true
            scope.launch {
                try {
                    val arr = JSONArray()
                    media.forEachIndexed { i, (kind, uri) ->
                        status = "अपलोड हो रहा है ${i + 1}/${media.size}"
                        arr.put(JSONObject().put("kind", kind).put("path", uploadUri(ctx, true, uri, kind)))
                    }
                    val p = JSONObject().put("note", note.trim()).put("media", arr)
                    voice?.let { p.put("audio_path", uploadFile(ctx, true, it, "audio", "audio/mp4")) }
                    taskId?.let { p.put("task_id", it) }
                    lastLocation(ctx)?.let { p.put("lat", it.latitude).put("lng", it.longitude) }
                    Api.leader(ctx, "report_submit", p)
                    Toast.makeText(ctx, "रिपोर्ट भेज दी गई", Toast.LENGTH_LONG).show()
                    onBack()
                } catch (e: Exception) { Toast.makeText(ctx, "भेजा नहीं जा सका, इंटरनेट चेक करें", Toast.LENGTH_LONG).show() }
                sending = false; status = ""
            }
        } },
        enabled = !sending, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy),
    ) { Text(if (sending) "भेजा जा रहा है…" else "रिपोर्ट भेजें", fontWeight = FontWeight.Bold) }
}

/** Messages ward members and observers sent to the President. */
@Composable
private fun PresidentInboxPage() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(Unit) { list = try { Api.leader(ctx, "president_inbox").optJSONArray("messages").list() } catch (e: Exception) { emptyList() } }
    Text("सदस्यों और ऑब्ज़र्वर ने आपको जो संदेश भेजे। हर संदेश पर उसका टैग लगा है।", fontSize = 12.5.sp, color = Muted)
    val l = list ?: run { CircularProgressIndicator(color = Orange); return }
    if (l.isEmpty()) Text("अभी कोई संदेश नहीं", color = Muted)
    l.forEach { m ->
        LCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.optString("sender_name").ifBlank { m.optString("mobile") }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                m.str("tone_label")?.let { label ->
                    val tone = m.optString("tone")
                    val (bg, fg) = when (tone) {
                        "critical" -> Color(0xFFB42318) to Color.White
                        "concern", "negative" -> Color(0xFFFDE8E8) to Color(0xFF9B1C1C)
                        "positive" -> GreenBg to GreenText
                        else -> Ground to Muted
                    }
                    Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = fg, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            val topics = m.optJSONArray("topics")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            if (topics.isNotEmpty()) Text(topics.joinToString(" · "), fontSize = 12.sp, color = Blue, fontWeight = FontWeight.SemiBold)
            Text("${if (m.optString("sender_role") == "member") "वार्ड ${m.optInt("ward_no")}" else LEVEL_HI[m.optString("sender_role")] ?: ""}${m.str("assembly")?.let { " · $it" } ?: ""} · ${hiDate(m.str("created_at"))}", fontSize = 12.sp, color = Muted)
            m.str("text")?.let { Text(it, fontSize = 14.sp) }
            m.str("audio_url")?.let { AudioPlayer(it) }
            val media = m.optJSONArray("media").list()
            if (media.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                media.forEach { x ->
                    val url = x.str("url") ?: return@forEach
                    Box(Modifier.size(90.dp).clip(RoundedCornerShape(10.dp)).background(Navy).clickable { openLink(ctx, url) }, contentAlignment = Alignment.Center) {
                        if (x.optString("kind") == "photo") AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Filled.PlayCircle, "वीडियो", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }
            }
        }
    }
}

/** President's message to one person, straight to their inbox. Same layout as a message to everyone: write, record, send. */
@Composable
private fun PersonalPage(c: Callee, preset: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(preset) }
    var kind by remember { mutableStateOf(if (preset.startsWith("बधाई")) "kudos" else if (preset.isNotBlank()) "motivate" else "personal") }
    var voice by remember { mutableStateOf<File?>(null) }
    var sending by remember { mutableStateOf(false) }
    LCard {
        Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(c.role, fontSize = 13.sp, color = Muted)
    }
    Picker("संदेश का प्रकार", kind, listOf("kudos" to "बधाई संदेश", "motivate" to "प्रेरणा संदेश", "personal" to "व्यक्तिगत संदेश")) { kind = it }
    OutlinedTextField(text, { text = it.take(2000) }, label = { Text("मैसेज लिखें") }, minLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
    VoiceNote(voice) { voice = it }
    val guard = rememberSendGuard()
    Text("यह संदेश उनके इनबॉक्स में सिर्फ़ उन्हीं को दिखेगा।", fontSize = 12.5.sp, color = Muted)
    Button(
        onClick = { guard {
            if (text.isBlank() && voice == null) { Toast.makeText(ctx, "मैसेज लिखें या वॉइस नोट रिकॉर्ड करें", Toast.LENGTH_SHORT).show(); return@guard }
            sending = true
            scope.launch {
                try {
                    val p = JSONObject().put("to_mobile", c.mobile).put("text", text.trim()).put("kind", kind)
                    voice?.let { p.put("audio_path", uploadFile(ctx, true, it, "audio", "audio/mp4")) }
                    Api.leader(ctx, "personal_message", p)
                    Toast.makeText(ctx, "भेज दिया गया", Toast.LENGTH_LONG).show()
                    onBack()
                } catch (e: Exception) { Toast.makeText(ctx, "भेजा नहीं जा सका", Toast.LENGTH_LONG).show() }
                sending = false
            }
        } },
        enabled = !sending, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Navy),
    ) { Icon(Icons.AutoMirrored.Filled.Send, null); Text(if (sending) "  भेजा जा रहा है…" else "  भेजें", fontWeight = FontWeight.Bold) }
}
