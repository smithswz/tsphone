package com.smithswz.tsphone.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.smithswz.tsphone.ts3.ChannelInfo
import com.smithswz.tsphone.ts3.ClientInfo
import com.smithswz.tsphone.ts3.ConnectionState
import com.smithswz.tsphone.ui.common.ChannelTree

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(onExit: () -> Unit) {
    val app = LocalContext.current.applicationContext as TSPhoneApp
    val vm: ServerViewModel = viewModel { ServerViewModel(app.container.connectionManager) }
    val connectionState by vm.connectionState.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val clients by vm.clients.collectAsStateWithLifecycle()
    val micMuted by vm.micMuted.collectAsStateWithLifecycle()

    val serverName = (connectionState as? ConnectionState.Connected)?.serverName
        ?: stringResource(R.string.title_server)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(serverName) },
            actions = {
                IconButton(onClick = vm::toggleMic) {
                    Icon(
                        if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = stringResource(R.string.toggle_mic)
                    )
                }
                IconButton(onClick = {
                    vm.exit()
                    onExit()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = stringResource(R.string.exit_server))
                }
            }
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (connectionState) {
                is ConnectionState.Connecting -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.connecting))
                }
                is ConnectionState.Disconnected -> DisconnectPanel(reason = (connectionState as ConnectionState.Disconnected).reason, onReconnect = vm::reconnect)
                else -> ChannelTree(channels = channels, clients = clients, onChannelClick = {}, onClientClick = {})
            }
        }
    }
}

@Composable
private fun DisconnectPanel(reason: String?, onReconnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.CallEnd, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.disconnected), style = MaterialTheme.typography.titleMedium)
        reason?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onReconnect) { Text(stringResource(R.string.reconnect)) }
    }
}
