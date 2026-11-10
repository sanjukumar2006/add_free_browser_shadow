package com.nova.browser.core

import org.junit.Assert.*
import org.junit.Test

class FilterEngineTest {
    private fun eng(vararg lines: String, cat: FilterCategory = FilterCategory.ADS) = FilterEngine.Builder().addLines(lines.asSequence(), cat).build()
    private fun d(e: FilterEngine, url: String, page: String? = "news.com", t: ResourceType = ResourceType.SCRIPT) = e.decide(FilterRequest(url, page, t))

    @Test fun domainAnchorBlocksHostAndSubdomains() {
        val e = eng("||ads.example.com^")
        assertTrue(d(e, "https://ads.example.com/x.js").blocked)
        assertTrue(d(e, "https://sub.ads.example.com/x.js").blocked)
        assertFalse(d(e, "https://example.com/x.js").blocked)
        assertFalse(d(e, "https://notads.example.com/x.js").blocked)
    }
    @Test fun domainAnchorDoesNotMatchLookalikeSuffix() {
        val e = eng("||ads.example.com^")
        assertFalse(d(e, "https://evilads.example.com/x").blocked)
        assertFalse(d(e, "https://ads.example.com.evil.org/x").blocked)
    }
    @Test fun separatorMatchesEndAndPunctuation() {
        val e = eng("||tracker.net^")
        assertTrue(d(e, "https://tracker.net").blocked)
        assertTrue(d(e, "https://tracker.net/").blocked)
        assertTrue(d(e, "https://tracker.net:8080/p").blocked)
    }
    @Test fun wildcardInPath() {
        val e = eng("/banner/*/img")
        assertTrue(d(e, "https://x.com/banner/300x250/img").blocked)
        assertFalse(d(e, "https://x.com/banner/img").blocked)
    }
    @Test fun wildcardInHost() {
        val e = eng("||ad*.cdn.com^")
        assertTrue(d(e, "https://ads1.cdn.com/a").blocked)
        assertFalse(d(e, "https://img.cdn.com/a").blocked)
    }
    @Test fun startAndEndAnchors() {
        val e = eng("|https://a.com/exact|")
        assertTrue(d(e, "https://a.com/exact").blocked)
        assertFalse(d(e, "https://a.com/exact/more").blocked)
        assertFalse(d(e, "https://b.com/?u=https://a.com/exact").blocked)
    }
    @Test fun exceptionOverridesBlock() {
        val e = eng("||ads.example.com^", "@@||ads.example.com/allowed^")
        assertTrue(d(e, "https://ads.example.com/x").blocked)
        val r = d(e, "https://ads.example.com/allowed/x")
        assertFalse(r.blocked); assertEquals("@@||ads.example.com/allowed^", r.exceptedBy)
    }
    @Test fun exceptionScopedToDomainOption() {
        val e = eng("||ads.example.com^", "@@||ads.example.com^\$domain=partner.com")
        assertFalse(d(e, "https://ads.example.com/x", "partner.com").blocked)
        assertTrue(d(e, "https://ads.example.com/x", "other.com").blocked)
    }
    @Test fun importantBeatsException() {
        val e = eng("||ads.example.com^\$important", "@@||ads.example.com^")
        assertTrue(d(e, "https://ads.example.com/x").blocked)
    }
    @Test fun thirdPartyOption() {
        val e = eng("||cdn.site.com/lib.js\$third-party")
        assertFalse(d(e, "https://cdn.site.com/lib.js", "site.com").blocked)
        assertTrue(d(e, "https://cdn.site.com/lib.js", "other.com").blocked)
    }
    @Test fun firstPartyNegation() {
        val e = eng("||cdn.site.com/lib.js\$~third-party")
        assertTrue(d(e, "https://cdn.site.com/lib.js", "site.com").blocked)
        assertFalse(d(e, "https://cdn.site.com/lib.js", "other.com").blocked)
    }
    @Test fun multiPartTldIsSameParty() {
        val e = eng("||a.example.co.uk^\$third-party")
        assertFalse(d(e, "https://a.example.co.uk/x", "www.example.co.uk").blocked)
        assertTrue(d(e, "https://a.example.co.uk/x", "other.co.uk").blocked)
    }
    @Test fun domainOptionInclude() {
        val e = eng("||ads.net^\$domain=a.com|b.com")
        assertTrue(d(e, "https://ads.net/x", "a.com").blocked)
        assertTrue(d(e, "https://ads.net/x", "m.b.com").blocked)
        assertFalse(d(e, "https://ads.net/x", "c.com").blocked)
        assertFalse(d(e, "https://ads.net/x", null).blocked)
    }
    @Test fun domainOptionExclude() {
        val e = eng("||ads.net^\$domain=~safe.com")
        assertFalse(d(e, "https://ads.net/x", "safe.com").blocked)
        assertTrue(d(e, "https://ads.net/x", "c.com").blocked)
    }
    @Test fun typeOptionsApply() {
        val e = eng("||x.com^\$script", "||y.com^\$~image")
        assertTrue(d(e, "https://x.com/a", t = ResourceType.SCRIPT).blocked)
        assertFalse(d(e, "https://x.com/a", t = ResourceType.IMAGE).blocked)
        assertFalse(d(e, "https://y.com/a", t = ResourceType.IMAGE).blocked)
        assertTrue(d(e, "https://y.com/a", t = ResourceType.XHR).blocked)
    }
    @Test fun documentRequestsNeverBlockedByDefaultRules() {
        val e = eng("||news.com^")
        assertFalse(d(e, "https://news.com/", "news.com", ResourceType.DOCUMENT).blocked)
    }
    @Test fun hostsFormatSupported() {
        val e = eng("0.0.0.0 bad.example.org", "127.0.0.1 localhost", "# comment")
        assertTrue(d(e, "https://bad.example.org/a").blocked)
        assertTrue(d(e, "https://x.bad.example.org/a").blocked)
        assertEquals(1, e.stats.blockRules)
        assertEquals(1, e.stats.malformed)
    }
    @Test fun categoriesReported() {
        val t = eng("||t.net^", cat = FilterCategory.TRACKERS)
        assertEquals(FilterCategory.TRACKERS, d(t, "https://t.net/a").category)
    }

