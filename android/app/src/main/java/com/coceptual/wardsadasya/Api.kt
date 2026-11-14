package com.coceptual.wardsadasya

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Saved login token and reminder state. */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ward_sadasya", Context.MODE_PRIVATE)
    fun token(ctx: Context): String? = prefs(ctx).getString("token", null)
    fun setToken(ctx: Context, t: String?) = prefs(ctx).edit().putString("token", t).apply()
    /** member, president, gs, dp or observer. */
    fun role(ctx: Context): String = prefs(ctx).getString("role", "member") ?: "member"
    fun setRole(ctx: Context, r: String) = prefs(ctx).edit().putString("role", r).apply()

    /** Every profile this number holds (a leader post and/or ward member), as sent at login. */
    fun profiles(ctx: Context): List<JSONObject> = try {
        val arr = org.json.JSONArray(prefs(ctx).getString("profiles", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
    } catch (e: Exception) { emptyList() }
    fun setProfiles(ctx: Context, list: List<JSONObject>) =
        prefs(ctx).edit().putString("profiles", org.json.JSONArray(list).toString()).apply()
    /** The profile not open right now, if this number has two. */
    fun otherProfile(ctx: Context): JSONObject? = profiles(ctx).firstOrNull { it.optString("role") != role(ctx) }
    /** Opens one of the stored profiles. */
    fun activate(ctx: Context, p: JSONObject) { setToken(ctx, p.optString("token")); setRole(ctx, p.optString("role", "member")) }
    fun lastPoll(ctx: Context): String? = prefs(ctx).getString("last_poll", null)
    fun setLastPoll(ctx: Context, v: String) = prefs(ctx).edit().putString("last_poll", v).apply()
    fun clear(ctx: Context) = prefs(ctx).edit().clear().apply()
    fun topics(ctx: Context): Set<String> = prefs(ctx).getStringSet("push_topics", emptySet()) ?: emptySet()
    fun setTopics(ctx: Context, t: Set<String>) = prefs(ctx).edit().putStringSet("push_topics", t).apply()
    /** True once this phone is registered for instant (Firebase) notifications. */
    fun pushReady(ctx: Context): Boolean = prefs(ctx).getBoolean("push_ready", false)
    fun setPushReady(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("push_ready", v).apply()
}

class ApiError(val code: String, val body: JSONObject) : Exception(code)

object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    /** Calls the Supabase Edge Function with { action, ...params }. */
    suspend fun call(ctx: Context, action: String, params: JSONObject = JSONObject()): JSONObject =
        post(ctx, BuildConfig.API_URL, action, params)

    /** Same, for the leader screens (President, GS, District Presidents, Observers). */
    suspend fun leader(ctx: Context, action: String, params: JSONObject = JSONObject()): JSONObject =
        post(ctx, BuildConfig.API_URL.substringBeforeLast("/") + "/leader", action, params)

    private suspend fun post(ctx: Context, url: String, action: String, params: JSONObject): JSONObject =
        withContext(Dispatchers.IO) {
            params.put("action", action)
            val builder = Request.Builder()
                .url(url)
                .addHeader("apikey", BuildConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer " + BuildConfig.ANON_KEY)
                .post(params.toString().toRequestBody("application/json".toMediaType()))
            Store.token(ctx)?.let { builder.addHeader("x-session", it) }
            client.newCall(builder.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", "bad_response") }
                if (!r.isSuccessful) throw ApiError(json.optString("error", "http_${r.code}"), json)
                json
            }
        }

    /** Streams a picked file to a Supabase signed upload URL, reporting progress 0..1. */
    suspend fun upload(ctx: Context, signedUrl: String, uri: Uri, mime: String, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val body = UriBody(ctx, uri, mime.toMediaType(), onProgress)
            val req = Request.Builder()
                .url(signedUrl)
                .addHeader("x-upsert", "false")
                .put(body)
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw ApiError("upload_failed_${r.code}", JSONObject().put("detail", r.body?.string().orEmpty()))
            }
            body.length
        }

    fun fileSize(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                if (it.length > 0) return it.length
            }
        } catch (_: Exception) {}
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1 && cursor.moveToFirst()) {
                    val sz = cursor.getLong(index)
                    if (sz > 0) return sz
                }
            }
        } catch (_: Exception) {}
        return -1L
    }
}

private class UriBody(
    private val ctx: Context,
    private val uri: Uri,
    private val type: MediaType,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    val length: Long = Api.fileSize(ctx, uri)
    override fun contentType() = type
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                total += n
                if (length > 0) onProgress(total.toFloat() / length)
            }
        }
    }
}
