package com.coceptual.wardsadasya

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.compose.AsyncImage
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

const val APP_NAME = "INC MCD NETWORK"

internal val Navy = Color(0xFF0E1B3D)
internal val NavySoft = Color(0xFF172A59)
internal val Blue = Color(0xFF2463D1)
internal val Orange = Color(0xFFF2852B)
internal val OrangeBg = Color(0xFFFFF1E4)
internal val OrangeText = Color(0xFF9A4A0B)
internal val Green = Color(0xFF12884A)
internal val GreenBg = Color(0xFFE6F5EC)
internal val GreenText = Color(0xFF14613A)
internal val Ground = Color(0xFFF2F4F8)
internal val Line = Color(0xFFE3E7EF)
internal val Muted = Color(0xFF6B7590)
internal val Ink = Color(0xFF141C33)
internal val Gold = Color(0xFFF59E0B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notify.createChannel(this)
        ReminderWorker.schedule(this)
        Push.register(this)
        openTask.value = intent?.getLongExtra("open_task", 0L)?.takeIf { it > 0 }
        openInbox.value = intent?.getBooleanExtra("open_inbox", false) == true
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, secondary = Orange, background = Ground, surface = Color.White)) {
                WardApp(openTask, openInbox)
            }
        }
    }

    // Set when the app is opened from a notification: open that task or the inbox.
    private val openTask = mutableStateOf<Long?>(null)
    private val openInbox = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getLongExtra("open_task", 0L).takeIf { it > 0 }?.let { openTask.value = it }
        if (intent.getBooleanExtra("open_inbox", false)) openInbox.value = true
    }

    override fun onResume() {
        super.onResume()
        ReminderWorker.checkNow(this)
    }
}

/** Upload screen: idle, sending one file (with progress), removing a wrong file, or submitting the finished task. */
internal sealed interface TaskPhase {
    data object Idle : TaskPhase
    data class Uploading(val kind: String, val progress: Float = 0f, val batch: String? = null) : TaskPhase
    data object Removing : TaskPhase
    data object Submitting : TaskPhase
}

/** Login: the number that was found, waiting for the person to confirm it is them. */
internal data class LoginMatch(val token: String, val role: String, val who: JSONObject, val profiles: List<JSONObject> = emptyList())

internal sealed class Screen {
    object Login : Screen()
    object Home : Screen()
    data class Task(val task: JSONObject, val shares: List<JSONObject>) : Screen()
    object Messages : Screen()
    data class Notifications(val data: JSONObject) : Screen()
    object Progress : Screen()
    object Profile : Screen()
    object Posters : Screen()
    object Inbox : Screen()
    object OwnTasks : Screen()
    object ToPresident : Screen()
}

internal fun depth(s: Screen) = when (s) {
    is Screen.Login -> 0
    is Screen.Home, is Screen.Messages, is Screen.Notifications, is Screen.Progress -> 1
    else -> 2
}

@Composable
internal fun WardApp(openTask: MutableState<Long?> = mutableStateOf(null), openInbox: MutableState<Boolean> = mutableStateOf(false)) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(if (Store.token(ctx) == null) Screen.Login else Screen.Home) }
    // Leaders (President, GS, District Presidents, Observers) get their own app after login.
    var role by remember { mutableStateOf(Store.role(ctx)) }
    var homeKey by remember { mutableIntStateOf(0) }
    // Home data is kept here, so going back to Home shows it instantly while it refreshes in the background.
    var homeData by remember { mutableStateOf<JSONObject?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ReminderWorker.checkNow(ctx)
        }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val logout = {
        Push.unregister(ctx)
        Store.clear(ctx)
        homeData = null
        role = "member"
        screen = Screen.Login
    }
    // Opened from a notification: go straight to that task (or the inbox) once home data is in.
    LaunchedEffect(openTask.value, homeData) {
        val id = openTask.value ?: return@LaunchedEffect
        val d = homeData ?: return@LaunchedEffect
        val a = d.optJSONArray("tasks") ?: JSONArray()
        val all = (0 until a.length()).map { a.getJSONObject(it) }
        all.firstOrNull { it.optLong("id") == id }?.let { t ->
            if (t.optString("kind") == "upload") screen = Screen.Task(t, all.filter { it.optString("kind") == "share" })
        }
        openTask.value = null
    }
    LaunchedEffect(openInbox.value) {
        if (openInbox.value && screen != Screen.Login && role == "member") { screen = Screen.Inbox; openInbox.value = false }
    }
    val switchProfile: (() -> Unit)? = Store.otherProfile(ctx)?.takeIf { screen != Screen.Login }?.let { other ->
        { Store.activate(ctx, other); role = Store.role(ctx); homeData = null; screen = Screen.Home }
    }
    if (screen != Screen.Login && role != "member") {
        key(role) { LeaderApp(role = role, onLogout = logout, onSwitch = switchProfile, openInbox = openInbox) }
        return
    }
    val goHome = { if (screen is Screen.Task || screen is Screen.Profile) homeKey++; screen = Screen.Home }
    val backFromInbox = { homeKey++; screen = Screen.Home }
    val nav = Nav(
        home = goHome,
        messages = { screen = Screen.Messages },
        notifications = { homeData?.let { screen = Screen.Notifications(it) } },
        progress = { screen = Screen.Progress },
        unread = homeData?.let { hasUnread(it) } ?: false,
    )

    Surface(Modifier.fillMaxSize(), color = Ground) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                if (depth(targetState) >= depth(initialState)) {
                    (slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280))) togetherWith fadeOut(tween(160))
                } else {
                    fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(220)))
                }
            },
            contentKey = { it::class },
            label = "screen",
        ) { s ->
            when (s) {
                is Screen.Login -> LoginScreen(onDone = {
                    role = Store.role(ctx)
                    screen = Screen.Home
                    ReminderWorker.checkNow(ctx)
                    Push.register(ctx)
                })
                is Screen.Home -> HomeScreen(
                    cached = homeData,
                    onData = { homeData = it },
                    refreshKey = homeKey,
                    nav = nav,
                    onTask = { t, shares -> screen = Screen.Task(t, shares) },
                    onLogout = logout,
                    otherProfile = Store.otherProfile(ctx),
                    onSwitch = switchProfile,
                    onMenu = { item -> screen = when (item) {
                        "posters" -> Screen.Posters
                        "inbox" -> Screen.Inbox
                        "own" -> Screen.OwnTasks
                        "president" -> Screen.ToPresident
                        else -> Screen.Profile
                    } },
                )
                is Screen.Task -> TaskScreen(s.task, s.shares, onBack = goHome, onLogout = logout)
                is Screen.Messages -> MessagesScreen(nav = nav, helpline = homeData?.str("helpline"), social = homeData?.optJSONObject("social"), dpccSocial = homeData?.optJSONObject("dpcc_social"))
                is Screen.Notifications -> NotificationsScreen(data = homeData ?: s.data, nav = nav, onTask = { t, shares -> screen = Screen.Task(t, shares) })
                is Screen.Progress -> ProgressScreen(data = homeData, onData = { homeData = it }, nav = nav, onProfile = { screen = Screen.Profile })
                is Screen.Profile -> ProfileScreen(onBack = { screen = Screen.Progress }, onLogout = logout)
                is Screen.Posters -> PostersScreen(onBack = { screen = Screen.Home })
                is Screen.Inbox -> InboxScreen(onBack = backFromInbox)
                is Screen.OwnTasks -> OwnTasksScreen(onBack = { screen = Screen.Home }, onLogout = logout)
                is Screen.ToPresident -> ToPresidentScreen(leader = false, onBack = { screen = Screen.Home })
            }
        }
    }
}

