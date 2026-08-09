package com.smithswz.tsphone.ts3

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.smithswz.tsphone.R
import com.smithswz.tsphone.TSPhoneApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service (type `microphone`) that owns the TS3 connection and the
 * voice pipeline. Android requires startForeground() promptly after the app
 * called startForegroundService() — so it happens synchronously in
 * onStartCommand, before any connection work.
 */
class TS3Service : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationHelper: NotificationHelper

    /** True once onStartCommand has put the service into the foreground. */
    @Volatile
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)

        val manager = (application as TSPhoneApp).container.connectionManager
        serviceScope.launch {
            manager.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connecting ->
                        updateForeground(getString(R.string.notif_connecting), "")
                    is ConnectionState.Connected ->
                        updateForeground(getString(R.string.notif_connected, state.serverName), "")
                    is ConnectionState.Disconnected ->
                        updateForeground(
                            getString(R.string.notif_disconnected),
                            getString(R.string.notif_tap_to_reconnect)
                        )
                    ConnectionState.Idle -> {
                        // Only act on Idle once the service is actually foreground;
                        // the initial StateFlow emission happens before onStartCommand.
                        if (foregroundStarted) {
                            ServiceCompat.stopForeground(this@TS3Service, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
    }

    private fun updateForeground(title: String, text: String) {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIFICATION_CONNECTION,
            notificationHelper.connectionNotification(title, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Synchronous foreground start — the 5 s ANR window starts now.
        updateForeground(getString(R.string.notif_connecting), "")
        foregroundStarted = true

        val bookmarkId = intent?.getLongExtra(EXTRA_BOOKMARK_ID, -1L)
        if (bookmarkId != null && bookmarkId > 0) {
            val app = application as TSPhoneApp
            serviceScope.launch {
                val bookmark = app.container.bookmarkRepository.allBookmarks.first()
                    .firstOrNull { it.id == bookmarkId }
                if (bookmark != null) {
                    app.container.connectionManager.connect(bookmark)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOKMARK_ID = "bookmark_id"

        fun connectIntent(context: Context, bookmarkId: Long): Intent =
            Intent(context, TS3Service::class.java).putExtra(EXTRA_BOOKMARK_ID, bookmarkId)
    }
}
