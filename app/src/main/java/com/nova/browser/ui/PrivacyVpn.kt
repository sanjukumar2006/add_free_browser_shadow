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
import androidx.compose.ui.unit.dp
import com.nova.browser.NovaApp
import com.nova.browser.tor.TorStatus
import com.nova.browser.vpn.VpnServer
import com.nova.browser.vpn.VpnStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.webkit.CookieManager
import android.webkit.WebStorage

private fun fmtBytes(b: Long): String = when { b > 1 shl 30 -> "%.2f GB".format(b / 1073741824.0); b > 1 shl 20 -> "%.1f MB".format(b / 1048576.0); b > 1024 -> "%d KB".format(b / 1024); else -> "$b B" }
private fun fmtDur(ms: Long): String { val s = ms / 1000; return "%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) }

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun PrivacyVpnScreen(app: NovaApp, requestVpnPermission: (onGranted: () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val vpn by app.vpn.state.collectAsState()
    val tor by app.tor.status.collectAsState()
    var servers by remember { mutableStateOf(emptyList<VpnServer>()) }
    var serverErr by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<VpnServer?>(null) }
    var token by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val prefs = app.prefs

    LaunchedEffect(Unit) { while (true) { app.vpn.tick(); now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(app.vpn.hasAccount()) {
        if (app.vpn.isConfigured && app.vpn.hasAccount()) app.vpn.fetchServers().onSuccess { servers = it; if (picked == null) picked = app.vpn.lastServer ?: it.firstOrNull() }.onFailure { serverErr = it.message }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Privacy & VPN", style = MaterialTheme.typography.headlineMedium) }

        // ---- VPN ----
        item {
            Card {
                Text("VPN (WireGuard)", style = MaterialTheme.typography.titleLarge)
                val connected = vpn.status == VpnStatus.CONNECTED
                Text(when (vpn.status) { VpnStatus.CONNECTED -> "Connected (WireGuard handshake verified)"; VpnStatus.CONNECTING -> "Connecting…"; VpnStatus.ERROR -> "Error: ${vpn.error}"; else -> "Not connected" },
                    color = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!app.vpn.isConfigured) {
                    Text(app.vpn.notConfiguredMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else if (!app.vpn.hasAccount()) {
                    Text("Enter the account token from your VPN provider backend to load servers.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(token, { token = it }, label = { Text("Account token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button({ if (token.isNotBlank()) { app.vpn.setAccountToken(token.trim()); token = "" } }) { Text("Save") }
                } else {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton({ open = true }, enabled = !connected) { Text(picked?.let { "${it.country}${if (it.city.isNotBlank()) " · " + it.city else ""}" } ?: "Select server") }
                        DropdownMenu(open, { open = false }) {
                            servers.forEach { s -> DropdownMenuItem(text = { Text("${s.country} · ${s.city}" + if (s.premium) "  ★" else "") }, onClick = { picked = s; open = false }) }
                        }
                    }
                    serverErr?.let { Text("Could not load servers: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                    vpn.diagnostics?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    if (connected) {
                        Text("Virtual IP: ${vpn.virtualIp}"); Text("Duration: ${fmtDur(now - vpn.connectedAt)}")
                        Text("↓ ${fmtBytes(vpn.rxBytes)} (${fmtBytes(vpn.rxSpeed)}/s)   ↑ ${fmtBytes(vpn.txBytes)} (${fmtBytes(vpn.txSpeed)}/s)")
                    }
                    Button(onClick = {
                        if (connected) { app.vpn.wantConnected = false; scope.launch { app.vpn.disconnect() } }
                        else picked?.let { s -> requestVpnPermission { app.vpn.wantConnected = true; scope.launch { app.vpn.connect(s) } } }
                    }, enabled = vpn.status != VpnStatus.CONNECTING && (connected || picked != null), modifier = Modifier.fillMaxWidth()) {
                        Text(if (connected) "Disconnect" else "Connect")
                    }
                    var auto by remember { mutableStateOf(app.vpn.autoReconnect) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Auto-reconnect", Modifier.weight(1f)); Switch(auto, { auto = it; app.vpn.autoReconnect = it }) }
                    TextButton({ app.vpn.openSystemVpnSettings() }) { Text("Enable kill switch (system Always-on VPN)") }
                    TextButton({ app.vpn.signOut() }) { Text("Sign out") }
                }
                Text("All IPv4 + IPv6 traffic and DNS are routed through the tunnel while connected. Kill switch is enforced by Android system settings, not by the app. Data handling is defined by your VPN provider's privacy policy.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- Tor ----
        item {
            Card {
                Text("Tor network", style = MaterialTheme.typography.titleLarge)
                Text("Routes ALL page traffic through Tor while on (normal and private tabs). Private tabs are NOT Tor: they only keep cookies/history separate on this device.", style = MaterialTheme.typography.bodyMedium)
                Text(when (tor) { TorStatus.ON -> "Connected to Tor"; TorStatus.STARTING -> "Bootstrapping…"; TorStatus.UNSUPPORTED -> "Not supported by this device's WebView"; TorStatus.ERROR -> "Failed: browsing is paused (no direct fallback)"; else -> "Off" })
                val torMsg by app.tor.message.collectAsState()
                torMsg?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                Text("While Tor is on, pages load only through Tor and downloads are disabled. Tor routing is not Tor Browser: no fingerprinting protection, WebRTC is not covered, and routing has not been leak-tested.", style = MaterialTheme.typography.labelSmall)
                if (tor != TorStatus.UNSUPPORTED) Button({ if (tor == TorStatus.ON || tor == TorStatus.STARTING) app.tor.stop() else app.tor.start() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (tor == TorStatus.ON || tor == TorStatus.STARTING) "Stop Tor" else "Start Tor") }
            }
        }

        // ---- Shields dashboard ----
        item {
            Card {
                Text("Shields", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Ad & tracker blocking", Modifier.weight(1f)); Switch(prefs.adBlockEnabled, { prefs.adBlockEnabled = it; prefs.save() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("YouTube ad protection", Modifier.weight(1f)); Switch(prefs.youtubeProtection, { prefs.youtubeProtection = it; prefs.save(); app.adBlock.settingsChanged() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Tracker protection", Modifier.weight(1f)); Switch(prefs.trackerProtection, { prefs.trackerProtection = it; prefs.save() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Hide ad boxes on pages", Modifier.weight(1f)); Switch(prefs.cosmeticFiltering, { prefs.cosmeticFiltering = it; prefs.save() }) }
                Text("Ads blocked: ${prefs.adsBlocked}   Trackers blocked: ${prefs.trackersBlocked}")
                Text("Counts are real blocked requests since install. Ads inside YouTube videos, ads from the same host as the content, and service-worker requests are not fully covered.", style = MaterialTheme.typography.labelSmall)
                var subTick by remember { mutableStateOf(0) }
                var subBusy by remember { mutableStateOf(false) }
                val statuses = remember(subTick) { app.adBlock.subscriptionStatus() }
                Text("Filter lists", style = MaterialTheme.typography.titleMedium)
                statuses.forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(st.title)
                            Text(if (st.lastUpdatedMs > 0) "Last updated ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(st.lastUpdatedMs))}" else "Not downloaded yet", style = MaterialTheme.typography.labelSmall)
                            st.error?.let { Text("Last update failed: $it. The cached copy is still used.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                        }
                        Switch(st.enabled, { prefs.setSubEnabled(st.id, it); subTick++; app.adBlock.settingsChanged() })
                    }
                }
                Button({
                    subBusy = true
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.adBlock.refreshSubscriptionsIfDue(force = true) }
                        subBusy = false; subTick++
                    }
                }, enabled = !subBusy, modifier = Modifier.fillMaxWidth()) { Text(if (subBusy) "Checking…" else "Check filter lists now") }
                Text("Lists refresh automatically at most once a week. Failed downloads keep the last copy.", style = MaterialTheme.typography.labelSmall)
                val ps = app.adBlock.parseStats()
                Text("Rules active: ${ps.blockRules} block, ${ps.exceptionRules} exceptions. Skipped: ${ps.cosmeticSkipped} cosmetic, ${ps.unsupportedSkipped} unsupported, ${ps.malformed} malformed.", style = MaterialTheme.typography.labelSmall)
                var listMsg by remember { mutableStateOf<String?>(null) }
                var listUrl by remember { mutableStateOf("") }
                OutlinedTextField(listUrl, { listUrl = it }, label = { Text("Custom filter list URL (https; ABP or hosts format)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button({ scope.launch(kotlinx.coroutines.Dispatchers.IO) { val err = app.adBlock.updateFromUrl(listUrl.trim()); listMsg = err ?: "List installed" } }, enabled = listUrl.startsWith("https://")) { Text("Update list") }
                listMsg?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                val wl = remember { mutableStateListOf<String>().apply { addAll(prefs.whitelist) } }
                if (wl.isNotEmpty()) Text("Whitelisted sites", style = MaterialTheme.typography.titleMedium)
                wl.toList().forEach { h -> Row(verticalAlignment = Alignment.CenterVertically) { Text(h, Modifier.weight(1f)); TextButton({ wl.remove(h); prefs.setWhitelist(wl.toSet()) }) { Text("Remove") } } }
            }
        }

        // ---- Privacy controls ----
        item {
            Card {
                Text("Privacy controls", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("HTTPS-only upgrade", Modifier.weight(1f)); Switch(prefs.httpsOnly, { prefs.httpsOnly = it; prefs.save() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Block third-party cookies", Modifier.weight(1f)); Switch(prefs.blockThirdPartyCookies, { prefs.blockThirdPartyCookies = it; prefs.save() }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Clear data when app closes", Modifier.weight(1f)); Switch(prefs.autoClear, { prefs.autoClear = it; prefs.save() }) }
                Text("Secure DNS is not implemented in Shadow. Use Android's Private DNS (Settings → Network) or the VPN's resolver.", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ prefs.clearHistory() }) { Text("History") }
                    OutlinedButton({ CookieManager.getInstance().removeAllCookies(null) }) { Text("Cookies (normal tabs)") }
                    OutlinedButton({ app.tabs.tabs.filter { !it.isPrivate }.forEach { it.webView?.clearCache(true) } }) { Text("Cache") }
                    OutlinedButton({ WebStorage.getInstance().deleteAllData() }) { Text("Site data") }
                }
            }
        }
    }
}
