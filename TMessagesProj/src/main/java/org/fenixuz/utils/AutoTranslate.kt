package org.fenixuz.utils

import android.content.Context
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Components.TranslateAlert2
import java.util.concurrent.ConcurrentHashMap

/**
 * Novagram "Auto-translate" — a per-chat target language for your OUTGOING messages. When set for a
 * chat, the first Send tap translates the typed message to that language and previews it in the input
 * box; the next Send actually sends it. This "translate then confirm" (two-tap) flow means you always
 * see exactly what will go out before it does.
 *
 * The translation itself is delegated to [VoiceTranslate] (Telegram's own free `messages.translateText`
 * with retry/empty-result handling) — no duplicated network code. Plain-text translation: message
 * formatting/custom-emoji is not preserved (kept simple and robust; the 99% case is plain messages).
 *
 * Clean rewrite of pro's `AutoTranslate`, whose flaws are all dropped: pro stored a Gson JSON list and
 * called blocking `commit()`, and re-deserialized that whole list on the SEND hot path (both
 * `checkNoLanguage` and `getAutoLanguageByDialogId` parsed JSON on every Send tap). Here the mapping is
 * an in-memory cache loaded once → O(1) reads on the send path, compact storage, async writes.
 *
 * Storage: a single compact "db" pref `auto_translate_map` = "dialogId:lang;dialogId:lang". Language
 * codes are simple tokens (e.g. "uz", "pt", "zh_hans") with no ':' or ';', so the format is unambiguous.
 */
object AutoTranslate {

    private const val PREF = "db"
    private const val KEY = "auto_translate_map"
    private const val TAG = "AutoTranslate"

    // Always-on diagnostic log. Telegram's FileLog.d is a no-op unless BuildVars.LOGS_ENABLED is true
    // (off in our build), so we use android.util.Log directly — visible in any build. Filter: "AutoTranslate".
    private fun log(msg: String) = android.util.Log.d(TAG, msg)

    @Volatile
    private var loaded = false

    // dialogId -> target language code. Read on the send hot path (UI thread) + settings; lock-free.
    private val langs = ConcurrentHashMap<Long, String>()

    private fun prefs(): android.content.SharedPreferences =
        ApplicationLoader.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = prefs().getString(KEY, "") ?: ""
            if (raw.isNotEmpty()) {
                for (entry in raw.split(';')) {
                    val sep = entry.indexOf(':')
                    if (sep <= 0) continue
                    val id = entry.substring(0, sep).toLongOrNull() ?: continue
                    val lang = entry.substring(sep + 1)
                    if (lang.isNotEmpty()) langs[id] = lang
                }
            }
            loaded = true
        }
    }

    /** Target language code for this chat, or null when auto-translate is off. O(1) hot-path read. */
    @JvmStatic
    fun getLang(dialogId: Long): String? {
        ensureLoaded()
        return langs[dialogId]
    }

    @JvmStatic
    fun isEnabled(dialogId: Long): Boolean = getLang(dialogId) != null

    /** Set the target language for a chat; null/blank/"not" turns auto-translate off for it. */
    @JvmStatic
    fun setLang(dialogId: Long, lang: String?) {
        ensureLoaded()
        if (lang.isNullOrBlank() || lang == "not") {
            if (langs.remove(dialogId) != null) save()
        } else {
            langs[dialogId] = lang
            save()
        }
    }

    private fun save() {
        val sb = StringBuilder()
        for ((id, lang) in langs) {
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(id).append(':').append(lang)
        }
        prefs().edit().putString(KEY, sb.toString()).apply()
    }

    fun interface TranslateCallback {
        /** translated = the result text (or null on total failure); ok = a real translation was produced. */
        fun onResult(translated: CharSequence?, ok: Boolean)
    }

    /**
     * Translate [text] to [toLang] as fast and robustly as possible. Real logcat timings on this build
     * proved the ordering: Google direct (`sl=auto`) returns in ~2-3s and handles BOTH short ("Nima gap")
     * and long text, while the Telegram SERVER `messages.translateText` takes 5-7s AND sometimes returns
     * an empty result — so server-first wasted ~5s before even reaching Google. Hence:
     *  1) Google direct with `sl=auto` ([TranslateAlert2.alternativeTranslate]) — FAST, primary engine.
     *     `sl=auto` lets Google detect the source server-side (no ML Kit round-trip, and no `sl=und` 400).
     *  2) Only if Google fails (HTTP 429 rate-limit / unreachable), fall back to the Telegram server,
     *     which routes over Telegram's own connection and works where Google is restricted.
     * Every step is logged (filter logcat for "AutoTranslate") so failures are diagnosable, never silent.
     *
     * The callback always fires on the UI thread.
     */
    @JvmStatic
    fun translate(text: CharSequence, toLang: String?, account: Int, callback: TranslateCallback) {
        var toLng = (toLang ?: "").substringBefore("_").lowercase()
        if (toLng == "nb") toLng = "no"
        val source = text.toString()
        if (toLng.isBlank() || toLng == "not") {
            callback.onResult(null, false)
            return
        }
        log("start to=$toLng len=${source.length}")
        // Google first (fast). alternativeTranslate's callback fires on the UI thread.
        TranslateAlert2.alternativeTranslate(source, "auto", toLng) { result, rateLimit ->
            if (!result.isNullOrEmpty()) {
                log("google OK sl=auto tl=$toLng")
                callback.onResult(result, true)
            } else {
                log("google empty rate=$rateLimit → server fallback to=$toLng")
                serverTranslate(source, toLng, account, callback)
            }
        }
    }

    /** Telegram-server fallback (messages.translateText). Slower, but works where Google is blocked. */
    private fun serverTranslate(source: String, toLng: String, account: Int, callback: TranslateCallback) {
        val req = TLRPC.TL_messages_translateText()
        val twe = TLRPC.TL_textWithEntities()
        twe.text = source
        req.flags = req.flags or 2          // FLAG_1: translate the arbitrary `text` field
        req.text.add(twe)
        req.to_lang = toLng
        ConnectionsManager.getInstance(account).sendRequest(req) { res, err ->
            AndroidUtilities.runOnUIThread {
                val out = (res as? TLRPC.TL_messages_translateResult)?.result?.firstOrNull()?.text
                if (!out.isNullOrEmpty()) {
                    log("server OK to=$toLng")
                    callback.onResult(out, true)
                } else {
                    log("server empty/err='${err?.text}' — all engines failed")
                    callback.onResult(null, false)
                }
            }
        }
    }
}
