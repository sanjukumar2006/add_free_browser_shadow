package com.nova.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nova.browser.NovaApp
import com.nova.browser.data.SearchEngines
import com.nova.browser.data.ThemeMode

@Composable
fun SettingsScreen(app: NovaApp) {
    val p = app.prefs
    var cn by remember { mutableStateOf("") }
    var ct by remember { mutableStateOf("") }
    var engines by remember { mutableStateOf(SearchEngines.all(p)) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.values().forEach { m -> FilterChip(p.theme == m, { p.theme = m; p.save() }, { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                Text("Text size: ${p.fontScale}%")
                Slider(p.fontScale.toFloat(), { p.fontScale = it.toInt(); p.save(); app.tabs.tabs.forEach { t -> t.webView?.settings?.textZoom = p.fontScale } }, valueRange = 70f..160f)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Desktop site by default", Modifier.weight(1f)); Switch(p.desktopMode, { p.desktopMode = it; p.save() }) }
                OutlinedTextField(p.homePage, { p.homePage = it; p.save() }, label = { Text("Custom home page (blank = Shadow home)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            Card {
                Text("Search engines", style = MaterialTheme.typography.titleLarge)
                Text("Default (normal tabs)", style = MaterialTheme.typography.titleMedium)
                engines.forEach { e -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(p.searchEngineId == e.id, { p.searchEngineId = e.id; p.save() }); Text(e.name) } }
                Text("Private tabs", style = MaterialTheme.typography.titleMedium)
                engines.forEach { e -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(p.privateSearchEngineId == e.id, { p.privateSearchEngineId = e.id; p.save() }); Text(e.name) } }
                Text("Onion search engines only load while the Tor network is on (Privacy tab).", style = MaterialTheme.typography.labelSmall)
                Text("Add custom engine", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(cn, { cn = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ct, { ct = it }, label = { Text("https URL with one %s, e.g. https://site/?q=%s") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                var ceErr by remember { mutableStateOf<String?>(null) }
                Button({ if (p.addCustomEngine(cn.trim(), ct.trim())) { engines = SearchEngines.all(p); cn = ""; ct = ""; ceErr = null } else ceErr = "Needs a name and an https URL containing exactly one %s" }) { Text("Add") }
                ceErr?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        }
        item {
            Card {
                Text("About", style = MaterialTheme.typography.titleLarge)
                Text("Shadow Browser 1.0 — uses Android System WebView (Chromium). Downloads are saved to Downloads/Shadow.", style = MaterialTheme.typography.bodyMedium)
                Text("Website permissions (camera, mic, location, notifications) are asked per request by the app/OS; manage them in Android Settings → Apps → Shadow → Permissions.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
