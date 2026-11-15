package org.fenixuz.review

/**
 * Novagram: WHEN the Google Play rating card may be requested. Pure logic, no Android, so it is unit-tested
 * ([ReviewRulesTest]); [AppReview] feeds it the clock, the install time, the version code and a preferences-backed
 * [Store], and talks to Play.
 *
 * The rules are the same as the iOS/macOS Novagram apps', so the three platforms can be compared:
 *  - a feature counts as "used" once per successful use ([recordUse]); a request becomes possible once ANY feature has
 *    been used [USES_TO_ASK] times;
 *  - never within [MIN_DAYS_SINCE_INSTALL] of the install, never within [MIN_DAYS_BETWEEN] of the previous request, at
 *    most once per app version, and at most [MAX_LIFETIME] times ever: Play stops showing the card to a user who has
 *    rated, but the app is never told, so the lifetime cap is the only thing that stops asking the ones who never will.
 *
 * Use counts are never reset: after a request the next one is simply gated by the 14 days / new version / lifetime
 * rules, and fires at the next use of a feature over the threshold.
 */
class ReviewRules(
    private val store: Store,
    private val now: () -> Long,
    private val installTimeMs: Long,
    private val versionCode: Int,
) {

    interface Store {
        fun getInt(key: String, def: Int): Int
        fun getLong(key: String, def: Long): Long
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
    }

    /** Why [shouldAsk] said no; for logs and tests. */
    enum class Block { NONE, TOO_FEW_USES, FRESH_INSTALL, TOO_SOON, SAME_VERSION, LIFETIME_CAP }

    /** Counts one successful use of [featureId]. Returns the new count. */
    fun recordUse(featureId: String): Int {
        val key = KEY_USES + featureId
        val count = store.getInt(key, 0)
        if (count >= COUNT_CAP) return count
        store.putInt(key, count + 1)
        return count + 1
    }

    fun uses(featureId: String): Int = store.getInt(KEY_USES + featureId, 0)

    fun requestCount(): Int = store.getInt(KEY_REQUEST_COUNT, 0)

    /** Whether a request may be made now on behalf of [featureId]; [block] tells why not. */
    fun shouldAsk(featureId: String): Boolean = block(featureId) == Block.NONE

    fun block(featureId: String): Block {
        if (uses(featureId) < USES_TO_ASK) return Block.TOO_FEW_USES
        if (requestCount() >= MAX_LIFETIME) return Block.LIFETIME_CAP
        if (store.getInt(KEY_LAST_VERSION, 0) == versionCode) return Block.SAME_VERSION
        val t = now()
        if (t - installTimeMs < MIN_DAYS_SINCE_INSTALL * DAY_MS) return Block.FRESH_INSTALL
        val last = store.getLong(KEY_LAST_REQUEST, 0L)
        if (last != 0L && t - last < MIN_DAYS_BETWEEN * DAY_MS) return Block.TOO_SOON
        return Block.NONE
    }

    /** A request was handed to Play (whether or not Play then showed the card: the app cannot know). */
    fun markAsked() {
        store.putLong(KEY_LAST_REQUEST, now())
        store.putInt(KEY_LAST_VERSION, versionCode)
        store.putInt(KEY_REQUEST_COUNT, requestCount() + 1)
    }

    companion object {
        const val USES_TO_ASK = 5
        const val MIN_DAYS_SINCE_INSTALL = 3
        const val MIN_DAYS_BETWEEN = 14
        const val MAX_LIFETIME = 3
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Counts stop growing here; nothing reads them past [USES_TO_ASK]. */
        private const val COUNT_CAP = 1_000_000

        const val KEY_USES = "uses_"
        const val KEY_LAST_REQUEST = "last_request"
        const val KEY_LAST_VERSION = "last_version"
        const val KEY_REQUEST_COUNT = "request_count"
    }
}
