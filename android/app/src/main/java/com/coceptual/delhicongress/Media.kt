package com.coceptual.delhicongress

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.abedelazizshe.lightcompressorlibrary.config.AppSpecificStorageConfiguration
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

internal val ROLE_HI = mapOf(
    "president" to "माननीय अध्यक्ष, DPCC",
    "gs" to "महासचिव",
    "dc" to "ज़िला कोऑर्डिनेटर",
    "dp" to "ज़िला अध्यक्ष",
    "observer" to "विधानसभा ऑब्ज़र्वर",
    "member" to "वार्ड सदस्य",
)

/** The three states a voice note can be in. Exactly one holds at a time. */
private sealed interface Voice {
    data object Idle : Voice
    data class Recording(val recorder: MediaRecorder, val file: File) : Voice
}

/**
 * The recording in progress on screen, if any. Send buttons check this through [rememberSendGuard],
 * so a message can't go out while the microphone is still on (the voice note would be missing).
 */
internal object ActiveRecording {
    var stop: (() -> Unit)? by mutableStateOf(null)
    val isOn: Boolean get() = stop != null
}

/**
 * Wraps a send action. If a voice note is still recording, the action does not run;
 * a small prompt asks to stop the recording first.
 */
@Composable
internal fun rememberSendGuard(): (action: () -> Unit) -> Unit {
    var ask by remember { mutableStateOf(false) }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            icon = { Icon(Icons.Filled.Mic, null, tint = Color(0xFFE5402F)) },
            title = { Text("रिकॉर्डिंग अभी चल रही है", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
            text = { Text("भेजने से पहले रिकॉर्डिंग रोकें, तभी आपका वॉइस नोट साथ जाएगा।", fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                Button(onClick = { ActiveRecording.stop?.invoke(); ask = false }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5402F))) {
                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp)); Text(" रिकॉर्डिंग रोकें")
                }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("वापस") } },
        )
    }
    return { action -> if (ActiveRecording.isOn) ask = true else action() }
}

/** Records a voice note (m4a). Shows record / stop / play / remove. */
@Composable
internal fun VoiceNote(file: File?, onChange: (File?) -> Unit) {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf<Voice>(Voice.Idle) }
    var seconds by remember { mutableIntStateOf(0) }

    fun stop() {
        val rec = state as? Voice.Recording ?: return
        val ok = try { rec.recorder.stop(); true } catch (_: Exception) { false }
        rec.recorder.release()
        state = Voice.Idle
        ActiveRecording.stop = null
        if (ok) {
            onChange(rec.file)
            Toast.makeText(ctx, "रिकॉर्डिंग सेव हो गई", Toast.LENGTH_SHORT).show()
        } else {
            onChange(null)
            Toast.makeText(ctx, "रिकॉर्डिंग बहुत छोटी थी, दोबारा करें", Toast.LENGTH_SHORT).show()
        }
    }
    fun start() {
        val f = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            seconds = 0
            state = Voice.Recording(r, f)
            ActiveRecording.stop = { stop() }
            Toast.makeText(ctx, "रिकॉर्डिंग शुरू हो गई है", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "रिकॉर्डिंग शुरू नहीं हो सकी", Toast.LENGTH_SHORT).show()
        }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else Toast.makeText(ctx, "वॉइस नोट के लिए माइक की अनुमति दें", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(state) {
        while (state is Voice.Recording) { delay(1000); seconds++; if (seconds >= 300) stop() }
    }
    // Leaving the screen mid-recording: stop quietly and free the microphone.
    DisposableEffect(Unit) {
        onDispose {
            (state as? Voice.Recording)?.let { try { it.recorder.stop() } catch (_: Exception) {}; it.recorder.release() }
            ActiveRecording.stop = null
        }
    }

    val recording = state is Voice.Recording
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (recording) Color(0xFFFDECEA) else Color(0xFFF2F4F8))
            .border(1.dp, if (recording) Color(0xFFF3B4AC) else Color(0xFFE3E7EF), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            recording -> {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE5402F)))
                Text("  रिकॉर्ड हो रहा है  ${seconds / 60}:${"%02d".format(seconds % 60)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(onClick = { stop() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5402F)), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(18.dp)); Text(" रोकें")
                }
            }
            file != null -> {
                AudioPlayer(file.absolutePath, Modifier.weight(1f))
                TextButton(onClick = { onChange(null) }) { Text("हटाएं", color = Color(0xFF9B1C1C)) }
            }
            else -> {
                Icon(Icons.Filled.Mic, null, tint = Color(0xFF6B7590))
                Text("  वॉइस नोट", fontSize = 14.sp, color = Color(0xFF6B7590), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                }, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Mic, null, modifier = Modifier.size(18.dp)); Text(" रिकॉर्ड करें") }
            }
        }
    }
}