internal fun openLink(ctx: Context, url: String) {
    try {
        val action = if (url.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW
        ctx.startActivity(Intent(action, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, "लिंक नहीं खुल सका", Toast.LENGTH_SHORT).show()
    }
}

internal fun fmtNum(n: Int): String = NumberFormat.getIntegerInstance(Locale("en", "IN")).format(n)

internal fun JSONObject.str(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

internal fun dayLabel(date: String): Pair<String, String> {
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val p = date.split("-")
    return if (p.size == 3) Pair(p[2].trimStart('0'), months[p[1].toInt() - 1]) else Pair(date, "")
}

// ---------------------------------------------------------------- Login

@Composable
internal fun LoginScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mobile by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var helpline by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<LoginMatch?>(null) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Column(
            Modifier.fillMaxWidth().background(Navy).padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painterResource(R.drawable.logo), contentDescription = "कांग्रेस पार्टी चिह्न",
                modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White), contentScale = ContentScale.Fit,
            )
            Text(APP_NAME, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("दिल्ली प्रदेश कांग्रेस कमेटी", color = Color(0xFFC9D2E3), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val match = found
            if (match == null) {
                Text("अपना मोबाइल नंबर डालें", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "वही 10 अंकों का नंबर डालें जो पार्टी के पास दर्ज है। नंबर सूची में होगा तो आपका नाम नीचे दिख जाएगा।",
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
                )

                OutlinedTextField(
                    value = mobile,
                    onValueChange = { v ->
                        mobile = v.filter { it.isDigit() }.take(10)
                        error = null
                    },
                    prefix = { Text("+91  ", fontWeight = FontWeight.SemiBold) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )

                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val r = Api.call(ctx, "login", JSONObject().put("mobile", mobile))
                                found = LoginMatch(
                                    token = r.getString("token"),
                                    role = r.optString("role", "member").ifBlank { "member" },
                                    who = r.optJSONObject("leader") ?: r.getJSONObject("member"),
                                    profiles = r.optJSONArray("profiles")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList(),
                                )
                            } catch (e: ApiError) {
                                error = if (e.code == "not_registered") "यह नंबर सूची में नहीं मिला।" else "मोबाइल नंबर सही नहीं है।"
                                helpline = e.body.optString("helpline").ifBlank { null }
                            } catch (e: Exception) {
                                error = "इंटरनेट चेक करें और फिर कोशिश करें।"
                            }
                            busy = false
                        }
                    },
                    enabled = mobile.length == 10 && !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                ) {
                    Text(if (busy) "जांच हो रही है…" else "आगे बढ़ें", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }

                error?.let {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(OrangeBg).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(it, color = OrangeText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "आपका नंबर अभी सूची में नहीं जुड़ा है। हेल्पलाइन पर बात करें, हम जोड़ देंगे।",
                            color = OrangeText, fontSize = 13.sp,
                        )
                        helpline?.let { h ->
                            OutlinedButton(
                                onClick = { openLink(ctx, "tel:$h") },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = OrangeText),
                                border = BorderStroke(1.dp, Orange),
                            ) {
                                Icon(Icons.Filled.Phone, null, modifier = Modifier.size(17.dp)); Text("  हेल्पलाइन पर कॉल करें: $h", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            } else {
                val m = match.who
                val loginRole = match.role
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF0FDF4))
                        .border(1.5.dp, Green, RoundedCornerShape(16.dp)).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(GreenBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Check, null, tint = Green)
                        }
                        Text(
                            "  आपका नाम सूची में मिल गया",
                            color = GreenText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                    }

                    HorizontalDivider(color = Color(0xFFBBF7D0))

                    ProfileField("नाम", m.optString("name"))
                    if (loginRole != "member") {
                        ProfileField("पद", ROLE_HI[loginRole] ?: loginRole)
                        m.str("district")?.let { ProfileField("ज़िला (District)", it) }
                        m.str("assembly")?.let { ProfileField("विधानसभा", it) }
                    } else {
                    val wardName = m.optString("ward_name").takeIf { it.isNotBlank() && it != "null" }
                    ProfileField("वार्ड", "वार्ड ${m.optInt("ward_no")}" + (wardName?.let { " ($it)" } ?: ""))
                    val district = (m.optString("district").takeIf { it.isNotBlank() && it != "null" }
                        ?: m.optString("zone").takeIf { it.isNotBlank() && it != "null" })
                    if (district != null) ProfileField("कांग्रेस संगठनात्मक ज़िला", district)
                    val ac = m.optString("assembly").takeIf { it.isNotBlank() && it != "null" }
                    if (ac != null) ProfileField("विधानसभा", ac)
                    }
                    ProfileField("मोबाइल", "+91 $mobile")
                }

                // This number holds two profiles (for example Observer of one assembly and member of a ward): let them pick.
                val others = match.profiles.filter { it.optString("role") != match.role }
                if (others.isNotEmpty()) {
                    Text("इस नंबर पर आपकी दो प्रोफ़ाइल हैं। जो खोलनी हो वह चुनें। बाद में मेन्यू से बदल भी सकते हैं।", fontSize = 13.sp, color = Muted, lineHeight = 19.sp)
                    others.forEach { o ->
                        OutlinedButton(
                            onClick = { Store.setProfiles(ctx, match.profiles); Store.activate(ctx, o); onDone() },
                            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                (ROLE_HI[o.optString("role")] ?: "") + " प्रोफ़ाइल खोलें" + (if (o.optString("role") == "member") " (वार्ड ${o.optInt("ward_no")})" else o.str("assembly")?.let { " ($it)" } ?: ""),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Navy,
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        Store.setProfiles(ctx, match.profiles)
                        Store.setToken(ctx, match.token)
                        Store.setRole(ctx, match.role)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) {
                    Text(if (others.isNotEmpty()) "${ROLE_HI[match.role] ?: ""} प्रोफ़ाइल खोलें  " else "आगे बढ़ें  ", fontSize = if (others.isNotEmpty()) 16.sp else 18.sp, fontWeight = FontWeight.Bold); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                }

                TextButton(
                    onClick = {
                        found = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("दूसरा नंबर डालें", color = Muted, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
internal fun ProfileField(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 14.sp)
        Text(value, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}


// ---------------------------------------------------------------- Shared bits

internal val HI_MONTHS = listOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर")
internal val HI_WEEKDAYS = listOf("रवि", "सोम", "मंगल", "बुध", "गुरु", "शुक्र", "शनि")

internal fun parseYmd(s: String): Calendar? {
    val p = s.take(10).split("-")
    if (p.size != 3) return null
    return try {
        Calendar.getInstance().apply { clear(); set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt()) }
    } catch (e: Exception) { null }
}

internal fun ymd(c: Calendar) =
    String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

/** "2026-11-12" or an ISO timestamp -> "12 नवंबर 2026". */
internal fun hiDate(s: String?): String {
    val c = s?.let { parseYmd(it) } ?: return ""
    return "${c.get(Calendar.DAY_OF_MONTH)} ${HI_MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
}

/** Continuous run of dates for the strip: back to the first task day (max 30 days), forward 6 days. */
internal fun stripDays(today: String, taskDays: List<String>): List<String> {
    val t = parseYmd(today) ?: return taskDays
    var back = 6
    taskDays.minOrNull()?.takeIf { it < today }?.let { first ->
        parseYmd(first)?.let { f ->
            val diff = ((t.timeInMillis - f.timeInMillis) / 86_400_000L).toInt()
            back = maxOf(back, minOf(diff, 30))
        }
    }
    return (-back..6).map { i -> ymd((t.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }) }
}

/** True when something in Notifications is new for this member. */
internal fun hasUnread(d: JSONObject): Boolean {
    val update = d.optJSONObject("app_update")
    val updateAvailable = (update?.optInt("version_code", 1) ?: 1) > BuildConfig.VERSION_CODE
    val leader = d.optJSONObject("leader")
    val announcements = d.optJSONArray("announcements")?.length() ?: 0
    return updateAvailable || (leader != null && !leader.optBoolean("seen")) || announcements > 0
}

/** Bottom tabs. Shown on Home, Messages, Notifications and Points. */
internal class Nav(
    val home: () -> Unit,
    val messages: () -> Unit,
    val notifications: () -> Unit,
    val progress: () -> Unit,
    val unread: Boolean,
)

/** Navy page header with a back arrow, used on every screen except Home. */
@Composable
internal fun PageTop(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Navy).padding(start = 10.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "वापस", tint = Color.White, modifier = Modifier.size(20.dp)) }
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Small photo of Devender Yadav ji cut from the home banner, with a play button. */
@Composable
internal fun LeaderThumb(size: Int, playSize: Int = 34) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(Navy), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.home_banner), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0.62f, -0.55f),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x990E1B3D)))))
        Box(Modifier.size(playSize.dp).clip(CircleShape).background(Orange), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size((playSize * 0.6f).dp))
        }
    }
}

@Composable
internal fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(20.dp)),
        content = content,
    )
}

@Composable
internal fun IconTile(icon: ImageVector, bg: Color, fg: Color, size: Int = 42) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(13.dp)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = fg, modifier = Modifier.size((size * 0.5f).dp))
    }
}

// ---------------------------------------------------------------- Home

