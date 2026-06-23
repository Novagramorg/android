package org.fenixuz.ui.text_style_dialog

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.ChatActivityEnterView
import org.telegram.ui.Components.TextStyleSpan
import org.telegram.ui.Components.TypefaceSpan

/**
 * Default text style applied to a message right before it is sent.
 * The chosen style is stored once (SharedPreferences) and applied in O(1) on send only —
 * there is no per-keystroke work, so it does not affect typing performance or memory.
 */
object TextStyleDialog {

    private const val TEXT_BOLD = 50
    private const val TEXT_ITALIC = 51
    private const val TEXT_MONO = 52
    private const val TEXT_LINK = 53
    private const val TEXT_REGULAR = 54
    private const val TEXT_STRIKE = 55
    private const val TEXT_UNDERLINE = 56
    private const val TEXT_SPOILER = 57
    private const val TEXT_QUOTE = 58
    private const val NONE = -1

    private val prefs =
        ApplicationLoader.applicationContext.getSharedPreferences("db", Context.MODE_PRIVATE)

    var selectedStyle = prefs.getInt("selected_style", NONE)
        private set

    private fun saveSelectedStyle(style: Int) {
        selectedStyle = style
        prefs.edit().putInt("selected_style", style).apply()
    }

    /** Apply the chosen style to the whole text. Called once per send — cheap. */
    fun changeTextStyle(enterView: ChatActivityEnterView?, text: String) {
        if (selectedStyle == NONE) return
        val field = enterView?.editField ?: return
        // Reset any leftover formatting first, then apply the selected style.
        field.setSelectionOverride(0, text.length)
        field.makeSelectedRegular()
        if (selectedStyle == TEXT_REGULAR) return
        field.setSelectionOverride(0, text.length)
        when (selectedStyle) {
            TEXT_BOLD -> field.makeSelectedBold()
            TEXT_ITALIC -> field.makeSelectedItalic()
            TEXT_SPOILER -> field.makeSelectedSpoiler()
            TEXT_QUOTE -> field.makeSelectedQuote()
            TEXT_MONO -> field.makeSelectedMono()
            TEXT_STRIKE -> field.makeSelectedStrike()
            TEXT_UNDERLINE -> field.makeSelectedUnderline()
            TEXT_LINK -> field.makeSelectedUrl()
        }
    }

    /** The radio styles, paired with their style codes (order = radio index). */
    private val radioStyles = intArrayOf(
        TEXT_REGULAR, TEXT_SPOILER, TEXT_BOLD, TEXT_ITALIC,
        TEXT_MONO, TEXT_STRIKE, TEXT_UNDERLINE, TEXT_QUOTE
    )

    fun textStyleDialog(context: Context) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val radioGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }

        val labels = buildStyledLabels()
        val checkedIndex = radioStyles.indexOf(selectedStyle)
        labels.forEachIndexed { index, label ->
            val radioButton = RadioButton(context).apply {
                text = label
                setTextColor(Theme.getColor(Theme.key_chats_menuItemText))
                setPadding(20, 30, 0, 30)
                id = index
                isChecked = index == checkedIndex
            }
            radioGroup.addView(radioButton)
        }
        layout.addView(radioGroup)

        val builder = AlertDialog.Builder(context)
        builder.setTitle(LanguageCode.getMyTitles(166))
        builder.setMessage(LanguageCode.getMyTitles(175))
        builder.setView(layout)
        builder.setPositiveButton(LanguageCode.getMyTitles(176)) { _, _ -> }
        builder.setNegativeButton(LanguageCode.getMyTitles(177)) { dialog, _ -> dialog.dismiss() }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selectedId = radioGroup.checkedRadioButtonId
                if (selectedId in radioStyles.indices) {
                    saveSelectedStyle(radioStyles[selectedId])
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun buildStyledLabels(): List<CharSequence> {
        fun styled(titleCode: Int, vararg spans: Any): CharSequence {
            val s = SpannableStringBuilder(LanguageCode.getMyTitles(titleCode))
            spans.forEach { s.setSpan(it, 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            return s
        }

        val strikeRun = TextStyleSpan.TextStyleRun().apply { flags = flags or TextStyleSpan.FLAG_STYLE_STRIKE }
        val underlineRun = TextStyleSpan.TextStyleRun().apply { flags = flags or TextStyleSpan.FLAG_STYLE_UNDERLINE }

        return listOf(
            styled(167),                                                              // Regular
            styled(168),                                                              // Spoiler
            styled(169, TypefaceSpan(AndroidUtilities.bold())),                       // Bold
            styled(170, TypefaceSpan(AndroidUtilities.getTypeface("fonts/ritalic.ttf"))), // Italic
            styled(171, TypefaceSpan(Typeface.MONOSPACE)),                            // Mono
            styled(172, TextStyleSpan(strikeRun)),                                    // Strikethrough
            styled(173, TextStyleSpan(underlineRun)),                                 // Underline
            styled(174)                                                               // Quote
        )
    }
}