/** Play / pause for a voice note (local file path or https URL). */
@Composable
internal fun AudioPlayer(source: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var player by remember(source) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(source) { mutableStateOf(false) }
    DisposableEffect(source) { onDispose { player?.release(); player = null } }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF0E1B3D)).clickable {
                try {
                    val p = player ?: MediaPlayer().also {
                        it.setDataSource(ctx, Uri.parse(if (source.startsWith("http")) source else "file://$source"))
                        it.setOnCompletionListener { playing = false }
                        it.prepare()
                        player = it
                    }
                    if (playing) { p.pause(); playing = false } else { p.start(); playing = true }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "वॉइस नोट नहीं चल सका", Toast.LENGTH_SHORT).show()
                }
            },
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "चलाएं", tint = Color.White, modifier = Modifier.size(20.dp)) }
        Text("  वॉइस नोट", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF141C33))
    }
}

object MediaCompressor {
    /**
     * Compresses an image:
     * - Reads EXIF rotation to keep proper orientation.
     * - Downsamples and scales to max 1600px width/height.
     * - Compresses to 80% JPEG.
     * Reduces 15-20 MB photos down to ~300-500 KB without quality loss.
     */
    fun compressPhoto(ctx: Context, uri: Uri): File? {
        return try {
            val cr = ctx.contentResolver
            var orientation = ExifInterface.ORIENTATION_NORMAL
            try {
                cr.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            } catch (_: Exception) {}

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return null

            val maxDim = 1600
            var sampleSize = 1
            var w = origW
            var h = origH
            while (w > maxDim * 2 || h > maxDim * 2) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val sampledBitmap = cr.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            val ratio = sampledBitmap.width.toFloat() / sampledBitmap.height.toFloat()
            val (finalW, finalH) = if (sampledBitmap.width > sampledBitmap.height) {
                val nw = if (sampledBitmap.width > maxDim) maxDim else sampledBitmap.width
                nw to (nw / ratio).toInt()
            } else {
                val nh = if (sampledBitmap.height > maxDim) maxDim else sampledBitmap.height
                (nh * ratio).toInt() to nh
            }

            val scaledBitmap = if (sampledBitmap.width != finalW || sampledBitmap.height != finalH) {
                Bitmap.createScaledBitmap(sampledBitmap, finalW, finalH, true)
            } else {
                sampledBitmap
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }
            val rotatedBitmap = if (!matrix.isIdentity) {
                Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.width, scaledBitmap.height, matrix, true)
            } else {
                scaledBitmap
            }

            val outDir = File(ctx.cacheDir, "compressed_photos").apply { mkdirs() }
            val outFile = File(outDir, "photo_${System.currentTimeMillis()}.jpg")
            FileOutputStream(outFile).use { fos ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }
            outFile
        } catch (e: Exception) {
            android.util.Log.e("MediaCompressor", "Photo compression failed", e)
            null
        }
    }

    /**
     * Compresses a video (up to 500 MB):
     * - Uses native hardware MediaCodec to transcode to 720p H.264 / AAC.
     * - Drops 300-500 MB video down to ~12-25 MB.
     * - Returns null on error, allowing safe fallback to original.
     */
    suspend fun compressVideo(ctx: Context, uri: Uri, onProgress: (Float) -> Unit = {}): File? =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                try { VideoCompressor.cancel() } catch (_: Exception) {}
            }
            try {
                VideoCompressor.start(
                    context = ctx.applicationContext,
                    uris = listOf(uri),
                    isStreamable = false,
                    storageConfiguration = AppSpecificStorageConfiguration(
                        subFolderName = "compressed_videos"
                    ),
                    configureWith = Configuration(
                        quality = VideoQuality.MEDIUM,
                        isMinBitrateCheckEnabled = true,
                        videoBitrateInMbps = null,
                        disableAudio = false,
                        keepOriginalResolution = false,
                        videoNames = listOf("comp_${System.currentTimeMillis()}.mp4")
                    ),
                    listener = object : CompressionListener {
                        override fun onProgress(index: Int, percent: Float) {
                            onProgress((percent / 100f).coerceIn(0f, 1f))
                        }
                        override fun onStart(index: Int) {
                            onProgress(0f)
                        }
                        override fun onSuccess(index: Int, size: Long, path: String?) {
                            if (cont.isActive) {
                                if (path != null) cont.resume(File(path)) else cont.resume(null)
                            }
                        }
                        override fun onFailure(index: Int, failureMessage: String) {
                            android.util.Log.e("MediaCompressor", "Video compression failed: $failureMessage")
                            if (cont.isActive) cont.resume(null)
                        }
                        override fun onCancelled(index: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("MediaCompressor", "VideoCompressor start exception", e)
                if (cont.isActive) cont.resume(null)
            }
        }
}

/** Uploads a local file through a signed URL from the given API; returns the storage path. */
internal suspend fun uploadFile(ctx: Context, leader: Boolean, file: File, kind: String, mime: String, purpose: String? = null): String {
    val params = JSONObject().put("kind", kind).put("ext", file.extension.ifBlank { "bin" }).put("size", file.length())
    purpose?.let { params.put("purpose", it) }
    val u = if (leader) Api.leader(ctx, "upload_url", params) else Api.call(ctx, "own_upload_url", params)
    Api.upload(ctx, u.getString("signed_url"), Uri.fromFile(file), mime) {}
    return u.getString("path")
}

/** Uploads a picked photo or video (content Uri), compressing before upload. */
internal suspend fun uploadUri(ctx: Context, leader: Boolean, uri: Uri, kind: String, purpose: String? = null, onProgress: (Float) -> Unit = {}): String {
    val compressedFile = if (kind == "photo") {
        MediaCompressor.compressPhoto(ctx, uri)
    } else if (kind == "video") {
        MediaCompressor.compressVideo(ctx, uri, onProgress)
    } else null

    val targetUri = compressedFile?.let { Uri.fromFile(it) } ?: uri
    val mime = if (kind == "video") "video/mp4" else "image/jpeg"
    val ext = if (kind == "video") "mp4" else "jpg"
    val size = Api.fileSize(ctx, targetUri)
    val params = JSONObject().put("kind", kind).put("ext", ext).put("size", size)
    purpose?.let { params.put("purpose", it) }
    try {
        val u = if (leader) Api.leader(ctx, "upload_url", params) else Api.call(ctx, "own_upload_url", params)
        Api.upload(ctx, u.getString("signed_url"), targetUri, mime, onProgress)
        return u.getString("path")
    } finally {
        compressedFile?.let {
            try { it.delete() } catch (_: Exception) {}
        }
    }
}

/** One person a leader can call. */
internal data class Callee(val mobile: String, val name: String, val role: String)

/**
 * Opens the phone's dialer, then asks for the result in one tap when the leader comes back.
 * Phones don't let apps know whether a call was answered.
 */
@Composable
internal fun rememberCaller(askResult: Boolean = true, onLogged: () -> Unit = {}): (Callee) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<Callee?>(null) }
    var asking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && pending != null) { if (askResult) asking = true else pending = null }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val c = pending
    if (asking && c != null) {
        AlertDialog(
            onDismissRequest = { asking = false; pending = null },
            title = { Text("${c.name} से बात हुई?", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("बात हो गई", "फ़ोन नहीं उठाया", "फ़ोन बंद / नेटवर्क नहीं", "गलत नंबर").forEach { outcome ->
                        OutlinedButton(
                            onClick = {
                                asking = false; pending = null
                                scope.launch {
                                    try {
                                        Api.leader(ctx, "call_log", JSONObject().put("to_mobile", c.mobile).put("to_name", c.name).put("to_role", c.role).put("outcome", outcome))
                                        onLogged()
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        ) { Text(outcome) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { asking = false; pending = null }) { Text("छोड़ें") } },
        )
    }
    return { callee ->
        pending = callee
        try {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${callee.mobile}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            pending = null
            Toast.makeText(ctx, "डायलर नहीं खुल सका", Toast.LENGTH_SHORT).show()
        }
    }
}

/** One row in the side menu. */
internal class MenuItem(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val badge: Int = 0, val danger: Boolean = false, val highlight: Boolean = false, val onClick: () -> Unit)

/** Name of one stored profile, for the switch button: the post and where. */
internal fun profileLabel(p: JSONObject): String = when (p.optString("role")) {
    "member" -> if (p.optBoolean("is_applicant", true)) "वार्ड सदस्य" else "ब्लॉक अध्यक्ष"
    "observer" -> "विधानसभा ऑब्ज़र्वर"
    else -> ROLE_HI[p.optString("role")] ?: p.optString("role")
}
internal fun profileWhere(p: JSONObject): String = when (p.optString("role")) {
    "member" -> if (p.optBoolean("is_applicant", true)) "वार्ड ${p.optInt("ward_no")}" + (if (!p.isNull("block_no")) " · ब्लॉक अध्यक्ष, ब्लॉक ${p.optInt("block_no")}" else "") else "ब्लॉक ${p.optInt("block_no")}"
    "observer" -> p.optString("assembly").takeIf { it.isNotBlank() && it != "null" } ?: ""
    "dp", "dc" -> p.optString("district").takeIf { it.isNotBlank() && it != "null" } ?: ""
    else -> "पूरी दिल्ली"
}

internal const val PRIVACY_URL = "https://congressdelhinetwork.com/privacy"
internal const val DELETE_URL = "https://congressdelhinetwork.com/delete-account"

/** The two small links at the bottom of the menu and of the login screen. */
@Composable
internal fun PolicyLinks(modifier: Modifier = Modifier, center: Boolean = false, before: () -> Unit = {}) {
    val ctx = LocalContext.current
    fun open(url: String) { before(); try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
    Column(modifier, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        listOf("Privacy Policy" to PRIVACY_URL, "Request for Account Deletion" to DELETE_URL).forEach { (label, url) ->
            Text(label, fontSize = 12.5.sp, color = Color(0xFF2463D1), fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { open(url) }.padding(horizontal = 8.dp, vertical = 7.dp))
        }
    }
}

/** A leader finishes a task by writing what was done (or recording it). A bare tick is not enough. */
@Composable
internal fun TaskDoneDialog(taskId: Long, title: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("काम हो गया", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, fontSize = 14.sp, color = Color(0xFF3A4560), lineHeight = 20.sp)
                OutlinedTextField(note, { note = it.take(2000) }, label = { Text("क्या किया, दो लाइन में लिखें") }, minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                VoiceNote(voice) { voice = it }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (note.isBlank() && voice == null) { Toast.makeText(ctx, "दो लाइन लिखें या वॉइस नोट रिकॉर्ड करें", Toast.LENGTH_SHORT).show(); return@Button }
                    busy = true
                    scope.launch {
                        try {
                            val p = JSONObject().put("task_id", taskId).put("note", note.trim())
                            voice?.let { p.put("audio_path", uploadFile(ctx, true, it, "audio", "audio/mp4")) }
                            Api.leader(ctx, "task_done", p)
                            Toast.makeText(ctx, "टास्क पूरा दर्ज हो गया", Toast.LENGTH_LONG).show()
                            onDone()
                        } catch (e: Exception) { Toast.makeText(ctx, "भेजा नहीं जा सका, इंटरनेट चेक करें", Toast.LENGTH_LONG).show() }
                        busy = false
                    }
                },
                enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E1B3D)),
            ) { Text(if (busy) "भेजा जा रहा है…" else "सबमिट करें", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("रहने दें") } },
    )
}

/** The bold line shown on every task that comes from the President, at every level. */
@Composable
internal fun PresidentTaskLine(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF1E4)).border(1.dp, Color(0xFFF6C9A0), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Campaign, null, tint = Color(0xFFF2852B), modifier = Modifier.size(22.dp))
        Text("यह टास्क प्रदेश कांग्रेस अध्यक्ष देवेंद्र यादव जी की तरफ़ से आया है।", fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9A4A0B), modifier = Modifier.padding(start = 10.dp))
    }
}