@Composable
internal fun HomeScreen(
    cached: JSONObject?,
    onData: (JSONObject) -> Unit,
    refreshKey: Int,
    nav: Nav,
    onTask: (JSONObject, List<JSONObject>) -> Unit,
    onLogout: () -> Unit,
    onMenu: (String) -> Unit = {},
    otherProfile: JSONObject? = null,
    onSwitch: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(cached?.str("date")) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }

    // Home is the root screen: the first back press warns, a second one within 2 seconds closes the app.
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) (ctx as? Activity)?.finish()
        else {
            lastBack = now
            Toast.makeText(ctx, "ऐप बंद करने के लिए फिर से वापस दबाएं", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(date, refreshKey, reload) {
        error = null
        loading = true
        try {
            val p = JSONObject(); date?.let { p.put("date", it) }
            onData(Api.call(ctx, "home", p))
        } catch (e: ApiError) {
            if (e.code == "unauthorized") onLogout() else error = "डेटा लोड नहीं हुआ।"
        } catch (e: Exception) {
            error = "इंटरनेट चेक करें।"
        }
        loading = false
    }

    val d = cached
    if (d == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, color = OrangeText)
                TextButton(onClick = { reload++ }) { Text("फिर कोशिश करें") }
            } else CircularProgressIndicator(color = Orange)
        }
        return
    }

    val update = d.optJSONObject("app_update")
    val serverVersionCode = update?.optInt("version_code", 1) ?: 1
    val isMandatory = update?.optBoolean("mandatory", false) ?: false
    val downloadUrl = update?.optString("download_url").orEmpty().ifBlank { "https://github.com/aadiliftekhar143-cmd/inc-mcd-network/releases/latest/download/app-release.apk" }
    val updateNotes = update?.optString("notes").orEmpty().ifBlank { "नया वर्शन उपलब्ध है।" }
    val versionName = update?.optString("version_name").orEmpty().ifBlank { "" }
    val isUpdateAvailable = serverVersionCode > BuildConfig.VERSION_CODE
    var showUpdateDialog by remember { mutableStateOf(true) }

    if (isUpdateAvailable && showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { if (!isMandatory) showUpdateDialog = false },
            title = { Text("नया अपडेट उपलब्ध है", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (versionName.isNotBlank()) {
                        Text("वर्शन $versionName (Build $serverVersionCode)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Blue)
                    }
                    Text(updateNotes, fontSize = 14.sp, color = Ink, lineHeight = 20.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "पुराना ऐप हटाने (uninstall) की ज़रूरत नहीं है। 'अभी अपडेट करें' पर दबाकर डाउनलोड करें और सीधे इंस्टॉल करें।",
                        fontSize = 12.sp, color = Muted, lineHeight = 17.sp,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { openLink(ctx, downloadUrl) }, colors = ButtonDefaults.buttonColors(containerColor = Navy)) {
                    Text("अभी अपडेट करें", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = if (!isMandatory) {
                { TextButton(onClick = { showUpdateDialog = false }) { Text("बाद में", color = Muted) } }
            } else null,
        )
    }

    val member = d.getJSONObject("member")
    val stats = d.optJSONObject("stats") ?: JSONObject()
    val rule = d.optJSONObject("points_rule") ?: JSONObject()
    val allTasks = remember(d) { d.optJSONArray("tasks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList() }
    val uploadTasks = remember(allTasks) { allTasks.filter { it.optString("kind") == "upload" } }
    val shareTasks = remember(allTasks) { allTasks.filter { it.optString("kind") == "share" } }
    val doneCount = uploadTasks.count { it.optBoolean("done") }
    val today = d.optString("today")
    val selected = d.optString("date")
    val taskDays = remember(d) { d.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() }
    val days = remember(today, taskDays) { stripDays(today, taskDays) }
    val leaderMsg = d.optJSONObject("leader")
    val district = member.str("district") ?: member.str("zone")

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            SideMenu(
                name = "${member.optString("name").trim()} Ji",
                sub = "वार्ड ${member.optInt("ward_no")}" + (district?.let { " · $it" } ?: ""),
                items = listOfNotNull(
                    if (otherProfile != null && onSwitch != null) MenuItem(Icons.Filled.SwitchAccount, "${ROLE_HI[otherProfile.optString("role")] ?: ""} प्रोफ़ाइल खोलें") { onSwitch() } else null,
                    MenuItem(Icons.Filled.MarkEmailUnread, "मेरा इनबॉक्स", badge = d.optInt("unread_inbox")) { onMenu("inbox") },
                    MenuItem(Icons.Filled.Image, "पोस्टर और बैनर") { onMenu("posters") },
                    MenuItem(Icons.Filled.AddTask, "अपना टास्क जोड़ें") { onMenu("own") },
                    MenuItem(Icons.Filled.RecordVoiceOver, "अध्यक्ष जी को संदेश भेजें") { onMenu("president") },
                    MenuItem(Icons.Filled.Person, "प्रोफ़ाइल एडिट करें") { onMenu("profile") },
                    MenuItem(Icons.AutoMirrored.Filled.Logout, "लॉगआउट", danger = true) { onLogout() },
                ),
                close = { scope.launch { drawer.close() } },
            )
        },
    ) {
    Scaffold(
        containerColor = Ground,
        bottomBar = { BottomBar(selected = "home", nav = nav.copyWithHome { reload++ }) },
    ) { inner ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            // Banner with greeting, and the points card overlapping its bottom edge
            item(key = "hero") {
                Box(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().background(Navy)) {
                            Image(
                                painterResource(R.drawable.home_banner), contentDescription = "देवेन्द्र यादव जी",
                                modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth,
                            )
                            Box(
                                Modifier.matchParentSize().background(
                                    Brush.verticalGradient(0f to Color.Transparent, 0.62f to Color.Transparent, 1f to Navy),
                                ),
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box {
                                    GlassButton(Icons.Filled.Menu, "मेन्यू") { scope.launch { drawer.open() } }
                                    if (d.optInt("unread_inbox") > 0) Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(Color(0xFFE5402F)))
                                }
                                Spacer(Modifier.width(10.dp))
                                Image(
                                    painterResource(R.drawable.logo), contentDescription = "कांग्रेस",
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.White),
                                )
                                Text(APP_NAME, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp,
                                    modifier = Modifier.padding(start = 8.dp).weight(1f))
                                GlassButton(Icons.Filled.Refresh, "रीफ्रेश") { reload++ }
                            }
                            Column(Modifier.align(Alignment.TopStart).fillMaxWidth(0.52f).padding(start = 18.dp, top = 58.dp)) {
                                Text("Namaste,", color = Color(0xFFAFC0E3), fontSize = 13.sp)
                                Text("${member.optString("name").trim()} Ji", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 29.sp)
                                Row(
                                    Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF))
                                        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.LocationOn, null, tint = Color(0xFFE2E9F8), modifier = Modifier.size(13.dp))
                                    Text(
                                        " वार्ड ${member.optInt("ward_no")}" + (district?.let { " · $it" } ?: ""),
                                        color = Color(0xFFE2E9F8), fontSize = 12.sp, maxLines = 1,
                                    )
                                }
                            }
                            if (loading) LinearProgressIndicator(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                                color = Orange, trackColor = Color.Transparent,
                            )
                        }
                        Spacer(Modifier.height(36.dp))
                    }
                    Row(
                        Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp).fillMaxWidth()
                            .shadow(10.dp, RoundedCornerShape(18.dp), spotColor = Color(0x330E1B3D))
                            .clip(RoundedCornerShape(18.dp)).background(Color.White)
                            .clickable { nav.progress() }.padding(vertical = 12.dp),
                    ) {
                        StatCell(fmtNum(stats.optInt("points")), "कुल अंक", Orange, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(40.dp).background(Line).align(Alignment.CenterVertically))
                        StatCell(fmtNum(stats.optInt("tasks_done")), "टास्क पूरे", Ink, Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(40.dp).background(Line).align(Alignment.CenterVertically))
                        StatCell(fmtNum(stats.optInt("videos")), "वीडियो", Ink, Modifier.weight(1f))
                    }
                }
            }

            if (isUpdateAvailable) item(key = "update") {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFEFF4FF)).border(1.dp, Color(0xFFC9D8F5), RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("नया अपडेट उपलब्ध है${if (versionName.isNotBlank()) " (v$versionName)" else ""}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Navy)
                        Text("बेहतर परफॉर्मेंस व नए फीचर्स के लिए अपडेट करें", fontSize = 12.sp, color = Muted)
                    }
                    Button(
                        onClick = { openLink(ctx, downloadUrl) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(36.dp),
                    ) { Text("अपडेट करें", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }

            // Date strip
            item(key = "days") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
                    SectionTitle("टास्क कैलेंडर") {
                        if (selected != today) Text("आज", color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { date = today }.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    val startIdx = days.indexOf(selected).coerceAtLeast(0)
                    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (startIdx - 2).coerceAtLeast(0))
                    LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(days, key = { it }) { day ->
                            DateChip(day, isToday = day == today, isSelected = day == selected, isFuture = day > today, hasTasks = day in taskDays) {
                                date = day
                            }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = Muted, modifier = Modifier.size(13.dp))
                        Text("  आने वाली तारीखें उसी दिन खुलेंगी।", fontSize = 12.5.sp, color = Muted)
                    }
                }
            }

            // President's message
            leaderMsg?.let { msg ->
                item(key = "leader") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp)) {
                        SectionTitle("अध्यक्ष जी का संदेश")
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)
                                .border(1.dp, Line, RoundedCornerShape(20.dp)).clickable { nav.messages() }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LeaderThumb(92)
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("माननीय DPCC अध्यक्ष", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = OrangeText,
                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(OrangeBg).padding(horizontal = 7.dp, vertical = 1.dp))
                                    if (!msg.optBoolean("seen")) Text("  नया", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE5402F))
                                }
                                Text(msg.optString("title"), fontSize = 15.5.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 21.sp,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                                Text("${hiDate(msg.str("published_at"))} · देखने के लिए टैप करें", fontSize = 12.5.sp, color = Muted,
                                    modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }

            // Tasks for the selected day
            item(key = "tasks") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp)) {
                    SectionTitle(if (selected == today || selected.isBlank()) "आज के टास्क" else "${hiDate(selected).substringBeforeLast(' ')} के टास्क")
                    CardBox {
                        if (uploadTasks.isEmpty()) {
                            Text("इस दिन के लिए अभी कोई टास्क नहीं है।", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp, horizontal = 16.dp))
                        } else {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
                                Row {
                                    Text("$doneCount / ${uploadTasks.size} टास्क पूरे", fontSize = 13.sp, color = Muted, modifier = Modifier.weight(1f))
                                    Text("${(doneCount * 100) / uploadTasks.size}%", fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Bold)
                                }
                                LinearProgressIndicator(
                                    progress = { doneCount.toFloat() / uploadTasks.size },
                                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(7.dp).clip(RoundedCornerShape(9.dp)),
                                    color = Orange, trackColor = Color(0xFFEDF0F5),
                                )
                            }
                            uploadTasks.forEach { t ->
                                HorizontalDivider(color = Color(0xFFF0F2F6))
                                TaskRow(t, perVideo = PTS_TASK) { onTask(t, shareTasks) }
                            }
                        }
                    }
                }
            }

            // Leader's posts for the day, from links the admin added to share cards
            item(key = "social") {
                Column(Modifier.padding(top = 26.dp)) {
                    SectionTitle("देवेन्द्र यादव जी के पोस्ट", Modifier.padding(horizontal = 16.dp))
                    SocialPosts(shareTasks, d.optJSONObject("social"), perShare = PTS_SHARE) { reload++ }
                }
            }

            item(key = "footer") { AppFooter(helpline = d.str("helpline")) }
        }
    }
    }
}

internal fun Nav.copyWithHome(home: () -> Unit) = Nav(home, messages, notifications, progress, unread)

@Composable
internal fun GlassButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x660E1B3D))
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(12.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(19.dp)) }
}

@Composable
internal fun StatCell(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, fontSize = 12.sp, color = Muted)
    }
}

@Composable
internal fun DateChip(day: String, isToday: Boolean, isSelected: Boolean, isFuture: Boolean, hasTasks: Boolean, onClick: () -> Unit) {
    val c = parseYmd(day)
    val weekday = c?.let { HI_WEEKDAYS[it.get(Calendar.DAY_OF_WEEK) - 1] } ?: ""
    val num = c?.get(Calendar.DAY_OF_MONTH)?.toString() ?: day
    val shape = RoundedCornerShape(16.dp)
    val bg = when { isSelected -> Navy; isFuture -> Color(0xFFEEF0F4); else -> Color.White }
    val borderColor = when { isSelected -> Navy; isToday -> Orange; else -> Line }
    val fg = when { isSelected -> Color.White; isFuture -> Color(0xFFA7AFC0); else -> Ink }
    Column(
        Modifier.size(width = 54.dp, height = 74.dp).clip(shape).background(bg)
            .border(if (isToday && !isSelected) 2.dp else 1.dp, borderColor, shape)
            .clickable(enabled = !isFuture) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(weekday, fontSize = 11.5.sp, color = if (isSelected) Color(0xFFAFC0E3) else if (isFuture) Color(0xFFB3BACA) else Muted)
        Text(num, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = fg)
        Box(Modifier.height(14.dp), contentAlignment = Alignment.Center) {
            when {
                isToday -> Text("आज", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isSelected) Color(0xFFFFB877) else Orange)
                isFuture -> Icon(Icons.Filled.Lock, "बंद", tint = Color(0xFFA7AFC0), modifier = Modifier.size(11.dp))
                hasTasks -> Box(Modifier.size(5.dp).clip(CircleShape).background(if (isSelected) Color(0xFFFFB877) else Orange))
                else -> {}
            }
        }
    }
}

@Composable
internal fun TaskRow(t: JSONObject, perVideo: Int, onClick: () -> Unit) {
    val done = t.optBoolean("done")
    val videos = t.optInt("videos")
    val slots = NEED_VIDEOS
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (done) IconTile(Icons.Filled.Check, GreenBg, Green) else IconTile(Icons.Filled.Videocam, OrangeBg, Orange)
        Column(Modifier.padding(start = 12.dp, end = 8.dp).weight(1f)) {
            Text(t.optString("title"), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 19.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("+$perVideo अंक", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Green, maxLines = 1)
                Text(" · $videos/$NEED_VIDEOS वीडियो · ${t.optInt("photos")}/$NEED_PHOTOS फोटो", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (done) {
            Row(
                Modifier.clip(RoundedCornerShape(11.dp)).background(GreenBg).padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, null, tint = Green, modifier = Modifier.size(15.dp))
                Text(" पूरा", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = GreenText)
            }
        } else {
            Text(
                "टास्क चेक करें", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Navy).clickable { onClick() }.padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }
    }
}

internal data class Platform(val key: String, val label: String, val color: Color)
internal val PLATFORMS = listOf(Platform("x", "X", Color(0xFF111111)), Platform("facebook", "Facebook", Color(0xFF1877F2)), Platform("instagram", "Instagram", Color(0xFFC13584)))

/**
 * Post cards for Facebook, X and Instagram. Each card comes from an admin share card (title, description, links).
 * The first open per post and platform is recorded by the server and earns points.
 */
@Composable
internal fun SocialPosts(shareTasks: List<JSONObject>, social: JSONObject?, perShare: Int, onShared: () -> Unit) {
    val ctx = LocalContext.current
    val firstWithPosts = PLATFORMS.firstOrNull { p -> shareTasks.any { it.str("link_${p.key}") != null } }?.key ?: "facebook"
    var platform by remember(shareTasks) { mutableStateOf(firstWithPosts) }
    val p = PLATFORMS.first { it.key == platform }
    val posts = shareTasks.filter { it.str("link_${p.key}") != null }

    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PLATFORMS.forEach { item ->
            val on = item.key == platform
            Text(
                item.label, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = if (on) Color.White else Navy,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) Navy else Color.Transparent)
                    .clickable { platform = item.key }.padding(vertical = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))

    if (posts.isEmpty()) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
                .border(1.dp, Line, RoundedCornerShape(18.dp)).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("इस दिन ${p.label} की कोई पोस्ट नहीं है।", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            social?.str(p.key)?.let { url ->
                TextButton(onClick = { Social.open(ctx, p.key, url) }) { Text("${p.label} प्रोफ़ाइल देखें", color = Blue, fontWeight = FontWeight.Bold) }
            }
        }
        return
    }

    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(posts, key = { p.key + it.optLong("id") }) { t ->
            PostCard(t, p, perShare, Modifier.fillParentMaxWidth(if (posts.size > 1) 0.86f else 1f), onShared)
        }
    }
}

