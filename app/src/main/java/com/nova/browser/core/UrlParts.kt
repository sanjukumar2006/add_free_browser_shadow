package com.nova.browser.core

/**
 * Lenient-but-safe URL splitter. java.net.URI rejects characters such as `[`, `]`, `|` and `{` that are common in real
 * query strings, which would make filtering and navigation silently fail open or closed. Only the authority is parsed strictly.
 */
data class UrlParts(val scheme: String, val userInfo: String?, val host: String, val port: Int?, val rest: String) {
    companion object {
        private val hostChars = Regex("""^(?:[A-Za-z0-9._~%-]+|\[[0-9A-Fa-f:.]+])$""")

        fun parse(url: String?): UrlParts? {
            if (url == null) return null
            val sep = url.indexOf("://")
            if (sep <= 0) return null
            val scheme = url.substring(0, sep).lowercase()
            if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } || !scheme[0].isLetter()) return null
            val afterScheme = url.substring(sep + 3)
            val end = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) afterScheme.length else it }
            var authority = afterScheme.substring(0, end)
            val rest = afterScheme.substring(end)
            var userInfo: String? = null
            val at = authority.lastIndexOf('@')
            if (at >= 0) { userInfo = authority.substring(0, at); authority = authority.substring(at + 1) }
            var port: Int? = null
            val hostPart: String
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']'); if (close < 0) return null
                hostPart = authority.substring(0, close + 1)
                val tail = authority.substring(close + 1)
                if (tail.isNotEmpty()) { if (!tail.startsWith(":")) return null; port = tail.substring(1).toIntOrNull() ?: return null }
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) { port = authority.substring(colon + 1).toIntOrNull() ?: return null; hostPart = authority.substring(0, colon) } else hostPart = authority
            }
            if (port != null && port !in 1..65535) return null
            if (hostPart.isEmpty() || !hostChars.matches(hostPart)) return null
            return UrlParts(scheme, userInfo, hostPart.lowercase(), port, rest)
        }
    }
}
