package com.smithswz.tsphone.ui.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smithswz.tsphone.data.db.BookmarkEntity
import com.smithswz.tsphone.ts3.ChannelInfo
import com.smithswz.tsphone.ts3.ClientInfo
import com.smithswz.tsphone.ts3.ConnectionManager
import com.smithswz.tsphone.ts3.ConnectionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ServerViewModel(private val manager: ConnectionManager) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = manager.connectionState
    val channels: StateFlow<Map<Int, ChannelInfo>> = manager.channels
    val clients: StateFlow<Map<Int, ClientInfo>> = manager.clients
    val speakingClients: StateFlow<Set<Int>> = manager.speakingClients
    val micMuted: StateFlow<Boolean> = manager.micMuted
    val speakerOn: StateFlow<Boolean> = manager.speakerOn

    fun toggleMic() = viewModelScope.launch { manager.toggleMic() }
    fun toggleSpeaker() = viewModelScope.launch { manager.toggleSpeaker() }

    /** Leaves the server entirely (returns to the bookmark list). */
    fun exit() = manager.disconnect()

    /** Reconnects after a server-side disconnect. */
    fun reconnect() = manager.reconnect()
}
