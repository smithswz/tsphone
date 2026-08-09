package com.smithswz.tsphone.core

import android.content.Context
import androidx.room.Room
import com.smithswz.tsphone.data.db.TsDatabase
import com.smithswz.tsphone.data.prefs.IdentityRepository
import com.smithswz.tsphone.data.prefs.IdentityState
import com.smithswz.tsphone.data.prefs.SettingsRepository
import com.smithswz.tsphone.data.repo.BookmarkRepository
import com.smithswz.tsphone.data.repo.ChatRepository
import com.smithswz.tsphone.ts3.ConnectionManager
import com.smithswz.tsphone.ts3.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/** Manual dependency container (no DI framework). Owned by [com.smithswz.tsphone.TSPhoneApp]. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: TsDatabase = Room.databaseBuilder(appContext, TsDatabase::class.java, "tsphone.db").build()

    val bookmarkRepository = BookmarkRepository(database.bookmarkDao())
    val chatRepository = ChatRepository(database.messageDao())
    val settingsRepository = SettingsRepository(appContext)
    val identityRepository = IdentityRepository(appContext, appScope)
    val identityState: StateFlow<IdentityState> = identityRepository.state
    val notificationHelper = NotificationHelper(appContext)
    val connectionManager = ConnectionManager(
        context = appContext,
        scope = appScope,
        identityRepository = identityRepository,
        settingsRepository = settingsRepository,
        bookmarkRepository = bookmarkRepository,
        chatRepository = chatRepository,
        notificationHelper = notificationHelper
    )
}
