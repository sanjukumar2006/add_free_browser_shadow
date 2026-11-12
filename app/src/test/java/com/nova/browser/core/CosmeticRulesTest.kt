package com.nova.browser.core

import org.junit.Assert.*
import org.junit.Test

class CosmeticRulesTest {
    private fun rules(vararg lines: String) = CosmeticRules.parse(lines.asSequence())

    @Test fun genericRuleAppliesEverywhere() {
        val r = rules("##.adsbygoogle")
        assertEquals(listOf(".adsbygoogle"), CosmeticRules.selectorsFor(r, "news.com"))
        assertEquals(listOf(".adsbygoogle"), CosmeticRules.selectorsFor(r, null))
    }

    @Test fun domainRuleAppliesOnlyOnThatSiteAndSubdomains() {
        val r = rules("youtube.com##.ytp-ad-module")
        assertEquals(listOf(".ytp-ad-module"), CosmeticRules.selectorsFor(r, "youtube.com"))
        assertEquals(listOf(".ytp-ad-module"), CosmeticRules.selectorsFor(r, "m.youtube.com"))
        assertTrue(CosmeticRules.selectorsFor(r, "notyoutube.com").isEmpty())
        assertTrue(CosmeticRules.selectorsFor(r, "news.com").isEmpty())
    }

    @Test fun excludedDomainIsNotHiddenAndSubdomainsFollow() {
        val r = rules("example.com,~news.example.com##.ad")
        assertEquals(listOf(".ad"), CosmeticRules.selectorsFor(r, "example.com"))
        assertTrue(CosmeticRules.selectorsFor(r, "news.example.com").isEmpty())
        assertTrue(CosmeticRules.selectorsFor(r, "x.news.example.com").isEmpty())
    }

    @Test fun exceptionRulesAreIgnored() {
        assertTrue(rules("example.com#@#.ad").isEmpty())
    }

    @Test fun commentsAndHeadersIgnored() {
        assertTrue(rules("! comment", "[Adblock Plus 2.0]", "").isEmpty())
    }

    @Test fun extendedAbpSelectorsAreDropped() {
        assertTrue(rules("##div:has-text(Sponsored)", "##:-abp-contains(ad)", "##div:xpath(//a)").isEmpty())
    }

    @Test fun selectorsThatCouldBreakTheStylesheetAreDropped() {
        assertTrue(rules("##.a{color:red}", "##.b;x", "##a<script>", "##div[id=\"x\"", "##div[id=\"x\"]]", "##p(x").isEmpty())
    }

    @Test fun tooBroadSelectorsAreDropped() {
        assertTrue(rules("##*", "##body", "##html").isEmpty())
    }

    @Test fun attributeSelectorsWithQuotesAreKept() {
        val r = rules("##[id^=\"google_ads_iframe\"]", "##iframe[src*=\"doubleclick.net\"]")
        assertEquals(2, CosmeticRules.selectorsFor(r, "a.com").size)
    }

    @Test fun malformedDomainListSkipped() {
        assertTrue(rules("a.com,,b.com##.ad", "~##.ad").isEmpty())
    }

    @Test fun cssHasOneRulePerSelector() {
        val css = CosmeticRules.css(listOf(".a", "div[x]"))
        assertEquals(".a{display:none!important}\ndiv[x]{display:none!important}\n", css)
    }

    @Test fun jsStringEscapesQuotesBackslashesAndNewlines() {
        assertEquals("\"a\\\"b\\\\c\\n\"", CosmeticRules.jsString("a\"b\\c\n"))
        assertEquals("\"\\u003cscript\\u003e\"", CosmeticRules.jsString("<script>"))
    }

    @Test fun injectionScriptContainsEscapedCss() {
        val js = CosmeticRules.injectionJs(listOf("[id^=\"x\"]"))
        assertTrue(js.contains("nova-cosmetic"))
        assertTrue(js.contains("[id^=\\\"x\\\"]{display:none!important}"))
    }

    @Test fun pageScriptNullWhenNothingApplies() {
        assertNull(CosmeticRules.pageScript(rules("example.com##.ad"), "other.com"))
    }

    @Test fun maxSelectorsCapped() {
        val many = (1..CosmeticRules.MAX_SELECTORS + 50).map { "##.c$it" }
        assertEquals(CosmeticRules.MAX_SELECTORS, CosmeticRules.parse(many.asSequence()).size)
    }
}