@Composable
internal fun PostCard(t: JSONObject, p: Platform, perShare: Int, modifier: Modifier, onShared: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val taskId = t.optLong("id")
    val url = t.str("link_${p.key}") ?: return
    val wa = t.str("link_whatsapp")
    var shared by remember(taskId) {
        mutableStateOf(t.optJSONArray("shared")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet())
    }

    fun record(platform: String, action: String) {
        scope.launch {
            try {
                val r = Api.call(ctx, "share", JSONObject().put("task_id", taskId).put("platform", platform).put("share_action", action))
                if (r.optInt("earned") > 0) {
                    Toast.makeText(ctx, "+${r.optInt("earned")} अंक मिले", Toast.LENGTH_SHORT).show()
                    onShared()
                }
                shared = shared + platform
            } catch (e: Exception) {
            }
        }
    }

    val isTweet = p.key == "x" && Social.tweetId(url) != null
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(18.dp))) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Navy)) {
                Image(
                    painterResource(R.drawable.home_banner), contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = 2.2f; scaleY = 2.2f
                        transformOrigin = TransformOrigin(0.71f, 0.25f)
                        translationX = (0.5f - 0.71f) * size.width
                        translationY = (0.45f - 0.25f) * size.height
                    },
                    contentScale = ContentScale.Crop, alignment = BiasAlignment(0.55f, -1f),
                )
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("Devender Yadav", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("${p.label} पोस्ट", fontSize = 11.5.sp, color = Muted)
            }
            if (p.key in shared) Row(
                Modifier.clip(RoundedCornerShape(50)).background(GreenBg).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, null, tint = Green, modifier = Modifier.size(13.dp))
                Text(" शेयर किया", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GreenText)
            } else Text("+$perShare अंक", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = Green,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(GreenBg).padding(horizontal = 8.dp, vertical = 3.dp))
        }
        Text(t.optString("title"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink, lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        t.str("description")?.let {
            Text(it, fontSize = 13.sp, color = Color(0xFF3A4560), lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp))
        }
        // Preview panel: tapping it opens the real post
        Box(
            Modifier.padding(12.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Navy, NavySoft, p.color.copy(alpha = 0.85f))))
                .clickable { Social.open(ctx, p.key, url); record(p.key, "open") },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Text("${p.label} पर पोस्ट देखें", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                Text(url.removePrefix("https://").removePrefix("www.").take(38), color = Color(0xFFC9D4EC), fontSize = 11.sp, maxLines = 1)
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isTweet) {
                OutlinedButton(
                    onClick = { Social.open(ctx, "x", url, "like"); record("x", "like") },
                    modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Line),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Icon(Icons.Filled.ThumbUp, null, tint = Ink, modifier = Modifier.size(16.dp)); Text("  लाइक", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { Social.open(ctx, "x", url, "retweet"); record("x", "retweet") },
                    modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange), contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Icon(Icons.Filled.Repeat, null, modifier = Modifier.size(16.dp)); Text("  रीट्वीट", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            } else {
                OutlinedButton(
                    onClick = { Social.open(ctx, p.key, url); record(p.key, "open") },
                    modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, Line),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Ink, modifier = Modifier.size(16.dp)); Text("  पोस्ट खोलें", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        if (wa != null) { Social.open(ctx, "whatsapp", wa, "share"); record("whatsapp", "share") }
                        else { Social.open(ctx, p.key, url); record(p.key, "open") }
                    },
                    modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Orange), contentPadding = PaddingValues(horizontal = 4.dp),
                ) { Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp)); Text(if (wa != null) "  WhatsApp" else "  शेयर करें", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BottomBar(selected: String, nav: Nav) {
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp, modifier = Modifier.border(BorderStroke(1.dp, Line))) {
        val colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Orange, selectedTextColor = Navy, indicatorColor = OrangeBg,
            unselectedIconColor = Color(0xFF7C86A0), unselectedTextColor = Color(0xFF7C86A0),
        )
        NavigationBarItem(selected = selected == "home", onClick = nav.home, colors = colors,
            icon = { Icon(Icons.Filled.Home, null) }, label = { Text("होम", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) })
        NavigationBarItem(selected = selected == "messages", onClick = nav.messages, colors = colors,
            icon = { Icon(Icons.Filled.Videocam, null) }, label = { Text("संदेश", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) })
        NavigationBarItem(selected = selected == "notifications", onClick = nav.notifications, colors = colors,
            icon = {
                BadgedBox(badge = { if (nav.unread) Badge(containerColor = Color(0xFFE5402F)) }) {
                    Icon(Icons.Filled.Notifications, null)
                }
            },
            label = { Text("सूचनाएँ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) })
        NavigationBarItem(selected = selected == "progress", onClick = nav.progress, colors = colors,
            icon = { Icon(Icons.Filled.EmojiEvents, null) }, label = { Text("मेरे अंक", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) })
    }
}

@Composable
internal fun AppFooter(helpline: String?) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().padding(top = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider(color = Line)
        Image(
            painterResource(R.drawable.logo), contentDescription = null,
            modifier = Modifier.padding(top = 24.dp).size(44.dp).clip(CircleShape).background(Color.White).border(1.dp, Line, CircleShape),
            contentScale = ContentScale.Fit,
        )
        Text(APP_NAME, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, letterSpacing = 0.4.sp, modifier = Modifier.padding(top = 8.dp))
        Text("दिल्ली प्रदेश कांग्रेस कमेटी", fontSize = 13.sp, color = Muted)
        Row(
            Modifier.padding(top = 10.dp, bottom = 26.dp).clip(RoundedCornerShape(50)).background(Color.White)
                .border(1.dp, Line, RoundedCornerShape(50))
                .clickable(enabled = helpline != null) { helpline?.let { openLink(ctx, "tel:$it") } }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Phone, null, tint = Muted, modifier = Modifier.size(14.dp))
            Text("  हेल्पलाइन: ${helpline ?: "जल्द उपलब्ध"}", fontSize = 12.5.sp, color = if (helpline != null) Blue else Muted)
        }
    }
}

internal val FbBlue = Color(0xFF1877F2)
internal val IgPink = Color(0xFFC13584)
internal val WaGreen = Color(0xFF128C7E)

// Points per action. The server sends the live values in points_rule; these are only fallbacks.
internal const val PTS_TASK = 100      // a task done in full: 2 photos and 2 videos
internal const val PTS_SHARE = 100     // per post and platform
internal const val PTS_OWN_TASK = 100  // own task, once the admin approves
internal const val NEED_VIDEOS = 2
internal const val NEED_PHOTOS = 2

/** One post on one platform that the member can still share. */
internal data class ShareItem(val taskId: Long, val caption: String, val platform: String, val url: String, val action: String)

/** Every (post, platform) pair in these share cards that the member has not shared yet. */
internal fun pendingShares(tasks: List<JSONObject>): List<ShareItem> = tasks.flatMap { t ->
    val shared = t.optJSONArray("shared")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
    listOf("facebook", "x", "instagram", "whatsapp").mapNotNull { p ->
        val url = t.str("link_$p") ?: return@mapNotNull null
        if (p in shared) return@mapNotNull null
        val action = when {
            p == "x" && Social.tweetId(url) != null -> "retweet"
            p == "whatsapp" -> "share"
            else -> "open"
        }
        ShareItem(t.optLong("id"), t.optString("title"), p, url, action)
    }
}

