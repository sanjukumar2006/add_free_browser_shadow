package com.nova.browser.core

/**
 * Element-hiding (cosmetic) filtering, ABP syntax:
 *   `##selector`                 applies on every site
 *   `example.com,~news.example.com##selector`  applies on example.com and its subdomains, not on the excluded ones
 * Exception rules (`#@#`) are NOT supported: they are ignored, so a site may hide something it should not. Selectors that need
 * ABP extensions (`:has-text`, `:-abp-*`, `:xpath`, ...) and selectors that could break out of the injected stylesheet are dropped.
 * Pure Kotlin; the page-script string is built here and run by the WebView wrapper.
 */
object CosmeticRules {
    const val MAX_SELECTORS = 5000
    private const val MAX_SELECTOR_LENGTH = 300

    data class Rule(val include: Set<String>, val exclude: Set<String>, val selector: String)

    private val unsupportedTokens = listOf(":has-text", ":-abp-", ":xpath", ":matches-css", ":upward", ":remove(", ":style(", ":has(", ":nth-ancestor", ":watch-attr")
    private val forbiddenChars = setOf('{', '}', '<', ';', '\\', '`', '\n', '\r', '\t')
    private val tooBroad = setOf("*", "html", "body", "html body", ":root", "head")

    /** Parses cosmetic rules from list text. Everything that is not a plain, safe `##` rule is skipped. */
    fun parse(lines: Sequence<String>): List<Rule> {
        val out = ArrayList<Rule>()
        for (raw in lines) {
            if (out.size >= MAX_SELECTORS) break
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue
            if (line.contains("#@#")) continue // exceptions: unsupported, ignored
            val idx = line.indexOf("##")
            if (idx < 0) continue
            val domainPart = line.substring(0, idx)
            val selector = line.substring(idx + 2).trim()
            if (!isSafeSelector(selector)) continue
            val include = HashSet<String>(); val exclude = HashSet<String>()
            var ok = true
            if (domainPart.isNotEmpty()) for (d0 in domainPart.split(',')) {
                val d = d0.trim().lowercase()
                if (d.isEmpty() || d == "~") { ok = false; break }
                if (d.startsWith("~")) exclude += d.substring(1) else include += d
            }
            if (ok) out += Rule(include, exclude, selector)
        }
        return out
    }

    /** A selector is accepted only if it is a plain CSS selector that cannot break the injected stylesheet. */
    internal fun isSafeSelector(s: String): Boolean {
        if (s.isEmpty() || s.length > MAX_SELECTOR_LENGTH) return false
        if (s in tooBroad) return false
        if (s.any { it in forbiddenChars || it.isISOControl() }) return false
        if (unsupportedTokens.any { s.contains(it) }) return false
        if (s.count { it == '[' } != s.count { it == ']' }) return false
        if (s.count { it == '(' } != s.count { it == ')' }) return false
        if (s.count { it == '"' } % 2 != 0 || s.count { it == '\'' } % 2 != 0) return false
        return true
    }

    /** Whether a rule applies on a page with the given host (null host: only rules that apply everywhere). */
    fun applies(rule: Rule, pageHost: String?): Boolean {
        if (pageHost == null) return rule.include.isEmpty() && rule.exclude.isEmpty()
        if (rule.exclude.any { FilterEngine.hostIsOrUnder(pageHost, it) }) return false
        if (rule.include.isEmpty()) return true
        return rule.include.any { FilterEngine.hostIsOrUnder(pageHost, it) }
    }

    fun selectorsFor(rules: List<Rule>, pageHost: String?): List<String> =
        rules.asSequence().filter { applies(it, pageHost) }.map { it.selector }.distinct().toList()

    /** Stylesheet text: one rule per selector, so one unusable selector cannot disable the others. */
    fun css(selectors: List<String>): String = selectors.joinToString("") { "$it{display:none!important}\n" }

    /** Idempotent script: creates or updates one <style id="nova-cosmetic"> element. Safe to run repeatedly. */
    fun injectionJs(selectors: List<String>): String =
        "(function(){try{var s=document.getElementById('nova-cosmetic');" +
            "if(!s){s=document.createElement('style');s.id='nova-cosmetic';(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=${jsString(css(selectors))};}catch(e){}})();"

    /** Element-hiding script for a host, or null when no selector applies. Gating (on/off, allow-list) is the caller's job. */
    fun pageScript(rules: List<Rule>, pageHost: String?): String? {
        val sels = selectorsFor(rules, pageHost)
        return if (sels.isEmpty()) null else injectionJs(sels)
    }

    /** Encodes a Kotlin string as a double-quoted JavaScript string literal. */
    fun jsString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '<' -> append("\\u003c")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(c)
        }
        append('"')
    }
}
