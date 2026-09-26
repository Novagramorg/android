package org.fenixuz.utils

import android.content.Context
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.NotificationCenter

/**
 * Novagram: show seconds in clock times — 12:01:45 instead of 12:01.
 *
 * Asked for by users. It costs one branch because Telegram already has both halves: every clock time in the
 * app goes through `LocaleController.getFormatterDay()` (chat bubbles, the chat list, shared files, read
 * receipts, transactions — around 35 call sites), and `getFormatterDayWithSeconds()` already exists with
 * `HH:mm:ss` / `h:mm:ss a`. So the toggle just decides which of the two that one getter hands back, and every
 * timestamp follows.
 *
 * Switching the getter rather than rebuilding a formatter is deliberate: each formatter keeps its own cached
 * instance, so there is no cache to invalidate and no window where the app holds a stale one. What does need
 * doing is repainting — views already on screen keep whatever text they were given — hence the
 * `reloadInterface` broadcast in [toggle], the same signal LocaleController fires when the language changes.
 *
 * Worth knowing before calling a missing second a bug: in the chat LIST only TODAY's rows show a clock at
 * all. `stringForMessageListDate` gives yesterday a weekday and anything older a date, and neither has a time
 * to put seconds on.
 *
 * Device-only ("db" prefs), default OFF.
 */
object TimeWithSeconds {

    private const val PREF = "db"
    private const val KEY = "time_with_seconds"

    @Volatile private var loaded = false
    @Volatile private var enabled = false

    /**
     * Reads the preference once, and is safe to call before the app is fully up.
     *
     * `getFormatterDay()` runs during startup and on background threads, so a missing application context has
     * to mean "not yet" rather than a crash: [loaded] stays false in that case and the next call tries again,
     * instead of caching a wrong answer for the life of the process.
     */
    @JvmStatic
    fun isEnabled(): Boolean {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    val ctx = ApplicationLoader.applicationContext ?: return false
                    enabled = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, false)
                    loaded = true
                }
            }
        }
        return enabled
    }

    @JvmStatic
    @Synchronized
    fun toggle(): Boolean {
        isEnabled()
        enabled = !enabled
        loaded = true
        ApplicationLoader.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        // Nothing redraws on its own -- the text already handed to a view does not change when the formatter
        // does. This is the broadcast LocaleController uses for a language switch, which is the same problem.
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface)
        return enabled
    }
}
