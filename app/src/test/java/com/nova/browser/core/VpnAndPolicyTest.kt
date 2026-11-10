package com.nova.browser.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class VpnConfigValidatorTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private fun t(address: String = "10.8.0.7/32", dns: String = "10.8.0.1", endpoint: String = "203.0.113.10:51820", k: String = key) =
        ProvisionedTunnel(address, dns, endpoint, k)

    @Test fun validConfig() { val r = VpnConfigValidator.validate(t()); assertTrue(r is VpnConfigValidator.Result.Valid); r as VpnConfigValidator.Result.Valid; assertEquals(51820, r.endpointPort) }
    @Test fun dualStackConfig() = assertTrue(VpnConfigValidator.validate(t(address = "10.8.0.7/32,fd00::7/128", dns = "10.8.0.1,fd00::1")) is VpnConfigValidator.Result.Valid)
    @Test fun hostnameEndpoint() = assertTrue(VpnConfigValidator.validate(t(endpoint = "vpn.mynet.org:51820")) is VpnConfigValidator.Result.Valid)
    @Test fun ipv6Endpoint() = assertTrue(VpnConfigValidator.validate(t(endpoint = "[2001:db8::1]:51820")) is VpnConfigValidator.Result.Valid)
    @Test fun badAddress() = assertTrue(VpnConfigValidator.validate(t(address = "10.8.0.7")) is VpnConfigValidator.Result.Invalid)
    @Test fun badPrefixLength() = assertTrue(VpnConfigValidator.validate(t(address = "10.8.0.7/33")) is VpnConfigValidator.Result.Invalid)
    @Test fun badOctet() = assertTrue(VpnConfigValidator.validate(t(address = "10.8.0.300/32")) is VpnConfigValidator.Result.Invalid)
    @Test fun dnsMustBeIp() = assertTrue(VpnConfigValidator.validate(t(dns = "dns.evil.com")) is VpnConfigValidator.Result.Invalid)
    @Test fun emptyDnsRejected() = assertTrue(VpnConfigValidator.validate(t(dns = "")) is VpnConfigValidator.Result.Invalid)
    @Test fun endpointNeedsPort() = assertTrue(VpnConfigValidator.validate(t(endpoint = "203.0.113.10")) is VpnConfigValidator.Result.Invalid)
    @Test fun endpointPortRange() { assertTrue(VpnConfigValidator.validate(t(endpoint = "1.2.3.4:0")) is VpnConfigValidator.Result.Invalid); assertTrue(VpnConfigValidator.validate(t(endpoint = "1.2.3.4:70000")) is VpnConfigValidator.Result.Invalid) }
    @Test fun loopbackEndpointRejected() = assertTrue(VpnConfigValidator.validate(t(endpoint = "127.0.0.1:51820")) is VpnConfigValidator.Result.Invalid)
    @Test fun shortKeyRejected() = assertTrue(VpnConfigValidator.validate(t(k = Base64.getEncoder().encodeToString(ByteArray(16) { 1 }))) is VpnConfigValidator.Result.Invalid)
    @Test fun zeroKeyRejected() = assertTrue(VpnConfigValidator.validate(t(k = Base64.getEncoder().encodeToString(ByteArray(32)))) is VpnConfigValidator.Result.Invalid)
    @Test fun nonBase64KeyRejected() = assertTrue(VpnConfigValidator.validate(t(k = "not base64!!")) is VpnConfigValidator.Result.Invalid)
    @Test fun multipleProblemsReported() { val r = VpnConfigValidator.validate(t(address = "x", dns = "y", endpoint = "z", k = "k")) as VpnConfigValidator.Result.Invalid; assertTrue(r.problems.size >= 4) }
    @Test fun placeholderApiIsNotConfigured() {
        assertFalse(VpnConfigValidator.isApiConfigured("https://vpn-api.example.com"))
        assertFalse(VpnConfigValidator.isApiConfigured("http://api.real.net"))
        assertFalse(VpnConfigValidator.isApiConfigured(""))
        assertTrue(VpnConfigValidator.isApiConfigured("https://api.myvpn.net"))
    }
}

class BridgeValidatorTest {
    @Test fun validVideo() = assertEquals(BridgeValidator.Media("https://a.com/v.mp4", 720, false), BridgeValidator.validateFound("https://a.com/v.mp4", 720, "video", 0))
    @Test fun kindCaseInsensitiveAudio() = assertTrue(BridgeValidator.validateFound("https://a.com/a.mp3", 0, "AUDIO", 0)!!.isAudio)
    @Test fun javascriptUrlRejected() = assertNull(BridgeValidator.validateFound("javascript:alert(1)", 0, "video", 0))
    @Test fun fileUrlRejected() = assertNull(BridgeValidator.validateFound("file:///data/x.mp4", 0, "video", 0))
    @Test fun blobRejected() = assertNull(BridgeValidator.validateFound("blob:https://a.com/x", 0, "video", 0))
    @Test fun badKindRejected() = assertNull(BridgeValidator.validateFound("https://a.com/v.mp4", 0, "script", 0))
    @Test fun nullsRejected() { assertNull(BridgeValidator.validateFound(null, 0, "video", 0)); assertNull(BridgeValidator.validateFound("https://a.com/v.mp4", 0, null, 0)) }
    @Test fun hugeUrlRejected() = assertNull(BridgeValidator.validateFound("https://a.com/" + "a".repeat(3000), 0, "video", 0))
    @Test fun heightClamped() { assertEquals(8640, BridgeValidator.validateFound("https://a.com/v.mp4", Int.MAX_VALUE, "video", 0)!!.height); assertEquals(0, BridgeValidator.validateFound("https://a.com/v.mp4", -5, "video", 0)!!.height) }
    @Test fun floodLimited() = assertNull(BridgeValidator.validateFound("https://a.com/v.mp4", 0, "video", BridgeValidator.MAX_CANDIDATES_PER_PAGE))
    @Test fun trustedPages() { assertTrue(BridgeValidator.isTrustedPage("https://a.com")); assertFalse(BridgeValidator.isTrustedPage("http://a.com")); assertFalse(BridgeValidator.isTrustedPage("file:///x")); assertFalse(BridgeValidator.isTrustedPage(null)) }
}

