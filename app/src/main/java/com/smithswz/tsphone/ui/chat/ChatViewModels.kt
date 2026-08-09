package com.smithswz.tsphone.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smithswz.tsphone.data.db.ConversationSummary
import com.smithswz.tsphone.data.db.MessageEntity
import com.smithswz.tsphone.data.repo.ChatRepository
import com.smithswz.tsphone.ts3.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared chat state: reloads history whenever the connection writes a message.
 *
 * The session key is passed in from the subclass's super() call — computed
 * there, its constructor parameters are already bound (calling an abstract
 * sessionKey() from the base init would run before the subclass's fields are
 * initialized, yielding null).
 */
abstract class ChatViewModelBase(
    protected val chatRepository: ChatRepository,
    private val sessionKey: String,
    manager: ConnectionManager
) : ViewModel() {

    protected val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    init {
        viewModelScope.launch {
            manager.messagesVersion.collect { _messages.value = chatRepository.recent(sessionKey) }
        }
    }
}

class ChannelChatViewModel(
    private val channelId: Int,
    chatRepository: ChatRepository,
    private val manager: ConnectionManager
) : ChatViewModelBase(chatRepository, MessageEntity.channelKey(channelId), manager) {

    private val _channelName = MutableStateFlow<String?>(null)
    val channelName: StateFlow<String?> = _channelName.asStateFlow()

    init {
        viewModelScope.launch {
            manager.channels.collect { _channelName.value = it[channelId]?.name }
        }
    }

    fun send(text: String) = manager.sendChannelMessage(channelId, text)
}

class PrivateChatViewModel(
    private val uid: String,
    chatRepository: ChatRepository,
    private val manager: ConnectionManager
) : ChatViewModelBase(chatRepository, MessageEntity.privateKey(uid), manager) {

    private val _peerName = MutableStateFlow<String?>(null)
    val peerName: StateFlow<String?> = _peerName.asStateFlow()

    init {
        viewModelScope.launch {
            manager.clients.collect { map ->
                map.values.firstOrNull { it.uniqueId == uid }?.let { _peerName.value = it.nickname }
            }
        }
    }

    fun send(text: String) = manager.sendPrivateMessage(uid, text)
}

class PrivateChatsViewModel(
    chatRepository: ChatRepository,
    manager: ConnectionManager
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    init {
        viewModelScope.launch {
            manager.messagesVersion.collect {
                _conversations.value = chatRepository.conversations()
                    .filter { it.sessionType == MessageEntity.TYPE_PRIVATE }
            }
        }
    }
}
