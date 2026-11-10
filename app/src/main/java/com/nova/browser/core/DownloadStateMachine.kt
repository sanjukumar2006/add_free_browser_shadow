package com.nova.browser.core

enum class DlState { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/** Legal download state transitions and retry accounting. Terminal states other than FAILED never change. */
object DownloadStateMachine {
    const val MAX_RETRIES = 3
    private val allowed: Map<DlState, Set<DlState>> = mapOf(
        DlState.QUEUED to setOf(DlState.RUNNING, DlState.CANCELLED, DlState.FAILED),
        DlState.RUNNING to setOf(DlState.PAUSED, DlState.COMPLETED, DlState.FAILED, DlState.CANCELLED),
        DlState.PAUSED to setOf(DlState.RUNNING, DlState.CANCELLED, DlState.FAILED),
        DlState.FAILED to setOf(DlState.QUEUED, DlState.CANCELLED),
        DlState.COMPLETED to emptySet(),
        DlState.CANCELLED to emptySet(),
    )

    fun canTransition(from: DlState, to: DlState) = to in allowed.getValue(from)
    fun canRetry(state: DlState, attempts: Int) = state == DlState.FAILED && attempts < MAX_RETRIES

    /** Maps android.app.DownloadManager.STATUS_* ints (1 pending, 2 running, 4 paused, 8 successful, 16 failed). */
    fun fromSystemStatus(s: Int): DlState = when (s) { 1 -> DlState.QUEUED; 2 -> DlState.RUNNING; 4 -> DlState.PAUSED; 8 -> DlState.COMPLETED; 16 -> DlState.FAILED; else -> DlState.FAILED }
}
