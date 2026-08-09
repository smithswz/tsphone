package com.smithswz.tsphone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.smithswz.tsphone.core.AppContainer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class TSPhoneApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        registerBouncyCastle()
        createNotificationChannels()
    }

    /**
     * ts3j's crypto (identity key generation, connection handshake) needs a
     * provider registered under the name "BC". Android ships a renamed BC, so
     * we add the standalone jar's provider if and only if it is missing.
     */
    private fun registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION,
                getString(R.string.channel_connection),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_POKE,
                getString(R.string.channel_poke),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    companion object {
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_POKE = "poke"
    }
}
