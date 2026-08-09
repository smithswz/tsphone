package com.smithswz.tsphone.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smithswz.tsphone.R
import com.smithswz.tsphone.ts3.ChannelInfo
import com.smithswz.tsphone.ts3.ClientInfo

/**
 * Channel tree with expandable nodes. Channels are displayed with their
 * online users underneath; clients of collapsed channels stay hidden.
 */
@Composable
fun ChannelTree(
    channels: Map<Int, ChannelInfo>,
    clients: Map<Int, ClientInfo>,
    speakingClients: Set<Int>,
    onChannelClick: (ChannelInfo) -> Unit,
    onClientClick: (ClientInfo) -> Unit
) {
    val roots = remember(channels) {
        channels.values.filter { it.parentId == 0 }.sortedWith(compareBy({ it.order }, { it.name }))
    }

    LazyColumn(Modifier.fillMaxWidth()) {
        items(roots, key = { it.id }) { channel ->
            ChannelRow(
                channel = channel,
                channels = channels,
                clients = clients,
                speakingClients = speakingClients,
                depth = 0,
                onChannelClick = onChannelClick,
                onClientClick = onClientClick
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelInfo,
    channels: Map<Int, ChannelInfo>,
    clients: Map<Int, ClientInfo>,
    speakingClients: Set<Int>,
    depth: Int,
    onChannelClick: (ChannelInfo) -> Unit,
    onClientClick: (ClientInfo) -> Unit
) {
    val children = remember(channels, channel.id) {
        channels.values.filter { it.parentId == channel.id }
            .sortedWith(compareBy({ it.order }, { it.name }))
    }
    val members = remember(channels, clients, channel.id) {
        clients.values.filter { it.channelId == channel.id }
    }
    var expanded by remember(channel.id) { mutableStateOf(true) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onChannelClick(channel) }
                .padding(start = (depth * 16).dp, end = 16.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The arrow toggles expansion; the row body opens the channel chat.
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp).clickable { expanded = !expanded },
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            Text(
                text = "(${members.size})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (expanded) {
            members.forEach { client ->
                ClientRow(
                    client = client,
                    depth = depth + 1,
                    isSpeaking = client.id in speakingClients,
                    onClick = { onClientClick(client) }
                )
            }
            children.forEach { child ->
                ChannelRow(
                    channel = child,
                    channels = channels,
                    clients = clients,
                    speakingClients = speakingClients,
                    depth = depth + 1,
                    onChannelClick = onChannelClick,
                    onClientClick = onClientClick
                )
            }
        }
    }
}

@Composable
private fun ClientRow(client: ClientInfo, depth: Int, isSpeaking: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (depth * 16 + 24).dp, end = 16.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (client.isSelf) "${client.nickname} ${stringResource(R.string.self_marker)}" else client.nickname,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
            color = if (client.isSelf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
        if (isSpeaking) {
            Icon(
                Icons.Default.VolumeUp,
                contentDescription = stringResource(R.string.speaking_indicator),
                modifier = Modifier.padding(start = 6.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
