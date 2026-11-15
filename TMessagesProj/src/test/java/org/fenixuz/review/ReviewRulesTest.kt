package org.fenixuz.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewRulesTest {

    private class MemoryStore : ReviewRules.Store {
        val ints = HashMap<String, Int>()
        val longs = HashMap<String, Long>()
        override fun getInt(key: String, def: Int) = ints[key] ?: def
        override fun getLong(key: String, def: Long) = longs[key] ?: def
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun putLong(key: String, value: Long) { longs[key] = value }
    }

    private val day = ReviewRules.DAY_MS
    private val install = 1_000_000_000_000L
    private var now = install + 10 * day
    private val store = MemoryStore()

    private fun rules(version: Int = 209) = ReviewRules(store, { now }, install, version)

    private fun use(r: ReviewRules, feature: String, times: Int) = repeat(times) { r.recordUse(feature) }

    @Test
    fun `no request before five uses`() {
        val r = rules()
        use(r, "ghost_mode", 4)
        assertEquals(ReviewRules.Block.TOO_FEW_USES, r.block("ghost_mode"))
        r.recordUse("ghost_mode")
        assertTrue(r.shouldAsk("ghost_mode"))
    }

    @Test
    fun `uses are counted per feature`() {
        val r = rules()
        use(r, "ghost_mode", 3)
        use(r, "chat_lock", 5)
        assertEquals(ReviewRules.Block.TOO_FEW_USES, r.block("ghost_mode"))
        assertTrue(r.shouldAsk("chat_lock"))
    }

    @Test
    fun `not within three days of the install`() {
        now = install + 2 * day
        val r = rules()
        use(r, "ghost_mode", 5)
        assertEquals(ReviewRules.Block.FRESH_INSTALL, r.block("ghost_mode"))
        now = install + 3 * day
        assertTrue(r.shouldAsk("ghost_mode"))
    }

    @Test
    fun `fourteen days between requests and a new version`() {
        val r = rules(version = 209)
        use(r, "ghost_mode", 5)
        assertTrue(r.shouldAsk("ghost_mode"))
        r.markAsked()
        assertEquals(ReviewRules.Block.SAME_VERSION, r.block("ghost_mode"))

        // Same version, two weeks later: still blocked by the version rule.
        now += 20 * day
        assertEquals(ReviewRules.Block.SAME_VERSION, r.block("ghost_mode"))

        // New version but only 5 days after the last request: blocked by the time rule.
        val r2 = rules(version = 210)
        now = install + 10 * day + 5 * day
        assertEquals(ReviewRules.Block.TOO_SOON, r2.block("ghost_mode"))

        now = install + 10 * day + 14 * day
        assertTrue(r2.shouldAsk("ghost_mode"))
    }

    @Test
    fun `at most three requests ever`() {
        var version = 209
        val feature = "story_download"
        use(rules(version), feature, 5)
        repeat(ReviewRules.MAX_LIFETIME) {
            val r = rules(version)
            assertTrue("request ${it + 1}", r.shouldAsk(feature))
            r.markAsked()
            now += 30 * day
            version++
        }
        assertEquals(ReviewRules.Block.LIFETIME_CAP, rules(version).block(feature))
        assertEquals(ReviewRules.MAX_LIFETIME, rules(version).requestCount())
    }

    @Test
    fun `counts survive a request and keep the next one possible`() {
        val r = rules(version = 209)
        use(r, "chat_lock", 7)
        r.markAsked()
        assertEquals(7, r.uses("chat_lock"))
        now += 15 * day
        assertTrue(rules(version = 210).shouldAsk("chat_lock"))
    }

    @Test
    fun `state round-trips through the store`() {
        val r = rules()
        use(r, "ghost_mode", 5)
        r.markAsked()
        // A fresh instance over the same store sees the same state, like after a process restart.
        val again = rules()
        assertEquals(5, again.uses("ghost_mode"))
        assertEquals(1, again.requestCount())
        assertFalse(again.shouldAsk("ghost_mode"))
    }
}
