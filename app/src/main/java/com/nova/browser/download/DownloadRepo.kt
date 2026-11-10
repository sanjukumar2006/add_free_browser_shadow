package com.nova.browser.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import com.nova.browser.core.FilenameSanitizer
import com.nova.browser.core.UrlPolicy

enum class FileCategory(val label: String) { VIDEO("Videos"), MUSIC("Music"), IMAGE("Images"), DOCUMENT("Documents"), APP("Applications"), OTHER("Other files") }

data class DownloadItem(
    val id: Long, val title: String, val uri: String?, val mime: String, val status: Int,
    val total: Long, val done: Long, val time: Long, val category: FileCategory
) {
    val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
    val running get() = status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
    val finished get() = status == DownloadManager.STATUS_SUCCESSFUL
    val paused get() = status == DownloadManager.STATUS_PAUSED
}

/**
 * Wraps the system DownloadManager: background downloads, notifications and resume-on-network-return come for free.
 * Note: DownloadManager has no user-initiated pause API; downloads auto-pause/resume with connectivity, and can be cancelled.
 */
class DownloadRepo(private val ctx: Context) {
    private val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    /** Returns a reason string to refuse downloads (e.g. Tor active: DownloadManager is outside the WebView proxy and would leak). */
    @Volatile var guard: (() -> String?)? = null
    @Volatile var lastRefusal: String? = null

    /**
     * Enqueues a download with the system DownloadManager. Returns the DownloadManager id, or -1 if the request was refused.
     * Safety rules:
     *  - only http(s) URLs; the file name is sanitized (no path separators / traversal) and is placed under Downloads/Shadow.
     *  - cookies are forwarded ONLY for normal tabs, taken from the default CookieManager. Private-tab downloads never receive
     *    any cookie (the private profile's cookies are not read at all) and no Referer, so they cannot leak private session data
     *    into the shared system downloader. Consequence: private downloads that need a login will fail. That is intentional.
     *  - LIMITATION: DownloadManager is a shared system component; a private download still appears in the system's Downloads
     *    list/notification and the file lands in shared storage. Shadow cannot prevent that with this API.
     */
    fun enqueue(url: String, fileName: String, mime: String?, userAgent: String?, referer: String?, isPrivate: Boolean = false): Long {
        lastRefusal = null
        guard?.invoke()?.let { lastRefusal = it; return -1 }
        if (!UrlPolicy.isAllowedNavigation(url) || url == UrlPolicy.ABOUT_BLANK) { lastRefusal = "Blocked an unsafe download address."; return -1 }
        val safeName = FilenameSanitizer.sanitize(fileName, mime)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(safeName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Shadow/$safeName")
            .setAllowedOverMetered(true)
        mime?.let { req.setMimeType(it) }
        userAgent?.let { req.addRequestHeader("User-Agent", it.take(512)) }
        if (!isPrivate) {
            referer?.takeIf { UrlPolicy.isAllowedNavigation(it) }?.let { req.addRequestHeader("Referer", it) }
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
        }
        return try { dm.enqueue(req) } catch (e: SecurityException) {
            lastRefusal = "Storage permission is required to save downloads on this Android version."; -1
        } catch (e: Exception) { lastRefusal = "Download could not be started: ${e.message}"; -1 }
    }

    fun list(): List<DownloadItem> {
        val out = ArrayList<DownloadItem>()
        dm.query(DownloadManager.Query()).use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)) ?: ""
                val title = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "file"
                out += DownloadItem(
                    id = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)),
                    title = title,
                    uri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)),
                    mime = mime,
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                    total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    time = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)),
                    category = categorize(mime, title))
            }
        }
        return out
    }

    fun categorize(mime: String, name: String): FileCategory {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video") || ext in setOf("mp4", "mkv", "webm", "avi", "mov") -> FileCategory.VIDEO
            mime.startsWith("audio") || ext in setOf("mp3", "m4a", "ogg", "wav", "flac", "opus") -> FileCategory.MUSIC
            mime.startsWith("image") || ext in setOf("jpg", "jpeg", "png", "gif", "webp") -> FileCategory.IMAGE
            ext == "apk" || mime == "application/vnd.android.package-archive" -> FileCategory.APP
            mime.startsWith("text") || mime.contains("pdf") || mime.contains("document") || ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt") -> FileCategory.DOCUMENT
            else -> FileCategory.OTHER
        }
    }

    fun delete(id: Long) { dm.remove(id) }

    fun open(item: DownloadItem) {
        val uri = dm.getUriForDownloadedFile(item.id) ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(i) }
    }

    fun share(item: DownloadItem) {
        val uri = dm.getUriForDownloadedFile(item.id) ?: return
        val i = Intent(Intent.ACTION_SEND).setType(item.mime.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        /** Picks a file name from Content-Disposition or the URL, then sanitizes it (traversal, separators, control chars, length). */
        fun guessName(url: String, disposition: String?, mime: String?): String {
            val fromDisp = disposition?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            val decoded = fromDisp?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            val raw = decoded ?: Uri.parse(url).lastPathSegment
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            return FilenameSanitizer.sanitize(raw, if (ext != null) mime else null)
        }
    }
}
