package org.fenixuz.callrec

import android.content.Context
import org.telegram.messenger.ApplicationLoader

/**
 * Novagram: the call recording switches. Same meaning as the iOS/macOS apps' (`CallRecordingSettings` in the shared
 * SDK), in our own "db" preferences like every other Novagram toggle. All default off: recording is opt-in.
 */
object CallRecordingSettings {
    private const val PREF = "db"
    private const val KEY_ENABLED = "call_rec_enabled"
    private const val KEY_AUTO = "call_rec_auto"
    private const val KEY_WARNED = "call_rec_warned"
    private const val KEY_QUALITY = "call_rec_quality"
    private const val KEY_KEEP_DAYS = "call_rec_keep_days"

    /** The retention choices offered: 0 is "keep forever". */
    val KEEP_DAYS_OPTIONS = intArrayOf(0, 7, 30, 90)

    /**
     * What a recording keeps of the call's video. Same keys as the iOS/macOS apps. The saver canvas is 480x848 on
     * purpose: Android's "minimum quality" shaper raises any VBR encode above 320x240 to about 2.25 bits per pixel,
     * which at 720x1280 turns 1.25 Mbit/s into 2.5 and makes "saver" the size of "high"; at 480x848 the floor is
     * under the rate asked for, so the file really is half.
     */
    enum class Quality(val key: String, val width: Int, val height: Int, val framesPerSecond: Int, val bitRate: Int) {
        /** Only the voices: the file is audio even in a video call. */
        AUDIO_ONLY("audio", 0, 0, 0, 0),
        /** 480x848 at 15 frames a second and half the bit rate: about half the battery and space of HIGH. */
        BATTERY_SAVER("saver", 480, 848, 15, 1_250_000),
        HIGH("high", 720, 1280, 30, 2_500_000);

        val recordsVideo: Boolean get() = this != AUDIO_ONLY

        companion object {
            val DEFAULT = BATTERY_SAVER
            fun of(key: String?): Quality = values().firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    private val prefs get() = ApplicationLoader.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** "Call recording": the Record button shows on 1:1 calls. */
    @JvmStatic
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    /** "Record every call automatically". Only while [isEnabled]. */
    @JvmStatic
    var isAutoEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO, value).apply() }

    /** The video quality of the next recording; a running one keeps the quality it started with. */
    @JvmStatic
    var quality: Quality
        get() = Quality.of(prefs.getString(KEY_QUALITY, null))
        set(value) { prefs.edit().putString(KEY_QUALITY, value.key).apply() }

    /**
     * Recordings older than this many days are deleted (0 = never). The files live in the app's private storage, where
     * Telegram's own cache cleaner does not see them, so without this an automatic recorder fills the phone unnoticed.
     */
    @JvmStatic
    var keepDays: Int
        get() = prefs.getInt(KEY_KEEP_DAYS, 0)
        set(value) { prefs.edit().putInt(KEY_KEEP_DAYS, value).apply() }

    /** The first-recording consent alert was accepted once. */
    @JvmStatic
    var wasWarned: Boolean
        get() = prefs.getBoolean(KEY_WARNED, false)
        set(value) { prefs.edit().putBoolean(KEY_WARNED, value).apply() }
}