/** One picked photo or video. It starts uploading as soon as it is picked; path is set once it is on the server. */
internal class Attachment(val kind: String, val uri: Uri) {
    var path by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
}

/**
 * Photo and video buttons with the list of picked files. Each file uploads the moment it is picked,
 * so by the time the person presses send there is nothing left to wait for.
 */
@Composable
internal fun AttachBox(items: androidx.compose.runtime.snapshots.SnapshotStateList<Attachment>, leader: Boolean, max: Int = 8, purpose: String? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun start(a: Attachment) {
        a.failed = false
        scope.launch {
            try { a.path = uploadUri(ctx, leader, a.uri, a.kind, purpose) } catch (e: Exception) { a.failed = true }
        }
    }
    fun add(kind: String, uris: List<Uri>) {
        val room = (max - items.size).coerceAtLeast(0)
        if (uris.size > room) Toast.makeText(ctx, "ज़्यादा से ज़्यादा $max फ़ाइलें जोड़ सकते हैं", Toast.LENGTH_SHORT).show()
        uris.take(room).forEach { u -> val a = Attachment(kind, u); items.add(a); start(a) }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us -> add("photo", us) }
    val pickVideos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { us -> add("video", us) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickPhotos.launch("image/*") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), enabled = items.size < max) { Icon(Icons.Filled.PhotoCamera, null); Text(" फोटो") }
        OutlinedButton(onClick = { pickVideos.launch("video/*") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp), enabled = items.size < max) { Icon(Icons.Filled.Videocam, null); Text(" वीडियो") }
    }
    items.forEachIndexed { i, a ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).border(1.dp, Color(0xFFE3E7EF), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (a.kind == "video") Icons.Filled.Videocam else Icons.Filled.Image, null, tint = Color(0xFF0E1B3D), modifier = Modifier.size(20.dp))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text((if (a.kind == "video") "वीडियो" else "फोटो") + " ${i + 1}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF141C33))
                Text(
                    when { a.path != null -> "अपलोड हो गया"; a.failed -> "अपलोड नहीं हुआ"; else -> "अपलोड हो रहा है…" },
                    fontSize = 12.sp, color = when { a.path != null -> Color(0xFF14613A); a.failed -> Color(0xFF9B1C1C); else -> Color(0xFF2463D1) },
                )
            }
            when {
                a.path != null -> Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF12884A), modifier = Modifier.size(20.dp))
                a.failed -> TextButton(onClick = { start(a) }) { Text("फिर कोशिश करें") }
                else -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF2463D1))
            }
            IconButton(onClick = { items.remove(a) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "हटाएं", tint = Color(0xFF9B1C1C), modifier = Modifier.size(18.dp)) }
        }
    }
}