/** Share buttons in a 2-column grid, each with the post's caption. */
@Composable
internal fun ShareGrid(items: List<ShareItem>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tapped by remember { mutableStateOf(setOf<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                row.forEach { item ->
                    val key = "${item.taskId}:${item.platform}"
                    val done = key in tapped
                    val (label, color, icon) = when (item.platform) {
                        "facebook" -> Triple("Facebook", FbBlue, R.drawable.ic_facebook)
                        "instagram" -> Triple("Instagram", IgPink, R.drawable.ic_instagram)
                        "x" -> Triple(if (item.action == "retweet") "रीट्वीट" else "X", Ink, R.drawable.ic_x)
                        else -> Triple("WhatsApp", WaGreen, null)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Ground)
                            .border(1.dp, Line, RoundedCornerShape(12.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(item.caption, fontSize = 12.sp, color = Color(0xFF3A4560), lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Button(
                            onClick = {
                                Social.open(ctx, item.platform, item.url, item.action)
                                scope.launch {
                                    try {
                                        val r = Api.call(ctx, "share", JSONObject().put("task_id", item.taskId).put("platform", item.platform).put("share_action", item.action))
                                        if (r.optInt("earned") > 0) Toast.makeText(ctx, "+${r.optInt("earned")} अंक मिले", Toast.LENGTH_SHORT).show()
                                        tapped = tapped + key
                                    } catch (e: Exception) {}
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (done) Green else color),
                            contentPadding = PaddingValues(horizontal = 6.dp), shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(42.dp),
                        ) {
                            when {
                                done -> Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                                icon != null -> Icon(painterResource(icon), null, modifier = Modifier.size(16.dp))
                                else -> Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp))
                            }
                            Text(if (done) " शेयर किया" else " $label", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}


// ---------------------------------------------------------------- Task + uploads

@Composable
internal fun TaskScreen(initial: JSONObject, shareTasks: List<JSONObject>, onBack: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val taskId = initial.getLong("id")
    val slots = NEED_VIDEOS
    var videos by remember { mutableIntStateOf(initial.optInt("videos")) }
    var photos by remember { mutableIntStateOf(initial.optInt("photos")) }
    var done by remember { mutableStateOf(initial.optBoolean("done")) }
    // What the screen is doing right now. Exactly one of: idle, uploading a file, or submitting the task.
    var phase by remember { mutableStateOf<TaskPhase>(TaskPhase.Idle) }
    val uploading = phase as? TaskPhase.Uploading
    val uploadingKind = uploading?.kind
    val progress = uploading?.progress
    val photoBatch = uploading?.batch
    val submitting = phase is TaskPhase.Submitting
    fun setProgress(v: Float) { (phase as? TaskPhase.Uploading)?.let { phase = it.copy(progress = v) } }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var earned by remember { mutableIntStateOf(0) }
    var freshShares by remember { mutableStateOf(shareTasks) }
    var perShare by remember { mutableIntStateOf(PTS_SHARE) }
    var totalPoints by remember { mutableIntStateOf(-1) }
    val busy = phase !is TaskPhase.Idle

    // Keep screen awake while uploading so Android OS does not suspend network
    DisposableEffect(busy) {
        val window = (ctx as? Activity)?.window
        if (busy) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler {
        if (busy) Toast.makeText(ctx, "अपलोड पूरा होने दें", Toast.LENGTH_SHORT).show() else onBack()
    }

    /** Uploads one file and attaches it to the task. Returns false if it should stop a batch. */
    suspend fun uploadOne(uri: Uri, kind: String): Boolean {
        val mime = ctx.contentResolver.getType(uri) ?: if (kind == "video") "video/mp4" else "image/jpeg"
        val size = Api.fileSize(ctx, uri)
        val maxBytes = 50L * 1024 * 1024 // 50 MB limit for Supabase Storage
        if (size > maxBytes) {
            val mb = (size / (1024 * 1024)).toInt()
            Toast.makeText(ctx, "यह फाइल $mb MB की है। 50 MB से छोटी फाइल चुनें।", Toast.LENGTH_LONG).show()
            return true
        }
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: if (kind == "video") "mp4" else "jpg"
        setProgress(0f)
            try {
                val u = Api.call(ctx, "upload_url", JSONObject().put("task_id", taskId).put("kind", kind).put("ext", ext).put("size", size))
                var lastErr: Exception? = null
                var ok = false
                for (attempt in 1..3) {
                    try {
                        Api.upload(ctx, u.getString("signed_url"), uri, mime) { p -> setProgress(p) }
                        ok = true; break
                    } catch (e: Exception) { lastErr = e; setProgress(0f) }
                }
                if (!ok) throw lastErr ?: Exception("upload")
                val r = Api.call(ctx, "attach", JSONObject().put("task_id", taskId).put("kind", kind).put("path", u.getString("path")).put("size", size))
                r.optJSONObject("task")?.let { videos = it.optInt("videos"); photos = it.optInt("photos") }
                return true
            } catch (e: ApiError) {
                val detail = e.body.optString("detail", "")
                when {
                    e.code == "unauthorized" -> { onLogout(); return false }
                    e.code == "not_open_yet" -> Toast.makeText(ctx, "यह टास्क अपनी तारीख पर खुलेगा", Toast.LENGTH_LONG).show()
                    e.code == "limit_reached" -> {
                        Toast.makeText(ctx, if (kind == "video") "इस टास्क के सभी वीडियो जुड़ चुके हैं" else "इस टास्क में $NEED_PHOTOS फोटो जुड़ चुकी हैं", Toast.LENGTH_LONG).show()
                        return false
                    }
                    e.code == "file_too_large" || detail.contains("EntityTooLarge", ignoreCase = true) || detail.contains("Payload too large", ignoreCase = true) -> {
                        Toast.makeText(ctx, "फाइल साइज 50 MB से बड़ा है। छोटा वीडियो चुनें।", Toast.LENGTH_LONG).show()
                    }
                    e.code.startsWith("upload_failed_") -> {
                        val status = e.code.removePrefix("upload_failed_")
                        Toast.makeText(ctx, "अपलोड नहीं हो पाया (कोड $status)। एक बार फिर कोशिश करें।", Toast.LENGTH_LONG).show()
                    }
                    else -> Toast.makeText(ctx, "अपलोड नहीं हुआ (${e.code})", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "इंटरनेट चेक करें और फिर कोशिश करें (${e.message ?: "error"})", Toast.LENGTH_LONG).show()
            }
            return false
    }

    /** Takes off the last uploaded photo or video so the right one can be put in. */
    fun removeLast(kind: String) {
        phase = TaskPhase.Removing
        scope.launch {
            try {
                Api.call(ctx, "remove_media", JSONObject().put("task_id", taskId).put("kind", kind)).optJSONObject("task")
                    ?.let { videos = it.optInt("videos"); photos = it.optInt("photos"); done = false }
            } catch (e: Exception) {
                Toast.makeText(ctx, "हट नहीं पाया, एक बार फिर कोशिश करें", Toast.LENGTH_SHORT).show()
            }
            phase = TaskPhase.Idle
        }
    }

    fun upload(uri: Uri, kind: String) {
        phase = TaskPhase.Uploading(kind)
        scope.launch { uploadOne(uri, kind); phase = TaskPhase.Idle }
    }

    fun uploadPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = (NEED_PHOTOS - photos).coerceAtLeast(0)
        val list = uris.take(room)
        if (list.isEmpty()) { Toast.makeText(ctx, "इस टास्क में $NEED_PHOTOS फोटो जुड़ चुकी हैं", Toast.LENGTH_LONG).show(); return }
        if (uris.size > room) Toast.makeText(ctx, "सिर्फ़ $room और फोटो जुड़ सकती हैं, पहली $room जोड़ी जा रही हैं", Toast.LENGTH_LONG).show()
        phase = TaskPhase.Uploading("photo")
        scope.launch {
            for ((i, u) in list.withIndex()) {
                phase = TaskPhase.Uploading("photo", batch = "${i + 1}/${list.size}")
                if (!uploadOne(u, "photo")) break
            }
            phase = TaskPhase.Idle
        }
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { upload(it, "video") } }
    // Several photos can be picked at once
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> uploadPhotos(uris) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!busy) onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "वापस") }
            Text("टास्क", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = Line)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(initial.optString("title"), fontSize = 19.sp, fontWeight = FontWeight.Bold)
            val desc = initial.optString("description")
            if (desc.isNotBlank() && desc != "null") Text(desc, fontSize = 14.sp, color = Color(0xFF3B4250))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("आज रात 11:59 तक", OrangeBg, OrangeText)
                Chip("2 फोटो + 2 वीडियो · +$PTS_TASK अंक", Ground, Color(0xFF3B4250))
            }

            Text("फोटो और वीडियो", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))

            // Videos
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ground).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row { Text("वीडियो", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("$videos / $slots", fontWeight = FontWeight.Bold) }
                for (i in 0 until slots) {
                    when {
                        i < videos -> SlotRow("वीडियो ${i + 1}", "अपलोड पूरा", GreenText, filled = true, onRemove = if (i == videos - 1 && !busy) ({ removeLast("video") }) else null) {}
                        i == videos && uploadingKind == "video" -> UploadingRow("वीडियो ${i + 1}", progress ?: 0f)
                        else -> SlotRow("वीडियो ${i + 1}", "गैलरी से वीडियो चुनें", Blue, filled = false, enabled = !busy && i == videos) {
                            pickVideo.launch("video/*")
                        }
                    }
                }
            }

            // Photos
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ground).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row { Text("फोटो", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("$photos / $NEED_PHOTOS", fontWeight = FontWeight.Bold) }
                for (i in 0 until NEED_PHOTOS) {
                    when {
                        i < photos -> SlotRow("फोटो ${i + 1}", "अपलोड पूरा", GreenText, filled = true, onRemove = if (i == photos - 1 && !busy) ({ removeLast("photo") }) else null) {}
                        i == photos && uploadingKind == "photo" -> UploadingRow(photoBatch?.let { "फोटो $it" } ?: "फोटो ${i + 1}", progress ?: 0f)
                        else -> SlotRow("फोटो ${i + 1}", "गैलरी से फोटो चुनें (एक साथ दोनों भी चुन सकते हैं)", Blue, filled = false, enabled = !busy && i == photos) {
                            pickPhoto.launch("image/*")
                        }
                    }
                }
            }
        }

        // Tick / Submit
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (videos < NEED_VIDEOS || photos < NEED_PHOTOS) {
                Text("2 फोटो और 2 वीडियो अपलोड होने के बाद जमा कर सकते हैं (+$PTS_TASK अंक)", fontSize = 13.sp, color = OrangeText, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    phase = TaskPhase.Submitting
                    scope.launch {
                        try {
                            val r = Api.call(ctx, "complete", JSONObject().put("task_id", taskId))
                            earned = r.optInt("earned")
                            totalPoints = r.optJSONObject("stats")?.optInt("points", -1) ?: -1
                            try {
                                val h = Api.call(ctx, "home", JSONObject().put("date", initial.optString("task_date")))
                                val a = h.optJSONArray("tasks") ?: JSONArray()
                                freshShares = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("kind") == "share" }
                                perShare = PTS_SHARE
                            } catch (e: Exception) {}
                            done = true
                            showSuccessDialog = true
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "जमा नहीं हुआ, फिर कोशिश करें", Toast.LENGTH_LONG).show()
                        }
                        phase = TaskPhase.Idle
                    }
                },
                enabled = videos >= NEED_VIDEOS && photos >= NEED_PHOTOS && !busy && !submitting,
                colors = ButtonDefaults.buttonColors(containerColor = if (done) Green else Blue),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (submitting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("जमा हो रहा है…", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Filled.Check, null)
                    Text(if (done) "  अपडेट सुरक्षित करें" else "  टिक करें और जमा करें", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = {
                showSuccessDialog = false
                onBack()
            },
            icon = {
                Box(
                    modifier = Modifier.size(60.dp).clip(CircleShape).background(Color(0xFFE8F5E9)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Green,
                        modifier = Modifier.size(40.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "टास्क जमा हो गया",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "आपकी फोटो और वीडियो हम तक पहुँच गई हैं। धन्यवाद!",
                        fontSize = 14.sp,
                        color = Muted,
                        textAlign = TextAlign.Center
                    )
                    if (earned > 0 || totalPoints >= 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFFEF3C7)).padding(10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Star, null, tint = Gold, modifier = Modifier.size(20.dp))
                            Text(
                                when {
                                    earned > 0 && totalPoints >= 0 -> " +$earned अंक मिले · कुल ${fmtNum(totalPoints)}"
                                    earned > 0 -> " +$earned अंक मिले"
                                    else -> " कुल अंक: ${fmtNum(totalPoints)}"
                                },
                                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Ground)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Videocam, null, tint = Ink, modifier = Modifier.size(17.dp))
                        Text(" $videos वीडियो", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        if (photos > 0) {
                            Text("   •   ", color = Muted)
                            Icon(Icons.Filled.PhotoCamera, null, tint = Ink, modifier = Modifier.size(16.dp))
                            Text(" $photos फोटो", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                    }
                    // Only posts this member has not shared yet; once shared they don't come back after the next upload.
                    val pending = remember(freshShares) { pendingShares(freshShares) }
                    if (pending.isNotEmpty()) {
                        HorizontalDivider(color = Line)
                        Text("अब यह कंटेंट शेयर करें (हर शेयर +$perShare अंक)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.fillMaxWidth())
                        ShareGrid(pending)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSuccessDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("ठीक है (होम पेज पर जाएं)", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(18.dp),
            containerColor = Color.White
        )
    }
}

@Composable
internal fun Chip(text: String, bg: Color, fg: Color) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
internal fun SlotRow(title: String, sub: String, subColor: Color, filled: Boolean, enabled: Boolean = true, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White)
            .then(if (!filled) Modifier.border(BorderStroke(1.5.dp, if (enabled) Color(0xFF8AABDD) else Line), RoundedCornerShape(10.dp)) else Modifier)
            .clickable(enabled = enabled && !filled) { onClick() }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(if (filled) Color(0xFF1E2433) else Color(0xFFE8EEF8)),
            contentAlignment = Alignment.Center,
        ) { Icon(if (filled) Icons.Filled.PlayArrow else Icons.Filled.Add, null, tint = if (filled) Color.White else Blue) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled || filled) Ink else Color(0xFFB0B5BE))
            Text(sub, fontSize = 12.sp, color = if (enabled || filled) subColor else Color(0xFFB0B5BE))
        }
        if (filled && onRemove != null) Box(
            Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFFDE8E8)).clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "हटाएं", tint = Color(0xFF9B1C1C), modifier = Modifier.size(18.dp)) }
    }
}

