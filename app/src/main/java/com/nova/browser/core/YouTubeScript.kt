package com.nova.browser.core

/**
 * YouTube ad handling, injected only on YouTube hosts.
 *
 * What it does, only while the player reports an advertisement:
 *  - mutes the video (the previous mute state is restored when the ad ends);
 *  - clicks a skip button when one is present;
 *  - closes a dismissible overlay ad.
 *
 * What it deliberately does NOT do: it never seeks (no `currentTime` writes), so it cannot break normal playback or
 * the user's own seeking. It does not poll: a MutationObserver (debounced) and YouTube's SPA navigation events trigger checks.
 *
 * Selectors are YouTube's internal class names and change without notice; treat a broken selector as a known limit, not a bug
 * that a fixed timer would hide. Versioned: a page that already runs an older version stops it and starts this one.
 */
object YouTubeScript {
    const val VERSION = "3.0.0"

    /** Hosts the script is injected into (the domain and its subdomains). */
    val HOSTS = setOf("youtube.com")

    fun appliesTo(host: String?): Boolean = host != null && HOSTS.any { FilterEngine.hostIsOrUnder(host, it) }

    /** The script source; VERSION is interpolated so the page and the Kotlin side always agree. */
    val SOURCE: String = """
(function () {
  'use strict';
  var V = '$VERSION';
  var prev = window.__novaYt;
  if (prev && prev.v === V) return;
  if (prev && typeof prev.stop === 'function') { try { prev.stop(); } catch (e) {} }

  var AD_PLAYER = ['.html5-video-player.ad-showing', '.html5-video-player.ad-interrupting'];
  var SKIP = ['.ytp-ad-skip-button-modern', '.ytp-ad-skip-button', '.ytp-skip-ad-button'];
  var CLOSE = ['.ytp-ad-overlay-close-button'];

  var st = { v: V, stop: null, obs: null, pending: false, video: null, restoreMuted: null };

  function first(list) {
    for (var i = 0; i < list.length; i++) {
      var el = document.querySelector(list[i]);
      if (el) return el;
    }
    return null;
  }

  function release() {
    if (st.video && st.restoreMuted !== null && st.video.isConnected) st.video.muted = st.restoreMuted;
    st.video = null;
    st.restoreMuted = null;
  }

  function check() {
    var player = first(AD_PLAYER);
    var video = player ? player.querySelector('video') : null;
    if (!player || !video) { release(); return; }
    if (st.video !== video) {
      release();
      st.video = video;
      st.restoreMuted = video.muted;
    }
    if (!video.muted) video.muted = true;
    var skip = first(SKIP);
    if (skip) { skip.click(); return; }
    var close = first(CLOSE);
    if (close) close.click();
  }

  function schedule() {
    if (st.pending) return;
    st.pending = true;
    setTimeout(function () {
      st.pending = false;
      try { check(); } catch (e) {}
    }, 150);
  }

  var root = document.documentElement;
  if (root) {
    st.obs = new MutationObserver(schedule);
    st.obs.observe(root, { subtree: true, childList: true, attributes: true, attributeFilter: ['class'] });
  }
  window.addEventListener('yt-navigate-finish', schedule, true);
  window.addEventListener('yt-page-data-updated', schedule, true);

  st.stop = function () {
    if (st.obs) { st.obs.disconnect(); st.obs = null; }
    window.removeEventListener('yt-navigate-finish', schedule, true);
    window.removeEventListener('yt-page-data-updated', schedule, true);
    release();
  };
  window.__novaYt = st;
  schedule();
})();
"""
}
