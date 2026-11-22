package com.coceptual.delhicongress

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** App start: notification channels and Firebase, before any screen or push service runs. */
class IncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notify.createChannel(this)
        Push.init(this)
    }
}

/**
 * Instant notifications through Firebase Cloud Messaging.
 * Firebase is set up from gradle.properties (FCM_*), so no google-services.json is needed.
 * If those values are empty, push stays off and the app relies on its 15-minute background check.
 */
object Push {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun configured(): Boolean =
        BuildConfig.FCM_APP_ID.isNotBlank() && BuildConfig.FCM_API_KEY.isNotBlank() &&
            BuildConfig.FCM_PROJECT_ID.isNotBlank() && BuildConfig.FCM_SENDER_ID.isNotBlank()

    fun init(ctx: Context) {
        if (!configured()) return
        try {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FCM_APP_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(ctx, options)
            }
        } catch (e: Exception) {
            // Bad Firebase values must never crash the app; polling still works.
        }
    }

    private fun ready(ctx: Context) = configured() && FirebaseApp.getApps(ctx).isNotEmpty()

    /** Call after login and on app start: registers this phone and subscribes to all / ward / district topics. */
    fun register(ctx: Context) {
        val app = ctx.applicationContext
        if (!ready(app) || Store.token(app) == null) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                if (!t.isNullOrBlank()) scope.launch { send(app, t) }
            }
        } catch (e: Exception) {
        }
    }

    suspend fun send(ctx: Context, fcmToken: String) {
        try {
            val r = Api.call(ctx, "register_push", JSONObject().put("token", fcmToken))
            val arr = r.optJSONArray("topics") ?: JSONArray()
            val topics = (0 until arr.length()).map { arr.getString(it) }.toSet()
            val fm = FirebaseMessaging.getInstance()
            (Store.topics(ctx) - topics).forEach { fm.unsubscribeFromTopic(it) }
            topics.forEach { fm.subscribeToTopic(it) }
            Store.setTopics(ctx, topics)
            Store.setPushReady(ctx, true)
        } catch (e: Exception) {
            Store.setPushReady(ctx, false)
        }
    }

    /** On logout: stop topic messages for the old member. */
    fun unregister(ctx: Context) {
        if (!ready(ctx)) return
        try {
            val fm = FirebaseMessaging.getInstance()
            Store.topics(ctx).forEach { fm.unsubscribeFromTopic(it) }
            fm.deleteToken()
        } catch (e: Exception) {
        }
    }
}

/** Receives pushes. In the background Android shows them itself on the "urgent" channel; here we handle the foreground. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val ctx = applicationContext
        if (Store.token(ctx) != null) CoroutineScope(Dispatchers.IO).launch { Push.send(ctx, token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: message.notification?.title ?: "INC Delhi"
        val body = message.data["body"] ?: message.notification?.body ?: return
        val id = 50_000 + ((message.data["type"].orEmpty() + message.data["ref_id"].orEmpty()).hashCode() and 0xFFFF)
        Notify.show(applicationContext, id, title, body, urgent = true)
    }
}
