package com.nova.browser.adblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nova.browser.core.FilterCategory
import com.nova.browser.core.FilterDecision
import com.nova.browser.core.FilterEngine
import com.nova.browser.core.FilterListValidator
import com.nova.browser.core.FilterRequest
import com.nova.browser.core.ParseStats
import com.nova.browser.data.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin Android wrapper around [FilterEngine] (pure Kotlin, unit tested).
 *
 * Counting: [onBlocked] is called by the WebViewClient ONLY after it has actually returned a blocked response, so
 * statistics are real counts of blocked requests. There is no "data saved" estimate any more.
 *
 * WebView limits (see README): shouldInterceptRequest cannot see request bodies or most headers, does not run for
 * service-worker-initiated requests unless a ServiceWorkerClient is installed, and gives no true resource type.
 * Cosmetic filtering and $redirect/$removeparam are not supported and are skipped by the parser.
 */
class AdBlockEngine(private val ctx: Context, private val prefs: Prefs) {
    @Volatile private var engine: FilterEngine = FilterEngine.Builder().build()
    @Volatile var lastUpdateError: String? = null; private set
    private val main = Handler(Looper.getMainLooper())

    init { reload() }

    fun reload() {
        val b = FilterEngine.Builder()
        runCatching { ctx.assets.open("filters_ads.txt").bufferedReader().use { b.addList(it.readText(), FilterCategory.ADS) } }
        runCatching { ctx.assets.open("filters_trackers.txt").bufferedReader().use { b.addList(it.readText(), FilterCategory.TRACKERS) } }
        val custom = File(ctx.filesDir, CUSTOM_FILE)
        if (custom.isFile && custom.length() <= FilterListValidator.MAX_BYTES) {
            // Re-validate on every load: the file could have been tampered with since it was written.
            val text = runCatching { custom.readText() }.getOrNull()
            if (text != null && FilterListValidator.validateContent(text) is FilterListValidator.Verdict.Ok) b.addList(text, FilterCategory.ADS)
            else custom.delete()
        }
        engine = b.build()
    }

    /** Downloads, validates and atomically installs a custom list. Call off the main thread. Returns null on success, else an error message. */
    fun updateFromUrl(url: String): String? {
        (FilterListValidator.validateUrl(url) as? FilterListValidator.Verdict.Rejected)?.let { lastUpdateError = it.reason; return it.reason }
        val err = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
            try {
                if (c.responseCode !in 200..299) return@runCatching "Server responded ${c.responseCode}"
                if (c.url.protocol != "https") return@runCatching "Redirected to a non-https URL"
                val buf = c.inputStream.use { it.readNBytesCapped(FilterListValidator.MAX_BYTES + 1) }
                if (buf.size > FilterListValidator.MAX_BYTES) return@runCatching "List too large"
                val text = String(buf, Charsets.UTF_8)
                (FilterListValidator.validateContent(text, buf.size) as? FilterListValidator.Verdict.Rejected)?.let { return@runCatching it.reason }
                val tmp = File(ctx.filesDir, "$CUSTOM_FILE.tmp"); tmp.writeText(text)
                if (!tmp.renameTo(File(ctx.filesDir, CUSTOM_FILE))) { tmp.delete(); return@runCatching "Could not save list" }
                null
            } finally { c.disconnect() }
        }.getOrElse { it.message ?: "Download failed" }
        lastUpdateError = err
        if (err == null) reload()
        return err
    }

    private fun java.io.InputStream.readNBytesCapped(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192); var total = 0
        while (total < max) { val n = read(b, 0, minOf(b.size, max - total)); if (n < 0) break; out.write(b, 0, n); total += n }
        return out.toByteArray()
    }

    fun parseStats(): ParseStats = engine.stats
    fun ruleCount() = engine.stats.blockRules + engine.stats.exceptionRules

    fun isWhitelisted(pageHost: String?): Boolean = prefs.isWhitelisted(pageHost)

    /** Pure decision; does not touch statistics. */
    fun decide(req: FilterRequest, isMainFrame: Boolean): FilterDecision {
        if (!prefs.adBlockEnabled || isMainFrame) return FilterDecision.ALLOW
        if (isWhitelisted(req.pageHost)) return FilterDecision.ALLOW
        return engine.decide(req)
    }

    /** Call once per request that was really answered with a blocked response. */
    fun onBlocked(d: FilterDecision) {
        main.post {
            if (d.category == FilterCategory.TRACKERS) prefs.trackersBlocked++ else prefs.adsBlocked++
            blockedSinceSave++
            if (blockedSinceSave >= 25) { blockedSinceSave = 0; prefs.saveStats() }
        }
    }
    private var blockedSinceSave = 0

    companion object { private const val CUSTOM_FILE = "custom_filter.txt" }
}
