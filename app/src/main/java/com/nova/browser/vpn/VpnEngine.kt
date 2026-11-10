package com.nova.browser.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nova.browser.BuildConfig
import com.nova.browser.core.ProvisionedTunnel
import com.nova.browser.core.VpnConfigValidator
import com.nova.browser.data.Prefs
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

data class VpnServer(val id: String, val country: String, val city: String, val premium: Boolean = false)

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class VpnState(
    val status: VpnStatus = VpnStatus.DISCONNECTED,
    val server: VpnServer? = null,
    val virtualIp: String? = null,
    val error: String? = null,
    val connectedAt: Long = 0L,
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L,
    val rxSpeed: Long = 0L,
    val txSpeed: Long = 0L,
    /** Human-readable diagnostic for the last attempt (handshake status, validation problems). Never contains keys. */
    val diagnostics: String? = null,
)

/**
 * WireGuard tunnel via the userspace GoBackend (runs inside Android's VpnService, declared by the wireguard tunnel AAR manifest).
 *
 * HONESTY NOTES
 *  - Nothing here is "connected" until the WireGuard handshake with the server has actually completed (see [awaitHandshake]).
 *  - There are NO built-in servers. Without a real backend (BuildConfig.VPN_API_BASE) the engine reports "not configured".
 *  - Control plane: your backend (see /backend/README.md) lists servers and, per connection, registers this device's PUBLIC key
 *    and returns {address, dns, endpoint, serverPublicKey}. The device private key is generated on-device and kept in
 *    EncryptedSharedPreferences. No keys or credentials are shipped in the APK.
 *  - Routing: AllowedIPs 0.0.0.0/0 and ::/0 send all IPv4+IPv6 through the tunnel and DNS is the server-provided resolver. This
 *    has NOT been leak-tested on a device (see TEST_REPORT.md).
 *  - Kill switch: an app cannot enforce one by itself. Android offers it only through system "Always-on VPN" + "Block connections
 *    without VPN" (the user must enable it); openSystemVpnSettings() opens that screen. While the tunnel is down mid-session
 *    (before reconnect) traffic is NOT blocked by Shadow.
 */
class VpnEngine(private val ctx: Context, private val prefs: Prefs) {
    private val backend by lazy { GoBackend(ctx) }
    private val _state = MutableStateFlow(VpnState())
    val state: StateFlow<VpnState> = _state

    /** False while VPN_API_BASE is the shipped placeholder or not https. */
    val isConfigured: Boolean get() = VpnConfigValidator.isApiConfigured(BuildConfig.VPN_API_BASE)
    val notConfiguredMessage = "VPN backend is not configured. Set VPN_API_BASE in app/build.gradle.kts to your own https control-plane (see backend/README.md). Shadow ships no VPN servers."

