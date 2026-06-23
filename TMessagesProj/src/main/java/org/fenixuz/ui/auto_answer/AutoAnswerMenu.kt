package org.fenixuz.ui.auto_answer

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import org.fenixuz.utils.LanguageCode
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.ActionBar.ActionBar
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.LayoutHelper

/**
 * Simple plain-text editor for the auto-answer reply.
 * No send/done button — you type, it auto-saves; the text is used as the auto-reply.
 */
class AutoAnswerMenu : BaseFragment() {

    private var editText: EditText? = null

    override fun createView(context: Context): View {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back)
        actionBar.setAllowOverlayTitle(true)
        actionBar.setTitle(LanguageCode.getMyTitles(228))
        actionBar.setActionBarMenuOnItemClick(object : ActionBar.ActionBarMenuOnItemClick() {
            override fun onItemClick(id: Int) {
                if (id == -1) {
                    finishFragment()
                }
            }
        })

        val root = FrameLayout(context)
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite))

        val et = EditText(context)
        et.background = null
        et.gravity = Gravity.TOP or Gravity.START
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText))
        et.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText))
        et.hint = LanguageCode.getMyTitles(238)
        et.textSize = 16f
        et.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.isSingleLine = false
        et.setPadding(
            AndroidUtilities.dp(16f),
            AndroidUtilities.dp(16f),
            AndroidUtilities.dp(16f),
            AndroidUtilities.dp(16f)
        )
        et.setText(AutoAnswer.getAutoAnswerText())
        et.setSelection(et.text?.length ?: 0)
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Auto-save as you type.
                AutoAnswer.saveAutoAnswerText(s?.toString() ?: "")
            }
        })
        editText = et

        root.addView(
            et,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT.toFloat())
        )

        fragmentView = root
        return root
    }

    // Match the bottom system navigation bar to the white page (it defaults to grey) — same as Telegram's
    // own white screens, so there's no grey seam at the bottom.
    override fun getNavigationBarColor(): Int = Theme.getColor(Theme.key_windowBackgroundWhite)

    override fun onResume() {
        super.onResume()
        editText?.requestFocus()
        AndroidUtilities.showKeyboard(editText)
    }

    override fun onPause() {
        super.onPause()
        editText?.let { AutoAnswer.saveAutoAnswerText(it.text?.toString() ?: "") }
    }
}
