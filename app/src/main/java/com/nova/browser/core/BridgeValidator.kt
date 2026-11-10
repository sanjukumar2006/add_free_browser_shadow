package com.nova.browser.core

/** Validation for everything the page-controlled JavaScript bridge can hand to native code. Page input is hostile. */
object BridgeValidator {
    const val MAX_URL = 2048
    const val MAX_CANDIDATES_PER_PAGE = 40

    data class Media(val url: String, val height: Int, val isAudio: Boolean)

    fun validateFound(url: String?, height: Int, kind: String?, currentCount: Int): Media? {
        if (currentCount >= MAX_CANDIDATES_PER_PAGE) return null
        if (url == null || url.length > MAX_URL) return null
        if (!UrlPolicy.isAllowedNavigation(url) || url == UrlPolicy.ABOUT_BLANK) return null
        val k = kind?.lowercase() ?: return null
        if (k != "video" && k != "audio") return null
        return Media(url, height.coerceIn(0, 8640), k == "audio")
    }

    /** The bridge only talks to https pages (or http when the user explicitly allowed it), never about:, file:, data:. */
    fun isTrustedPage(pageUrl: String?): Boolean = pageUrl != null && pageUrl.startsWith("https://") && UrlPolicy.isAllowedNavigation(pageUrl)
}
