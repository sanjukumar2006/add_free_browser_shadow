package com.nova.browser.adblock

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nova.browser.core.CosmeticRules
import com.nova.browser.core.FilterCategory
import com.nova.browser.core.FilterDecision
import com.nova.browser.core.FilterEngine
import com.nova.browser.core.FilterListValidator
import com.nova.browser.core.FilterRequest
import com.nova.browser.core.ParseStats
import com.nova.browser.core.SubscriptionPolicy
import com.nova.browser.core.YouTubeScript
import com.nova.browser.data.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android wrapper around [FilterEngine] and [CosmeticRules] (both pure Kotlin, unit tested).
 *
 * Loading: rules are parsed on a background thread, never on the main thread. Until the first load finishes the engine is
 * empty, so nothing is blocked yet, and then it is swapped in atomically.
 *
 * Sources, in order: bundled assets (ads, trackers, YouTube, cosmetic), subscriptions cached on disk (EasyList, EasyPrivacy),
 * and the user's custom https list. Every cached file is re-validated on each load.
 *
 * Counting: [onBlocked] is called only after a blocked response was really returned, so counters are real.
 *
 * WebView limits (see README): shouldInterceptRequest cannot see request bodies, does not see service-worker traffic unless a
 * ServiceWorkerClient is installed, and gives only an inferred resource type. $redirect and $removeparam are not applied.
 */
class AdBlockEngine(private val ctx: Context, private val prefs: Prefs) {
    @Volatile private var engine: FilterEngine = FilterEngine.Builder().build()
    @Volatile private var cosmetic: List<CosmeticRules.Rule> = emptyList()
    @Volatile var lastUpdateError: String? = null; private set
    private val loadLock = Any()
    private val main = Handler(Looper.getMainLooper())

    init { background("adblock-load") { reload() } }

    /** Loads (or re-loads) every source. Blocking: call from a background thread. */
    fun reload() {
      synchronized(loadLock) {
        val b = FilterEngine.Builder()
        val cos = ArrayList<CosmeticRules.Rule>()

        assetText("filters_ads.txt")?.let { b.addList(it, FilterCategory.ADS) }
        assetText("filters_trackers.txt")?.let { b.addList(it, FilterCategory.TRACKERS) }
        assetText("filters_cosmetic.txt")?.let { cos.addAll(CosmeticRules.parse(it.lineSequence())) }
        if (prefs.youtubeProtection) assetText("filters_youtube.txt")?.let { b.addList(it, FilterCategory.ADS) }

        for (sub in SubscriptionPolicy.ALL) {
            if (!prefs.subEnabled(sub.id)) continue
            val f = File(ctx.filesDir, SubscriptionPolicy.cacheFileName(sub))
            if (!f.isFile || f.length() > FilterListValidator.MAX_BYTES) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            if (FilterListValidator.validateContent(text) !is FilterListValidator.Verdict.Ok) { f.delete(); continue }
            val cat = if (sub.id == SubscriptionPolicy.EASYPRIVACY.id) FilterCategory.TRACKERS else FilterCategory.ADS
            b.addList(text, cat)
            cos.addAll(CosmeticRules.parse(text.lineSequence()))
        }

        val custom = File(ctx.filesDir, CUSTOM_FILE)
        if (custom.isFile && custom.length() <= FilterListValidator.MAX_BYTES) {
            // Re-validate on every load: the file could have been tampered with since it was written.
            val text = runCatching { custom.readText() }.getOrNull()
            if (text != null && FilterListValidator.validateContent(text) is FilterListValidator.Verdict.Ok) {
                b.addList(text, FilterCategory.ADS)
                cos.addAll(CosmeticRules.parse(text.lineSequence()))
            } else custom.delete()
        }

        engine = b.build()
        cosmetic = cos
      }
    }

    /** Call after a settings change that affects the rule set (YouTube protection, subscriptions). Reloads off the main thread. */
    fun settingsChanged() = background("adblock-reload") { reload() }

    // ---- subscriptions ----

    data class SubscriptionStatus(val id: String, val title: String, val enabled: Boolean, val lastUpdatedMs: Long, val error: String?)

    fun subscriptionStatus(): List<SubscriptionStatus> = SubscriptionPolicy.ALL.map {
        SubscriptionStatus(it.id, it.title, prefs.subEnabled(it.id), prefs.subUpdated(it.id), prefs.subError(it.id))
    }

    /**
     * Downloads each enabled subscription that is due (at most weekly; after a failure, retried no sooner than
     * [RETRY_AFTER_FAILURE_MS]). [force] ignores both limits (the user pressed "Check now"). A failed download keeps the cached copy, so offline use continues to work. Blocking.
     */
    fun refreshSubscriptionsIfDue(nowMs: Long = System.currentTimeMillis(), force: Boolean = false): Boolean {
        var changed = false
        for (sub in SubscriptionPolicy.ALL) {
            if (!prefs.subEnabled(sub.id)) continue
            if (!force && !SubscriptionPolicy.isUpdateDue(prefs.subUpdated(sub.id), nowMs)) continue
            if (!force && prefs.subError(sub.id) != null && nowMs - prefs.subAttempted(sub.id) < RETRY_AFTER_FAILURE_MS) continue
            prefs.setSubAttempted(sub.id, nowMs)
            if (!SubscriptionPolicy.isAllowedSubscriptionUrl(sub.url)) { prefs.setSubError(sub.id, "Subscription URL is not https"); continue }
            val (text, err) = fetch(sub.url)
            if (text != null) {
                if (install(File(ctx.filesDir, SubscriptionPolicy.cacheFileName(sub)), text)) {
                    prefs.setSubUpdated(sub.id, nowMs); prefs.setSubError(sub.id, null); changed = true
                } else prefs.setSubError(sub.id, "Could not save the downloaded list")
            } else prefs.setSubError(sub.id, err ?: "Download failed")
        }
        if (changed) reload()
        return changed
    }

