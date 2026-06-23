package org.fenixuz.utils

import android.content.Context
import org.telegram.messenger.ApplicationLoader

/**
 * Novagram: opt-in toggle that adds a "Save story" item to the story viewer's "⋯" menu, letting you
 * download ANYONE's story to your gallery — bypassing both the Premium gate and the author's
 * save-restriction (Telegram only offers the real save to Premium users, and only when the author
 * allowed it). The actual save reuses the native [org.telegram.ui.Stories.PeerStoriesView]'s own
 * `saveToGallery()` (FileLoader path → MediaController.saveFile), so there is no duplicated download code.
 *
 * Device-only ("db" pref, key `story_download`, matching pro's key), no Firebase. In-memory `@Volatile`
 * cached, loaded once (double-checked) → [isEnabled] is an O(1) read on the menu-build path; writes async.
 */
object StoryDownload {

    private const val PREF = "db"
    private const val KEY = "story_download"

    @Volatile private var loaded = false
    @Volatile private var enabled = false

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

    @Synchronized
    fun toggle(): Boolean {
        ensureLoaded()
        enabled = !enabled
        prefs().edit().putBoolean(KEY, enabled).apply()
        return enabled
    }
}
