package com.nova.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.browser.NovaApp
import com.nova.browser.data.Site
import com.nova.browser.engine.Tab

/** Brave-style bottom toolbar: back, forward, new tab, tab-count box, menu. */
@Composable
fun BraveBottomBar(app: NovaApp, tab: Tab?, onHome: () -> Unit, onNewTab: () -> Unit, onTabs: () -> Unit, onMenu: () -> Unit) {
    val onPage = tab != null && !tab.isHome
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            Row(Modifier.fillMaxWidth().height(52.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (onPage) tab?.webView?.goBack() else onHome() }) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = if (onPage && tab?.canGoBack != true) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface) }
                IconButton({ tab?.webView?.goForward() }, enabled = onPage && tab?.canGoForward == true) { Icon(Icons.Default.ArrowForward, "Forward") }
                IconButton(onNewTab) { Icon(Icons.Default.Add, "New tab") }
                // Tab-count box
                val count = app.tabs.tabs.count { it.isPrivate == (tab?.isPrivate == true) }.coerceAtLeast(1)
                IconButton(onTabs) {
                    Surface(shape = RoundedCornerShape(6.dp), color = androidx.compose.ui.graphics.Color.Transparent, border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(24.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(if (count > 99) ":D" else count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                IconButton(onMenu) { Icon(Icons.Default.MoreVert, "Menu") }
            }
        }
    }
}

@Composable
private fun MenuTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(18.dp)); Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Brave-style main menu sheet. Navigation (downloads/privacy/settings) lives here, as in Brave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BraveMenuSheet(
    app: NovaApp, tab: Tab?, onDismiss: () -> Unit,
    onNewTab: () -> Unit, onNewPrivateTab: () -> Unit, onDownloads: () -> Unit, onHistory: () -> Unit, onPrivacy: () -> Unit, onSettings: () -> Unit, onHome: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val onPage = tab != null && !tab.isHome
    val bookmarked = tab != null && app.prefs.bookmarks.any { it.url == tab.url }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                MenuTile(Icons.Default.Add, "New tab", Modifier.weight(1f)) { onNewTab(); onDismiss() }
                MenuTile(Icons.Default.VisibilityOff, "New private tab", Modifier.weight(1f)) { onNewPrivateTab(); onDismiss() }
                MenuTile(Icons.Default.Download, "Downloads", Modifier.weight(1f)) { onDownloads(); onDismiss() }
                MenuTile(Icons.Default.Shield, "Shields & VPN", Modifier.weight(1f), tint = BraveOrange) { onPrivacy(); onDismiss() }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
            if (onPage && tab != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    MenuTile(if (bookmarked) Icons.Default.Star else Icons.Default.StarBorder, "Bookmark", Modifier.weight(1f), tint = if (bookmarked) BraveOrange else MaterialTheme.colorScheme.onSurface) {
                        app.prefs.toggleBookmark(Site(tab.title, tab.url)); onDismiss() }
                    MenuTile(Icons.Default.Refresh, "Reload", Modifier.weight(1f)) { tab.webView?.reload(); onDismiss() }
                    MenuTile(Icons.Default.Share, "Share", Modifier.weight(1f)) {
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, tab.url), "Share")); onDismiss() }
                    MenuTile(Icons.Default.Home, "Home", Modifier.weight(1f)) { tab.url = ""; onHome(); onDismiss() }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outline, thickness = 0.5.dp)
                MenuRow(Icons.Default.DesktopWindows, if (app.prefs.desktopMode) "Mobile site" else "Desktop site") {
                    app.prefs.desktopMode = !app.prefs.desktopMode; app.prefs.save()
                    tab.webView?.settings?.userAgentString = if (app.prefs.desktopMode) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" else null
                    tab.webView?.reload(); onDismiss() }
                MenuRow(Icons.Default.PushPin, if (tab.pinned) "Unpin tab" else "Pin tab") { tab.pinned = !tab.pinned; onDismiss() }
                MenuRow(Icons.Default.Shield, "Turn Shields off for this site") {
                    val h = com.nova.browser.core.UrlPolicy.host(tab.url)
                    if (h != null) app.prefs.setWhitelist(app.prefs.whitelist + h); onDismiss() }
                if (tab.isPrivate) Text("Private tab: separate cookies/cache; not anonymous", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            MenuRow(Icons.Default.History, "History") { onHistory(); onDismiss() }
            MenuRow(Icons.Default.Settings, "Settings") { onSettings(); onDismiss() }
        }
    }
}
