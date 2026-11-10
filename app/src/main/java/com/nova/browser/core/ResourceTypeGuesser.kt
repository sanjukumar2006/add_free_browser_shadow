package com.nova.browser.core

/**
 * WebView's shouldInterceptRequest does not report a resource type. This infers one from the Accept header and URL extension,
 * which is good enough for `$script` / `$image` style options but is an approximation (documented in README).
 */
object ResourceTypeGuesser {
    fun guess(url: String, isMainFrame: Boolean, accept: String?): ResourceType {
        if (isMainFrame) return ResourceType.DOCUMENT
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        val a = accept?.lowercase() ?: ""
        return when {
            ext == "js" || ext == "mjs" -> ResourceType.SCRIPT
            ext == "css" -> ResourceType.STYLESHEET
            ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp") || a.startsWith("image/") -> ResourceType.IMAGE
            ext in setOf("woff", "woff2", "ttf", "otf", "eot") -> ResourceType.FONT
            ext in setOf("mp4", "webm", "mp3", "m4a", "ogg", "m4v", "mkv") -> ResourceType.MEDIA
            "text/css" in a -> ResourceType.STYLESHEET
            "text/html" in a -> ResourceType.SUBDOCUMENT
            "application/json" in a || "*/*" == a.trim() -> ResourceType.XHR
            else -> ResourceType.OTHER
        }
    }
}
