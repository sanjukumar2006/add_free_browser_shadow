package com.nova.browser.tor

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.nova.browser.core.NetworkMode
import com.nova.browser.core.TorPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.torproject.jni.TorService

enum class TorStatus { OFF, STARTING, ON, UNSUPPORTED, ERROR }

/**
 * Runs the real Tor daemon (Guardian Project tor-android; Tor is BSD-3-Clause, check tor-android's own license file for the
 * exact terms of the version you ship) and routes WebView traffic through its SOCKS port with androidx.webkit ProxyController.
 *
 * FAIL-CLOSED: while [mode] is TOR the WebViewClient refuses every request unless [status] is ON (proxy override confirmed
 * applied). A Tor failure therefore pauses browsing; it never falls back to a direct connection.
 * ProxyController is process-wide: Tor mode applies to ALL tabs, normal and private.
 * Chromium resolves hostnames through a socks5:// proxy, so ordinary page DNS does not go to the local resolver. WebRTC/STUN,
 * and anything outside WebView (downloads via DownloadManager, the VPN engine's API calls, other apps) are NOT covered. Because of
 * that, downloads are refused while Tor mode is active (see DownloadRepo guard in MainActivity/Tabs wiring).
 * This is Tor *routing*, not Tor Browser: no fingerprinting resistance. Routing/DNS-leak behaviour has NOT been verified on a device.
 * Bootstrap percentage is not exposed by TorService broadcasts; this class reports STARTING without a percentage rather than inventing one.
 */
class TorEngine(private val ctx: Context) {
    private val proxySupported get() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) }.getOrDefault(false)
    private val idle get() = if (proxySupported) TorStatus.OFF else TorStatus.UNSUPPORTED

    private val _status = MutableStateFlow(idle)
    val status: StateFlow<TorStatus> = _status
    /** Human-readable detail for the UI (errors, what is happening). */
    val message = MutableStateFlow<String?>(null)

    @Volatile var mode: NetworkMode = NetworkMode.DIRECT; private set
    val phase: TorPhase get() = when (_status.value) {
        TorStatus.OFF -> TorPhase.OFF; TorStatus.STARTING -> TorPhase.STARTING; TorStatus.ON -> TorPhase.ON
        TorStatus.UNSUPPORTED -> TorPhase.UNSUPPORTED; TorStatus.ERROR -> TorPhase.ERROR
    }

    private var bound = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getStringExtra(TorService.EXTRA_STATUS)) {
                TorService.STATUS_ON -> applyProxy(TorService.socksPort)
                TorService.STATUS_STARTING -> { _status.value = TorStatus.STARTING; message.value = "Bootstrapping Tor… (percentage is not available)" }
                TorService.STATUS_OFF -> if (mode == NetworkMode.TOR && _status.value == TorStatus.ON) {
                    // Daemon died while the user wanted Tor: stay fail-closed.
                    _status.value = TorStatus.ERROR; message.value = "Tor stopped unexpectedly. Browsing is paused."
                }
            }
        }
    }
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, s: IBinder?) {}
        override fun onServiceDisconnected(n: ComponentName?) {}
    }

    fun start() {
        if (_status.value == TorStatus.UNSUPPORTED || _status.value == TorStatus.ON || _status.value == TorStatus.STARTING) return
        mode = NetworkMode.TOR            // from this moment nothing may load directly
        _status.value = TorStatus.STARTING; message.value = "Starting Tor…"
        try {
            if (!receiverRegistered) { ContextCompat.registerReceiver(ctx, receiver, IntentFilter(TorService.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED); receiverRegistered = true }
            bound = ctx.bindService(Intent(ctx, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
            if (!bound) fail("Could not start the Tor service.")
        } catch (e: Exception) { fail("Could not start Tor: ${e.message}") }
    }

    /** Stops Tor and returns to direct browsing only after the proxy override has really been cleared. */
    fun stop() {
        if (receiverRegistered) { runCatching { ctx.unregisterReceiver(receiver) }; receiverRegistered = false }
        if (bound) { runCatching { ctx.unbindService(conn) }; bound = false }
        if (!proxySupported) { mode = NetworkMode.DIRECT; _status.value = TorStatus.UNSUPPORTED; return }
        // Keep mode=TOR (fail closed) until the override is cleared, so no request slips out direct during teardown.
        runCatching {
            ProxyController.getInstance().clearProxyOverride({ it.run() }, {
                mode = NetworkMode.DIRECT; _status.value = TorStatus.OFF; message.value = null
            })
        }.onFailure { fail("Could not clear the Tor proxy: ${it.message}") }
    }

    private fun fail(msg: String) { _status.value = TorStatus.ERROR; message.value = msg }

    private fun applyProxy(port: Int) {
        if (port !in 1..65535) { fail("Tor did not report a valid SOCKS port."); return }
        if (!proxySupported) { fail("WebView cannot route through a proxy."); return }
        val cfg = runCatching { ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:$port").build() }.getOrNull() ?: run { fail("Invalid proxy configuration."); return }
        // Status becomes ON only in the success callback, i.e. after the proxy override is confirmed applied.
        runCatching {
            ProxyController.getInstance().setProxyOverride(cfg, { it.run() }, { _status.value = TorStatus.ON; message.value = "Connected through Tor (routing not leak-tested)." })
        }.onFailure { fail("Could not apply the Tor proxy: ${it.message}") }
    }
}
