package com.smithswz.tsphone.ts3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.smithswz.tsphone.MainActivity
import com.smithswz.tsphone.R
import com.smithswz.tsphone.TSPhoneApp

/** Builds the connection-status and poke notifications. */
class NotificationHelper(private val context: Context) {

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun connectionNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, TSPhoneApp.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** Heads-up poke alert; deliberately has no contentIntent (tap does nothing). */
    fun pokeNotification(invokerName: String, message: String): Notification {
        return NotificationCompat.Builder(context, TSPhoneApp.CHANNEL_POKE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.poke_title, invokerName))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()
    }

    fun notify(id: Int, notification: Notification) {
        notificationManager.notify(id, notification)
    }

    fun cancel(id: Int) {
        notificationManager.cancel(id)
    }

    companion object {
        const val NOTIFICATION_CONNECTION = 1
    }
}