/** The panel that slides in from the left: who is logged in, then the menu. */
@Composable
internal fun SideMenu(name: String, sub: String, items: List<MenuItem>, photo: String? = null, close: () -> Unit) {
    val navy = Color(0xFF0E1B3D)
    ModalDrawerSheet(drawerContainerColor = Color.White, drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp), modifier = Modifier.fillMaxWidth(0.82f)) {
        Column(Modifier.fillMaxWidth().background(navy).padding(start = 20.dp, end = 16.dp, top = 28.dp, bottom = 20.dp)) {
            if (photo != null) coil.compose.AsyncImage(
                model = photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.White),
            ) else androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.logo), contentDescription = null,
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.White),
            )
            Text(name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp), maxLines = 2)
            Text(sub, color = Color(0xFFAFC0E3), fontSize = 13.sp, maxLines = 2, lineHeight = 18.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            items.forEach { it ->
                if (it.danger) HorizontalDivider(color = Color(0xFFE3E7EF), modifier = Modifier.padding(vertical = 6.dp))
                // A highlighted row (switching to the other profile) sits in its own orange box so it can't be missed.
                if (it.highlight) Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF2852B))
                        .clickable { close(); it.onClick() }.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(it.icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Text(it.label, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    Icon(Icons.Filled.ChevronRight, null, tint = Color.White, modifier = Modifier.size(20.dp))
                } else Row(
                    Modifier.fillMaxWidth().clickable { close(); it.onClick() }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(it.icon, null, tint = if (it.danger) Color(0xFF9B1C1C) else navy, modifier = Modifier.size(22.dp))
                    Text(it.label, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = if (it.danger) Color(0xFF9B1C1C) else Color(0xFF141C33), modifier = Modifier.padding(start = 16.dp).weight(1f))
                    if (it.badge > 0) Text("${it.badge}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(CircleShape).background(Color(0xFFE5402F)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
        }
        Text("$APP_NAME · v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Color(0xFF6B7590), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
        PolicyLinks(Modifier.padding(start = 12.dp, bottom = 10.dp)) { close() }
    }
}
