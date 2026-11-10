package com.nova.browser.core

/**
 * Produces a safe single-segment file name from untrusted input (URL, Content-Disposition, page title).
 * Guarantees: no path separators, no "..", no control chars, no leading dots, bounded length, never empty.
 */
object FilenameSanitizer {
    private const val MAX_LEN = 120
    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")
    private val reservedWin = setOf("con", "prn", "aux", "nul") + (1..9).map { "com$it" } + (1..9).map { "lpt$it" }
    private val mimeExt = mapOf(
        "video/mp4" to "mp4", "video/webm" to "webm", "video/x-matroska" to "mkv",
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/ogg" to "ogg", "application/pdf" to "pdf",
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp",
        "text/plain" to "txt", "application/zip" to "zip",
    )

    fun extensionForMime(mime: String?): String? = mime?.substringBefore(';')?.trim()?.lowercase()?.let { mimeExt[it] }

    fun sanitize(raw: String?, mime: String? = null, fallbackBase: String = "download"): String {
        var s = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')   // strip any directory part
        s = illegal.replace(s, "_").trim().trim('.', ' ')
        while (s.contains("..")) s = s.replace("..", ".")
        if (s.isEmpty()) s = fallbackBase
        var base = s.substringBeforeLast('.', s)
        var ext = if (s.contains('.')) s.substringAfterLast('.') else ""
        if (base.isEmpty()) { base = fallbackBase }
        if (base.lowercase() in reservedWin) base = "_$base"
        if (ext.isEmpty()) extensionForMime(mime)?.let { ext = it }
        if (ext.length > 10) { base += "." + ext; ext = "" }
        val keep = MAX_LEN - (if (ext.isEmpty()) 0 else ext.length + 1)
        if (base.length > keep) base = base.take(keep)
        return if (ext.isEmpty()) base else "$base.$ext"
    }

    /** Returns [name] or "base (n).ext" so that it does not collide with [existing] (case-insensitive). */
    fun uniquify(name: String, existing: Set<String>): String {
        val lower = existing.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 1
        while ("$base ($n)$ext".lowercase() in lower) n++
        return "$base ($n)$ext"
    }

    /** Reject MIME types that make no sense for the declared extension class of a "video" download. */
    fun isAcceptableMedia(mime: String?): Boolean {
        val m = mime?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return m.startsWith("video/") || m.startsWith("audio/") || m == "application/octet-stream"
    }
}
