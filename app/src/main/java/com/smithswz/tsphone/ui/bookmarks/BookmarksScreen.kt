package com.smithswz.tsphone.ui.bookmarks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smithswz.tsphone.R
import com.smithswz.tsphone.TSPhoneApp
import com.smithswz.tsphone.data.db.BookmarkEntity
import com.smithswz.tsphone.data.prefs.IdentityState
import com.smithswz.tsphone.ts3.TS3Service

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    onOpenSettings: () -> Unit,
    onConnect: (BookmarkEntity) -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as TSPhoneApp
    val vm: BookmarksViewModel = viewModel {
        BookmarksViewModel(app.container.bookmarkRepository, app.container.identityState)
    }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val identity by vm.identity.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<BookmarkEntity?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingConnect by remember { mutableStateOf<BookmarkEntity?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        pendingConnect?.let { bookmark ->
            pendingConnect = null
            if (result.values.all { granted -> granted }) {
                ContextCompat.startForegroundService(context, TS3Service.connectIntent(context, bookmark.id))
                onConnect(bookmark)
            }
        }
    }

    fun connectWithPermissions(bookmark: BookmarkEntity) {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            ContextCompat.startForegroundService(context, TS3Service.connectIntent(context, bookmark.id))
            onConnect(bookmark)
        } else {
            pendingConnect = bookmark
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_bookmarks)) }, actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.title_settings))
            }
        }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_server))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            IdentityBanner(identity)
            if (bookmarks.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.no_bookmarks), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            onClick = { connectWithPermissions(bookmark) },
                            onEdit = { editing = bookmark },
                            onDelete = { vm.delete(bookmark) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog || editing != null) {
        BookmarkDialog(
            initial = editing,
            onDismiss = { showAddDialog = false; editing = null },
            onSave = { name, address, port, password, nickname ->
                val current = editing
                if (current == null) {
                    vm.add(name, address, port, password, nickname)
                } else {
                    vm.update(current.copy(name = name, address = address, port = port, password = password, nickname = nickname))
                }
                showAddDialog = false
                editing = null
            }
        )
    }
}

@Composable
private fun IdentityBanner(identity: IdentityState) {
    when (identity) {
        is IdentityState.Generating -> Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.identity_generating))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        is IdentityState.Ready -> Text(
            stringResource(R.string.identity_ready, identity.uniqueId),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        is IdentityState.Failed -> Text(
            stringResource(R.string.identity_failed, identity.message),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun BookmarkRow(bookmark: BookmarkEntity, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bookmark.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${bookmark.address}:${bookmark.port}${bookmark.nickname?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.edit_server))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun BookmarkDialog(
    initial: BookmarkEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 9987).toString()) }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var nickname by remember { mutableStateOf(initial?.nickname ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.add_server else R.string.edit_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text(stringResource(R.string.field_address)) }, singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.field_port)) }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text(stringResource(R.string.field_password_optional)) }, singleLine = true)
                OutlinedTextField(value = nickname, onValueChange = { nickname = it }, label = { Text(stringResource(R.string.field_nickname_optional)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val portValue = port.toIntOrNull() ?: 9987
                    onSave(name.trim(), address.trim(), portValue, password.ifBlank { null }, nickname.ifBlank { null })
                },
                enabled = name.isNotBlank() && address.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
