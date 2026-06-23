package org.fenixuz.utils

import android.content.Context
import com.google.gson.Gson
import org.telegram.messenger.ApplicationLoader

/**
 * Per-chat "one-time voice / round video" preference.
 *
 * When enabled for a dialog, every NEW voice or round-video recording in that chat
 * defaults to Telegram's native view-once mode (ttl = 0x7FFFFFFF), so the recipient
 * can listen/watch it only once. The user can still override a single recording with
 * the native "once" button in the record UI — this only changes the DEFAULT.
 *
 * Senior / perf / ANR notes — improves on pro's `OneTimeVoiceOrAudioCache`, which:
 *   (a) re-parsed the WHOLE Gson list on every `getConfigById` call (hit on the
 *       record-start path), and (b) used a blocking `commit()` (disk I/O on the UI
 *       thread → ANR risk on slow storage).
 * Here instead:
 *   - State is an in-memory [HashSet] of enabled dialog ids, loaded ONCE lazily.
 *     [isEnabled] is an O(1) set lookup on the hot record-start path — no JSON parsing.
 *   - Writes are async ([android.content.SharedPreferences.Editor.apply]) — never block the UI thread.
 *   - Persisted compactly as a Gson `long[]` in the device-only "db" prefs (survives
 *     logout), matching the existing fenixuz storage convention.
 *
 * All access happens on the UI thread (record-start hook + 3-dot menu click), so the
 * lock-free [isEnabled] read is safe; writes are still guarded for defensive correctness.
 */
object OneTimeVoice {

    private const val KEY = "one_time_media_ids"

    private val prefs by lazy {
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)
    }
    private val gson = Gson()

    @Volatile
    private var ids: HashSet<Long>? = null

    private fun ensureLoaded(): HashSet<Long> {
        var local = ids
        if (local == null) {
            synchronized(this) {
                local = ids
                if (local == null) {
                    local = load()
                    ids = local
                }
            }
        }
        return local!!
    }

    private fun load(): HashSet<Long> {
        val str = prefs.getString(KEY, null)
        if (str.isNullOrEmpty()) return HashSet()
        return try {
            val arr = gson.fromJson(str, LongArray::class.java)
            if (arr == null) HashSet() else HashSet<Long>(arr.size * 2).apply { arr.forEach { add(it) } }
        } catch (e: Exception) {
            HashSet()
        }
    }

    /** O(1) lookup on the record-start hot path. */
    fun isEnabled(dialogId: Long): Boolean = ensureLoaded().contains(dialogId)

    @Synchronized
    fun setEnabled(dialogId: Long, enabled: Boolean) {
        val set = ensureLoaded()
        val changed = if (enabled) set.add(dialogId) else set.remove(dialogId)
        if (changed) {
            prefs.edit().putString(KEY, gson.toJson(set.toLongArray())).apply()
        }
    }

    /** Flips the per-chat setting and returns the new state. */
    fun toggle(dialogId: Long): Boolean {
        val newState = !isEnabled(dialogId)
        setEnabled(dialogId, newState)
        return newState
    }
}
