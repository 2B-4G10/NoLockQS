package com.example.nolockqs

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateFormat
import java.util.Date

/**
 * Receives the hooks' reports (see [Reports]): records each blocked attempt, posts an alert for
 * it when alerts are on, and keeps each process's last hook status for the diagnostics report.
 * It runs before the first unlock too, so nothing it touches may need the unlocked user storage.
 */
class ReportReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "blocked_attempts"
        private const val NOTIFICATION_ID = 1

        /** Creates the alerts channel: silent, so a thief hears nothing, and never shown on the lock screen. */
        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alerts_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.alerts_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        /** Takes the alert down, as the history it points to is being looked at. */
        fun cancelAlert(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)

        /** The label of a [Reports] KIND_ value, such as "Power menu". */
        fun kindLabel(context: Context, kind: String): String = context.getString(
            when (kind) {
                Reports.KIND_POWER_MENU -> R.string.feature_power_title
                else -> R.string.feature_qs_title
            },
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val log = AttemptLog(context)
        val time = intent.getLongExtra(Reports.EXTRA_TIME, System.currentTimeMillis())
        when (intent.action) {
            Reports.ACTION_BLOCKED -> {
                val kind = intent.getStringExtra(Reports.EXTRA_KIND) ?: return
                val unseen = log.add(AttemptLog.Attempt(time, kind))
                if (log.alertsEnabled) postAlert(context, kind, time, unseen)
            }
            Reports.ACTION_STATUS -> {
                val process = intent.getStringExtra(Reports.EXTRA_PROCESS) ?: return
                val layers = intent.getStringArrayListExtra(Reports.EXTRA_LAYERS).orEmpty()
                log.saveStatus(process, AttemptLog.Status(time, layers))
            }
        }
    }

    private fun postAlert(context: Context, kind: String, time: Long, unseen: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        val at = DateFormat.getTimeFormat(context).format(Date(time))
        val title = context.getString(R.string.alert_title, kindLabel(context, kind))
        val text = if (unseen > 1) {
            context.resources.getQuantityString(R.plurals.alert_text_many, unseen, unseen, at)
        } else {
            context.getString(R.string.alert_text_one, at)
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_active)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(time)
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }
}
