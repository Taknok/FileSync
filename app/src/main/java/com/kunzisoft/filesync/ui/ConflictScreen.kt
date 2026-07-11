package com.kunzisoft.filesync.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kunzisoft.filesync.storage.FileMetadata

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictScreen(viewModel: ProviderViewModel) {
    val conflicts by viewModel.conflicts.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Conflicts") }) }
    ) { padding ->
        if (conflicts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No active conflicts", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conflicts) { conflict ->
                    ConflictItem(
                        metadata = conflict,
                        onResolve = { action -> viewModel.resolveConflict(conflict, action) }
                    )
                }
            }
        }
    }
}

@Composable
fun ConflictItem(
    metadata: FileMetadata,
    onResolve: (ConflictAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = "Conflict", tint = Color.Red)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = metadata.name, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = "Path: ${metadata.path}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = "The remote file has changed. Choose how to proceed:",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.End)
            ) {
                TextButton(onClick = { onResolve(ConflictAction.DISCARD_LOCAL) }) {
                    Text("Discard Local")
                }
                Button(onClick = { onResolve(ConflictAction.FORCE_OVERWRITE) }) {
                    Text("Force Overwrite")
                }
            }
        }
    }
}
