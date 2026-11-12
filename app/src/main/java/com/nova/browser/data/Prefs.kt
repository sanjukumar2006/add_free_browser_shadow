package com.nova.browser.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nova.browser.core.SessionCodec
import com.nova.browser.core.UrlPolicy

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Site(val title: String, val url: String)

/** Settings + bookmarks + history. Observable values use Compose state so the UI updates instantly. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("nova", Context.MODE_PRIVATE)

    /** Encrypted store for sensitive values (VPN private key, API token). */
    val secure: SharedPreferences = openSecure(ctx)

    private fun openSecure(ctx: Context): SharedPreferences {
        fun create() = EncryptedSharedPreferences.create(
            ctx, "nova_secure",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        // A corrupted Keystore entry (restore, OS bug, factory-reset key) throws on every start. Recover by discarding the
        // unreadable file: the user simply has to sign in to the VPN again and a new device key is generated.
        return try { create() } catch (e: Exception) {
            ctx.deleteSharedPreferences("nova_secure")
            runCatching { java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("_androidx_security_master_key_") }
            create()
        }
    }

    var theme by mutableStateOf(runCatching { ThemeMode.valueOf(sp.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM))
    var searchEngineId by mutableStateOf(sp.getString("se", "duckduckgo")!!)
    var privateSearchEngineId by mutableStateOf(sp.getString("pse", "duckduckgo")!!)
    var adBlockEnabled by mutableStateOf(sp.getBoolean("adblock", true))
    /** YouTube: network rules for ad endpoints plus the in-page ad handler. Independent of the master switch's other parts. */
    var youtubeProtection by mutableStateOf(sp.getBoolean("yt_protect", true))
    /** Tracker rules (analytics, tracking pixels). Ad rules are not affected by this switch. */
    var trackerProtection by mutableStateOf(sp.getBoolean("trk_protect", true))
    /** Element hiding (##) rules: removes ad boxes from pages. */
    var cosmeticFiltering by mutableStateOf(sp.getBoolean("cosmetic", true))
    var httpsOnly by mutableStateOf(sp.getBoolean("https", true))
    var desktopMode by mutableStateOf(sp.getBoolean("desktop", false))
    var fontScale by mutableStateOf(sp.getInt("font", 100))
    var autoClear by mutableStateOf(sp.getBoolean("autoclear", false))
    var blockThirdPartyCookies by mutableStateOf(sp.getBoolean("tpc", true))
    var homePage by mutableStateOf(sp.getString("home", "")!!)
    var secureDns by mutableStateOf(sp.getString("dns", "system")!!)

    fun save() = sp.edit()
        .putString("theme", theme.name).putString("se", searchEngineId).putString("pse", privateSearchEngineId)
        .putBoolean("adblock", adBlockEnabled).putBoolean("yt_protect", youtubeProtection)
        .putBoolean("trk_protect", trackerProtection).putBoolean("cosmetic", cosmeticFiltering).putBoolean("https", httpsOnly).putBoolean("desktop", desktopMode)
        .putInt("font", fontScale).putBoolean("autoclear", autoClear).putBoolean("tpc", blockThirdPartyCookies)
        .putString("home", homePage).putString("dns", secureDns).apply()

    // ---- filter subscriptions (EasyList / EasyPrivacy): per-list state, persisted immediately ----
    fun subEnabled(id: String): Boolean = sp.getBoolean("sub_on_$id", true)
    fun setSubEnabled(id: String, on: Boolean) = sp.edit().putBoolean("sub_on_$id", on).apply()
    fun subUpdated(id: String): Long = sp.getLong("sub_ts_$id", 0L)
    fun setSubUpdated(id: String, ms: Long) = sp.edit().putLong("sub_ts_$id", ms).apply()
    fun subAttempted(id: String): Long = sp.getLong("sub_try_$id", 0L)
    fun setSubAttempted(id: String, ms: Long) = sp.edit().putLong("sub_try_$id", ms).apply()
    fun subError(id: String): String? = sp.getString("sub_err_$id", null)
    fun setSubError(id: String, msg: String?) = sp.edit().apply { if (msg == null) remove("sub_err_$id") else putString("sub_err_$id", msg) }.apply()

    // ---- stats ----
    var adsBlocked by mutableStateOf(sp.getInt("ads", 0))
    var trackersBlocked by mutableStateOf(sp.getInt("trk", 0))
    var bytesSaved by mutableStateOf(sp.getLong("saved", 0L))
    fun saveStats() = sp.edit().putInt("ads", adsBlocked).putInt("trk", trackersBlocked).putLong("saved", bytesSaved).apply()

    // ---- whitelist ----
    @Volatile private var wlCache: Set<String> = sp.getStringSet("wl", emptySet())!!.toSet()
    val whitelist: MutableSet<String> get() = wlCache.toMutableSet()
    fun setWhitelist(s: Set<String>) { wlCache = s.toSet(); sp.edit().putStringSet("wl", s).apply() }
    /** Hot path (called per request from the WebView IO thread): reads only an in-memory snapshot. */
    fun isWhitelisted(host: String?): Boolean = host != null && wlCache.any { host == it || host.endsWith(".$it") }

    // ---- custom search engines  "name|template" ----
    fun customEngines(): List<String> = sp.getStringSet("custom_se", emptySet())!!.toList()
    /** Returns false (and stores nothing) when the template is not a valid https search URL with exactly one %s. */
    fun addCustomEngine(name: String, template: String): Boolean {
        if (name.isBlank() || name.contains('|') || name.length > 40 || !UrlPolicy.isValidSearchTemplate(template)) return false
        sp.edit().putStringSet("custom_se", customEngines().toSet() + "$name|$template").apply(); return true
    }

    // ---- bookmarks / history (simple tab-separated lines) ----
    var bookmarks by mutableStateOf(readSites("bm"))
    var history by mutableStateOf(readSites("hist"))
    var visits by mutableStateOf(sp.getString("visits", "")!!.lines().filter { it.isNotBlank() }
        .associate { it.substringBefore('\t') to it.substringAfter('\t').toIntOrNull().let { n -> n ?: 1 } })

    private fun readSites(k: String): List<Site> = sp.getString(k, "")!!.lines().filter { it.contains('\t') }
        .map { Site(it.substringBefore('\t'), it.substringAfter('\t')) }
        .filter { UrlPolicy.isAllowedNavigation(it.url) } // never surface a tampered/dangerous URL to the UI

    private fun writeSites(k: String, l: List<Site>) =
        sp.edit().putString(k, l.filter { UrlPolicy.isAllowedNavigation(it.url) }
            .joinToString("\n") { it.title.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') + "\t" + it.url }).apply()

    fun toggleBookmark(s: Site) {
        bookmarks = if (bookmarks.any { it.url == s.url }) bookmarks.filterNot { it.url == s.url } else listOf(s) + bookmarks
        writeSites("bm", bookmarks)
    }

    /** Callers must only pass NORMAL-tab visits; private visits are filtered by SessionCodec.shouldRecordHistory at the call site. */
    fun addHistory(s: Site) {
        if (!SessionCodec.shouldRecordHistory(false, s.url)) return
        history = (listOf(s) + history.filterNot { it.url == s.url }).take(500)
        writeSites("hist", history)
        visits = visits + (s.url to ((visits[s.url] ?: 0) + 1))
        sp.edit().putString("visits", visits.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    /** Deletes one history entry (and its visit counter) so it also disappears from "frequent" sites. */
    fun removeHistory(url: String) {
        history = history.filterNot { it.url == url }
        visits = visits - url
        writeSites("hist", history)
        sp.edit().putString("visits", visits.entries.joinToString("\n") { "${it.key}\t${it.value}" }).apply()
    }

    fun clearHistory() { history = emptyList(); visits = emptyMap(); sp.edit().remove("hist").remove("visits").apply() }

    fun frequent(): List<Site> = history.sortedByDescending { visits[it.url] ?: 0 }.take(8)

    // ---- session restore ----
    fun saveSessionRaw(encoded: String) = sp.edit().putString("session_v2", encoded).remove("session").apply()
    fun loadSessionRaw(): String = sp.getString("session_v2", "")!!
}