@Composable
internal fun UploadingRow(title: String, p: Float) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp)) {
        Text("$title अपलोड हो रहा है… ${(p * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { p }, modifier = Modifier.padding(top = 6.dp).fillMaxWidth())
        Text("स्क्रीन और ऐप खुला रखें (अपलोड जारी है)", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------------------------------------------------------- Notifications

@Composable
internal fun NotificationsScreen(data: JSONObject, nav: Nav, onTask: (JSONObject, List<JSONObject>) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    BackHandler { nav.home() }
    val update = data.optJSONObject("app_update")
    val serverVersionCode = update?.optInt("version_code", 1) ?: 1
    val isUpdateAvailable = serverVersionCode > BuildConfig.VERSION_CODE
    val versionName = update?.optString("version_name").orEmpty().ifBlank { "" }
    val updateNotes = update?.optString("notes").orEmpty().ifBlank { "नया वर्शन उपलब्ध है।" }
    val downloadUrl = update?.optString("download_url").orEmpty().ifBlank { "https://ward-sadasya.vercel.app/download.html" }

    val announcements = data.optJSONArray("announcements") ?: JSONArray()
    val leader = data.optJSONObject("leader")
    val tasks = data.optJSONArray("tasks") ?: JSONArray()
    val isToday = data.optString("date") == data.optString("today")
    val pendingUploads = if (!isToday) emptyList() else (0 until tasks.length())
        .map { tasks.getJSONObject(it) }
        .filter { it.optString("kind") == "upload" && !it.optBoolean("done") }

    Scaffold(containerColor = Ground, bottomBar = { BottomBar("notifications", nav) }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            PageTop("सूचनाएँ", nav.home)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isUpdateAvailable) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFEFF4FF))
                                .border(1.5.dp, Color(0xFFC9D8F5), RoundedCornerShape(16.dp)).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(Icons.Filled.SystemUpdate, Color(0xFFDDE7FB), Blue, 40)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text("नया ऐप अपडेट उपलब्ध है", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Navy)
                                    if (versionName.isNotBlank()) Text("वर्शन v$versionName (Build $serverVersionCode)", fontSize = 12.sp, color = Muted)
                                }
                            }
                            Text(updateNotes, fontSize = 13.sp, color = Ink, lineHeight = 19.sp)
                            Button(
                                onClick = { openLink(ctx, downloadUrl) },
                                colors = ButtonDefaults.buttonColors(containerColor = Navy),
                                shape = RoundedCornerShape(11.dp),
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                            ) { Text("अभी नया वर्शन डाउनलोड करें", fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        }
                    }

                    if (leader != null) {
                        val seen = leader.optBoolean("seen")
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                                .border(1.dp, if (!seen) Color(0xFFF6C9A0) else Line, RoundedCornerShape(16.dp))
                                .clickable { nav.messages() }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LeaderThumb(64, 26)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("देवेन्द्र यादव जी का संदेश", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
                                    if (!seen) Text(" नया", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE5402F))
                                }
                                Text(leader.optString("title"), fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hiDate(leader.str("published_at")), fontSize = 11.5.sp, color = Muted)
                            }
                        }
                    }

                    // Each pending task opens that task directly
                    val shareCards = (0 until tasks.length()).map { tasks.getJSONObject(it) }.filter { it.optString("kind") == "share" }
                    pendingUploads.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                                .border(1.dp, Color(0xFFF6C9A0), RoundedCornerShape(16.dp)).clickable { onTask(t, shareCards) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconTile(Icons.Filled.Videocam, OrangeBg, Orange, 40)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text("आज का नया टास्क", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OrangeText)
                                Text(t.optString("title"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("2 फोटो + 2 वीडियो · आज रात 11:59 तक", fontSize = 12.sp, color = Muted)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
                        }
                    }

                    if (announcements.length() > 0) {
                        Text("पार्टी घोषणाएँ", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
                        for (i in 0 until announcements.length()) {
                            val a = announcements.getJSONObject(i)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                                    .border(1.dp, Line, RoundedCornerShape(16.dp)).clickable { nav.home() }.padding(14.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                IconTile(Icons.Filled.Campaign, OrangeBg, Orange, 40)
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    a.str("title")?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink) }
                                    Text(a.optString("body"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink, lineHeight = 20.sp)
                                    val created = hiDate(a.str("created_at"))
                                    if (created.isNotBlank()) Text(created, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }

                    if (!isUpdateAvailable && leader == null && announcements.length() == 0 && pendingUploads.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Notifications, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(56.dp))
                            Text("अभी कोई नई सूचना नहीं है।", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                AppFooter(helpline = data.str("helpline"))
            }
        }
    }
}

// ---------------------------------------------------------------- Leader messages

@Composable
internal fun MessagesScreen(nav: Nav, helpline: String?, social: JSONObject?, dpccSocial: JSONObject?) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var current by remember { mutableStateOf<JSONObject?>(null) }
    var seen by remember { mutableStateOf(setOf<Long>()) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    BackHandler { nav.home() }
    LaunchedEffect(Unit) {
        try {
            val r = Api.call(ctx, "messages").optJSONArray("messages") ?: JSONArray()
            val l = (0 until r.length()).map { r.getJSONObject(it) } // newest first, as sent by the server
            list = l; current = l.firstOrNull()
            seen = l.filter { it.optBoolean("seen") }.map { it.getLong("id") }.toSet()
        } catch (e: Exception) { list = emptyList() }
    }

    Scaffold(containerColor = Ground, bottomBar = { BottomBar("messages", nav) }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            PageTop("अध्यक्ष जी का संदेश", nav.home)
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                current?.let { m ->
                    key(m.getLong("id")) {
                        AndroidView(
                            factory = { c ->
                                VideoView(c).apply {
                                    val mc = MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                                    setVideoURI(Uri.parse(m.optString("url")))
                                    setOnPreparedListener { start() }
                                    setOnErrorListener { _, _, _ ->
                                        Toast.makeText(c, "वीडियो लोड नहीं हो सका", Toast.LENGTH_SHORT).show()
                                        true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
                            onRelease = { it.stopPlayback() },
                        )
                    }
                    LaunchedEffect(m.getLong("id")) {
                        val id = m.getLong("id")
                        if (id !in seen) {
                            try { Api.call(ctx, "message_seen", JSONObject().put("id", id)); seen = seen + id } catch (e: Exception) {}
                        }
                    }
                    Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) {
                        Text(hiDate(m.str("published_at")), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Orange)
                        // The admin's caption, shown exactly as written
                        Text(m.optString("title"), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Ink, lineHeight = 27.sp, modifier = Modifier.padding(top = 4.dp))
                        Text(
                            m.str("summary") ?: "माननीय DPCC अध्यक्ष देवेन्द्र यादव जी का सभी वार्ड सदस्यों के लिए संदेश।",
                            fontSize = 14.5.sp, color = Color(0xFF3A4560), lineHeight = 24.sp, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    HorizontalDivider(color = Line)
                }
                val l = list
                when {
                    l == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
                    l.isEmpty() -> Text("अभी कोई संदेश नहीं है।", color = Muted, modifier = Modifier.padding(20.dp))
                    l.size > 1 -> {
                        Text("पिछले संदेश", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 10.dp))
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Every message except the one playing, still newest first
                            l.filter { it.getLong("id") != current?.getLong("id") }.forEach { m ->
                                val isAudio = m.optString("kind") == "audio"
                                val wasSeen = m.getLong("id") in seen
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                                        .border(1.dp, Line, RoundedCornerShape(16.dp))
                                        .clickable { current = m; scope.launch { scroll.animateScrollTo(0) } }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    LeaderThumb(64, 26)
                                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                        Text(m.optString("title"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${hiDate(m.str("published_at"))} · ${if (isAudio) "ऑडियो" else "वीडियो"}", fontSize = 12.sp, color = Muted)
                                    }
                                    Text(
                                        if (wasSeen) (if (isAudio) "सुना" else "देखा") else "बाकी",
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (wasSeen) GreenText else OrangeText,
                                    )
                                }
                            }
                        }
                    }
                }
                FollowLeaderAndDpcc(social, dpccSocial)
                AppFooter(helpline = helpline)
            }
        }
    }
}

// ---------------------------------------------------------------- Points

@Composable
internal fun ProgressScreen(data: JSONObject?, onData: (JSONObject) -> Unit, nav: Nav, onProfile: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { nav.home() }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) {
        try { profile = Api.call(ctx, "profile", JSONObject().put("lite", true)) } catch (e: Exception) {}
    }
    val profileStatus = profile?.optJSONObject("latest")?.optString("status")
    val shownName = profile?.optJSONObject("approved")?.str("full_name") ?: data?.optJSONObject("member")?.str("name")
    // Totals always come fresh from the server.
    LaunchedEffect(Unit) {
        try {
            val p = JSONObject(); data?.str("date")?.let { p.put("date", it) }
            onData(Api.call(ctx, "home", p))
        } catch (e: Exception) {}
    }
    val stats = data?.optJSONObject("stats") ?: JSONObject()
    val rule = data?.optJSONObject("points_rule") ?: JSONObject()
    // Final scheme: 100 for every share (per post, per platform), every completed task and every approved own task.
    val perShare = PTS_SHARE
    val perTask = PTS_TASK
    val perOwn = PTS_OWN_TASK
    var ranks by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { try { ranks = Api.call(ctx, "ranks").optJSONObject("ranks") } catch (e: Exception) {} }

    Scaffold(containerColor = Ground, bottomBar = { BottomBar("progress", nav) }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            PageTop("मेरे अंक", nav.home)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF22396F), Navy))).padding(horizontal = 18.dp, vertical = 22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfilePhoto(profile?.str("photo_url"), 66, onProfile)
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            shownName?.let { Text("${it.trim()} Ji", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Text("कुल अंक", color = Color(0xFFAFC0E3), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                            Text(fmtNum(stats.optInt("points")), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 44.sp)
                        }
                        Icon(Icons.Filled.EmojiEvents, null, tint = Gold, modifier = Modifier.size(34.dp))
                    }
                    // Anonymous rank: no names, only the member's own position
                    ranks?.let { rk ->
                        Text("मेरी रैंकिंग", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(start = 2.dp, top = 20.dp, bottom = 10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("ward" to "वार्ड में", "assembly" to "विधानसभा में", "district" to "ज़िले में", "delhi" to "दिल्ली में").forEach { (k, label) ->
                                val o = rk.optJSONObject(k)
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(o?.let { "${it.optInt("rank")}" } ?: "–", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Navy)
                                    Text(o?.let { "/ ${fmtNum(it.optInt("total"))}" } ?: "", fontSize = 11.sp, color = Muted)
                                    Text(label, fontSize = 11.5.sp, color = Ink, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    // 1. The rules
                    Text("आपके अंक कैसे गिने जाते हैं?", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(start = 2.dp, top = 22.dp, bottom = 10.dp))
                    CardBox {
                        RuleRow(Icons.Filled.Share, Color(0xFFE8F0FE), Blue, "सोशल मीडिया शेयर (हर पोस्ट, हर प्लेटफ़ॉर्म)", perShare)
                        HorizontalDivider(color = Color(0xFFF0F2F6))
                        RuleRow(Icons.Filled.Videocam, OrangeBg, Orange, "टास्क पूरे करने के अंक (2 फोटो + 2 वीडियो)", perTask)
                        HorizontalDivider(color = Color(0xFFF0F2F6))
                        RuleRow(Icons.Filled.AddTask, GreenBg, Green, "आपके द्वारा बनाए गए टास्क (मंज़ूरी के बाद)", perOwn)
                    }
                    Row(Modifier.padding(top = 10.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Info, null, tint = Muted, modifier = Modifier.size(14.dp))
                        Text("  सब मिलाकर एक ही कुल अंक बनते हैं।", fontSize = 12.5.sp, color = Muted)
                    }

                    // 2. What this member has done so far
                    val shares = stats.optInt("shares"); val fullTasks = stats.optInt("full_tasks")
                    val ownApproved = stats.optInt("own_tasks"); val ownPending = stats.optInt("own_pending")
                    val ownTotal = maxOf(stats.optInt("own_total"), ownApproved + ownPending)
                    val joined = parseYmd(data?.optJSONObject("member")?.optString("first_login_at").orEmpty())
                    val days = joined?.let { ((System.currentTimeMillis() - it.timeInMillis) / 86_400_000L).toInt() + 1 }
                    Text("अब तक आपका काम", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(start = 2.dp, top = 22.dp, bottom = 10.dp))
                    CardBox {
                        days?.let {
                            DoneRow("ऐप से जुड़े हुए", "$it दिन", null)
                            HorizontalDivider(color = Color(0xFFF0F2F6))
                        }
                        DoneRow("सोशल मीडिया शेयर", "$shares शेयर", shares * perShare)
                        HorizontalDivider(color = Color(0xFFF0F2F6))
                        DoneRow("पूरे किए गए टास्क", "$fullTasks टास्क", fullTasks * perTask)
                        HorizontalDivider(color = Color(0xFFF0F2F6))
                        DoneRow(
                            "आपके बनाए टास्क",
                            "$ownTotal बनाए · $ownApproved मंज़ूर" + if (ownPending > 0) " · $ownPending की मंज़ूरी बाकी" else "",
                            ownApproved * perOwn,
                        )
                        if (ownPending > 0) Text("बाकी $ownPending टास्क के अंक मंज़ूरी मिलते ही जुड़ जाएंगे।", fontSize = 12.sp, color = OrangeText,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                    }
                    // Profile
                    Text("मेरी प्रोफ़ाइल", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Ink, modifier = Modifier.padding(start = 2.dp, top = 22.dp, bottom = 10.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)
                            .border(1.dp, Line, RoundedCornerShape(20.dp)).clickable { onProfile() }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(Icons.Filled.Person, Color(0xFFE8F0FE), Blue)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("प्रोफ़ाइल एडिट करें", fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                            Text(
                                when (profileStatus) {
                                    "pending" -> "मंज़ूरी का इंतज़ार है"
                                    "approved" -> "मंज़ूर हो चुकी है"
                                    "rejected" -> "मंज़ूर नहीं हुई, फिर से भेजें"
                                    else -> "फोटो, नाम, वार्ड, परिवार और मोबाइल नंबर जोड़ें"
                                },
                                fontSize = 12.sp, color = if (profileStatus == "rejected") Color(0xFF9B1C1C) else Muted,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
                    }
                }
                AppFooter(helpline = data?.str("helpline"))
            }
        }
    }
}

@Composable
internal fun PointsRow(icon: ImageVector, bg: Color, fg: Color, label: String, count: Int, each: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, bg, fg)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("हर एक पर $each अंक", fontSize = 12.sp, color = Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtNum(count * each), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Text("$each × $count", fontSize = 11.5.sp, color = Muted)
        }
    }
}

// ---------------------------------------------------------------- Follow Devender Yadav ji

/** Facebook, X and Instagram follow buttons under a heading. Links are set in admin Settings. */
@Composable
internal fun FollowSection(heading: AnnotatedString, social: JSONObject?) {
    val ctx = LocalContext.current
    val items = listOf(
        Triple("facebook", "Facebook", R.drawable.ic_facebook),
        Triple("x", "X", R.drawable.ic_x),
        Triple("instagram", "Instagram", R.drawable.ic_instagram),
    ).mapNotNull { (key, label, icon) -> social?.str(key)?.let { url -> Triple(label, icon, key to url) } }
    if (items.isEmpty()) return
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Color.White).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(heading, fontSize = 15.sp, color = Ink, textAlign = TextAlign.Center, lineHeight = 22.sp)
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items.forEach { (label, icon, link) ->
                val (key, url) = link
                val bg = when (key) {
                    "facebook" -> Brush.linearGradient(listOf(Color(0xFF1877F2), Color(0xFF1877F2)))
                    "x" -> Brush.linearGradient(listOf(Color(0xFF111111), Color(0xFF111111)))
                    else -> Brush.linearGradient(listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5)))
                }
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable { Social.open(ctx, key, url) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(54.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
internal fun FollowLeaderAndDpcc(leader: JSONObject?, dpcc: JSONObject?) {
    Spacer(Modifier.height(6.dp))
    FollowSection(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("माननीय DPCC अध्यक्ष देवेन्द्र यादव जी") }
            append(" के सोशल मीडिया हैंडल्स को फॉलो करें")
        },
        leader,
    )
    FollowSection(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append("दिल्ली प्रदेश कांग्रेस कमेटी") }
            append(" के सोशल मीडिया हैंडल्स को फॉलो करें")
        },
        dpcc,
    )
}

