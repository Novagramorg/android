package org.fenixuz.ui.auto_text

import android.app.Activity
import org.fenixuz.utils.AutoTranslate
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.LocaleController
import org.telegram.messenger.TranslateController
import org.telegram.ui.ActionBar.AlertDialog

/**
 * Novagram: the per-chat auto-translate language picker, opened from the chat's 3-dot menu
 * ("Auto-translate", sitting next to "Voice to text"). Lists "Off" + every translatable language and
 * stores the choice via [AutoTranslate]. The current target language is marked with a ✓ so the on/off
 * state is visible at a glance. Kept here — not in [AutoTranslate], which is pure storage/engine — so the
 * UI stays out of the engine and the picker can be reused from anywhere.
 */
object AutoTranslatePicker {

    private const val CHECK = "  ✓"

    @JvmStatic
    fun show(activity: Activity, dialogId: Long, onChanged: Runnable?) {
        pickLanguage(activity, AutoTranslate.getLang(dialogId), true) { code ->
            AutoTranslate.setLang(dialogId, code)
            onChanged?.run()
        }
    }

    /**
     * Generic target-language picker, reused by chat auto-translate and OCR "Scan text" → Translate
     * (long-press). Lists every translatable language ([includeOff] prepends an "Off" row that yields a
     * null code); [currentCode] is marked with a trailing ✓; [onPicked] receives the chosen pluralLangCode
     * (or null for "Off").
     */
    fun pickLanguage(activity: Activity, currentCode: String?, includeOff: Boolean, onPicked: (String?) -> Unit) {
        val locales = TranslateController.getLocales()
        val names = ArrayList<CharSequence>(locales.size + 1)
        val codes = ArrayList<String?>(locales.size + 1)
        if (includeOff) {
            names.add(LanguageCode.getMyTitles(326))   // Off
            codes.add(null)
        }
        for (li in locales) {
            names.add(capitalFirst(localeName(li)))
            codes.add(li.pluralLangCode)
        }

        // Mark the current target (or "Off") with a trailing ✓.
        var checked = if (includeOff) 0 else -1
        if (currentCode != null) {
            val base = currentCode.substringBefore("_")
            for (i in codes.indices) {
                val c = codes[i] ?: continue
                if (c == currentCode || c == base) { checked = i; break }
            }
        }
        if (checked >= 0) names[checked] = names[checked].toString() + CHECK

        AlertDialog.Builder(activity)
            .setTitle(LanguageCode.getMyTitles(324))
            .setItems(names.toTypedArray()) { _, which -> onPicked(codes[which]) }
            .show()
    }

    private fun localeName(li: LocaleController.LocaleInfo): String {
        val n = li.name
        return if (!n.isNullOrBlank()) n else (li.nameEnglish ?: li.pluralLangCode ?: "")
    }

    private fun capitalFirst(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        return text.substring(0, 1).uppercase() + text.substring(1)
    }
}
