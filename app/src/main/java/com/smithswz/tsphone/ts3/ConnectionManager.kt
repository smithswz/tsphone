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
    val isSelf: Boolean
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
    private val notificationHelper: NotificationHelper
) {

    private val socketLock = Any()
    private var socket: LocalTeamspeakClientSocket? = null
    private var selfId: Int = 0
    private var lastBookmark: BookmarkEntity? = null

    private val opusCodec = OpusCodec()
    private val micSource = TSMicSource(context, settingsRepository, scope, opusCodec)

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
                newSocket.setMicrophone(micSource)
                micSource.start()
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
        micSource.stop()
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
