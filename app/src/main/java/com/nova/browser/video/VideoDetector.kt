package com.nova.browser.video

import android.net.Uri

data class VideoCandidate(val url: String, val mime: String, val label: String, val height: Int = 0) {
    val ext: String get() = when {
        mime.contains("webm") -> "webm"
        mime.contains("matroska") -> "mkv"
        mime.contains("mpeg") && mime.startsWith("audio") -> "mp3"
        mime.startsWith("audio") -> "m4a"
        else -> "mp4"
    }
    val isAudio get() = mime.startsWith("audio")
}

/**
 * Detects DIRECT media files only (mp4/webm/mkv/mp3/m4a/ogg) from network requests and <video>/<audio> tags.
 * It deliberately does NOT handle blob:, DRM (EME) or encrypted HLS/DASH manifests, and never bypasses site protections.
 */
object VideoDetector {
    private val exts = mapOf("mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "ogg" to "audio/ogg", "opus" to "audio/ogg")
    private val protectedHosts = listOf("youtube.com", "googlevideo.com", "youtu.be", "facebook.com", "fbcdn.net", "netflix.com",
        "primevideo.com", "hotstar.com", "disneyplus.com", "spotify.com")

    /** Platforms whose terms do not allow third-party downloading: we show an explanation instead. */
    fun isRestrictedPlatform(host: String?): Boolean = host != null && protectedHosts.any { host == it || host.endsWith(".$it") }

    fun fromRequest(url: String): VideoCandidate? {
        val path = Uri.parse(url).path?.lowercase() ?: return null
        val ext = path.substringAfterLast('.', "")
        val mime = exts[ext] ?: return null
        val host = Uri.parse(url).host
        if (isRestrictedPlatform(host)) return null
        val name = path.substringAfterLast('/').take(40)
        return VideoCandidate(url, mime, name.ifBlank { ext.uppercase() })
    }

    /** JS injected on page finish. Reports media elements that have a plain http(s) source. */
    val SCAN_JS = """
    (function(){
      if(window.__novaScan) return; window.__novaScan = true;
      function report(el){
        try{
          var srcs=[]; if(el.currentSrc) srcs.push(el.currentSrc); if(el.src) srcs.push(el.src);
          el.querySelectorAll('source').forEach(function(s){ if(s.src) srcs.push(s.src); });
          srcs.forEach(function(u){
            if(u && u.indexOf('http')===0) NovaVideo.found(u, el.videoHeight||0, el.tagName.toLowerCase());
          });
          if(el.src && el.src.indexOf('blob:')===0) NovaVideo.blob();
        }catch(e){}
      }
      function scan(){ document.querySelectorAll('video,audio').forEach(function(el){
        report(el); if(!el.__nv){ el.__nv=1; el.addEventListener('playing', function(){report(el)}); el.addEventListener('loadedmetadata', function(){report(el)}); }
      }); }
      scan(); new MutationObserver(scan).observe(document.documentElement,{childList:true,subtree:true});
    })();
    """.trimIndent()
}
