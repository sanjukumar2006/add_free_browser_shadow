package com.nova.browser.core

/**
 * Gatekeeper for downloaded filter lists. A hostile or broken list must not be able to
 * (a) exhaust memory, (b) block the whole web, or (c) smuggle in non-list content.
 */
object FilterListValidator {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_LINES = 400_000

    sealed class Verdict {
        object Ok : Verdict()
        data class Rejected(val reason: String) : Verdict()
    }

    fun validateUrl(url: String): Verdict {
        if (!url.startsWith("https://")) return Verdict.Rejected("filter list URLs must use https")
        if (!UrlPolicy.isAllowedNavigation(url)) return Verdict.Rejected("invalid URL")
        return Verdict.Ok
    }

    fun validateContent(text: String, byteLength: Int = text.toByteArray().size): Verdict {
        if (byteLength > MAX_BYTES) return Verdict.Rejected("list too large")
        val head = text.take(512).trimStart().lowercase()
        if (head.startsWith("<!doctype") || head.startsWith("<html") || head.startsWith("{") ) return Verdict.Rejected("not a filter list")
        val lines = text.lineSequence().count()
        if (lines > MAX_LINES) return Verdict.Rejected("too many lines")
        val engine = FilterEngine.Builder().addList(text, FilterCategory.ADS).build()
        val s = engine.stats
        val useful = s.blockRules + s.exceptionRules
        if (useful == 0) return Verdict.Rejected("no usable rules")
        if (s.malformed > (s.totalLines - s.comments) / 4) return Verdict.Rejected("too many malformed lines")
        // A list that blocks everything would brick browsing.
        val probes = listOf("https://example.org/", "https://www.wikipedia.org/", "https://github.com/", "https://www.mozilla.org/")
        if (probes.any { engine.decide(FilterRequest(it, "example.net", ResourceType.SCRIPT)).blocked })
            return Verdict.Rejected("list blocks well-known first-party sites")
        return Verdict.Ok
    }
}