    private val tunnel = object : Tunnel {
        override fun getName() = "nova0"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && _state.value.status == VpnStatus.CONNECTED) {
                _state.value = VpnState(VpnStatus.DISCONNECTED, diagnostics = "Tunnel went down (stopped by the system or another VPN).")
            }
        }
    }

    var lastServer: VpnServer? = null
    var autoReconnect = true
    /** Set true when the user taps connect, false when the user taps disconnect. */
    @Volatile var wantConnected = false
    private var failures = 0
    private var nextAttemptAt = 0L

    // ---------- backend API ----------
    private fun token(): String? = prefs.secure.getString("vpn_token", null)?.takeIf { it.isNotBlank() }
    fun setAccountToken(t: String) = prefs.secure.edit().putString("vpn_token", t).apply()
    fun hasAccount() = token() != null

    /** Removes the account token AND the device key, so the next sign-in registers a fresh key with the backend. */
    fun signOut() { wantConnected = false; prefs.secure.edit().remove("vpn_token").remove("wg_priv").apply() }

    private fun http(path: String, method: String, body: String? = null): String {
        check(isConfigured) { notConfiguredMessage }
        val c = URL(BuildConfig.VPN_API_BASE.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            token()?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.setRequestProperty("Accept", "application/json")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            when (val code = c.responseCode) {
                in 200..299 -> {}
                401, 403 -> error("Account token rejected by the backend ($code)")
                else -> error("Backend responded $code")
            }
            val bytes = c.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
                while (true) { val n = ins.read(buf); if (n < 0) break; total += n; require(total <= 256 * 1024) { "Backend response too large" }; out.write(buf, 0, n) }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    suspend fun fetchServers(): Result<List<VpnServer>> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = org.json.JSONArray(http("/v1/servers", "GET"))
            (0 until minOf(arr.length(), 500)).map {
                val o = arr.getJSONObject(it)
                VpnServer(o.getString("id").take(64), o.getString("country").take(64), o.optString("city").take(64), o.optBoolean("premium"))
            }
        }
    }

    private fun deviceKeys(): KeyPair {
        val priv = prefs.secure.getString("wg_priv", null)
        return if (priv != null) KeyPair(com.wireguard.crypto.Key.fromBase64(priv))
        else KeyPair().also { prefs.secure.edit().putString("wg_priv", it.privateKey.toBase64()).apply() }
    }

    /** Caller must have obtained VpnService permission first (VpnService.prepare in the Activity). */
    suspend fun connect(server: VpnServer) = withContext(Dispatchers.IO) {
        if (!isConfigured) { _state.value = VpnState(VpnStatus.ERROR, server, error = notConfiguredMessage); return@withContext }
        _state.value = VpnState(VpnStatus.CONNECTING, server, diagnostics = "Requesting configuration…")
        try {
            val keys = deviceKeys()
            val resp = JSONObject(http("/v1/connect", "POST",
                JSONObject().put("serverId", server.id).put("publicKey", keys.publicKey.toBase64()).toString()))
            val provisioned = ProvisionedTunnel(resp.getString("address"), resp.getString("dns"), resp.getString("endpoint"), resp.getString("serverPublicKey"))
            val v = VpnConfigValidator.validate(provisioned)
            if (v is VpnConfigValidator.Result.Invalid) {
                _state.value = VpnState(VpnStatus.ERROR, server, error = "Backend returned an invalid configuration", diagnostics = v.problems.joinToString("; "))
                return@withContext
            }
            v as VpnConfigValidator.Result.Valid
            val iface = Interface.Builder().parsePrivateKey(keys.privateKey.toBase64())
            v.addresses.forEach { iface.addAddress(InetNetwork.parse(it)) }
            v.dns.forEach { iface.addDnsServer(InetAddress.getByName(it)) }
            val cfg = Config.Builder()
                .setInterface(iface.build())
                .addPeer(Peer.Builder()
                    .parsePublicKey(provisioned.serverPublicKey)
                    .parseEndpoint("${v.endpointHost}:${v.endpointPort}")
                    .addAllowedIp(InetNetwork.parse("0.0.0.0/0"))
                    .addAllowedIp(InetNetwork.parse("::/0"))
                    .setPersistentKeepalive(25)
                    .build())
                .build()
            _state.value = VpnState(VpnStatus.CONNECTING, server, diagnostics = "Starting tunnel; waiting for WireGuard handshake…")
            backend.setState(tunnel, Tunnel.State.UP, cfg)
            if (!awaitHandshake()) {
                runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
                failures++
                _state.value = VpnState(VpnStatus.ERROR, server, error = "No handshake with the VPN server within 15 s",
                    diagnostics = "Server unreachable, UDP blocked by the network, or the backend did not register this device's key.")
                return@withContext
            }
            failures = 0
            lastServer = server
            _state.value = VpnState(VpnStatus.CONNECTED, server, v.addresses.first().substringBefore('/'), connectedAt = System.currentTimeMillis(), diagnostics = "Handshake completed.")
        } catch (e: Exception) {
            failures++
            runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
            _state.value = VpnState(VpnStatus.ERROR, server, error = e.message ?: "Connection failed")
        }
    }

    /** True once the peer reports a completed handshake. This is the only evidence we accept that the tunnel works. */
    private suspend fun awaitHandshake(timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val st = runCatching { backend.getStatistics(tunnel) }.getOrNull()
            if (st != null && st.peers().any { (st.peer(it)?.latestHandshakeEpochMillis() ?: 0L) > 0L }) return true
            delay(500)
        }
        return false
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        wantConnected = false
        runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }
        _state.value = VpnState()
    }

    /** Poll tunnel counters about once per second while connected. Also drives bounded auto-reconnect with exponential backoff. */
    suspend fun tick() {
        val s = _state.value
        if (s.status == VpnStatus.CONNECTED) {
            val stats = runCatching { backend.getStatistics(tunnel) }.getOrNull() ?: return
            val rx = stats.totalRx(); val tx = stats.totalTx()
            _state.value = s.copy(rxBytes = rx, txBytes = tx, rxSpeed = (rx - s.rxBytes).coerceAtLeast(0), txSpeed = (tx - s.txBytes).coerceAtLeast(0))
        } else if (s.status == VpnStatus.DISCONNECTED && autoReconnect && wantConnected && failures < MAX_RECONNECT_FAILURES && online() &&
            System.currentTimeMillis() >= nextAttemptAt) {
            val srv = lastServer ?: return
            nextAttemptAt = System.currentTimeMillis() + (1_000L shl minOf(failures, 6)) // 1 s, 2 s, 4 s … 64 s
            connect(srv)
        }
    }

    fun online(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True when on Wi-Fi. Android does not expose whether a network is unsecured, so Shadow does not offer "auto-protect on open Wi-Fi". */
    fun onWifi(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun openSystemVpnSettings() {
        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object { const val MAX_RECONNECT_FAILURES = 5 }
}
