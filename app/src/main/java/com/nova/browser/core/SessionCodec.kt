package com.nova.browser.core

import java.net.URLDecoder
import java.net.URLEncoder

/** Metadata we persist about a tab. We never persist WebView state, form data or anything from private tabs. */
data class TabMeta(val url: String, val title: String, val pinned: Boolean = false, val isPrivate: Boolean = false)

object SessionCodec {
    private const val MAX_TABS = 50
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    /** Private tabs, blank tabs and non-http(s) URLs are dropped. Line-based, with all fields percent-encoded (no injection via tabs/newlines). */
    fun encode(tabs: List<TabMeta>): String = tabs
        .filter { !it.isPrivate && UrlPolicy.isAllowedNavigation(it.url) && it.url != UrlPolicy.ABOUT_BLANK }
        .take(MAX_TABS)
        .joinToString("\n") { "${enc(it.url)}\t${enc(it.title.take(200))}\t${if (it.pinned) 1 else 0}" }

    fun decode(text: String): List<TabMeta> = text.lineSequence().mapNotNull { line ->
        runCatching {
            val p = line.split('\t')
            if (p.size < 2) return@runCatching null
            val url = dec(p[0])
            if (!UrlPolicy.isAllowedNavigation(url) || url == UrlPolicy.ABOUT_BLANK) null
            else TabMeta(url, dec(p[1]), p.getOrNull(2) == "1")
        }.getOrNull()
    }.take(MAX_TABS).toList()

    /** History/bookmark persistence policy. */
    fun shouldRecordHistory(isPrivate: Boolean, url: String) = !isPrivate && UrlPolicy.isAllowedNavigation(url) && url != UrlPolicy.ABOUT_BLANK
}