    // ---- unsupported / malformed: must never block ----
    @Test fun redirectRulesAreSkippedAndNeverBlock() {
        val e = eng("||ads.com/ad.js\$script,redirect=noopjs", "||x.com^\$redirect-rule=noopjs")
        assertEquals(2, e.stats.unsupportedSkipped); assertEquals(0, e.stats.blockRules)
        assertFalse(d(e, "https://ads.com/ad.js").blocked)
    }
    @Test fun cosmeticRulesSkipped() {
        val e = eng("##.ad-banner", "example.com##div.ad", "example.com#@#.ad", "||a.com^")
        assertEquals(3, e.stats.cosmeticSkipped); assertEquals(1, e.stats.blockRules)
    }
    @Test fun urlsWithBracketsAndPipesStillFiltered() { val e = eng("||a.com^"); assertTrue(d(e, "https://a.com/p?x[]=1|2&y={z}").blocked) }
    @Test fun slashDelimitedRuleIsTreatedAsRegexAndSkipped() { val e = eng("/ads/"); assertEquals(1, e.stats.unsupportedSkipped) }
    @Test fun regexRulesSkipped() { val e = eng("/ads?[0-9]+/"); assertEquals(1, e.stats.unsupportedSkipped); assertFalse(d(e, "https://a.com/ad1").blocked) }
    @Test fun unknownOptionSkippedNotGuessed() { val e = eng("||a.com^\$weirdoption"); assertEquals(1, e.stats.unsupportedSkipped); assertFalse(d(e, "https://a.com/x").blocked) }
    @Test fun malformedRulesCounted() {
        val e = eng("||", "*", "||a.com^\$", "||a.com^\$script,,image", "bad rule with spaces", "||b.com^\$domain=")
        assertEquals(0, e.stats.blockRules); assertEquals(6, e.stats.malformed)
    }
    @Test fun matchEverythingRuleRejected() { val e = eng("*"); assertFalse(d(e, "https://a.com/x").blocked) }
    @Test fun veryLongLineIsMalformed() { val e = eng("||" + "a".repeat(5000) + ".com^"); assertEquals(1, e.stats.malformed) }
    @Test fun regexMetacharactersAreLiteral() {
        val e = eng("||a.com/x(y)+[z]^")
        assertTrue(d(e, "https://a.com/x(y)+[z]").blocked)
        assertFalse(d(e, "https://a.com/xyyz").blocked)
    }
    @Test fun caseInsensitiveUrls() { val e = eng("||ADS.Example.COM^"); assertTrue(d(e, "https://Ads.EXAMPLE.com/X").blocked) }
    @Test fun badUrlAllowed() { val e = eng("||a.com^"); assertFalse(d(e, "not a url").blocked) }
    @Test fun statsAccountForEveryLine() {
        val e = eng("! c", "||a.com^", "@@||b.com^", "##x", "||q\$redirect=y", "||", "")
        assertEquals(7, e.stats.totalLines)
    }
    @Test fun substringTokenDoesNotOverMatch() {
        val e = eng("/ads/*")
        assertTrue(d(e, "https://x.com/ads/banner.png").blocked)
        assertFalse(d(e, "https://x.com/loads/banner.png").blocked)
        assertFalse(d(e, "https://x.com/roads/x").blocked)
    }
    @Test fun performanceOnLargeList() {
        val sb = StringBuilder(); for (i in 0 until 60000) sb.append("||host$i.tracker${i % 50}.com^\n")
        val e = FilterEngine.Builder().addList(sb.toString(), FilterCategory.TRACKERS).build()
        val t0 = System.nanoTime(); repeat(2000) { d(e, "https://cdn.example.org/a/b/c.js?x=$it") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("too slow: ${ms}ms", ms < 1500)
        assertTrue(d(e, "https://host77.tracker27.com/p").blocked)
    }
}

class FilterListValidatorTest {
    @Test fun httpUrlRejected() = assertTrue(FilterListValidator.validateUrl("http://a.com/l.txt") is FilterListValidator.Verdict.Rejected)
    @Test fun httpsUrlAccepted() = assertTrue(FilterListValidator.validateUrl("https://a.com/l.txt") is FilterListValidator.Verdict.Ok)
    @Test fun javascriptUrlRejected() = assertTrue(FilterListValidator.validateUrl("javascript:alert(1)") is FilterListValidator.Verdict.Rejected)
    @Test fun htmlRejected() = assertTrue(FilterListValidator.validateContent("<!DOCTYPE html><html>") is FilterListValidator.Verdict.Rejected)
    @Test fun emptyRejected() = assertTrue(FilterListValidator.validateContent("! only comments\n") is FilterListValidator.Verdict.Rejected)
    @Test fun garbageRejected() = assertTrue(FilterListValidator.validateContent("a b\nc d\ne f\ng h\n||ok.com^") is FilterListValidator.Verdict.Rejected)
    @Test fun goodListAccepted() = assertTrue(FilterListValidator.validateContent("! list\n||ads.example.net^\n||t.example.net^\$third-party\n") is FilterListValidator.Verdict.Ok)
    @Test fun blockEverythingListRejected() = assertTrue(FilterListValidator.validateContent("||example.org^\n||wikipedia.org^\n||github.com^\n||mozilla.org^\n") is FilterListValidator.Verdict.Rejected)
    @Test fun oversizedRejected() = assertTrue(FilterListValidator.validateContent("||a.com^", byteLength = FilterListValidator.MAX_BYTES + 1) is FilterListValidator.Verdict.Rejected)
}