class SessionAndPrivacyTest {
    @Test fun privateTabsNeverPersisted() {
        val enc = SessionCodec.encode(listOf(TabMeta("https://a.com", "A"), TabMeta("https://secret.com", "S", isPrivate = true)))
        assertFalse(enc.contains("secret")); assertEquals(1, SessionCodec.decode(enc).size)
    }
    @Test fun roundTripPreservesMetadata() {
        val t = TabMeta("https://a.com/p?q=1&r=2", "Title\twith\ttabs\nand newline", pinned = true)
        val back = SessionCodec.decode(SessionCodec.encode(listOf(t))).single()
        assertEquals(t.url, back.url); assertEquals(t.title, back.title); assertTrue(back.pinned)
    }
    @Test fun dangerousUrlsDroppedOnEncodeAndDecode() {
        assertEquals("", SessionCodec.encode(listOf(TabMeta("javascript:alert(1)", "x"), TabMeta("file:///etc/passwd", "y"), TabMeta("about:blank", "z"))))
        val tampered = "javascript%3Aalert%281%29\tx\t0\nhttps%3A%2F%2Fok.com\tok\t0\nfile%3A%2F%2F%2Fa\tf\t0\n%ZZ\tbad"
        assertEquals(listOf("https://ok.com"), SessionCodec.decode(tampered).map { it.url })
    }
    @Test fun tabLimit() = assertEquals(50, SessionCodec.decode(SessionCodec.encode((1..80).map { TabMeta("https://a.com/$it", "t") })).size)
    @Test fun emptyAndGarbageDecode() { assertTrue(SessionCodec.decode("").isEmpty()); assertTrue(SessionCodec.decode("\n\n\t\t").isEmpty()) }
    @Test fun historyPolicy() {
        assertTrue(SessionCodec.shouldRecordHistory(false, "https://a.com"))
        assertFalse(SessionCodec.shouldRecordHistory(true, "https://a.com"))
        assertFalse(SessionCodec.shouldRecordHistory(false, "javascript:1"))
        assertFalse(SessionCodec.shouldRecordHistory(false, "about:blank"))
    }
}

class RoutingAndDownloadTest {
    @Test fun directAlwaysLoads() = TorPhase.values().forEach { assertTrue(RoutingPolicy.canLoad(NetworkMode.DIRECT, it)) }
    @Test fun torOnlyLoadsWhenOn() {
        assertTrue(RoutingPolicy.canLoad(NetworkMode.TOR, TorPhase.ON))
        listOf(TorPhase.OFF, TorPhase.STARTING, TorPhase.ERROR, TorPhase.UNSUPPORTED).forEach { assertFalse("$it", RoutingPolicy.canLoad(NetworkMode.TOR, it)) }
    }
    @Test fun blockedMessagesPresentOnlyWhenBlocked() {
        assertNull(RoutingPolicy.blockedMessage(NetworkMode.TOR, TorPhase.ON))
        TorPhase.values().filter { it != TorPhase.ON }.forEach { assertNotNull(RoutingPolicy.blockedMessage(NetworkMode.TOR, it)) }
    }
    @Test fun onionDetection() { assertTrue(RoutingPolicy.isOnion("abc.onion")); assertFalse(RoutingPolicy.isOnion("abc.com")); assertFalse(RoutingPolicy.isOnion(null)) }

    @Test fun downloadHappyPath() {
        assertTrue(DownloadStateMachine.canTransition(DlState.QUEUED, DlState.RUNNING))
        assertTrue(DownloadStateMachine.canTransition(DlState.RUNNING, DlState.COMPLETED))
        assertTrue(DownloadStateMachine.canTransition(DlState.RUNNING, DlState.PAUSED))
        assertTrue(DownloadStateMachine.canTransition(DlState.PAUSED, DlState.RUNNING))
    }
    @Test fun terminalStatesAreFinal() {
        DlState.values().forEach { assertFalse(DownloadStateMachine.canTransition(DlState.COMPLETED, it)); assertFalse(DownloadStateMachine.canTransition(DlState.CANCELLED, it)) }
    }
    @Test fun failedCanOnlyRetryOrCancel() {
        assertTrue(DownloadStateMachine.canTransition(DlState.FAILED, DlState.QUEUED)); assertFalse(DownloadStateMachine.canTransition(DlState.FAILED, DlState.COMPLETED))
    }
    @Test fun retryLimit() {
        assertTrue(DownloadStateMachine.canRetry(DlState.FAILED, 2)); assertFalse(DownloadStateMachine.canRetry(DlState.FAILED, 3)); assertFalse(DownloadStateMachine.canRetry(DlState.RUNNING, 0))
    }
    @Test fun systemStatusMapping() {
        assertEquals(DlState.QUEUED, DownloadStateMachine.fromSystemStatus(1)); assertEquals(DlState.RUNNING, DownloadStateMachine.fromSystemStatus(2))
        assertEquals(DlState.PAUSED, DownloadStateMachine.fromSystemStatus(4)); assertEquals(DlState.COMPLETED, DownloadStateMachine.fromSystemStatus(8))
        assertEquals(DlState.FAILED, DownloadStateMachine.fromSystemStatus(16)); assertEquals(DlState.FAILED, DownloadStateMachine.fromSystemStatus(99))
    }
}
