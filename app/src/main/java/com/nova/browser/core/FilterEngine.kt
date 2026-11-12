package com.nova.browser.core

enum class ResourceType { DOCUMENT, SUBDOCUMENT, SCRIPT, IMAGE, STYLESHEET, XHR, MEDIA, FONT, PING, WEBSOCKET, OTHER }
enum class FilterCategory { ADS, TRACKERS }

data class FilterRequest(val url: String, val pageHost: String?, val type: ResourceType = ResourceType.OTHER)
data class FilterDecision(val blocked: Boolean, val category: FilterCategory? = null, val rule: String? = null, val exceptedBy: String? = null) {
    companion object { val ALLOW = FilterDecision(false) }
}

/** Counts of what the parser did with a list. Nothing is silently dropped: every skipped line is accounted for. */
data class ParseStats(
    val blockRules: Int = 0,
    val exceptionRules: Int = 0,
    val cosmeticSkipped: Int = 0,
    val unsupportedSkipped: Int = 0,
    val malformed: Int = 0,
    val comments: Int = 0,
) {
    val totalLines get() = blockRules + exceptionRules + cosmeticSkipped + unsupportedSkipped + malformed + comments
    operator fun plus(o: ParseStats) = ParseStats(blockRules + o.blockRules, exceptionRules + o.exceptionRules,
        cosmeticSkipped + o.cosmeticSkipped, unsupportedSkipped + o.unsupportedSkipped, malformed + o.malformed, comments + o.comments)
}

/**
 * Network-request filter for a useful subset of Adblock Plus / EasyList syntax:
 *  `||domain^`, `|start`, `end|`, `*` wildcards, `^` separators, `@@` exceptions, `$third-party`/`~third-party`,
 *  `$domain=a.com|~b.com`, resource-type options (`script`, `image`, `~image`, ...), `$important`,
 *  and hosts-file lines (`0.0.0.0 host`).
 * NOT supported (parsed, counted, and skipped so they can never cause a false block):
 *  cosmetic rules (`##`, `#@#`, `#?#`), regex rules (`/.../`), `$redirect`, `$removeparam`, `$csp`, `$replace`, `$popup`,
 *  `$document`/`$elemhide`, `$badfilter`, `$match-case`, scriptlets.
 * Third-party detection uses a small built-in multi-part-TLD list, not the full Public Suffix List.
 */
