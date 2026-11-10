package com.nova.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.browser.NovaApp

/** Browsing history (normal tabs only): search, delete single entries, or clear everything. */
@Composable
fun HistoryScreen(app: NovaApp, onOpen: (String) -> Unit) {
    val prefs = app.prefs
    var q by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val list = prefs.history.filter { q.isBlank() || it.title.contains(q, true) || it.url.contains(q, true) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton({ confirmClear = true }, enabled = prefs.history.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Clear all")
            }
        }
        OutlinedTextField(q, { q = it }, placeholder = { Text("Search history") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) })
        if (list.isEmpty()) {
            Text(if (prefs.history.isEmpty()) "No browsing history." else "No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.url }) { s ->
                Surface(onClick = { onOpen(s.url) }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, null, tint = BraveOrange); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title.ifBlank { s.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(s.url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton({ prefs.removeHistory(s.url) }) { Icon(Icons.Default.Close, "Delete from history") }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear browsing history?") },
        text = { Text("All ${prefs.history.size} saved history entries will be deleted. This can't be undone.") },
        confirmButton = { TextButton({ prefs.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } }
    )
}
