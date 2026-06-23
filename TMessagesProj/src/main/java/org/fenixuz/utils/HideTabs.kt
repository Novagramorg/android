package org.fenixuz.utils

import android.content.Context
import org.telegram.messenger.ApplicationLoader

/**
 * Novagram: persistent toggle that HIDES the folder/filter tabs row (FilterTabsView) in the chats list.
 *
 * Device-only ("db" prefs), no Firebase. Senior-grade for the hot path:
 *  - in-memory @Volatile boolean loaded ONCE (lazy, double-checked), so [isEnabled] is an O(1) field read.
 *    It's queried from DialogsActivity.updateFilterTabs (runs on every filter change / tab rebuild), so it
 *    must never touch disk/JSON per call — no jank, no ANR.
 *  - writes use apply() (async) — never commit().
 *  - a one-shot [dirty] flag lets DialogsActivity re-apply the change exactly once on its next resume (the
 *    toggle lives on a separate settings screen, and DialogsActivity is paused while it's open, so a live
 *    NotificationCenter post wouldn't reliably animate the tabs out). No observers, no polling.
 *
 * Hook: DialogsActivity.updateFilterTabs gates "show tabs" on `filters.size() > 1 && !HideTabs.isEnabled()`,
 * reusing Telegram's own hide path (which also drops the 50dp list padding), so there are zero layout
 * side-effects to manage here.
 */
object HideTabs {

    private const val PREF = "db"
    private const val KEY = "hide_tabs"

    @Volatile private var loaded = false
    @Volatile private var enabled = false
    @Volatile private var dirty = false

    private fun prefs() =
        ApplicationLoader.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            enabled = prefs().getBoolean(KEY, false)
            loaded = true
        }
    }

    fun isEnabled(): Boolean {
        ensureLoaded()
        return enabled
    }

    /** Flip the toggle, persist async, and mark dirty so DialogsActivity refreshes on resume. */
    @Synchronized
    fun toggle(): Boolean {
        ensureLoaded()
        enabled = !enabled
        prefs().edit().putBoolean(KEY, enabled).apply()
        dirty = true
        return enabled
    }

    /** Returns true at most once after each [toggle]; DialogsActivity uses it to re-apply on resume. */
    @Synchronized
    fun consumeDirty(): Boolean {
        val d = dirty
        dirty = false
        return d
    }
}
