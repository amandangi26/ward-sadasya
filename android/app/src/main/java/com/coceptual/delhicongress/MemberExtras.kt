package com.coceptual.delhicongress

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

// ---------------------------------------------------------------- Posters and banners

@Composable
internal fun PostersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onBack() }
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(Unit) {
        list = try { Api.call(ctx, "posters").optJSONArray("posters").objects() } catch (e: Exception) { emptyList() }
    }
    Column(Modifier.fillMaxSize().background(Ground)) {
        PageTop("पोस्टर और बैनर", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("पार्टी के कार्यक्रम से संबंधित पोस्टर और बैनर यहाँ से डाउनलोड करें।", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text("डाउनलोड करें या WhatsApp पर अपने इलाके के प्रिंटर को भेजें।", fontSize = 13.sp, color = Muted)
            val l = list
            when {
                l == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
                l.isEmpty() -> Text("अभी कोई पोस्टर नहीं है।", color = Muted, modifier = Modifier.padding(20.dp))
                else -> l.forEach { p ->
                    val url = p.optString("url"); val title = p.optString("title")
                    val isImage = p.optString("mime").startsWith("image/")
                    CardBox {
                        if (isImage) AsyncImage(model = url, contentDescription = title, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit)
                        else Box(Modifier.fillMaxWidth().height(120.dp).background(Ground), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PictureAsPdf, null, tint = Navy, modifier = Modifier.size(42.dp)) }
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { download(ctx, url, title, p.optString("path")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) {
                                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(16.dp)); Text(" डाउनलोड")
                                }
                                Button(onClick = { shareToWhatsApp(ctx, "$title\n$url") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF128C7E))) {
                                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(16.dp)); Text(" WhatsApp")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Saves the file to the phone's Downloads folder. */
private fun download(ctx: Context, url: String, title: String, path: String) {
    try {
        val name = (title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "poster" }) + "." + path.substringAfterLast('.', "jpg")
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        Toast.makeText(ctx, "डाउनलोड शुरू हो गया। फ़ोन के Downloads में मिलेगा।", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        openLink(ctx, url)
    }
}

private fun shareToWhatsApp(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try {
        ctx.startActivity(Intent(send).setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        ctx.startActivity(Intent.createChooser(send, "भेजें").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------------------------------------------------------------- Inbox (one way)

internal val INBOX_KIND = mapOf("kudos" to "बधाई संदेश", "motivate" to "प्रेरणा संदेश", "personal" to "व्यक्तिगत संदेश", "broadcast" to "संदेश", "notice" to "सूचना")

@Composable
internal fun InboxScreen(onBack: () -> Unit, leader: Boolean = false) {
    val ctx = LocalContext.current
    BackHandler { onBack() }
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    LaunchedEffect(Unit) {
        list = try {
            (if (leader) Api.leader(ctx, "inbox") else Api.call(ctx, "inbox")).optJSONArray("messages").objects()
        } catch (e: Exception) { emptyList() }
    }
    Column(Modifier.fillMaxSize().background(Ground)) {
        PageTop("मेरा इनबॉक्स", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val l = list
            when {
                l == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
                l.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.MarkEmailRead, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(56.dp))
                    Text("अभी कोई संदेश नहीं है।", color = Muted, modifier = Modifier.padding(top = 8.dp))
                }
                else -> l.forEach { m ->
                    val kind = m.optString("kind")
                    val personal = kind != "broadcast" && kind != "notice"
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                            .border(if (personal) 1.5.dp else 1.dp, if (personal) Color(0xFFF6C9A0) else Line, RoundedCornerShape(16.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(INBOX_KIND[kind] ?: "संदेश", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = OrangeText,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(OrangeBg).padding(horizontal = 7.dp, vertical = 2.dp))
                            Text("  ${hiDate(m.str("created_at"))}", fontSize = 12.sp, color = Muted)
                        }
                        Text("${m.optString("from_name")} की ओर से", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Navy)
                        if (personal) Text("यह संदेश सिर्फ़ और सिर्फ़ आपके लिए है।", fontSize = 12.sp, color = Muted)
                        m.str("text")?.let { Text(it, fontSize = 15.sp, color = Ink, lineHeight = 22.sp) }
                        m.str("audio_url")?.let { AudioPlayer(it) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Own tasks

@Composable
internal fun OwnTasksScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    var title by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(listOf<String>()) }
    var videos by remember { mutableStateOf(listOf<String>()) }
    var uploading by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { mine = try { Api.call(ctx, "own_tasks").optJSONArray("tasks").objects() } catch (e: Exception) { emptyList() } }

    fun add(uris: List<Uri>, kind: String) {
        val room = 2 - (if (kind == "photo") photos.size else videos.size)
        val list = uris.take(room.coerceAtLeast(0))
        if (list.isEmpty()) return
        scope.launch {
            for (u in list) {
                uploading = if (kind == "photo") "फोटो" else "वीडियो"
                try {
                    val path = uploadUri(ctx, false, u, kind)
                    if (kind == "photo") photos = photos + path else videos = videos + path
                } catch (e: ApiError) {
                    if (e.code == "unauthorized") { onLogout(); return@launch }
                    Toast.makeText(ctx, if (e.code == "file_too_large") "फाइल साइज बहुत बड़ा है (500 MB तक समर्थित)" else "अपलोड नहीं हुआ", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "इंटरनेट चेक करें", Toast.LENGTH_LONG).show()
                }
            }
            uploading = null
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { add(it, "photo") }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { add(it, "video") }

    Column(Modifier.fillMaxSize().background(Ground)) {
        PageTop("अपना टास्क जोड़ें", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("आप अपने इलाके में खुद से भी कार्यक्रम कर सकते हैं", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Text("जैसे डोर टू डोर प्रचार, खराब सड़क पर वीडियो, पानी की गुणवत्ता की जांच, लोगों से मिलना या कोई मिलन समारोह। जो भी कार्यक्रम आप करें, उसे यहाँ जोड़ें। 2 फोटो और 2 वीडियो ज़रूरी हैं। एडमिन की मंज़ूरी के बाद +$PTS_OWN_TASK अंक मिलेंगे।",
                fontSize = 13.sp, color = Muted, lineHeight = 19.sp)
            OutlinedTextField(title, { title = it.take(160) }, label = { Text("आपने क्या किया? *") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(details, { details = it.take(1000) }, label = { Text("थोड़ा और बताएं (कब, कहाँ, कितने लोग)") }, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            listOf("photo" to photos, "video" to videos).forEach { (kind, have) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(if (kind == "photo") Icons.Filled.PhotoCamera else Icons.Filled.Videocam, if (have.size >= 2) GreenBg else OrangeBg, if (have.size >= 2) Green else Orange, 40)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(if (kind == "photo") "फोटो" else "वीडियो", fontWeight = FontWeight.Bold, color = Ink)
                        Text("${have.size} / 2 जुड़े", fontSize = 12.sp, color = if (have.size >= 2) GreenText else Muted)
                    }
                    OutlinedButton(
                        onClick = { if (kind == "photo") pickPhotos.launch("image/*") else pickVideos.launch("video/*") },
                        enabled = have.size < 2 && uploading == null, shape = RoundedCornerShape(10.dp),
                    ) { Text(if (have.size >= 2) "पूरा" else "चुनें") }
                }
            }
            uploading?.let { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Orange); Text("  $it अपलोड हो रहा है…", fontSize = 13.sp) } }
            Button(
                onClick = {
                    if (title.isBlank()) { Toast.makeText(ctx, "बताएं आपने क्या किया", Toast.LENGTH_SHORT).show(); return@Button }
                    sending = true
                    scope.launch {
                        try {
                            val media = JSONArray()
                            photos.forEach { media.put(JSONObject().put("kind", "photo").put("path", it)) }
                            videos.forEach { media.put(JSONObject().put("kind", "video").put("path", it)) }
                            Api.call(ctx, "own_task_submit", JSONObject().put("title", title.trim()).put("description", details.trim()).put("media", media))
                            Toast.makeText(ctx, "भेज दिया। मंज़ूरी के बाद अंक जुड़ेंगे।", Toast.LENGTH_LONG).show()
                            title = ""; details = ""; photos = emptyList(); videos = emptyList(); reload++
                        } catch (e: ApiError) {
                            Toast.makeText(ctx, if (e.code == "need_more") "2 फोटो और 2 वीडियो ज़रूरी हैं" else "भेजा नहीं जा सका", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) { Toast.makeText(ctx, "इंटरनेट चेक करें", Toast.LENGTH_LONG).show() }
                        sending = false
                    }
                },
                enabled = photos.size >= 2 && videos.size >= 2 && uploading == null && !sending,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
            ) { Text(if (sending) "भेजा जा रहा है…" else "मंज़ूरी के लिए भेजें", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

            if (mine.isNotEmpty()) {
                Text("मेरे भेजे टास्क", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Muted, modifier = Modifier.padding(top = 10.dp))
                mine.forEach { t ->
                    val st = t.optString("status")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.optString("title"), fontWeight = FontWeight.SemiBold, color = Ink)
                            Text(hiDate(t.str("created_at")), fontSize = 12.sp, color = Muted)
                        }
                        Chip(when (st) { "approved" -> "मंज़ूर · +$PTS_OWN_TASK"; "rejected" -> "मंज़ूर नहीं"; else -> "इंतज़ार" },
                            when (st) { "approved" -> GreenBg; "rejected" -> Color(0xFFFDE8E8); else -> OrangeBg },
                            when (st) { "approved" -> GreenText; "rejected" -> Color(0xFF9B1C1C); else -> OrangeText })
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Message to the DPCC President

@Composable
internal fun ToPresidentScreen(leader: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }
    var text by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf<File?>(null) }
    var sending by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(listOf<Pair<String, Uri>>()) }
    var status by remember { mutableStateOf("") }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us -> attach = (attach + us.map { "photo" to it }).take(6) }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us -> attach = (attach + us.map { "video" to it }).take(6) }
    Column(Modifier.fillMaxSize().background(Ground)) {
        PageTop("अध्यक्ष जी को संदेश", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Navy).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LeaderThumb(60, 0)
                Column(Modifier.padding(start = 14.dp)) {
                    Row(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Orange).padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text(" गोपनीय", color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("अगर आपको कोई चिंता है या आप माननीय DPCC अध्यक्ष देवेन्द्र यादव जी को निजी तौर पर कोई संदेश देना चाहते हैं, तो इस सुविधा का प्रयोग करें।",
                        color = Color.White, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            OutlinedTextField(text, { text = it.take(2000) }, label = { Text("अपना संदेश लिखें") }, minLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            VoiceNote(voice) { voice = it }
            val guard = rememberSendGuard()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickPhotos.launch("image/*") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.PhotoCamera, null); Text(" फोटो जोड़ें") }
                OutlinedButton(onClick = { pickVideos.launch("video/*") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Videocam, null); Text(" वीडियो जोड़ें") }
            }
            if (attach.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${attach.count { it.first == "photo" }} फोटो, ${attach.count { it.first == "video" }} वीडियो जुड़े", fontSize = 13.sp, color = GreenText, modifier = Modifier.weight(1f))
                TextButton(onClick = { attach = emptyList() }) { Text("हटाएं", color = Color(0xFF9B1C1C)) }
            }
            if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = Blue)
            Button(
                onClick = { guard {
                    if (text.isBlank() && voice == null && attach.isEmpty()) { Toast.makeText(ctx, "संदेश लिखें या वॉइस नोट रिकॉर्ड करें", Toast.LENGTH_SHORT).show(); return@guard }
                    sending = true
                    scope.launch {
                        try {
                            var audio: String? = null
                            voice?.let { audio = uploadFile(ctx, leader, it, "audio", "audio/mp4", purpose = "president") }
                            val media = JSONArray()
                            attach.forEachIndexed { i, (kind, uri) ->
                                status = "अपलोड हो रहा है ${i + 1}/${attach.size}"
                                media.put(JSONObject().put("kind", kind).put("path", uploadUri(ctx, leader, uri, kind, purpose = "president")))
                            }
                            val p = JSONObject().put("text", text.trim()).put("media", media); audio?.let { p.put("audio_path", it) }
                            if (leader) Api.leader(ctx, "to_president", p) else Api.call(ctx, "president_message", p)
                            Toast.makeText(ctx, "आपका संदेश अध्यक्ष जी तक भेज दिया गया।", Toast.LENGTH_LONG).show()
                            onBack()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "भेजा नहीं जा सका, इंटरनेट चेक करें", Toast.LENGTH_LONG).show()
                        }
                        sending = false; status = ""
                    }
                } },
                enabled = !sending, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Navy),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                Text(if (sending) "  भेजा जा रहा है…" else "  अध्यक्ष जी को भेजें", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