// ---------------------------------------------------------------- Profile

/** Round profile photo, or a person icon when there is none. */
@Composable
internal fun ProfilePhoto(model: Any?, size: Int, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(0x22FFFFFF)).border(2.dp, Color.White, CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) AsyncImage(model = model, contentDescription = "प्रोफ़ाइल फोटो", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Crops the picked image to a centred square, scales it to 640 px and saves it as a JPEG in the cache. */
internal fun squarePhoto(ctx: Context, uri: Uri): File? = try {
    val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    if (src == null) null else {
        // Respect the camera's rotation so faces are upright.
        val rotation = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) { 0 }
        val upright = if (rotation != 0) Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation.toFloat()) }, true) else src
        val side = minOf(upright.width, upright.height)
        val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, 640, 640, true)
        val out = File(ctx.cacheDir, "profile-${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out
    }
} catch (e: Exception) { null }

/** Field that opens a full list to pick from, with search when the list is long. */
@Composable
internal fun PickField(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = options.firstOrNull { it.first == value }?.second ?: value
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true,
        )
        // Transparent layer so the whole field opens the list.
        Box(Modifier.matchParentSize().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable { open = true })
    }
    if (open) PickerDialog(label, value, options, onDismiss = { open = false }) { onPick(it); open = false }
}

@Composable
internal fun PickerDialog(title: String, value: String, options: List<Pair<String, String>>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val list = remember(query, options) { if (query.isBlank()) options else options.filter { it.second.contains(query.trim(), ignoreCase = true) } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(Color.White)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$title चुनें", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "बंद करें", tint = Muted) }
            }
            if (options.size > 8) OutlinedTextField(
                query, { query = it }, placeholder = { Text("खोजें") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(12.dp),
            )
            HorizontalDivider(color = Line, modifier = Modifier.padding(top = 6.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(list, key = { it.first }) { (v, text) ->
                    val on = v == value
                    Row(
                        Modifier.fillMaxWidth().background(if (on) OrangeBg else Color.Transparent).clickable { onPick(v) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, color = Ink, modifier = Modifier.weight(1f))
                        if (on) Icon(Icons.Filled.Check, null, tint = Orange, modifier = Modifier.size(20.dp))
                    }
                    HorizontalDivider(color = Color(0xFFF0F2F6))
                }
                if (list.isEmpty()) item { Text("कुछ नहीं मिला", color = Muted, modifier = Modifier.padding(20.dp)) }
            }
        }
    }
}

