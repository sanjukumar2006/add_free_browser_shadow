package com.nova.browser.engine

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.TextUtils
import android.webkit.*
import androidx.compose.runtime.*
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.nova.browser.NovaApp
import com.nova.browser.core.BridgeValidator
import com.nova.browser.core.FilterRequest
import com.nova.browser.core.NetworkMode
import com.nova.browser.core.ResourceTypeGuesser
import com.nova.browser.core.RoutingPolicy
import com.nova.browser.core.SessionCodec
import com.nova.browser.core.TabMeta
import com.nova.browser.core.TorPhase
import com.nova.browser.core.UrlPolicy
import com.nova.browser.data.Site
import com.nova.browser.video.VideoCandidate
import com.nova.browser.video.VideoDetector
import java.io.ByteArrayInputStream
import java.util.UUID

class Tab(val isPrivate: Boolean, startUrl: String = "") {
    val id: String = UUID.randomUUID().toString()
    var url by mutableStateOf(startUrl)
    var title by mutableStateOf("New tab")
    var progress by mutableStateOf(0)
    var pinned by mutableStateOf(false)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var preview by mutableStateOf<Bitmap?>(null)
    var group by mutableStateOf<String?>(null)
    var restrictedNotice by mutableStateOf<String?>(null)
    val videos = mutableStateListOf<VideoCandidate>()
    var webView: WebView? = null
    val isHome get() = url.isBlank()
}

/**
 * Owns all tabs.
 *
 * PRIVATE BROWSING ARCHITECTURE
 * Private tabs run in a dedicated androidx.webkit *Profile* ([PRIVATE_PROFILE]) that has its own cookie jar, web storage and HTTP
 * cache, separate from the default profile used by normal tabs. Nothing is ever cleared in the global CookieManager to create or
 * end a private session. When the last private tab closes (and on every cold start, to recover from a crash) the whole profile is
 * deleted, which removes its cookies, cache and storage.
 * Requires WebViewFeature.MULTI_PROFILE (WebView 128+). If it is NOT supported, private tabs are REFUSED rather than silently
 * sharing the normal cookie jar. Remaining limitations (so this is *not* labelled "fully isolated"): all private tabs share one
 * private profile; the browser process, DNS cache, proxy override (Tor) and VPN are process/device-wide; the OS and the system
 * DownloadManager are shared; it provides no network anonymity.
 */
class TabManager(private val app: NovaApp) {
    val tabs = mutableStateListOf<Tab>()
    var currentId by mutableStateOf<String?>(null)
    val recentlyClosed = mutableStateListOf<Site>()
    /** One-shot user-facing message (e.g. private tabs unsupported). The UI shows and clears it. */
    var notice by mutableStateOf<String?>(null)
    val current: Tab? get() = tabs.firstOrNull { it.id == currentId }

    val privateSupported: Boolean
        get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /** Opens a tab. Returns null (and sets [notice]) if the URL is unsafe or private tabs cannot be isolated on this device. */
    fun open(url: String = "", private: Boolean = false, select: Boolean = true): Tab? {
        if (private && !privateSupported) {
            notice = "Private tabs need a newer Android System WebView (profile support). Update WebView to use private browsing; Shadow will not mix private tabs into your normal cookies."
            return null
        }
        if (url.isNotBlank() && !UrlPolicy.isAllowedNavigation(url)) { notice = "Blocked an unsafe or invalid address."; return null }
        val t = Tab(private, url)
        tabs.add(t)
        if (select) currentId = t.id
        return t
    }

    fun select(id: String) { currentId = id }

    fun close(id: String) {
        val t = tabs.firstOrNull { it.id == id } ?: return
        if (!t.isPrivate && t.url.isNotBlank()) { recentlyClosed.add(0, Site(t.title, t.url)); if (recentlyClosed.size > 20) recentlyClosed.removeAt(recentlyClosed.size - 1) }
        val idx = tabs.indexOf(t)
        releaseWebView(t)
        tabs.remove(t)
        if (currentId == id) currentId = tabs.getOrNull((idx - 1).coerceAtLeast(0))?.id
        if (t.isPrivate && tabs.none { it.isPrivate }) wipePrivateSession()
        persist()
    }

