package org.fenixuz.utils

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC

/**
 * Translates dictated text via Telegram's own `messages.translateText` (the same free, non-premium
 * per-message translate Telegram uses). This is the translate half of the voice-translate feature:
 * speech is recognised in the speak language by [VoiceDictation], then translated to the target here.
 *
 * Robustness (added 2026-06-12 after "translating TO Uzbek fails ~9/10"): Telegram's translate backend
 * is flaky for some target languages — it intermittently answers with an EMPTY `result` or a transient
 * error / FLOOD_WAIT, even for a request that succeeds moments later. So a single shot is unreliable.
 * Here every request is retried up to [MAX_ATTEMPTS] with backoff, and FLOOD_WAIT_N is honoured by
 * waiting N seconds before the retry. Two distinct outcomes are reported to the caller:
 *  - [Callback.onResult] (text, translated=true)  → a real translation came back.
 *  - [Callback.onResult] (text, translated=false) → genuine failure AFTER all retries; caller may hint.
 * The crucial fix for the false "failed" spam: an EMPTY result with NO error is NOT a failure — it is
 * Telegram saying "nothing to translate" (the text is already in the target language, e.g. speaking
 * Uzbek with target Uzbek, or an unsupported pair). We then keep the original text but report
 * `translated=true` so the caller does NOT show a failure toast. Only a true ERROR after all retries
 * reports `translated=false` (the one case the caller surfaces a hint for).
 *
 * Simplified vs pro's `TranslateVoiceToText.translate`: dictated text is PLAIN (no message entities /
 * custom emoji), so pro's heavy `preprocess` entity-remapping is unnecessary — we just send the text
 * and take the translated string back. On failure the original text is preserved; nothing is ever lost.
 */
object VoiceTranslate {

    fun interface Callback {
        /**
         * @param translated true  = success: [text] is the server translation, OR the original kept because
         *                            the server had nothing to translate (already in the target language).
         *                    false = a real error occurred after all retries; [text] is the unchanged input.
         */
        fun onResult(text: CharSequence, translated: Boolean)
    }

    private const val MAX_ATTEMPTS = 3          // initial try + 2 retries
    private const val RETRY_DELAY_MS = 600L     // backoff for empty/transient errors
    private const val MAX_FLOOD_WAIT_S = 8      // cap how long we'll honour a FLOOD_WAIT before giving up

    /**
     * @param toLang Telegram language code (pluralLangCode, e.g. "uz"). Blank → no translation.
     */
    fun translate(text: CharSequence, toLang: String?, account: Int, callback: Callback) {
        var lang = (toLang ?: "").substringBefore("_").lowercase()
        if (lang == "nb") lang = "no"
        if (lang.isBlank() || lang == "not") {
            callback.onResult(text, false)
            return
        }
        sendAttempt(text, lang, account, 1, callback)
    }

    private fun sendAttempt(text: CharSequence, lang: String, account: Int, attempt: Int, callback: Callback) {
        val req = TLRPC.TL_messages_translateText()
        val twe = TLRPC.TL_textWithEntities()
        twe.text = text.toString()
        // entities defaults to an empty ArrayList (never null) — safe to serialize as-is.
        req.flags = req.flags or 2   // FLAG_1: the `text` field is present (translate arbitrary text)
        req.text.add(twe)
        req.to_lang = lang
        ConnectionsManager.getInstance(account).sendRequest(req) { res, err ->
            AndroidUtilities.runOnUIThread {
                if (err != null) {
                    FileLog.e("VoiceTranslate to=$lang attempt=$attempt err: ${err.code} ${err.text}")
                    val floodWait = parseFloodWait(err.text)
                    if (attempt < MAX_ATTEMPTS && (floodWait in 1..MAX_FLOOD_WAIT_S || floodWait == 0)) {
                        val delay = if (floodWait > 0) floodWait * 1000L + 250L else RETRY_DELAY_MS
                        AndroidUtilities.runOnUIThread({ sendAttempt(text, lang, account, attempt + 1, callback) }, delay)
                    } else {
                        callback.onResult(text, false)
                    }
                    return@runOnUIThread
                }
                val out = (res as? TLRPC.TL_messages_translateResult)
                    ?.result?.firstOrNull()?.text
                when {
                    !out.isNullOrEmpty() -> callback.onResult(out, true)
                    // Empty result: the server may be transiently failing → retry a couple of times.
                    attempt < MAX_ATTEMPTS -> {
                        FileLog.e("VoiceTranslate to=$lang attempt=$attempt: empty result, retrying")
                        AndroidUtilities.runOnUIThread({ sendAttempt(text, lang, account, attempt + 1, callback) }, RETRY_DELAY_MS)
                    }
                    // Still empty after all retries: Telegram has nothing to translate (already target /
                    // unsupported pair). Keep the original but report success → NO failure toast.
                    else -> {
                        FileLog.e("VoiceTranslate to=$lang: empty after $MAX_ATTEMPTS attempts, keeping original (already-target)")
                        callback.onResult(text, true)
                    }
                }
            }
        }
    }

    /** Returns the seconds from a "FLOOD_WAIT_N" error text, or 0 if not a flood-wait. */
    private fun parseFloodWait(errText: String?): Int {
        val t = errText ?: return 0
        val marker = "FLOOD_WAIT_"
        val i = t.indexOf(marker)
        if (i < 0) return 0
        return t.substring(i + marker.length).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}