@Composable
internal fun ProfileScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    var loaded by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var ward by remember { mutableStateOf("") }
    var assembly by remember { mutableStateOf("") }
    var lokSabha by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var family by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var altMobile by remember { mutableStateOf("") }
    var photoUrl by remember { mutableStateOf<String?>(null) }
    var newPhoto by remember { mutableStateOf<File?>(null) }
    var saving by remember { mutableStateOf(false) }
    var dob by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var married by remember { mutableStateOf<Boolean?>(null) }
    var anniversary by remember { mutableStateOf("") }
    var linkFb by remember { mutableStateOf("") }
    var linkIg by remember { mutableStateOf("") }
    var linkX by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val r = Api.call(ctx, "profile")
            // Prefill from the latest request, else the approved profile, else what the admin uploaded.
            val src = r.optJSONObject("latest") ?: r.optJSONObject("approved") ?: r.optJSONObject("defaults") ?: JSONObject()
            val def = r.optJSONObject("defaults") ?: JSONObject()
            fun pick(k: String) = src.str(k) ?: def.str(k) ?: ""
            name = pick("full_name"); details = src.str("details") ?: ""
            ward = pick("ward_no"); assembly = pick("assembly"); lokSabha = pick("lok_sabha")
            district = pick("district"); family = src.str("family_members") ?: ""
            mobile = pick("contact_mobile"); altMobile = src.str("alt_mobile") ?: ""
            dob = src.str("dob") ?: ""; gender = src.str("gender") ?: ""
            married = if (src.has("married") && !src.isNull("married")) src.optBoolean("married") else null
            anniversary = src.str("anniversary") ?: ""
            linkFb = src.str("link_facebook") ?: ""; linkIg = src.str("link_instagram") ?: ""; linkX = src.str("link_x") ?: ""
            photoUrl = r.str("photo_url")
            loaded = r
        } catch (e: ApiError) {
            if (e.code == "unauthorized") onLogout() else error = "प्रोफ़ाइल लोड नहीं हुई।"
        } catch (e: Exception) { error = "इंटरनेट चेक करें।" }
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch { newPhoto = withContext(Dispatchers.IO) { squarePhoto(ctx, it) } ?: run { Toast.makeText(ctx, "फोटो नहीं खुल सकी", Toast.LENGTH_SHORT).show(); null } } }
    }

    fun save() {
        if (name.isBlank()) { Toast.makeText(ctx, "पूरा नाम लिखें", Toast.LENGTH_SHORT).show(); return }
        if (dob.isBlank()) { Toast.makeText(ctx, "जन्म तिथि चुनें", Toast.LENGTH_SHORT).show(); return }
        if (gender.isBlank()) { Toast.makeText(ctx, "लिंग चुनें", Toast.LENGTH_SHORT).show(); return }
        if (married == null) { Toast.makeText(ctx, "विवाहित / अविवाहित चुनें", Toast.LENGTH_SHORT).show(); return }
        if (married == true && anniversary.isBlank()) { Toast.makeText(ctx, "शादी की सालगिरह की तारीख चुनें", Toast.LENGTH_SHORT).show(); return }
        if (mobile.isNotBlank() && mobile.length != 10) { Toast.makeText(ctx, "मोबाइल नंबर 10 अंकों का हो", Toast.LENGTH_SHORT).show(); return }
        if (altMobile.isNotBlank() && altMobile.length != 10) { Toast.makeText(ctx, "वैकल्पिक मोबाइल नंबर 10 अंकों का हो", Toast.LENGTH_SHORT).show(); return }
        saving = true
        scope.launch {
            try {
                var photoPath: String? = null
                newPhoto?.let { file ->
                    val u = Api.call(ctx, "profile_photo_url", JSONObject().put("ext", "jpg").put("size", file.length()))
                    Api.upload(ctx, u.getString("signed_url"), Uri.fromFile(file), "image/jpeg") {}
                    photoPath = u.getString("path")
                }
                val p = JSONObject()
                    .put("full_name", name.trim()).put("details", details.trim())
                    .put("ward_no", ward).put("assembly", assembly).put("lok_sabha", lokSabha).put("district", district)
                    .put("family_members", family).put("contact_mobile", mobile).put("alt_mobile", altMobile)
                    .put("dob", dob).put("gender", gender).put("married", married == true).put("anniversary", if (married == true) anniversary else JSONObject.NULL)
                    .put("link_facebook", linkFb.trim()).put("link_instagram", linkIg.trim()).put("link_x", linkX.trim())
                photoPath?.let { p.put("photo_path", it) }
                Api.call(ctx, "profile_save", p)
                Toast.makeText(ctx, "जानकारी भेज दी गई। एडमिन की मंज़ूरी के बाद जुड़ जाएगी।", Toast.LENGTH_LONG).show()
                onBack()
            } catch (e: ApiError) {
                when (e.code) {
                    "unauthorized" -> onLogout()
                    "bad_mobile", "bad_alt_mobile" -> Toast.makeText(ctx, "मोबाइल नंबर सही नहीं है", Toast.LENGTH_LONG).show()
                    "dob_required", "gender_required", "married_required", "anniversary_required" -> Toast.makeText(ctx, "जन्म तिथि, लिंग और विवाह की जानकारी ज़रूरी है", Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(ctx, "सेव नहीं हुआ (${e.code})", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "इंटरनेट चेक करें और फिर कोशिश करें", Toast.LENGTH_LONG).show()
            }
            saving = false
        }
    }

    Column(Modifier.fillMaxSize().background(Ground)) {
        PageTop("प्रोफ़ाइल एडिट करें", onBack)
        val r = loaded
        if (r == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!, color = OrangeText) else CircularProgressIndicator(color = Orange)
            }
            return@Column
        }
        val opts = r.optJSONObject("options") ?: JSONObject()
        fun list(k: String) = opts.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val wards = opts.optJSONArray("wards")?.let { a ->
            (0 until a.length()).map { i -> val w = a.getJSONObject(i); val no = w.optInt("no"); no.toString() to ("वार्ड $no" + (w.str("name")?.let { " · $it" } ?: "")) }
        } ?: (1..250).map { it.toString() to "वार्ड $it" }
        val status = r.optJSONObject("latest")?.optString("status")
        // The area master from the server (w = ward, a = assembly, d = district, l = Lok Sabha). Empty on an older server.
        val tree = remember(r) { opts.optJSONArray("tree")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList() }
        val lokOptions = list("lok_sabha").ifEmpty { DELHI_LOK_SABHA }
        val districtOptions = if (tree.isEmpty()) list("districts") else tree.filter { lokSabha.isBlank() || it.optString("l") == lokSabha }.map { it.optString("d") }.distinct().sorted()
        val assemblyOptions = if (tree.isEmpty()) list("assemblies").ifEmpty { DELHI_ASSEMBLIES } else tree.filter { (district.isBlank() || it.optString("d") == district) && (lokSabha.isBlank() || it.optString("l") == lokSabha) }.map { it.optString("a") }.distinct().sorted()
        val wardsInAssembly = tree.filter { assembly.isNotBlank() && it.optString("a") == assembly }.map { it.optInt("w").toString() }.toSet()
        val wardOptions = if (wardsInAssembly.isEmpty()) wards else wards.filter { it.first in wardsInAssembly }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (status) {
                "pending" -> Chip("आपकी पिछली जानकारी मंज़ूरी के लिए भेजी जा चुकी है। बदलाव करके फिर भेज सकते हैं।", OrangeBg, OrangeText)
                "approved" -> Chip("आपकी प्रोफ़ाइल मंज़ूर हो चुकी है।", GreenBg, GreenText)
                "rejected" -> Chip("पिछली जानकारी मंज़ूर नहीं हुई। सही करके फिर भेजें।", Color(0xFFFDE8E8), Color(0xFF9B1C1C))
            }
            // Photo
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Navy).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfilePhoto(newPhoto ?: photoUrl, 84) { pickPhoto.launch("image/*") }
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text("प्रोफ़ाइल फोटो", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { pickPhoto.launch("image/*") }, modifier = Modifier.padding(top = 8.dp).height(38.dp),
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Color(0x55FFFFFF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Icon(Icons.Filled.PhotoCamera, null, modifier = Modifier.size(16.dp)); Text(if (newPhoto != null || photoUrl != null) "  फोटो बदलें" else "  फोटो चुनें", fontSize = 13.sp) }
                }
            }

            OutlinedTextField(name, { name = it.take(80) }, label = { Text("पूरा नाम *") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            DateField("जन्म तिथि *", dob) { dob = it }
            ChoiceRow("लिंग *", listOf("male" to "पुरुष", "female" to "महिला", "other" to "अन्य"), gender) { gender = it }
            ChoiceRow("वैवाहिक स्थिति *", listOf("yes" to "विवाहित", "no" to "अविवाहित"), when (married) { true -> "yes"; false -> "no"; else -> "" }) { married = it == "yes" }
            if (married == true) DateField("शादी की सालगिरह / विवाह वर्षगांठ *", anniversary) { anniversary = it }
            OutlinedTextField(family, { v -> family = v.filter { it.isDigit() }.take(2) }, label = { Text("परिवार में सदस्यों की संख्या") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            // Area, top down: Lok Sabha > Congress organisational district > Vidhan Sabha > ward. Each list narrows the next.
            PickField("लोकसभा क्षेत्र", lokSabha, lokOptions.map { it to it }) { lokSabha = it; if (tree.isNotEmpty()) { district = ""; assembly = ""; ward = "" } }
            PickField("कांग्रेस संगठनात्मक ज़िला", district, districtOptions.map { it to it }) { district = it; if (tree.isNotEmpty()) { assembly = ""; ward = "" } }
            PickField("विधानसभा", assembly, assemblyOptions.map { it to it }) { assembly = it; if (tree.isNotEmpty()) ward = "" }
            PickField("वार्ड", ward, wardOptions) { ward = it }
            OutlinedTextField(details, { details = it.take(500) }, label = { Text("अपने बारे में (पद, काम आदि)") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(mobile, { v -> mobile = v.filter { it.isDigit() }.take(10) }, label = { Text("मोबाइल नंबर") }, singleLine = true, prefix = { Text("+91 ") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(altMobile, { v -> altMobile = v.filter { it.isDigit() }.take(10) }, label = { Text("वैकल्पिक मोबाइल नंबर") }, singleLine = true, prefix = { Text("+91 ") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Text("आपके सोशल मीडिया अकाउंट्स के लिंक", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(linkFb, { linkFb = it.take(300) }, label = { Text("Facebook प्रोफ़ाइल लिंक") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(linkIg, { linkIg = it.take(300) }, label = { Text("Instagram प्रोफ़ाइल लिंक") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(linkX, { linkX = it.take(300) }, label = { Text("X (Twitter) प्रोफ़ाइल लिंक") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Text("आपका लॉगिन नंबर नहीं बदलेगा। यह जानकारी एडमिन की मंज़ूरी के बाद आपकी प्रोफ़ाइल में दिखने लगेगी।", fontSize = 12.sp, color = Muted, lineHeight = 17.sp)
        }
        Button(
            onClick = { save() }, enabled = !saving,
            modifier = Modifier.padding(16.dp).fillMaxWidth().height(54.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Navy),
        ) {
            if (saving) { CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp); Text("  भेजा जा रहा है…", fontWeight = FontWeight.Bold) }
            else Text("सेव करें और मंज़ूरी के लिए भेजें", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Date picked with the phone's calendar, stored as YYYY-MM-DD. */
@Composable
internal fun DateField(label: String, value: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (value.isBlank()) "" else hiDate(value), onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, null) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        )
        Box(Modifier.matchParentSize().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable {
            val c = parseYmd(value) ?: Calendar.getInstance().apply { add(Calendar.YEAR, -30) }
            android.app.DatePickerDialog(ctx, { _, y, m, d ->
                onPick(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        })
    }
}

/** A row of choices, one can be picked. */
@Composable
internal fun ChoiceRow(label: String, options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, color = Muted, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (k, text) ->
                val on = k == value
                Text(
                    text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    color = if (on) Color.White else Ink,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) Navy else Color.White)
                        .border(1.dp, if (on) Navy else Line, RoundedCornerShape(10.dp)).clickable { onPick(k) }.padding(vertical = 11.dp),
                )
            }
        }
    }
}

/** Delhi's 70 Vidhan Sabha seats (same list the server sends). */
internal val DELHI_ASSEMBLIES = listOf("Narela", "Burari", "Timarpur", "Adarsh Nagar", "Badli", "Rithala", "Bawana", "Mundka", "Kirari",
    "Sultanpur Majra", "Nangloi Jat", "Mangol Puri", "Rohini", "Shalimar Bagh", "Shakur Basti", "Tri Nagar", "Wazirpur",
    "Model Town", "Sadar Bazar", "Chandni Chowk", "Matia Mahal", "Ballimaran", "Karol Bagh", "Patel Nagar", "Moti Nagar",
    "Madipur", "Rajouri Garden", "Hari Nagar", "Tilak Nagar", "Janakpuri", "Vikaspuri", "Uttam Nagar", "Dwarka", "Matiala",
    "Najafgarh", "Bijwasan", "Palam", "Delhi Cantt", "Rajinder Nagar", "New Delhi", "Jangpura", "Kasturba Nagar",
    "Malviya Nagar", "R K Puram", "Mehrauli", "Chhatarpur", "Deoli", "Ambedkar Nagar", "Sangam Vihar", "Greater Kailash",
    "Kalkaji", "Tughlakabad", "Badarpur", "Okhla", "Trilokpuri", "Kondli", "Patparganj", "Laxmi Nagar", "Vishwas Nagar",
    "Krishna Nagar", "Gandhi Nagar", "Shahdara", "Seemapuri", "Rohtas Nagar", "Seelampur", "Ghonda", "Babarpur",
    "Gokalpur", "Mustafabad", "Karawal Nagar").mapIndexed { i, n -> "${i + 1} - $n" }

internal val DELHI_LOK_SABHA = listOf(
    "चांदनी चौक (Chandni Chowk)", "उत्तर पूर्वी दिल्ली (North East Delhi)", "पूर्वी दिल्ली (East Delhi)", "नई दिल्ली (New Delhi)",
    "उत्तर पश्चिम दिल्ली (North West Delhi)", "पश्चिमी दिल्ली (West Delhi)", "दक्षिणी दिल्ली (South Delhi)",
)

/** One line of the points rules: what earns points and how many. */
@Composable
internal fun RuleRow(icon: ImageVector, bg: Color, fg: Color, label: String, each: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, bg, fg)
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.padding(start = 12.dp, end = 8.dp).weight(1f))
        Text("$each अंक", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Green)
    }
}

/** One line of the member's own tally: what, how many, and the points it earned. */
@Composable
internal fun DoneRow(label: String, count: String, points: Int?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(count, fontSize = 12.5.sp, color = Muted)
        }
        points?.let { Text("${fmtNum(it)} अंक", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink) }
    }
}
