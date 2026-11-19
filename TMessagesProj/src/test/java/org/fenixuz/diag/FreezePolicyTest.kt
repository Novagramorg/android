package org.fenixuz.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezePolicyTest {

    private val start = 1_000_000L          // process start (uptime ms)
    private val later = start + 60_000L      // well past the start-up grace
    private val gap = 5 * 60_000L

    @Test
    fun `a freeze is two seconds of main-thread silence`() {
        val p = FreezePolicy()
        assertFalse(p.isFreeze(later, later + 1_999))
        assertTrue(p.isFreeze(later, later + 2_000))
    }

    @Test
    fun `freezes during cold start are not reported`() {
        val p = FreezePolicy()
        assertFalse(p.tryReport(start + 16_000, start + 14_000, start, false))
        assertEquals(0, p.reportsMade())
        assertTrue(p.tryReport(start + 18_000, start + 15_000, start, false))
    }

    @Test
    fun `nothing is reported with a debugger attached`() {
        val p = FreezePolicy()
        assertFalse(p.tryReport(later + 2_000, later, start, true))
        assertEquals(0, p.reportsMade())
    }

    @Test
    fun `reports are at least five minutes apart`() {
        val p = FreezePolicy()
        assertTrue(p.tryReport(later + 2_000, later, start, false))
        assertFalse(p.tryReport(later + 2_000 + gap - 1, later + gap - 2_001, start, false))
        assertTrue(p.tryReport(later + 2_000 + gap, later + gap - 2_000, start, false))
        assertEquals(2, p.reportsMade())
    }

    @Test
    fun `at most three reports per process`() {
        val p = FreezePolicy()
        var now = later
        repeat(3) {
            assertTrue(p.tryReport(now + 2_000, now, start, false))
            now += gap
        }
        assertFalse(p.tryReport(now + 2_000, now, start, false))
        assertFalse(p.tryReport(now + 10 * gap, now + 9 * gap, start, false))
        assertEquals(3, p.reportsMade())
    }

    @Test
    fun `a refused freeze does not use up the budget`() {
        val p = FreezePolicy()
        assertFalse(p.tryReport(later + 2_000, later, start, true))      // debugger
        assertFalse(p.tryReport(start + 3_000, start + 1_000, start, false)) // cold start
        assertTrue(p.tryReport(later + 2_000, later, start, false))
        assertEquals(1, p.reportsMade())
    }
}
