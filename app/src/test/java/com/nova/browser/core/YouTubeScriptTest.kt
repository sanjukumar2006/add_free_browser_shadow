package com.nova.browser.core

import org.junit.Assert.*
import org.junit.Test

class YouTubeScriptTest {
    @Test fun appliesOnlyToYouTubeHosts() {
        assertTrue(YouTubeScript.appliesTo("youtube.com"))
        assertTrue(YouTubeScript.appliesTo("www.youtube.com"))
        assertTrue(YouTubeScript.appliesTo("m.youtube.com"))
        assertFalse(YouTubeScript.appliesTo("notyoutube.com"))
        assertFalse(YouTubeScript.appliesTo("youtube.com.evil.net"))
        assertFalse(YouTubeScript.appliesTo("news.com"))
        assertFalse(YouTubeScript.appliesTo(null))
    }

    @Test fun carriesVersionIdentifier() {
        assertEquals("3.0.0", YouTubeScript.VERSION)
        assertTrue(YouTubeScript.SOURCE.contains("var V = '3.0.0';"))
    }

    @Test fun usesObserverNotPolling() {
        assertTrue(YouTubeScript.SOURCE.contains("MutationObserver"))
        assertFalse("must not poll on a timer", YouTubeScript.SOURCE.contains("setInterval"))
    }

    @Test fun neverSeeksThePlayer() {
        // Blind seeking to the end was removed in v3: it must not appear anywhere in the script.
        assertFalse(YouTubeScript.SOURCE.contains("currentTime"))
    }

    @Test fun actsOnlyOnConfirmedAdState() {
        assertTrue(YouTubeScript.SOURCE.contains("'.html5-video-player.ad-showing'"))
        assertTrue(YouTubeScript.SOURCE.contains("'.html5-video-player.ad-interrupting'"))
        assertTrue(YouTubeScript.SOURCE.contains("if (!player || !video) { release(); return; }"))
    }

    @Test fun restoresMuteStateAfterAd() {
        assertTrue(YouTubeScript.SOURCE.contains("st.restoreMuted"))
        assertTrue(YouTubeScript.SOURCE.contains("function release()"))
    }

    @Test fun hasIdempotentGuardAndStop() {
        assertTrue(YouTubeScript.SOURCE.contains("if (prev && prev.v === V) return;"))
        assertTrue(YouTubeScript.SOURCE.contains("prev.stop()"))
        assertTrue(YouTubeScript.SOURCE.contains("st.stop = function"))
    }

    @Test fun handlesSpaNavigation() {
        assertTrue(YouTubeScript.SOURCE.contains("yt-navigate-finish"))
        assertTrue(YouTubeScript.SOURCE.contains("yt-page-data-updated"))
    }

    @Test fun clicksSkipButtonsAndOverlayCloseOnly() {
        assertTrue(YouTubeScript.SOURCE.contains("skip.click()"))
        assertTrue(YouTubeScript.SOURCE.contains("'.ytp-ad-skip-button-modern'"))
        assertTrue(YouTubeScript.SOURCE.contains("'.ytp-ad-overlay-close-button'"))
    }

    @Test fun scriptHasBalancedBrackets() {
        val s = YouTubeScript.SOURCE
        assertEquals(s.count { it == '{' }, s.count { it == '}' })
        assertEquals(s.count { it == '(' }, s.count { it == ')' })
        assertEquals(s.count { it == '[' }, s.count { it == ']' })
    }
}