class FilterEngine private constructor(
    private val domainIndex: Map<String, List<Rule>>,
    private val tokenIndex: Map<String, List<Rule>>,
    private val generic: List<Rule>,
    private val exceptDomainIndex: Map<String, List<Rule>>,
    private val exceptTokenIndex: Map<String, List<Rule>>,
    private val exceptGeneric: List<Rule>,
    val stats: ParseStats,
) {
    internal class Rule(
        val raw: String, val category: FilterCategory, val regex: Regex,
        val thirdParty: Boolean?, val includeDomains: Set<String>, val excludeDomains: Set<String>,
        val types: Set<ResourceType>?, val notTypes: Set<ResourceType>, val important: Boolean,
    ) {
        fun matches(url: String, req: FilterRequest, reqHost: String?): Boolean {
            val t = req.type
            if (t == ResourceType.DOCUMENT) { if (types == null || ResourceType.DOCUMENT !in types) return false }
            else { if (types != null && t !in types) return false; if (t in notTypes) return false }
            if (thirdParty != null && isThirdParty(reqHost, req.pageHost) != thirdParty) return false
            if (includeDomains.isNotEmpty() || excludeDomains.isNotEmpty()) {
                val ph = req.pageHost
                if (ph != null && excludeDomains.any { hostIsOrUnder(ph, it) }) return false
                if (includeDomains.isNotEmpty() && (ph == null || includeDomains.none { hostIsOrUnder(ph, it) })) return false
            }
            return regex.containsMatchIn(url)
        }
    }

    /** True if some rule, exception or not, would apply; used for tests and diagnostics only. */
    fun decide(req: FilterRequest): FilterDecision {
        val url = req.url.lowercase()
        val reqHost = hostOf(url) ?: return FilterDecision.ALLOW
        val hit = firstMatch(url, req, reqHost, domainIndex, tokenIndex, generic, wantImportant = false) ?: return FilterDecision.ALLOW
        if (!hit.important) {
            firstMatch(url, req, reqHost, exceptDomainIndex, exceptTokenIndex, exceptGeneric, wantImportant = false)?.let {
                return FilterDecision(false, hit.category, hit.raw, exceptedBy = it.raw)
            }
        }
        return FilterDecision(true, hit.category, hit.raw)
    }

    /**
     * Decision for a top-level navigation or a pop-up/redirect target. Such URLs were never checked before (the WebView only
     * gives sub-resource requests to shouldInterceptRequest), so ad and malware hosts could open as pop-ups or redirect hops.
     * Uses the same rules as sub-resources, but the request is typed OTHER: `$script`, `$image`, etc. rules never block a
     * navigation, and only rules without a type restriction (or with `$~type` for other types) apply.
     */
    fun decideNavigation(url: String, pageHost: String?): FilterDecision =
        decide(FilterRequest(url, pageHost, ResourceType.OTHER))

    private fun firstMatch(
        url: String, req: FilterRequest, reqHost: String,
        dom: Map<String, List<Rule>>, tok: Map<String, List<Rule>>, gen: List<Rule>, wantImportant: Boolean,
    ): Rule? {
        var best: Rule? = null
        fun consider(r: Rule) { if (best == null || (r.important && !best!!.important)) best = r }
        var h: String? = reqHost
        while (h != null) {
            dom[h]?.forEach { if (it.matches(url, req, reqHost)) consider(it) }
            val i = h.indexOf('.'); h = if (i < 0) null else h.substring(i + 1)
        }
        for (t in tokenize(url)) tok[t]?.forEach { if (it.matches(url, req, reqHost)) consider(it) }
        gen.forEach { if (it.matches(url, req, reqHost)) consider(it) }
        return best
    }

    class Builder {
        private val dom = HashMap<String, MutableList<Rule>>(); private val tok = HashMap<String, MutableList<Rule>>(); private val gen = ArrayList<Rule>()
        private val eDom = HashMap<String, MutableList<Rule>>(); private val eTok = HashMap<String, MutableList<Rule>>(); private val eGen = ArrayList<Rule>()
        private var stats = ParseStats()

        fun addList(text: String, category: FilterCategory): Builder = addLines(text.lineSequence(), category)

        fun addLines(lines: Sequence<String>, category: FilterCategory): Builder {
            var s = stats
            for (line in lines) {
                when (val p = parseLine(line, category)) {
                    is Parsed.Comment -> s = s.copy(comments = s.comments + 1)
                    is Parsed.Cosmetic -> s = s.copy(cosmeticSkipped = s.cosmeticSkipped + 1)
                    is Parsed.Unsupported -> s = s.copy(unsupportedSkipped = s.unsupportedSkipped + 1)
                    is Parsed.Malformed -> s = s.copy(malformed = s.malformed + 1)
                    is Parsed.Ok -> {
                        if (p.exception) { s = s.copy(exceptionRules = s.exceptionRules + 1); place(p, eDom, eTok, eGen) }
                        else { s = s.copy(blockRules = s.blockRules + 1); place(p, dom, tok, gen) }
                    }
                }
            }
            stats = s
            return this
        }

        private fun place(p: Parsed.Ok, d: MutableMap<String, MutableList<Rule>>, t: MutableMap<String, MutableList<Rule>>, g: MutableList<Rule>) {
            when {
                p.hostKey != null -> d.getOrPut(p.hostKey) { ArrayList() }.add(p.rule)
                p.token != null -> t.getOrPut(p.token) { ArrayList() }.add(p.rule)
                else -> g.add(p.rule)
            }
        }

        fun build() = FilterEngine(dom, tok, gen, eDom, eTok, eGen, stats)
    }

    private sealed class Parsed {
        object Comment : Parsed(); object Cosmetic : Parsed(); object Unsupported : Parsed(); object Malformed : Parsed()
        class Ok(val rule: Rule, val exception: Boolean, val hostKey: String?, val token: String?) : Parsed()
    }

    companion object {
        private const val MAX_LINE = 2048
        private val unsupportedOptions = setOf("redirect", "redirect-rule", "removeparam", "queryprune", "csp", "replace", "popup", "document", "doc",
            "elemhide", "ehide", "generichide", "ghide", "genericblock", "badfilter", "match-case", "cname", "permissions", "rewrite", "inline-script", "inline-font", "header", "all")
        private val typeNames = mapOf(
            "script" to ResourceType.SCRIPT, "image" to ResourceType.IMAGE, "stylesheet" to ResourceType.STYLESHEET, "css" to ResourceType.STYLESHEET,
            "xmlhttprequest" to ResourceType.XHR, "xhr" to ResourceType.XHR, "subdocument" to ResourceType.SUBDOCUMENT, "frame" to ResourceType.SUBDOCUMENT,
            "media" to ResourceType.MEDIA, "font" to ResourceType.FONT, "ping" to ResourceType.PING, "websocket" to ResourceType.WEBSOCKET, "other" to ResourceType.OTHER,
        )
        private val multiPartTlds = setOf("co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.in", "net.in", "org.in", "co.jp", "ne.jp",
            "com.br", "com.cn", "com.mx", "co.nz", "co.za", "com.tr", "com.sg", "com.hk", "co.kr")
        private val ipLike = Regex("""^[\d.]+$|^\[?[0-9a-f:]+]?$""")

        fun hostOf(url: String): String? = UrlParts.parse(url)?.host

        internal fun hostIsOrUnder(host: String, domain: String) = host == domain || host.endsWith(".$domain")

        internal fun registrable(host: String): String {
            if (ipLike.matches(host)) return host
            val labels = host.split('.')
            if (labels.size <= 2) return host
            val last2 = labels.takeLast(2).joinToString(".")
            return if (last2 in multiPartTlds && labels.size >= 3) labels.takeLast(3).joinToString(".") else last2
        }

        internal fun isThirdParty(reqHost: String?, pageHost: String?): Boolean {
            if (reqHost == null || pageHost == null) return false
            return registrable(reqHost) != registrable(pageHost)
        }

        private val tokenSplit = Regex("[^a-z0-9]+")
        internal fun tokenize(url: String): Set<String> = url.split(tokenSplit).filter { it.length >= 3 }.toSet()

        private fun parseLine(rawLine: String, category: FilterCategory): Parsed {
            var line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[Adblock") || line.startsWith("#") && !line.contains("##")) return Parsed.Comment
            if (line.length > MAX_LINE) return Parsed.Malformed
            if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#\$#") || line.contains("#%#")) return Parsed.Cosmetic

            // hosts-file format: "0.0.0.0 ads.example.com" (also 127.0.0.1)
            val hostsMatch = Regex("""^(?:0\.0\.0\.0|127\.0\.0\.1)\s+([a-z0-9.-]+)\s*(?:#.*)?$""", RegexOption.IGNORE_CASE).find(line)
            if (hostsMatch != null) {
                val h = hostsMatch.groupValues[1].lowercase()
                if (!h.contains('.') || h == "localhost" || h.startsWith(".") || h.endsWith(".")) return Parsed.Malformed
                return Parsed.Ok(mk("||$h^", category, Regex("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?${Regex.escape(h)}(?:[^a-zA-Z0-9_.%-]|$)"), null, emptySet(), emptySet(), null, emptySet(), false), false, h, null)
            }
            if (line.contains(' ')) return Parsed.Malformed

            var exception = false
            if (line.startsWith("@@")) { exception = true; line = line.substring(2) }

            var pattern = line
            var optionText: String? = null
            val dollar = line.lastIndexOf('$')
            if (dollar >= 0 && !(line.startsWith("/") && line.endsWith("/"))) {
                val tail = line.substring(dollar + 1)
                // '$' inside a pattern (rare) vs option separator: options contain only [a-z0-9_=,|~.-:]
                if (Regex("""^[A-Za-z0-9_=,|~.:\-/*]*$""").matches(tail) && tail.isNotEmpty()) { pattern = line.substring(0, dollar); optionText = tail }
                else if (tail.isEmpty()) return Parsed.Malformed
            }
            if (pattern.length >= 2 && pattern.startsWith("/") && pattern.endsWith("/")) return Parsed.Unsupported // regex rule
            if (pattern.isEmpty() || pattern == "*" || pattern == "||" || pattern == "|") return Parsed.Malformed

            var thirdParty: Boolean? = null
            var include = emptySet<String>(); var exclude = emptySet<String>()
            var types: MutableSet<ResourceType>? = null; val notTypes = HashSet<ResourceType>(); var important = false
            if (optionText != null) {
                for (opt in optionText.split(',')) {
                    if (opt.isEmpty()) return Parsed.Malformed
                    val neg = opt.startsWith("~"); val name = opt.removePrefix("~").lowercase()
                    when {
                        name == "third-party" || name == "3p" -> thirdParty = !neg
                        name == "first-party" || name == "1p" -> thirdParty = neg
                        name == "important" -> important = true
                        name.startsWith("domain=") -> {
                            val inc = HashSet<String>(); val exc = HashSet<String>()
                            for (d in opt.substringAfter('=').split('|')) {
                                if (d.isEmpty() || d == "~") return Parsed.Malformed
                                if (d.startsWith("~")) exc += d.substring(1).lowercase() else inc += d.lowercase()
                            }
                            include = inc; exclude = exc
                        }
                        name.substringBefore('=') in unsupportedOptions -> return Parsed.Unsupported
                        typeNames.containsKey(name) -> { val rt = typeNames.getValue(name); if (neg) notTypes += rt else (types ?: HashSet<ResourceType>().also { types = it }).add(rt) }
                        else -> return Parsed.Unsupported // unknown option: never guess
                    }
                }
            }

            val compiled = compilePattern(pattern) ?: return Parsed.Malformed
            val rule = mk(rawLine.trim(), category, compiled.first, thirdParty, include, exclude, types, notTypes, important)
            return Parsed.Ok(rule, exception, compiled.second, if (compiled.second == null) pickToken(pattern) else null)
        }

        private fun mk(raw: String, c: FilterCategory, rx: Regex, tp: Boolean?, inc: Set<String>, exc: Set<String>, types: Set<ResourceType>?, not: Set<ResourceType>, imp: Boolean) =
            Rule(raw, c, rx, tp, inc, exc, types, not, imp)

        /** Returns (regex, hostKey) where hostKey is non-null only for plain `||host^...` rules that can use the host index. */
        private fun compilePattern(p0: String): Pair<Regex, String?>? {
            var p = p0.lowercase()
            val sb = StringBuilder()
            var hostKey: String? = null
            when {
                p.startsWith("||") -> {
                    p = p.substring(2)
                    sb.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?")
                    val m = Regex("""^[a-z0-9.-]+""").find(p)
                    if (m != null && m.value.contains('.') && !m.value.startsWith(".") && !m.value.endsWith(".") &&
                        (m.range.last == p.length - 1 || p[m.range.last + 1] in "^/?:")) hostKey = m.value
                }
                p.startsWith("|") -> { p = p.substring(1); sb.append("^") }
            }
            var endAnchor = false
            if (p.endsWith("|") && p.length > 1) { endAnchor = true; p = p.dropLast(1) }
            if (p.isEmpty() && sb.isEmpty()) return null
            for (ch in p) when (ch) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^a-zA-Z0-9_.%-]|$)")
                else -> sb.append(Regex.escape(ch.toString()))
            }
            if (endAnchor) sb.append("$")
            val rx = runCatching { Regex(sb.toString()) }.getOrNull() ?: return null
            return rx to hostKey
        }

        /** Longest alphanumeric run that is bounded by literal characters on both sides (so it must appear as a whole URL token). */
        private fun pickToken(pattern: String): String? {
            val p = pattern.lowercase().removePrefix("||").removePrefix("|")
            var best: String? = null
            val m = Regex("[a-z0-9]{3,}").findAll(p)
            for (t in m) {
                val before = t.range.first - 1; val after = t.range.last + 1
                val okBefore = before >= 0 && p[before] != '*' && !p[before].isLetterOrDigit() && (before > 0 || true)
                val okAfter = after < p.length && p[after] != '*' && !p[after].isLetterOrDigit()
                if (okBefore && okAfter && (best == null || t.value.length > best.length)) best = t.value
            }
            return best
        }
    }
}
