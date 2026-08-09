package com.smithswz.tsphone.ts3

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service owning the TS3 connection (and, later, the voice pipeline).
 *
 * The real lifecycle logic (startForeground with microphone type, connection
 * management, notification updates) lands with the connection feature. This
 * stub exists so the manifest declaration compiles from the start.
 */
class TS3Service : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
