package com.nova.browser

import android.app.Application
import com.nova.browser.adblock.AdBlockEngine
import com.nova.browser.data.Prefs
import com.nova.browser.download.DownloadRepo
import com.nova.browser.engine.TabManager
import com.nova.browser.tor.TorEngine
import com.nova.browser.vpn.VpnEngine

/** Simple service locator (swap for Hilt/Koin later). */
class NovaApp : Application() {
    lateinit var prefs: Prefs
    lateinit var adBlock: AdBlockEngine
    lateinit var downloads: DownloadRepo
    lateinit var vpn: VpnEngine
    lateinit var tor: TorEngine
    lateinit var tabs: TabManager

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        adBlock = AdBlockEngine(this, prefs)
        downloads = DownloadRepo(this)
        vpn = VpnEngine(this, prefs)
        tor = TorEngine(this)
        tabs = TabManager(this)
        // Weekly filter-list refresh, off the main thread. Skipped entirely unless a list is actually due.
        Thread({ runCatching { adBlock.refreshSubscriptionsIfDue() } }, "adblock-subs").apply { isDaemon = true; start() }
        // Crash/kill recovery: no WebView exists yet, so any leftover private profile is stale and is deleted before first use.
        tabs.deletePrivateProfile()
        downloads.guard = {
            if (tor.mode == com.nova.browser.core.NetworkMode.TOR)
                "Downloads are disabled while Tor mode is on: Android's download service does not use the Tor proxy and would reveal your IP."
            else null
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) tabs.trimBackground()
    }
}
