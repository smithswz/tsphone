@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.smithswz.tsphone.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smithswz.tsphone.R
import com.smithswz.tsphone.TSPhoneApp
import com.smithswz.tsphone.data.db.ConversationSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ChannelChatScreen(channelId: Int) {
    val app = LocalContext.current.applicationContext as TSPhoneApp
    val vm: ChannelChatViewModel = viewModel {
        ChannelChatViewModel(channelId, app.container.chatRepository, app.container.connectionManager)
    }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val channelName by vm.channelName.collectAsStateWithLifecycle()

    ChatScreen(
        title = channelName ?: stringResource(R.string.title_channel_chat),
        messages = messages,
        onSend = vm::send
    )
}

@Composable
fun PrivateChatScreen(uid: String) {
    val app = LocalContext.current.applicationContext as TSPhoneApp
    val vm: PrivateChatViewModel = viewModel {
        PrivateChatViewModel(uid, app.container.chatRepository, app.container.connectionManager)
    }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val peerName by vm.peerName.collectAsStateWithLifecycle()

    ChatScreen(
        title = peerName ?: uid,
        messages = messages,
        onSend = vm::send
    )
}

@Composable
fun PrivateChatsScreen(onOpenChat: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as TSPhoneApp
    val vm: PrivateChatsViewModel = viewModel {
        PrivateChatsViewModel(app.container.chatRepository, app.container.connectionManager)
    }
    val conversations by vm.conversations.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.title_private_chats)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (conversations.isEmpty()) {
                Text(
                    stringResource(R.string.no_private_chats),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(32.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(conversations, key = { it.sessionKey }) { conversation ->
                        ConversationRow(conversation) {
                            onOpenChat(conversation.sessionKey.removePrefix("p:"))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConversationRow(conversation: ConversationSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(conversation.peerName, style = MaterialTheme.typography.titleMedium)
                Text(
                    DATE_FORMAT.format(Instant.ofEpochMilli(conversation.lastTs).atZone(ZoneId.systemDefault())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
