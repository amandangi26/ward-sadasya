package com.coceptual.delhicongress

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Runs periodically and on-demand: checks for new tasks, announcements, and reminders. */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Store.token(ctx) == null) return Result.success()
        // Leaders use a different API; this reminder check is for ward members.
        if (Store.role(ctx) != "member") return Result.success()
        val res = try {
            val p = JSONObject()
            Store.lastPoll(ctx)?.let { p.put("since", it) }
            Api.call(ctx, "poll", p)
        } catch (e: ApiError) {
            if (e.code == "unauthorized") Store.setToken(ctx, null)
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
        val serverNow = res.optString("now")
        if (serverNow.isNotBlank()) Store.setLastPoll(ctx, serverNow)

        val hour = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).get(Calendar.HOUR_OF_DAY)
        if (hour < 7 || hour >= 23) return Result.success() // quiet hours (11 PM to 7 AM IST)

        // Items the admin already pushed instantly through Firebase are not repeated here.
        val pushReady = Store.pushReady(ctx)

        // 1. New tasks created by Admin
        val newTasks = res.optJSONArray("new_tasks")
        if (newTasks != null && !pushReady) {
            for (i in 0 until newTasks.length()) {
                val t = newTasks.getJSONObject(i)
                val taskId = t.optInt("id", (System.currentTimeMillis() % 10000).toInt())
                val title = t.optString("title", "नया टास्क")
                // Text comes from the task itself (built on the server), so share cards and upload tasks read differently.
                val notice = t.optJSONObject("notify")
                Notify.show(
                    ctx,
                    4000 + taskId,
                    notice?.optString("title")?.takeIf { it.isNotBlank() } ?: "नया टास्क: $title",
                    notice?.optString("body")?.takeIf { it.isNotBlank() } ?: "आज रात 11:59 तक वीडियो अपलोड करें।",
                    taskId = t.optLong("id"),
                )
            }
        }

        // Personal messages from the DPCC President
        val inbox = res.optJSONArray("new_inbox")
        if (inbox != null && inbox.length() > 0) {
            val first = inbox.getJSONObject(0)
            Notify.show(ctx, 6000 + first.optInt("id") % 1000, "माननीय DPCC अध्यक्ष जी का संदेश",
                first.optString("text").takeIf { it.isNotBlank() && it != "null" } ?: "आपके लिए एक वॉइस नोट आया है", inbox = true)
        }

        // 2. Announcements
        val ann = res.optJSONArray("announcements")
        if (ann != null) {
            for (i in 0 until ann.length()) {
                val a = ann.getJSONObject(i)
                if (pushReady && !a.isNull("pushed_count")) continue
                val annId = a.optInt("id", i)
                val title = a.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: "INC Delhi"
                Notify.show(ctx, 1000 + annId, title, a.optString("body"), urgent = a.optString("priority") == "high")
            }
        }

        // 3. Leader video message
        if (!pushReady) res.optJSONObject("new_message")?.let {
            val msgId = it.optInt("id", 2000)
            Notify.show(ctx, 2000 + msgId, "देवेन्द्र यादव जी का संदेश", it.optString("title"))
        }

        // 4. Pending tasks reminder
        val pending = res.optInt("pending")
        if (pending > 0 && (newTasks == null || newTasks.length() == 0 || pushReady)) {
            Notify.show(ctx, 3000, "आज के टास्क बाकी हैं", "आपके $pending टास्क बाकी हैं। रात 11:59 तक वीडियो अपलोड करें।")
        }

        // 5. App update notification (system tray)
        val update = res.optJSONObject("app_update")
        if (update != null) {
            val serverVersion = update.optInt("version_code", 1)
            val versionName = update.optString("version_name", "")
            if (serverVersion > BuildConfig.VERSION_CODE) {
                Notify.show(
                    ctx,
                    9999,
                    "नया अपडेट उपलब्ध है${if (versionName.isNotBlank()) " (v$versionName)" else ""}",
                    update.optString("notes", "ऐप को नए फ़ीचर्स के लिए अपडेट करें।")
                )
            }
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            // Periodic background check every 15 minutes (Android WorkManager minimum)
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "reminders",
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
            checkNow(ctx)
        }

        fun checkNow(ctx: Context) {
            if (Store.token(ctx) == null) return
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}

object Notify {
    private const val CHANNEL = "reminders"
    /** High-priority channel for instant pushes from the admin panel. Must match channel_id in the push function. */
    const val URGENT = "urgent"

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "टास्क और घोषणाएँ", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "दैनिक टास्क, नेताजी के संदेश और घोषणाएं"
                enableVibration(true)
                enableLights(true)
            }
            val urgent = NotificationChannel(URGENT, "ज़रूरी सूचनाएं (तुरंत)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "पार्टी की ओर से तुरंत भेजी गई ज़रूरी सूचनाएं"
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
            nm.createNotificationChannel(urgent)
        }
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, id: Int, title: String, text: String, urgent: Boolean = false, taskId: Long? = null, inbox: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { taskId?.let { putExtra("open_task", it) }; if (inbox) putExtra("open_inbox", true) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, if (urgent) URGENT else CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }
}
