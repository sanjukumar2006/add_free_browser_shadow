package com.nova.browser.core

import org.junit.Assert.*
import org.junit.Test

class SubscriptionPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test fun neverUpdatedIsDue() = assertTrue(SubscriptionPolicy.isUpdateDue(0L, now))

    @Test fun recentlyUpdatedIsNotDue() = assertFalse(SubscriptionPolicy.isUpdateDue(now - 2 * day, now))

    @Test fun dueExactlyAtInterval() = assertTrue(SubscriptionPolicy.isUpdateDue(now - SubscriptionPolicy.UPDATE_INTERVAL_MS, now))

    @Test fun dueAfterInterval() = assertTrue(SubscriptionPolicy.isUpdateDue(now - 8 * day, now))

    @Test fun clockMovedBackwardsIsDue() = assertTrue(SubscriptionPolicy.isUpdateDue(now + day, now))

    @Test fun intervalIsWeekly() = assertEquals(7 * day, SubscriptionPolicy.UPDATE_INTERVAL_MS)

    @Test fun onlyHttpsSubscriptionsAllowed() {
        assertTrue(SubscriptionPolicy.isAllowedSubscriptionUrl("https://easylist.to/easylist/easylist.txt"))
        assertFalse(SubscriptionPolicy.isAllowedSubscriptionUrl("http://easylist.to/easylist/easylist.txt"))
        assertFalse(SubscriptionPolicy.isAllowedSubscriptionUrl("javascript:alert(1)"))
        assertFalse(SubscriptionPolicy.isAllowedSubscriptionUrl("https://a.com/" + "x".repeat(3000)))
    }

    @Test fun builtInSubscriptionsAreHttps() {
        SubscriptionPolicy.ALL.forEach { assertTrue(it.url, SubscriptionPolicy.isAllowedSubscriptionUrl(it.url)) }
    }

    @Test fun cacheFileNamesAreSafe() {
        SubscriptionPolicy.ALL.forEach {
            val n = SubscriptionPolicy.cacheFileName(it)
            assertFalse(n.contains('/')); assertFalse(n.contains('\\')); assertFalse(n.contains(".."))
        }
    }
}