    fun closeAll(private: Boolean? = null) {
        tabs.filter { private == null || it.isPrivate == private }.filter { !it.pinned || it.isPrivate }.map { it.id }.forEach { close(it) }
    }

    fun restoreClosed(site: Site) { recentlyClosed.remove(site); open(site.url) }

    /** Restores tab METADATA only (URL, title, pinned). Page state (scroll, forms, back stack) is not restored; WebViews are created lazily when a tab is shown. */
    fun restoreSession() {
        SessionCodec.decode(app.prefs.loadSessionRaw()).forEach { m -> open(m.url, select = false)?.also { it.title = m.title; it.pinned = m.pinned } }
        currentId = tabs.firstOrNull()?.id
    }

    fun persist() = app.prefs.saveSessionRaw(SessionCodec.encode(tabs.map { TabMeta(it.url, it.title, it.pinned, it.isPrivate) }))

    private fun releaseWebView(t: Tab) {
        val w = t.webView ?: return
        t.webView = null
        runCatching {
            (w.parent as? android.view.ViewGroup)?.removeView(w)
            w.stopLoading(); w.webViewClient = WebViewClient(); w.webChromeClient = WebChromeClient()
            w.removeJavascriptInterface("NovaVideo")
            w.loadUrl("about:blank"); w.clearHistory()
            w.destroy()
        }
    }

    /**
     * Ends the private session: destroys every private WebView, then deletes the private profile (cookies, cache, storage).
     * The default profile / global CookieManager is never touched.
     */
    fun wipePrivateSession() {
        tabs.filter { it.isPrivate }.forEach { releaseWebView(it) }
        deletePrivateProfile()
    }

    /** Deletes the private profile if present. Safe to call on cold start (no WebView can be using it yet). */
    fun deletePrivateProfile() {
        if (!privateSupported) return
        runCatching {
            val store = ProfileStore.getInstance()
            if (PRIVATE_PROFILE in store.allProfileNames) store.deleteProfile(PRIVATE_PROFILE)
        }.onFailure { /* profile still in use by a live WebView; it will be deleted on next cold start */ }
    }

    /** Frees WebViews of background tabs (metadata is kept; the page reloads when the tab is selected again). */
    fun trimBackground() {
        tabs.filter { it.id != currentId && it.webView != null && it.progress >= 100 }.forEach { t ->
            t.url = t.webView?.url?.takeIf { UrlPolicy.isAllowedNavigation(it) } ?: t.url
            releaseWebView(t)
        }
    }

    companion object { const val PRIVATE_PROFILE = "nova_private" }
}

/** JavaScript bridge. EVERY argument is attacker-controlled page input and is validated by [BridgeValidator]. */
class VideoBridge(private val tab: Tab, private val pageUrl: () -> String?) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface fun found(url: String?, height: Int, kind: String?) {
        val page = pageUrl()
        if (!BridgeValidator.isTrustedPage(page)) return
        if (VideoDetector.isRestrictedPlatform(UrlPolicy.host(page))) return
        main.post {
            val m = BridgeValidator.validateFound(url, height, kind, tab.videos.size) ?: return@post
            val c = VideoDetector.fromRequest(m.url) ?: VideoCandidate(m.url, if (m.isAudio) "audio/mpeg" else "video/mp4",
                m.url.substringBefore('?').substringAfterLast('/').take(40).ifBlank { if (m.isAudio) "audio" else "video" })
            val withHeight = if (m.height > 0 && c.height == 0) c.copy(height = m.height, label = "${m.height}p · ${c.label}") else c
            if (tab.videos.none { v -> v.url == withHeight.url }) tab.videos.add(withHeight)
        }
    }

    @JavascriptInterface fun blob() {
        if (!BridgeValidator.isTrustedPage(pageUrl())) return
        main.post { tab.restrictedNotice = "This page streams video in a protected or segmented format. Shadow only downloads direct files that a site permits; it does not bypass DRM or access controls." }
    }
}

object WebViewFactory {
    private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val main = Handler(Looper.getMainLooper())

    private fun blockedResponse(status: Int = 403, reason: String = "Blocked") =
        WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun routingBlocked(app: NovaApp): String? =
        RoutingPolicy.blockedMessage(app.tor.mode, app.tor.phase)

