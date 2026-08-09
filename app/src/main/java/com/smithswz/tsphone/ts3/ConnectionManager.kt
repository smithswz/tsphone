package com.smithswz.tsphone.ts3

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.github.manevolent.ts3j.command.SingleCommand
import com.github.manevolent.ts3j.protocol.ProtocolRole
import com.github.manevolent.ts3j.protocol.TS3DNS
import com.github.manevolent.ts3j.protocol.socket.client.LocalTeamspeakClientSocket
import com.smithswz.tsphone.audio.OpusCodec
import com.smithswz.tsphone.audio.TSMicSource
import com.smithswz.tsphone.audio.VoiceFrame
import com.smithswz.tsphone.audio.VoiceMixer
import com.smithswz.tsphone.data.db.BookmarkEntity
import com.smithswz.tsphone.data.db.MessageEntity
import com.smithswz.tsphone.data.prefs.IdentityRepository
import com.smithswz.tsphone.data.prefs.SettingsRepository
import com.smithswz.tsphone.data.repo.BookmarkRepository
import com.smithswz.tsphone.data.repo.ChatRepository
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
import java.net.InetAddress
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
    val isSelf: Boolean,
    val inputMuted: Boolean = false,
    val outputMuted: Boolean = false
)

/**
 * Process-wide owner of the TS3 socket. All state is exposed as StateFlows;
 * ts3j listener callbacks arrive on ts3j threads and only mutate flows, so the
 * UI can collect from anywhere.
 */
class ConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val identityRepository: IdentityRepository,
    private val settingsRepository: SettingsRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val chatRepository: ChatRepository,
    private val notificationHelper: NotificationHelper
) {

    private val socketLock = Any()
    private var socket: LocalTeamspeakClientSocket? = null
    private var selfId: Int = 0
    private var lastBookmark: BookmarkEntity? = null

    private val opusCodec = OpusCodec()
    private val micSource = TSMicSource(context, settingsRepository, scope, opusCodec)
    private val voiceMixer = VoiceMixer(context, opusCodec, settingsRepository, scope)

    /** Clients currently speaking (voice receive). */
    val speakingClients: StateFlow<Set<Int>> = voiceMixer.speakingClients

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _channels = MutableStateFlow<Map<Int, ChannelInfo>>(emptyMap())
    val channels: StateFlow<Map<Int, ChannelInfo>> = _channels.asStateFlow()

    private val _clients = MutableStateFlow<Map<Int, ClientInfo>>(emptyMap())
    val clients: StateFlow<Map<Int, ClientInfo>> = _clients.asStateFlow()

    val micMuted: StateFlow<Boolean> =
        settingsRepository.masterMuted.stateIn(scope, SharingStarted.Eagerly, false)

    val outputMuted: StateFlow<Boolean> =
        settingsRepository.outputMuted.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * The test server sends cfid=0 in enter-view events and denies
     * channellist/clientlist, so the per-client channel must come from
     * clientinfo queries after the join events settle.
     */
    private fun resolveClientChannels() {
        scope.launch {
            delay(2_000)
            val s = synchronized(socketLock) { socket } ?: return@launch
            val known = _clients.value.keys.toList()
            for (clid in known) {
                withContext(Dispatchers.IO) {
                    runCatching { s.getClientInfo(clid) }
                        .onSuccess { c ->
                            c.getMap()["cid"]?.toIntOrNull()?.takeIf { it != 0 }?.let { cid ->
                                moveClient(clid, cid)
                            }
                        }
                        .onFailure { Log.w(TAG, "clientinfo #$clid: ${it.message}") }
                }
            }
        }
    }

    /** Bumped after every Room message write; chat screens reload on change. */
    private val _messagesVersion = MutableStateFlow(0L)
    val messagesVersion: StateFlow<Long> = _messagesVersion.asStateFlow()

    @Volatile
    private var selfNickname = ""

    /** The channel our own client is currently in. */
    val currentChannelId: Int?
        get() = _clients.value[selfId]?.channelId?.takeIf { it != 0 }

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
                selfNickname = nickname
                newSocket.addListener(Ts3ListenerImpl(this@ConnectionManager))
                newSocket.setExceptionHandler { e ->
                    markDisconnected(e.message ?: "connection error")
                }

                withContext(Dispatchers.IO) {
                    // Resolve like the official client: TSDNS SRV lookup first
                    // (`_ts3._udp.<host>` — custom TS3 domains often have NO
                    // A record and a non-default port), then plain A-record
                    // fallback. ts3j NPEs on unresolved addresses otherwise.
                    val target = resolveTarget(bookmark)
                    newSocket.connect(target, bookmark.password ?: "", 10_000L)
                    // Best-effort: some servers deny these to normal clients;
                    // the channel tree still populates from subscription events.
                    runCatching { newSocket.subscribeAll() }
                        .onFailure { Log.w(TAG, "subscribeAll denied: ${it.message}") }
                    // The full channel list comes from the channellist command;
                    // subscription events only cover changes.
                    runCatching {
                        newSocket.getChannels().get().forEach { ch ->
                            val m = ch.getMap()
                            val name = m["channel_name"] ?: return@forEach
                            upsertChannel(
                                id = ch.getId(),
                                parentId = m["channel_parent"]?.toIntOrNull() ?: 0,
                                name = name,
                                order = m["channel_order"]?.toIntOrNull() ?: 0
                            )
                        }
                    }.onFailure { Log.w(TAG, "channellist denied: ${it.message}") }
                    runCatching { newSocket.listClients() }
                        .onFailure { Log.w(TAG, "listClients denied: ${it.message}") }
                        .getOrDefault(emptyList())
                        .forEach { c ->
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
                resolveClientChannels()
                // Resync our persisted mute states with the server.
                val micMutedNow = settingsRepository.masterMuted.first()
                val outMutedNow = settingsRepository.outputMuted.first()
                sendClientUpdate(
                    "client_input_muted" to if (micMutedNow) "1" else "0",
                    "client_output_muted" to if (outMutedNow) "1" else "0"
                )
                newSocket.setMicrophone(micSource)
                newSocket.setVoiceHandler { body ->
                    voiceMixer.offer(VoiceFrame(body.getClientId(), body.getCodecType(), body.getCodecData()))
                }
                newSocket.setWhisperHandler { body ->
                    voiceMixer.offer(VoiceFrame(body.getClientId(), body.getCodecType(), body.getCodecData()))
                }
                micSource.start()
                voiceMixer.start()
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
            micSource.stop()
            voiceMixer.stop()
            synchronized(socketLock) { socket?.disconnectSafely() }
            _connectionState.value = ConnectionState.Idle
        }
    }

    fun toggleMic() {
        scope.launch {
            val muted = !micMuted.value
            settingsRepository.setMasterMuted(muted)
            sendClientUpdate("client_input_muted" to if (muted) "1" else "0")
        }
    }

    val speakerOn: StateFlow<Boolean> =
        settingsRepository.speakerOn.stateIn(scope, SharingStarted.Eagerly, true)

    fun setSpeaker(on: Boolean) {
        scope.launch { settingsRepository.setSpeakerOn(on) }
    }

    fun toggleOutputMute() {
        scope.launch {
            val muted = !outputMuted.value
            settingsRepository.setOutputMuted(muted)
            sendClientUpdate("client_output_muted" to if (muted) "1" else "0")
        }
    }

    /** Broadcasts our mute states; also called once per connect to resync. */
    private fun sendClientUpdate(vararg params: Pair<String, String>) {
        val socket = synchronized(socketLock) { socket } ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    socket.executeCommand(
                        SingleCommand(
                            "clientupdate",
                            ProtocolRole.CLIENT,
                            *params.map { (k, v) -> com.github.manevolent.ts3j.command.parameter.CommandSingleParameter(k, v) }.toTypedArray()
                        )
                    ).get(5_000)
                }.onFailure { Log.w(TAG, "clientupdate failed: ${it.message}") }
            }
        }
    }

    fun sendChannelMessage(channelId: Int, text: String) {
        scope.launch {
            val socket = synchronized(socketLock) { socket } ?: return@launch
            runCatching { socket.sendChannelMessage(channelId, text) }
                .onSuccess {
                    persistOutgoing(MessageEntity.channelKey(channelId), MessageEntity.TYPE_CHANNEL, selfNickname, text)
                }
                .onFailure { Log.w(TAG, "sendChannelMessage failed: ${it.message}") }
        }
    }

    fun sendPrivateMessage(peerUid: String, text: String) {
        scope.launch {
            val socket = synchronized(socketLock) { socket } ?: return@launch
            val client = _clients.value.values.firstOrNull { it.uniqueId == peerUid }
                ?: return@launch
            runCatching { socket.sendPrivateMessage(client.id, text) }
                .onSuccess {
                    persistOutgoing(
                        MessageEntity.privateKey(peerUid),
                        MessageEntity.TYPE_PRIVATE,
                        client.nickname,
                        text
                    )
                }
                .onFailure { Log.w(TAG, "sendPrivateMessage failed: ${it.message}") }
        }
    }

    /** Poke from another client → heads-up notification (tap does nothing). */
    fun onPoke(invokerName: String, message: String) {
        notificationHelper.notify(
            System.currentTimeMillis().toInt(),
            notificationHelper.pokeNotification(invokerName, message)
        )
    }

    /** Incoming text message from the listener (ts3j thread). */
    fun onIncomingMessage(sessionKey: String, sessionType: String, senderName: String, body: String) {
        scope.launch {
            chatRepository.insert(
                MessageEntity(
                    sessionKey = sessionKey,
                    sessionType = sessionType,
                    direction = MessageEntity.DIRECTION_IN,
                    senderName = senderName,
                    peerName = senderName,
                    body = body,
                    ts = System.currentTimeMillis()
                )
            )
            _messagesVersion.value++
        }
    }

    private fun persistOutgoing(sessionKey: String, sessionType: String, peerName: String, body: String) {
        scope.launch {
            chatRepository.insert(
                MessageEntity(
                    sessionKey = sessionKey,
                    sessionType = sessionType,
                    direction = MessageEntity.DIRECTION_OUT,
                    senderName = selfNickname,
                    peerName = peerName,
                    body = body,
                    ts = System.currentTimeMillis()
                )
            )
            _messagesVersion.value++
        }
    }

    fun markConnected(serverName: String) {
        _connectionState.value = ConnectionState.Connected(serverName)
    }

    fun markDisconnected(reason: String?) {
        val current = _connectionState.value
        if (current is ConnectionState.Idle) return
        Log.w(TAG, "markDisconnected: $reason (was $current)")
        micSource.stop()
        voiceMixer.stop()
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

    fun upsertClient(
        id: Int,
        nickname: String,
        uniqueId: String?,
        channelId: Int,
        preserveChannel: Boolean = true,
        inputMuted: Boolean? = null,
        outputMuted: Boolean? = null
    ) {
        val current = _clients.value[id]
        val effectiveChannel = if (preserveChannel && current != null) current.channelId else channelId
        _clients.value = _clients.value + (id to ClientInfo(
            id = id,
            nickname = nickname,
            uniqueId = uniqueId ?: current?.uniqueId,
            channelId = effectiveChannel,
            isSelf = id == selfId,
            inputMuted = inputMuted ?: current?.inputMuted ?: false,
            outputMuted = outputMuted ?: current?.outputMuted ?: false
        ))
    }

    /** Partial mute-state update from notifyclientupdated. */
    fun updateClientMutes(id: Int, inputMuted: Boolean?, outputMuted: Boolean?) {
        val current = _clients.value[id] ?: return
        if (inputMuted == null && outputMuted == null) return
        _clients.value = _clients.value + (id to current.copy(
            inputMuted = inputMuted ?: current.inputMuted,
            outputMuted = outputMuted ?: current.outputMuted
        ))
    }

    fun removeClient(id: Int) {
        _clients.value = _clients.value - id
    }

    fun moveClient(id: Int, channelId: Int) {
        val current = _clients.value[id] ?: return
        _clients.value = _clients.value + (id to current.copy(channelId = channelId))
    }

    private fun resolveTarget(bookmark: BookmarkEntity): InetSocketAddress {
        // dnsjava reads /etc/resolv.conf which does not exist on Android —
        // feed it the phone's DNS servers via the dns.server property
        // (comma-separated) and refresh its cached config.
        val configured = runCatching { System.getProperty("dns.server") }.getOrNull()
        if (configured.isNullOrBlank()) {
            val servers = connectivityDnsServers().ifEmpty { FALLBACK_DNS }
            Log.w(TAG, "phone DNS servers: ${servers.joinToString(", ")}")
            if (servers.isNotEmpty()) {
                System.setProperty("dns.server", servers.joinToString(","))
                runCatching { org.xbill.DNS.ResolverConfig.refresh() }
            }
        }
        val ts3dns = runCatching { TS3DNS.lookup(bookmark.address) }
            .onFailure { Log.w(TAG, "TS3DNS.lookup failed: ${it.message}") }
            .getOrNull()
        ts3dns?.firstOrNull()?.let {
            Log.w(TAG, "TS3DNS resolved ${bookmark.address} -> ${it.address?.hostAddress}:${it.port}")
            return it
        }
        Log.w(TAG, "TS3DNS lookup returned nothing for ${bookmark.address}")
        // Fallback: plain A record with the bookmark's port
        val ip = try {
            InetAddress.getByName(bookmark.address)
        } catch (e: Exception) {
            throw IllegalStateException("无法解析服务器地址: ${bookmark.address}")
        }
        return InetSocketAddress(ip, bookmark.port)
    }

    private fun connectivityDnsServers(): List<String> {
        val result = mutableListOf<String>()
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.forEach { net ->
                cm.getLinkProperties(net)?.dnsServers?.forEach { result.add(it.hostAddress) }
            }
        }
        if (result.isEmpty()) {
            // Fallback: the DHCP-assigned DNS from wifi
            runCatching {
                val wifi = context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val dhcp = wifi.dhcpInfo
                listOf(dhcp.dns1, dhcp.dns2).forEach { dns ->
                    if (dns != 0) result.add(
                        "%d.%d.%d.%d".format(
                            (dns ushr 24) and 0xFF, (dns ushr 16) and 0xFF,
                            (dns ushr 8) and 0xFF, dns and 0xFF
                        )
                    )
                }
            }
        }
        return result.distinct()
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

        /** Public resolvers used when the phone's network hands out no DNS. */
        private val FALLBACK_DNS = listOf("223.5.5.5", "119.29.29.29", "114.114.114.114")
    }
}
