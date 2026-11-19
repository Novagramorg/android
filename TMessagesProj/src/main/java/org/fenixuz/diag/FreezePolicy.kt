package org.fenixuz.diag

/**
 * The decisions of [FreezeWatchdog] that do not touch Android, kept apart so they are unit-tested (FreezePolicyTest):
 * what counts as a freeze and which freezes may be reported. All times are SystemClock.uptimeMillis() values.
 *
 * Not thread-safe: only the watchdog thread calls it.
 */
class FreezePolicy(
    /** The main thread silent this long while the app is in front = a freeze the user feels. */
    val freezeMs: Long = 2_000L,
    /** Reports per process at most, so one stuck screen cannot flood Crashlytics. */
    private val maxReports: Int = 3,
    /** Minimum time between two reports of one process. */
    private val gapMs: Long = 5 * 60_000L,
    /** Freezes that start this soon after the process started are cold start, which Play already measures. */
    private val startupGraceMs: Long = 15_000L,
) {
    private var reports = 0
    private var lastReportAt = 0L

    fun isFreeze(postedAt: Long, now: Long): Boolean = now - postedAt >= freezeMs

    /**
     * Whether a freeze that began at [freezeStartedAt] may be reported at [now] in a process that started at
     * [processStart]; when true it is counted as reported.
     */
    fun tryReport(now: Long, freezeStartedAt: Long, processStart: Long, debuggerAttached: Boolean): Boolean {
        if (debuggerAttached) return false
        if (freezeStartedAt - processStart < startupGraceMs) return false
        if (reports >= maxReports) return false
        if (reports > 0 && now - lastReportAt < gapMs) return false
        reports++
        lastReportAt = now
        return true
    }

    fun reportsMade(): Int = reports
}
