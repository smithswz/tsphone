package com.smithswz.tsphone.ts3

import android.util.Log
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import com.smithswz.tsphone.data.db.BookmarkEntity
import com.smithswz.tsphone.data.prefs.IdentityRepository
import com.smithswz.tsphone.data.prefs.SettingsRepository
import com.smithswz.tsphone.data.repo.BookmarkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val serverName: String) : ConnectionState
    data class Disconnected(val reason: String?) : ConnectionState
}

data class ChannelInfo(
    val id: Int,
    val parentId: Int,
    val name: String,
    val order: Int
)

data class ClientInfo(
    val id: Int,
    val nickname: String,
    val uniqueId: String?,
    val channelId: Int,
    val isSelf: Boolean
)

/**
 * Process-wide owner of the TS3 socket. All state is exposed as StateFlows;
 * ts3j listener callbacks arrive on ts3j threads and only mutate flows, so the
 * UI can collect from anywhere.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val identityRepository: IdentityRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val notificationHelper: NotificationHelper
) {

    private val socketLock = Any()
    private var socket: LocalTeamspeakClientSocket? = null
    private var selfId: Int = 0
    private var lastBookmark: BookmarkEntity? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _channels = MutableStateFlow<Map<Int, ChannelInfo>>(emptyMap())
    val channels: StateFlow<Map<Int, ChannelInfo>> = _channels.asStateFlow()

    private val _clients = MutableStateFlow<Map<Int, ClientInfo>>(emptyMap())
    val clients: StateFlow<Map<Int, ClientInfo>> = _clients.asStateFlow()

    val micMuted: StateFlow<Boolean> =
        settingsRepository.masterMuted.stateIn(scope, SharingStarted.Eagerly, false)

    fun connect(bookmark: BookmarkEntity) {
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value is ConnectionState.Connected
        ) return

        lastBookmark = bookmark
        _channels.value = emptyMap()
        _clients.value = emptyMap()
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            try {
                val identity = identityRepository.load()
                    ?: throw IllegalStateException("identity not ready")
                val nickname = bookmark.nickname?.takeIf { it.isNotBlank() }
                    ?: settingsRepository.defaultNickname.first()

                val newSocket = LocalTeamspeakClientSocket()
                synchronized(socketLock) {
                    socket?.disconnectSafely()
                    socket = newSocket
                }
                newSocket.setIdentity(identity)
                newSocket.setNickname(nickname)
                newSocket.addListener(Ts3ListenerImpl(this@ConnectionManager))
                newSocket.setExceptionHandler { e ->
                    markDisconnected(e.message ?: "connection error")
                }

                withContext(Dispatchers.IO) {
                    newSocket.connect(
                        InetSocketAddress(bookmark.address, bookmark.port),
                        bookmark.password ?: "",
                        10_000L
                    )
                    newSocket.subscribeAll()
                    newSocket.listClients().forEach { c ->
                        // listClients lacks the unique id; events carry it and win on merge
                        upsertClient(
                            id = c.id,
                            nickname = c.nickname,
                            uniqueId = null,
                            channelId = 0,
                            preserveChannel = false
                        )
                    }
                }

                selfId = newSocket.getClientId()
                bookmarkRepository.touchLastConnected(bookmark.id)
                startWatchdog()
                Log.i(TAG, "connected to ${bookmark.address}:${bookmark.port} as #$selfId")
            } catch (e: Exception) {
                Log.w(TAG, "connect failed: ${e.message}", e)
                markDisconnected(e.message ?: "connection failed")
            }
        }
    }

    /**
     * ts3j does not reliably surface lost connections (a dead TCP socket can
     * block its read loop forever). Every interval we round-trip a `whoami`
     * command; when it fails, the connection is gone.
     */
    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (_connectionState.value !is ConnectionState.Connected) return@launch
                val current = synchronized(socketLock) { socket } ?: return@launch
                val alive = withContext(Dispatchers.IO) {
                    try {
                        current.executeCommand(SingleCommand("whoami", ProtocolRole.CLIENT))
                            .get(WATCHDOG_COMMAND_TIMEOUT_MS)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (!alive) {
                    markDisconnected("connection lost")
                    return@launch
                }
            }
        }
    }

    fun reconnect() {
        lastBookmark?.let { connect(it) }
    }

    /** User-initiated full disconnect: returns to Idle (no banner, service stops). */
    fun disconnect() {
        scope.launch {
            synchronized(socketLock) { socket?.disconnectSafely() }
            _connectionState.value = ConnectionState.Idle
        }
    }

    fun toggleMic() {
        scope.launch { settingsRepository.setMasterMuted(!micMuted.value) }
    }

    fun markConnected(serverName: String) {
        _connectionState.value = ConnectionState.Connected(serverName)
    }

    fun markDisconnected(reason: String?) {
        val current = _connectionState.value
        if (current is ConnectionState.Idle) return
        Log.w(TAG, "markDisconnected: $reason (was $current)")
        synchronized(socketLock) {
            socket?.disconnectSafely()
            socket = null
        }
        _connectionState.value = ConnectionState.Disconnected(reason)
    }

    // --- Channel/client state (called from ts3j listener threads) ---

    fun upsertChannel(id: Int, parentId: Int, name: String, order: Int) {
        _channels.value = _channels.value + (id to ChannelInfo(id, parentId, name, order))
    }

    fun upsertClient(id: Int, nickname: String, uniqueId: String?, channelId: Int, preserveChannel: Boolean = true) {
        val current = _clients.value[id]
        val effectiveChannel = if (preserveChannel && current != null) current.channelId else channelId
        _clients.value = _clients.value + (id to ClientInfo(
            id = id,
            nickname = nickname,
            uniqueId = uniqueId ?: current?.uniqueId,
            channelId = effectiveChannel,
            isSelf = id == selfId
        ))
    }

    fun removeClient(id: Int) {
        _clients.value = _clients.value - id
    }

    fun moveClient(id: Int, channelId: Int) {
        val current = _clients.value[id] ?: return
        _clients.value = _clients.value + (id to current.copy(channelId = channelId))
    }

    private fun LocalTeamspeakClientSocket.disconnectSafely() {
        try {
            disconnect()
        } catch (_: Exception) {
            // socket already closed
        }
    }

    companion object {
        private const val TAG = "TSPhone"
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val WATCHDOG_COMMAND_TIMEOUT_MS = 5_000L
    }
}
