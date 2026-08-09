package com.smithswz.tsphone.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smithswz.tsphone.ui.bookmarks.BookmarksScreen
import com.smithswz.tsphone.ui.chat.ChannelChatScreen
import com.smithswz.tsphone.ui.chat.PrivateChatScreen
import com.smithswz.tsphone.ui.chat.PrivateChatsScreen
import com.smithswz.tsphone.ui.server.ServerScreen
import com.smithswz.tsphone.ui.settings.SettingsScreen

object Routes {
    const val BOOKMARKS = "bookmarks"
    const val SERVER = "server"
    const val CHANNEL_CHAT = "channelChat/{channelId}"
    const val PRIVATE_CHATS = "privateChats"
    const val PRIVATE_CHAT = "privateChat/{uid}"
    const val SETTINGS = "settings"

    fun channelChat(channelId: Int) = "channelChat/$channelId"

    /** TS3 UIDs are base64 and contain '/'/'+' — substitute for the route. */
    fun privateChat(uid: String) = "privateChat/${UidCodec.encode(uid)}"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.BOOKMARKS) {
        composable(Routes.BOOKMARKS) {
            BookmarksScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onConnect = { navController.navigate(Routes.SERVER) }
            )
        }
        composable(Routes.SERVER) {
            ServerScreen(
                onExit = { navController.popBackStack() },
                onOpenChannelChat = { channelId -> navController.navigate(Routes.channelChat(channelId)) },
                onOpenPrivateChat = { uid -> navController.navigate(Routes.privateChat(uid)) },
                onOpenPrivateChats = { navController.navigate(Routes.PRIVATE_CHATS) }
            )
        }
        composable(
            route = Routes.CHANNEL_CHAT,
            arguments = listOf(navArgument("channelId") { type = NavType.IntType })
        ) { entry ->
            ChannelChatScreen(entry.arguments?.getInt("channelId") ?: 0)
        }
        composable(Routes.PRIVATE_CHATS) {
            PrivateChatsScreen(onOpenChat = { uid -> navController.navigate(Routes.privateChat(uid)) })
        }
        composable(
            route = Routes.PRIVATE_CHAT,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { entry ->
            PrivateChatScreen(UidCodec.decode(entry.arguments?.getString("uid") ?: ""))
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
    }
}
