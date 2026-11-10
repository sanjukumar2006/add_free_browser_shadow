package com.nova.browser.core

import java.net.InetAddress
import java.util.Base64

/** Values returned by the (user-operated) backend's POST /v1/connect. Untrusted until validated. */
data class ProvisionedTunnel(
    val address: String,          // "10.8.0.7/32" or "10.8.0.7/32,fd00::7/128"
    val dns: String,              // "10.8.0.1" or "10.8.0.1,fd00::1"
    val endpoint: String,         // "203.0.113.10:51820" or "vpn.example.net:51820"
    val serverPublicKey: String,  // base64, 32 bytes
)

object VpnConfigValidator {
    sealed class Result {
        data class Valid(val addresses: List<String>, val dns: List<String>, val endpointHost: String, val endpointPort: Int) : Result()
        data class Invalid(val problems: List<String>) : Result()
    }

    /** The shipped default is a placeholder; it must never be treated as a real server. */
    fun isApiConfigured(base: String): Boolean {
        if (!base.startsWith("https://")) return false
        val host = UrlPolicy.host(base) ?: return false
        return !(host == "example.com" || host.endsWith(".example.com") || host.endsWith(".example") || host == "localhost")
    }

    fun isValidKey(b64: String): Boolean = runCatching {
        val raw = Base64.getDecoder().decode(b64)
        raw.size == 32 && raw.any { it.toInt() != 0 }
    }.getOrDefault(false)

    private val hostName = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$""")
    private val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    private fun isIpLiteral(s: String): Boolean {
        val m = v4.matchEntire(s)
        if (m != null) return m.groupValues.drop(1).all { it.toInt() in 0..255 }
        return s.contains(':') && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } &&
            runCatching { InetAddress.getByName(s) }.isSuccess
    }

    private fun validCidr(c: String): Boolean {
        val ip = c.substringBefore('/'); val len = c.substringAfter('/', "").toIntOrNull() ?: return false
        if (!isIpLiteral(ip)) return false
        return if (ip.contains(':')) len in 1..128 else len in 1..32
    }

    fun validate(t: ProvisionedTunnel): Result {
        val problems = ArrayList<String>()
        val addrs = t.address.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (addrs.isEmpty() || addrs.any { !validCidr(it) }) problems += "invalid tunnel address"
        if (addrs.any { it.startsWith("0.0.0.0") || it.startsWith("127.") }) problems += "tunnel address is not routable"
        val dns = t.dns.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (dns.isEmpty() || dns.any { !isIpLiteral(it) }) problems += "DNS must be one or more IP literals"
        val host = t.endpoint.substringBeforeLast(':', "").removePrefix("[").removeSuffix("]")
        val port = t.endpoint.substringAfterLast(':', "").toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) problems += "invalid endpoint (expected host:port)"
        else if (!isIpLiteral(host) && !hostName.matches(host)) problems += "invalid endpoint host"
        else if (host == "0.0.0.0" || host.startsWith("127.")) problems += "endpoint is not routable"
        if (!isValidKey(t.serverPublicKey)) problems += "invalid server public key"
        return if (problems.isEmpty()) Result.Valid(addrs, dns, host, port!!) else Result.Invalid(problems)
    }
}
