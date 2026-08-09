package com.smithswz.tsphone.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smithswz.tsphone.R
import com.smithswz.tsphone.ui.common.PlaceholderScreen

object Routes {
    const val BOOKMARKS = "bookmarks"
    const val SERVER = "server"
    const val CHANNEL_CHAT = "channelChat/{channelId}"
    const val PRIVATE_CHATS = "privateChats"
    const val PRIVATE_CHAT = "privateChat/{uid}"
    const val SETTINGS = "settings"

    fun channelChat(channelId: Int) = "channelChat/$channelId"
    fun privateChat(uid: String) = "privateChat/$uid"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.BOOKMARKS) {
        composable(Routes.BOOKMARKS) {
            PlaceholderScreen(stringResource(R.string.title_bookmarks))
        }
        composable(Routes.SERVER) {
            PlaceholderScreen(stringResource(R.string.title_server))
        }
        composable(
            route = Routes.CHANNEL_CHAT,
            arguments = listOf(navArgument("channelId") { type = NavType.IntType })
        ) {
            PlaceholderScreen(stringResource(R.string.title_channel_chat))
        }
        composable(Routes.PRIVATE_CHATS) {
            PlaceholderScreen(stringResource(R.string.title_private_chats))
        }
        composable(
            route = Routes.PRIVATE_CHAT,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) {
            PlaceholderScreen(stringResource(R.string.title_private_chat))
        }
        composable(Routes.SETTINGS) {
            PlaceholderScreen(stringResource(R.string.title_settings))
        }
    }
}
