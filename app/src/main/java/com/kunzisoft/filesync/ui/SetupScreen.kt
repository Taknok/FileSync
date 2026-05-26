package com.kunzisoft.filesync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kunzisoft.filesync.storage.ProviderConfig

import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings

import androidx.compose.ui.platform.LocalContext
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(viewModel: ProviderViewModel) {
    val providers by viewModel.providers.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current

    LaunchedEffect(viewModel.refreshEvents) {
        viewModel.refreshEvents.collect { result ->
            when (result) {
                is ProviderViewModel.RefreshResult.Success -> {
                    Toast.makeText(context, "Refresh successful: ${result.providerName}", Toast.LENGTH_SHORT).show()
                }
                is ProviderViewModel.RefreshResult.Failure -> {
                    errorDialogMessage = "Failed to refresh ${result.providerName}: ${result.message}"
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("FileSync Setup") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Provider")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            items(providers) { provider ->
                ProviderItem(
                    config = provider,
                    onDelete = { viewModel.deleteProvider(it) },
                    onEdit = { editingProvider = it },
                    onRefresh = { viewModel.forceRefresh(it) }
                )
            }
        }

        if (showAddDialog) {
            ProviderDialog(
                title = "Add WebDAV Provider",
                onDismiss = { showAddDialog = false },
                onConfirm = { name, url, user, password ->
                    viewModel.addProvider(name, url, user, password)
                    showAddDialog = false
                }
            )
        }

        editingProvider?.let { provider ->
            ProviderDialog(
                title = "Edit WebDAV Provider",
                initialName = provider.name,
                initialUrl = provider.baseUrl ?: "",
                initialUser = provider.username ?: "",
                onDismiss = { editingProvider = null },
                onConfirm = { name, url, user, password ->
                    val updated = provider.copy(name = name, baseUrl = url, username = user)
                    viewModel.updateProvider(updated, password.ifBlank { null })
                    editingProvider = null
                }
            )
        }

        errorDialogMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { errorDialogMessage = null },
                title = { Text("Refresh Failed") },
                text = { Text(message) },
                confirmButton = {
                    TextButton(onClick = { errorDialogMessage = null }) {
                        Text("OK")
                    }
                }
            )
        }
    }
}

@Composable
fun ProviderItem(
    config: ProviderConfig,
    onDelete: (ProviderConfig) -> Unit,
    onEdit: (ProviderConfig) -> Unit,
    onRefresh: (ProviderConfig) -> Unit
) {
    ListItem(
        headlineContent = { Text(config.name) },
        supportingContent = { Text(config.baseUrl ?: "") },
        trailingContent = {
            Row {
                IconButton(onClick = { onRefresh(config) }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Force Refresh")
                }
                IconButton(onClick = { onEdit(config) }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = { onDelete(config) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    )
}

@Composable
fun ProviderDialog(
    title: String,
    initialName: String = "",
    initialUrl: String = "",
    initialUser: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = name, onValueChange = { name = it }, label = { Text("Account Name") })
                TextField(value = url, onValueChange = { url = it }, label = { Text("Server URL") })
                TextField(value = user, onValueChange = { user = it }, label = { Text("Username") })
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (initialName.isEmpty()) "Password" else "New Password (Optional)") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url, user, password) }) {
                Text(if (initialName.isEmpty()) "Add" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
