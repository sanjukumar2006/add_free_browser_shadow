package com.nova.browser.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.browser.NovaApp
import com.nova.browser.data.SearchEngines
import com.nova.browser.data.Site
import com.nova.browser.vpn.VpnStatus

@Composable
fun SearchBar(app: NovaApp, private: Boolean, onSubmit: (String) -> Unit, modifier: Modifier = Modifier, initial: String = "") {
    var text by remember(initial) { mutableStateOf(initial) }
    var menu by remember { mutableStateOf(false) }
    val engine = SearchEngines.get(app.prefs, if (private) app.prefs.privateSearchEngineId else app.prefs.searchEngineId)
    val shieldsOn = app.prefs.adBlockEnabled
    Surface(modifier.height(46.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Shields button (tap = toggle), like the lion icon inside Brave's address bar.
            IconButton(onClick = { app.prefs.adBlockEnabled = !app.prefs.adBlockEnabled; app.prefs.save() }, modifier = Modifier.size(40.dp)) {
                Icon(if (private) Icons.Default.VisibilityOff else Icons.Default.Shield, "Shields",
                    tint = if (shieldsOn) BraveOrange else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            BasicTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f), singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(BraveOrange),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = { onSubmit(SearchEngines.resolve(text, engine)) }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("Search or type web address", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    }
                })
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Search, "Search engine", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(menu, { menu = false }) {
                    SearchEngines.all(app.prefs).forEach { e ->
                        DropdownMenuItem(text = { Text(e.name) }, onClick = {
                            if (private) app.prefs.privateSearchEngineId = e.id else app.prefs.searchEngineId = e.id
                            app.prefs.save(); menu = false
                        }, trailingIcon = { if (e.id == engine.id) Icon(Icons.Default.Check, null) })
                    }
                }
            }
        }
    }
}

@Composable
fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp),
        color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = .18f) else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp)); Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun fmtCount(n: Int) = if (n >= 1000) "%,d".format(n) else n.toString()
private fun fmtSaved(b: Long) = when { b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0); b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0); b >= 1024 -> "${b / 1024} KB"; else -> "$b B" }
// Same rough heuristic Brave documents: ~50 ms saved per blocked request.
private fun fmtTime(blocked: Int): String { val s = blocked * 50L / 1000; return when { s >= 3600 -> "${s / 3600}h ${s % 3600 / 60}m"; s >= 60 -> "${s / 60}m ${s % 60}s"; else -> "${s}s" } }

@Composable
private fun StatBlock(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(value, color = color, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun HomeScreen(app: NovaApp, onOpen: (String) -> Unit, onPrivate: () -> Unit, onDownloads: () -> Unit, onSettings: () -> Unit, onVpn: () -> Unit) {
    // The nebula wallpaper is drawn behind the Scaffold (MainActivity); here every card becomes translucent "glass"
    // with light text, whatever the app theme is, so it stays readable on the image.
    val glass = ShadowDark.copy(surfaceVariant = Color(0xFF14121F).copy(alpha = 0.62f))
    MaterialTheme(colorScheme = glass, typography = MaterialTheme.typography) {
        Surface(color = Color.Transparent, contentColor = glass.onBackground) { HomeContent(app, onOpen) }
    }
}

@Composable
private fun HomeContent(app: NovaApp, onOpen: (String) -> Unit) {
    val prefs = app.prefs
    val quick = remember { listOf(Site("Wikipedia", "https://wikipedia.org"), Site("GitHub", "https://github.com"), Site("Reddit", "https://reddit.com"),
        Site("News", "https://news.ycombinator.com"), Site("Maps", "https://openstreetmap.org"), Site("Archive", "https://archive.org")) }
    val blocked = prefs.adsBlocked + prefs.trackersBlocked
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Spacer(Modifier.height(8.dp)); SearchBar(app, false, onOpen, Modifier.fillMaxWidth()) }
        // Privacy stats card (Brave's "Trackers & ads blocked / Bandwidth saved / Time saved").
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, null, tint = if (prefs.adBlockEnabled) BraveOrange else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (prefs.adBlockEnabled) "Shields are up" else "Shields are down", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(prefs.adBlockEnabled, { prefs.adBlockEnabled = it; prefs.save() },
                            colors = SwitchDefaults.colors(checkedTrackColor = BraveOrange))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        StatBlock(fmtCount(blocked), "Trackers & ads blocked", BraveOrange, Modifier.weight(1f))
                        StatBlock(fmtSaved(prefs.bytesSaved), "Bandwidth saved", BravePurple, Modifier.weight(1f))
                        StatBlock(fmtTime(blocked), "Time saved", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                    }
                }
            }
        }
        item { Text("Top sites", style = MaterialTheme.typography.titleMedium) }
        item {
            val all = remember(prefs.history, prefs.visits) { quick + prefs.frequent().filter { f -> quick.none { it.url == f.url } }.take(2) }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(190.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), userScrollEnabled = false) {
                items(all) { s -> SiteTile(s) { onOpen(s.url) } }
            }
        }
        if (prefs.bookmarks.isNotEmpty()) {
            item { Text("Favorites", style = MaterialTheme.typography.titleMedium) }
            items(prefs.bookmarks.take(5)) { SiteRow(it, Icons.Default.Star) { onOpen(it.url) } }
        }
        if (prefs.history.isNotEmpty()) {
            item { Text("Recent history", style = MaterialTheme.typography.titleMedium) }
            items(prefs.history.take(6)) { SiteRow(it, Icons.Default.History) { onOpen(it.url) } }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun SiteTile(s: Site, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(s.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = BraveOrange)
        }
        Spacer(Modifier.height(6.dp)); Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SiteRow(s: Site, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = BraveOrange); Spacer(Modifier.width(12.dp))
            Column {
                Text(s.title.ifBlank { s.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(s.url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
