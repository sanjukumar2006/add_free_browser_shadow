package com.nova.browser.core

enum class NetworkMode { DIRECT, TOR }
enum class TorPhase { OFF, STARTING, ON, UNSUPPORTED, ERROR }

/**
 * Fail-closed routing: when the user selected Tor, nothing may load until the Tor SOCKS proxy has been
 * confirmed applied. A failed or starting Tor must never fall back to a direct connection.
 */
object RoutingPolicy {
    fun canLoad(mode: NetworkMode, tor: TorPhase): Boolean = when (mode) {
        NetworkMode.DIRECT -> true
        NetworkMode.TOR -> tor == TorPhase.ON
    }

    fun blockedMessage(mode: NetworkMode, tor: TorPhase): String? = if (canLoad(mode, tor)) null else when (tor) {
        TorPhase.STARTING -> "Tor is still connecting. Pages will load once Tor is ready."
        TorPhase.UNSUPPORTED -> "This device's WebView cannot route traffic through a proxy, so Tor mode is unavailable."
        TorPhase.ERROR -> "Tor failed to start. Browsing is paused rather than falling back to a direct connection."
        else -> "Tor is off. Start Tor, or switch back to direct browsing."
    }

    /** Onion hosts can only be reached through Tor. */
    fun isOnion(host: String?) = host != null && host.endsWith(".onion")
}
