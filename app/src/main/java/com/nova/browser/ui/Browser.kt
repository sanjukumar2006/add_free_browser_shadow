package com.nova.browser.ui

import android.webkit.WebView
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.nova.browser.NovaApp
import com.nova.browser.data.Site
import com.nova.browser.download.DownloadRepo
import com.nova.browser.engine.Tab
import com.nova.browser.engine.WebViewFactory
import com.nova.browser.video.VideoCandidate
import com.nova.browser.video.VideoDetector

@Composable
fun BrowserScreen(app: NovaApp, tab: Tab, onNavigate: (String) -> Unit, onTabs: () -> Unit, onNewTab: (String) -> Unit, onPermission: (android.webkit.PermissionRequest) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var showVideo by remember { mutableStateOf(false) }
    val bookmarked = app.prefs.bookmarks.any { it.url == tab.url }

    Column(Modifier.fillMaxSize()) {
        SearchBar(app, tab.isPrivate, onNavigate, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), initial = tab.url)
        if (tab.progress in 1..99) LinearProgressIndicator(progress = { tab.progress / 100f }, Modifier.fillMaxWidth().height(2.dp), color = BraveOrange, trackColor = androidx.compose.ui.graphics.Color.Transparent)
        Box(Modifier.weight(1f)) {
            key(tab.id) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                    (tab.webView?.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                        ?: WebViewFactory.create(c, app, tab, onNewTab, onPermission))
                        .also { (it.context as? android.content.MutableContextWrapper)?.baseContext = c }
                }, update = { w -> w.onResume() },
                // Detach (do NOT destroy: the tab may be re-selected) and stop pinning the Activity context. TabManager.close() destroys.
                onRelease = { w -> w.onPause(); (w.parent as? android.view.ViewGroup)?.removeView(w)
                    (w.context as? android.content.MutableContextWrapper)?.baseContext = w.context.applicationContext })
            }
            // Floating video-detected button
            if (tab.videos.isNotEmpty()) {
                ExtendedFloatingActionButton(onClick = { showVideo = true }, containerColor = BraveOrange, contentColor = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    icon = { Icon(Icons.Default.Download, null) }, text = { Text("Video detected (${tab.videos.size})") })
            }
            tab.restrictedNotice?.let { n ->
                Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp))
                        Text(n, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton({ tab.restrictedNotice = null }) { Icon(Icons.Default.Close, "Dismiss") }
                    }
                }
            }
        }
    }

    if (showVideo) VideoSheet(app, tab) { showVideo = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSheet(app: NovaApp, tab: Tab, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf<VideoCandidate?>(tab.videos.firstOrNull()) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("VIDEO DETECTED", style = MaterialTheme.typography.titleLarge)
            Text("Only direct media files the site serves are listed. Protected (DRM) or segmented streams are not downloaded.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(tab.videos) { v ->
                    Row(Modifier.fillMaxWidth().clickable { selected = v; name = "" }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected?.url == v.url, { selected = v; name = "" })
                        Column {
                            Text(buildString { append(if (v.isAudio) "Audio" else "Video"); append(" · ").append(v.ext.uppercase()); if (v.height > 0) append(" · ${v.height}p") },
                                style = MaterialTheme.typography.titleMedium)
                            Text(v.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text("Quality and format are fixed by the file the site provides; the list shows each available source.", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(name, { name = it }, label = { Text("File name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                selected?.let { v ->
                    val fn = (name.ifBlank { DownloadRepo.guessName(v.url, null, v.mime).substringBeforeLast('.').ifBlank { "video" } }) + "." + v.ext
                    val id = app.downloads.enqueue(v.url, fn, v.mime, tab.webView?.settings?.userAgentString, tab.url, tab.isPrivate)
                    if (id < 0) tab.restrictedNotice = app.downloads.lastRefusal ?: "The download could not be started."
                }; onDismiss()
            }, enabled = selected != null, modifier = Modifier.fillMaxWidth()) { Text("Download now") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
