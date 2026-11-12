package com.nova.browser.core

/**
 * Remote filter subscriptions (EasyList, EasyPrivacy). Pure Kotlin: no I/O here, so the rules are unit tested.
 * The AdBlockEngine wrapper does the download, validation and atomic install.
 */
object SubscriptionPolicy {
    data class Subscription(val id: String, val title: String, val url: String)

    val EASYLIST = Subscription("easylist", "EasyList", "https://easylist.to/easylist/easylist.txt")
    val EASYPRIVACY = Subscription("easyprivacy", "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt")
    val ALL: List<Subscription> = listOf(EASYLIST, EASYPRIVACY)

    /** Lists are refreshed at most once per week, never on every launch. */
    const val UPDATE_INTERVAL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** Due when never updated, when the interval has passed, or when the clock went backwards (lastUpdated in the future). */
    fun isUpdateDue(lastUpdatedMs: Long, nowMs: Long, intervalMs: Long = UPDATE_INTERVAL_MS): Boolean {
        if (lastUpdatedMs <= 0L) return true
        if (nowMs < lastUpdatedMs) return true
        return nowMs - lastUpdatedMs >= intervalMs
    }

    /** Only HTTPS subscriptions are ever downloaded. */
    fun isAllowedSubscriptionUrl(url: String): Boolean = url.startsWith("https://") && url.length <= 2048

    /** Cache file name for a subscription. The id is from the fixed list above, so it cannot contain path separators. */
    fun cacheFileName(sub: Subscription): String = "subs_${sub.id}.txt"
}