    /**
     * Creates the WebView for [tab]. JavaScript is enabled deliberately (a browser must run page scripts); everything else is
     * locked down: no file/content access, no universal access from file URLs, no mixed content, Safe Browsing on,
     * geolocation off, and the JS bridge is validated and only active on https pages.
     * The WebView is built on a MutableContextWrapper(applicationContext) so it never pins a destroyed Activity; the UI swaps in
     * the Activity as base context while the view is attached.
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun create(ctx: Context, app: NovaApp, tab: Tab, onNewTab: (String) -> Unit, onPermission: (PermissionRequest) -> Unit): WebView {
        val prefs = app.prefs
        val wv = WebView(MutableContextWrapper(ctx.applicationContext).also { it.baseContext = ctx })
        // Must be the FIRST call on the WebView: binds it to the isolated private profile.
        if (tab.isPrivate) {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { "Private tab without profile support" }
            ProfileStore.getInstance().getOrCreateProfile(TabManager.PRIVATE_PROFILE)
            WebViewCompat.setProfile(wv, TabManager.PRIVATE_PROFILE)
        }
        tab.webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT // private profile has its own cache, deleted with the profile
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            textZoom = prefs.fontScale
            setGeolocationEnabled(false) // no geolocation UI exists yet; do not advertise it to pages
            allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION") run { allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false }
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            savePassword = false
            @Suppress("DEPRECATION") saveFormData = false
            if (prefs.desktopMode) userAgentString = DESKTOP_UA
        }
        // CookieManager of the tab's own profile (private -> private profile; normal -> default profile).
        val cookies = if (tab.isPrivate) ProfileStore.getInstance().getOrCreateProfile(TabManager.PRIVATE_PROFILE).cookieManager else CookieManager.getInstance()
        cookies?.apply { setAcceptCookie(true); setAcceptThirdPartyCookies(wv, !prefs.blockThirdPartyCookies && !tab.isPrivate) }
        // pageUrl must NOT call wv.url: bridge methods run on the "JavaBridge" thread and WebView methods are main-thread only.
        wv.addJavascriptInterface(VideoBridge(tab) { tab.url }, "NovaVideo")

        wv.setDownloadListener { url, ua, disp, mime, _ ->
            val id = app.downloads.enqueue(url, com.nova.browser.download.DownloadRepo.guessName(url, disp, mime), mime, ua, wv.url, tab.isPrivate)
            if (id < 0) main.post { tab.restrictedNotice = app.downloads.lastRefusal ?: "The download could not be started." }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url; val scheme = u.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") {
                    if (!UrlPolicy.isAllowedNavigation(u.toString())) return true
                    // Top-level navigations and redirect hops were never filtered before. Block ad/tracker/malware hosts here.
                    val nav = app.adBlock.decideNavigation(u.toString(), UrlPolicy.host(tab.url))
                    if (nav.blocked) {
                        app.adBlock.onBlocked(nav)
                        main.post {tab.restrictedNotice ="Blocked a redirect to ${UrlPolicy.host(u.toString()) ?: "an ad or tracking site"}."}
                        return true
                    }
                    routingBlocked(app)?.let { msg -> main.post { tab.restrictedNotice = msg }; return true }
                    if (scheme == "http" && prefs.httpsOnly && r.isForMainFrame) { v.loadUrl(UrlPolicy.upgradeToHttps(u.toString())); return true }
                    return false
                }
                if (scheme == "about" && u.toString() == UrlPolicy.ABOUT_BLANK) return false
                // Only tel:/mailto:/sms: are handed to the OS, and only for user gestures. intent:, file:, content:, javascript:, data: are dropped.
                if (r.hasGesture() && scheme in setOf("tel", "mailto", "sms"))
                    runCatching { v.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }

            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                // FAIL CLOSED for Tor: never let a request through while Tor is requested but not confirmed ready.
                if (routingBlocked(app) != null) return blockedResponse(503, "Tor not ready")
                val url = r.url.toString()
                // shouldInterceptRequest runs on a background thread: WebView.getUrl() throws there ("WebView methods must be called on the
                // same thread"), which crashed the app on every page load. Use the tab's own (thread-safe state) URL instead.
                val pageHost = UrlPolicy.host(tab.url)
                val type = ResourceTypeGuesser.guess(url, r.isForMainFrame, r.requestHeaders["Accept"])
                val d = app.adBlock.decide(FilterRequest(url, pageHost, type), r.isForMainFrame)
                if (d.blocked) { app.adBlock.onBlocked(d); return blockedResponse() }
                if (!r.isForMainFrame) VideoDetector.fromRequest(url)?.let { c ->
                    main.post { if (tab.videos.size < BridgeValidator.MAX_CANDIDATES_PER_PAGE && tab.videos.none { it.url == c.url }) tab.videos.add(c) }
                }
                return null
            }

            override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                tab.url = url; tab.videos.clear(); tab.restrictedNotice = null
                val host = UrlPolicy.host(url)
                app.adBlock.pageScript(host)?.let { v.evaluateJavascript(it, null) } // hide ad boxes early; re-applied on finish
                if (VideoDetector.isRestrictedPlatform(host)) tab.restrictedNotice =
                    "$host does not permit third-party downloading. Shadow won't download from this site; use the site's own offline/download feature if it offers one."
            }

            override fun onPageFinished(v: WebView, url: String) {
                tab.url = url; tab.title = v.title ?: url
                tab.canGoBack = v.canGoBack(); tab.canGoForward = v.canGoForward()
                app.adBlock.pageScript(UrlPolicy.host(url))?.let { v.evaluateJavascript(it, null) }
                if (BridgeValidator.isTrustedPage(url)) v.evaluateJavascript(VideoDetector.SCAN_JS, null)
                if (SessionCodec.shouldRecordHistory(tab.isPrivate, url)) app.prefs.addHistory(Site(tab.title, url))
                if (!tab.isPrivate) app.tabs.persist()
            }

            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) showError(v, "Can't reach this page", e.description?.toString() ?: "Unknown error")
            }

            // TLS errors are NEVER bypassed. Cancel explicitly and tell the user.
            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                showError(v, "Connection is not secure", "The site's certificate could not be verified (code ${error.primaryError}). Shadow will not continue.")
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { tab.progress = p; tab.canGoBack = v.canGoBack(); tab.canGoForward = v.canGoForward() }
            override fun onReceivedTitle(v: WebView, t: String?) { if (!t.isNullOrBlank()) tab.title = t }
            override fun onPermissionRequest(r: PermissionRequest) {
                // Only grant to https origins; the Activity maps to runtime permissions and grants only what was asked for.
                if (r.origin.scheme != "https") r.deny() else onPermission(r)
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) { cb.invoke(origin, false, false) }
            override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false // popup blocker
                val tmp = WebView(MutableContextWrapper(v.context.applicationContext))
                if (tab.isPrivate) WebViewCompat.setProfile(tmp, TabManager.PRIVATE_PROFILE) // popups of private tabs stay in the private profile
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(w: WebView, r: WebResourceRequest): Boolean {
                        val target = r.url.toString()
                        // A user-gesture pop-up still opens a tab, so its target is checked like any navigation.
                        val nav = app.adBlock.decideNavigation(target, UrlPolicy.host(tab.url))
                        main.post {
                            when {
                                nav.blocked -> {app.adBlock.onBlocked(nav) 
                                tab.restrictedNotice = "Blocked a pop-up from ${UrlPolicy.host(target) ?: "an ad or tracking site"}."}
                                UrlPolicy.isAllowedNavigation(target) -> onNewTab(target)
                            }
                            w.destroy()
                        }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = tmp
                resultMsg.sendToTarget()
                return true
            }
        }

        if (tab.url.isNotBlank() && UrlPolicy.isAllowedNavigation(tab.url) && routingBlocked(app) == null) wv.loadUrl(tab.url)
        else if (tab.url.isNotBlank()) routingBlocked(app)?.let { tab.restrictedNotice = it }
        return wv
    }

    private fun showError(v: WebView, title: String, detail: String) {
        // Everything interpolated into the error page is HTML-escaped (error text can contain page-controlled data).
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
            "<body style='font-family:sans-serif;padding:32px;background:#111;color:#eee'><h2>${TextUtils.htmlEncode(title)}</h2>" +
            "<p>${TextUtils.htmlEncode(detail)}</p></body></html>"
        v.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
