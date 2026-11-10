package com.nova.browser.ui

import androidx.compose.foundation.*
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
import com.nova.browser.download.DownloadItem
import com.nova.browser.download.FileCategory
import com.nova.browser.engine.Tab
import kotlinx.coroutines.delay

// ---------------- Tabs ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(app: NovaApp, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val tm = app.tabs
    var query by remember { mutableStateOf("") }
    var privateView by remember { mutableStateOf(tm.current?.isPrivate == true) }
    val shown = tm.tabs.filter { it.isPrivate == privateView && (query.isBlank() || it.title.contains(query, true) || it.url.contains(query, true)) }
        .sortedByDescending { it.pinned }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).heightIn(min = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(!privateView, { privateView = false }, { Text("Tabs") })
                FilterChip(privateView, { privateView = true }, { Text("Private") }, leadingIcon = { Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp)) })
                Spacer(Modifier.weight(1f))
                TextButton({ tm.closeAll(privateView) }) { Text("Close all") }
                FilledTonalButton({ tm.open("", privateView); onDismiss() }) { Icon(Icons.Default.Add, null); Text("New") }
            }
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search tabs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { t ->
                    Surface(onClick = { onSelect(t.id) }, shape = RoundedCornerShape(16.dp),
                        color = if (t.id == tm.currentId) BraveOrange.copy(.15f) else MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (t.pinned) Icon(Icons.Default.PushPin, null, Modifier.size(16.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                                Text(t.url.ifBlank { "Home" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton({ t.pinned = !t.pinned }) { Icon(if (t.pinned) Icons.Default.PushPin else Icons.Default.PushPin, "Pin") }
                            IconButton({ tm.close(t.id) }) { Icon(Icons.Default.Close, "Close") }
                        }
                    }
                }
                if (!privateView && tm.recentlyClosed.isNotEmpty()) {
                    item { Text("Recently closed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    items(tm.recentlyClosed.toList()) { s ->
                        SiteRow(s, Icons.Default.Restore) { tm.restoreClosed(s); onDismiss() }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ---------------- Downloads ----------------
@Composable
fun DownloadsScreen(app: NovaApp) {
    var items by remember { mutableStateOf(emptyList<DownloadItem>()) }
    var cat by remember { mutableStateOf<FileCategory?>(null) }
    var sort by remember { mutableStateOf("date") }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { items = app.downloads.list(); delay(1000) } }
    val list = items.filter { (cat == null || it.category == cat) && (q.isBlank() || it.title.contains(q, true)) }.let {
        when (sort) { "name" -> it.sortedBy { d -> d.title.lowercase() }; "type" -> it.sortedBy { d -> d.category.ordinal }; else -> it.sortedByDescending { d -> d.time } }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Downloads", style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(q, { q = it }, placeholder = { Text("Search downloads") }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) })
        LazyRowChips(cat, { cat = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("date", "name", "type").forEach { s -> FilterChip(sort == s, { sort = s }, { Text("Sort: $s") }) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { d ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(14.dp)) {
                        Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text("${d.category.label} · " + when { d.finished -> "Done"; d.paused -> "Waiting for network"; d.running -> "${(d.progress * 100).toInt()}%"; else -> "Failed" },
                            style = MaterialTheme.typography.labelSmall)
                        if (d.running || d.paused) LinearProgressIndicator(progress = { d.progress }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Row {
                            if (d.finished) { TextButton({ app.downloads.open(d) }) { Text("Open") }; TextButton({ app.downloads.share(d) }) { Text("Share") } }
                            TextButton({ app.downloads.delete(d.id) }) { Text(if (d.finished) "Delete" else "Cancel") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyRowChips(cat: FileCategory?, onCat: (FileCategory?) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(cat == null, { onCat(null) }, { Text("All") }) }
        items(FileCategory.values().toList()) { c -> FilterChip(cat == c, { onCat(c) }, { Text(c.label) }) }
    }
}
