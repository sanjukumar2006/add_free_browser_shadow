package com.nova.browser.data

import com.nova.browser.core.Resolved
import com.nova.browser.core.UrlPolicy

data class SearchEngine(val id: String, val name: String, val template: String, val onion: Boolean = false)

/** Search PROVIDERS only. Routing through Tor is a separate network setting (see TorEngine). */
object SearchEngines {
    val builtIn = listOf(
        SearchEngine("google", "Google", "https://www.google.com/search?q=%s"),
        SearchEngine("bing", "Microsoft Bing", "https://www.bing.com/search?q=%s"),
        SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        SearchEngine("yahoo", "Yahoo", "https://search.yahoo.com/search?p=%s"),
        SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
        SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s"),
        SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
        SearchEngine("qwant", "Qwant", "https://www.qwant.com/?q=%s"),
        SearchEngine("ddg_onion", "DuckDuckGo (onion, needs Tor)",
            "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/?q=%s", onion = true),
    )

    fun all(prefs: Prefs): List<SearchEngine> = builtIn + prefs.customEngines().mapNotNull {
        val p = it.split("|", limit = 2)
        if (p.size == 2 && UrlPolicy.isValidSearchTemplate(p[1])) SearchEngine("custom_" + p[0], p[0], p[1]) else null
    }

    fun get(prefs: Prefs, id: String): SearchEngine = all(prefs).firstOrNull { it.id == id } ?: builtIn[2]

    /**
     * Turns whatever the user typed into a loadable http(s) URL, or "" when it is empty/unsafe (javascript:, file:, data:, ...).
     * Logic lives in [UrlPolicy] (pure Kotlin, unit tested).
     */
    fun resolve(input: String, engine: SearchEngine): String = when (val r = UrlPolicy.resolve(input, engine.template)) {
        is Resolved.Navigate -> r.url
        is Resolved.Search -> r.url
        is Resolved.Rejected -> ""
    }
}
