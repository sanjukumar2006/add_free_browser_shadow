package com.nova.browser

import android.Manifest
import android.net.VpnService
import android.os.Bundle
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.translate
import androidx.core.view.WindowCompat
import com.nova.browser.ui.*

private enum class Dest { HOME, BROWSER, DOWNLOADS, HISTORY, PRIVACY, SETTINGS }

class MainActivity : ComponentActivity() {
    private var pendingWebPerm: PermissionRequest? = null
    private var onVpnGranted: (() -> Unit)? = null

    private val vpnPerm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) onVpnGranted?.invoke() }
    private val runtimePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        pendingWebPerm?.let { r ->
            // Grant ONLY the resources that were requested and whose Android permission was actually granted.
            val ok = r.resources.filter {
                (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && res[Manifest.permission.CAMERA] == true) ||
                (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && res[Manifest.permission.RECORD_AUDIO] == true)
            }.toTypedArray()
            if (ok.isNotEmpty()) r.grant(ok) else r.deny()
        }; pendingWebPerm = null
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val storagePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = application as NovaApp
        if (app.tabs.tabs.isEmpty()) app.tabs.restoreSession()
        // Safe-by-default: an incoming VIEW intent is only used if it is a valid http(s) URL (never file:, content:, javascript:, intent:).
        val incoming = intent?.data?.toString()?.takeIf { com.nova.browser.core.UrlPolicy.isAllowedNavigation(it) }

        setContent {
            NovaTheme(app.prefs.theme) {
                var dest by remember { mutableStateOf(if (incoming != null) Dest.BROWSER else Dest.HOME) }
                var showTabs by remember { mutableStateOf(false) }
                var showMenu by remember { mutableStateOf(false) }
                val tm = app.tabs
                if (incoming != null && savedInstanceState == null) LaunchedEffect(Unit) { tm.open(incoming) }

                // Show one-shot engine notices (private tabs unsupported, blocked URL, download refused).
                val ctx = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(tm.notice) { tm.notice?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); tm.notice = null } }
                // Contextual notification permission: asked when the user first opens Downloads, not at launch.
                LaunchedEffect(dest) {
                    if (dest == Dest.DOWNLOADS) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else if (android.os.Build.VERSION.SDK_INT <= 28 && androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            storagePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
                // Hide private tab contents from the recents screen / screenshots.
                val priv = tm.current?.isPrivate == true
                LaunchedEffect(priv) { if (priv) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }

                fun navigate(url: String, private: Boolean = false) {
                    if (!com.nova.browser.core.UrlPolicy.isAllowedNavigation(url)) { tm.notice = "Blocked an unsafe or invalid address."; return }
                    val cur = tm.current
                    val tab = if (cur == null || (private && !cur.isPrivate)) tm.open(url, private) else cur
                    if (tab == null) return
                    val blocked = com.nova.browser.core.RoutingPolicy.blockedMessage(app.tor.mode, app.tor.phase)
                    tab.url = url
                    if (blocked != null) tm.notice = blocked else tab.webView?.loadUrl(url)
                    dest = Dest.BROWSER
                }

                BackHandler(dest == Dest.BROWSER && tm.current?.canGoBack == true) { tm.current?.webView?.goBack() }
                BackHandler(dest != Dest.HOME && dest != Dest.BROWSER) { dest = if (tm.current?.isHome == false) Dest.BROWSER else Dest.HOME }

                val cur0 = tm.current
                val onHomePage = (dest == Dest.HOME || dest == Dest.BROWSER) && (cur0 == null || cur0.isHome)
                val wallpaper = androidx.compose.ui.res.painterResource(R.drawable.home_bg)
                // Cross-fade between plain background and wallpaper so switching pages never flashes.
                val wallAlpha by androidx.compose.animation.core.animateFloatAsState(if (onHomePage) 1f else 0f, androidx.compose.animation.core.tween(220), label = "wall")
                Scaffold(
                    modifier = Modifier.fillMaxSize().drawBehind {
                        drawRect(androidx.compose.ui.graphics.Color(0xFF0B0B14))
                        if (wallAlpha > 0f) {
                            val sz = wallpaper.intrinsicSize
                            val s = maxOf(size.width / sz.width, size.height / sz.height) // centre-crop
                            val w = sz.width * s; val h = sz.height * s
                            translate((size.width - w) / 2f, (size.height - h) / 2f) {
                                with(wallpaper) { draw(androidx.compose.ui.geometry.Size(w, h), alpha = wallAlpha) }
                            }
                            drawRect(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.38f * wallAlpha)) // scrim for legibility
                        }
                    },
                    containerColor = if (onHomePage) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.background,
                    bottomBar = {
                        BraveBottomBar(app, tm.current,
                            onHome = { if (dest == Dest.HOME || dest == Dest.BROWSER) { /* already on the new-tab page */ } else dest = if (tm.current?.isHome == false) Dest.BROWSER else Dest.HOME },
                            onNewTab = { tm.open("", tm.current?.isPrivate == true); dest = Dest.HOME },
                            onTabs = { showTabs = true },
                            onMenu = { showMenu = true })
                    }
                ) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        val cur = tm.current
                        when {
                            dest == Dest.DOWNLOADS -> DownloadsScreen(app)
                            dest == Dest.HISTORY -> HistoryScreen(app) { navigate(it) }
                            dest == Dest.PRIVACY -> PrivacyVpnScreen(app) { granted ->
                                val i = VpnService.prepare(this@MainActivity)
                                if (i == null) granted() else { onVpnGranted = granted; vpnPerm.launch(i) }
                            }
                            dest == Dest.SETTINGS -> SettingsScreen(app)
                            cur != null && !cur.isHome -> BrowserScreen(app, cur, { navigate(it, cur.isPrivate) }, { showTabs = true },
                                { u -> tm.open(u, cur.isPrivate); Unit },
                                { r -> requestWebPermission(r) })
                            else -> HomeScreen(app, { navigate(it) }, { if (tm.open("", true) != null) dest = Dest.HOME }, { dest = Dest.DOWNLOADS }, { dest = Dest.SETTINGS }, { dest = Dest.PRIVACY })
                        }
                    }
                }
                if (showMenu) BraveMenuSheet(app, tm.current, { showMenu = false },
                    onNewTab = { tm.open("", tm.current?.isPrivate == true); dest = Dest.HOME },
                    onNewPrivateTab = { if (tm.open("", true) != null) dest = Dest.HOME },
                    onDownloads = { dest = Dest.DOWNLOADS }, onHistory = { dest = Dest.HISTORY }, onPrivacy = { dest = Dest.PRIVACY }, onSettings = { dest = Dest.SETTINGS },
                    onHome = { dest = Dest.HOME })
                if (showTabs) TabsSheet(app, { showTabs = false }) { id -> tm.select(id); showTabs = false; dest = if (tm.current?.isHome == false) Dest.BROWSER else Dest.HOME }
            }
        }
    }

    private fun requestWebPermission(r: PermissionRequest) {
        val needed = r.resources.mapNotNull {
            when (it) { PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA; PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO; else -> null }
        }.toTypedArray()
        if (needed.isEmpty()) { r.deny(); return }
        pendingWebPerm = r
        runtimePerms.launch(needed)
    }

    override fun onStop() {
        super.onStop()
        val app = application as NovaApp
        app.prefs.saveStats(); app.tabs.persist()
        if (isFinishing && app.prefs.autoClear) {
            // Clears the NORMAL (default-profile) data only; the private profile is handled by wipePrivateSession().
            app.prefs.clearHistory(); android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }

    override fun onDestroy() {
        val app = application as NovaApp
        if (isFinishing) { app.tabs.closeAll(private = true); app.tabs.wipePrivateSession() }
        super.onDestroy()
    }
}
