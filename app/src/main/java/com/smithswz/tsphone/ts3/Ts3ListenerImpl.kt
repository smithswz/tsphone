package com.smithswz.tsphone.ts3

import com.github.manevolent.ts3j.api.TextMessageTargetMode
import com.github.manevolent.ts3j.event.ClientJoinEvent
import com.github.manevolent.ts3j.event.ClientLeaveEvent
import com.github.manevolent.ts3j.event.ClientMovedEvent
import com.github.manevolent.ts3j.event.ChannelListEvent
import com.github.manevolent.ts3j.event.ConnectedEvent
import com.github.manevolent.ts3j.event.DisconnectedEvent
import com.github.manevolent.ts3j.event.TS3Listener
import com.github.manevolent.ts3j.event.TextMessageEvent
import com.smithswz.tsphone.data.db.MessageEntity

/**
 * Bridges ts3j events into [ConnectionManager] state. Called on ts3j's
 * connection thread — handlers must never block.
 */
class Ts3ListenerImpl(private val manager: ConnectionManager) : TS3Listener {

    override fun onChannelList(e: ChannelListEvent) {
        val map = e.getMap()
        val name = map["channel_name"] ?: return
        android.util.Log.w("TSPhone", "channellist: #${e.getChannelId()} '$name' parent=${map["channel_parent"]} order=${map["channel_order"]}")
        manager.upsertChannel(
            id = e.getChannelId(),
            parentId = map["channel_parent"]?.toIntOrNull() ?: 0,
            name = name,
            order = map["channel_order"]?.toIntOrNull() ?: 0
        )
    }

    override fun onClientJoin(e: ClientJoinEvent) {
        if (e.getClientType() != 0) return // skip server-query clients
        // The client's current channel arrives as "cfid" in notifycliententerview.
        val channelId = e.getMap()["cfid"]?.toIntOrNull() ?: 0
        android.util.Log.w("TSPhone", "clientjoin: #${e.getClientId()} '${e.getClientNickname()}' cfid=${e.getMap()["cfid"]}")
        manager.upsertClient(
            id = e.getClientId(),
            nickname = e.getClientNickname(),
            uniqueId = e.getUniqueClientIdentifier(),
            channelId = channelId
        )
    }

    override fun onClientLeave(e: ClientLeaveEvent) {
        manager.removeClient(e.getClientId())
    }

    override fun onClientMoved(e: ClientMovedEvent) {
        manager.moveClient(e.getClientId(), e.getTargetChannelId())
    }

    override fun onTextMessage(e: TextMessageEvent) {
        val body = e.getMessage()
        when (e.getTargetMode()) {
            TextMessageTargetMode.CHANNEL -> {
                // Route into the sender's channel session; fall back to the
                // channel the invoker is known to be in.
                val senderChannel = manager.clients.value[e.getInvokerId()]?.channelId
                val channelId = senderChannel ?: manager.currentChannelId
                if (channelId != null) {
                    manager.onIncomingMessage(
                        MessageEntity.channelKey(channelId),
                        MessageEntity.TYPE_CHANNEL,
                        e.getInvokerName(),
                        body
                    )
                }
            }
            TextMessageTargetMode.CLIENT -> {
                manager.onIncomingMessage(
                    MessageEntity.privateKey(e.getInvokerUniqueId()),
                    MessageEntity.TYPE_PRIVATE,
                    e.getInvokerName(),
                    body
                )
            }
            else -> {
                // Server-wide messages: show in the server root chat session.
                manager.onIncomingMessage(
                    MessageEntity.channelKey(0),
                    MessageEntity.TYPE_CHANNEL,
                    e.getInvokerName(),
                    body
                )
            }
        }
    }

    override fun onConnected(e: ConnectedEvent) {
        manager.markConnected(e.getMap()["virtualserver_name"] ?: "TeamSpeak")
    }

    override fun onDisconnected(e: DisconnectedEvent) {
        manager.markDisconnected(null)
    }
}
