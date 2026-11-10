package com.nova.browser.core

import org.junit.Assert.*
import org.junit.Test

class UrlPolicyTest {
    private val ddg = "https://duckduckgo.com/?q=%s"
    private fun r(s: String) = UrlPolicy.resolve(s, ddg)

    @Test fun bareHostBecomesHttps() = assertEquals(Resolved.Navigate("https://example.com"), r("example.com"))
    @Test fun hostWithPathAndPort() = assertEquals(Resolved.Navigate("https://example.com:8443/a?b=1"), r("example.com:8443/a?b=1"))
    @Test fun ipv4Navigates() = assertEquals(Resolved.Navigate("https://192.168.1.1"), r("192.168.1.1"))
    @Test fun localhostNavigates() = assertEquals(Resolved.Navigate("https://localhost:3000"), r("localhost:3000"))
    @Test fun explicitHttpKept() = assertEquals(Resolved.Navigate("http://example.com/x"), r("http://example.com/x"))
    @Test fun phraseIsSearch() = assertEquals(Resolved.Search("https://duckduckgo.com/?q=hello+world"), r("hello world"))
    @Test fun singleWordIsSearch() = assertTrue(r("kotlin") is Resolved.Search)
    @Test fun sentenceWithDotIsSearch() = assertTrue(r("what is a.b thing") is Resolved.Search)
    @Test fun searchQueryIsEncoded() = assertEquals(Resolved.Search("https://duckduckgo.com/?q=a%26b%3Dc"), r("a&b=c"))
    @Test fun searchEngineSwitching() =
        assertEquals(Resolved.Search("https://www.bing.com/search?q=cats"), UrlPolicy.resolve("cats", "https://www.bing.com/search?q=%s"))
    @Test fun lenientQueryCharsAccepted() = assertTrue(UrlPolicy.isAllowedNavigation("https://a.com/p?x[]=1|2&y={z}"))
    @Test fun ipv6HostAccepted() = assertTrue(UrlPolicy.isAllowedNavigation("http://[2001:db8::1]:8080/x"))
    @Test fun badPortRejected() = assertFalse(UrlPolicy.isAllowedNavigation("https://a.com:99999/"))
    @Test fun hostParsing() { assertEquals("a.com", UrlPolicy.host("https://User@A.com:8080/x")); assertNull(UrlPolicy.host("nope")) }
    @Test fun emptyRejected() = assertTrue(r("   ") is Resolved.Rejected)

    // ---- security ----
    @Test fun javascriptSchemeRejected() = assertTrue(r("javascript:alert(1)") is Resolved.Rejected)
    @Test fun mixedCaseJavascriptRejected() = assertTrue(r("JaVaScRiPt:alert(1)") is Resolved.Rejected)
    @Test fun dataSchemeRejected() = assertTrue(r("data:text/html,<script>1</script>") is Resolved.Rejected)
    @Test fun fileSchemeRejected() = assertTrue(r("file:///data/data/com.nova.browser/shared_prefs/x.xml") is Resolved.Rejected)
    @Test fun contentSchemeRejected() = assertTrue(r("content://com.android.providers/x") is Resolved.Rejected)
    @Test fun intentSchemeRejected() = assertTrue(r("intent://scan/#Intent;scheme=zxing;end") is Resolved.Rejected)
    @Test fun blobRejected() = assertTrue(r("blob:https://example.com/uuid") is Resolved.Rejected)
    @Test fun ftpRejected() = assertTrue(r("ftp://example.com/file") is Resolved.Rejected)
    @Test fun aboutBlankAllowed() = assertEquals(Resolved.Navigate("about:blank"), r("about:blank"))
    @Test fun otherAboutRejected() = assertTrue(r("about:config") is Resolved.Rejected)
    @Test fun controlCharsRejected() = assertTrue(r("exa\u0000mple.com") is Resolved.Rejected)
    @Test fun userInfoSpoofRejected() = assertTrue(r("https://google.com@evil.com/") is Resolved.Rejected)
    @Test fun hugeInputRejected() = assertTrue(r("a".repeat(9000)) is Resolved.Rejected)

    @Test fun allowedNavigationMatrix() {
        assertTrue(UrlPolicy.isAllowedNavigation("https://a.com/x"))
        assertTrue(UrlPolicy.isAllowedNavigation("http://a.com"))
        assertTrue(UrlPolicy.isAllowedNavigation("about:blank"))
        assertFalse(UrlPolicy.isAllowedNavigation(null))
        assertFalse(UrlPolicy.isAllowedNavigation("javascript:alert(1)"))
        assertFalse(UrlPolicy.isAllowedNavigation("file:///etc/passwd"))
        assertFalse(UrlPolicy.isAllowedNavigation("https://"))
        assertFalse(UrlPolicy.isAllowedNavigation("https://a.com/\n"))
        assertFalse(UrlPolicy.isAllowedNavigation("not a url"))
    }

    @Test fun httpsUpgrade() {
        assertEquals("https://a.com/x", UrlPolicy.upgradeToHttps("http://a.com/x"))
        assertEquals("https://a.com", UrlPolicy.upgradeToHttps("https://a.com"))
    }

    @Test fun maliciousSearchTemplateRejected() {
        assertTrue(UrlPolicy.isValidSearchTemplate("https://site.com/?q=%s"))
        assertFalse(UrlPolicy.isValidSearchTemplate("javascript:alert(%s)"))
        assertFalse(UrlPolicy.isValidSearchTemplate("http://site.com/?q=%s"))
        assertFalse(UrlPolicy.isValidSearchTemplate("https://site.com/?q=%s&r=%s"))
        assertFalse(UrlPolicy.isValidSearchTemplate("https://site.com/"))
    }
}
