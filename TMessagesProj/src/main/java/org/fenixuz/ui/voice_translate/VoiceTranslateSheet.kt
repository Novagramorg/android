package org.fenixuz.ui.voice_translate

import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.fenixuz.utils.LanguageCode
import org.fenixuz.utils.VoiceDictation
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.AndroidUtilities.dp
import org.telegram.messenger.FileLog
import org.telegram.messenger.TranslateController
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.BottomSheet
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper
import org.telegram.ui.Components.StickerImageView
import java.util.Locale

/**
 * Novagram voice-translate config sheet — the entry point pro opened from the chat menu.
 *
 * Picks the two languages of the voice-translate feature: the SPEAK language (what you dictate in,
 * fed to the on-device recogniser) and the TRANSLATE-TO language (Telegram's free translate; "Off"
 * means dictation only). Both are stored device-only by [VoiceDictation]. The "Start" button begins
 * dictation immediately (via [onStart]); the composer mic does the same with the saved languages.
 *
 * Cleaner than pro's `VoiceToTextTranslateBottomSheetDialog`: the language list is a plain themed
 * AlertDialog (not a hand-laid ActionBarPopupWindow), language names come straight from the system
 * locale, and there is no broken "en-EN" code juggling.
 */
class VoiceTranslateSheet(
    private val fragment: BaseFragment,
    private val onStart: Runnable
) : BottomSheet(fragment.parentActivity, false) {

    private val ctx = fragment.parentActivity
    private val fromValue: TextView
    private val toValue: TextView

    override fun canDismissWithSwipe(): Boolean = false

    init {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val sticker = StickerImageView(ctx, currentAccount).apply {
            setStickerNum(2)
            imageReceiver.setAutoRepeat(1)
        }
        root.addView(sticker, LayoutHelper.createLinear(144, 144, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0))

        val title = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            typeface = AndroidUtilities.bold()
            text = LanguageCode.getMyTitles(308)
        }
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 21, 16, 21, 4))

        fromValue = addRow(root, LanguageCode.getMyTitles(313)) { pickFrom() }
        toValue = addRow(root, LanguageCode.getMyTitles(314)) { pickTo() }
        refreshFrom()
        refreshTo()

        val accent = Theme.getColor(Theme.key_featuredStickers_addButton)
        val start = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            typeface = AndroidUtilities.bold()
            text = LanguageCode.getMyTitles(316)
            setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                dp(8f), accent, Theme.getColor(Theme.key_featuredStickers_addButtonPressed)
            )
            setOnClickListener {
                dismiss()
                onStart.run()
            }
        }
        root.addView(start, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 22, 16, 16))

        val scroll = ScrollView(ctx)
        scroll.addView(root)
        setCustomView(scroll)
    }

    private fun addRow(parent: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val lbl = TextView(ctx).apply {
            text = label
            setTextColor(Theme.getColor(Theme.key_dialogTextBlack))
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        }
        row.addView(lbl, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL))

        val accent = Theme.getColor(Theme.key_featuredStickers_addButton)
        val value = TextView(ctx).apply {
            setTextColor(accent)
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            typeface = AndroidUtilities.bold()
            // Long language names ("Traditional Chinese (Hong Kong)") must not push the button off the
            // right edge on narrow phones — cap the width and ellipsize; the label keeps its weight=1 space.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = AndroidUtilities.displaySize.x / 2
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = Theme.createSimpleSelectorRoundRectDrawable(
                dp(6f), Theme.multAlpha(accent, 0.12f), Theme.multAlpha(accent, 0.22f)
            )
            setOnClickListener { onClick() }
        }
        row.addView(value, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL))

        parent.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 21, 14, 21, 0))
        return value
    }

    private fun refreshFrom() {
        val code = VoiceDictation.getSpeakLang(ctx)
        fromValue.text = if (code.isBlank()) LanguageCode.getMyTitles(312) else langName(code)
    }

    private fun refreshTo() {
        val code = VoiceDictation.getTranslateLang(ctx)
        toValue.text = if (code.isBlank()) LanguageCode.getMyTitles(315) else langName(code)
    }

    private fun pickFrom() {
        // First option = device default.
        val (labels, codes) = buildLanguageList(LanguageCode.getMyTitles(312))
        showPicker(LanguageCode.getMyTitles(313), labels, codes, VoiceDictation.getSpeakLang(ctx)) { picked ->
            VoiceDictation.setSpeakLang(ctx, picked)
            refreshFrom()
        }
    }

    private fun pickTo() {
        // First option = Off (no translation).
        val (labels, codes) = buildLanguageList(LanguageCode.getMyTitles(315))
        showPicker(LanguageCode.getMyTitles(314), labels, codes, VoiceDictation.getTranslateLang(ctx)) { picked ->
            VoiceDictation.setTranslateLang(ctx, picked)
            refreshTo()
        }
    }

    private fun buildLanguageList(firstLabel: String): Pair<ArrayList<CharSequence>, ArrayList<String>> {
        val labels = ArrayList<CharSequence>()
        val codes = ArrayList<String>()
        labels.add(firstLabel)
        codes.add("")
        try {
            val seen = HashSet<String>()
            for (li in TranslateController.getLocales()) {
                val code = li.pluralLangCode ?: continue
                if (code.isBlank() || code == "auto" || code == TranslateController.UNKNOWN_LANGUAGE || !seen.add(code)) continue
                labels.add(langName(code))
                codes.add(code)
            }
        } catch (e: Exception) {
            FileLog.e(e)
        }
        return labels to codes
    }

    private fun showPicker(
        dialogTitle: String,
        labels: ArrayList<CharSequence>,
        codes: ArrayList<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val items = arrayOfNulls<CharSequence>(labels.size)
        for (i in labels.indices) {
            items[i] = if (codes[i] == current) "✓  ${labels[i]}" else labels[i]
        }
        val builder = AlertDialog.Builder(ctx)
        builder.setTitle(dialogTitle)
        builder.setItems(items) { _, which -> onPick(codes[which]) }
        builder.setNegativeButton(LanguageCode.getMyTitles(80), null)
        try {
            builder.show()
        } catch (e: Exception) {
            FileLog.e(e)
        }
    }

    private fun langName(code: String): String = try {
        val name = Locale.forLanguageTag(code.replace("_", "-")).getDisplayName(Locale.getDefault())
        if (name.isNullOrBlank()) code else name.replaceFirstChar { it.uppercase() }
    } catch (e: Exception) {
        code
    }
}
