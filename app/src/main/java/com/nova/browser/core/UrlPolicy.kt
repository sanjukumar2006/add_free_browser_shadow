package com.nova.browser.core

import java.net.URLEncoder

/** Result of turning address-bar input into something loadable. Pure JVM so it can be unit tested. */
sealed class Resolved {
    data class Navigate(val url: String) : Resolved()
    data class Search(val url: String) : Resolved()
    data class Rejected(val reason: String) : Resolved()
}

/**
 * Central URL safety policy. Only http(s) and about:blank may be loaded by the browser engine.
 * file:, content:, javascript:, data:, blob:, intent:, ftp: etc. are never navigated to from the address bar,
 * restored from a saved session, or opened from an incoming VIEW intent.
 */
object UrlPolicy {
    const val ABOUT_BLANK = "about:blank"
    private const val MAX_URL_LENGTH = 8192

    private val blockedTypedSchemes = setOf("javascript", "data", "file", "content", "intent", "blob", "vbscript", "ftp", "view-source", "chrome", "android-app")
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?(/.*)?$""")
    private val hostLike = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+(:\d{1,5})?([/?#].*)?$""")
    private val localhostLike = Regex("""^localhost(:\d{1,5})?([/?#].*)?$""", RegexOption.IGNORE_CASE)
    private val schemeLike = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")

    /** True when [url] may be loaded in a tab (http/https with a host and no embedded credentials, or about:blank). */
    fun isAllowedNavigation(url: String?): Boolean {
        if (url == null || url.length > MAX_URL_LENGTH) return false
        if (url == ABOUT_BLANK) return true
        if (url.any { it.code < 0x20 || it.code == 0x7f }) return false
        if (url.any { it == ' ' }) return false
        val u = UrlParts.parse(url) ?: return false
        if (u.scheme != "http" && u.scheme != "https") return false
        if (u.userInfo != null) return false // https://trusted.com@evil.com spoofing
        return true
    }

    fun host(url: String?): String? = UrlParts.parse(url)?.host

    fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /** Characters that make a typed string a search query rather than an address. */
    fun resolve(input: String, searchTemplate: String): Resolved {
        val t = input.trim()
        if (t.isEmpty()) return Resolved.Rejected("empty")
        if (t.any { it.code < 0x20 || it.code == 0x7f }) return Resolved.Rejected("control characters")
        if (t.length > MAX_URL_LENGTH) return Resolved.Rejected("too long")

        val scheme = schemeLike.find(t)?.groupValues?.get(1)?.lowercase()
        val hasAuthority = t.contains("://")
        if (scheme != null && (hasAuthority || scheme in blockedTypedSchemes || scheme == "about")) {
            if (t.equals(ABOUT_BLANK, ignoreCase = true)) return Resolved.Navigate(ABOUT_BLANK)
            if (scheme != "http" && scheme != "https") return Resolved.Rejected("unsupported scheme: $scheme")
            return if (isAllowedNavigation(t)) Resolved.Navigate(t) else Resolved.Rejected("invalid address")
        }
        val noSpaces = !t.contains(' ')
        if (noSpaces && (hostLike.matches(t) || ipv4.matches(t) || localhostLike.matches(t))) {
            val candidate = "https://$t"
            return if (isAllowedNavigation(candidate)) Resolved.Navigate(candidate) else Resolved.Rejected("invalid address")
        }
        if (!searchTemplate.contains("%s")) return Resolved.Rejected("search template has no %s")
        val url = searchTemplate.replace("%s", URLEncoder.encode(t, "UTF-8"))
        return if (isAllowedNavigation(url)) Resolved.Search(url) else Resolved.Rejected("search template is not an http(s) URL")
    }

    /** Validates a user-supplied search template: https only, exactly one %s, host present. */
    fun isValidSearchTemplate(template: String): Boolean =
        template.split("%s").size == 2 && isAllowedNavigation(template.replace("%s", "test")) && template.startsWith("https://")
}