    // ---- custom list (user-supplied https URL) ----

    /** Downloads, validates and atomically installs a custom list. Blocking. Returns null on success, else an error message. */
    fun updateFromUrl(url: String): String? {
        (FilterListValidator.validateUrl(url) as? FilterListValidator.Verdict.Rejected)?.let { lastUpdateError = it.reason; return it.reason }
        val (text, err) = fetch(url)
        val result = when {
            text == null -> err ?: "Download failed"
            install(File(ctx.filesDir, CUSTOM_FILE), text) -> null
            else -> "Could not save list"
        }
        lastUpdateError = result
        if (result == null) reload()
        return result
    }

    /** Downloads and validates. Returns (text, null) on success or (null, reason). Blocking. */
    private fun fetch(url: String): Pair<String?, String?> {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
                if (c.responseCode !in 200..299) return null to "Server responded ${c.responseCode}"
                if (c.url.protocol != "https") return null to "Redirected to a non-https URL"
                val buf = c.inputStream.use { it.readNBytesCapped(FilterListValidator.MAX_BYTES + 1) }
                if (buf.size > FilterListValidator.MAX_BYTES) return null to "List too large"
                val text = String(buf, Charsets.UTF_8)
                (FilterListValidator.validateContent(text, buf.size) as? FilterListValidator.Verdict.Rejected)?.let { return null to it.reason }
                text to null
            } finally { c.disconnect() }
        } catch (e: Exception) {
            null to (e.message ?: "Download failed")
        }
    }

    /** Writes via a temp file and renames, so a crash mid-write never leaves a half-written list. */
    private fun install(target: File, text: String): Boolean = runCatching {
        val tmp = File(target.parentFile, "${target.name}.tmp"); tmp.writeText(text)
        tmp.renameTo(target) || run { tmp.delete(); false }
    }.getOrDefault(false)

    private fun java.io.InputStream.readNBytesCapped(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192); var total = 0
        while (total < max) { val n = read(b, 0, minOf(b.size, max - total)); if (n < 0) break; out.write(b, 0, n); total += n }
        return out.toByteArray()
    }

    private fun assetText(name: String): String? = runCatching { ctx.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    fun parseStats(): ParseStats = engine.stats
    fun ruleCount() = engine.stats.blockRules + engine.stats.exceptionRules

    fun isWhitelisted(pageHost: String?): Boolean = prefs.isWhitelisted(pageHost)

    /** Pure decision for a sub-resource; does not touch statistics. Main-frame requests are handled by [decideNavigation]. */
    fun decide(req: FilterRequest, isMainFrame: Boolean): FilterDecision {
        if (!prefs.adBlockEnabled || isMainFrame) return FilterDecision.ALLOW
        if (isWhitelisted(req.pageHost)) return FilterDecision.ALLOW
        return applyToggles(engine.decide(req))
    }

    /**
     * Decision for a top-level navigation, pop-up target or redirect hop (see [FilterEngine.decideNavigation]).
     * Honours the global switch, the per-site allow-list and the tracker toggle.
     */
    fun decideNavigation(url: String, pageHost: String?): FilterDecision {
        if (!prefs.adBlockEnabled) return FilterDecision.ALLOW
        if (isWhitelisted(pageHost)) return FilterDecision.ALLOW
        return applyToggles(engine.decideNavigation(url, pageHost))
    }

    /** Tracker protection can be switched off independently: then tracker rules are ignored, ad rules still apply. */
    private fun applyToggles(d: FilterDecision): FilterDecision =
        if (d.blocked && d.category == FilterCategory.TRACKERS && !prefs.trackerProtection) FilterDecision.ALLOW else d

    /**
     * JavaScript for a page of [pageHost]: element hiding (if cosmetic filtering is on) and the YouTube handler (if YouTube
     * protection is on and the page is YouTube). Null when blocking is off, the site is allow-listed, or nothing applies.
     * Idempotent: safe to run on page start and again on page finish.
     */
    fun pageScript(pageHost: String?): String? {
        if (!prefs.adBlockEnabled || isWhitelisted(pageHost)) return null
        val parts = ArrayList<String>()
        if (prefs.cosmeticFiltering) CosmeticRules.pageScript(cosmetic, pageHost)?.let { parts += it }
        if (prefs.youtubeProtection && YouTubeScript.appliesTo(pageHost)) parts += YouTubeScript.SOURCE
        return if (parts.isEmpty()) null else parts.joinToString("\n")
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

    private fun background(name: String, block: () -> Unit) {
        Thread({ runCatching(block) }, name).apply { isDaemon = true; start() }
    }

    companion object {
        private const val CUSTOM_FILE = "custom_filter.txt"
        /** After a failed download, wait this long before trying the same subscription again. */
        const val RETRY_AFTER_FAILURE_MS: Long = 12L * 60 * 60 * 1000
    }
}
